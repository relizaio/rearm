import { describe, it, expect, beforeAll, afterAll } from 'vitest';
import * as BomService from '../../src/bomService';
import { pool } from '../../src/utils';
import { TEST_ORG_UUID, loadFixture, createTestRebomOptions, generateSerialNumber, cleanupTestBoms } from '../helpers';

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

        // Verify retrieval
        expect(retrieved).toBeDefined();
        expect(retrieved.serialNumber).toBe(serialNumber);
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

        // Verify
        expect(retrieved).toBeDefined();
        expect(retrieved.serialNumber).toBe(serialNumber);
        // Note: version field is BOM version (1), not component version (2.0.0)
        expect(retrieved.version).toBeDefined();
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
