import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import * as fs from 'fs';
import * as os from 'os';
import * as path from 'path';
import { execFileSync } from 'child_process';

// Mock integrationRepository before importing bomService (table may not exist in test DB)
vi.mock('../../src/integrationRepository', () => ({
    findIntegrationByTypeAndOrg: vi.fn().mockResolvedValue(null),
    upsertIntegration: vi.fn(),
    createSecret: vi.fn(),
    findSecretById: vi.fn(),
    updateSecret: vi.fn(),
    deleteSecret: vi.fn(),
    deleteIntegration: vi.fn(),
}));

import * as BomService from '../../src/bomService';
import validateBom from '../../src/validateBom';
import { pool } from '../../src/utils';
import { clearMockOciStorage } from '../../src/services/oci';
import { TEST_ORG_UUID, generateSerialNumber } from '../helpers';
import { DANGLING_REFS_DROPPED_PROPERTY } from '../../src/services/bom/bomProcessingService';
import {
    ORG, COMMIT, UPLOAD_DIGEST, RELEASE_DIGEST, SCE_ROOT, DELIVERABLE_ROOT, RELEASE_ROOT, RELEASE_NODE, PRODUCT_ROOT,
    COMPONENT_LICENSE, COMPONENT_LICENSE_URL, LICENSE_MERGE_VERSION, PRODUCT_LICENSE, PLACEHOLDER_REF, EXTERNAL_PLACEHOLDER_REF,
    RELEASE_ATTACHED_ROOT, sceUpload, deliverableUpload, releaseUpload, releaseAttachedUpload, releaseNodeUpload,
    productMergeRequest, node, storeUpload, storeMerge, structureCheck, expectNoOrphansAndRefsResolve
} from '../releaseNodeFacts.fixture';

/**
 * SCORE-64 (T-3; ADR-2 to ADR-4): under FLATTEN_UNDER_NEW_ROOT merge-boms drops the input roots'
 * own dependency entries and names no root, so every input root that stays a node of the export
 * (the release's SCE- and deliverable-attached roots, an inbound deliverable's) was an orphan.
 * rebom's linkUnfoldedRoots makes each a dependency of the merged root, after the libraries, in
 * input order. Real `rearm bomutils merge-boms` and `rearm bomutils score`, real storage path, the
 * documents served as stored.
 */

const LIBRARIES = ['pkg:npm/lib-a@1.0.0', 'pkg:npm/lib-b@1.0.0', 'pkg:npm/lib-c@1.0.0', 'pkg:npm/lib-g@1.0.0'];
const COMPONENT_FACTS = { supplierName: ORG, hashes: [{ alg: 'SHA-256', content: RELEASE_DIGEST }], licenseExpression: COMPONENT_LICENSE, licenseUrl: COMPONENT_LICENSE_URL };
/** The digest an inbound deliverable's SBOM was uploaded under (another artifact than UPLOAD_DIGEST's). */
const INBOUND_DIGEST = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855';

function property(list: any[] | undefined, name: string): any[] {
    return (list || []).filter((p: any) => p?.name === name);
}

/**
 * The stored row's root dependsOn: the pass appends in input order (the unit tests pin it), and the
 * store sorts every dependsOn list (bomAddService's de-dup key sorts it in place), so the served
 * row reads sorted.
 */
const sorted = (refs: string[]) => [...refs].sort();

/** The merged root's dependency entries. */
function rootEntries(bom: any): any[] {
    return (bom.dependencies || []).filter((d: any) => d.ref === bom.metadata.component['bom-ref']);
}

function rootEntry(bom: any): any {
    const entries = rootEntries(bom);
    expect(entries).toHaveLength(1);
    return entries[0];
}

/** An inbound deliverable's SBOM: a base image with its own library and a full dependency graph. */
function inboundUpload(): any {
    const root = { type: 'container', name: 'base-image', version: '3.0.0', purl: 'pkg:oci/base-image@3.0.0', 'bom-ref': 'pkg:oci/base-image@3.0.0' };
    const lib = { type: 'library', name: 'lib-h', version: '1.0.0', purl: 'pkg:npm/lib-h@1.0.0', 'bom-ref': 'pkg:npm/lib-h@1.0.0',
        supplier: { name: 'lib-h maintainers' }, hashes: [{ alg: 'SHA-256', content: '7'.repeat(64) }], licenses: [{ license: { id: 'MIT' } }] };
    return {
        bomFormat: 'CycloneDX', specVersion: '1.6', serialNumber: generateSerialNumber(), version: 1,
        metadata: { timestamp: '2026-10-23T00:00:00Z', component: root },
        components: [lib],
        dependencies: [{ ref: root['bom-ref'], dependsOn: [lib['bom-ref']] }, { ref: lib['bom-ref'], dependsOn: [] }]
    };
}

/** sceUpload() with a file component the root depends on. */
function sceUploadWithFile(): any {
    const doc = sceUpload();
    doc.components.push({ type: 'file', name: '/opt/tool', 'bom-ref': 'file-tool' });
    doc.dependencies[0].dependsOn.push('file-tool');
    doc.dependencies.push({ ref: 'file-tool', dependsOn: [] });
    return doc;
}

