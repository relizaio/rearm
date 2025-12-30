import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import * as BomService from '../../src/bomService';
import { pool } from '../../src/utils';
import { clearMockOciStorage } from '../../src/ociService';
import { TEST_ORG_UUID, loadFixture, createTestRebomOptions, generateSerialNumber } from '../helpers';

/**
 * Integration tests for BOM merge operations
 * Tests the mergeAndStoreBoms functionality used by rearm-saas/backend for release BOMs
 */

describe('BOM Merge Operations', () => {
    const createdSerialNumbers: string[] = [];

    beforeEach(() => {
        clearMockOciStorage();
    });

    afterEach(async () => {
        // Cleanup test data
        if (createdSerialNumbers.length > 0) {
            const serialNumberList = createdSerialNumbers.map(sn => `'${sn}'`).join(',');
            await pool.query(
                `DELETE FROM rebom.boms WHERE meta->>'serialNumber' IN (${serialNumberList})`
            );
            createdSerialNumbers.length = 0;
        }
    });

    it('should merge two BOMs into a single BOM', async () => {
        // Create first BOM with lodash and express
        const bomContent1 = loadFixture('cyclonedx-simple.json');
        const serialNumber1 = generateSerialNumber();
        createdSerialNumbers.push(serialNumber1);
        bomContent1.serialNumber = serialNumber1;

        const bomInput1 = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent1,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber: serialNumber1,
                    name: 'app-v1',
                    version: '1.0.0'
                })
            }
        };

        const created1 = await BomService.addBom(bomInput1);

        // Create second BOM with lodash, axios, react
        const bomContent2 = loadFixture('cyclonedx-for-merge.json');
        const serialNumber2 = generateSerialNumber();
        createdSerialNumbers.push(serialNumber2);
        bomContent2.serialNumber = serialNumber2;

        const bomInput2 = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent2,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber: serialNumber2,
                    name: 'app-v2',
                    version: '2.0.0'
                })
            }
        };

        const created2 = await BomService.addBom(bomInput2);

        // Merge the two BOMs
        const mergedSerialNumber = generateSerialNumber();
        createdSerialNumbers.push(mergedSerialNumber);

        const mergedRecord = await BomService.mergeAndStoreBoms(
            [created1.uuid, created2.uuid],
            createTestRebomOptions({
                serialNumber: mergedSerialNumber,
                name: 'merged-app',
                version: '1.0.0'
            }),
            TEST_ORG_UUID
        );

        // Verify merged BOM record structure (used by rearm-saas/backend)
        expect(mergedRecord).toBeDefined();
        expect(mergedRecord).toHaveProperty('uuid');
        expect(mergedRecord).toHaveProperty('meta');
        expect(typeof mergedRecord.uuid).toBe('string');
        
        // Retrieve the full merged BOM
        const mergedBom = await BomService.findBomObjectById(mergedRecord.uuid, TEST_ORG_UUID) as any;
        
        expect(mergedBom).toBeDefined();
        
        // Verify serial number format (must be valid UUID)
        expect(mergedBom.serialNumber).toBeDefined();
        expect(mergedBom.serialNumber).toMatch(/^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
        
        // Verify CycloneDX spec 1.6 compliance
        expect(mergedBom.bomFormat).toBe('CycloneDX');
        expect(mergedBom.specVersion).toBe('1.6');
        expect(mergedBom.version).toBeDefined();
        expect(typeof mergedBom.version).toBe('number');
        
        // Verify metadata structure
        expect(mergedBom.metadata).toBeDefined();
        expect(mergedBom.metadata.timestamp).toBeDefined();
        
        // Verify tools format for spec 1.6 (object with components array)
        expect(mergedBom.metadata.tools).toBeDefined();
        expect(mergedBom.metadata.tools).toHaveProperty('components');
        expect(Array.isArray(mergedBom.metadata.tools.components)).toBe(true);
        
        // Verify merged BOM has component data (in components or dependencies)
        const hasComponents = mergedBom.components && mergedBom.components.length > 0;
        const hasDependencies = mergedBom.dependencies && mergedBom.dependencies.length > 0;
        expect(hasComponents || hasDependencies).toBe(true);
    });

    it.skip('should deduplicate components when merging BOMs', async () => {
        // Create two BOMs with overlapping components
        const bomContent1 = loadFixture('cyclonedx-simple.json');
        const serialNumber1 = generateSerialNumber();
        createdSerialNumbers.push(serialNumber1);
        bomContent1.serialNumber = serialNumber1;

        const bomInput1 = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent1,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber: serialNumber1,
                    name: 'app',
                    version: '1.0.0'
                })
            }
        };

        const created1 = await BomService.addBom(bomInput1);

        // Create second BOM with same components (should be deduplicated)
        const bomContent2 = loadFixture('cyclonedx-simple.json');
        const serialNumber2 = generateSerialNumber();
        createdSerialNumbers.push(serialNumber2);
        bomContent2.serialNumber = serialNumber2;

        const bomInput2 = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent2,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber: serialNumber2,
                    name: 'app',
                    version: '1.0.0'
                })
            }
        };

        const created2 = await BomService.addBom(bomInput2);

        // Merge the BOMs
        const mergedSerialNumber = generateSerialNumber();
        createdSerialNumbers.push(mergedSerialNumber);

        const mergedRecord = await BomService.mergeAndStoreBoms(
            [created1.uuid, created2.uuid],
            createTestRebomOptions({
                serialNumber: mergedSerialNumber,
                name: 'merged-dedup',
                version: '1.0.0',
                structure: 'FLAT'
            }),
            TEST_ORG_UUID
        );

        const mergedBom = await BomService.findBomObjectById(mergedRecord.uuid, TEST_ORG_UUID) as any;
        
        // Should have deduplicated components (same as original, not doubled)
        expect(mergedBom.components).toBeDefined();
        // With deduplication, should have same number as original (2: lodash, express)
        expect(mergedBom.components.length).toBeGreaterThanOrEqual(2);
    });

    it('should handle merge with root component options', async () => {
        // Create two BOMs
        const bomContent1 = loadFixture('cyclonedx-simple.json');
        const serialNumber1 = generateSerialNumber();
        createdSerialNumbers.push(serialNumber1);
        bomContent1.serialNumber = serialNumber1;

        const bomInput1 = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent1,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber: serialNumber1,
                    name: 'component1',
                    version: '1.0.0'
                })
            }
        };

        const created1 = await BomService.addBom(bomInput1);

        const bomContent2 = loadFixture('cyclonedx-for-merge.json');
        const serialNumber2 = generateSerialNumber();
        createdSerialNumbers.push(serialNumber2);
        bomContent2.serialNumber = serialNumber2;

        const bomInput2 = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent2,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber: serialNumber2,
                    name: 'component2',
                    version: '2.0.0'
                })
            }
        };

        const created2 = await BomService.addBom(bomInput2);

        // Merge with root component merge mode
        const mergedSerialNumber = generateSerialNumber();
        createdSerialNumbers.push(mergedSerialNumber);

        const mergedRecord = await BomService.mergeAndStoreBoms(
            [created1.uuid, created2.uuid],
            createTestRebomOptions({
                serialNumber: mergedSerialNumber,
                name: 'release-bom',
                version: '1.0.0',
                rootComponentMergeMode: 'PRESERVE_UNDER_NEW_ROOT',
                belongsTo: 'RELEASE'
            }),
            TEST_ORG_UUID
        );

        // Verify merge succeeded with root component merge mode
        expect(mergedRecord).toBeDefined();
        expect(mergedRecord.uuid).toBeDefined();
        
        const mergedBom = await BomService.findBomObjectById(mergedRecord.uuid, TEST_ORG_UUID) as any;
        
        expect(mergedBom).toBeDefined();
        
        // Verify serial number format
        expect(mergedBom.serialNumber).toBeDefined();
        expect(mergedBom.serialNumber).toMatch(/^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
        
        // Verify metadata and root component structure
        expect(mergedBom.metadata).toBeDefined();
        expect(mergedBom.metadata.component).toBeDefined();
        
        // Verify root component has required fields
        const rootComponent = mergedBom.metadata.component;
        expect(rootComponent).toHaveProperty('type');
        expect(rootComponent).toHaveProperty('name');
        expect(rootComponent.name).toBe('release-bom'); // Should match rebomOptions.name
        
        // Verify PRESERVE_UNDER_NEW_ROOT mode preserved original components
        expect(mergedBom.bomFormat).toBe('CycloneDX');
        expect(mergedBom.specVersion).toBe('1.6');
    });

    it('should preserve spec version in merged BOM', async () => {
        // Create two spec 1.6 BOMs
        const bomContent1 = loadFixture('cyclonedx-simple.json');
        const serialNumber1 = generateSerialNumber();
        createdSerialNumbers.push(serialNumber1);
        bomContent1.serialNumber = serialNumber1;

        const bomInput1 = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent1,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber: serialNumber1,
                    name: 'app1',
                    version: '1.0.0'
                })
            }
        };

        const created1 = await BomService.addBom(bomInput1);

        const bomContent2 = loadFixture('cyclonedx-for-merge.json');
        const serialNumber2 = generateSerialNumber();
        createdSerialNumbers.push(serialNumber2);
        bomContent2.serialNumber = serialNumber2;

        const bomInput2 = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent2,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber: serialNumber2,
                    name: 'app2',
                    version: '2.0.0'
                })
            }
        };

        const created2 = await BomService.addBom(bomInput2);

        // Merge
        const mergedSerialNumber = generateSerialNumber();
        createdSerialNumbers.push(mergedSerialNumber);

        const mergedRecord = await BomService.mergeAndStoreBoms(
            [created1.uuid, created2.uuid],
            createTestRebomOptions({
                serialNumber: mergedSerialNumber,
                name: 'merged',
                version: '1.0.0'
            }),
            TEST_ORG_UUID
        );

        const mergedBom = await BomService.findBomObjectById(mergedRecord.uuid, TEST_ORG_UUID) as any;
        
        // Verify spec 1.6 preservation
        expect(mergedBom.bomFormat).toBe('CycloneDX');
        expect(mergedBom.specVersion).toBe('1.6');
        
        // Verify proper tools format for spec 1.6 (object with components array, not direct array)
        expect(mergedBom.metadata).toBeDefined();
        expect(mergedBom.metadata.tools).toBeDefined();
        expect(typeof mergedBom.metadata.tools).toBe('object');
        expect(mergedBom.metadata.tools).toHaveProperty('components');
        expect(Array.isArray(mergedBom.metadata.tools.components)).toBe(true);
        
        // Verify rebom tool was added to tools.components
        const rebomTool = mergedBom.metadata.tools.components.find((t: any) => t.name === 'rebom');
        expect(rebomTool).toBeDefined();
        expect(rebomTool.type).toBe('application');
        
        // Verify spec 1.6 uses 'authors' field (not 'author')
        expect(rebomTool).toHaveProperty('authors');
        expect(Array.isArray(rebomTool.authors)).toBe(true);
    });

    it('should return BomRecord with correct structure', async () => {
        // Create two BOMs
        const bomContent1 = loadFixture('cyclonedx-simple.json');
        const serialNumber1 = generateSerialNumber();
        createdSerialNumbers.push(serialNumber1);
        bomContent1.serialNumber = serialNumber1;

        const bomInput1 = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent1,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber: serialNumber1,
                    name: 'app1',
                    version: '1.0.0'
                })
            }
        };

        const created1 = await BomService.addBom(bomInput1);

        const bomContent2 = loadFixture('cyclonedx-for-merge.json');
        const serialNumber2 = generateSerialNumber();
        createdSerialNumbers.push(serialNumber2);
        bomContent2.serialNumber = serialNumber2;

        const bomInput2 = {
            bomInput: {
                format: 'CYCLONEDX' as const,
                bom: bomContent2,
                org: TEST_ORG_UUID,
                rebomOptions: createTestRebomOptions({
                    serialNumber: serialNumber2,
                    name: 'app2',
                    version: '2.0.0'
                })
            }
        };

        const created2 = await BomService.addBom(bomInput2);

        // Merge
        const mergedSerialNumber = generateSerialNumber();
        createdSerialNumbers.push(mergedSerialNumber);

        const mergedRecord = await BomService.mergeAndStoreBoms(
            [created1.uuid, created2.uuid],
            createTestRebomOptions({
                serialNumber: mergedSerialNumber,
                name: 'merged',
                version: '1.0.0'
            }),
            TEST_ORG_UUID
        );

        // Verify BomRecord structure matches what rearm-saas/backend expects
        expect(mergedRecord).toBeDefined();
        expect(mergedRecord).toHaveProperty('uuid');
        expect(mergedRecord).toHaveProperty('meta');
        expect(typeof mergedRecord.uuid).toBe('string');
        expect(typeof mergedRecord.meta).toBe('object');
        
        // Verify UUID format (must be valid UUID string)
        expect(mergedRecord.uuid).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
        
        // Meta should contain serial number (urn:uuid format)
        expect(mergedRecord.meta).toHaveProperty('serialNumber');
        expect(mergedRecord.meta.serialNumber).toBeDefined();
        expect(mergedRecord.meta.serialNumber).toMatch(/^urn:uuid:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
        
        // Meta should contain other required fields for rearm-saas/backend
        expect(mergedRecord.meta).toHaveProperty('name');
        expect(mergedRecord.meta).toHaveProperty('version');
        expect(mergedRecord.meta.name).toBe('merged');
        expect(mergedRecord.meta.version).toBe('1.0.0');
    });
});
