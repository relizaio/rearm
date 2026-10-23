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
import validateBom from '../../src/validateBom';
import { pool } from '../../src/utils';
import { clearMockOciStorage } from '../../src/services/oci';
import { TEST_ORG_UUID, createTestRebomOptions, generateSerialNumber } from '../helpers';
import { logger } from '../../src/logger';
import { DROPPED_LICENSE_PROPERTY } from '../../src/services/bom/bomProcessingService';
import {
    ORG, RELEASE_NODE, PRODUCT_LICENSE, LISTING_ROOT, DROPPED_MERGE_VERSION,
    node, buildListingProducts, releaseNodeListing
} from '../releaseNodeFacts.fixture';

/**
 * SCORE-44 (T-7, ADR-1, ADR-2): a product of products. The inner product, over the component
 * release (declared AGPL-3.0-only) and a listing of its node with an expression, records the
 * dropped expression on the release node; a second listing application lists the node again with
 * another expression, and the outer product folds the two. Which holder survives depends on the
 * input order: the inner product first keeps the declaration and adds the second record; the
 * listing first keeps its expression (SCORE-40 as built), and the declared id the settle then
 * drops is not recorded. Real `rearm bomutils merge-boms`, in memory and stored.
 */

const OTHER_LISTING_ROOT = 'pkg:npm/other-app@2.0.0';
const X2_TEXT = 'BSD-3-Clause OR MIT';
const X3_TEXT = 'GPL-2.0-only WITH Classpath-exception-2.0';
const LISTED_DROP = 'merge-boms will drop the listed expression: the survivor licenses stand';
const SETTLED = 'license fold settled: the survivor licenses stand';

const P = (value: string) => ({ name: DROPPED_LICENSE_PROPERTY, value });

function droppedRecords(component: any): any[] {
    return (component?.properties || []).filter((p: any) => p?.name === DROPPED_LICENSE_PROPERTY);
}

describe('a product of products records each listed expression dropped on the release node', () => {
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

    /** The inner product (component first, stored) and the second listing upload, as record ids. */
    async function inputs(): Promise<{ inner: string, listing: string }> {
        const { stored, storedIds } = await buildListingProducts(created, { licenseExpression: PRODUCT_LICENSE }, [{ expression: X2_TEXT }]);
        // the inner product as T-5 pins it: the declaration, and the first record
        expect(node(stored[0], RELEASE_NODE).licenses).toStrictEqual([{ license: { id: 'AGPL-3.0-only' } }]);
        expect(droppedRecords(node(stored[0], RELEASE_NODE))).toStrictEqual([P(X2_TEXT)]);
        const bom = releaseNodeListing([{ expression: X3_TEXT }], false, OTHER_LISTING_ROOT);
        const record = await BomService.addBom({
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({ serialNumber: bom.serialNumber, group: ORG, name: 'other-app', version: '2.0.0', purl: OTHER_LISTING_ROOT })
            }
        });
        created.push(record.uuid);
        return { inner: storedIds[0], listing: record.uuid };
    }

    const outerOptions = () => ({
        ...createTestRebomOptions({
            serialNumber: generateSerialNumber(), group: ORG, name: 'facts-portfolio', version: '4.0.0',
            rootComponentMergeMode: 'PRESERVE_UNDER_NEW_ROOT', mergeVersion: DROPPED_MERGE_VERSION
        }),
        releaseNode: { supplierName: ORG, licenseExpression: PRODUCT_LICENSE }
    });

    /** The outer product over `ids`, in memory and stored and served, each with the fold lines it logged. */
    async function outer(ids: string[]): Promise<[string, any, any[][]][]> {
        const lines = () => {
            const seen = info.mock.calls.filter((call: any[]) => call[1] === LISTED_DROP || call[1] === SETTLED);
            info.mockClear();
            return seen;
        };
        info.mockClear();
        const merged = await BomService.mergeBoms(ids, outerOptions(), TEST_ORG_UUID);
        const mergedLines = lines();
        const record = await BomService.mergeAndStoreBoms(ids, outerOptions(), TEST_ORG_UUID, BomService.addBom);
        created.push(record.uuid);
        const storedLines = lines();
        const stored = await BomService.findBomObjectById(record.uuid, TEST_ORG_UUID);
        return [['in memory', merged, mergedLines], ['stored', stored, storedLines]];
    }

    function expectNoRecordElsewhere(product: any, where: string): void {
        expect(droppedRecords(product.metadata.component), where).toStrictEqual([]);
        expect(droppedRecords(node(product, LISTING_ROOT)), where).toStrictEqual([]);
        expect(droppedRecords(node(product, OTHER_LISTING_ROOT)), where).toStrictEqual([]);
    }

    it('inner product first: the declaration stands, the record survives the merge above and the next drop is added (T-7)', async () => {
        const { inner, listing } = await inputs();

        for (const [where, product, lines] of await outer([inner, listing])) {
            const releaseNode = node(product, RELEASE_NODE);
            expect(releaseNode.licenses, where).toStrictEqual([{ license: { id: 'AGPL-3.0-only' } }]);
            expect(droppedRecords(releaseNode), where).toStrictEqual([P(X2_TEXT), P(X3_TEXT)]);
            expect(lines.filter(call => call[1] === LISTED_DROP), where).toStrictEqual([[
                { bomRef: RELEASE_NODE, kept: [{ license: { id: 'AGPL-3.0-only' } }], dropped: [{ expression: X3_TEXT }] }, LISTED_DROP
            ]]);
            expectNoRecordElsewhere(product, where);
            await expect(validateBom(product), where).resolves.toBeTruthy();
        }
    });

    it('listing first: the listing\'s expression stands, the inner record is carried, the dropped id is logged and not recorded (T-7, F-3, SCORE-68 T-4)', async () => {
        const { inner, listing } = await inputs();

        for (const [where, product, lines] of await outer([listing, inner])) {
            const releaseNode = node(product, RELEASE_NODE);
            expect(releaseNode.licenses, where).toStrictEqual([{ expression: X3_TEXT }]);
            expect(droppedRecords(releaseNode), where).toStrictEqual([P(X2_TEXT)]);
            // rearm-cli 26.10.15 drops the id beside the kept expression (SCORE-68 T-4): rebom's
            // prediction names it, and nothing is left for the settle
            expect(lines.filter(call => call[1] === LISTED_DROP), where).toStrictEqual([[
                { bomRef: RELEASE_NODE, kept: [{ expression: X3_TEXT }], dropped: [{ license: { id: 'AGPL-3.0-only' } }] }, LISTED_DROP
            ]]);
            expect(lines.filter(call => call[1] === SETTLED && call[0]?.bomRef === RELEASE_NODE), where).toHaveLength(0);
            expectNoRecordElsewhere(product, where);
            await expect(validateBom(product), where).resolves.toBeTruthy();
        }
    });
});
