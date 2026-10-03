import { SPDX as CDXSpdx } from '@cyclonedx/cyclonedx-library';
import { logger } from '../../logger';

/**
 * Internal target spec version. CycloneDX 1.6 is the highest version every
 * library in our stack (cyclonedx-go, cyclonedx-core-java,
 * cyclonedx-javascript-library) currently understands; 1.7 is published
 * upstream but no language binding has shipped support yet.
 *
 * Until upstream catches up we transparently downgrade incoming 1.7 BOMs to
 * 1.6 *for the augmented (canonical) copy* — the original raw bytes are
 * still pushed to OCI under the `<uuid>-raw` key in `addCycloneDxBom`, so
 * the source-of-truth document is preserved verbatim and we can re-process
 * once the libraries catch up.
 */
const TARGET_SPEC_VERSION = '1.6';

/**
 * Map from "incoming spec version we can't yet handle" → "spec version we
 * pretend it is". Add new entries as future spec versions ship before our
 * libraries catch up.
 */
const SUPPORTED_DOWNGRADES: Record<string, string> = {
    '1.7': TARGET_SPEC_VERSION,
};

/**
 * Mutates and returns the BOM with `specVersion` downgraded to a supported
 * value when needed. Pass a clone if the caller wants to preserve the
 * original (`addCycloneDxBom` does this — `rawBom` is pushed to OCI as the
 * raw artifact while a deep-cloned, downgraded copy goes through validation
 * + augmentation).
 *
 * A 1.7 BOM is rewritten into the 1.6 shape, not only relabelled: the 1.6
 * strict validator rejects every field 1.7 added (cdxgen's formulation
 * certificates carry `certificateProperties.serialNumber`, for example), so
 * relabelling alone failed the upload. See {@link downgrade17To16} for what
 * changes. The raw 1.7 bytes stay untouched in OCI.
 */
/**
 * Spec versions our processing + validation stack (cyclonedx-go,
 * cyclonedx-core-java, cyclonedx-javascript-library) can handle — natively or
 * via the downgrade above. Anything outside this set (e.g. the CycloneDX 2.0
 * HBOM prototype, whose `specFormat`/`entities`/device shape no 1.x schema
 * accepts) is stored raw + verbatim and parsed straight from the raw bytes,
 * skipping processing/validation/augmentation until the libraries catch up.
 */
const PROCESSABLE_SPEC_VERSIONS = new Set<string>([
    '1.0', '1.1', '1.2', '1.3', '1.4', '1.5', '1.6', '1.7',
]);

/**
 * Returns true when the given CycloneDX specVersion can go through the normal
 * processing/validation/augmentation pipeline. False for spec versions our
 * libraries don't yet understand (CDX 2.0+), which must be stored + parsed raw.
 */
export function isProcessableCycloneDxSpec(specVersion?: string): boolean {
    if (!specVersion) return true; // legacy/missing — keep existing best-effort behaviour
    return PROCESSABLE_SPEC_VERSIONS.has(specVersion);
}

export function downgradeCycloneDxSpecIfNeeded<T extends { specVersion?: string; $schema?: string }>(bom: T): T {
    if (!bom?.specVersion) return bom;
    const target = SUPPORTED_DOWNGRADES[bom.specVersion];
    if (!target) return bom;
    const original = bom.specVersion;
    if (original === '1.7' && target === '1.6') downgrade17To16(bom);
    bom.specVersion = target;
    if (typeof bom.$schema === 'string' && bom.$schema.includes(original)) {
        bom.$schema = bom.$schema.replace(original, target);
    }
    logger.info({ originalSpecVersion: original, targetSpecVersion: target },
        'Downgraded CycloneDX BOM specVersion before processing — raw bytes preserved in OCI');
    return bom;
}

const PATENT_EXTERNAL_REFERENCE_TYPES = new Set(['patent', 'patent-family', 'patent-assertion', 'citation']);
const HASH_ALGORITHMS_NEW_IN_17 = new Set(['Streebog-256', 'Streebog-512']);
const PROTOCOL_TYPES_NEW_IN_17 = new Set(['dtls', 'quic', 'eap-aka', 'eap-aka-prime', 'prins', '5g-aka']);
const CERTIFICATE_PROPERTIES_NEW_IN_17 = ['serialNumber', 'certificateFileExtension', 'fingerprint', 'certificateState',
    'creationDate', 'activationDate', 'deactivationDate', 'revocationDate', 'destructionDate',
    'certificateExtensions', 'relatedCryptographicAssets'];
