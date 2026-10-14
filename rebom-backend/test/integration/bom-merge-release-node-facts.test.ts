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
import {
    ORG, COMMIT, UPLOAD_DIGEST, RELEASE_DIGEST, PLACEHOLDER_DIGEST, PLACEHOLDER_REF, EXTERNAL_PLACEHOLDER_REF,
    SCE_ROOT, DELIVERABLE_ROOT, RELEASE_ROOT, RELEASE_NODE, PRODUCT_ROOT,
    buildProductExport, node, sceUpload, deliverableUpload
} from '../releaseNodeFacts.fixture';

/**
 * SCORE-30 (T-8, T-9; ADR-1, ADR-3, ADR-4, ADR-6, ADR-7, ADR-12): every node ReARM writes into a
 * merged release or product SBOM names the supplier ReARM holds for it, and carries the hash or
 * the source commit ReARM holds for it. Real `rearm bomutils merge-boms`, real storage path.
 */

const COMMITS = { commits: [{ uid: COMMIT }] };

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
            hashes: [{ alg: 'SHA-256', content: RELEASE_DIGEST }]
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
