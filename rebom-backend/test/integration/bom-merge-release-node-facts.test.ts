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

import * as BomService from '../../src/bomService';
import * as BomRepository from '../../src/bomRepository';
import validateBom from '../../src/validateBom';
import { pool } from '../../src/utils';
import { clearMockOciStorage, fetchProcessedBomWithRetry } from '../../src/services/oci';
import { TEST_ORG_UUID, createTestRebomOptions } from '../helpers';
import { logger } from '../../src/logger';
import { DROPPED_LICENSE_PROPERTY, RELEASE_NODE_PROPERTY } from '../../src/services/bom/bomProcessingService';
import {
    ORG, COMMIT, UPLOAD_DIGEST, RELEASE_DIGEST, PLACEHOLDER_DIGEST, PLACEHOLDER_REF, EXTERNAL_PLACEHOLDER_REF,
    SCE_ROOT, DELIVERABLE_ROOT, RELEASE_ROOT, RELEASE_NODE, PRODUCT_ROOT,
    COMPONENT_LICENSE, COMPONENT_LICENSE_URL, PRODUCT_LICENSE,
    buildProductExport, productMergeRequest, node, sceUpload, deliverableUpload,
    LISTING_ROOT, LISTING_MID, DeclaredLicence, buildListingProducts
} from '../releaseNodeFacts.fixture';

/**
 * SCORE-30 (T-8, T-9; ADR-1, ADR-3, ADR-4, ADR-6, ADR-7, ADR-12): every node ReARM writes into a
 * merged release or product SBOM names the supplier ReARM holds for it, and carries the hash or
 * the source commit ReARM holds for it. Real `rearm bomutils merge-boms`, real storage path.
 */

const COMMITS = { commits: [{ uid: COMMIT }] };
/** SCORE-60 ADR-1: rebom's stamp on every merged root. */
const STAMP = { name: RELEASE_NODE_PROPERTY, value: 'true' };