const IKEV2_TRANSFORM_TYPES = ['encr', 'prf', 'integ', 'ke', 'auth'];
/** Keys under which CycloneDX holds component objects. */
const COMPONENT_LIST_KEYS = new Set(['components', 'ancestors', 'descendants', 'variants']);

const isObject = (v: unknown): v is Record<string, any> => !!v && typeof v === 'object' && !Array.isArray(v);

/**
 * Rewrites the CycloneDX 1.7 additions into what 1.6 can carry, in place.
 *
 * Each change applies only where the 1.7 schema defines the field (a component's `isExternal`,
 * a certificate's `serialNumber`), never by key name alone: `serialNumber` is also the BOM's
 * own identifier, and `properties` is also a component's. What changes:
 * - mapped onto the 1.6 field that carries the same data: `ellipticCurve` -> `curve`; a
 *   certificate's `certificateFileExtension` -> `certificateExtension` (as cdxgen itself does
 *   when it writes 1.6); `relatedCryptographicAssets` -> the 1.6 refs (a certificate's
 *   `signatureAlgorithmRef` / `subjectPublicKeyRef`, related material's `algorithmRef`, a
 *   protocol's `cryptoRefArray`) where the entry's type says which; external-reference types,
 *   the `key-wrap` primitive and protocol types 1.6 does not know -> `other`; IKEv2 transform
 *   objects -> the algorithm refs 1.6 lists;
 * - licenses: 1.7 lets one list mix licenses and expressions, and lets an expression carry
 *   `licensing` and `properties`; 1.6 takes a list of licenses or one bare expression, and only
 *   a license carries those two. An expression that is mixed in a list, or carries either field,
 *   becomes a license (its SPDX id when it is one, else its text as the name) that keeps them;
 * - removed, as 1.6 has no field for them: top-level `citations`, `definitions.patents`,
 *   `metadata.distributionConstraints`; a component's `versionRange`, `isExternal` and
 *   `patentAssertions`; a service's `patentAssertions`; an external reference's `properties`;
 *   an expression's `expressionDetails`; the remaining certificate, related-material and
 *   cipher-suite fields 1.7 added, and related assets whose type maps to no 1.6 ref;
 * - dropped: Streebog hashes, which 1.6 has no algorithm for.
 */
export function downgrade17To16(bom: any): void {
    if (!isObject(bom)) return;
    delete bom.citations;
    if (isObject(bom.definitions)) {
        delete bom.definitions.patents;
        if (!Object.keys(bom.definitions).length) delete bom.definitions;
    }
    if (isObject(bom.metadata)) delete bom.metadata.distributionConstraints;
    if (isObject(bom.metadata?.component)) downgradeComponent(bom.metadata.component);
    walk(bom, undefined);
}

function walk(node: unknown, key: string | undefined): void {
    if (Array.isArray(node)) {
        if (key && COMPONENT_LIST_KEYS.has(key)) node.forEach((c) => isObject(c) && downgradeComponent(c));
        if (key === 'services') node.forEach((s) => isObject(s) && delete s.patentAssertions);
        if (key === 'externalReferences') node.forEach((r) => isObject(r) && downgradeExternalReference(r));
        if (key === 'licenses') downgradeLicenses(node);
        if (key === 'hashes') {
            for (let i = node.length - 1; i >= 0; i--) {
                if (isObject(node[i]) && HASH_ALGORITHMS_NEW_IN_17.has(node[i].alg)) node.splice(i, 1);
            }
        }
        node.forEach((v) => walk(v, undefined));
        return;
    }
    if (!isObject(node)) return;
    if (key === 'cryptoProperties') downgradeCryptoProperties(node);
    for (const [k, v] of Object.entries(node)) walk(v, k);
}

function downgradeComponent(component: Record<string, any>): void {
    delete component.versionRange;
    delete component.isExternal;
    delete component.patentAssertions;
}

function downgradeExternalReference(ref: Record<string, any>): void {
    delete ref.properties;
    if (PATENT_EXTERNAL_REFERENCE_TYPES.has(ref.type)) ref.type = 'other';
}

