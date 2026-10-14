import { describe, it, expect, beforeAll, afterAll, vi } from 'vitest';

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
import { pool } from '../../src/utils';
import { TEST_ORG_UUID, loadFixture, createTestRebomOptions, generateSerialNumber, cleanupTestBoms } from '../helpers';
import { BomValidationError } from '../../src/types/errors';

/**
 * Integration tests for BOM CRUD operations
 * Uses real PostgreSQL database (localhost:5438 by default)
 */

describe('BOM CRUD Operations', () => {
    const createdSerialNumbers: string[] = [];

    afterAll(async () => {
        // Cleanup test data
        await cleanupTestBoms(pool, createdSerialNumbers);
    });

    it('should create a CycloneDX BOM and retrieve it', async () => {
        // Load fixture and update serialNumber to avoid conflicts
        const bomContent = loadFixture('cyclonedx-simple.json');
        const serialNumber = generateSerialNumber();
        createdSerialNumbers.push(serialNumber);
        
        // Update BOM content with unique serialNumber
        bomContent.serialNumber = serialNumber;

        // Create BOM
        const bomInput = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber,
                    name: 'test-app',
                    group: 'com.test',
                    version: '1.0.0'
                })
            }
        };

        const created = await BomService.addBom(bomInput);

        // Verify creation
        expect(created).toBeDefined();
        expect(created.uuid).toBeDefined();
        expect(created.meta.serialNumber).toBe(serialNumber);

        // Retrieve BOM by ID
        const retrieved = await BomService.findBomObjectById(created.uuid, TEST_ORG_UUID) as any;

        // Verify retrieval. The row keeps the producer's serialNumber; the
        // processed document it serves is a different document and carries its
        // own, with a BOM-Link back to the producer's.
        expect(retrieved).toBeDefined();
        expect(retrieved.serialNumber).not.toBe(serialNumber);
        expect(retrieved.externalReferences).toContainEqual(expect.objectContaining({
            type: 'bom',
            url: `urn:cdx:${serialNumber.replace('urn:uuid:', '')}/1`
        }));
        expect(retrieved.components).toBeDefined();
        expect(retrieved.components.length).toBeGreaterThanOrEqual(2); // lodash, express (may have more after dedup)
    });

    it('should detect duplicate BOMs', async () => {
        // Load fixture and update serialNumber
        const bomContent = loadFixture('cyclonedx-simple.json');
        const serialNumber = generateSerialNumber();
        createdSerialNumbers.push(serialNumber);
        bomContent.serialNumber = serialNumber;

        const bomInput = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber,
                    name: 'test-duplicate',
                    group: 'com.test',
                    version: '1.0.0',
                    bomVersion: '1'
                })
            }
        };

        // Create BOM first time
        const first = await BomService.addBom(bomInput);
        expect(first.uuid).toBeDefined();
        const firstUuid = first.uuid;

        // Create same BOM again (same serialNumber + version)
        const second = await BomService.addBom(bomInput);
        expect(second.uuid).toBe(firstUuid); // Should return same UUID for duplicate
    });

    it('should retrieve BOM by serial number and version', async () => {
        // Load fixture and update serialNumber
        const bomContent = loadFixture('cyclonedx-simple.json');
        const serialNumber = generateSerialNumber();
        createdSerialNumbers.push(serialNumber);
        bomContent.serialNumber = serialNumber;

        // Create BOM
        const bomInput = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber,
                    name: 'test-retrieve',
                    group: 'com.test',
                    version: '2.0.0',
                    bomVersion: '1'
                })
            }
        };

        await BomService.addBom(bomInput);

        // Retrieve by serial number + version
        const retrieved = await BomService.findBomBySerialNumberAndVersion(
            serialNumber,
            1, // bomVersion
            TEST_ORG_UUID,
            false // not raw
        ) as any;

        // Verify. Looked up by the producer's serialNumber -- the row's
        // identity -- and served as the processed document, under its own.
        expect(retrieved).toBeDefined();
        expect(retrieved.serialNumber).not.toBe(serialNumber);
        // Note: version field is BOM version (1), not component version (2.0.0)
        // It stays the producer's: rearm-core reads it back off this document.
        expect(retrieved.version).toBe(1);
    });

    it('should retrieve BOM metadata by serial number', async () => {
        // Load fixture and update serialNumber
        const bomContent = loadFixture('cyclonedx-simple.json');
        const serialNumber = generateSerialNumber();
        createdSerialNumbers.push(serialNumber);
        bomContent.serialNumber = serialNumber;

        // Create BOM
        const bomInput = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber,
                    name: 'test-metadata',
                    group: 'com.test',
                    version: '3.0.0'
                })
            }
        };

        await BomService.addBom(bomInput);

        // Retrieve metadata
        const metaList = await BomService.findBomMetasBySerialNumber(serialNumber, TEST_ORG_UUID);

        // Verify
        expect(metaList).toBeDefined();
        expect(Array.isArray(metaList)).toBe(true);
        expect(metaList.length).toBeGreaterThan(0);
        expect(metaList[0].serialNumber).toBe(serialNumber);
        expect(metaList[0].name).toBe('test-metadata');
    });
});