describe('merged SBOM nodes carry the facts ReARM holds', () => {
    const created: string[] = [];

    beforeEach(() => {
        clearMockOciStorage();
    });

    afterEach(async () => {
        if (created.length > 0) {
            await pool.query('DELETE FROM rebom.boms WHERE uuid = ANY($1::uuid[])', [created]);
            created.length = 0;
        }
    });

    /** Stores `bom` with `meta` as its row's attachment facts, the way an older upload left it. */
    async function storeWithMeta(bom: any, meta: any): Promise<string> {
        const record = await BomService.addBom({
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom,
                org: TEST_ORG_UUID,
                // no supplier and no reference: the upload fill has nothing to write
                rebomOptions: createTestRebomOptions({ serialNumber: bom.serialNumber, name: 'api', version: '1.0.0', purl: 'pkg:npm/api@1.0.0' })
            }
        });
        created.push(record.uuid);
        await pool.query('UPDATE rebom.boms SET meta = meta || $2::jsonb WHERE uuid = $1', [record.uuid, JSON.stringify(meta)]);
        const [row] = await BomRepository.bomById(record.uuid);
        const stored: any = await fetchProcessedBomWithRetry(row);
        expect(stored.metadata.component).not.toHaveProperty('supplier');
        expect(stored.metadata.component).not.toHaveProperty('hashes');
        expect(stored.metadata.component).not.toHaveProperty('pedigree');
        return record.uuid;
    }

    it('stores the source code entry upload with its supplier and its commit as a pedigree commit (T-8)', async () => {
        const { ids } = await buildProductExport(created, true);

        const [row] = await BomRepository.bomById(ids.sce);
        expect((row.meta as any).supplierName).toBe(ORG);
        const stored: any = await fetchProcessedBomWithRetry(row);
        expect(stored.metadata.component.supplier).toStrictEqual({ name: ORG });
        expect(stored.metadata.component.pedigree).toStrictEqual(COMMITS);
        expect(stored.metadata.component).not.toHaveProperty('hashes');
    });

    it('a component merge: the root from the release facts, each uploaded root from its attachment (T-8)', async () => {
        const { componentMerge } = await buildProductExport(created, true);

        expect(componentMerge.metadata.component).toStrictEqual({
            type: 'application', name: 'facts-api', group: ORG, version: '1.0.0', purl: RELEASE_NODE, 'bom-ref': RELEASE_NODE,
            supplier: { name: ORG },
            hashes: [{ alg: 'SHA-256', content: RELEASE_DIGEST }],
            properties: [STAMP]
        });
        const sce = node(componentMerge, SCE_ROOT);
        expect(sce.supplier).toStrictEqual({ name: ORG });
        expect(sce.pedigree).toStrictEqual(COMMITS);
        expect(sce).not.toHaveProperty('hashes');
        expect(sce).not.toHaveProperty('licenses');
        const deliverable = node(componentMerge, DELIVERABLE_ROOT);
        expect(deliverable.supplier).toStrictEqual({ name: ORG });
        expect(deliverable.hashes).toStrictEqual([{ alg: 'SHA-256', content: UPLOAD_DIGEST }]);
        expect(deliverable).not.toHaveProperty('pedigree');
        // the uploaded components keep their own supplier
        expect(node(componentMerge, 'pkg:npm/lib-a@1.0.0').supplier).toStrictEqual({ name: 'lib-a maintainers' });
        await expect(validateBom(componentMerge)).resolves.toBeTruthy();
    });

    it('a product merge: the supplier and the hash or commit ReARM holds on each of its nodes, nothing else (T-8)', async () => {
        const { product } = await buildProductExport(created, true);

        // the product root: supplier from its facts, no hashes (its facts carry none)
        expect(product.metadata.component.purl).toBe(PRODUCT_ROOT);
        expect(product.metadata.component.supplier).toStrictEqual({ name: ORG });
        expect(product.metadata.component).not.toHaveProperty('hashes');

        const releaseNode = node(product, RELEASE_NODE);
        expect(releaseNode.supplier).toStrictEqual({ name: ORG });
        expect(releaseNode.hashes).toStrictEqual([{ alg: 'SHA-256', content: RELEASE_DIGEST }]);

        // the CLI carries the pedigree of a flattened input through
        expect(node(product, SCE_ROOT).supplier).toStrictEqual({ name: ORG });
        expect(node(product, SCE_ROOT).pedigree).toStrictEqual(COMMITS);
        expect(node(product, SCE_ROOT)).not.toHaveProperty('hashes');
        expect(node(product, DELIVERABLE_ROOT).supplier).toStrictEqual({ name: ORG });
        expect(node(product, DELIVERABLE_ROOT).hashes).toStrictEqual([{ alg: 'SHA-256', content: UPLOAD_DIGEST }]);

        // stored without supplierName, the legacy shape: no supplier, and no hash for a release root
        const releaseRoot = node(product, RELEASE_ROOT);
        expect(releaseRoot).not.toHaveProperty('supplier');
        expect(releaseRoot).not.toHaveProperty('hashes');
        expect(releaseRoot).not.toHaveProperty('pedigree');

        const placeholder = node(product, PLACEHOLDER_REF);
        expect(placeholder.supplier).toStrictEqual({ name: ORG });
        expect(placeholder.hashes).toStrictEqual([{ alg: 'SHA-256', content: PLACEHOLDER_DIGEST }]);

        // the EXTERNAL component's placeholder: ReARM names no supplier for third-party software
        const external = node(product, EXTERNAL_PLACEHOLDER_REF);
        expect(external).toBeDefined();
        expect(external).not.toHaveProperty('supplier');
        expect(external).not.toHaveProperty('hashes');

        // nothing ReARM wrote claims a licence or an unknown
        for (const ref of [RELEASE_NODE, SCE_ROOT, DELIVERABLE_ROOT, RELEASE_ROOT, PLACEHOLDER_REF, EXTERNAL_PLACEHOLDER_REF]) {
            expect(node(product, ref)).not.toHaveProperty('licenses');
        }
        expect(product.metadata.component).not.toHaveProperty('licenses');
        expect(JSON.stringify(product)).not.toContain('NOASSERTION');
        await expect(validateBom(product)).resolves.toBeTruthy();
    });

    it('without facts the merged roots carry no supplier or hashes, and the uploaded roots still their own (T-8)', async () => {
        const { componentMerge, product } = await buildProductExport(created, false);

        expect(componentMerge.metadata.component).not.toHaveProperty('supplier');
        expect(componentMerge.metadata.component).not.toHaveProperty('hashes');
        expect(node(product, RELEASE_NODE)).not.toHaveProperty('supplier');
        expect(node(product, RELEASE_NODE)).not.toHaveProperty('hashes');
        expect(node(product, PLACEHOLDER_REF)).not.toHaveProperty('hashes');
        expect(node(product, SCE_ROOT).pedigree).toStrictEqual(COMMITS);
        expect(node(product, DELIVERABLE_ROOT).hashes).toStrictEqual([{ alg: 'SHA-256', content: UPLOAD_DIGEST }]);
    });

    // SCORE-40 (T-5, ADR-2 to ADR-4): the licence ReARM declares lands on the nodes ReARM wrote, in
    // the form declaredLicenses gives, and never on an uploaded node.
    const NAMED = [{ license: { name: COMPONENT_LICENSE, url: COMPONENT_LICENSE_URL } }];
    const PRODUCT_ID_FORM = [{ license: { id: PRODUCT_LICENSE } }];
    const LIBRARIES = ['lib-a', 'lib-b', 'lib-c', 'lib-d', 'lib-e'].map(name => `pkg:npm/${name}@1.0.0`);

    it('a component merge: the root carries the declared licence in the named form, and so do the attached roots (SCORE-40 T-5, SCORE-63)', async () => {
        const { componentMerge } = await buildProductExport(created, true, true);

        expect(componentMerge.metadata.component).toStrictEqual({
            type: 'application', name: 'facts-api', group: ORG, version: '1.0.0', purl: RELEASE_NODE, 'bom-ref': RELEASE_NODE,
            supplier: { name: ORG },
            hashes: [{ alg: 'SHA-256', content: RELEASE_DIGEST }],
            licenses: [{ license: { name: 'LicenseRef-Facts-Commercial', url: 'https://facts.example/licence' } }],
            properties: [STAMP]
        });
        // SCORE-63: the listed SCE- and deliverable-attached roots carry the declared licence, absent-only
        expect(node(componentMerge, SCE_ROOT).licenses).toStrictEqual(NAMED);
        expect(node(componentMerge, DELIVERABLE_ROOT).licenses).toStrictEqual(NAMED);
        for (const ref of LIBRARIES.slice(0, 3)) {
            expect(node(componentMerge, ref).licenses, ref).toStrictEqual([{ license: { id: 'MIT' } }]);
        }
        await expect(validateBom(componentMerge)).resolves.toBeTruthy();
    });

    it('a product merge: the root and the INTERNAL placeholder in the id form, the release node and the attached roots in the named form, nothing else (SCORE-40 T-5, SCORE-63)', async () => {
        const { product } = await buildProductExport(created, true, true);

        expect(product.metadata.component.purl).toBe(PRODUCT_ROOT);
        expect(product.metadata.component.licenses).toStrictEqual([{ license: { id: 'AGPL-3.0-only' } }]);
        // the normaliser of the product merge leaves the named form alone
        expect(node(product, RELEASE_NODE).licenses).toStrictEqual(NAMED);
        expect(node(product, PLACEHOLDER_REF).licenses).toStrictEqual(PRODUCT_ID_FORM);
        // SCORE-63: the component's attached roots carry the component's licence from its row, never the product's
        for (const ref of [SCE_ROOT, DELIVERABLE_ROOT]) {
            expect(node(product, ref).licenses, ref).toStrictEqual(NAMED);
        }
        for (const ref of [EXTERNAL_PLACEHOLDER_REF, RELEASE_ROOT]) {
            expect(node(product, ref), ref).toBeDefined();
            expect(node(product, ref), ref).not.toHaveProperty('licenses');
        }
        for (const ref of LIBRARIES) {
            expect(node(product, ref).licenses, ref).toStrictEqual([{ license: { id: 'MIT' } }]);
        }
        // supplier and hashes as SCORE-30 writes them
        expect(product.metadata.component.supplier).toStrictEqual({ name: ORG });
        expect(node(product, RELEASE_NODE).hashes).toStrictEqual([{ alg: 'SHA-256', content: RELEASE_DIGEST }]);
        expect(node(product, PLACEHOLDER_REF).hashes).toStrictEqual([{ alg: 'SHA-256', content: PLACEHOLDER_DIGEST }]);
        await expect(validateBom(product)).resolves.toBeTruthy();
    });

    it('a second product merge of the same inputs carries the licences it was sent, not the stored ones, url dropped beside an expression (SCORE-40 T-5)', async () => {
        const { ids } = await buildProductExport(created, true, true);
        const request = productMergeRequest(true, 'MIT OR Apache-2.0');
        // a url beside the compound is dropped; a single LicenseRef without one is an expression
        request.extra.releaseNode.licenseUrl = 'https://facts.example/product-licence';
        request.extra.missingSbomComponents[0].licenseExpression = 'LicenseRef-Facts-Worker';

        const again: any = await BomService.mergeBoms([ids.componentMerge, ids.release],
            { ...createTestRebomOptions({ group: ORG, ...request.overrides }), ...request.extra }, TEST_ORG_UUID);

        expect(again.metadata.component.purl).toBe(PRODUCT_ROOT);
        expect(again.metadata.component.licenses).toStrictEqual([{ expression: 'MIT OR Apache-2.0' }]);
        expect(node(again, PLACEHOLDER_REF).licenses).toStrictEqual([{ expression: 'LicenseRef-Facts-Worker' }]);
        expect(node(again, EXTERNAL_PLACEHOLDER_REF)).not.toHaveProperty('licenses');
        expect(node(again, RELEASE_NODE).licenses).toStrictEqual(NAMED);
        await expect(validateBom(again)).resolves.toBeTruthy();
    });

    it('without a licence fact no node ReARM wrote carries a licence (SCORE-40 T-5, ADR-5)', async () => {
        const { componentMerge, product } = await buildProductExport(created, true);

        expect(componentMerge.metadata.component).not.toHaveProperty('licenses');
        expect(product.metadata.component).not.toHaveProperty('licenses');
        for (const ref of [RELEASE_NODE, PLACEHOLDER_REF, EXTERNAL_PLACEHOLDER_REF]) {
            expect(node(product, ref), ref).not.toHaveProperty('licenses');
        }
        await expect(validateBom(componentMerge)).resolves.toBeTruthy();
        await expect(validateBom(product)).resolves.toBeTruthy();
    });

    it('keeps the NOASSERTION an uploaded copy of the release node carries, in the component merge and in the product (SCORE-40 T-5)', async () => {
        const { componentMerge, product, ids } = await buildProductExport(created, true, true, [{ expression: 'NOASSERTION' }]);

        expect(ids.releaseNodeCopy).toBeDefined();
        // the copy collapsed into the merged root, which keeps the uploaded licence over the facts
        expect(node(componentMerge, RELEASE_NODE)).toBeUndefined();
        expect(componentMerge.metadata.component['bom-ref']).toBe(RELEASE_NODE);
        expect(componentMerge.metadata.component.licenses).toStrictEqual([{ expression: 'NOASSERTION' }]);
        expect(componentMerge.metadata.component.supplier).toStrictEqual({ name: ORG });
        expect(node(componentMerge, 'pkg:npm/lib-f@1.0.0').licenses).toStrictEqual([{ license: { id: 'MIT' } }]);
        // in the product it is a component like any other, still NOASSERTION
        expect(node(product, RELEASE_NODE).licenses).toStrictEqual([{ expression: 'NOASSERTION' }]);
        expect(product.metadata.component.licenses).toStrictEqual(PRODUCT_ID_FORM);
        await expect(validateBom(componentMerge)).resolves.toBeTruthy();
        await expect(validateBom(product)).resolves.toBeTruthy();
    });

    it('serves a legacy deliverable row with its digest and no supplier from its group (T-9, ADR-12)', async () => {
        const id = await storeWithMeta(deliverableUpload(), { group: ORG, belongsTo: 'DELIVERABLE', hash: 'sha256:' + UPLOAD_DIGEST });

        const served: any = await BomService.findBomObjectById(id, TEST_ORG_UUID);
        expect(served.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: UPLOAD_DIGEST }]);
        expect(served.metadata.component).not.toHaveProperty('supplier');
        expect(served.metadata.component).not.toHaveProperty('pedigree');
    });

    it('serves a row stored without the fields with them, the same on every read and in a merge (T-9)', async () => {
        // A row as an upload stored it before SCORE-30: no supplier, hash or pedigree on its root,
        // with the supplier and the commit in its meta.
        const bom = sceUpload();
        const id = await storeWithMeta(bom, { supplierName: ORG, belongsTo: 'SCE', hash: COMMIT });
        const [row] = await BomRepository.bomById(id);
        expect((row.meta as any).supplierName).toBe(ORG);
        expect((row.meta as any).hash).toBe(COMMIT);

        const first: any = await BomService.findBomObjectById(id, TEST_ORG_UUID);
        expect(first.metadata.component.supplier).toStrictEqual({ name: ORG });
        expect(first.metadata.component.pedigree).toStrictEqual(COMMITS);
        expect(first.metadata.component).not.toHaveProperty('hashes');
        const second: any = await BomService.findBomObjectById(id, TEST_ORG_UUID);
        expect(JSON.stringify(second)).toBe(JSON.stringify(first));
        // by serial number, the other way the same read finds a row
        const bySerial: any = await BomService.findBomObjectById(bom.serialNumber.replace('urn:uuid:', ''), TEST_ORG_UUID);
        expect(JSON.stringify(bySerial)).toBe(JSON.stringify(first));

        // the merge reads its inputs through the same path
        const other = deliverableUpload();
        const otherRecord = await BomService.addBom({
            bomInput: {
                format: 'CYCLONEDX' as const, bom: other, org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({ serialNumber: other.serialNumber, group: ORG, name: 'api', version: '1.0.0' })
            }
        });
        created.push(otherRecord.uuid);
        const merged: any = await BomService.mergeBoms([id, otherRecord.uuid],
            createTestRebomOptions({ group: ORG, name: 'facts-api', version: '1.0.0', rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT', mergeVersion: 6 }),
            TEST_ORG_UUID);
        const input = node(merged, first.metadata.component['bom-ref']);
        expect(input.supplier).toStrictEqual({ name: ORG });
        expect(input.pedigree).toStrictEqual(COMMITS);
        expect(input).not.toHaveProperty('hashes');
    });
});

