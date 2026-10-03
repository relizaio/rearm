import { describe, it, expect } from 'vitest';
import { downgradeCycloneDxSpecIfNeeded } from '../../src/services/cyclonedx/cdxSpecDowngrade';
import validateBom from '../../src/validateBom';

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

        it('preserves fields that are not 1.7 additions', () => {
            // Only the fields 1.7 added are rewritten (see downgrade17To16); anything
            // else passes through for the validator to judge.
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

/**
 * A 1.7 document using every addition 1.7 made over 1.6. It is schema-valid 1.7 (asserted
 * first, so the fixture cannot drift into testing nothing), and after the downgrade it must be
 * schema-valid 1.6 -- the strict validator every upload passes.
 */
function every17Addition(): any {
    const cert = {
        type: 'cryptographic-asset', name: 'ca-cert', 'bom-ref': 'crypto/certificate/ca@sha256:aa',
        properties: [{ name: 'cdx:crypto:trustDomain', value: 'system' }],
        cryptoProperties: {
            assetType: 'certificate',
            certificateProperties: {
                serialNumber: '0a:1b:2c', subjectName: 'CN=Example CA', issuerName: 'CN=Example CA',
                certificateFormat: 'X.509', certificateFileExtension: 'pem',
                fingerprint: { alg: 'SHA-256', content: 'aa'.repeat(32) },
                certificateState: [{ state: 'active' }], creationDate: '2025-01-01T00:00:00Z',
                relatedCryptographicAssets: [
                    { type: 'algorithm', ref: 'crypto/algorithm/sha256-rsa' },
                    { type: 'publicKey', ref: 'crypto/key/ca-public' },
                    { type: 'privateKey', ref: 'crypto/key/ca-private' },
                ],
            },
        },
    };
    const algorithm = {
        type: 'cryptographic-asset', name: 'ecdsa', 'bom-ref': 'crypto/algorithm/sha256-rsa',
        cryptoProperties: { assetType: 'algorithm',
            algorithmProperties: { primitive: 'signature', ellipticCurve: 'secg/secp256r1', algorithmFamily: 'ECDSA' } },
    };
    const key = {
        type: 'cryptographic-asset', name: 'ca-public', 'bom-ref': 'crypto/key/ca-public',
        cryptoProperties: { assetType: 'related-crypto-material',
            relatedCryptoMaterialProperties: { type: 'public-key',
                relatedCryptographicAssets: [{ type: 'algorithm', ref: 'crypto/algorithm/sha256-rsa' }] } },
    };
    const protocol = {
        type: 'cryptographic-asset', name: 'tls', 'bom-ref': 'crypto/protocol/tls',
        cryptoProperties: { assetType: 'protocol',
            protocolProperties: { type: 'tls', version: '1.3', cryptoRefArray: ['crypto/key/ca-public'],
                relatedCryptographicAssets: [
                    { type: 'publicKey', ref: 'crypto/key/ca-public' },
                    { type: 'algorithm', ref: 'crypto/algorithm/sha256-rsa' },
                ] } },
    };
    return {
        bomFormat: 'CycloneDX', specVersion: '1.7', version: 1,
        serialNumber: 'urn:uuid:3e671687-395b-41f5-a30f-a58921a69b79',
        metadata: {
            timestamp: '2026-10-03T00:00:00Z',
            distributionConstraints: { tlp: 'GREEN' },
            component: { type: 'application', name: 'app', version: '1', isExternal: false },
        },
        components: [
            { type: 'library', name: 'lib', isExternal: true, versionRange: 'vers:npm/>=1.0.0|<2.0.0',
              hashes: [{ alg: 'SHA-256', content: 'bb'.repeat(32) }, { alg: 'Streebog-256', content: 'cc'.repeat(32) }],
              externalReferences: [
                  { type: 'website', url: 'https://example.com', properties: [{ name: 'k', value: 'v' }] },
                  { type: 'citation', url: 'https://example.com/paper' },
              ],
              licenses: [{ license: { id: 'MIT' } }, { expression: 'Apache-2.0 OR GPL-2.0-only' }],
              properties: [{ name: 'cdx:npm:package:development', value: 'true' }] },
            { type: 'library', name: 'expr-only', licenses: [{ expression: 'MIT OR Apache-2.0' }] },
            { type: 'library', name: 'expr-licensed', licenses: [{ expression: 'LicenseRef-Acme-EULA',
                licensing: { licenseTypes: ['perpetual'], licensor: { organization: { name: 'Acme' } } },
                properties: [{ name: 'acme:seat', value: '42' }] }] },
        ],
        formulation: [{ 'bom-ref': 'formulation-1', components: [cert, algorithm, key, protocol] }],
    };
}

describe('downgrade17To16', () => {
    it('turns a 1.7 document using every 1.7 addition into a schema-valid 1.6 one', async () => {
        const bom = every17Addition();
        await expect(validateBom(structuredClone(bom))).resolves.toBe(true);   // valid 1.7 to begin with
        const downgraded = downgradeCycloneDxSpecIfNeeded(bom);
        expect(downgraded.specVersion).toBe('1.6');
        await expect(validateBom(downgraded)).resolves.toBe(true);
    });

    it('is what made the reported cdxgen formulation upload fail: relabelling alone does not validate', async () => {
        const bom = every17Addition();
        bom.specVersion = '1.6';
        await expect(validateBom(bom)).rejects.toThrow(/serialNumber|additional/);
    });

    it('changes only what 1.7 added, where 1.7 defines it', () => {
        const bom = downgradeCycloneDxSpecIfNeeded(every17Addition());
        expect(bom.serialNumber).toBe('urn:uuid:3e671687-395b-41f5-a30f-a58921a69b79');
        expect(bom.metadata.distributionConstraints).toBeUndefined();
        expect(bom.metadata.component.isExternal).toBeUndefined();
        const lib = bom.components[0];
        expect(lib.isExternal).toBeUndefined();
        expect(lib.versionRange).toBeUndefined();
        expect(lib.properties).toEqual([{ name: 'cdx:npm:package:development', value: 'true' }]);
        expect(lib.hashes.map((h: any) => h.alg)).toEqual(['SHA-256']);
        expect(lib.externalReferences).toEqual([
            { type: 'website', url: 'https://example.com' },
            { type: 'other', url: 'https://example.com/paper' },
        ]);
        // a mixed list keeps every entry; the expression's text survives as a license name
        expect(lib.licenses).toEqual([{ license: { id: 'MIT' } }, { license: { name: 'Apache-2.0 OR GPL-2.0-only' } }]);
        // a lone expression is valid 1.6 and stays an expression
        expect(bom.components[1].licenses).toEqual([{ expression: 'MIT OR Apache-2.0' }]);
        // an expression carrying licensing data becomes a license, which 1.6 lets carry it
        expect(bom.components[2].licenses).toEqual([{ license: { name: 'LicenseRef-Acme-EULA',
            licensing: { licenseTypes: ['perpetual'], licensor: { organization: { name: 'Acme' } } },
            properties: [{ name: 'acme:seat', value: '42' }] } }]);
        const [cert, algorithm, key, protocol] = bom.formulation[0].components;
        expect(cert.properties).toEqual([{ name: 'cdx:crypto:trustDomain', value: 'system' }]);
        // related assets land on 1.6's ref fields; a type 1.6 has no field for goes
        expect(cert.cryptoProperties.certificateProperties).toEqual({
            subjectName: 'CN=Example CA', issuerName: 'CN=Example CA', certificateFormat: 'X.509',
            certificateExtension: 'pem',
            signatureAlgorithmRef: 'crypto/algorithm/sha256-rsa', subjectPublicKeyRef: 'crypto/key/ca-public',
        });
        expect(algorithm.cryptoProperties.algorithmProperties).toEqual({ primitive: 'signature', curve: 'secg/secp256r1' });
        expect(key.cryptoProperties.relatedCryptoMaterialProperties)
            .toEqual({ type: 'public-key', algorithmRef: 'crypto/algorithm/sha256-rsa' });
        expect(protocol.cryptoProperties.protocolProperties).toEqual({ type: 'tls', version: '1.3',
            cryptoRefArray: ['crypto/key/ca-public', 'crypto/algorithm/sha256-rsa'] });
    });

    it('leaves a 1.6 document as it is', () => {
        const bom: any = { bomFormat: 'CycloneDX', specVersion: '1.6',
            components: [{ type: 'library', name: 'x', isExternal: true }] };
        downgradeCycloneDxSpecIfNeeded(bom);
        expect(bom.components[0].isExternal).toBe(true);
    });
});
