/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Pin the CWE-string → CDX integer conversion in {@link ReleaseService#parseCwesToCdxIntegers}.
 *
 * <p>The DTO field {@code Set<String> cwes} carries two flavours of input in the wild:
 *
 * <ul>
 *   <li>Going-forward: {@code "CWE-79"} prefixed strings produced by post-{@code f67a1d16} ingestion.</li>
 *   <li>Backwards-compat: bare numeric strings ({@code "79"}) — Jackson's coercion of the older raw
 *       JSON integer arrays when the field type changed from {@code Set<Integer>} to {@code Set<String>}.</li>
 * </ul>
 *
 * Both must round-trip to the same CDX 1.6 {@code List<Integer>}; anything else is silently dropped.
 */
public class ReleaseServiceCweParseTest {

	@Test
	void prefixedString_parsesToInteger() {
		List<Integer> out = ReleaseService.parseCwesToCdxIntegers(Set.of("CWE-79"));
		assertEquals(List.of(79), out);
	}

	@Test
	void bareNumericString_parsesToInteger() {
		// Backwards-compat path: pre-f67a1d16 artifact metrics held raw JSON ints; Jackson coerces
		// them to bare strings on read. The emitter must accept these as numeric CWE ids.
		List<Integer> out = ReleaseService.parseCwesToCdxIntegers(Set.of("441"));
		assertEquals(List.of(441), out);
	}

	@Test
	void mixedPrefixedAndBare_bothEmitted() {
		// LinkedHashSet to make the assertion deterministic.
		Set<String> input = new LinkedHashSet<>();
		input.add("CWE-22");
		input.add("59");
		input.add("CWE-918");
		List<Integer> out = ReleaseService.parseCwesToCdxIntegers(input);
		assertEquals(3, out.size());
		assertTrue(out.contains(22));
		assertTrue(out.contains(59));
		assertTrue(out.contains(918));
	}

	@Test
	void caseInsensitivePrefix() {
		// regionMatches is case-insensitive; "cwe-12" should also parse.
		assertEquals(List.of(12), ReleaseService.parseCwesToCdxIntegers(Set.of("cwe-12")));
	}

	@Test
	void leadingTrailingWhitespace_trimmed() {
		assertEquals(List.of(79), ReleaseService.parseCwesToCdxIntegers(Set.of("  CWE-79 ")));
	}

	@Test
	void nonNumericValue_skipped() {
		// Future taxonomy or typo — no integer to emit, so the value drops silently rather than
		// blowing up the whole VDR.
		List<Integer> out = ReleaseService.parseCwesToCdxIntegers(Set.of("CAPEC-100"));
		assertTrue(out.isEmpty(), "non-CWE/non-numeric input must be dropped");
	}

	@Test
	void prefixedWithGarbageSuffix_skipped() {
		// "CWE-79abc" → numericPart = "79abc" → NumberFormatException → skip.
		assertTrue(ReleaseService.parseCwesToCdxIntegers(Set.of("CWE-79abc")).isEmpty());
	}

	@Test
	void nullSet_returnsEmptyList() {
		assertTrue(ReleaseService.parseCwesToCdxIntegers(null).isEmpty());
	}

	@Test
	void emptySet_returnsEmptyList() {
		assertTrue(ReleaseService.parseCwesToCdxIntegers(new HashSet<>()).isEmpty());
	}

	@Test
	void nullEntryInSet_skipped() {
		// HashSet allows a single null entry. Guard must skip it without NPE.
		Set<String> input = new HashSet<>();
		input.add(null);
		input.add("CWE-79");
		assertEquals(List.of(79), ReleaseService.parseCwesToCdxIntegers(input));
	}

	@Test
	void duplicateIntegerValues_dedupedInOutput() {
		// Cross-format collision: union-merge can produce both "CWE-79" and "79" for the same
		// CWE id when one source is post-Phase-1b (prefixed) and another is pre (bare). The
		// emitter must collapse both to a single integer in the CDX output.
		Set<String> input = new LinkedHashSet<>();
		input.add("CWE-79");
		input.add("79");
		assertEquals(List.of(79), ReleaseService.parseCwesToCdxIntegers(input));
	}
}
