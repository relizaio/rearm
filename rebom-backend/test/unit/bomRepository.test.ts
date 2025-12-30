import { describe, it, expect, afterEach } from 'vitest';
import * as BomRepository from '../../src/bomRespository';
import { pool } from '../../src/utils';
import { TEST_ORG_UUID, generateSerialNumber } from '../helpers';

/**
 * Unit tests for BOM repository functions
 * Tests database query operations
 */

describe('BOM Repository - Unit Tests', () => {
    const createdSerialNumbers: string[] = [];

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

    describe('bomById', () => {
        it('should return empty array for non-existent UUID', async () => {
            const nonExistentUuid = '00000000-0000-0000-0000-000000000000';
            
            const result = await BomRepository.bomById(nonExistentUuid);
            
            expect(result).toBeDefined();
            expect(Array.isArray(result)).toBe(true);
            expect(result.length).toBe(0);
        });
    });

    describe('bomBySerialNumber', () => {
        it('should return empty array for non-existent serial number', async () => {
            const nonExistentSerial = generateSerialNumber();
            
            const result = await BomRepository.bomBySerialNumber(nonExistentSerial, TEST_ORG_UUID);
            
            expect(result).toBeDefined();
            expect(Array.isArray(result)).toBe(true);
            expect(result.length).toBe(0);
        });
    });

    describe('allBomsBySerialNumber', () => {
        it('should return empty array for non-existent serial number', async () => {
            const nonExistentSerial = generateSerialNumber();
            
            const result = await BomRepository.allBomsBySerialNumber(
                nonExistentSerial,
                TEST_ORG_UUID
            );
            
            expect(result).toBeDefined();
            expect(Array.isArray(result)).toBe(true);
            expect(result.length).toBe(0);
        });
    });
});
