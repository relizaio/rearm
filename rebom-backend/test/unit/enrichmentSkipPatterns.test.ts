import { describe, it, expect } from 'vitest';
import {
    BUILT_IN_ENRICHMENT_SKIP_PATTERNS,
    effectiveSkipPatterns,
} from '../../src/services/bom/enrichmentSkipPatterns';

/**
 * The built-in skip list exists because pkg:generic purls are unresolvable by
 * construction (no upstream registry), so BEAR lookups on them burn the
 * 30-minute enrichment budget for zero yield — observed in production as a
 * FAILED -> retry -> FAILED loop on a filesystem-scan SBOM. These tests pin
 * the merge contract: built-ins always present, org config only ever adds.
 */
describe('enrichmentSkipPatterns', () => {
    it('applies the built-ins when the org has no configured patterns', () => {
        expect(effectiveSkipPatterns([])).toEqual([...BUILT_IN_ENRICHMENT_SKIP_PATTERNS]);
        expect(effectiveSkipPatterns(null)).toEqual([...BUILT_IN_ENRICHMENT_SKIP_PATTERNS]);
        expect(effectiveSkipPatterns(undefined)).toEqual([...BUILT_IN_ENRICHMENT_SKIP_PATTERNS]);
    });

    it('keeps pkg:generic/ in the built-ins — the unresolvable-purl class', () => {
        expect(BUILT_IN_ENRICHMENT_SKIP_PATTERNS).toContain('pkg:generic/');
    });

    it('appends configured patterns after the built-ins', () => {
        expect(effectiveSkipPatterns(['pkg:npm/@internal/']))
            .toEqual([...BUILT_IN_ENRICHMENT_SKIP_PATTERNS, 'pkg:npm/@internal/']);
    });

    it('cannot lose a built-in to org configuration, only add to them', () => {
        // An org restating a built-in must not duplicate it — and there is no
        // way to remove one, which is the property the built-ins exist for.
        const out = effectiveSkipPatterns(['pkg:generic/', 'pkg:golang/internal/']);
        expect(out.filter(p => p === 'pkg:generic/')).toHaveLength(1);
        expect(out).toContain('pkg:golang/internal/');
    });

    it('drops blank and non-string configured entries', () => {
        const out = effectiveSkipPatterns(['', '   ', null as any, 42 as any, 'pkg:swift/']);
        expect(out).toEqual([...BUILT_IN_ENRICHMENT_SKIP_PATTERNS, 'pkg:swift/']);
    });

    it('trims stray whitespace instead of pushing a dead pattern', () => {
        // Canonical purls percent-encode spaces, so ' pkg:swift/ ' as-is
        // could never match anything.
        const out = effectiveSkipPatterns([' pkg:swift/ ']);
        expect(out).toContain('pkg:swift/');
        expect(out).not.toContain(' pkg:swift/ ');
    });

    it('deduplicates a built-in restated with whitespace padding', () => {
        const out = effectiveSkipPatterns(['  pkg:generic/  ']);
        expect(out.filter(p => p === 'pkg:generic/')).toHaveLength(1);
    });

    it('deduplicates repeated configured entries', () => {
        const out = effectiveSkipPatterns(['pkg:swift/', 'pkg:swift/']);
        expect(out.filter(p => p === 'pkg:swift/')).toHaveLength(1);
    });
});
