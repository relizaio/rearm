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
import io.reliza.model.dto.ReleaseMetricsDto.SeveritySource;
import io.reliza.model.dto.ReleaseMetricsDto.SeveritySourceDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationType;
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

	// ---- purl-qualifier dedup -------------------------------------------------
	// Synthetic Dependency-Track emits Syft distro purls carrying ?arch=&distro=
	// qualifiers, while another scan of the same base image emits the bare purl.
	// Both name the same installed package@version; keying content-dedup on the
	// raw purl treated them as distinct findings and double-counted the shared
	// CVE (observed ~20% org-wide inflation after the synthetic-DT rollout).
	// Dedup keys now run through Utils.minimizePurl (qualifier-stripped coordinate).

	private static final String SYSTEMD_BARE = "pkg:deb/debian/systemd@257.9-1~deb13u1";
	private static final String SYSTEMD_QUALIFIED =
			SYSTEMD_BARE + "?arch=amd64&distro=debian-13&distro_name=trixie";

	private static VulnerabilityDto medVuln(String purl, String vulnId) {
		// Carry a real severity source -- alias/group collapse re-derives severity
		// from the severities set via selectBestSeverity (empty would reseverity to
		// UNASSIGNED), so production findings always populate it.
		return new VulnerabilityDto(purl, vulnId, VulnerabilitySeverity.MEDIUM,
				Set.of(), Set.of(),
				Set.of(new SeveritySourceDto(SeveritySource.NVD, VulnerabilitySeverity.MEDIUM)),
				null, null, null,
				null, null, null, null, null, null);
	}

	@Test
	void qualifierOnlyPurlVariantsCollapseInMerge() {
		ReleaseMetricsDto merged = mergeViaPublicSurface(
				medVuln(SYSTEMD_BARE, "DEBIAN-CVE-2023-31437"),
				medVuln(SYSTEMD_QUALIFIED, "DEBIAN-CVE-2023-31437"));

		assertEquals(1, merged.getVulnerabilityDetails().size(),
				"bare and qualifier-bearing purls for the same package@version must dedup to one finding");
		assertEquals(1, merged.getMedium(),
				"the shared CVE must be tallied once, not once per purl qualifier spelling");
	}

	@Test
	void qualifierOnlyPurlVariantsCollapseInOrganize() {
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rmd.setVulnerabilityDetails(new LinkedList<>(List.of(
				medVuln(SYSTEMD_BARE, "DEBIAN-CVE-2023-31437"),
				medVuln(SYSTEMD_QUALIFIED, "DEBIAN-CVE-2023-31437"))));
		rmd.computeMetricsFromFacts();

		assertEquals(1, rmd.getVulnerabilityDetails().size(),
				"organizeVulnerabilitiesWithAliases must group qualifier variants of the same package");
		assertEquals(1, rmd.getMedium(), "the shared CVE must be tallied once");
	}

	@Test
	void qualifierOnlyPurlVariantsCollapseForViolations() {
		// deduplicateViolations (runs inside computeMetricsFromFacts) must apply the
		// same qualifier-blind dedup as the vuln path, so violation totals stay
		// consistent with vuln totals.
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rmd.setViolationDetails(new LinkedList<>(List.of(
				new ViolationDto(SYSTEMD_BARE,
						ViolationType.OPERATIONAL, null, null, Set.of(), null, null, null),
				new ViolationDto(SYSTEMD_QUALIFIED,
						ViolationType.OPERATIONAL, null, null, Set.of(), null, null, null))));
		rmd.computeMetricsFromFacts();

		assertEquals(1, rmd.getPolicyViolationsOperationalTotal(),
				"bare and qualifier-bearing purls for the same package+type must dedup to one violation");
	}

	@Test
	void genuinelyDifferentVersionsStayDistinct() {
		// Guard against over-merge: qualifier-stripping must keep the version, so
		// two releases pinning different versions remain two findings.
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rmd.setVulnerabilityDetails(new LinkedList<>(List.of(
				medVuln("pkg:apk/alpine/postgresql18@18.2-r0", "CVE-2026-6637"),
				medVuln("pkg:apk/alpine/postgresql18@18.3-r0", "CVE-2026-6637"))));
		rmd.computeMetricsFromFacts();

		assertEquals(2, rmd.getMedium(),
				"qualifier-stripping must not collapse genuinely different versions");
	}

	@Test
	void nullPurlFindingsDedupByVulnId() {
		// purlIdentity falls back to "" for a null purl, so two findings with no
		// purl and the same vulnId still collapse rather than both surviving.
		ReleaseMetricsDto merged = mergeViaPublicSurface(
				medVuln(null, "CVE-2099-7000"),
				medVuln(null, "CVE-2099-7000"));

		assertEquals(1, merged.getVulnerabilityDetails().size(),
				"null-purl findings with the same vulnId must dedup to one");
		assertEquals(1, merged.getMedium(), "the shared CVE must be tallied once");
	}

	@Test
	void nonPkgLocatorsStayDistinct() {
		// Non-pkg locators are not minimizable; purlIdentity falls back to the raw
		// string so two different locators remain two distinct findings.
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rmd.setVulnerabilityDetails(new LinkedList<>(List.of(
				medVuln("/usr/lib/libfoo.so", "CVE-2099-7001"),
				medVuln("/usr/lib/libbar.so", "CVE-2099-7001"))));
		rmd.computeMetricsFromFacts();

		assertEquals(2, rmd.getMedium(), "distinct non-pkg locators must not collapse");
	}

	@Test
	void subpathDistinguishesFindings() {
		// purlIdentity preserves subpath, so purls differing only by subpath stay
		// distinct (consistent with Utils.minimizePurl used for analysis matching).
		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rmd.setVulnerabilityDetails(new LinkedList<>(List.of(
				medVuln("pkg:golang/github.com/x/y@1.0.0#cmd/a", "CVE-2099-7002"),
				medVuln("pkg:golang/github.com/x/y@1.0.0#cmd/b", "CVE-2099-7002"))));
		rmd.computeMetricsFromFacts();

		assertEquals(2, rmd.getMedium(), "subpath must distinguish findings");
	}
}