/** SCORE-44: the reliza:license:dropped entries of a node, none when it has no properties. */
function droppedRecords(component: any): any[] {
    return (component?.properties || []).filter((p: any) => p?.name === DROPPED_LICENSE_PROPERTY);
}

const LISTED_DROP = 'merge-boms will drop the listed expression: the survivor licenses stand';

/** SCORE-44: one reliza:license:dropped entry. */
const P = (value: string) => ({ name: DROPPED_LICENSE_PROPERTY, value });

/** SCORE-40 T-10: every component carrying `ref`, at any depth, the root included. */
function carrying(bom: any, ref: string): any[] {
    const walk = (list: any[] = []): any[] => list.flatMap((c: any) => [c, ...walk(c.components)]);
    return [bom.metadata.component, ...walk(bom.metadata.component?.components), ...walk(bom.components)]
        .filter((c: any) => c?.['bom-ref'] === ref);
}

/**
 * SCORE-40 (T-10, ADR-6): in a product merge the component release's node, an input root that
 * already carries the declared licence, is folded with another input's component of the same purl
 * (an application listing the release as an in-house library). The node is the survivor whatever
 * the input order: its licences stand, listed ids are unioned beside an id or named form, a listed
 * expression or NOASSERTION is dropped, and a node without a declared licence keeps the listing.
 * Every product passes validation. Real `rearm bomutils merge-boms`, in memory and stored.
 */
