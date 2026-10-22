import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';

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

import { PackageURL } from 'packageurl-js';
import * as BomService from '../../src/bomService';
import validateBom from '../../src/validateBom';
import { pool } from '../../src/utils';
import { clearMockOciStorage } from '../../src/services/oci';
import { TEST_ORG_UUID } from '../helpers';
import { logger } from '../../src/logger';
import {
    FOLDED_ROOT_PROPERTY,
    RELEASE_ROOTS_FOLDED_PROPERTY,
    BOM_REFS_DEDUPLICATED_PROPERTY
} from '../../src/services/bom/bomProcessingService';
import {
    ORG, COMMIT, UPLOAD_DIGEST, RELEASE_DIGEST, SCE_ROOT, DELIVERABLE_ROOT, RELEASE_ROOT, RELEASE_NODE, PRODUCT_LICENSE,
    COMPONENT_LICENSE, COMPONENT_LICENSE_URL, LICENSE_MERGE_VERSION,
    sceUpload, deliverableUpload, releaseUpload, releaseNodeUpload, releaseAttachedUpload, productMergeRequest, node,
    storeUpload, storeMerge, RELEASE_ATTACHED_ROOT, RELEASE_ATTACHED_WEBSITE
} from '../releaseNodeFacts.fixture';

/**
 * SCORE-57 (T-5, T-6, T-7; ADR-2 to ADR-5, ADR-8, ADR-11): the root of an SBOM uploaded against
 * the release itself folds into the merged root of the component release merge, when rearm-saas
 * lists that upload in releaseRootIds, and nowhere else. Real `rearm bomutils merge-boms`, real
 * storage path, the documents served as stored.
 */

/** The same upload built without a generator purl: its stored root is the generic purl with the qualifier. */
const RELEASE_ATTACHED_ROOT_2 = 'pkg:generic/Facts%20Org/facts-api@1.0.0?belongsto=RELEASE';
/** A preferred identifier for the product (T-6). */
const PRODUCT_ID = 'pkg:maven/org.facts/facts-product@3.0.0';
const PRODUCT_DIGEST = '2c26b46b68ffc68ff99b453c1d30413413422d706483bfa0f98a5e886266e7ae';
const COMPONENT_OWN_DIGEST = 'fcde2b2edba56bf408601fb721fe9b5c338d10ee429ea04fae5511b68fbf8fb9';
const SAME_NAME_ROW_ROOT = 'pkg:generic/Facts%20Org/facts-product@3.0.0?belongsto=RELEASE';

const NAMED_LICENSE = [{ license: { name: COMPONENT_LICENSE, url: COMPONENT_LICENSE_URL } }];

function decoded(purl: string): string {
    const p = PackageURL.fromString(purl);
    return `${p.name}@${p.version}`;
}

function property(list: any[] | undefined, name: string): any[] {
    return (list || []).filter((p: any) => p?.name === name);
}

/** Every component at any depth, metadata.component first. */
function allComponents(bom: any): any[] {
    const out: any[] = [];
    const visit = (c: any) => {
        if (!c || typeof c !== 'object') return;
        out.push(c);
        (c.components || []).forEach(visit);
    };
    visit(bom.metadata?.component);
    (bom.components || []).forEach(visit);
    return out;
}

function hasBelongsToRelease(purl: unknown): boolean {
    if (typeof purl !== 'string') return false;
    try {
        const q: any = PackageURL.fromString(purl).qualifiers || {};
        return Object.keys(q).some(k => k.toLowerCase() === 'belongsto' && String(q[k]).toUpperCase() === 'RELEASE');
    } catch {
        return false;
    }
}

function rootEntry(bom: any): any {
    return (bom.dependencies || []).find((d: any) => d.ref === bom.metadata.component['bom-ref']);
}

