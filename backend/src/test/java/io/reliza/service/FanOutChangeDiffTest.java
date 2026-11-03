/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables;

/**
 * TPF-137 architecture 6.1 case 1: {@link SyntheticSbomService#changedCanonicalPurls}, the diff of
 * two stored bucket payloads the ingest, the resync, the re-key and the retirement record from.
 * Order and attributedAt are ignored as sameFindings ignores them; a purl with no findings counts
 * as absent.
 */
class FanOutChangeDiffTest {

	private static final String P1 = "pkg:npm/p1@1";
	private static final String P2 = "pkg:npm/p2@1";

	private static Map<String, Object> vuln(String id, String severity, String attributedAt) {
		Map<String, Object> v = new LinkedHashMap<>();
		v.put("vulnId", id);
		v.put("severity", severity);
		v.put("cvss", new BigDecimal("7.5"));
		v.put(CommonVariables.ATTRIBUTED_AT_FIELD, attributedAt);
		return v;
	}

	private static Map<String, Object> entry(List<Map<String, Object>> vulns) {
		Map<String, Object> e = new LinkedHashMap<>();
		e.put("vulns", new ArrayList<Object>(vulns));
		e.put("violations", new ArrayList<Object>());
		return e;
	}

	private static Map<String, Object> payload(Object... purlThenEntry) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < purlThenEntry.length; i += 2) m.put((String) purlThenEntry[i], purlThenEntry[i + 1]);
		return m;
	}

	private final Map<String, Object> base = payload(
			P1, entry(List.of(vuln("CVE-1", "HIGH", "2026-10-30T00:00:00Z"), vuln("CVE-2", "LOW", "2026-10-30T00:00:00Z"))),
			P2, entry(List.of(vuln("CVE-3", "MEDIUM", "2026-10-30T00:00:00Z"))));

	@Test
	void equalMapsDifferInNothing() {
		assertEquals(Set.of(), SyntheticSbomService.changedCanonicalPurls(base, base));
	}

	@Test
	void findingOrderAndAttributedAtAreIgnored() {
		Map<String, Object> reordered = payload(
				P2, entry(List.of(vuln("CVE-3", "MEDIUM", "2026-10-31T09:00:00Z"))),
				P1, entry(List.of(vuln("CVE-2", "LOW", "2026-10-31T09:00:00Z"), vuln("CVE-1", "HIGH", "2026-10-31T09:00:00Z"))));
		assertEquals(Set.of(), SyntheticSbomService.changedCanonicalPurls(base, reordered));
	}

	@Test
	void aPurlWithNoFindingsCountsAsAbsent() {
		Map<String, Object> withEmpty = new LinkedHashMap<>(base);
		withEmpty.put("pkg:npm/empty@1", entry(List.of()));
		assertEquals(Set.of(), SyntheticSbomService.changedCanonicalPurls(base, withEmpty));
		assertEquals(Set.of(), SyntheticSbomService.changedCanonicalPurls(withEmpty, base));
	}

	@Test
	void anAddedVulnChangesItsPurl() {
		Map<String, Object> after = new LinkedHashMap<>(base);
		after.put(P1, entry(List.of(vuln("CVE-1", "HIGH", "x"), vuln("CVE-2", "LOW", "x"), vuln("CVE-9", "LOW", "x"))));
		assertEquals(Set.of(P1), SyntheticSbomService.changedCanonicalPurls(base, after));
	}

	@Test
	void aChangedSeverityChangesItsPurl() {
		Map<String, Object> after = new LinkedHashMap<>(base);
		after.put(P1, entry(List.of(vuln("CVE-1", "CRITICAL", "x"), vuln("CVE-2", "LOW", "x"))));
		assertEquals(Set.of(P1), SyntheticSbomService.changedCanonicalPurls(base, after));
	}

	@Test
	void aRemovedPurlChanges() {
		Map<String, Object> after = new LinkedHashMap<>(base);
		after.remove(P1);
		assertEquals(Set.of(P1), SyntheticSbomService.changedCanonicalPurls(base, after));
	}

	@Test
	void everyPurlWithFindingsChangesFromAnEmptyOrNullBefore() {
		assertEquals(Set.of(P1, P2), SyntheticSbomService.changedCanonicalPurls(new LinkedHashMap<>(), base));
		assertEquals(Set.of(P1, P2), SyntheticSbomService.changedCanonicalPurls(null, base));
	}

	@Test
	void everyPurlThatHadFindingsChangesToAnEmptyAfter() {
		assertEquals(Set.of(P1, P2), SyntheticSbomService.changedCanonicalPurls(base, Map.of()));
	}
}