describe('a product merge folds the release node with a listing of its purl', () => {
    const created: string[] = [];

    beforeEach(() => {
        clearMockOciStorage();
    });

    afterEach(async () => {
        if (created.length > 0) {
            await pool.query('DELETE FROM rebom.boms WHERE uuid = ANY($1::uuid[])', [created]);
            created.length = 0;
        }
    });

    const NAMED_FORM = { license: { name: COMPONENT_LICENSE, url: COMPONENT_LICENSE_URL } };
    const DECLARED: Record<string, DeclaredLicence | undefined> = {
        named: { licenseExpression: COMPONENT_LICENSE, licenseUrl: COMPONENT_LICENSE_URL },
        id: { licenseExpression: PRODUCT_LICENSE },
        expression: { licenseExpression: 'MIT OR Apache-2.0' },
        none: undefined
    };
    const LISTED: Record<string, any[] | undefined> = {
        expression: [{ expression: 'BSD-3-Clause OR MIT' }],
        id: [{ license: { id: 'MIT' } }],
        NOASSERTION: [{ expression: 'NOASSERTION' }],
        'no key': undefined
    };
    // [declared, listed, the release node's licenses in the product (undefined: no key)]
    const CASES: [string, string, any[] | undefined][] = [
        ['named', 'expression', [NAMED_FORM]],
        ['named', 'id', [NAMED_FORM, { license: { id: 'MIT' } }]],
        ['named', 'NOASSERTION', [NAMED_FORM]],
        ['named', 'no key', [NAMED_FORM]],
        ['id', 'id', [{ license: { id: 'AGPL-3.0-only' } }, { license: { id: 'MIT' } }]],
        ['id', 'expression', [{ license: { id: 'AGPL-3.0-only' } }]],
        // the T-1 reproduction of run 1: refused by validation before ADR-6
        ['expression', 'id', [{ expression: 'MIT OR Apache-2.0' }]],
        ['expression', 'expression', [{ expression: 'MIT OR Apache-2.0' }]],
        ['expression', 'NOASSERTION', [{ expression: 'MIT OR Apache-2.0' }]],
        ['none', 'expression', [{ expression: 'BSD-3-Clause OR MIT' }]],
        ['none', 'id', [{ license: { id: 'MIT' } }]],
        ['none', 'NOASSERTION', [{ expression: 'NOASSERTION' }]],
        ['none', 'no key', undefined]
    ];
    // SCORE-44 T-5 (ADR-2): the rows whose fold drops the listed expression record it on the release node
    const RECORDED = new Set(['named|expression', 'id|expression', 'expression|expression']);

    for (const [declared, listed, expected] of CASES) {
        const records = RECORDED.has(`${declared}|${listed}`) ? [P('BSD-3-Clause OR MIT')] : [];
        it(`declared ${declared}, listed ${listed}: the release node carries ${expected === undefined ? 'no licenses' : JSON.stringify(expected)}, ${records.length ? 'the dropped expression recorded' : 'no record'}, in both orders (T-10, SCORE-44 T-5)`, async () => {
            const { merged, stored } = await buildListingProducts(created, DECLARED[declared], LISTED[listed]);

            const products = [['merged, component first', merged[0]], ['merged, listing first', merged[1]],
                ['stored, component first', stored[0]], ['stored, listing first', stored[1]]];
            for (const [label, product] of products) {
                expect(carrying(product, RELEASE_NODE), label).toHaveLength(1);
                const releaseNode = node(product, RELEASE_NODE);
                if (expected === undefined) {
                    expect(releaseNode, label).not.toHaveProperty('licenses');
                } else {
                    expect(releaseNode.licenses, label).toStrictEqual(expected);
                }
                expect(product.metadata.component.purl, label).toBe(PRODUCT_ROOT);
                expect(product.metadata.component.licenses, label).toStrictEqual([{ license: { id: 'AGPL-3.0-only' } }]);
                expect(node(product, LISTING_ROOT), label).toBeDefined();
                expect(droppedRecords(releaseNode), label).toStrictEqual(records);
                expect(droppedRecords(product.metadata.component), label).toStrictEqual([]);
                expect(droppedRecords(node(product, LISTING_ROOT)), label).toStrictEqual([]);
                await expect(validateBom(product), label).resolves.toBeTruthy();
            }
        });
    }
});

