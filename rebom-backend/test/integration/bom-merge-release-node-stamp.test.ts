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
import { pool } from '../../src/utils';
import { clearMockOciStorage, fetchProcessedBomWithRetry } from '../../src/services/oci';
import { TEST_ORG_UUID } from '../helpers';
import { RELEASE_NODE_PROPERTY } from '../../src/services/bom/bomProcessingService';
import { REBOM_MERGE_MOD } from '../../src/services/bom/releaseNodeStamp';
import {
    ORG, COMMIT, UPLOAD_DIGEST, PLACEHOLDER_REF, SCE_ROOT, DELIVERABLE_ROOT, RELEASE_ROOT, RELEASE_NODE, PRODUCT_ROOT,
    buildProductExport, productMergeRequest, storeUpload, storeMerge, sceUpload, deliverableUpload, releaseUpload
} from '../releaseNodeFacts.fixture';

/**
 * SCORE-60 (T-10, ADR-1; SCORE-58 ADR-5): every merge rebom stores carries `reliza:export:releaseNode`
 * on its root, persisted with the row, and a product merge over a component row keeps that row's
 * root stamped in its `components[]`; no uploaded root, placeholder or library carries it. Real
 * `rearm bomutils merge-boms`, real storage path (the releaseNodeFacts fixture).
 */

const STAMP = { name: RELEASE_NODE_PROPERTY, value: 'true' };

/** Every node of `bom` at any depth (root, components[] and their components) carrying the stamp, by bom-ref. */
function stampedRefs(bom: any): string[] {
    const out: string[] = [];
    const visit = (c: any) => {
        if (!c || typeof c !== 'object') return;
        if ((c.properties || []).some((p: any) => p.name === RELEASE_NODE_PROPERTY)) out.push(c['bom-ref']);
        (c.components || []).forEach(visit);
    };
    visit(bom.metadata?.component);
    (bom.components || []).forEach(visit);
    return out;
}

function stampsOf(c: any): any[] {
    return (c?.properties || []).filter((p: any) => p.name === RELEASE_NODE_PROPERTY);
}

describe('merged roots carry rebom\'s release node stamp', () => {
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

    it('stamps the component merge root once, on the served and the stored document, and stores it as a merge output', async () => {
        const { componentMerge, ids } = await buildProductExport(created, true);

        expect(stampsOf(componentMerge.metadata.component)).toStrictEqual([STAMP]);
        expect(componentMerge.metadata.component.properties.at(-1)).toStrictEqual(STAMP);
        const [row] = await BomRepository.bomById(ids.componentMerge);
        expect((row.meta as any).mod).toBe(REBOM_MERGE_MOD);
        const stored: any = await fetchProcessedBomWithRetry(row);
        expect(stampsOf(stored.metadata.component)).toStrictEqual([STAMP]);
        expect(stampedRefs(componentMerge)).toStrictEqual([RELEASE_NODE]);
    });

    it('a product merge: its root and the component row root carry the stamp, no uploaded root or placeholder does', async () => {
        const { product } = await buildProductExport(created, true);

        expect(product.metadata.component.purl).toBe(PRODUCT_ROOT);
        expect(stampsOf(product.metadata.component)).toStrictEqual([STAMP]);
        expect(stampedRefs(product).sort()).toStrictEqual([PRODUCT_ROOT, RELEASE_NODE].sort());
        for (const ref of [RELEASE_ROOT, SCE_ROOT, DELIVERABLE_ROOT, PLACEHOLDER_REF]) {
            expect(stampedRefs(product), ref).not.toContain(ref);
        }
    });

    for (const [label, extra] of [['top-level only', { tldOnly: true }], ['HIERARCHICAL', { structure: 'HIERARCHICAL' }]] as const) {
        it(`a ${label} product merge keeps the component row root stamped at the top level`, async () => {
            const sce = await storeUpload(created, sceUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'SCE', hash: COMMIT, supplierName: ORG });
            const deliverable = await storeUpload(created, deliverableUpload(), { name: 'facts-api', version: '1.0.0', belongsTo: 'DELIVERABLE', hash: 'sha256:' + UPLOAD_DIGEST, supplierName: ORG });
            const release = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });
            const componentMerge = await storeMerge(created, [sce, deliverable], { name: 'facts-api', version: '1.0.0', rootComponentMergeMode: 'FLATTEN_UNDER_NEW_ROOT' }, {});
            const request = productMergeRequest(true);
            const productId = await storeMerge(created, [componentMerge, release], { ...request.overrides, ...extra }, request.extra);
            const product = await BomService.findBomObjectById(productId, TEST_ORG_UUID);

            expect(stampsOf(product.metadata.component)).toStrictEqual([STAMP]);
            const releaseNode = (product.components || []).find((c: any) => c['bom-ref'] === RELEASE_NODE);
            expect(releaseNode, 'the component row root at the top level').toBeDefined();
            expect(stampsOf(releaseNode)).toStrictEqual([STAMP]);
            expect(stampedRefs(product).sort()).toStrictEqual([product.metadata.component['bom-ref'], RELEASE_NODE].sort());
        });
    }

    it('a product over a product row keeps the inner component row root stamped', async () => {
        const { ids } = await buildProductExport(created, true);
        const other = await storeUpload(created, releaseUpload(), { name: 'web', version: '2.0.0', belongsTo: 'RELEASE' });
        const outer = await storeMerge(created, [ids.product, other], { name: 'facts-suite', version: '5.0.0', rootComponentMergeMode: 'PRESERVE_UNDER_NEW_ROOT' }, {});
        const suite = await BomService.findBomObjectById(outer, TEST_ORG_UUID);

        const refs = stampedRefs(suite);
        expect(refs).toContain(suite.metadata.component['bom-ref']);
        expect(refs).toContain(PRODUCT_ROOT);
        expect(refs).toContain(RELEASE_NODE);
        expect(refs).toHaveLength(3);
    });
});
