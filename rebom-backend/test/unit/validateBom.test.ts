import { describe, it, expect } from 'vitest';
import validateBom from '../../src/validateBom';
import { BomValidationError } from '../../src/types/errors';

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

        // The thrown BomValidationError previously contained ajv's raw output —
        // path + allowed enum + a generic "must be equal to one of the allowed
        // values" message — but never the actual offending value. Production
        // logs were essentially "X failed validation, where X is one of [list]"
        // with no way to tell what the BOM actually contained. enrichErrorsWithActualValues
        // resolves the ajv instancePath against the BOM so the actual value
        // surfaces alongside every error.
        it('should include the actual offending value on enum violation', async () => {
            const bomWithBadType = {
                bomFormat: 'CycloneDX',
                specVersion: '1.4',
                serialNumber: 'urn:uuid:00000000-0000-0000-0000-000000000000',
                version: 1,
                metadata: { timestamp: '2026-01-01T00:00:00Z', tools: [] },
                components: [
                    {
                        // 1.4 enum doesn't include "platform" — added in 1.5.
                        // Triggers exactly the production rejection class we
                        // are diagnosing.
                        type: 'platform',
                        name: 'test-lib',
                        version: '1.0.0',
                    },
                ],
            };
            await expect(validateBom(bomWithBadType)).rejects.toThrow(BomValidationError);
            try {
                await validateBom(bomWithBadType);
            } catch (err) {
                expect(err).toBeInstanceOf(BomValidationError);
                const ve = (err as BomValidationError).details?.validationErrors as any[];
                expect(Array.isArray(ve)).toBe(true);
                const enumErr = ve.find((e) => e.keyword === 'enum');
                expect(enumErr).toBeDefined();
                expect(enumErr.instancePath).toBe('/components/0/type');
                expect(enumErr.actualValue).toBe('platform');
            }
        });

        it('should preview large object values and stay bounded in length', async () => {
            // A BOM whose component.type is invalid AND whose ancestor object
            // contains a lot of data — the helper should not stringify the
            // entire object, only the leaf at the instancePath. Confirms we
            // don't accidentally start logging entire components.
            const bigComponent: any = {
                type: 'platform', // bad in 1.4
                name: 'big',
                version: '1.0',
                description: 'x'.repeat(500),
            };
            const bom = {
                bomFormat: 'CycloneDX',
                specVersion: '1.4',
                serialNumber: 'urn:uuid:11111111-1111-1111-1111-111111111111',
                version: 1,
                metadata: { timestamp: '2026-01-01T00:00:00Z', tools: [] },
                components: [bigComponent],
            };
            try {
                await validateBom(bom);
                expect.fail('expected validation to throw');
            } catch (err) {
                const ve = (err as BomValidationError).details?.validationErrors as any[];
                const enumErr = ve.find((e) => e.keyword === 'enum');
                // The leaf is the string "platform" — short, no truncation.
                expect(enumErr.actualValue).toBe('platform');
            }
        });
    });
});
