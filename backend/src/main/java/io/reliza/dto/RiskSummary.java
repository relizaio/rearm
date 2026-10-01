/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.dto;

import io.reliza.model.VulnerabilityRecordData.VulnScoreType;

/**
 * GraphQL {@code RiskSummary}: a read-time summary of one metrics object's
 * open vulnerability findings, scored from the org's vulnerability records.
 * Never stored. Built by {@code RiskSummaryCalculator}.
 *
 * @param maxCvss               highest headline CVSS score over the open findings; null when none is scored
 * @param maxCvssType           CVSS version of {@code maxCvss}
 * @param maxCvssVulnId         finding id carrying {@code maxCvss}
 * @param maxEpss               highest EPSS probability (0..1); null when none
 * @param maxEpssVulnId         finding id carrying {@code maxEpss}
 * @param cvssBands             open finding rows per headline-CVSS band
 * @param epssAtLeastTenPercent open finding rows with an EPSS probability of at least 0.1
 * @param kevCount              open finding rows in the CISA KEV catalog
 * @param severityWeightedScore Dependency-Track's inherited-risk weighting of the stored severity counts
 * @param scoredFindings        open finding rows with a headline CVSS score
 * @param totalFindings         open finding rows
 */
public record RiskSummary(
		Double maxCvss,
		VulnScoreType maxCvssType,
		String maxCvssVulnId,
		Double maxEpss,
		String maxEpssVulnId,
		CvssBands cvssBands,
		int epssAtLeastTenPercent,
		int kevCount,
		int severityWeightedScore,
		int scoredFindings,
		int totalFindings) {

	/** GraphQL {@code CvssBands}: finding rows per headline-CVSS band; each row counts once. */
	public record CvssBands(int critical, int high, int medium, int low, int none, int unscored) {}
}
