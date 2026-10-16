import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

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
import * as BomRepository from '../../src/bomRepository';
import { pool } from '../../src/utils';
import { clearMockOciStorage, fetchProcessedBomWithRetry } from '../../src/services/oci';
import { TEST_ORG_UUID, createTestRebomOptions, generateSerialNumber } from '../helpers';

/**
 * SCORE-29 (design T-5): a merge whose group and name hold spaces writes a single-encoded root
 * purl and bom-ref, and a product merge over an upload stored before the fix (its root reads
 * %2520) places that input under the repaired, single-encoded ref. Runs the real
 * `rearm bomutils merge-boms`.
 */

const FIXTURES = path.join(__dirname, '..', 'fixtures', 'merge');
const GROUP = 'SBOM Score Walkthrough';
const LEGACY_ROOT = 'pkg:generic/SBOM%2520Score%2520Walkthrough/sbom-score%2520api@1.0.0';
const FIXED_ROOT = 'pkg:generic/SBOM%20Score%20Walkthrough/sbom-score%20api@1.0.0';

function fixture(name: string): any {
    const bom = JSON.parse(fs.readFileSync(path.join(FIXTURES, name), 'utf8'));
    bom.serialNumber = generateSerialNumber();
    return bom;
}

function rootEntries(bom: any): any[] {
    const rootRef = bom.metadata.component['bom-ref'];
    return (bom.dependencies || []).filter((d: any) => d.ref === rootRef);
}

describe('merged BOM root purls are percent-encoded once', () => {
    const createdUuids: string[] = [];

    beforeEach(() => {
        clearMockOciStorage();
    });

    afterEach(async () => {
        if (createdUuids.length > 0) {
            await pool.query('DELETE FROM rebom.boms WHERE uuid = ANY($1::uuid[])', [createdUuids]);
            createdUuids.length = 0;
        }
    });

    async function upload(bom: any, name: string): Promise<string> {
        const record = await BomService.addBom({
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({ serialNumber: bom.serialNumber, group: GROUP, name, version: '1.0.0' })
            }
        });
        createdUuids.push(record.uuid);
        return record.uuid;
    }

    async function merge(ids: string[], overrides: any): Promise<any> {
        const record = await BomService.mergeAndStoreBoms(
            ids,
            createTestRebomOptions({ serialNumber: generateSerialNumber(), version: '1.0.0', mergeVersion: 5, ...overrides }),
            TEST_ORG_UUID,
            BomService.addBom
        );
        createdUuids.push(record.uuid);
        return BomService.findBomObjectById(record.uuid, TEST_ORG_UUID);
    }

    it('writes the merged root single-encoded and keys exactly one dependency entry on it', async () => {
        const appId = await upload(fixture('app.input.cdx.json'), 'sbom score api');
        const nginxId = await upload(fixture('nginx-services.input.cdx.json'), 'sbom score web');

        const bom = await merge([appId, nginxId], { group: GROUP, name: 'sbom score release', tldOnly: true });

        const root = bom.metadata.component;
        expect(root.purl).toBe('pkg:generic/SBOM%20Score%20Walkthrough/sbom%20score%20release@1.0.0?tldonly=true');
        expect(root['bom-ref']).toBe(root.purl);
        const parsed = PackageURL.fromString(root.purl);
        expect(parsed.namespace).toBe(GROUP);
        expect(parsed.name).toBe('sbom score release');
        expect(rootEntries(bom)).toHaveLength(1);
        expect(JSON.stringify(bom)).not.toContain('%25');
    });

    it('places a stored double-encoded input root under its repaired ref in a product merge', async () => {
        const legacy = fixture('app.input.cdx.json');
        legacy.metadata.component.purl = LEGACY_ROOT;
        legacy.metadata.component['bom-ref'] = LEGACY_ROOT;
        legacy.dependencies[0].ref = LEGACY_ROOT;
        const legacyId = await upload(legacy, 'sbom-score api');
        const otherId = await upload(fixture('nginx-services.input.cdx.json'), 'sbom score web');

        // The stored document is the pre-fix shape: raw group and name beside a %2520 purl.
        const [record] = await BomRepository.bomById(legacyId);
        const storedBom: any = await fetchProcessedBomWithRetry(record);
        expect(storedBom.metadata.component.purl).toBe(LEGACY_ROOT);
        expect(storedBom.metadata.component.name).toBe('sbom-score api');

        const product = await merge([legacyId, otherId], {
            group: 'Reliza', name: 'product x', rootComponentMergeMode: 'PRESERVE_UNDER_NEW_ROOT'
        });

        const node = (product.components || []).find((c: any) => c['bom-ref'] === FIXED_ROOT);
        expect(node).toBeDefined();
        expect(node.purl).toBe(FIXED_ROOT);
        expect(PackageURL.fromString(node.purl).name).toBe('sbom-score api');
        const [rootEntry] = rootEntries(product);
        expect(rootEntry.dependsOn).toContain(FIXED_ROOT);
        expect(product.metadata.component.purl).toBe('pkg:generic/Reliza/product%20x@1.0.0');
        expect(JSON.stringify(product)).not.toContain('%25');
    });
});
