import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import * as BomService from '../../src/bomService';
import { pool } from '../../src/utils';
import { clearMockOciStorage } from '../../src/services/oci';
import { TEST_ORG_UUID, loadFixture, createTestRebomOptions, generateSerialNumber } from '../helpers';

/**
 * Unit tests for BOM Service
 * Tests core BOM operations with mocked dependencies
 */

describe('BOM Service - Unit Tests', () => {
    const createdSerialNumbers: string[] = [];

    beforeEach(() => {
        // Clear OCI mock storage
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

    describe('addBom', () => {
        it('should create BOM and return metadata', async () => {
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
                        name: 'unit-test-bom',
                        version: '1.0.0'
                    })
                }
            };

            const result = await BomService.addBom(bomInput);

            expect(result).toBeDefined();
            expect(result.uuid).toBeDefined();
            expect(result.meta).toBeDefined();
            expect(result.meta.serialNumber).toBe(serialNumber);
        });

        it('should detect duplicate BOM on second upload', async () => {
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
                        bomVersion: '1'
                    })
                }
            };

            const first = await BomService.addBom(bomInput);
            const second = await BomService.addBom(bomInput);

            expect(first.uuid).toBe(second.uuid);
        });

        it('should validate BOM format', async () => {
            const invalidBom = { invalid: 'structure' };
            const serialNumber = generateSerialNumber();

            const bomInput = {
                bomInput: {
                    format: 'CYCLONEDX' as const,
                    bom: invalidBom,
                    org: TEST_ORG_UUID,
                    rebomOptions: createTestRebomOptions({ serialNumber })
                }
            };

            // Should throw validation error
            await expect(BomService.addBom(bomInput)).rejects.toThrow();
        });
    });

    describe('findBomObjectById', () => {
        it('should retrieve BOM by UUID', async () => {
            const bomContent = loadFixture('cyclonedx-simple.json');
            const serialNumber = generateSerialNumber();
            createdSerialNumbers.push(serialNumber);
            bomContent.serialNumber = serialNumber;

            const bomInput = {
                bomInput: {
                    format: 'CYCLONEDX' as const,
                    bom: bomContent,
                    org: TEST_ORG_UUID,
                    rebomOptions: createTestRebomOptions({ serialNumber })
                }
            };

            const created = await BomService.addBom(bomInput);
            const retrieved = await BomService.findBomObjectById(created.uuid, TEST_ORG_UUID) as any;

            expect(retrieved).toBeDefined();
            expect(retrieved.serialNumber).toBe(serialNumber);
        });

        it('should throw error for non-existent BOM', async () => {
            const nonExistentUuid = '00000000-0000-0000-0000-000000000000';

            await expect(
                BomService.findBomObjectById(nonExistentUuid, TEST_ORG_UUID)
            ).rejects.toThrow('BOM not found');
        });

        it('should augment BOM with metadata', async () => {
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
                        name: 'test-component',
                        group: 'com.test',
                        version: '1.0.0'
                    })
                }
            };

            const created = await BomService.addBom(bomInput);
            const retrieved = await BomService.findBomObjectById(created.uuid, TEST_ORG_UUID) as any;

            // Should have metadata from rebomOptions
            expect(retrieved.metadata).toBeDefined();
            expect(retrieved.metadata.component).toBeDefined();
        });
    });

    describe('findBomBySerialNumberAndVersion', () => {
        it('should retrieve BOM by serial number and version', async () => {
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
                        bomVersion: '1'
                    })
                }
            };

            await BomService.addBom(bomInput);
            const retrieved = await BomService.findBomBySerialNumberAndVersion(
                serialNumber,
                1,
                TEST_ORG_UUID,
                false
            ) as any;

            expect(retrieved).toBeDefined();
            expect(retrieved.serialNumber).toBe(serialNumber);
        });

        it('should return raw BOM when raw=true', async () => {
            const bomContent = loadFixture('cyclonedx-simple.json');
            const serialNumber = generateSerialNumber();
            createdSerialNumbers.push(serialNumber);
            bomContent.serialNumber = serialNumber;

            const bomInput = {
                bomInput: {
                    format: 'CYCLONEDX' as const,
                    bom: bomContent,
                    org: TEST_ORG_UUID,
                    rebomOptions: createTestRebomOptions({ serialNumber })
                }
            };

            await BomService.addBom(bomInput);
            const rawBom = await BomService.findBomBySerialNumberAndVersion(
                serialNumber,
                1,
                TEST_ORG_UUID,
                true
            ) as any;

            // Raw BOM should match original structure
            expect(rawBom.serialNumber).toBe(serialNumber);
            expect(rawBom.components).toBeDefined();
        });
    });

    describe('bomMetaBySerialNumber', () => {
        it('should return all versions of a BOM', async () => {
            const bomContent = loadFixture('cyclonedx-simple.json');
            const serialNumber = generateSerialNumber();
            createdSerialNumbers.push(serialNumber);
            bomContent.serialNumber = serialNumber;

            // Create version 1
            const bomInput1 = {
                bomInput: {
                    format: 'CYCLONEDX' as const,
                    bom: bomContent,
                    org: TEST_ORG_UUID,
                    rebomOptions: createTestRebomOptions({
                        serialNumber,
                        bomVersion: '1'
                    })
                }
            };
            await BomService.addBom(bomInput1);

            const metas = await BomService.findBomMetasBySerialNumber(serialNumber, TEST_ORG_UUID);

            expect(metas).toBeDefined();
            expect(metas.length).toBeGreaterThan(0);
            expect(metas[0].serialNumber).toBe(serialNumber);
        });
    });

    describe('Serial Number Handling', () => {
        it('should accept serial number with urn:uuid: prefix', async () => {
            const bomContent = loadFixture('cyclonedx-simple.json');
            const uuid = generateSerialNumber().replace('urn:uuid:', '');
            const serialNumber = `urn:uuid:${uuid}`;
            createdSerialNumbers.push(serialNumber);
            bomContent.serialNumber = serialNumber;

            const bomInput = {
                bomInput: {
                    format: 'CYCLONEDX' as const,
                    bom: bomContent,
                    org: TEST_ORG_UUID,
                    rebomOptions: createTestRebomOptions({ serialNumber })
                }
            };

            const result = await BomService.addBom(bomInput);
            expect(result.meta.serialNumber).toBe(serialNumber);
        });

        it('should normalize serial number to include urn:uuid: prefix', async () => {
            const bomContent = loadFixture('cyclonedx-simple.json');
            const uuid = generateSerialNumber().replace('urn:uuid:', '');
            const fullSerialNumber = `urn:uuid:${uuid}`;
            createdSerialNumbers.push(fullSerialNumber);
            // BOM content must have urn:uuid: prefix for validation
            bomContent.serialNumber = fullSerialNumber;

            const bomInput = {
                bomInput: {
                    format: 'CYCLONEDX' as const,
                    bom: bomContent,
                    org: TEST_ORG_UUID,
                    rebomOptions: createTestRebomOptions({ serialNumber: fullSerialNumber })
                }
            };

            const result = await BomService.addBom(bomInput);
            expect(result.meta.serialNumber).toBe(fullSerialNumber);
        });
    });
});