/**
 * SCORE-40 T-12 (ADR-6): the licence settle runs on the merge path only. An uploaded `licenses`
 * array that mixes an expression with license entries is the generator's error and is refused at
 * store with the CycloneDX 1.6 error; the two homogeneous shapes of the same document are stored
 * and served as uploaded.
 */
describe('an uploaded mixed licenses array is refused at store', () => {
    const createdSerialNumbers: string[] = [];
    const MIXED = 'pkg:npm/mixed@1.0.0';

    afterAll(async () => {
        await cleanupTestBoms(pool, createdSerialNumbers);
    });

    function upload(licenses: any[]): { serialNumber: string, input: any } {
        const serialNumber = generateSerialNumber();
        createdSerialNumbers.push(serialNumber);
        const bom = {
            bomFormat: 'CycloneDX',
            specVersion: '1.6',
            serialNumber,
            version: 1,
            metadata: { component: { type: 'application', name: 'mixed-app', version: '1.0.0', 'bom-ref': 'mixed-app' } },
            components: [{ type: 'library', name: 'mixed', version: '1.0.0', purl: MIXED, 'bom-ref': MIXED, licenses }],
            dependencies: [{ ref: 'mixed-app', dependsOn: [MIXED] }, { ref: MIXED, dependsOn: [] }]
        };
        return {
            serialNumber,
            input: {
                bomInput: {
                    format: 'CYCLONEDX' as const,
                    bom,
                    org: TEST_ORG_UUID,
                    rebomOptions: createTestRebomOptions({ serialNumber, name: 'mixed-app', group: 'com.test', version: '1.0.0' })
                }
            }
        };
    }

    it('rejects an expression beside a license id with the 1.6 error and stores nothing (T-12)', async () => {
        const { serialNumber, input } = upload([{ expression: 'MIT OR Apache-2.0' }, { license: { id: 'MIT' } }]);

        const refused = BomService.addBom(input);

        await expect(refused).rejects.toBeInstanceOf(BomValidationError);
        await expect(refused).rejects.toThrow('/components/0/licenses');
        await expect(refused).rejects.toThrow("must have required property 'license'");
        const rows = await pool.query("SELECT uuid FROM rebom.boms WHERE meta->>'serialNumber' = $1", [serialNumber]);
        expect(rows.rows).toStrictEqual([]);
    });

    const HOMOGENEOUS: [string, any[]][] = [
        ['one expression', [{ expression: 'MIT OR Apache-2.0' }]],
        ['license ids', [{ license: { id: 'MIT' } }, { license: { id: 'Apache-2.0' } }]]
    ];
    for (const [label, licenses] of HOMOGENEOUS) {
        it(`stores and serves ${label} unchanged (T-12)`, async () => {
            const { input } = upload(structuredClone(licenses));

            const created = await BomService.addBom(input);
            const served = await BomService.findBomObjectById(created.uuid, TEST_ORG_UUID) as any;

            expect(served.components.filter((c: any) => c['bom-ref'] === MIXED).map((c: any) => c.licenses)).toStrictEqual([licenses]);
        });
    }
});
