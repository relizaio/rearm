/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.reliza.model.AnalysisState;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityReferenceDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

/**
 * Pin the V33 contract for the per-finding enrichment fields
 * ({@code description}, {@code cwes}, {@code references},
 * {@code published}, {@code updated}) under
 * {@link ReleaseMetricsDto#mergeWithByContent} and
 * {@link ReleaseMetricsDto#organizeVulnerabilitiesWithAliases}.
 *
 * <p>Before V33 those fields were per-finding copies of upstream advisory
 * text, and merge had to union them across alias-equivalent rows to
 * recover values that DTrack happened to put on the second of two
 * mirror rows. After V33 the canonical enrichment lives in
 * {@code rearm.vulnerability_records}; the per-finding fields are
 * always nulled at the write paths, so merge no longer has anything to
 * union — and surfacing input values here would silently keep the
 * legacy bloat alive on freshly-merged metrics. These tests pin the
 * "always null after merge" contract for the merge paths.
 */
public class ReleaseMetricsDtoMergeTest {

	private static final String PURL = "pkg:npm/lodash@4.17.21";

	private static VulnerabilityDto vuln(String vulnId, Set<String> cwes, String description,
			Set<VulnerabilityReferenceDto> refs, ZonedDateTime published, ZonedDateTime updated) {
		return new VulnerabilityDto(PURL, vulnId, VulnerabilitySeverity.HIGH,
				Set.of(), Set.of(), Set.of(), null, null, null,
				description, cwes, refs, published, updated, null);
	}

	private static ReleaseMetricsDto mergeViaPublicSurface(VulnerabilityDto first, VulnerabilityDto second) {
		ReleaseMetricsDto a = new ReleaseMetricsDto();
		a.setVulnerabilityDetails(new LinkedList<>(List.of(first)));
		ReleaseMetricsDto b = new ReleaseMetricsDto();
		b.setVulnerabilityDetails(new LinkedList<>(List.of(second)));
		a.mergeWithByContent(b);
		return a;
	}

	@Test
	void mergeDropsCwesEvenWhenInputsHadThem() {
		// Inputs carry CWEs (e.g. legacy artifact metrics written before V33);
		// merge must drop them so the merged metrics row doesn't keep the
		// per-finding bloat alive. Canonical CWE list now lives in
		// rearm.vulnerability_records.
		VulnerabilityDto first = vuln("CVE-2099-0001", Set.of("CWE-79"), null, null, null, null);
		VulnerabilityDto second = vuln("CVE-2099-0001", Set.of("CWE-22"), null, null, null, null);

		ReleaseMetricsDto merged = mergeViaPublicSurface(first, second);
		List<VulnerabilityDto> out = merged.getVulnerabilityDetails();

		assertEquals(1, out.size(), "merge must collapse to one entry per (purl, vulnId)");
		assertNull(out.get(0).cwes(), "cwes must be dropped at merge under the V33 contract");
	}

	@Test
	void mergeDropsDescriptionAndDates() {
		VulnerabilityDto first = vuln("CVE-2099-0003", null, "  ", null, null, null);
		ZonedDateTime pub = ZonedDateTime.parse("2026-01-15T10:00:00Z");
		ZonedDateTime upd = ZonedDateTime.parse("2026-02-20T11:00:00Z");
		VulnerabilityDto second = vuln("CVE-2099-0003", null, "real description", null, pub, upd);

		ReleaseMetricsDto merged = mergeViaPublicSurface(first, second);
		VulnerabilityDto out = merged.getVulnerabilityDetails().get(0);

		assertNull(out.description(), "description must be dropped at merge under the V33 contract");
		assertNull(out.published(), "published must be dropped at merge under the V33 contract");
		assertNull(out.updated(), "updated must be dropped at merge under the V33 contract");
	}

	@Test
	void mergeDropsReferences() {
		VulnerabilityReferenceDto refA = new VulnerabilityReferenceDto("GHSA-aaaa", "GitHub", "https://github.com/x");
		VulnerabilityReferenceDto refB = new VulnerabilityReferenceDto("CVE-mirror", "NVD", "https://nvd.nist.gov/y");
		VulnerabilityDto first = vuln("CVE-2099-0004", null, null, Set.of(refA), null, null);
		VulnerabilityDto second = vuln("CVE-2099-0004", null, null, Set.of(refB), null, null);

		ReleaseMetricsDto merged = mergeViaPublicSurface(first, second);

		assertNull(merged.getVulnerabilityDetails().get(0).references(),
				"references must be dropped at merge under the V33 contract");
	}

	@Test
	void mergeBothNullEnrichment_emitsNullNotEmptySet() {
		VulnerabilityDto first = vuln("CVE-2099-0006", null, null, null, null, null);
		VulnerabilityDto second = vuln("CVE-2099-0006", null, null, null, null, null);

		ReleaseMetricsDto merged = mergeViaPublicSurface(first, second);
		VulnerabilityDto out = merged.getVulnerabilityDetails().get(0);

		assertNull(out.cwes(), "empty union must collapse to null, not empty Set");
		assertNull(out.references(), "empty union must collapse to null, not empty Set");
	}

