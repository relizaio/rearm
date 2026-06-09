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
 * Transform is deliberately minimal — only `specVersion` and the `$schema`
 * URL are rewritten. We don't strip 1.7-only fields; cyclonedx-go's lenient
 * decoder ignores unknown JSON fields and the 1.6 strict validator should
 * tolerate additive ones. If a future BOM trips the strict validator on a
 * 1.7-only field, the validator error will tell us exactly what to strip;
 * revisit then.
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
    bom.specVersion = target;
    if (typeof bom.$schema === 'string' && bom.$schema.includes(original)) {
        bom.$schema = bom.$schema.replace(original, target);
    }
    logger.info({ originalSpecVersion: original, targetSpecVersion: target },
        'Downgraded CycloneDX BOM specVersion before processing — raw bytes preserved in OCI');
    return bom;
}
