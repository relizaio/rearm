import { describe, it, expect } from 'vitest';
import { downgradeCycloneDxSpecIfNeeded } from '../../src/services/cyclonedx/cdxSpecDowngrade';

/**
 * Unit tests for the CycloneDX 1.7 → 1.6 downgrade shim. Lives at the
 * BOM-receive boundary in `addCycloneDxBom` so all downstream (validation,
 * augmentation, BEAR enrichment) sees a spec our libraries actually
 * support. Pull this shim once cyclonedx-go / cyclonedx-core-java /
 * cyclonedx-javascript-library all ship 1.7 support.
 */
describe('cdxSpecDowngrade', () => {
    describe('downgradeCycloneDxSpecIfNeeded', () => {
        it('rewrites specVersion 1.7 → 1.6', () => {
            const bom: any = {
                bomFormat: 'CycloneDX',
                specVersion: '1.7',
                serialNumber: 'urn:uuid:00000000-0000-0000-0000-000000000000',
                version: 1,
                components: [],
            };
            const result = downgradeCycloneDxSpecIfNeeded(bom);
            expect(result.specVersion).toBe('1.6');
        });

        it('rewrites $schema URL when it embeds the original spec version', () => {
            const bom: any = {
                $schema: 'http://cyclonedx.org/schema/bom-1.7.schema.json',
                bomFormat: 'CycloneDX',
                specVersion: '1.7',
            };
            const result = downgradeCycloneDxSpecIfNeeded(bom);
            expect(result.specVersion).toBe('1.6');
            expect(result.$schema).toBe('http://cyclonedx.org/schema/bom-1.6.schema.json');
        });

        it('leaves supported spec versions untouched', () => {
            const bom: any = {
                bomFormat: 'CycloneDX',
                specVersion: '1.6',
                $schema: 'http://cyclonedx.org/schema/bom-1.6.schema.json',
            };
            const result = downgradeCycloneDxSpecIfNeeded(bom);
            expect(result.specVersion).toBe('1.6');
            expect(result.$schema).toBe('http://cyclonedx.org/schema/bom-1.6.schema.json');
        });

        it('leaves a BOM with no specVersion alone', () => {
            const bom: any = { bomFormat: 'CycloneDX' };
            const result = downgradeCycloneDxSpecIfNeeded(bom);
            expect(result.specVersion).toBeUndefined();
        });

        it('preserves non-spec fields (additive 1.7 fields are not stripped)', () => {
            // 1.7 may add new top-level fields; we don't enumerate them, we just
            // pass them through. cyclonedx-go's lenient JSON decoder ignores
            // unknown fields, and the 1.6 strict validator should tolerate
            // additive ones. If a future BOM trips the validator we'll
            // discover the offending field via the validator error and revisit.
            const bom: any = {
                bomFormat: 'CycloneDX',
                specVersion: '1.7',
                someNew17Field: { foo: 'bar' },
                components: [{ name: 'x', purl: 'pkg:npm/x@1.0.0' }],
            };
            const result = downgradeCycloneDxSpecIfNeeded(bom);
            expect(result.specVersion).toBe('1.6');
            expect(result.someNew17Field).toEqual({ foo: 'bar' });
            expect(result.components).toHaveLength(1);
        });
    });
});
