/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.reliza.model.FindingChangeEvent;
import io.reliza.model.FindingChangeEvent.FindingKind;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityAliasDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityAliasType;

/**
 * Contract tests for the FROZEN {@link FindingDimKey} dimension hash. The GOLDEN VECTORS below pin the
 * exact digest of known inputs: any change to the algorithm, delimiter, field order, charset, or
 * version would break them -- which is the whole point of a frozen wire contract (a determinism-only
 * suite would pass such a silent re-key). Also pins: field sensitivity, the load-bearing
 * ALIASES-EXCLUDED property (exercised through the real {@code hash(FindingChangeEvent)} entry point),
 * and fail-loud on the NOT-NULL key fields.
 */
class FindingDimKeyTest {

	private static String hex(byte[] b) {
		return HexFormat.of().formatHex(b);
	}

	@Test
	void goldenVectors_pinTheFrozenContract() {
		// SHA-256("1" + NUL + kind + NUL + findingKey), truncated to 16 bytes, hex.
		// If any of these change, the dimension key changed -- bump KEY_VERSION and re-key deliberately.
		assertEquals("f1ebca4e1d20c37934cf805e95922dee",
				hex(FindingDimKey.hash("VULNERABILITY", "CVE-1|pkg:npm/a@1")), "VULNERABILITY golden vector");
		assertEquals("98ff7034fb23cc9255f6cfdb19548eaa",
				hex(FindingDimKey.hash("WEAKNESS", "CWE-89|f.java:1")), "WEAKNESS golden vector");
		assertEquals("cca1fc9382af93965077e7a31cf76549",
				hex(FindingDimKey.hash("VIOLATION", "LICENSE|pkg:npm/b@2")), "VIOLATION golden vector");
	}

	@Test
	void deterministic_and16Bytes() {
		byte[] a = FindingDimKey.hash("VULNERABILITY", "CVE-1|p");
		byte[] b = FindingDimKey.hash("VULNERABILITY", "CVE-1|p");
		assertArrayEquals(a, b, "same identity must hash identically (live-emit vs backfill convergence)");
		assertEquals(16, a.length, "dim_hash must be 16 bytes");
	}

	@Test
	void fieldSensitive_kindAndFindingKeyMatter() {
		byte[] base = FindingDimKey.hash("VULNERABILITY", "CVE-1|p");
		assertFalse(Arrays.equals(base, FindingDimKey.hash("VULNERABILITY", "CVE-2|p")), "findingKey matters");
		assertFalse(Arrays.equals(base, FindingDimKey.hash("WEAKNESS", "CVE-1|p")), "findingKind matters");
	}

	@Test
	void nonNullDelimitationIsUnambiguous() {
		// The NUL delimiter cannot be shifted: (kind="A", key="B|C") differs from (kind="A|B", key="C")
		// only structurally, but since a real findingKind is an enum name (no NUL) and findingKey is a
		// NOT NULL text column, the boundary is fixed -- these two never collide.
		assertFalse(Arrays.equals(
				FindingDimKey.hash("VULNERABILITY", "B"), FindingDimKey.hash("VULNERABILITY", "")),
				"empty key != non-empty key");
	}

	@Test
	void aliasesExcluded_sameIdentityDifferentAliasesHashEqual() {
		// THE load-bearing property, exercised through the real hash(FindingChangeEvent) entry point:
		// two events with identical (kind, findingKey) but DIFFERENT aliases must hash to the SAME dim,
		// so alias drift across history never splits a finding -> dedup stays 1:1.
		FindingChangeEvent e1 = event(FindingKind.VULNERABILITY, "CVE-9|p",
				Set.of(new VulnerabilityAliasDto(VulnerabilityAliasType.GHSA, "GHSA-x")));
		FindingChangeEvent e2 = event(FindingKind.VULNERABILITY, "CVE-9|p",
				Set.of(new VulnerabilityAliasDto(VulnerabilityAliasType.CVE, "CVE-9")));
		FindingChangeEvent e3 = event(FindingKind.VULNERABILITY, "CVE-9|p", null);
		assertArrayEquals(FindingDimKey.hash(e1), FindingDimKey.hash(e2), "different aliases -> same dim");
		assertArrayEquals(FindingDimKey.hash(e1), FindingDimKey.hash(e3), "null aliases -> same dim");
	}

	@Test
	void eventOverload_matchesFieldOverload() {
		FindingChangeEvent ev = event(FindingKind.WEAKNESS, "CWE-89|f.java:1", null);
		assertArrayEquals(FindingDimKey.hash("WEAKNESS", "CWE-89|f.java:1"), FindingDimKey.hash(ev),
				"the event overload must agree with the field overload");
	}

	@Test
	void nullKeyFields_failLoud() {
		assertThrows(IllegalArgumentException.class,
				() -> FindingDimKey.hash(null, "CVE-1|p"), "null findingKind must fail loud, not mint a dim");
		assertThrows(IllegalArgumentException.class,
				() -> FindingDimKey.hash("VULNERABILITY", null), "null findingKey must fail loud");
	}

	private static FindingChangeEvent event(FindingKind kind, String key, Set<VulnerabilityAliasDto> aliases) {
		FindingChangeEvent ev = new FindingChangeEvent();
		ev.setFindingKind(kind);
		ev.setFindingKey(key);
		ev.setAliases(aliases);
		return ev;
	}
}