describe('the merged release export folds the uploaded root that describes the release (SCORE-57)', () => {
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

    async function uploads(releaseDoc: any = releaseAttachedUpload(), releaseOverrides: any = {}): Promise<Uploads> {
        const sce = await storeUpload(created, sceUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'SCE', hash: COMMIT, supplierName: ORG });
        const deliverable = await storeUpload(created, deliverableUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'DELIVERABLE', hash: 'sha256:' + UPLOAD_DIGEST, supplierName: ORG });
        const release = await storeUpload(created, releaseDoc, { name: 'facts-api', version: '1.0.0', belongsTo: 'RELEASE', supplierName: ORG, ...releaseOverrides });
        return { sce, deliverable, release };
    }

    const COMPONENT_FACTS = { releaseNode: { supplierName: ORG, hashes: [{ alg: 'SHA-256', content: RELEASE_DIGEST }], licenseExpression: COMPONENT_LICENSE, licenseUrl: COMPONENT_LICENSE_URL } };

    /** The component release merge (FLATTEN) as rearm-saas asks for it, with `extra` merge-only fields. */
    async function componentMerge(ids: string[], extra: any = {}, overrides: any = {}): Promise<{ id: string, bom: any }> {
        const id = await storeMerge(created, ids,
            { name: 'facts-api', version: '1.0.0', rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT', mergeVersion: LICENSE_MERGE_VERSION, ...overrides },
            { ...COMPONENT_FACTS, ...extra });
        return { id, bom: await BomService.findBomObjectById(id, TEST_ORG_UUID) };
    }

    describe('the component release merge (T-5)', () => {
        it('serves one node for the release, the merged root, with the upload description and the declared facts filling the rest', async () => {
            const u = await uploads();
            const { id, bom } = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });

            const ofRelease = allComponents(bom).filter(c => typeof c.purl === 'string' && decoded(c.purl) === 'facts-api@1.0.0');
            expect(ofRelease).toHaveLength(1);
            expect(ofRelease[0]).toBe(bom.metadata.component);
            const root = bom.metadata.component;
            expect(root.purl).toBe(RELEASE_NODE);
            expect(root.supplier).toStrictEqual({ name: ORG });
            expect(root.licenses).toStrictEqual(NAMED_LICENSE);
            expect(root.hashes).toStrictEqual([{ alg: 'SHA-256', content: RELEASE_DIGEST }]);
            expect(root.description).toBe('The facts API');
            expect(root.externalReferences).toContainEqual(RELEASE_ATTACHED_WEBSITE);
            expect(property(root.properties, FOLDED_ROOT_PROPERTY)).toStrictEqual([{ name: FOLDED_ROOT_PROPERTY, value: RELEASE_ATTACHED_ROOT }]);
            expect(allComponents(bom).filter(c => c !== root && hasBelongsToRelease(c.purl))).toStrictEqual([]);
            expect(node(bom, SCE_ROOT)).toBeDefined();
            expect(node(bom, DELIVERABLE_ROOT)).toBeDefined();
            expect(node(bom, SCE_ROOT).pedigree).toStrictEqual({ commits: [{ uid: COMMIT }] });
            expect(rootEntry(bom).dependsOn).toContain('pkg:npm/lib-g@1.0.0');
            expect(rootEntry(bom).dependsOn).not.toContain(RELEASE_NODE);
            expect(JSON.stringify(bom.dependencies)).not.toContain(RELEASE_ATTACHED_ROOT);
            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([{ name: RELEASE_ROOTS_FOLDED_PROPERTY, value: '1' }]);
            await expect(validateBom(bom)).resolves.toBeTruthy();
            const again = await BomService.findBomObjectById(id, TEST_ORG_UUID);
            expect(JSON.stringify(again)).toBe(JSON.stringify(bom));
        });

        it('folds nothing when the request lists nothing: the upload root stays beside the root, which carries the declared facts only', async () => {
            const u = await uploads();
            const { bom } = await componentMerge([u.sce, u.deliverable, u.release]);

            expect(node(bom, RELEASE_ATTACHED_ROOT)).toBeDefined();
            expect(bom.metadata.component).not.toHaveProperty('description');
            expect(bom.metadata.component.licenses).toStrictEqual(NAMED_LICENSE);
            expect(property(bom.metadata.component.properties, FOLDED_ROOT_PROPERTY)).toStrictEqual([]);
            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([]);
        });

        it('folds nothing when the list names the SCE upload instead, and logs one line naming the kind', async () => {
            const info = vi.spyOn(logger, 'info');
            const u = await uploads();
            const { bom } = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.sce] });

            expect(node(bom, RELEASE_ATTACHED_ROOT)).toBeDefined();
            expect(node(bom, SCE_ROOT)).toBeDefined();
            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([]);
            const lines = info.mock.calls.filter((c: any[]) => typeof c[1] === 'string' && c[1].includes('attached to another kind'));
            expect(lines).toHaveLength(1);
            expect(String(lines[0][0].belongsTo).toUpperCase()).toBe('SCE');
        });

        it('puts the upload own expression and supplier on the release node, not the declaration (ADR-10 (i))', async () => {
            const doc = releaseAttachedUpload();
            doc.metadata.component.licenses = [{ expression: 'BSD-3-Clause OR MIT' }];
            doc.metadata.component.supplier = { name: 'S' };
            const u = await uploads(doc);
            const { bom } = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });

            expect(bom.metadata.component.licenses).toStrictEqual([{ expression: 'BSD-3-Clause OR MIT' }]);
            expect(bom.metadata.component.supplier).toStrictEqual({ name: 'S' });
            expect(bom.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: RELEASE_DIGEST }]);
        });

        it('keeps an upload NOASSERTION on the release node', async () => {
            const doc = releaseAttachedUpload();
            doc.metadata.component.licenses = [{ expression: 'NOASSERTION' }];
            const u = await uploads(doc);
            const { bom } = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });

            expect(bom.metadata.component.licenses).toStrictEqual([{ expression: 'NOASSERTION' }]);
        });

        it('folds two listed RELEASE uploads into one node, records both and unions their hashes', async () => {
            const first = releaseAttachedUpload();
            first.metadata.component.hashes = [{ alg: 'SHA-512', content: 'e'.repeat(128) }];
            const second = releaseAttachedUpload();
            delete second.metadata.component.purl;
            delete second.metadata.component['bom-ref'];
            second.dependencies[0].ref = 'second-root';
            second.metadata.component['bom-ref'] = 'second-root';
            second.metadata.component.hashes = [{ alg: 'SHA-384', content: 'f'.repeat(96) }];
            const u = await uploads(first);
            const other = await storeUpload(created, second, { name: 'facts-api', version: '1.0.0', belongsTo: 'RELEASE', supplierName: ORG });

            const { bom } = await componentMerge([u.sce, u.deliverable, u.release, other], { releaseRootIds: [u.release, other] });

            expect(property(bom.metadata.component.properties, FOLDED_ROOT_PROPERTY).map((p: any) => p.value))
                .toStrictEqual([RELEASE_ATTACHED_ROOT, RELEASE_ATTACHED_ROOT_2]);
            expect(bom.metadata.component.hashes.map((h: any) => h.alg)).toStrictEqual(['SHA-512', 'SHA-384']);
            expect(allComponents(bom).filter(c => c !== bom.metadata.component && hasBelongsToRelease(c.purl))).toStrictEqual([]);
            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([{ name: RELEASE_ROOTS_FOLDED_PROPERTY, value: '2' }]);
        });

        // SCORE-62 ADR-8, T-5c: the folded node's children take its place at the top level.
        it('folds under HIERARCHICAL, the upload components at the top level in place of its root', async () => {
            const u = await uploads();
            const { bom } = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] }, { structure: 'HIERARCHICAL' });

            expect(property(bom.metadata.component.properties, FOLDED_ROOT_PROPERTY)).toHaveLength(1);
            expect(node(bom, 'pkg:npm/lib-g@1.0.0')).toBeDefined();
            expect((bom.metadata.component.components || []).map((c: any) => c['bom-ref'])).not.toContain('pkg:npm/lib-g@1.0.0');
            expect(node(bom, SCE_ROOT)?.components?.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/lib-a@1.0.0', 'pkg:npm/lib-b@1.0.0']);
            expect(node(bom, DELIVERABLE_ROOT)?.components?.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/lib-c@1.0.0']);
            expect(rootEntry(bom).dependsOn).toContain('pkg:npm/lib-g@1.0.0');
            expect(allComponents(bom).filter(c => c !== bom.metadata.component && hasBelongsToRelease(c.purl))).toStrictEqual([]);
            await expect(validateBom(bom)).resolves.toBeTruthy();
        });

        it('folds in the top-level-only merge', async () => {
            const u = await uploads();
            const { bom } = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] }, { tldOnly: true });

            expect(property(bom.metadata.component.properties, FOLDED_ROOT_PROPERTY)).toStrictEqual([{ name: FOLDED_ROOT_PROPERTY, value: RELEASE_ATTACHED_ROOT }]);
            expect(node(bom, RELEASE_ATTACHED_ROOT)).toBeUndefined();
            expect(bom.metadata.component.description).toBe('The facts API');
        });

        it('leaves the identifier case byte-identical to an unlisted merge and writes no fold record', async () => {
            const sce = await storeUpload(created, sceUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'SCE', hash: COMMIT, supplierName: ORG });
            const copy = await storeUpload(created, releaseNodeUpload([{ license: { id: 'MIT' } }]), { name: 'facts-api', version: '1.0.0', purl: RELEASE_NODE });

            const listed = await componentMerge([sce, copy], { releaseRootIds: [copy] });
            const unlisted = await componentMerge([sce, copy]);

            // the serial numbers, the producer bom-link that names the request's serial, and the timestamp are per document
            const strip = (b: any) => JSON.stringify({ ...b, serialNumber: undefined, metadata: { ...b.metadata, timestamp: undefined } })
                .replace(/urn:cdx:[0-9a-f-]+\/\d+/g, 'BOM-LINK');
            expect(strip(listed.bom)).toBe(strip(unlisted.bom));
            expect(property(listed.bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([]);
            expect(property(listed.bom.metadata.component.properties, FOLDED_ROOT_PROPERTY)).toStrictEqual([]);
        });
    });

    describe('products (T-6, ADR-8, F-1)', () => {
        async function productMerge(ids: string[], overrides: any = {}, extra: any = {}): Promise<{ id: string, bom: any }> {
            const request = productMergeRequest(true, PRODUCT_LICENSE);
            const id = await storeMerge(created, ids, { ...request.overrides, ...overrides }, { ...request.extra, ...extra });
            return { id, bom: await BomService.findBomObjectById(id, TEST_ORG_UUID) };
        }

        /** (ii): a component release named like the product, with its own RELEASE-attached upload, merged with belongsTo RELEASE. */
        async function sameNameComponentRow(): Promise<{ id: string, bom: any }> {
            const doc = releaseAttachedUpload();
            doc.metadata.component = {
                type: 'application', name: 'facts-product', version: '3.0.0', purl: 'pkg:npm/facts-product@3.0.0', 'bom-ref': 'pkg:npm/facts-product@3.0.0',
                supplier: { name: 'S-comp' }, licenses: [{ license: { id: 'MIT' } }], hashes: [{ alg: 'SHA-256', content: COMPONENT_OWN_DIGEST }]
            };
            doc.dependencies[0].ref = 'pkg:npm/facts-product@3.0.0';
            const upload = await storeUpload(created, doc, { name: 'facts-product', version: '3.0.0', belongsTo: 'RELEASE', supplierName: ORG });
            const id = await storeMerge(created, [upload],
                { name: 'facts-product', version: '3.0.0', rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT', belongsTo: 'RELEASE', mergeVersion: LICENSE_MERGE_VERSION },
                { releaseRootIds: [upload] });
            return { id, bom: await BomService.findBomObjectById(id, TEST_ORG_UUID) };
        }

        it('(i) carries the component folded node once and the other release root as its own node; nothing folds into the product', async () => {
            const u = await uploads();
            const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });
            const web = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });

            const { bom } = await productMerge([component.id, web]);

            const ofComponent = allComponents(bom).filter(c => typeof c.purl === 'string' && decoded(c.purl) === 'facts-api@1.0.0');
            expect(ofComponent).toHaveLength(1);
            expect(ofComponent[0].description).toBe('The facts API');
            expect(property(ofComponent[0].properties, FOLDED_ROOT_PROPERTY)).toStrictEqual([{ name: FOLDED_ROOT_PROPERTY, value: RELEASE_ATTACHED_ROOT }]);
            expect(node(bom, RELEASE_ROOT)).toBeDefined();
            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([]);
        });

        it('(ii) keeps a same-name, same-version component row as its own node under a product with an identifier (the F-1 shape)', async () => {
            const row = await sameNameComponentRow();
            expect(row.bom.metadata.component.purl).toBe(SAME_NAME_ROW_ROOT);
            expect(row.bom.metadata.component.supplier).toStrictEqual({ name: 'S-comp' });
            const u = await uploads();
            const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });

            const { bom } = await productMerge([row.id, component.id], { purl: PRODUCT_ID, belongsTo: 'RELEASE' },
                { releaseNode: { supplierName: ORG, hashes: [{ alg: 'SHA-256', content: PRODUCT_DIGEST }], licenseExpression: PRODUCT_LICENSE } });

            const root = bom.metadata.component;
            expect(root.purl).toBe(PRODUCT_ID);
            expect(root.supplier).toStrictEqual({ name: ORG });
            expect(root.licenses).toStrictEqual([{ license: { id: PRODUCT_LICENSE } }]);
            expect(root.hashes).toStrictEqual([{ alg: 'SHA-256', content: PRODUCT_DIGEST }]);
            expect(property(root.properties, FOLDED_ROOT_PROPERTY)).toStrictEqual([]);
            const same = node(bom, SAME_NAME_ROW_ROOT);
            expect(same).toBeDefined();
            expect(same.supplier).toStrictEqual({ name: 'S-comp' });
            expect(same.licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
            expect(same.hashes).toContainEqual({ alg: 'SHA-256', content: COMPONENT_OWN_DIGEST });
            expect(rootEntry(bom).dependsOn).toContain(SAME_NAME_ROW_ROOT);
            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([]);
        });

        it('(iii) folds the product own listed RELEASE-attached upload into the product root (the TEA shape)', async () => {
            const u = await uploads();
            const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });
            const ownDoc = releaseAttachedUpload();
            ownDoc.metadata.component = { type: 'application', name: 'facts-product', version: '3.0.0', purl: 'pkg:oci/facts-product@3.0.0', 'bom-ref': 'pkg:oci/facts-product@3.0.0', description: 'The facts product' };
            ownDoc.dependencies[0].ref = 'pkg:oci/facts-product@3.0.0';
            const own = await storeUpload(created, ownDoc, { name: 'facts-product', version: '3.0.0', belongsTo: 'RELEASE' });

            const { bom } = await productMerge([component.id, own], { purl: PRODUCT_ID }, { releaseRootIds: [own] });

            const root = bom.metadata.component;
            expect(root.purl).toBe(PRODUCT_ID);
            expect(root.description).toBe('The facts product');
            expect(property(root.properties, FOLDED_ROOT_PROPERTY)).toStrictEqual([{ name: FOLDED_ROOT_PROPERTY, value: 'pkg:oci/facts-product@3.0.0?belongsto=RELEASE' }]);
            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([{ name: RELEASE_ROOTS_FOLDED_PROPERTY, value: '1' }]);
            expect(rootEntry(bom).dependsOn).toContain(RELEASE_NODE);
            expect(rootEntry(bom).dependsOn).not.toContain(PRODUCT_ID);
            expect(JSON.stringify(bom.dependencies)).not.toContain('pkg:oci/facts-product@3.0.0?belongsto=RELEASE');
        });

        it('(iv) records the pre-existing SCORE-13 same-name collision as it is (SCORE-57 section 5, follow-up 4)', async () => {
            // Not this task's pass: without an identifier the product root and the same-name component
            // row's root are one purl, and the bom-ref de-dup folds the row into the product root. A fix
            // of the follow-up changes this case knowingly.
            const row = await sameNameComponentRow();
            const u = await uploads();
            const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });

            const { bom } = await productMerge([row.id, component.id], { belongsTo: 'RELEASE' },
                { releaseNode: { supplierName: ORG, hashes: [{ alg: 'SHA-256', content: PRODUCT_DIGEST }], licenseExpression: PRODUCT_LICENSE } });

            expect(bom.metadata.component.purl).toBe(SAME_NAME_ROW_ROOT);
            expect(property(bom.metadata.properties, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' }]);
            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([]);
            expect(bom.metadata.component.supplier).toStrictEqual({ name: 'S-comp' });
            expect(bom.metadata.component.licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
        });

        it('(v) a product stored above (i) carries the folded node through unchanged', async () => {
            const u = await uploads();
            const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });
            const web = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });
            const inner = await productMerge([component.id, web]);
            const extraUpload = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.1.0', belongsTo: 'RELEASE' });

            const { bom } = await productMerge([inner.id, extraUpload], { name: 'facts-suite', version: '1.0.0' });

            const ofComponent = allComponents(bom).filter(c => typeof c.purl === 'string' && decoded(c.purl) === 'facts-api@1.0.0');
            expect(ofComponent).toHaveLength(1);
            const before = allComponents(inner.bom).find(c => typeof c.purl === 'string' && decoded(c.purl) === 'facts-api@1.0.0');
            expect(ofComponent[0].description).toBe(before.description);
            expect(ofComponent[0].properties).toStrictEqual(before.properties);
            expect(ofComponent[0].hashes).toStrictEqual(before.hashes);
            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([]);
        });
    });

    describe('the export filtered by belongsTo RELEASE (T-7)', () => {
        it('folds the generator-purl upload root in the filtered component merge', async () => {
            const u = await uploads();
            const { bom } = await componentMerge([u.release], { releaseRootIds: [u.release] }, { belongsTo: 'RELEASE' });

            expect(bom.metadata.component.purl).toBe(RELEASE_ATTACHED_ROOT_2);
            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([{ name: RELEASE_ROOTS_FOLDED_PROPERTY, value: '1' }]);
            expect(node(bom, RELEASE_ATTACHED_ROOT)).toBeUndefined();
        });

        it('leaves the no-generator-purl upload root to the bom-ref de-dup: same purl as the filtered root, no fold record', async () => {
            const doc = releaseAttachedUpload();
            delete doc.metadata.component.purl;
            doc.metadata.component['bom-ref'] = 'no-purl-root';
            doc.dependencies[0].ref = 'no-purl-root';
            const u = await uploads(doc);
            const { bom } = await componentMerge([u.release], { releaseRootIds: [u.release] }, { belongsTo: 'RELEASE' });

            expect(bom.metadata.component.purl).toBe(RELEASE_ATTACHED_ROOT_2);
            expect(bom.metadata.component.description).toBe('The facts API');
            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([]);
            expect(property(bom.metadata.properties, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' }]);
        });

        it('keeps every component root with belongsto=RELEASE and its own name as a node of the filtered product', async () => {
            const u = await uploads();
            const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });
            const web = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });
            const request = productMergeRequest(true, PRODUCT_LICENSE);

            const id = await storeMerge(created, [component.id, web], { ...request.overrides, belongsTo: 'RELEASE' }, request.extra);
            const bom = await BomService.findBomObjectById(id, TEST_ORG_UUID);

            expect(node(bom, RELEASE_ROOT)).toBeDefined();
            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([]);
            expect(rootEntry(bom).dependsOn).toContain(RELEASE_ROOT);
            expect(rootEntry(bom).dependsOn).toContain(RELEASE_NODE);
        });
    });
});