function downgradeLicenses(licenses: any[]): void {
    const isExpression = (e: unknown): e is Record<string, any> => isObject(e) && typeof e.expression === 'string';
    for (const entry of licenses) {
        if (isExpression(entry)) delete entry.expressionDetails;
    }
    const mixed = licenses.length > 1 && licenses.some(isExpression);
    for (let i = 0; i < licenses.length; i++) {
        const e = licenses[i];
        if (!isExpression(e)) continue;
        // A lone bare expression is valid 1.6 and keeps its expression semantics.
        if (!mixed && e.licensing === undefined && e.properties === undefined) continue;
        const id = CDXSpdx.fixupSpdxId(e.expression);
        const license: any = id ? { id } : { name: e.expression };
        for (const k of ['acknowledgement', 'bom-ref', 'licensing', 'properties']) {
            if (e[k] !== undefined) license[k] = e[k];
        }
        licenses[i] = { license };
    }
}

function downgradeCryptoProperties(crypto: Record<string, any>): void {
    const algorithm = crypto.algorithmProperties;
    if (isObject(algorithm)) {
        delete algorithm.algorithmFamily;
        if (!algorithm.curve && typeof algorithm.ellipticCurve === 'string') algorithm.curve = algorithm.ellipticCurve;
        delete algorithm.ellipticCurve;
        if (algorithm.primitive === 'key-wrap') algorithm.primitive = 'other';
    }
    const certificate = crypto.certificateProperties;
    if (isObject(certificate)) {
        if (!certificate.certificateExtension && typeof certificate.certificateFileExtension === 'string') {
            certificate.certificateExtension = certificate.certificateFileExtension;
        }
        mapRelatedAssets(certificate, { signatureAlgorithmRef: ALGORITHM_TYPES, subjectPublicKeyRef: PUBLIC_KEY_TYPES });
        for (const k of CERTIFICATE_PROPERTIES_NEW_IN_17) delete certificate[k];
    }
    const material = crypto.relatedCryptoMaterialProperties;
    if (isObject(material)) {
        delete material.fingerprint;
        mapRelatedAssets(material, { algorithmRef: ALGORITHM_TYPES });
        delete material.relatedCryptographicAssets;
    }
    const protocol = crypto.protocolProperties;
    if (isObject(protocol)) {
        // 1.6 lists a protocol's related assets as plain refs, whatever their type.
        const refs = relatedAssetRefs(protocol, () => true);
        if (refs.length) {
            const existing: unknown[] = Array.isArray(protocol.cryptoRefArray) ? protocol.cryptoRefArray : [];
            protocol.cryptoRefArray = [...new Set([...existing, ...refs])];
        }
        delete protocol.relatedCryptographicAssets;
        if (PROTOCOL_TYPES_NEW_IN_17.has(protocol.type)) protocol.type = 'other';
        if (Array.isArray(protocol.cipherSuites)) {
            for (const suite of protocol.cipherSuites) {
                if (isObject(suite)) { delete suite.tlsGroups; delete suite.tlsSignatureSchemes; }
            }
        }
        const ike = protocol.ikev2TransformTypes;
        if (isObject(ike)) {
            for (const t of IKEV2_TRANSFORM_TYPES) {
                if (!Array.isArray(ike[t])) continue;
                ike[t] = ike[t]
                    .map((v: unknown) => (typeof v === 'string' ? v : isObject(v) && typeof v.algorithm === 'string' ? v.algorithm : null))
                    .filter((v: string | null) => v !== null);
            }
        }
    }
}

const ALGORITHM_TYPES = new Set(['algorithm', 'signaturealgorithm']);
const PUBLIC_KEY_TYPES = new Set(['publickey', 'subjectpublickey']);

/** The refs of an object's 1.7 `relatedCryptographicAssets` whose type passes `wanted`. */
function relatedAssetRefs(node: Record<string, any>, wanted: (type: string) => boolean): string[] {
    const assets = node.relatedCryptographicAssets;
    if (!Array.isArray(assets)) return [];
    return assets
        .filter((a: any) => isObject(a) && typeof a.ref === 'string' && wanted(typeof a.type === 'string' ? a.type.toLowerCase() : ''))
        .map((a: any) => a.ref);
}

/**
 * Fills each 1.6 single-ref field from the first related asset of a matching type, unless the
 * field is already set. The caller removes `relatedCryptographicAssets` afterwards.
 */
function mapRelatedAssets(node: Record<string, any>, fields: Record<string, Set<string>>): void {
    for (const [field, types] of Object.entries(fields)) {
        if (typeof node[field] === 'string' && node[field]) continue;
        const [ref] = relatedAssetRefs(node, (t) => types.has(t));
        if (ref) node[field] = ref;
    }
}
