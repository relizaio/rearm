/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.reliza.dto.RiskSummary;
import io.reliza.dto.RiskSummary.CvssBands;
import io.reliza.model.AnalysisState;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.model.VulnerabilityRecordData.VulnScore;
import io.reliza.model.VulnerabilityRecordData.VulnScoreType;
import io.reliza.model.VulnerabilityRecordData.VulnSubScoreType;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

/**
 * The read-time risk summary: headline-CVSS bands over open finding rows,
 * maxima, EPSS threshold, KEV, coverage and the severity-weighted score.
 */
class RiskSummaryCalculatorTest {

	private static VulnerabilityDto row(String purl, String vulnId, AnalysisState state, Boolean kev) {
		return new VulnerabilityDto(purl, vulnId, VulnerabilitySeverity.HIGH, Set.of(), Set.of(), Set.of(),
				state, null, null, null, null, null, null, null, kev);
	}

	private static VulnerabilityDto row(String vulnId) {
		return row("pkg:npm/a@1", vulnId, null, null);
	}

	private static VulnerabilityRecordData record(VulnScore... scores) {
		VulnerabilityRecordData d = new VulnerabilityRecordData();
		d.setScores(List.of(scores));
		return d;
	}

	private static VulnScore cvss(VulnScoreType type, double score) {
		return VulnScore.of(type, score, null);
	}

	private static VulnScore epss(double probability) {
		return VulnScore.of(VulnScoreType.EPSS, probability, null).withSubScore(VulnSubScoreType.PERCENTILE, 0.5);
	}

	private static ReleaseMetricsDto metrics(VulnerabilityDto... rows) {
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(new LinkedList<>(List.of(rows)));
		m.setVulnerabilities(rows.length);
		return m;
	}

	@Test
	void bandsUseTheStoredScoreAtTheBoundaries() {
		Map<String, VulnerabilityRecordData> records = new HashMap<>();
		records.put("V-90", record(cvss(VulnScoreType.CVSS_V3, 9.0)));
		records.put("V-89", record(cvss(VulnScoreType.CVSS_V3, 8.9)));
		records.put("V-70", record(cvss(VulnScoreType.CVSS_V3, 7.0)));
		records.put("V-69", record(cvss(VulnScoreType.CVSS_V3, 6.9)));
		records.put("V-40", record(cvss(VulnScoreType.CVSS_V3, 4.0)));
		records.put("V-39", record(cvss(VulnScoreType.CVSS_V3, 3.9)));
		records.put("V-01", record(cvss(VulnScoreType.CVSS_V3, 0.1)));
		records.put("V-00", record(cvss(VulnScoreType.CVSS_V3, 0.0)));
		ReleaseMetricsDto m = metrics(row("V-90"), row("V-89"), row("V-70"), row("V-69"), row("V-40"),
				row("V-39"), row("V-01"), row("V-00"), row("V-NONE"));

		RiskSummary s = RiskSummaryCalculator.compute(m, records);

		assertEquals(new CvssBands(1, 2, 2, 2, 1, 1), s.cvssBands());
		assertEquals(9, s.totalFindings());
		assertEquals(8, s.scoredFindings());
		assertEquals(9.0, s.maxCvss());
		assertEquals("V-90", s.maxCvssVulnId());
	}

	@Test
	void headlineIsV4ThenV3ThenV2() {
		Map<String, VulnerabilityRecordData> records = Map.of(
				"ALL", record(cvss(VulnScoreType.CVSS_V2, 10.0), cvss(VulnScoreType.CVSS_V3, 5.0), cvss(VulnScoreType.CVSS_V4, 6.0)),
				"V3V2", record(cvss(VulnScoreType.CVSS_V2, 10.0), cvss(VulnScoreType.CVSS_V3, 4.5)),
				"V2", record(cvss(VulnScoreType.CVSS_V2, 7.5)));

		RiskSummary s = RiskSummaryCalculator.compute(metrics(row("ALL"), row("V3V2"), row("V2")), records);

		// v4 6.0 (medium), v3 4.5 (medium), v2 7.5 (high): a v2 10.0 never wins over a newer version
		assertEquals(new CvssBands(0, 1, 2, 0, 0, 0), s.cvssBands());
		assertEquals(7.5, s.maxCvss());
		assertEquals(VulnScoreType.CVSS_V2, s.maxCvssType());
		assertEquals("V2", s.maxCvssVulnId());
	}

	@Test
	void anEntryWithoutAScoreIsSkippedForTheHeadline() {
		VulnScore v4VectorOnly = VulnScore.of(VulnScoreType.CVSS_V4, null, "CVSS:4.0/AV:N");
		VulnerabilityRecordData r = record(v4VectorOnly, cvss(VulnScoreType.CVSS_V3, 8.1));

		assertEquals(VulnScoreType.CVSS_V3, r.findTopCvss().orElseThrow().getType());
		assertEquals(VulnScoreType.CVSS_V3,
				RiskSummaryCalculator.compute(metrics(row("X")), Map.of("X", r)).maxCvssType());
	}

