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

/**
 * SCORE-30 (T-5, ADR-3): a placeholder carries the digests of its release's one deliverable when
 * ReARM sends them, right after the purl; with none the key is left out.
 */
describe('placeholderBomObjects: hashes', () => {
    const SHA256 = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08';
    const SHA512 = 'ee26b0dd4af7e749aa1a8ee3c10ae9923f618980772e473f8819a5d4940e0db27ac185f8a0e1d5f84f88bc887fd67b143732c304cc5fa9ad8e6f57f50028a8ff';

    it('copies the hashes ReARM sent, in order, after the purl (T-5)', () => {
        const [input] = placeholderBomObjects([{
            releaseUuid: HTTPD, name: 'httpd', version: '2.4.68', type: 'container',
            supplierName: 'Example Org', group: 'Example Org', purl: 'pkg:oci/httpd@2.4.68',
            hashes: [{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }],
            reason: MissingSbomReason.NO_SBOM_ARTIFACT
        }]);

        expect(input.metadata.component).toStrictEqual({
            type: 'container',
            name: 'httpd',
            version: '2.4.68',
            group: 'Example Org',
            supplier: { name: 'Example Org' },
            purl: 'pkg:oci/httpd@2.4.68',
            hashes: [{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }],
            'bom-ref': 'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000001',
            properties: [{ name: 'reliza:sbom:missing', value: 'NO_SBOM_ARTIFACT' }]
        });
        expect(Object.keys(input.metadata.component)).toStrictEqual(
            ['type', 'name', 'version', 'group', 'supplier', 'purl', 'hashes', 'bom-ref', 'properties']);
    });

    it('carries hashes without a purl too (T-5)', () => {
        const [input] = placeholderBomObjects([{
            releaseUuid: FLYWAY, name: 'flyway', version: '11.20.3', type: 'library', purl: null,
            hashes: [{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }],
            reason: MissingSbomReason.NO_SBOM_ARTIFACT
        }]);

        expect(input.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-256', content: SHA256 }, { alg: 'SHA-512', content: SHA512 }]);
        expect(input.metadata.component).not.toHaveProperty('purl');
    });

    it('leaves the key out when the entry has no hashes or an empty list (T-5)', () => {
        for (const hashes of [undefined, null, []]) {
            const [input] = placeholderBomObjects([{
                releaseUuid: FLYWAY, name: 'flyway', version: '11.20.3', type: 'library', hashes,
                reason: MissingSbomReason.NO_SBOM_ARTIFACT
            }]);
            expect(input.metadata.component).not.toHaveProperty('hashes');
        }
    });

    it('leaves out a hash CycloneDX refuses and keeps the others (T-5)', () => {
        const [input] = placeholderBomObjects([{
            releaseUuid: FLYWAY, name: 'flyway', version: '11.20.3', type: 'library',
            hashes: [{ alg: 'SHA-256', content: 'sha256:' + SHA256 }, { alg: 'SHA-512', content: SHA512 }, { alg: 'SHA-256', content: SHA256 }],
            reason: MissingSbomReason.NO_SBOM_ARTIFACT
        }]);

        expect(input.metadata.component.hashes).toStrictEqual([{ alg: 'SHA-512', content: SHA512 }, { alg: 'SHA-256', content: SHA256 }]);
    });
});

/**
 * SCORE-40 (T-3, ADR-4): a placeholder carries the licence ReARM declares for its release, in the
 * form declaredLicenses gives, right after the hashes; with no expression the key is left out.
 */
describe('placeholderBomObjects: licenses', () => {
    const SHA256 = '9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08';

    it('writes a single SPDX id in the id form, after the hashes and before the bom-ref (T-3)', () => {
        const [input] = placeholderBomObjects([{
            releaseUuid: HTTPD, name: 'httpd', version: '2.4.68', type: 'container',
            supplierName: 'Example Org', group: 'Example Org', purl: 'pkg:oci/httpd@2.4.68',
            hashes: [{ alg: 'SHA-256', content: SHA256 }],
            licenseExpression: 'AGPL-3.0-only',
            reason: MissingSbomReason.NO_SBOM_ARTIFACT
        }]);

        expect(input).toStrictEqual({
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
                    hashes: [{ alg: 'SHA-256', content: SHA256 }],
                    licenses: [{ license: { id: 'AGPL-3.0-only' } }],
                    'bom-ref': 'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000001',
                    properties: [{ name: 'reliza:sbom:missing', value: 'NO_SBOM_ARTIFACT' }]
                }
            },
            components: [],
            dependencies: [{ ref: 'urn:rearm:release:0b0f4a52-6c55-4d1f-9a8e-000000000001', dependsOn: [] }]
        });
        expect(Object.keys(input.metadata.component)).toStrictEqual(
            ['type', 'name', 'version', 'group', 'supplier', 'purl', 'hashes', 'licenses', 'bom-ref', 'properties']);
    });

    it('writes a single LicenseRef with its url in the named form (T-3)', () => {
        const [input] = placeholderBomObjects([{
            releaseUuid: FLYWAY, name: 'flyway', version: '11.20.3', type: 'library',
            licenseExpression: 'LicenseRef-Vendor-EULA', licenseUrl: 'https://vendor.example/eula',
            reason: MissingSbomReason.NO_SBOM_ARTIFACT
        }]);

        expect(input.metadata.component.licenses)
            .toStrictEqual([{ license: { name: 'LicenseRef-Vendor-EULA', url: 'https://vendor.example/eula' } }]);
        expect(Object.keys(input.metadata.component)).toStrictEqual(['type', 'name', 'version', 'licenses', 'bom-ref', 'properties']);
    });

    it('writes a compound expression in the expression form, the url dropped (T-3)', () => {
        const [input] = placeholderBomObjects([{
            releaseUuid: FLYWAY, name: 'flyway', version: '11.20.3', type: 'library',
            licenseExpression: 'MIT OR Apache-2.0', licenseUrl: 'https://vendor.example/eula',
            reason: MissingSbomReason.NO_SBOM_ARTIFACT
        }]);

        expect(input.metadata.component.licenses).toStrictEqual([{ expression: 'MIT OR Apache-2.0' }]);
    });

    it('leaves the key out with no expression, a blank one, or a url alone (T-3)', () => {
        for (const extra of [{}, { licenseExpression: '' }, { licenseExpression: '   ' }, { licenseExpression: null }, { licenseUrl: 'https://vendor.example/eula' }]) {
            const [input] = placeholderBomObjects([{
                releaseUuid: FLYWAY, name: 'flyway', version: '11.20.3', type: 'library',
                ...extra, reason: MissingSbomReason.NO_SBOM_ARTIFACT
            }]);

            expect(Object.keys(input.metadata.component), JSON.stringify(extra)).toStrictEqual(['type', 'name', 'version', 'bom-ref', 'properties']);
        }
    });

    it('gives each entry its own licence, in the order sent (T-3)', () => {
        const inputs = placeholderBomObjects([
            { releaseUuid: HTTPD, name: 'httpd', version: '2.4.68', type: 'container', licenseExpression: 'Apache-2.0', reason: MissingSbomReason.NO_SBOM_ARTIFACT },
            { releaseUuid: FLYWAY, name: 'flyway', version: '11.20.3', type: 'library', licenseExpression: 'MIT OR Apache-2.0', reason: MissingSbomReason.COVERAGE_EXCLUDED }
        ]);

        expect(inputs.map(i => i.metadata.component.licenses)).toStrictEqual([
            [{ license: { id: 'Apache-2.0' } }],
            [{ expression: 'MIT OR Apache-2.0' }]
        ]);
    });
});
