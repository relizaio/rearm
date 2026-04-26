/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityReferenceDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

/**
 * Pin the union-merge of enrichment fields (cwes, references, description, published, updated)
 * across alias-equivalent / cross-artifact occurrences of the same vulnerability.
 *
 * <p>Pre-fix the merge picked the first occurrence's values, silently dropping data when the
 * first artifact's metrics had nulls (e.g. legacy Jackson coercion or pre-Phase-1b ingestion).
 * Bug #2 from the L16 plan; observed in release {@code db41860d-…} where 5/10 CVEs lost CWEs
 * because the merge-base artifact had nulls even though sibling artifacts carried them.
 */
public class ReleaseMetricsDtoMergeTest {

	private static final String PURL = "pkg:npm/lodash@4.17.21";

	private static VulnerabilityDto vuln(String vulnId, Set<String> cwes, String description,
			Set<VulnerabilityReferenceDto> refs, ZonedDateTime published, ZonedDateTime updated) {
		return new VulnerabilityDto(PURL, vulnId, VulnerabilitySeverity.HIGH,
				Set.of(), Set.of(), Set.of(), null, null, null,
				description, cwes, refs, published, updated);
	}

	private static ReleaseMetricsDto mergeViaPublicSurface(VulnerabilityDto first, VulnerabilityDto second) {
		// mergeVulnerabilityDtos is private; exercise it through the public mergeWithByContent
		// API which is the actual aggregation entry point in production.
		ReleaseMetricsDto a = new ReleaseMetricsDto();
		a.setVulnerabilityDetails(new LinkedList<>(List.of(first)));
		ReleaseMetricsDto b = new ReleaseMetricsDto();
		b.setVulnerabilityDetails(new LinkedList<>(List.of(second)));
		a.mergeWithByContent(b);
		return a;
	}

	@Test
	void mergeUnionsCwesAcrossSources() {
		// CVE-X seen on two artifacts; each carries different CWEs.
		VulnerabilityDto first = vuln("CVE-2099-0001", Set.of("CWE-79"), null, null, null, null);
		VulnerabilityDto second = vuln("CVE-2099-0001", Set.of("CWE-22"), null, null, null, null);

		ReleaseMetricsDto merged = mergeViaPublicSurface(first, second);
		List<VulnerabilityDto> out = merged.getVulnerabilityDetails();

		assertEquals(1, out.size(), "merge must collapse to one entry per (purl, vulnId)");
		Set<String> mergedCwes = out.get(0).cwes();
		assertNotNull(mergedCwes);
		assertEquals(2, mergedCwes.size());
		assertTrue(mergedCwes.contains("CWE-79"));
		assertTrue(mergedCwes.contains("CWE-22"));
	}

	@Test
	void mergeRescuesCwesWhenFirstSourceHasNull() {
		// Regression for Bug #2: pre-fix this returned cwes=null because "first wins".
		VulnerabilityDto first = vuln("CVE-2099-0002", null, null, null, null, null);
		VulnerabilityDto second = vuln("CVE-2099-0002", Set.of("CWE-918"), null, null, null, null);

		ReleaseMetricsDto merged = mergeViaPublicSurface(first, second);

		Set<String> mergedCwes = merged.getVulnerabilityDetails().get(0).cwes();
		assertNotNull(mergedCwes, "second source's cwes must not be lost when first is null");
		assertEquals(Set.of("CWE-918"), mergedCwes);
	}

	@Test
	void mergeRescuesDescriptionWhenFirstSourceIsBlank() {
		VulnerabilityDto first = vuln("CVE-2099-0003", null, "  ", null, null, null);
		VulnerabilityDto second = vuln("CVE-2099-0003", null, "real description", null, null, null);

		ReleaseMetricsDto merged = mergeViaPublicSurface(first, second);

		assertEquals("real description", merged.getVulnerabilityDetails().get(0).description());
	}