/**
 * SCORE-40 (T-10, ADR-6): the same fold on the de-dup path. The listing sits one level down, under
 * a library of the other application, where merge-boms folds nothing; dedupeBomRefs folds it into
 * the release node, the first occurrence (the top-level components sort by key, pkg:generic before
 * pkg:npm), with the same three lines. Real `rearm bomutils merge-boms`, in memory and stored.
 */
describe('a product merge folds the release node with a listing nested under another component', () => {
    const created: string[] = [];
    let info: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
        clearMockOciStorage();
        info = vi.spyOn(logger, 'info');
    });

    afterEach(async () => {
        info.mockRestore();
        if (created.length > 0) {
            await pool.query('DELETE FROM rebom.boms WHERE uuid = ANY($1::uuid[])', [created]);
            created.length = 0;
        }
    });

    const DEDUP_LINE = 'bom-ref de-dup: an expression cannot be unioned, the survivor licenses stand';
    // [case, declared, listed, the release node's licenses in the product, its reliza:license:dropped
    // entries (SCORE-44 T-6), the de-dup lines over the four merges]
    const CASES: [string, DeclaredLicence | undefined, any[], any[], any[], number][] = [
        // the de-dup twin of run 1's T-1 reproduction: refused by validation if the union read the duplicate alone
        ['declared expression, listed id', { licenseExpression: 'MIT OR Apache-2.0' }, [{ license: { id: 'MIT' } }], [{ expression: 'MIT OR Apache-2.0' }], [], 4],
        ['declared id, listed NOASSERTION', { licenseExpression: PRODUCT_LICENSE }, [{ expression: 'NOASSERTION' }], [{ license: { id: 'AGPL-3.0-only' } }], [], 4],
        ['no declared licence, listed id', undefined, [{ license: { id: 'MIT' } }], [{ license: { id: 'MIT' } }], [], 0],
        // SCORE-44 T-6: the de-dup drops the listed expression beside the declared id and records it
        ['declared id, listed expression', { licenseExpression: PRODUCT_LICENSE }, [{ expression: 'BSD-3-Clause OR MIT' }], [{ license: { id: 'AGPL-3.0-only' } }],
            [P('BSD-3-Clause OR MIT')], 4],
        // SCORE-44 T-6: the listing repeats the declared expression: nothing is lost, nothing recorded or logged
        ['declared expression, listed the same expression', { licenseExpression: 'MIT OR Apache-2.0' }, [{ expression: 'MIT OR Apache-2.0' }], [{ expression: 'MIT OR Apache-2.0' }], [], 0]
    ];

    for (const [label, declared, listed, expected, records, dedupLines] of CASES) {
        it(`${label}: the release node carries ${JSON.stringify(expected)}, ${records.length ? 'the dropped expression recorded' : 'no record'}, in both orders (T-10, SCORE-44 T-6)`, async () => {
            const { merged, stored } = await buildListingProducts(created, declared, listed, true);

            // nested, merge-boms folds nothing: no ADR-8 line; the de-dup logs its own, one per product merge
            expect(info.mock.calls.filter((call: any[]) => call[1] === LISTED_DROP)).toStrictEqual([]);
            expect(info.mock.calls.filter((call: any[]) => call[1] === DEDUP_LINE && call[0]?.bomRef === RELEASE_NODE)).toHaveLength(dedupLines);

            const products = [['merged, component first', merged[0]], ['merged, listing first', merged[1]],
                ['stored, component first', stored[0]], ['stored, listing first', stored[1]]];
            for (const [where, product] of products) {
                expect(carrying(product, RELEASE_NODE), where).toHaveLength(1);
                expect(node(product, RELEASE_NODE).licenses, where).toStrictEqual(expected);
                expect(product.metadata.component.purl, where).toBe(PRODUCT_ROOT);
                expect(product.metadata.component.licenses, where).toStrictEqual([{ license: { id: 'AGPL-3.0-only' } }]);
                expect(node(product, LISTING_ROOT), where).toBeDefined();
                expect(node(product, LISTING_MID), where).toBeDefined();
                expect(droppedRecords(node(product, RELEASE_NODE)), where).toStrictEqual(records);
                expect(droppedRecords(product.metadata.component), where).toStrictEqual([]);
                await expect(validateBom(product), where).resolves.toBeTruthy();
            }
        });
    }
});