	@Test
	void nonAffectingAnalysisStatesAreNotCounted() {
		Map<String, VulnerabilityRecordData> records = Map.of("V", record(cvss(VulnScoreType.CVSS_V3, 9.8), epss(0.9)));
		ReleaseMetricsDto m = metrics(
				row("pkg:npm/a@1", "V", AnalysisState.FALSE_POSITIVE, true),
				row("pkg:npm/b@1", "V", AnalysisState.NOT_AFFECTED, true),
				row("pkg:npm/c@1", "V", AnalysisState.RESOLVED, true),
				row("pkg:npm/d@1", "V", AnalysisState.IN_TRIAGE, false),
				row("pkg:npm/e@1", "V", AnalysisState.EXPLOITABLE, true));

		RiskSummary s = RiskSummaryCalculator.compute(m, records);

		assertEquals(2, s.totalFindings());
		assertEquals(2, s.cvssBands().critical());
		assertEquals(1, s.kevCount());
		assertEquals(2, s.epssAtLeastTenPercent());
		assertEquals(Set.of("V"), RiskSummaryCalculator.openVulnIds(m));
	}

	@Test
	void oneVulnerabilityUnderTwoPurlsIsTwoRowsButOneId() {
		Map<String, VulnerabilityRecordData> records = Map.of("V", record(cvss(VulnScoreType.CVSS_V3, 7.5)));
		ReleaseMetricsDto m = metrics(row("pkg:npm/a@1", "V", null, null), row("pkg:npm/b@2", "V", null, null));

		assertEquals(2, RiskSummaryCalculator.compute(m, records).cvssBands().high());
		assertEquals(Set.of("V"), RiskSummaryCalculator.openVulnIds(m));
	}

	@Test
	void epssThresholdIsInclusiveAtTenPercent() {
		Map<String, VulnerabilityRecordData> records = Map.of(
				"A", record(epss(0.1)),
				"B", record(epss(0.0999)),
				"C", record(epss(0.42)));

		RiskSummary s = RiskSummaryCalculator.compute(metrics(row("A"), row("B"), row("C")), records);

		assertEquals(2, s.epssAtLeastTenPercent());
		assertEquals(0.42, s.maxEpss());
		assertEquals("C", s.maxEpssVulnId());
		// EPSS alone is not a CVSS score
		assertEquals(3, s.cvssBands().unscored());
		assertNull(s.maxCvss());
	}

	@Test
	void severityWeightedScoreWeighsTheStoredCounts() {
		ReleaseMetricsDto m = metrics();
		m.setCritical(1);
		m.setHigh(2);
		m.setMedium(3);
		m.setLow(4);
		m.setUnassigned(5);
		m.setVulnerabilities(0);

		assertEquals(10 + 10 + 9 + 4 + 25, RiskSummaryCalculator.compute(m, Map.of()).severityWeightedScore());
	}

	@Test
	void emptyInputIsAllZeros() {
		RiskSummary s = RiskSummaryCalculator.compute(metrics(), Map.of());

		assertEquals(new CvssBands(0, 0, 0, 0, 0, 0), s.cvssBands());
		assertEquals(0, s.totalFindings());
		assertEquals(0, s.scoredFindings());
		assertEquals(0, s.kevCount());
		assertEquals(0, s.epssAtLeastTenPercent());
		assertEquals(0, s.severityWeightedScore());
		assertNull(s.maxCvss());
		assertNull(s.maxEpss());
	}

	@Test
	void totalsOnlyMetricsHaveNoFindingRows() {
		ReleaseMetricsDto light = metrics();
		light.setVulnerabilities(3);
		assertFalse(RiskSummaryCalculator.hasFindingRows(light), "counts but no rows: the rows were not loaded");

		// every counted finding is a weakness: no vulnerability rows to load
		light.setWeaknesses(3);
		assertTrue(RiskSummaryCalculator.hasFindingRows(light));

		assertTrue(RiskSummaryCalculator.hasFindingRows(metrics(row("V"))));
		assertTrue(RiskSummaryCalculator.hasFindingRows(metrics()));
		assertFalse(RiskSummaryCalculator.hasFindingRows(null));
	}

	@Test
	void openKevCountSkipsNonAffectingRows() {
		assertEquals(1, RiskSummaryCalculator.openKevCount(List.of(
				row("pkg:npm/a@1", "A", null, true),
				row("pkg:npm/b@1", "B", AnalysisState.NOT_AFFECTED, true),
				row("pkg:npm/c@1", "C", null, false),
				row("pkg:npm/d@1", "D", null, null))));
		assertEquals(0, RiskSummaryCalculator.openKevCount(null));
	}
}
