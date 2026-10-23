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
import {
    RELEASE_ROOTS_FOLDED_PROPERTY,
    FOLDED_ROOT_PROPERTY,
    BOM_REFS_DEDUPLICATED_PROPERTY,
    NODES_REKEYED_PROPERTY
} from '../../src/services/bom/bomProcessingService';
import {
    ORG, COMMIT, UPLOAD_DIGEST, RELEASE_DIGEST, SCE_ROOT, DELIVERABLE_ROOT, RELEASE_ROOT, RELEASE_NODE, PRODUCT_ROOT,
    COMPONENT_LICENSE, COMPONENT_LICENSE_URL, LICENSE_MERGE_VERSION, PRODUCT_LICENSE, EXTERNAL_PLACEHOLDER_REF,
    RELEASE_ATTACHED_ROOT, sceUpload, deliverableUpload, releaseUpload, releaseAttachedUpload, productMergeRequest,
    node, storeUpload, storeMerge, Q, P
} from '../releaseNodeFacts.fixture';

/**
 * SCORE-63 (T-5; ADR-1 to ADR-6, ADR-9): the roots of the merging release's own SCE- and
 * deliverable-attached uploads, as rearm-saas lists them in attachedRootIds, carry the release's
 * declared licence in the merged export where the upload wrote none; a root with its own licence
 * is unchanged, no hash is copied, and an input that is not listed is never filled. Real
 * `rearm bomutils merge-boms`, real storage path, the documents served as stored.
 */

const NAMED = [{ license: { name: COMPONENT_LICENSE, url: COMPONENT_LICENSE_URL } }];
const PRODUCT_ID_FORM = [{ license: { id: PRODUCT_LICENSE } }];
const COMMITS = { commits: [{ uid: COMMIT } ] };
/** A deliverable identifier with no belongsto qualifier (rearm-saas sent the deliverable's identifier as `purl`). */
const DELIVERABLE_ID = 'pkg:oci/api-image@1.0.0';
/**
 * A component inside the SCE upload that imitates an attached root's purl (the belongsto=SCE
 * qualifier and a commit). Not SCE_ROOT's purl exactly, which the design's LISTING is: merge-boms
 * (26.10.14) keys components by purl under FLAT and replaces an exact-purl copy of the root, so the
 * listing would never reach the merged document to be left alone. The unit test (T-1) holds the
 * exact purl.
 */
const LISTING = `pkg:npm/facts%20org/api@1.0.0?belongsto=SCE&hash=${'b'.repeat(40)}`;
const LISTING_REF = 'listing-in-sce-upload';

const COMPONENT_FACTS = { supplierName: ORG, hashes: [{ alg: 'SHA-256', content: RELEASE_DIGEST }], licenseExpression: COMPONENT_LICENSE, licenseUrl: COMPONENT_LICENSE_URL };

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

/** The bom-refs of every component and service, at any depth, are unique (the fold test's shape). */
function expectUniqueBomRefs(bom: any): void {
    const refs = [...allComponents(bom), ...allServices(bom)].map(c => c['bom-ref']).filter(Boolean);
    expect(new Set(refs).size).toBe(refs.length);
}

function belongsToOf(purl: unknown): string | undefined {
    if (typeof purl !== 'string') return undefined;
    const q: any = PackageURL.fromString(purl).qualifiers || {};
    const key = Object.keys(q).find(k => k.toLowerCase() === 'belongsto');
    return key === undefined ? undefined : String(q[key]);
}

/** The serial numbers, the producer bom-link that names the request's serial, and the timestamp are per document. */
function strip(b: any): string {
    return JSON.stringify({ ...b, serialNumber: undefined, metadata: { ...b.metadata, timestamp: undefined } })
        .replace(/urn:cdx:[0-9a-f-]+\/\d+/g, 'BOM-LINK');
}

/** `bom` with `licenses` taken off the top-level nodes `refs`. */
function withoutLicensesOn(bom: any, refs: string[]): any {
    return {
        ...bom,
        components: bom.components.map((c: any) => {
            if (!refs.includes(c['bom-ref'])) return c;
            const { licenses, ...rest } = c;
            return rest;
        })
    };
}

