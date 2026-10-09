import { describe, it, expect } from 'vitest';
import {
    placeholderBomObjects,
    placeholderBomRef,
    SBOM_MISSING_PROPERTY,
    RELEASE_BOM_REF_PREFIX
} from '../../src/services/bom/bomProcessingService';
import { MissingSbomReason } from '../../src/types';

/**
 * SCORE-15 (T-7, ADR-1, ADR-2): one synthetic merge input per component release ReARM holds no
 * SBOM for. merge-boms reads its metadata.component, components and dependencies; nothing else.
 */

const HTTPD = '0b0f4a52-6c55-4d1f-9a8e-000000000001';
const FLYWAY = '0b0f4a52-6c55-4d1f-9a8e-000000000002';

describe('placeholderBomObjects', () => {
    it('describes the release in metadata.component with every field ReARM sent (T-7)', () => {
        const inputs = placeholderBomObjects([{
            releaseUuid: HTTPD, name: 'httpd', version: '2.4.68', type: 'container',
            supplierName: 'Example Org', group: 'Example Org', purl: 'pkg:oci/httpd@2.4.68', reason: MissingSbomReason.NO_SBOM_ARTIFACT
        }]);

        expect(inputs).toStrictEqual([{
            bomFormat: 'CycloneDX',
            specVersion: '1.6',
            metadata: {
                component: {
                    type: 'container',
                    name: 'httpd',
                    version: '2.4.68',
                    group: 'Example Org',
                    supplier: { name: 'Example Org' },
                    purl: 'pkg:oci/httpd@2.4.68',
                    'bom-ref': 'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000001',
                    properties: [{ name: 'reliza:sbom:missing', value: 'NO_SBOM_ARTIFACT' }]
                }
            },
            components: [],
            dependencies: [{ ref: 'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000001', dependsOn: [] }]
        }]);
    });

    it('leaves the purl key out when ReARM records none, and never makes one up (T-7)', () => {
        const [input] = placeholderBomObjects([{
            releaseUuid: FLYWAY, name: 'flyway', version: '11.20.3', type: 'library',
            supplierName: 'Example Org', group: 'Example Org', purl: null, reason: MissingSbomReason.COVERAGE_EXCLUDED
        }]);

        expect(input.metadata.component).toStrictEqual({
            type: 'library',
            name: 'flyway',
            version: '11.20.3',
            group: 'Example Org',
            supplier: { name: 'Example Org' },
            'bom-ref': 'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000002',
            properties: [{ name: 'reliza:sbom:missing', value: 'COVERAGE_EXCLUDED' }]
        });
        expect(input.metadata.component).not.toHaveProperty('purl');
    });

    it('leaves group and supplier out when ReARM sent none (T-7)', () => {
        const [input] = placeholderBomObjects([{
            releaseUuid: FLYWAY, name: 'flyway', version: '11.20.3', type: 'library', reason: MissingSbomReason.NO_SBOM_ARTIFACT
        }]);

        expect(Object.keys(input.metadata.component)).toStrictEqual(['type', 'name', 'version', 'bom-ref', 'properties']);
    });

    it('makes one input per entry, in the order ReARM sent them (T-7)', () => {
        const inputs = placeholderBomObjects([
            { releaseUuid: HTTPD, name: 'httpd', version: '2.4.68', type: 'container', reason: MissingSbomReason.NO_SBOM_ARTIFACT },
            { releaseUuid: FLYWAY, name: 'flyway', version: '11.20.3', type: 'library', reason: MissingSbomReason.COVERAGE_EXCLUDED }
        ]);

        expect(inputs.map(i => i.metadata.component['bom-ref'])).toStrictEqual([
            'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000001',
            'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000002'
        ]);
        expect(inputs.map(i => i.dependencies)).toStrictEqual([
            [{ ref: 'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000001', dependsOn: [] }],
            [{ ref: 'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000002', dependsOn: [] }]
        ]);
    });

    it('makes nothing from an empty, null or absent list (T-7)', () => {
        expect(placeholderBomObjects([])).toStrictEqual([]);
        expect(placeholderBomObjects(null)).toStrictEqual([]);
        expect(placeholderBomObjects(undefined)).toStrictEqual([]);
    });

    it('names the marker and the bom-ref prefix as the export documents them (T-7)', () => {
        expect(SBOM_MISSING_PROPERTY).toBe('reliza:sbom:missing');
        expect(RELEASE_BOM_REF_PREFIX).toBe('urn:rearm:release:');
        expect(placeholderBomRef(HTTPD)).toBe('urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000001');
    });
});
