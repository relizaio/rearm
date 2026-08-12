/**
 * Built-in skip patterns applied to EVERY BEAR enrichment run, merged with —
 * never replaced by — the org's configured integration.config.skipPatterns.
 *
 * Why this exists: pkg:generic purls have no upstream registry by definition,
 * so neither BEAR (license/supplier lookup) nor any Dependency-Track analyzer
 * (OSS Index / OSV / GitHub Advisories / NVD-by-CPE) has anything to resolve
 * them against. Asking BEAR about them is pure cost with no possible signal.
 *
 * Observed in production (2026-08): a filesystem-cataloguing scanner produced
 * an SBOM with thousands of pkg:generic per-file entries (shape:
 * pkg:generic/example-header.h?path=%2Fusr%2Finclude%2F...). Every 30-minute
 * enrichment attempt spent its whole budget failing to look those up, timed
 * out, and was retried from scratch — FAILED -> retry -> FAILED — while the
 * org's un-enriched candidate window filled with those same components and
 * starved every other BOM's enrichment pull (org-wide [SYNTHETIC-STALL]).
 * Skipping generic purls up front lets such a BOM enrich its resolvable
 * remainder in minutes.
 *
 * Skipped components still ship: rearm-cli copies them into the enriched
 * output untouched, the BOM reaches COMPLETED, and the backend puller stamps
 * enriched_at on every component it carries — so the synthetic
 * Dependency-Track gate passes. Skipping affects only what BEAR is asked.
 *
 * Patterns are substring-matched against the component purl by rearm-cli
 * (--skipPattern), same semantics as the org-configured ones.
 */
export const BUILT_IN_ENRICHMENT_SKIP_PATTERNS: readonly string[] = [
  'pkg:generic/',
];

/**
 * The skip patterns an enrichment run actually uses: built-ins first, then
 * the org-configured ones, de-duplicated, blank entries dropped. Configured
 * patterns can only ADD to the built-ins — an org must never be able to
 * (accidentally) re-enable a class of lookup the built-ins exist to prevent.
 */
export function effectiveSkipPatterns(configured: string[] | null | undefined): string[] {
  const merged: string[] = [...BUILT_IN_ENRICHMENT_SKIP_PATTERNS];
  for (const pattern of configured || []) {
    if (typeof pattern === 'string' && pattern.trim().length > 0 && !merged.includes(pattern)) {
      merged.push(pattern);
    }
  }
  return merged;
}