/** Every `properties` list of the document, keyed by where it sits. */
function propertiesOf(bom: any): Record<string, any> {
    const out: Record<string, any> = { document: bom.metadata?.properties ?? null };
    for (const c of allComponents(bom)) out[c['bom-ref']] = c.properties ?? null;
    return out;
}

describe('the merged export fills the declared licence on the release own SCE- and deliverable-attached roots (SCORE-63)', () => {
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

    /** The SCE, deliverable and RELEASE-attached uploads of facts-api@1.0.0, `sceDoc` and `deliverableExtra` changing the first two. */
    async function uploads(sceDoc: any = sceUpload(), deliverableExtra: any = {}): Promise<Uploads> {
        const sce = await storeUpload(created, sceDoc, SCE_OPTIONS);
        const deliverable = await storeUpload(created, deliverableUpload(), { ...DELIVERABLE_OPTIONS, ...deliverableExtra });
        const release = await storeUpload(created, releaseAttachedUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'RELEASE', supplierName: ORG });
        return { sce, deliverable, release };
    }

    /** The component release merge (FLATTEN) as rearm-saas asks for it, with `extra` merge-only fields. */
    async function componentMerge(ids: string[], extra: any = {}, overrides: any = {}, facts: any = COMPONENT_FACTS): Promise<{ id: string, bom: any }> {
        const id = await storeMerge(created, ids,
            { name: 'facts-api', version: '1.0.0', rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT', mergeVersion: LICENSE_MERGE_VERSION, ...overrides },
            { releaseNode: facts, ...extra });
        return { id, bom: await BomService.findBomObjectById(id, TEST_ORG_UUID) };
    }

    /** The component merge of (i): every upload, the RELEASE one in releaseRootIds, the SCE and deliverable ones in attachedRootIds. */
    async function listedMerge(u: Uploads, overrides: any = {}): Promise<{ id: string, bom: any }> {
        return componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release], attachedRootIds: [u.sce, u.deliverable] }, overrides);
    }

    async function productMerge(ids: string[], extra: any = {}): Promise<{ id: string, bom: any }> {
        const request = productMergeRequest(true, PRODUCT_LICENSE);
        const id = await storeMerge(created, ids, request.overrides, { ...request.extra, ...extra });
        return { id, bom: await BomService.findBomObjectById(id, TEST_ORG_UUID) };
    }

    describe('the component release merge', () => {
        it('(i) fills both listed roots with the named form, keeps their pedigree, digest and supplier, and writes no property', async () => {
            const u = await uploads();
            const { id, bom } = await listedMerge(u);

            const sce = node(bom, SCE_ROOT);
            expect(sce.licenses).toStrictEqual(NAMED);
            expect(sce.pedigree).toStrictEqual(COMMITS);
            expect(sce.supplier).toStrictEqual({ name: ORG });
            expect(sce).not.toHaveProperty('hashes');
            const deliverable = node(bom, DELIVERABLE_ROOT);
            expect(deliverable.licenses).toStrictEqual(NAMED);
            expect(deliverable.hashes).toStrictEqual([{ alg: 'SHA-256', content: UPLOAD_DIGEST }]);
            expect(deliverable.supplier).toStrictEqual({ name: ORG });
            // the merged root as SCORE-57 T-5: folded, the declared facts filling the rest
            const root = bom.metadata.component;
            expect(root.purl).toBe(RELEASE_NODE);
            expect(root.licenses).toStrictEqual(NAMED);
            expect(root.hashes).toStrictEqual([{ alg: 'SHA-256', content: RELEASE_DIGEST }]);
            expect(property(root.properties, FOLDED_ROOT_PROPERTY)).toStrictEqual([{ name: FOLDED_ROOT_PROPERTY, value: RELEASE_ATTACHED_ROOT }]);
            expect(node(bom, RELEASE_ATTACHED_ROOT)).toBeUndefined();
            await expect(validateBom(bom)).resolves.toBeTruthy();

            // no property and no dependency moves against the same merge without the list
            const { bom: unlisted } = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });
            expect(propertiesOf(bom)).toStrictEqual(propertiesOf(unlisted));
            expect(bom.dependencies).toStrictEqual(unlisted.dependencies);

            const again = await BomService.findBomObjectById(id, TEST_ORG_UUID);
            expect(strip(again)).toBe(strip(bom));
        });

        it('(ii) fills nothing when the request lists nothing: the rest is byte-equal to (i) but the two licence arrays', async () => {
            const u = await uploads();
            const { bom: listed } = await listedMerge(u);
            const { bom } = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] });

            expect(node(bom, SCE_ROOT)).not.toHaveProperty('licenses');
            expect(node(bom, DELIVERABLE_ROOT)).not.toHaveProperty('licenses');
            expect(strip(bom)).toBe(strip(withoutLicensesOn(listed, [SCE_ROOT, DELIVERABLE_ROOT])));
        });

        const OWN: [string, any[] | undefined][] = [
            ['an expression', [{ expression: 'BSD-3-Clause OR MIT' }]],
            ['NOASSERTION', [{ expression: 'NOASSERTION' }]]
        ];
        for (const [label, licenses] of OWN) {
            it(`(iii) keeps a listed SCE root that carries ${label} of its own`, async () => {
                const doc = sceUpload();
                doc.metadata.component.licenses = licenses;
                const u = await uploads(doc);
                const { bom } = await listedMerge(u);

                expect(node(bom, SCE_ROOT).licenses).toStrictEqual(licenses);
                expect(node(bom, DELIVERABLE_ROOT).licenses).toStrictEqual(NAMED);
            });
        }

        it('(iii) fills a listed SCE root whose licences are an empty list', async () => {
            const doc = sceUpload();
            doc.metadata.component.licenses = [];
            const u = await uploads(doc);
            const { bom } = await listedMerge(u);

            expect(node(bom, SCE_ROOT).licenses).toStrictEqual(NAMED);
        });

        it('(iv) leaves a listing inside the SCE upload that imitates an attached root purl without a licence', async () => {
            const doc = sceUpload();
            doc.components.push({ type: 'library', name: 'api', version: '1.0.0', purl: LISTING, 'bom-ref': LISTING_REF });
            doc.dependencies.push({ ref: LISTING_REF, dependsOn: [] });
            const u = await uploads(doc);
            const { bom } = await listedMerge(u);

            const listing = node(bom, LISTING_REF);
            expect(listing).toBeDefined();
            expect(listing.purl).toBe(LISTING);
            expect(listing).not.toHaveProperty('licenses');
            expect(node(bom, SCE_ROOT).licenses).toStrictEqual(NAMED);
        });

        it('(v) fills a listed deliverable root that carries the deliverable identifier and no qualifier, its digest kept', async () => {
            const u = await uploads(sceUpload(), { purl: DELIVERABLE_ID });
            const stored = await BomService.findBomObjectById(u.deliverable, TEST_ORG_UUID);
            expect(stored.metadata.component.purl).toBe(DELIVERABLE_ID);
            const { bom } = await listedMerge(u);

            const deliverable = node(bom, DELIVERABLE_ID);
            expect(deliverable).toBeDefined();
            expect(deliverable.licenses).toStrictEqual(NAMED);
            expect(deliverable.hashes).toStrictEqual([{ alg: 'SHA-256', content: UPLOAD_DIGEST }]);
        });

        it('(vi) folds the RELEASE root as before when it is listed in both lists, and fills the SCE and deliverable roots', async () => {
            const u = await uploads();
            const { bom } = await componentMerge([u.sce, u.deliverable, u.release],
                { releaseRootIds: [u.release], attachedRootIds: [u.sce, u.deliverable, u.release] });

            expect(property(bom.metadata.properties, RELEASE_ROOTS_FOLDED_PROPERTY)).toStrictEqual([{ name: RELEASE_ROOTS_FOLDED_PROPERTY, value: '1' }]);
            expect(node(bom, RELEASE_ATTACHED_ROOT)).toBeUndefined();
            expect(bom.metadata.component.description).toBe('The facts API');
            expect(node(bom, SCE_ROOT).licenses).toStrictEqual(NAMED);
            expect(node(bom, DELIVERABLE_ROOT).licenses).toStrictEqual(NAMED);
        });

        it('(vii) fills nothing when the facts declare no licence: the document equals the one without the list', async () => {
            const facts = { supplierName: ORG, hashes: [{ alg: 'SHA-256', content: RELEASE_DIGEST }] };
            const u = await uploads();
            const { bom } = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release], attachedRootIds: [u.sce, u.deliverable] }, {}, facts);
            const { bom: unlisted } = await componentMerge([u.sce, u.deliverable, u.release], { releaseRootIds: [u.release] }, {}, facts);

            for (const ref of [SCE_ROOT, DELIVERABLE_ROOT]) expect(node(bom, ref), ref).not.toHaveProperty('licenses');
            expect(bom.metadata.component).not.toHaveProperty('licenses');
            expect(strip(bom)).toBe(strip(unlisted));
        });

        it('(x) fills both roots under HIERARCHICAL, each a top-level component with its children nested', async () => {
            const u = await uploads();
            const { bom } = await listedMerge(u, { structure: 'HIERARCHICAL' });

            expect(node(bom, SCE_ROOT)?.components?.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/lib-a@1.0.0', 'pkg:npm/lib-b@1.0.0']);
            expect(node(bom, DELIVERABLE_ROOT)?.components?.map((c: any) => c['bom-ref'])).toStrictEqual(['pkg:npm/lib-c@1.0.0']);
            expect(node(bom, SCE_ROOT).licenses).toStrictEqual(NAMED);
            expect(node(bom, DELIVERABLE_ROOT).licenses).toStrictEqual(NAMED);
            await expect(validateBom(bom)).resolves.toBeTruthy();
        });

        it('(xi) fills both roots in the top-level-only merge', async () => {
            const u = await uploads();
            const { bom } = await listedMerge(u, { tldOnly: true });

            expect(node(bom, SCE_ROOT).licenses).toStrictEqual(NAMED);
            expect(node(bom, DELIVERABLE_ROOT).licenses).toStrictEqual(NAMED);
        });

        it('(xii) fills the SCE root in the export filtered by belongsTo SCE, beside a merged root that carries belongsto=SCE', async () => {
            const u = await uploads();
            const { bom } = await componentMerge([u.sce], { attachedRootIds: [u.sce] }, { belongsTo: 'SCE' });

            expect(belongsToOf(bom.metadata.component.purl)?.toUpperCase()).toBe('SCE');
            expect(bom.metadata.component['bom-ref']).not.toBe(SCE_ROOT);
            const sce = node(bom, SCE_ROOT);
            expect(sce).toBeDefined();
            expect(sce.licenses).toStrictEqual(NAMED);
            expect(sce.pedigree).toStrictEqual(COMMITS);
        });
    });

    describe('products (ADR-6, ADR-9)', () => {
        it('(viii) carries the component licence on its attached roots and never writes the product licence on another release root', async () => {
            const u = await uploads();
            const row = await listedMerge(u);
            const web = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });

            const { bom } = await productMerge([row.id, web]);

            expect(bom.metadata.component.purl).toBe(PRODUCT_ROOT);
            expect(bom.metadata.component.licenses).toStrictEqual(PRODUCT_ID_FORM);
            expect(node(bom, SCE_ROOT).licenses).toStrictEqual(NAMED);
            expect(node(bom, DELIVERABLE_ROOT).licenses).toStrictEqual(NAMED);
            expect(node(bom, RELEASE_ROOT)).toBeDefined();
            expect(node(bom, RELEASE_ROOT)).not.toHaveProperty('licenses');
            expect(node(bom, EXTERNAL_PLACEHOLDER_REF)).toBeDefined();
            expect(node(bom, EXTERNAL_PLACEHOLDER_REF)).not.toHaveProperty('licenses');
            await expect(validateBom(bom)).resolves.toBeTruthy();
        });

        it('(ix) fills the product own listed SCE root with the product licence, the component roots still the component licence', async () => {
            const sceOwn = await storeUpload(created, sceUpload(), { name: 'facts-product', version: '3.0.0', belongsTo: 'SCE', hash: COMMIT, supplierName: ORG });
            const sceOwnRoot = (await BomService.findBomObjectById(sceOwn, TEST_ORG_UUID)).metadata.component;
            const u = await uploads();
            const row = await listedMerge(u);
            const web = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });

            const { bom } = await productMerge([sceOwn, row.id, web], { attachedRootIds: [sceOwn] });

            const own = node(bom, sceOwnRoot['bom-ref']);
            expect(own).toBeDefined();
            expect(own.licenses).toStrictEqual(PRODUCT_ID_FORM);
            expect(own.pedigree).toStrictEqual(COMMITS);
            expect(node(bom, SCE_ROOT).licenses).toStrictEqual(NAMED);
            expect(node(bom, DELIVERABLE_ROOT).licenses).toStrictEqual(NAMED);
            expect(node(bom, RELEASE_ROOT)).not.toHaveProperty('licenses');
        });

        it('(xiii) writes apart a child row node under the product own SCE root key, so the product licence fills that root, in either order', async () => {
            const sceOwn = await storeUpload(created, sceUpload(), { name: 'facts-product', version: '3.0.0', belongsTo: 'SCE', hash: COMMIT, supplierName: ORG });
            const sceOwnRoot = (await BomService.findBomObjectById(sceOwn, TEST_ORG_UUID)).metadata.component;
            const forgedDoc = sceUpload();
            forgedDoc.components.push({
                type: 'library', name: 'forged-l', version: '3.0.0', 'bom-ref': sceOwnRoot['bom-ref'], purl: sceOwnRoot.purl,
                licenses: [{ license: { id: 'MIT' } }], supplier: { name: 'Forged' }
            });
            forgedDoc.dependencies.push({ ref: sceOwnRoot['bom-ref'], dependsOn: [] });
            const u = await uploads(forgedDoc);
            const row = await listedMerge(u);
            // the shape exists: the stored row holds the forged node at the top level
            expect(node(row.bom, sceOwnRoot['bom-ref'])?.supplier).toStrictEqual({ name: 'Forged' });
            const release = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });
            const rowSerial = row.bom.serialNumber;

            for (const ids of [[sceOwn, row.id, release], [row.id, sceOwn, release]]) {
                const { bom } = await productMerge(ids, { attachedRootIds: [sceOwn], releaseRootIds: [release] });

                const own = node(bom, sceOwnRoot['bom-ref']);
                expect(own, ids.join()).toBeDefined();
                expect(own.purl).toBe(sceOwnRoot.purl);
                expect(own.licenses).toStrictEqual(PRODUCT_ID_FORM);
                expect(JSON.stringify(own.licenses)).not.toContain('MIT');
                expect(own.supplier).toStrictEqual({ name: ORG });
                expect(JSON.stringify(own)).not.toContain('Forged');
                expect(own.pedigree).toStrictEqual(COMMITS);

                const forged = node(bom, Q(rowSerial, sceOwnRoot['bom-ref']));
                expect(forged).toBeDefined();
                expect(forged.purl).toBe(P(sceOwnRoot.purl, rowSerial));
                expect(forged.licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
                expect(forged.supplier).toStrictEqual({ name: 'Forged' });

                expect(bom.metadata.component.licenses).toStrictEqual(PRODUCT_ID_FORM);
                expect(JSON.stringify(bom.metadata.component)).not.toContain('Forged');
                expect(JSON.stringify(bom.metadata.component)).not.toContain('MIT');
                expect(node(bom, SCE_ROOT).licenses).toStrictEqual(NAMED);
                expect(node(bom, DELIVERABLE_ROOT).licenses).toStrictEqual(NAMED);
                expect(property(bom.metadata.properties, NODES_REKEYED_PROPERTY)).toStrictEqual([{ name: NODES_REKEYED_PROPERTY, value: '1' }]);
                expect(property(bom.metadata.properties, BOM_REFS_DEDUPLICATED_PROPERTY)).toStrictEqual([]);
                expectUniqueBomRefs(bom);
                await expect(validateBom(bom)).resolves.toBeTruthy();
            }
        });
    });
});
