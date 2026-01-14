import { describe, it, expect } from 'vitest';
import validateBom from '../../src/validateBom';

/**
 * Unit tests for BOM validation
 * Tests JSON schema validation for CycloneDX BOMs
 */

describe('BOM Validation - Unit Tests', () => {
    describe('validateBom', () => {
        it('should validate a valid CycloneDX BOM', () => {
            const validBom = {
                bomFormat: 'CycloneDX',
                specVersion: '1.4',
                serialNumber: 'urn:uuid:3e671687-395b-41f5-a30f-a58921a69b79',
                version: 1,
                metadata: {
                    timestamp: '2023-01-01T00:00:00Z',
                    tools: []
                },
                components: []
            };

            expect(() => validateBom(validBom)).not.toThrow();
        });

        it('should accept BOM with valid structure', () => {
            const validBom = {
                bomFormat: 'CycloneDX',
                specVersion: '1.4',
                serialNumber: 'urn:uuid:3e671687-395b-41f5-a30f-a58921a69b79',
                version: 1,
                metadata: {
                    timestamp: '2023-01-01T00:00:00Z',
                    tools: []
                },
                components: [
                    {
                        type: 'library',
                        name: 'test-lib',
                        version: '1.0.0'
                    }
                ]
            };

            // Should not throw
            expect(() => validateBom(validBom)).not.toThrow();
        });
    });
});