	@Test
	void aliasCollapsePreservesAliasesAndDropsEnrichment() {
		// mergeVulnerabilityGroup path: two vulns sharing an alias get
		// collapsed into one. Aliases and sources are preserved (those still
		// live on the finding); enrichment is dropped.
		Set<ReleaseMetricsDto.VulnerabilityAliasDto> aliasA = Set.of(
				new ReleaseMetricsDto.VulnerabilityAliasDto(ReleaseMetricsDto.VulnerabilityAliasType.GHSA, "GHSA-shared"));
		Set<ReleaseMetricsDto.VulnerabilityAliasDto> aliasB = Set.of(
				new ReleaseMetricsDto.VulnerabilityAliasDto(ReleaseMetricsDto.VulnerabilityAliasType.CVE, "CVE-2099-9999"));
		VulnerabilityDto cveSide = new VulnerabilityDto(PURL, "CVE-2099-9999", VulnerabilitySeverity.HIGH,
				aliasA, Set.of(), Set.of(), null, null, null,
				null, Set.of("CWE-79"), null, null, null, null);
		VulnerabilityDto ghsaSide = new VulnerabilityDto(PURL, "GHSA-shared", VulnerabilitySeverity.HIGH,
				aliasB, Set.of(), Set.of(), null, null, null,
				null, Set.of("CWE-22"), null, null, null, null);

		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rmd.setVulnerabilityDetails(new LinkedList<>(List.of(cveSide, ghsaSide)));
		rmd.organizeVulnerabilitiesWithAliases();

		assertEquals(1, rmd.getVulnerabilityDetails().size(),
				"alias-equivalent vulns must collapse into a single merged entry");
		VulnerabilityDto collapsed = rmd.getVulnerabilityDetails().get(0);
		assertNull(collapsed.cwes(),
				"cwes must be dropped at alias-collapse under the V33 contract");
		// Aliases themselves still need to survive — the canonical enrichment
		// row uses them as the lookup key.
		assertTrue(collapsed.aliases().stream().anyMatch(a -> "GHSA-shared".equals(a.aliasId())),
				"GHSA alias must survive collapse");
	}

	/**
	 * Builds a finding with an explicit severity, knownExploited flag, and
	 * analysis state. Distinct purls keep alias-organization from collapsing
	 * the findings so each one is tallied independently.
	 */
	private static VulnerabilityDto kevVuln(String purl, String vulnId, VulnerabilitySeverity sev,
			Boolean knownExploited, AnalysisState analysisState) {
		return new VulnerabilityDto(purl, vulnId, sev,
				Set.of(), Set.of(), Set.of(), analysisState, null, null,
				null, null, null, null, null, knownExploited);
	}

	@Test
	void computeMetricsFromFactsCountsKevFindings() {
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rmd.setVulnerabilityDetails(new LinkedList<>(List.of(
				// counted: knownExploited TRUE, affecting
				kevVuln("pkg:npm/a@1", "CVE-2099-1001", VulnerabilitySeverity.CRITICAL, Boolean.TRUE, null),
				kevVuln("pkg:npm/b@1", "CVE-2099-1002", VulnerabilitySeverity.HIGH, Boolean.TRUE, null),
				// not counted: knownExploited FALSE
				kevVuln("pkg:npm/c@1", "CVE-2099-1003", VulnerabilitySeverity.HIGH, Boolean.FALSE, null),
				// not counted: knownExploited null (probe never stamped)
				kevVuln("pkg:npm/d@1", "CVE-2099-1004", VulnerabilitySeverity.MEDIUM, null, null),
				// not counted: knownExploited TRUE but NOT_AFFECTED (non-affecting state)
				kevVuln("pkg:npm/e@1", "CVE-2099-1005", VulnerabilitySeverity.CRITICAL, Boolean.TRUE,
						AnalysisState.NOT_AFFECTED)
		)));

		rmd.computeMetricsFromFacts();

		assertEquals(2, rmd.getKevCount(),
				"kevCount must count only affecting findings whose knownExploited is TRUE");
	}

	@Test
	void computeMetricsFromFactsKevCountZeroWhenNoneExploited() {
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rmd.setVulnerabilityDetails(new LinkedList<>(List.of(
				kevVuln("pkg:npm/a@1", "CVE-2099-2001", VulnerabilitySeverity.CRITICAL, Boolean.FALSE, null),
				kevVuln("pkg:npm/b@1", "GHSA-xxxx", VulnerabilitySeverity.HIGH, null, null)
		)));

		rmd.computeMetricsFromFacts();

		assertEquals(0, rmd.getKevCount(), "kevCount must be 0 when no finding is KEV-listed");
	}
}
