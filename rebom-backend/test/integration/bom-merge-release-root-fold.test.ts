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
import { TEST_ORG_UUID, createTestRebomOptions } from '../helpers';
import * as BomRepository from '../../src/bomRepository';
import { logger } from '../../src/logger';
import {
    FOLDED_ROOT_PROPERTY,
    RELEASE_ROOTS_FOLDED_PROPERTY,
    BOM_REFS_DEDUPLICATED_PROPERTY,
    NODES_REKEYED_PROPERTY,
    DANGLING_REFS_DROPPED_PROPERTY,
    COMPONENTS_WITHOUT_SBOM_PROPERTY
} from '../../src/services/bom/bomProcessingService';
import {
    ORG, COMMIT, UPLOAD_DIGEST, RELEASE_DIGEST, SCE_ROOT, DELIVERABLE_ROOT, RELEASE_ROOT, RELEASE_NODE, PRODUCT_LICENSE,
    COMPONENT_LICENSE, COMPONENT_LICENSE_URL, LICENSE_MERGE_VERSION,
    sceUpload, deliverableUpload, releaseUpload, releaseNodeUpload, releaseAttachedUpload, productMergeRequest, node,
    storeUpload, storeMerge, RELEASE_ATTACHED_ROOT, RELEASE_ATTACHED_WEBSITE,
    PRODUCT_ROOT, PLACEHOLDER_REF, EXTERNAL_PLACEHOLDER_REF, placeholder, externalPlaceholder, Q, P
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

/** Every service at any depth. */
function allServices(bom: any): any[] {
    const out: any[] = [];
    const visit = (s: any) => {
        if (!s || typeof s !== 'object') return;
        out.push(s);
        (s.services || []).forEach(visit);
    };
    (bom.services || []).forEach(visit);
    return out;
}

/** The bom-refs of every component and service, at any depth, are unique. */
function expectUniqueBomRefs(bom: any): void {
    const refs = [...allComponents(bom), ...allServices(bom)].map(c => c['bom-ref']).filter(Boolean);
    expect(new Set(refs).size).toBe(refs.length);
}

function expectUniqueDependencyRefs(bom: any): void {
    const refs = (bom.dependencies || []).map((d: any) => d.ref);
    expect(new Set(refs).size).toBe(refs.length);
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

        /**
         * (ii): a component release named like the product, with its own RELEASE-attached upload, merged with belongsTo
         * RELEASE. SCORE-66: `edit` changes the upload before it is stored, `overrides` the merge options.
         */
        async function sameNameComponentRow(edit: (doc: any) => void = () => undefined, overrides: any = {}): Promise<{ id: string, bom: any }> {
            const doc = releaseAttachedUpload();
            doc.metadata.component = {
                type: 'application', name: 'facts-product', version: '3.0.0', purl: 'pkg:npm/facts-product@3.0.0', 'bom-ref': 'pkg:npm/facts-product@3.0.0',
                supplier: { name: 'S-comp' }, licenses: [{ license: { id: 'MIT' } }], hashes: [{ alg: 'SHA-256', content: COMPONENT_OWN_DIGEST }]
            };
            doc.dependencies[0].ref = 'pkg:npm/facts-product@3.0.0';
            edit(doc);
            const upload = await storeUpload(created, doc, { name: 'facts-product', version: '3.0.0', belongsTo: 'RELEASE', supplierName: ORG });
            const id = await storeMerge(created, [upload],
                { name: 'facts-product', version: '3.0.0', rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT', belongsTo: 'RELEASE', mergeVersion: LICENSE_MERGE_VERSION, ...overrides },
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

        const PRODUCT_FACTS = { releaseNode: { supplierName: ORG, hashes: [{ alg: 'SHA-256', content: PRODUCT_DIGEST }], licenseExpression: PRODUCT_LICENSE } };

        /** The product root of (iv) and every case built on it: the product's declared facts, nothing of the component. */
        function expectProductFactsOnly(root: any): void {
            expect(root.supplier).toStrictEqual({ name: ORG });
            expect(root.licenses).toStrictEqual([{ license: { id: PRODUCT_LICENSE } }]);
            expect(root.hashes).toStrictEqual([{ alg: 'SHA-256', content: PRODUCT_DIGEST }]);
            expect(property(root.properties, FOLDED_ROOT_PROPERTY)).toStrictEqual([]);
        }

        /** The one top-level node whose purl decodes to facts-product@3.0.0, the same-name row written apart (SCORE-66). */
        function expectRowApart(bom: any, serial: string, key: string = SAME_NAME_ROW_ROOT): any {
            const same = (bom.components || []).filter((c: any) => typeof c.purl === 'string' && decoded(c.purl) === 'facts-product@3.0.0');
            expect(same).toHaveLength(1);
            expect(same[0]['bom-ref']).toBe(Q(serial, key));
            expect(same[0].purl).toBe(P(key, serial));
            expect((PackageURL.fromString(same[0].purl).qualifiers as any).rearm_input).toBe(serial);
            expect(same[0].supplier).toStrictEqual({ name: 'S-comp' });
            expect(same[0].licenses).toContainEqual({ license: { id: 'MIT' } });
            expect(same[0].hashes).toStrictEqual([{ alg: 'SHA-256', content: COMPONENT_OWN_DIGEST }]);
            return same[0];
        }

        it('(iv) keeps a same-name, same-version component row apart from the product root without an identifier (SCORE-66)', async () => {
            // SCORE-66: the product root and the same-name row's root share one purl; the row, not listed, is
            // written apart before the merge, so nothing of it folds into the product root.
            const row = await sameNameComponentRow();
            const u = await uploads();
            const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });

            const { id, bom } = await productMerge([row.id, component.id], { belongsTo: 'RELEASE' }, PRODUCT_FACTS);

            const root = bom.metadata.component;
            expect(root.purl).toBe(SAME_NAME_ROW_ROOT);
            expect(root['bom-ref']).toBe(SAME_NAME_ROW_ROOT);
            expectProductFactsOnly(root);
            expect(property(bom.metadata.properties, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([]);
            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([]);
            expect(property(bom.metadata.properties, NODES_REKEYED_PROPERTY)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
            const same = expectRowApart(bom, row.bom.serialNumber);
            expect(rootEntry(bom).dependsOn).toContain(same['bom-ref']);
            expect(rootEntry(bom).dependsOn).toContain(RELEASE_NODE);
            expect(rootEntry(bom).dependsOn).not.toContain(SAME_NAME_ROW_ROOT);
            expectUniqueDependencyRefs(bom);
            expectUniqueBomRefs(bom);
            await expect(validateBom(bom)).resolves.toBeTruthy();
            const again = await BomService.findBomObjectById(id, TEST_ORG_UUID);
            expect(JSON.stringify(again)).toBe(JSON.stringify(bom));
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

        /**
         * SCORE-66 (T-3; ADR-1 to ADR-5): in a product merge no node of an unlisted input folds into a release's
         * node by a release key, and two unlisted roots of one key are both written apart. Real merge-boms,
         * stored rows read back.
         */
        describe('a same-named release key never folds an unlisted node into a release node (SCORE-66)', () => {
            /** The product's own RELEASE-attached upload without a generator purl: its stored root is SAME_NAME_ROW_ROOT. */
            async function ownUploadWithoutPurl(): Promise<string> {
                const doc = releaseAttachedUpload();
                delete doc.metadata.component.purl;
                doc.metadata.component['bom-ref'] = 'no-purl-root';
                doc.dependencies[0].ref = 'no-purl-root';
                return storeUpload(created, doc, { name: 'facts-product', version: '3.0.0', belongsTo: 'RELEASE', supplierName: ORG });
            }

            it('(vi) keeps the row apart through the CLI purl path of the product own listed upload, in either order', async () => {
                const own = await ownUploadWithoutPurl();
                const ownBom = await BomService.findBomObjectById(own, TEST_ORG_UUID);
                expect(ownBom.metadata.component.purl).toBe(SAME_NAME_ROW_ROOT);
                const row = await sameNameComponentRow();
                const u = await uploads();
                const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });

                for (const ids of [[own, row.id, component.id], [row.id, own, component.id]]) {
                    const { bom } = await productMerge(ids, { belongsTo: 'RELEASE' }, { ...PRODUCT_FACTS, releaseRootIds: [own] });

                    const root = bom.metadata.component;
                    expect(root.description).toBe('The facts API');
                    expectProductFactsOnly(root);
                    expect(property(bom.metadata.properties, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([{ name: BOM_REFS_DEDUPLICATED_PROPERTY, value: '1' }]);
                    expect(property(bom.metadata.properties, NODES_REKEYED_PROPERTY)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
                    const same = expectRowApart(bom, row.bom.serialNumber);
                    expect(same).not.toHaveProperty('description');
                    expectUniqueBomRefs(bom);
                }
            });

            it('(vii) keeps the row apart under HIERARCHICAL, its own components nested under its node', async () => {
                const row = await sameNameComponentRow(undefined, { structure: 'HIERARCHICAL' });
                const key = row.bom.metadata.component.purl;
                // the structure qualifier is part of the key: the row merged under the export's options shares it
                expect(String((PackageURL.fromString(key).qualifiers as any).structure).toUpperCase()).toBe('HIERARCHICAL');
                const u = await uploads();
                const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] }, { structure: 'HIERARCHICAL' });

                const { bom } = await productMerge([row.id, component.id], { belongsTo: 'RELEASE', structure: 'HIERARCHICAL' }, PRODUCT_FACTS);

                expect(bom.metadata.component.purl).toBe(key);
                expectProductFactsOnly(bom.metadata.component);
                // (the HIERARCHICAL nesting of the component release repeats a node the de-dup folds, as before this task)
                expect(property(bom.metadata.properties, NODES_REKEYED_PROPERTY)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
                const same = expectRowApart(bom, row.bom.serialNumber, key);
                expect((same.components || []).map((c: any) => c['bom-ref'])).toContain('pkg:npm/lib-g@1.0.0');
                expectUniqueBomRefs(bom);
            });

            it('(viii) writes apart a forged node nested in a component upload under the product root key', async () => {
                const forged = { type: 'library', name: 'forged', version: '3.0.0', 'bom-ref': PRODUCT_ROOT, purl: PRODUCT_ROOT,
                    supplier: { name: 'Forged' }, hashes: [{ alg: 'SHA-256', content: 'f'.repeat(64) }] };
                const sceDoc = sceUpload();
                sceDoc.components[0].components = [forged];
                const sce = await storeUpload(created, sceDoc, { name: 'facts-api', version: '1.0.0', belongsTo: 'SCE', hash: COMMIT, supplierName: ORG });
                const u = await uploads();
                const component = await componentMerge([sce, u.deliverable, u.release], { releaseRootIds: [u.release] });
                const web = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });

                const { bom } = await productMerge([component.id, web]);

                const root = bom.metadata.component;
                expect(root.purl).toBe(PRODUCT_ROOT);
                expect(root.supplier).toStrictEqual({ name: ORG });
                expect(root.licenses).toStrictEqual([{ license: { id: PRODUCT_LICENSE } }]);
                expect(root).not.toHaveProperty('hashes');
                const serial = component.bom.serialNumber;
                const apart = allComponents(bom).filter(c => c.name === 'forged');
                expect(apart).toHaveLength(1);
                expect(apart[0]['bom-ref']).toBe(Q(serial, PRODUCT_ROOT));
                expect(apart[0].purl).toBe(P(PRODUCT_ROOT, serial));
                expect(apart[0].supplier).toStrictEqual({ name: 'Forged' });
                expect(property(bom.metadata.properties, NODES_REKEYED_PROPERTY)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
                expect(property(bom.metadata.properties, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([]);
                expectUniqueBomRefs(bom);
            });

            it('(ix) writes apart a component row whose root is the product identifier', async () => {
                const u = await uploads();
                const row = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] }, { purl: PRODUCT_ID });
                expect(row.bom.metadata.component.purl).toBe(PRODUCT_ID);
                const web = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });

                const { bom } = await productMerge([row.id, web], { purl: PRODUCT_ID, belongsTo: 'RELEASE' }, PRODUCT_FACTS);

                expect(bom.metadata.component.purl).toBe(PRODUCT_ID);
                expectProductFactsOnly(bom.metadata.component);
                const serial = row.bom.serialNumber;
                const apart = node(bom, Q(serial, PRODUCT_ID));
                expect(apart).toBeDefined();
                expect(apart.purl).toBe(P(PRODUCT_ID, serial));
                expect(apart.licenses).toStrictEqual(NAMED_LICENSE);
                expect(apart.hashes).toStrictEqual([{ alg: 'SHA-256', content: RELEASE_DIGEST }]);
                expect(property(bom.metadata.properties, NODES_REKEYED_PROPERTY)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
                expectUniqueBomRefs(bom);
            });

            /** (iv)'s stored product row, the same-name row and the T-5 component it was merged over. */
            async function innerProduct(): Promise<{ inner: { id: string, bom: any }, row: { id: string, bom: any }, component: { id: string, bom: any } }> {
                const row = await sameNameComponentRow();
                const u = await uploads();
                const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });
                const inner = await productMerge([row.id, component.id], { belongsTo: 'RELEASE' }, PRODUCT_FACTS);
                return { inner, row, component };
            }

            it('(x) carries the inner product node and the written-apart row node unchanged through a product above', async () => {
                const { inner } = await innerProduct();
                const innerApart = (inner.bom.components || []).find((c: any) => typeof c.purl === 'string' && c.purl.includes('rearm_input='));
                expect(innerApart).toBeDefined();
                const other = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.1.0', belongsTo: 'RELEASE' });

                const { bom } = await productMerge([inner.id, other], { name: 'facts-suite', version: '1.0.0' });

                const innerNode = node(bom, SAME_NAME_ROW_ROOT);
                expect(innerNode).toBeDefined();
                expect(innerNode.purl).toBe(SAME_NAME_ROW_ROOT);
                expect(innerNode.supplier).toStrictEqual({ name: ORG });
                expect(innerNode.licenses).toStrictEqual([{ license: { id: PRODUCT_LICENSE } }]);
                expect(innerNode.hashes).toStrictEqual([{ alg: 'SHA-256', content: PRODUCT_DIGEST }]);
                const carried = node(bom, innerApart['bom-ref']);
                expect(carried).toBeDefined();
                for (const field of ['bom-ref', 'purl', 'supplier', 'licenses', 'hashes']) expect(carried[field]).toStrictEqual(innerApart[field]);
                expect(property(bom.metadata.properties, NODES_REKEYED_PROPERTY)).toStrictEqual([]);
                expect(rootEntry(bom).dependsOn).toContain(SAME_NAME_ROW_ROOT);
                expect(bom.dependencies.find((d: any) => d.ref === SAME_NAME_ROW_ROOT).dependsOn).toContain(innerApart['bom-ref']);
                expectUniqueBomRefs(bom);
            });

            it('(xi) writes apart the inner product root and the row root that share a key in the full unwind, in either order', async () => {
                const { inner, row, component } = await innerProduct();
                const innerSerial = inner.bom.serialNumber;
                const rowSerial = row.bom.serialNumber;

                for (const ids of [[inner.id, row.id, component.id], [row.id, inner.id, component.id]]) {
                    const { bom } = await productMerge(ids, { name: 'facts-suite', version: '1.0.0' });

                    const innerNode = node(bom, Q(innerSerial, SAME_NAME_ROW_ROOT));
                    expect(innerNode).toBeDefined();
                    expect(innerNode.purl).toBe(P(SAME_NAME_ROW_ROOT, innerSerial));
                    expect(innerNode.supplier).toStrictEqual({ name: ORG });
                    expect(innerNode.licenses).toStrictEqual([{ license: { id: PRODUCT_LICENSE } }]);
                    expect(innerNode.hashes).toStrictEqual([{ alg: 'SHA-256', content: PRODUCT_DIGEST }]);
                    const rowNodes = allComponents(bom).filter(c => c['bom-ref'] === Q(rowSerial, SAME_NAME_ROW_ROOT));
                    expect(rowNodes).toHaveLength(1);
                    expect(rowNodes[0].purl).toBe(P(SAME_NAME_ROW_ROOT, rowSerial));
                    expect(rowNodes[0].supplier).toStrictEqual({ name: 'S-comp' });
                    expect(rowNodes[0].licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
                    expect(rowNodes[0].hashes).toStrictEqual([{ alg: 'SHA-256', content: COMPONENT_OWN_DIGEST }]);
                    expect(node(bom, SAME_NAME_ROW_ROOT)).toBeUndefined();
                    const innerEntry = bom.dependencies.find((d: any) => d.ref === Q(innerSerial, SAME_NAME_ROW_ROOT));
                    expect(innerEntry.dependsOn).toContain(Q(rowSerial, SAME_NAME_ROW_ROOT));
                    expect(rootEntry(bom).dependsOn).toEqual(expect.arrayContaining([Q(innerSerial, SAME_NAME_ROW_ROOT), Q(rowSerial, SAME_NAME_ROW_ROOT), RELEASE_NODE]));
                    expect(property(bom.metadata.properties, NODES_REKEYED_PROPERTY)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '2' }]);
                    expectUniqueBomRefs(bom);
                    expectUniqueDependencyRefs(bom);
                }
            });

            it('(xii) qualifies a placeholder carrying the product identifier and keeps its bom-ref for the composition', async () => {
                const u = await uploads();
                const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });
                const web = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });
                const info = vi.spyOn(logger, 'info');

                const { bom } = await productMerge([component.id, web], { purl: PRODUCT_ID, belongsTo: 'RELEASE' },
                    { ...PRODUCT_FACTS, missingSbomComponents: [{ ...placeholder(true), purl: PRODUCT_ID, licenseExpression: PRODUCT_LICENSE }, externalPlaceholder()] });

                const ph = node(bom, PLACEHOLDER_REF);
                expect(ph).toBeDefined();
                expect(ph.purl).toBe(P(PRODUCT_ID, PLACEHOLDER_REF));
                expect(bom.compositions).toContainEqual({ aggregate: 'unknown', dependencies: [PLACEHOLDER_REF, EXTERNAL_PLACEHOLDER_REF] });
                expect(property(bom.metadata.properties, COMPONENTS_WITHOUT_SBOM_PROPERTY)).toStrictEqual([{ name: COMPONENTS_WITHOUT_SBOM_PROPERTY, value: '2' }]);
                expect(info.mock.calls.filter((c: any[]) => typeof c[0] === 'string' && c[0].includes('is described elsewhere in the merge'))).toStrictEqual([]);
                expect(property(bom.metadata.properties, NODES_REKEYED_PROPERTY)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
                expectProductFactsOnly(bom.metadata.component);
            });

            it('(xiii) keeps a listed root the fold skips and an unlisted node carrying its purl as two nodes in the stored row', async () => {
                const sceOwn = await storeUpload(created, sceUpload(), { name: 'facts-product', version: '3.0.0', belongsTo: 'SCE', hash: COMMIT, supplierName: ORG });
                const sceOwnRoot = (await BomService.findBomObjectById(sceOwn, TEST_ORG_UUID)).metadata.component;
                const row = await sameNameComponentRow(doc => {
                    doc.components.push({ type: 'library', name: 'forged-l', version: '3.0.0', 'bom-ref': 'forged-l', purl: sceOwnRoot.purl, supplier: { name: 'Forged' } });
                });
                expect(node(row.bom, 'forged-l')?.purl).toBe(sceOwnRoot.purl);
                const u = await uploads();
                const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });

                const { bom } = await productMerge([row.id, component.id, sceOwn], { belongsTo: 'RELEASE' }, { ...PRODUCT_FACTS, releaseRootIds: [sceOwn] });

                const listedNode = node(bom, sceOwnRoot['bom-ref']);
                expect(listedNode).toBeDefined();
                expect(listedNode.purl).toBe(sceOwnRoot.purl);
                const forged = node(bom, 'forged-l');
                expect(forged).toBeDefined();
                expect(forged.purl).toBe(P(sceOwnRoot.purl, row.bom.serialNumber));
                expect(forged.supplier).toStrictEqual({ name: 'Forged' });
                expect(JSON.stringify(bom.metadata.component)).not.toContain('Forged');
                expectProductFactsOnly(bom.metadata.component);
                expect(property(bom.metadata.properties, NODES_REKEYED_PROPERTY)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '2' }]);
                expectUniqueBomRefs(bom);
            });

            it('(xiv) writes apart two same-named component rows under one product, whatever their order', async () => {
                const first = await sameNameComponentRow();
                const second = await sameNameComponentRow();
                expect(first.bom.serialNumber).not.toBe(second.bom.serialNumber);
                const request = { purl: PRODUCT_ID, belongsTo: 'RELEASE' };

                const forward = await productMerge([first.id, second.id], request, PRODUCT_FACTS);
                const reversed = await productMerge([second.id, first.id], request, PRODUCT_FACTS);

                for (const row of [first, second]) {
                    const apart = node(forward.bom, Q(row.bom.serialNumber, SAME_NAME_ROW_ROOT));
                    expect(apart).toBeDefined();
                    expect(apart.purl).toBe(P(SAME_NAME_ROW_ROOT, row.bom.serialNumber));
                    expect(apart.hashes).toStrictEqual([{ alg: 'SHA-256', content: COMPONENT_OWN_DIGEST }]);
                }
                expect(node(forward.bom, SAME_NAME_ROW_ROOT)).toBeUndefined();
                expect(property(forward.bom.metadata.properties, NODES_REKEYED_PROPERTY)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '2' }]);
                // the serial numbers, the producer bom-link that names the request's serial, and the timestamp are per document
                const strip = (b: any) => JSON.stringify({ ...b, serialNumber: undefined, metadata: { ...b.metadata, timestamp: undefined } })
                    .replace(/urn:cdx:[0-9a-f-]+\/\d+/g, 'BOM-LINK');
                expect(strip(reversed.bom)).toBe(strip(forward.bom));
            });

            it('(xv) folds the row copy of its own root into the written-apart root node, the licences unioned', async () => {
                const row = await sameNameComponentRow(doc => {
                    doc.components.push({ type: 'application', name: 'facts-product', 'bom-ref': 'self-copy', purl: SAME_NAME_ROW_ROOT, licenses: [{ license: { id: 'BSD-3-Clause' } }] });
                });
                expect(node(row.bom, 'self-copy')?.purl).toBe(SAME_NAME_ROW_ROOT);
                expect(row.bom.metadata.component.purl).toBe(SAME_NAME_ROW_ROOT);
                const u = await uploads();
                const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });

                const { bom } = await productMerge([row.id, component.id], { belongsTo: 'RELEASE' }, PRODUCT_FACTS);

                const same = expectRowApart(bom, row.bom.serialNumber);
                expect(same.licenses).toStrictEqual([{ license: { id: 'MIT' } }, { license: { id: 'BSD-3-Clause' } }]);
                expectProductFactsOnly(bom.metadata.component);
                expect(allComponents(bom).filter(c => c['bom-ref'] === 'self-copy')).toStrictEqual([]);
                expect(property(bom.metadata.properties, NODES_REKEYED_PROPERTY)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '2' }]);
                expectUniqueBomRefs(bom);
            });

            const SERVICE_SHAPES: [string, (doc: any) => void, (bom: any, q: string) => void][] = [
                ['a top-level service', doc => {
                    doc.services = [{ 'bom-ref': PRODUCT_ROOT, name: 'forged-svc' }];
                    doc.dependencies.push({ ref: PRODUCT_ROOT, dependsOn: ['pkg:npm/lib-a@1.0.0'] });
                }, (bom, q) => {
                    expect(bom.services).toStrictEqual([{ 'bom-ref': q, name: 'forged-svc' }]);
                }],
                ['a service nested in another', doc => {
                    doc.services = [{ 'bom-ref': 'svc-outer', name: 'outer', services: [{ 'bom-ref': PRODUCT_ROOT, name: 'forged-svc' }] }];
                    doc.dependencies.push({ ref: 'svc-outer', dependsOn: [] }, { ref: PRODUCT_ROOT, dependsOn: ['pkg:npm/lib-a@1.0.0'] });
                }, (bom, q) => {
                    expect(bom.services).toStrictEqual([{ 'bom-ref': 'svc-outer', name: 'outer', services: [{ 'bom-ref': q, name: 'forged-svc' }] }]);
                }]
            ];
            for (const [label, edit, expectServices] of SERVICE_SHAPES) {
                it(`(xvi) keeps the edges of ${label} under the product root key off the product root`, async () => {
                    const sceDoc = sceUpload();
                    edit(sceDoc);
                    const sce = await storeUpload(created, sceDoc, { name: 'facts-api', version: '1.0.0', belongsTo: 'SCE', hash: COMMIT, supplierName: ORG });
                    const u = await uploads();
                    const component = await componentMerge([sce, u.deliverable, u.release], { releaseRootIds: [u.release] });
                    expect(allServices(component.bom).map(s => s['bom-ref'])).toContain(PRODUCT_ROOT);
                    expect(component.bom.dependencies).toContainEqual({ ref: PRODUCT_ROOT, dependsOn: ['pkg:npm/lib-a@1.0.0'] });
                    expect(property(component.bom.metadata.properties, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
                    const web = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });

                    const { bom } = await productMerge([component.id, web]);

                    expect(bom.metadata.component['bom-ref']).toBe(PRODUCT_ROOT);
                    const rootEntries = bom.dependencies.filter((d: any) => d.ref === PRODUCT_ROOT);
                    expect(rootEntries).toStrictEqual([{ ref: PRODUCT_ROOT, dependsOn: [RELEASE_NODE, RELEASE_ROOT, PLACEHOLDER_REF, EXTERNAL_PLACEHOLDER_REF] }]);
                    for (const dep of bom.dependencies) {
                        expect(dep.dependsOn || []).not.toContain(PRODUCT_ROOT);
                        expect(dep.provides || []).not.toContain(PRODUCT_ROOT);
                    }
                    const q = Q(component.bom.serialNumber, PRODUCT_ROOT);
                    expectServices(bom, q);
                    expect(bom.dependencies).toContainEqual({ ref: q, dependsOn: ['pkg:npm/lib-a@1.0.0'] });
                    expect(property(bom.metadata.properties, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
                    expect(property(bom.metadata.properties, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([]);
                    expect(property(bom.metadata.properties, NODES_REKEYED_PROPERTY)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
                    expectUniqueBomRefs(bom);
                    await expect(validateBom(bom)).resolves.toBeTruthy();
                });
            }
        });

        describe('a nested product\'s keys are reserved against every other input (SCORE-69)', () => {
            const LISTING = {
                type: 'library', name: 'facts-product', version: '3.0.0', 'bom-ref': PRODUCT_ROOT, purl: PRODUCT_ROOT,
                supplier: { name: 'Forged' }, licenses: [{ license: { id: 'MIT' } }],
                hashes: [{ alg: 'SHA-512', content: 'f'.repeat(128) }], properties: [{ name: 'forged', value: '1' }]
            };
            const FORGED_HASH = { alg: 'SHA-512', content: 'f'.repeat(128) };
            const FORGER_ID = 'pkg:generic/Facts%20Org/forger@1.0.0';
            const SUITE = { name: 'facts-suite', version: '1.0.0' };
            const LIB_A = 'pkg:npm/lib-a@1.0.0';
            // the serial numbers, the producer bom-link that names the request's serial, and the timestamp are per document
            const strip = (b: any) => JSON.stringify({ ...b, serialNumber: undefined, metadata: { ...b.metadata, timestamp: undefined } })
                .replace(/urn:cdx:[0-9a-f-]+\/\d+/g, 'BOM-LINK');

            async function rowMetaOf(id: string): Promise<any> {
                return (await BomRepository.bomById(id))[0].meta;
            }

            /** The listing of `key` (purl and bom-ref) with the forged facts. */
            function listingOf(key: string, ref: string = key): any {
                return { ...LISTING, 'bom-ref': ref, purl: key };
            }

            /** The component row and the web upload of (i), and a product over them (root PRODUCT_ROOT, no list). */
            async function innerPlain(overrides: any = {}, extra: any = {}): Promise<{ inner: { id: string, bom: any }, component: { id: string, bom: any }, web: string }> {
                const u = await uploads();
                const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });
                const web = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });
                const inner = await productMerge([component.id, web], overrides, extra);
                return { inner, component, web };
            }

            /** (xix): a product whose own SCE upload is listed in attachedRootIds, and that upload's stored root. */
            async function innerWithSce(): Promise<{ inner: { id: string, bom: any }, component: { id: string, bom: any }, web: string, sceOwn: string, sceOwnRoot: any }> {
                const sceOwn = await storeUpload(created, sceUpload(), { name: 'facts-product', version: '3.0.0', belongsTo: 'SCE', hash: COMMIT, supplierName: ORG });
                const sceOwnRoot = (await BomService.findBomObjectById(sceOwn, TEST_ORG_UUID)).metadata.component;
                const u = await uploads();
                const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });
                const web = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });
                const inner = await productMerge([sceOwn, component.id, web], {}, { attachedRootIds: [sceOwn] });
                return { inner, component, web, sceOwn, sceOwnRoot };
            }

            /** A component row `lister` 1.0.0 over an SCE upload (no hash) edited by `edit`. */
            async function lister(edit: (doc: any) => void, overrides: any = {}): Promise<{ id: string, bom: any }> {
                const doc = sceUpload();
                edit(doc);
                const sce = await storeUpload(created, doc, { name: 'lister', version: '1.0.0', belongsTo: 'SCE', supplierName: ORG });
                return componentMerge([sce], {}, { name: 'lister', ...overrides });
            }

            const pushListing = (key: string = PRODUCT_ROOT) => (doc: any) => {
                doc.components.push(listingOf(key));
                doc.dependencies.push({ ref: key, dependsOn: [LIB_A] });
            };

            function forgedNodes(bom: any): any[] {
                return allComponents(bom).filter(c => property(c.properties, 'forged').length > 0);
            }

            function rearmInput(purl: string): string | undefined {
                return (PackageURL.fromString(purl).qualifiers as any)?.rearm_input;
            }

            function expectNoForgedFacts(n: any): void {
                expect(n).toBeDefined();
                expect((n.licenses || []).some((l: any) => l?.license?.id === 'MIT')).toBe(false);
                expect(n.hashes || []).not.toContainEqual(FORGED_HASH);
                expect(property(n.properties, 'forged')).toStrictEqual([]);
                expect(JSON.stringify(n)).not.toContain('Forged');
            }

            function rekeyedCount(bom: any): any[] {
                return property(bom.metadata.properties, NODES_REKEYED_PROPERTY);
            }

            it('(xvii) keeps a component listing of a nested product root apart from that product node, in both orders (the acceptance)', async () => {
                const { inner } = await innerPlain();
                const lst = await lister(pushListing());
                const listerSerial = lst.bom.serialNumber;
                expect(node(lst.bom, PRODUCT_ROOT)?.supplier).toStrictEqual({ name: 'Forged' });

                const outs: any[] = [];
                for (const ids of [[inner.id, lst.id], [lst.id, inner.id]]) {
                    const { bom } = await productMerge(ids, SUITE);
                    outs.push(bom);
                    const product = node(bom, PRODUCT_ROOT);
                    expectNoForgedFacts(product);
                    expect(product.supplier).toStrictEqual({ name: ORG });
                    expect(product.licenses).toStrictEqual([{ license: { id: PRODUCT_LICENSE } }]);
                    expect(product).not.toHaveProperty('hashes');
                    const apart = allComponents(bom).filter(c => c['bom-ref'] === Q(listerSerial, PRODUCT_ROOT));
                    expect(apart).toHaveLength(1);
                    expect(apart[0].purl).toBe(P(PRODUCT_ROOT, listerSerial));
                    expect(apart[0].licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
                    expect(apart[0].hashes).toContainEqual(FORGED_HASH);
                    expect(property(apart[0].properties, 'forged')).toStrictEqual([{ name: 'forged', value: '1' }]);
                    const productEntries = bom.dependencies.filter((d: any) => d.ref === PRODUCT_ROOT);
                    expect(productEntries).toHaveLength(1);
                    expect(productEntries[0].dependsOn || []).not.toContain(LIB_A);
                    expect(bom.dependencies.find((d: any) => d.ref === Q(listerSerial, PRODUCT_ROOT)).dependsOn).toContain(LIB_A);
                    expect(rekeyedCount(bom)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
                    expectUniqueBomRefs(bom);
                    await expect(validateBom(bom)).resolves.toBeTruthy();
                }
                expect(strip(outs[1])).toBe(strip(outs[0]));
            });

            it('(xviii) keeps a service under the nested product root and its edges off that product', async () => {
                const { inner } = await innerPlain();
                const lst = await lister(doc => {
                    doc.services = [{ 'bom-ref': PRODUCT_ROOT, name: 'forged-svc' }];
                    doc.dependencies.push({ ref: PRODUCT_ROOT, dependsOn: [LIB_A] });
                });
                expect(allServices(lst.bom).map(s => s['bom-ref'])).toContain(PRODUCT_ROOT);
                expect(lst.bom.dependencies).toContainEqual({ ref: PRODUCT_ROOT, dependsOn: [LIB_A] });
                expect(property(lst.bom.metadata.properties, DANGLING_REFS_DROPPED_PROPERTY)).toStrictEqual([]);
                const innerEntry = inner.bom.dependencies.find((d: any) => d.ref === PRODUCT_ROOT);

                const { bom } = await productMerge([inner.id, lst.id], SUITE);

                const entries = bom.dependencies.filter((d: any) => d.ref === PRODUCT_ROOT);
                expect(entries).toHaveLength(1);
                expect(entries[0].dependsOn).toStrictEqual(innerEntry.dependsOn);
                const q = Q(lst.bom.serialNumber, PRODUCT_ROOT);
                expect(allServices(bom).map(s => s['bom-ref'])).toStrictEqual([q]);
                expect(bom.dependencies).toContainEqual({ ref: q, dependsOn: [LIB_A] });
                expect(rekeyedCount(bom)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
                expectUniqueBomRefs(bom);
            });

            it('(xix) keeps a listing of the nested product SCE root apart from that root', async () => {
                const { inner, sceOwnRoot } = await innerWithSce();
                expect(sceOwnRoot.purl).toBe(sceOwnRoot['bom-ref']);
                expect((await rowMetaOf(inner.id)).listedRootKeys).toStrictEqual([sceOwnRoot.purl]);
                const carried = node(inner.bom, sceOwnRoot['bom-ref']);
                const lst = await lister(pushListing(sceOwnRoot.purl));

                const { bom } = await productMerge([inner.id, lst.id], SUITE);

                const sceNode = node(bom, sceOwnRoot['bom-ref']);
                expectNoForgedFacts(sceNode);
                expect(sceNode.licenses).toStrictEqual(carried.licenses);
                const apart = node(bom, Q(lst.bom.serialNumber, sceOwnRoot['bom-ref']));
                expect(apart.purl).toBe(P(sceOwnRoot.purl, lst.bom.serialNumber));
                expect(apart.licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
                expect(rekeyedCount(bom)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
                expectUniqueBomRefs(bom);
            });

            it('(xx) keeps the copy a product row carries of the nested product it merged, once, until that product is rebuilt alone', async () => {
                const { inner: inner0, component, web } = await innerPlain();
                const inner1 = await productMerge([inner0.id, component.id, web], { name: 'facts-mid', version: '1.0.0' });
                const inner0Serial = (await rowMetaOf(inner0.id)).serialNumber;
                expect((await rowMetaOf(inner1.id)).nestedProductSerials).toStrictEqual([inner0Serial]);
                expect(inner0Serial).not.toBe(inner0.bom.serialNumber);
                const inner0Root = inner0.bom.metadata.component;

                const outs: any[] = [];
                for (const ids of [[inner1.id, inner0.id, component.id, web], [inner0.id, inner1.id, component.id, web]]) {
                    const { bom } = await productMerge(ids, SUITE);
                    outs.push(bom);
                    const product = allComponents(bom).filter(c => c.purl === PRODUCT_ROOT);
                    expect(product).toHaveLength(1);
                    for (const field of ['supplier', 'licenses', 'hashes']) expect(product[0][field]).toStrictEqual(inner0Root[field]);
                    expect(allComponents(bom).filter(c => typeof c.purl === 'string' && c.purl.startsWith(PRODUCT_ROOT + '?rearm_input'))).toStrictEqual([]);
                    expect(rekeyedCount(bom)).toStrictEqual([]);
                }
                expect(strip(outs[1])).toBe(strip(outs[0]));

                // the same with the nested product listing its own SCE root: that root once
                const sce = await innerWithSce();
                const mid = await productMerge([sce.inner.id, sce.component.id, sce.web], { name: 'facts-mid', version: '1.0.0' });
                const { bom: withSce } = await productMerge([mid.id, sce.inner.id, sce.component.id, sce.web], SUITE);
                expect(allComponents(withSce).filter(c => typeof c.purl === 'string' && c.purl.startsWith(sce.sceOwnRoot.purl))).toHaveLength(1);
                expect(rekeyedCount(withSce)).toStrictEqual([]);

                // the nested product rebuilt alone while the row above is not: the carried copy is written apart
                const inner0b = await productMerge([component.id, web]);
                expect((await rowMetaOf(inner0b.id)).serialNumber).not.toBe(inner0Serial);
                const inner1Serial = inner1.bom.serialNumber;
                const { bom: stale } = await productMerge([inner1.id, inner0b.id, component.id, web], SUITE);
                const clean = node(stale, PRODUCT_ROOT);
                for (const field of ['supplier', 'licenses', 'hashes']) expect(clean[field]).toStrictEqual(inner0b.bom.metadata.component[field]);
                const copy = node(stale, Q(inner1Serial, PRODUCT_ROOT));
                expect(copy).toBeDefined();
                expect(copy.purl).toBe(P(PRODUCT_ROOT, inner1Serial));
                expect(rekeyedCount(stale)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);

                // the row above rebuilt: once again
                const inner1b = await productMerge([inner0b.id, component.id, web], { name: 'facts-mid', version: '1.0.0' });
                expect((await rowMetaOf(inner1b.id)).nestedProductSerials).toStrictEqual([(await rowMetaOf(inner0b.id)).serialNumber]);
                const { bom: rebuilt } = await productMerge([inner1b.id, inner0b.id, component.id, web], SUITE);
                expect(allComponents(rebuilt).filter(c => typeof c.purl === 'string' && c.purl.startsWith(PRODUCT_ROOT))).toHaveLength(1);
                expect(rekeyedCount(rebuilt)).toStrictEqual([]);
            });

            it('(xxi) writes apart a sibling product copy of a listing beside the lister own node', async () => {
                const { inner: innerA, web } = await innerPlain();
                const lst = await lister(pushListing());
                const innerB = await productMerge([lst.id, web], { name: 'facts-b', version: '1.0.0' });
                expect(node(innerB.bom, PRODUCT_ROOT)?.supplier).toStrictEqual({ name: 'Forged' });
                expect((await rowMetaOf(innerB.id)).nestedProductSerials).toStrictEqual([]);

                const { bom } = await productMerge([innerA.id, innerB.id, lst.id], SUITE);

                expectNoForgedFacts(node(bom, PRODUCT_ROOT));
                for (const base of [innerB.bom.serialNumber, lst.bom.serialNumber]) {
                    const apart = node(bom, Q(base, PRODUCT_ROOT));
                    expect(apart?.purl).toBe(P(PRODUCT_ROOT, base));
                    expect(apart.supplier).toStrictEqual({ name: 'Forged' });
                }
                expect(rekeyedCount(bom)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '2' }]);
                expectUniqueBomRefs(bom);
            });

            it('(xxii) keeps the listing apart under HIERARCHICAL', async () => {
                const { inner } = await innerPlain();
                const lst = await lister(pushListing());

                const { bom } = await productMerge([inner.id, lst.id], { ...SUITE, structure: 'HIERARCHICAL' });

                const product = node(bom, PRODUCT_ROOT);
                expectNoForgedFacts(product);
                expect(Array.isArray(product.components) && product.components.length > 0).toBe(true);
                const apart = allComponents(bom).filter(c => c['bom-ref'] === Q(lst.bom.serialNumber, PRODUCT_ROOT));
                expect(apart).toHaveLength(1);
                expect(apart[0].licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
                expect(rekeyedCount(bom)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
                expectUniqueBomRefs(bom);
            });

            it('(xxiii) stores the two records on merge rows from the row meta, and never on an upload', async () => {
                const { inner, component, web } = await innerPlain();
                expect(await rowMetaOf(inner.id)).toMatchObject({ nestedProductSerials: [], listedRootKeys: [] });
                const mid = await productMerge([inner.id, component.id, web], { name: 'facts-mid', version: '1.0.0' });
                const innerSerial = (await rowMetaOf(inner.id)).serialNumber;
                expect((await rowMetaOf(mid.id)).nestedProductSerials).toStrictEqual([innerSerial]);
                expect(innerSerial).not.toBe(inner.bom.serialNumber);
                const sce = await innerWithSce();
                expect(await rowMetaOf(sce.inner.id)).toMatchObject({ nestedProductSerials: [], listedRootKeys: [sce.sceOwnRoot.purl] });
                const lst = await lister(() => undefined);
                expect(await rowMetaOf(lst.id)).toMatchObject({ nestedProductSerials: [], listedRootKeys: [] });

                const bom = sceUpload();
                const rebomOptions = { ...createTestRebomOptions({ serialNumber: bom.serialNumber, group: ORG, name: 'facts-api', version: '1.0.0', belongsTo: 'SCE' }), nestedProductSerials: ['x'], listedRootKeys: ['y'] };
                expect(rebomOptions).toMatchObject({ nestedProductSerials: ['x'], listedRootKeys: ['y'] });
                const record = await BomService.addBom({ bomInput: { format: 'CYCLONEDX' as const, bom, org: TEST_ORG_UUID, rebomOptions } });
                created.push(record.uuid);
                const meta = await rowMetaOf(record.uuid);
                expect(meta).not.toHaveProperty('nestedProductSerials');
                expect(meta).not.toHaveProperty('listedRootKeys');
            });

            it('(xxiv) keeps a listing of a nested product identifier apart', async () => {
                const { inner } = await innerPlain({ purl: PRODUCT_ID });
                expect(inner.bom.metadata.component.purl).toBe(PRODUCT_ID);
                const lst = await lister(pushListing(PRODUCT_ID));

                const { bom } = await productMerge([inner.id, lst.id], SUITE);

                expectNoForgedFacts(node(bom, PRODUCT_ID));
                expect(node(bom, Q(lst.bom.serialNumber, PRODUCT_ID))?.purl).toBe(P(PRODUCT_ID, lst.bom.serialNumber));
                expect(rekeyedCount(bom)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
            });

            it('(xxv) keeps a listing of a written-apart nested product root (its image) apart, in both orders', async () => {
                const row = await sameNameComponentRow();
                const u = await uploads();
                const component = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });
                const inner = await productMerge([row.id, component.id], { belongsTo: 'RELEASE' }, PRODUCT_FACTS);
                const innerSerial = inner.bom.serialNumber;
                const rowSerial = row.bom.serialNumber;
                const imageRef = Q(innerSerial, SAME_NAME_ROW_ROOT);
                const lst = await lister(doc => doc.components.push(listingOf(P(SAME_NAME_ROW_ROOT, innerSerial), imageRef)));
                const listerSerial = lst.bom.serialNumber;
                expect(node(lst.bom, imageRef)?.purl).toBe(P(SAME_NAME_ROW_ROOT, innerSerial));

                const outs: any[] = [];
                for (const ids of [[inner.id, row.id, component.id, lst.id], [lst.id, row.id, component.id, inner.id]]) {
                    const { bom } = await productMerge(ids, SUITE);
                    outs.push(bom);
                    const innerNode = node(bom, imageRef);
                    expect(innerNode.purl).toBe(P(SAME_NAME_ROW_ROOT, innerSerial));
                    expectNoForgedFacts(innerNode);
                    expect(innerNode.supplier).toStrictEqual({ name: ORG });
                    expect(innerNode.licenses).toStrictEqual([{ license: { id: PRODUCT_LICENSE } }]);
                    expect(innerNode.hashes).toStrictEqual([{ alg: 'SHA-256', content: PRODUCT_DIGEST }]);
                    const apart = allComponents(bom).filter(c => c['bom-ref'] === Q(listerSerial, imageRef));
                    expect(apart).toHaveLength(1);
                    expect(apart[0].purl).toBe(P(SAME_NAME_ROW_ROOT, listerSerial));
                    expect(apart[0].licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
                    expect(apart[0].hashes).toContainEqual(FORGED_HASH);
                    expect(property(apart[0].properties, 'forged')).toHaveLength(1);
                    const rowNode = node(bom, Q(rowSerial, SAME_NAME_ROW_ROOT));
                    expect(rowNode.supplier).toStrictEqual({ name: 'S-comp' });
                    expect(rekeyedCount(bom)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '3' }]);
                    expectUniqueBomRefs(bom);
                }
                expect(strip(outs[1])).toBe(strip(outs[0]));
            });

            /** The (xxvi) to (xxviii) listings: the nested product SCE root, and the image of its written-apart root. */
            const forgedListings = (sceOwnRoot: any, innerSerial: string) => (doc: any) => {
                doc.components.push(listingOf(sceOwnRoot.purl, sceOwnRoot['bom-ref']), listingOf(P(PRODUCT_ROOT, innerSerial), Q(innerSerial, PRODUCT_ROOT)));
            };

            /** `inner`'s three strings in the outer export: no root under PRODUCT_ROOT, its SCE root and its written-apart root clean. */
            function expectInnerApartClean(bom: any, inner: any, sceOwnRoot: any, innerSerial: string): void {
                expect(node(bom, PRODUCT_ROOT)).toBeUndefined();
                const sceNodes = allComponents(bom).filter(c => c['bom-ref'] === sceOwnRoot['bom-ref']);
                expect(sceNodes).toHaveLength(1);
                expectNoForgedFacts(sceNodes[0]);
                expect(sceNodes[0].licenses).toStrictEqual(node(inner.bom, sceOwnRoot['bom-ref']).licenses);
                const apart = allComponents(bom).filter(c => c['bom-ref'] === Q(innerSerial, PRODUCT_ROOT));
                expect(apart).toHaveLength(1);
                expect(apart[0].purl).toBe(P(PRODUCT_ROOT, innerSerial));
                expectNoForgedFacts(apart[0]);
                expect(apart[0].supplier).toStrictEqual({ name: ORG });
                expect(apart[0].licenses).toStrictEqual([{ license: { id: PRODUCT_LICENSE } }]);
                expect(apart[0].hashes).toStrictEqual(inner.bom.metadata.component.hashes);
            }

            function expectForgedUnder(bom: any, bases: string[], count: number): void {
                const forged = forgedNodes(bom);
                expect(forged).toHaveLength(count);
                for (const n of forged) expect(bases).toContain(rearmInput(n.purl));
            }

            it('(xxvi) gives no exemption from a component release identifier: the nested product nodes stay clean, in both orders', async () => {
                const { inner, web, sceOwnRoot } = await innerWithSce();
                const innerSerial = inner.bom.serialNumber;
                const forger = await lister(forgedListings(sceOwnRoot, innerSerial), { name: 'forger', purl: PRODUCT_ROOT });
                expect(forger.bom.metadata.component.purl).toBe(PRODUCT_ROOT);
                expect(node(forger.bom, sceOwnRoot['bom-ref'])).toBeDefined();
                expect(node(forger.bom, Q(innerSerial, PRODUCT_ROOT))).toBeDefined();
                const innerB = await productMerge([forger.id, web], { name: 'facts-b', version: '1.0.0' });
                for (const ref of [PRODUCT_ROOT, sceOwnRoot['bom-ref'], Q(innerSerial, PRODUCT_ROOT)]) expect(node(innerB.bom, ref)).toBeDefined();
                expect((await rowMetaOf(innerB.id)).nestedProductSerials).toStrictEqual([]);

                const outs: any[] = [];
                for (const ids of [[inner.id, innerB.id, forger.id, web], [innerB.id, inner.id, forger.id, web]]) {
                    const { bom } = await productMerge(ids, SUITE);
                    outs.push(bom);
                    expectInnerApartClean(bom, inner, sceOwnRoot, innerSerial);
                    expectForgedUnder(bom, [forger.bom.serialNumber, innerB.bom.serialNumber], 4);
                    expect(rekeyedCount(bom)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '7' }]);
                    expectUniqueBomRefs(bom);
                    await expect(validateBom(bom)).resolves.toBeTruthy();
                }
                expect(strip(outs[1])).toBe(strip(outs[0]));
            });

            /** (xxvii): a sibling product keyed PRODUCT_ROOT whose own listed SCE upload carries the two listings. */
            async function forgedProduct(sceOwnRoot: any, innerSerial: string, web: string, purl: string = PRODUCT_ROOT): Promise<{ forgerSce: string, forgerSceRoot: any, forgerP: { id: string, bom: any } }> {
                const doc = sceUpload();
                forgedListings(sceOwnRoot, innerSerial)(doc);
                const forgerSce = await storeUpload(created, doc, { name: 'forger', version: '1.0.0', belongsTo: 'SCE', supplierName: ORG });
                const forgerSceRoot = (await BomService.findBomObjectById(forgerSce, TEST_ORG_UUID)).metadata.component;
                const forgerP = await productMerge([forgerSce, web], { name: 'forger', version: '1.0.0', purl }, { attachedRootIds: [forgerSce] });
                return { forgerSce, forgerSceRoot, forgerP };
            }

            it('(xxvii) gives no exemption where another root carries the nested product key: the record is honest, the nodes clean', async () => {
                const { inner, web, sceOwnRoot } = await innerWithSce();
                const innerSerial = inner.bom.serialNumber;
                const { forgerSceRoot, forgerP } = await forgedProduct(sceOwnRoot, innerSerial, web);
                expect(forgerP.bom.metadata.component.purl).toBe(PRODUCT_ROOT);
                for (const ref of [forgerSceRoot['bom-ref'], sceOwnRoot['bom-ref'], Q(innerSerial, PRODUCT_ROOT)]) expect(node(forgerP.bom, ref)).toBeDefined();
                expect(await rowMetaOf(forgerP.id)).toMatchObject({ nestedProductSerials: [], listedRootKeys: [forgerSceRoot.purl] });
                const innerB = await productMerge([forgerP.id, web], { name: 'facts-b', version: '1.0.0' });
                for (const ref of [PRODUCT_ROOT, forgerSceRoot['bom-ref'], sceOwnRoot['bom-ref'], Q(innerSerial, PRODUCT_ROOT)]) expect(node(innerB.bom, ref)).toBeDefined();
                expect((await rowMetaOf(innerB.id)).nestedProductSerials).toStrictEqual([(await rowMetaOf(forgerP.id)).serialNumber]);
                const innerBSerial = innerB.bom.serialNumber;

                const outs: any[] = [];
                for (const ids of [[inner.id, innerB.id, forgerP.id, web], [innerB.id, inner.id, forgerP.id, web]]) {
                    const { bom } = await productMerge(ids, SUITE);
                    outs.push(bom);
                    expectInnerApartClean(bom, inner, sceOwnRoot, innerSerial);
                    expectForgedUnder(bom, [forgerP.bom.serialNumber, innerBSerial], 4);
                    expect(allComponents(bom).filter(c => c['bom-ref'] === forgerSceRoot['bom-ref'])).toHaveLength(1);
                    expect(allComponents(bom).filter(c => c['bom-ref'] === Q(innerBSerial, forgerSceRoot['bom-ref']))).toHaveLength(1);
                    expect(rekeyedCount(bom)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '8' }]);
                    expectUniqueBomRefs(bom);
                    await expect(validateBom(bom)).resolves.toBeTruthy();
                }
                expect(strip(outs[1])).toBe(strip(outs[0]));
            });

            it('(xxviii) gives no exemption from a stale record: the sibling re-identified and rebuilt, or absent, in both orders', async () => {
                const { inner, web, sceOwnRoot } = await innerWithSce();
                const innerSerial = inner.bom.serialNumber;
                const { forgerSce, forgerSceRoot, forgerP } = await forgedProduct(sceOwnRoot, innerSerial, web);
                const innerB = await productMerge([forgerP.id, web], { name: 'facts-b', version: '1.0.0' });
                expect((await rowMetaOf(innerB.id)).nestedProductSerials).toStrictEqual([(await rowMetaOf(forgerP.id)).serialNumber]);
                const innerBSerial = innerB.bom.serialNumber;
                const forgerP2 = await productMerge([forgerSce, web], { name: 'forger', version: '1.0.0', purl: FORGER_ID }, { attachedRootIds: [forgerSce] });
                expect(forgerP2.bom.metadata.component.purl).toBe(FORGER_ID);
                expect((await rowMetaOf(forgerP2.id)).serialNumber).not.toBe((await rowMetaOf(forgerP.id)).serialNumber);

                /** inner's root kept under its key, clean, and nothing under its written-apart strings. */
                const expectInnerClean = (bom: any) => {
                    const root = node(bom, PRODUCT_ROOT);
                    expectNoForgedFacts(root);
                    expect(root.supplier).toStrictEqual({ name: ORG });
                    expect(root.licenses).toStrictEqual([{ license: { id: PRODUCT_LICENSE } }]);
                    expect(root.hashes).toStrictEqual(inner.bom.metadata.component.hashes);
                    const sceNodes = allComponents(bom).filter(c => c['bom-ref'] === sceOwnRoot['bom-ref']);
                    expect(sceNodes).toHaveLength(1);
                    expectNoForgedFacts(sceNodes[0]);
                    expect(allComponents(bom).filter(c => c['bom-ref'] === Q(innerSerial, PRODUCT_ROOT) || c.purl === P(PRODUCT_ROOT, innerSerial))).toStrictEqual([]);
                };

                const outs: any[] = [];
                for (const ids of [[inner.id, innerB.id, forgerP2.id, web], [innerB.id, inner.id, forgerP2.id, web]]) {
                    const { bom } = await productMerge(ids, SUITE);
                    outs.push(bom);
                    expectInnerClean(bom);
                    expect(node(bom, FORGER_ID)).toBeDefined();
                    expect(allComponents(bom).filter(c => c['bom-ref'] === forgerSceRoot['bom-ref'])).toHaveLength(1);
                    expect(allComponents(bom).filter(c => c['bom-ref'] === Q(innerBSerial, forgerSceRoot['bom-ref']))).toHaveLength(1);
                    expectForgedUnder(bom, [innerBSerial, forgerP2.bom.serialNumber], 4);
                    expect(rekeyedCount(bom)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '6' }]);
                    expectUniqueBomRefs(bom);
                    await expect(validateBom(bom)).resolves.toBeTruthy();
                }
                expect(strip(outs[1])).toBe(strip(outs[0]));

                // the sibling absent (the OMIT and SKIPPED_CYCLE paths)
                const { bom: absent } = await productMerge([inner.id, innerB.id, web], SUITE);
                expectInnerClean(absent);
                expect(allComponents(absent).filter(c => c['bom-ref'] === forgerSceRoot['bom-ref'])).toHaveLength(1);
                expectForgedUnder(absent, [innerBSerial], 2);
                expect(rekeyedCount(absent)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '3' }]);
            });
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