	@Test
	void mergeUnionsReferences() {
		VulnerabilityReferenceDto refA = new VulnerabilityReferenceDto("GHSA-aaaa", "GitHub", "https://github.com/x");
		VulnerabilityReferenceDto refB = new VulnerabilityReferenceDto("CVE-mirror", "NVD", "https://nvd.nist.gov/y");
		VulnerabilityDto first = vuln("CVE-2099-0004", null, null, Set.of(refA), null, null);
		VulnerabilityDto second = vuln("CVE-2099-0004", null, null, Set.of(refB), null, null);

		ReleaseMetricsDto merged = mergeViaPublicSurface(first, second);

		Set<VulnerabilityReferenceDto> mergedRefs = merged.getVulnerabilityDetails().get(0).references();
		assertNotNull(mergedRefs);
		assertEquals(2, mergedRefs.size());
		assertTrue(mergedRefs.contains(refA));
		assertTrue(mergedRefs.contains(refB));
	}

	@Test
	void mergeRescuesPublishedAndUpdatedDates() {
		// Vulnerability publication/update dates should be invariant across artifact sources;
		// merging must surface whichever source happens to have them.
		ZonedDateTime pub = ZonedDateTime.parse("2026-01-15T10:00:00Z");
		ZonedDateTime upd = ZonedDateTime.parse("2026-02-20T11:00:00Z");
		VulnerabilityDto first = vuln("CVE-2099-0005", null, null, null, null, null);
		VulnerabilityDto second = vuln("CVE-2099-0005", null, null, null, pub, upd);

		ReleaseMetricsDto merged = mergeViaPublicSurface(first, second);

		assertEquals(pub, merged.getVulnerabilityDetails().get(0).published());
		assertEquals(upd, merged.getVulnerabilityDetails().get(0).updated());
	}

	@Test
	void mergeBothNullEnrichment_emitsNullNotEmptySet() {
		// When neither source has cwes/references, the merged value must remain null so the
		// VDR emitter's "non-null and non-empty" check correctly omits the CDX field.
		VulnerabilityDto first = vuln("CVE-2099-0006", null, null, null, null, null);
		VulnerabilityDto second = vuln("CVE-2099-0006", null, null, null, null, null);

		ReleaseMetricsDto merged = mergeViaPublicSurface(first, second);
		VulnerabilityDto out = merged.getVulnerabilityDetails().get(0);

		assertNull(out.cwes(), "empty union must collapse to null, not empty Set");
		assertNull(out.references(), "empty union must collapse to null, not empty Set");
	}

	@Test
	void mergeUnionsCwesWhenAliasCollapseFires() {
		// mergeVulnerabilityGroup path: two vulns sharing an alias get collapsed into one.
		// Each carries different CWEs; union must apply.
		Set<ReleaseMetricsDto.VulnerabilityAliasDto> aliasA = Set.of(
				new ReleaseMetricsDto.VulnerabilityAliasDto(ReleaseMetricsDto.VulnerabilityAliasType.GHSA, "GHSA-shared"));
		Set<ReleaseMetricsDto.VulnerabilityAliasDto> aliasB = Set.of(
				new ReleaseMetricsDto.VulnerabilityAliasDto(ReleaseMetricsDto.VulnerabilityAliasType.CVE, "CVE-2099-9999"));
		VulnerabilityDto cveSide = new VulnerabilityDto(PURL, "CVE-2099-9999", VulnerabilitySeverity.HIGH,
				aliasA, Set.of(), Set.of(), null, null, null,
				null, Set.of("CWE-79"), null, null, null);
		VulnerabilityDto ghsaSide = new VulnerabilityDto(PURL, "GHSA-shared", VulnerabilitySeverity.HIGH,
				aliasB, Set.of(), Set.of(), null, null, null,
				null, Set.of("CWE-22"), null, null, null);

		ReleaseMetricsDto rmd = new ReleaseMetricsDto();
		rmd.setVulnerabilityDetails(new LinkedList<>(List.of(cveSide, ghsaSide)));
		// organizeVulnerabilitiesWithAliases is what folds alias-equivalent rows into one entry.
		rmd.organizeVulnerabilitiesWithAliases();

		assertEquals(1, rmd.getVulnerabilityDetails().size(),
				"alias-equivalent vulns must collapse into a single merged entry");
		Set<String> cwes = rmd.getVulnerabilityDetails().get(0).cwes();
		assertNotNull(cwes);
		assertTrue(cwes.contains("CWE-79"), "CVE-side cwes must survive alias collapse");
		assertTrue(cwes.contains("CWE-22"), "GHSA-side cwes must survive alias collapse");
	}
}