function score(bom: any): any {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), 'score64-'));
    try {
        const file = path.join(dir, 'bom.json');
        fs.writeFileSync(file, JSON.stringify(bom));
        return JSON.parse(execFileSync('rearm', ['bomutils', 'score', '--profile', 'cisa-2026', '--format', 'json', '--infile', file], { encoding: 'utf8' }));
    } finally {
        fs.rmSync(dir, { recursive: true, force: true });
    }
}

// The image pins a CLI with `bomutils score` for linux/amd64 only (SCORE-4 design 3.7).
describe.skipIf(process.arch !== 'x64')('the component release merge links every unfolded input root under the merged root (SCORE-64)', () => {
    const created: string[] = [];

    beforeEach(() => {
        clearMockOciStorage();
    });

    afterEach(async () => {
        vi.restoreAllMocks();
        if (created.length > 0) {
            await pool.query('DELETE FROM rebom.boms WHERE uuid = ANY($1::uuid[])', [created]);
            created.length = 0;
        }
    });

    type Uploads = { sce: string, deliverable: string, release: string };

    const SCE_OPTIONS = { name: 'facts-api', version: '1.0.0', belongsTo: 'SCE', hash: COMMIT, supplierName: ORG };
    const DELIVERABLE_OPTIONS = { name: 'facts-api', version: '1.0.0', belongsTo: 'DELIVERABLE', hash: 'sha256:' + UPLOAD_DIGEST, supplierName: ORG };

    async function uploads(sceDoc: any = sceUpload()): Promise<Uploads> {
        const sce = await storeUpload(created, sceDoc, SCE_OPTIONS);
        const deliverable = await storeUpload(created, deliverableUpload(), DELIVERABLE_OPTIONS);
        const release = await storeUpload(created, releaseAttachedUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'RELEASE', supplierName: ORG });
        return { sce, deliverable, release };
    }

    /** The component release merge (FLATTEN) as rearm-saas asks for it, with `extra` merge-only fields. */
    async function componentMerge(ids: string[], extra: any = {}, overrides: any = {}): Promise<{ id: string, bom: any }> {
        const id = await storeMerge(created, ids,
            { name: 'facts-api', version: '1.0.0', rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT', mergeVersion: LICENSE_MERGE_VERSION, ...overrides },
            { releaseNode: COMPONENT_FACTS, ...extra });
        return { id, bom: await BomService.findBomObjectById(id, TEST_ORG_UUID) };
    }

    /** The merge of (i): the RELEASE upload in releaseRootIds, the SCE and deliverable ones in attachedRootIds. */
    async function listedMerge(u: Uploads, overrides: any = {}): Promise<{ id: string, bom: any }> {
        return componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release], attachedRootIds: [u.sce, u.deliverable] }, overrides);
    }

    async function productMerge(ids: string[]): Promise<{ id: string, bom: any }> {
        const request = productMergeRequest(true, PRODUCT_LICENSE);
        const id = await storeMerge(created, ids, request.overrides, request.extra);
        return { id, bom: await BomService.findBomObjectById(id, TEST_ORG_UUID) };
    }

    it('(i) names the SCE and deliverable roots beside the four libraries, never the folded root or itself; no-orphans passes', async () => {
        const u = await uploads();
        const { id, bom } = await listedMerge(u);

        expect(bom.metadata.component['bom-ref']).toBe(RELEASE_NODE);
        expect(rootEntry(bom).dependsOn).toStrictEqual(sorted([...LIBRARIES, SCE_ROOT, DELIVERABLE_ROOT]));
        expect(rootEntry(bom).dependsOn).not.toContain(RELEASE_ATTACHED_ROOT);
        expect(rootEntry(bom).dependsOn).not.toContain(RELEASE_NODE);
        expect(node(bom, SCE_ROOT)).toBeDefined();
        expect(node(bom, DELIVERABLE_ROOT)).toBeDefined();
        expect(node(bom, RELEASE_ATTACHED_ROOT)).toBeUndefined();
        expect(property(bom.metadata.properties, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
        expectNoOrphansAndRefsResolve(score(bom));
        await expect(validateBom(bom)).resolves.toBeTruthy();

        const again = await BomService.findBomObjectById(id, TEST_ORG_UUID);
        expect(JSON.stringify(again)).toBe(JSON.stringify(bom));
    });

    it('(ii) links the same roots when attachedRootIds is dropped: the pass reads no list', async () => {
        const u = await uploads();
        const { bom: listed } = await listedMerge(u);
        const { bom: unlisted } = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });

        expect(unlisted.dependencies).toStrictEqual(listed.dependencies);
    });

    it('(ii-b) with neither list the RELEASE-attached root is not folded and is linked too', async () => {
        const u = await uploads();
        const { bom } = await componentMerge([u.sce, u.deliverable, u.release]);

        expect(node(bom, RELEASE_ATTACHED_ROOT)).toBeDefined();
        expect(rootEntry(bom).dependsOn).toStrictEqual(sorted([...LIBRARIES, SCE_ROOT, DELIVERABLE_ROOT, RELEASE_ATTACHED_ROOT]));
        expect(structureCheck(score(bom), 'structure.no-orphans').failing).toStrictEqual([]);
    });

    it('(iii) names the root of an inbound deliverable SBOM that is in no list', async () => {
        const u = await uploads();
        const inbound = await storeUpload(created, inboundUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'DELIVERABLE', hash: 'sha256:' + INBOUND_DIGEST, supplierName: ORG });
        const inboundRoot = (await BomService.findBomObjectById(inbound, TEST_ORG_UUID)).metadata.component['bom-ref'];
        expect(inboundRoot).toContain('pkg:oci/base-image@');

        const { bom } = await componentMerge([u.sce, u.deliverable, u.release, inbound], { releaseRootIds: [u.release], attachedRootIds: [u.sce, u.deliverable] });

        expect(node(bom, inboundRoot)).toBeDefined();
        expect(rootEntry(bom).dependsOn).toStrictEqual(sorted([...LIBRARIES, 'pkg:npm/lib-h@1.0.0', SCE_ROOT, DELIVERABLE_ROOT, inboundRoot]));
        expectNoOrphansAndRefsResolve(score(bom));
    });

    it('(iv) names both roots under HIERARCHICAL, each top-level with its children nested', async () => {
        const u = await uploads();
        const { bom } = await listedMerge(u, { structure: 'HIERARCHICAL' });

        expect(node(bom, SCE_ROOT)?.components?.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/lib-a@1.0.0', 'pkg:npm/lib-b@1.0.0']);
        expect(node(bom, DELIVERABLE_ROOT)?.components?.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/lib-c@1.0.0']);
        expect(rootEntry(bom).dependsOn).toEqual(expect.arrayContaining([SCE_ROOT, DELIVERABLE_ROOT]));
        expectNoOrphansAndRefsResolve(score(bom));
        await expect(validateBom(bom)).resolves.toBeTruthy();
    });

    it('(v) names both roots in the top-level-only merge', async () => {
        const u = await uploads();
        const { bom } = await listedMerge(u, { tldOnly: true });

        expect(rootEntry(bom).dependsOn).toEqual(expect.arrayContaining([SCE_ROOT, DELIVERABLE_ROOT]));
        expect(structureCheck(score(bom), 'structure.no-orphans').failing).toStrictEqual([]);
    });

    it('(vi) names both roots with the file filter on, a file component under the SCE root left out, and drops no ref', async () => {
        const u = await uploads(sceUploadWithFile());
        const { bom } = await listedMerge(u, { excludeFileComponents: true });

        expect(JSON.stringify(bom)).not.toContain('file-tool');
        expect(rootEntry(bom).dependsOn).toEqual(expect.arrayContaining([SCE_ROOT, DELIVERABLE_ROOT]));
        expect(property(bom.metadata.properties, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
        expectNoOrphansAndRefsResolve(score(bom));
    });

    it('(vii) leaves the product root entry as it was, carries the component row entry whole, and no-orphans passes on the product', async () => {
        const u = await uploads();
        const component = await listedMerge(u);
        const web = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });

        const { bom } = await productMerge([component.id, web]);

        expect(bom.metadata.component['bom-ref']).toBe(PRODUCT_ROOT);
        expect(rootEntries(bom)).toStrictEqual([{ ref: PRODUCT_ROOT, dependsOn: [RELEASE_NODE, RELEASE_ROOT, PLACEHOLDER_REF, EXTERNAL_PLACEHOLDER_REF] }]);
        const row = bom.dependencies.filter((d: any) => d.ref === RELEASE_NODE);
        expect(row).toHaveLength(1);
        expect(row[0].dependsOn).toContain(SCE_ROOT);
        expect(row[0].dependsOn).toContain(DELIVERABLE_ROOT);
        expectNoOrphansAndRefsResolve(score(bom));
    });

    it('(viii) never names the merged root under itself when an upload root is the release node (the identifier case)', async () => {
        const sce = await storeUpload(created, sceUpload(), SCE_OPTIONS);
        const copy = await storeUpload(created, releaseNodeUpload([{ license: { id: 'MIT' } }]), { name: 'facts-api', version: '1.0.0', purl: RELEASE_NODE });

        const { bom } = await componentMerge([sce, copy], { releaseRootIds: [copy], attachedRootIds: [sce] });

        expect(bom.metadata.component['bom-ref']).toBe(RELEASE_NODE);
        for (const dep of bom.dependencies) expect(dep.dependsOn || []).not.toContain(RELEASE_NODE);
        expect(rootEntry(bom).dependsOn).toContain(SCE_ROOT);
        expect(structureCheck(score(bom), 'structure.no-orphans').failing).toStrictEqual([]);
    });
});