const FOLD_LINES = [
    LISTED_DROP,
    'bom-ref de-dup: an expression cannot be unioned, the survivor licenses stand',
    'license fold settled: the survivor licenses stand'
];

/**
 * SCORE-40 (T-10, ADR-6 line 1 as round 4 states it, ADR-8): an empty licences list on the release
 * node, an uploaded root of the product merge, beside a listing of its purl with an expression.
 * Where merge-boms folds (the listing at the top level) `[]` is held: the expression is dropped and
 * ADR-8 logs it. Where dedupeBomRefs folds (the listing nested) `[]` is absent: the node takes the
 * listing whole and nothing is logged. Real `rearm bomutils merge-boms`, in memory and stored.
 */
describe('a product merge folds a release node with an empty licences list and a listing of its purl', () => {
    const created: string[] = [];
    const X2 = [{ expression: 'BSD-3-Clause OR MIT' }];
    let info: ReturnType<typeof vi.spyOn>;

    beforeEach(() => {
        clearMockOciStorage();
        info = vi.spyOn(logger, 'info');
    });

    afterEach(async () => {
        info.mockRestore();
        if (created.length > 0) {
            await pool.query('DELETE FROM rebom.boms WHERE uuid = ANY($1::uuid[])', [created]);
            created.length = 0;
        }
    });

    /** The fold lines the info spy saw. */
    function foldLines(): any[][] {
        return info.mock.calls.filter((call: any[]) => FOLD_LINES.includes(call[1]));
    }

    // [layout, nested, the release node's licenses in the product, the fold lines over the four merges,
    // its reliza:license:dropped entries (SCORE-44 T-5)]
    const CASES: [string, boolean, any[], () => any[][], any[]][] = [
        ['top level, merge-boms holds [] and drops the expression, logged and recorded', false, [],
            () => Array(4).fill([{ bomRef: RELEASE_NODE, kept: [], dropped: X2 }, LISTED_DROP]), [P('BSD-3-Clause OR MIT')]],
        ['nested, the de-dup reads [] as absent and takes the listing, nothing logged or recorded', true, X2, () => [], []]
    ];

    for (const [label, nested, expected, lines, records] of CASES) {
        it(`${label}: the release node carries ${JSON.stringify(expected)}, in both orders (T-10, SCORE-44 T-5)`, async () => {
            const { merged, stored } = await buildListingProducts(created, undefined, X2, nested, []);

            // one ADR-8 line per product merge on the top level (two in memory, two stored), none nested
            expect(foldLines()).toStrictEqual(lines());
            const products = [['merged, release node first', merged[0]], ['merged, listing first', merged[1]],
                ['stored, release node first', stored[0]], ['stored, listing first', stored[1]]];
            for (const [where, product] of products) {
                expect(carrying(product, RELEASE_NODE), where).toHaveLength(1);
                expect(node(product, RELEASE_NODE).licenses, where).toStrictEqual(expected);
                expect(product.metadata.component.purl, where).toBe(PRODUCT_ROOT);
                expect(product.metadata.component.licenses, where).toStrictEqual([{ license: { id: 'AGPL-3.0-only' } }]);
                expect(node(product, LISTING_ROOT), where).toBeDefined();
                expect(droppedRecords(node(product, RELEASE_NODE)), where).toStrictEqual(records);
                expect(droppedRecords(product.metadata.component), where).toStrictEqual([]);
                await expect(validateBom(product), where).resolves.toBeTruthy();
            }
        });
    }
});
