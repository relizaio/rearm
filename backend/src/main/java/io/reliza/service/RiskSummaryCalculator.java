/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import io.reliza.dto.RiskSummary;
import io.reliza.dto.RiskSummary.CvssBands;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.model.VulnerabilityRecordData.VulnScore;
import io.reliza.model.VulnerabilityRecordData.VulnScoreType;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

/**
 * Read-time risk summary over one metrics object's open vulnerability
 * findings. Pure: the caller loads the vulnerability records, this class
 * only reduces, so nothing here reads or writes stored metrics.
 *
 * <p>The population is the finding ROWS of {@code vulnerabilityDetails}
 * after the open-finding filter of
 * {@code ReleaseMetricsDto.computeMetricsFromFacts}: the vulnerability rows
 * behind the stored severity counts. The same vulnerability under two purls
 * is two rows. Weaknesses carry no CVSS and are not counted here, while the
 * stored severity counts also count open weaknesses; the two agree when a
 * release has none.
 */
public final class RiskSummaryCalculator {

	/** EPSS probability at or above which a finding counts as likely exploited. */
	public static final double EPSS_HIGH_THRESHOLD = 0.1;

	private RiskSummaryCalculator() {}

	/** CVSS qualitative severity bands, on the one-decimal score as stored. */
	enum CvssBand {
		CRITICAL(9.0), HIGH(7.0), MEDIUM(4.0), LOW(0.1), NONE(0.0);

		private final double floor;

		CvssBand(double floor) {
			this.floor = floor;
		}

		/** Declaration order is descending, so the first floor reached wins. */
		static CvssBand of(double score) {
			for (CvssBand b : values()) {
				if (score >= b.floor) return b;
			}
			return NONE;
		}
	}

	/** The exclusion {@code computeMetricsFromFacts} applies: a suppressing state does not affect the release. */
	static boolean isOpen(VulnerabilityDto vuln) {
		return vuln.analysisState() == null || !vuln.analysisState().isSuppressing();
	}

	/** The distinct vulnerability ids of the open rows, for one batched record load. */
	public static Set<String> openVulnIds(ReleaseMetricsDto metrics) {
		return openVulnIdsOfRows(rows(metrics));
	}

	/** The same over any set of finding rows. */
	public static Set<String> openVulnIdsOfRows(List<VulnerabilityDto> rows) {
		Set<String> ids = new LinkedHashSet<>();
		if (rows == null) return ids;
		for (VulnerabilityDto v : rows) {
			if (v != null && isOpen(v) && v.vulnId() != null) ids.add(v.vulnId());
		}
		return ids;
	}

	/** Open rows flagged as in the CISA KEV catalog. Only as fresh as the rows' KEV stamp. */
	public static int openKevCount(List<VulnerabilityDto> vulns) {
		int n = 0;
		if (vulns == null) return n;
		for (VulnerabilityDto v : vulns) {
			if (isOpen(v) && Boolean.TRUE.equals(v.knownExploited())) n++;
		}
		return n;
	}

	/**
	 * Whether {@code metrics} carries its finding rows. A metrics object
	 * built from the totals-only view has empty detail lists but non-zero
	 * counts; summarising that would report a release with findings as
	 * clean. The stored {@code vulnerabilities} count also includes open
	 * weaknesses, hence the subtraction.
	 */
	public static boolean hasFindingRows(ReleaseMetricsDto metrics) {
		if (metrics == null) return false;
		if (!rows(metrics).isEmpty()) return true;
		return intOf(metrics.getVulnerabilities()) - intOf(metrics.getWeaknesses()) <= 0;
	}

	/**
	 * The summary of {@code metrics}' open rows, scored from
	 * {@code recordsByVulnId} (keyed by the id the row carries; a row
	 * without a record is unscored). On a tie the first row in stored
	 * order keeps the maximum.
	 */
	public static RiskSummary compute(ReleaseMetricsDto metrics, Map<String, VulnerabilityRecordData> recordsByVulnId) {
		return summarize(rows(metrics), recordsByVulnId, severityWeightedScore(metrics));
	}

	/**
	 * The summary of a set of finding rows that has no stored counts, such as
	 * one SBOM component's findings: as {@link #compute}, except that
	 * {@code severityWeightedScore} weights the open rows' own severities.
	 */
	public static RiskSummary computeOfRows(List<VulnerabilityDto> rows, Map<String, VulnerabilityRecordData> recordsByVulnId) {
		List<VulnerabilityDto> population = rows == null ? List.of() : rows;
		return summarize(population, recordsByVulnId, severityWeightedScoreOfRows(population));
	}

	private static RiskSummary summarize(List<VulnerabilityDto> rows, Map<String, VulnerabilityRecordData> recordsByVulnId,
			int severityWeightedScore) {
		Map<String, VulnerabilityRecordData> records = recordsByVulnId == null ? Map.of() : recordsByVulnId;
		int critical = 0, high = 0, medium = 0, low = 0, none = 0, unscored = 0;
		int epssHigh = 0, kev = 0, total = 0;
		Double maxCvss = null;
		VulnScoreType maxCvssType = null;
		String maxCvssVulnId = null;
		Double maxEpss = null;
		String maxEpssVulnId = null;
		for (VulnerabilityDto v : rows) {
			if (v == null || !isOpen(v)) continue;
			total++;
			if (Boolean.TRUE.equals(v.knownExploited())) kev++;
			VulnerabilityRecordData record = v.vulnId() == null ? null : records.get(v.vulnId());
			Optional<VulnScore> cvss = record == null ? Optional.empty() : record.findTopCvss();
			if (cvss.isEmpty()) {
				unscored++;
			} else {
				double score = cvss.get().getScore();
				switch (CvssBand.of(score)) {
				case CRITICAL -> critical++;
				case HIGH -> high++;
				case MEDIUM -> medium++;
				case LOW -> low++;
				case NONE -> none++;
				}
				if (maxCvss == null || score > maxCvss) {
					maxCvss = score;
					maxCvssType = cvss.get().getType();
					maxCvssVulnId = v.vulnId();
				}
			}
			Double epss = record == null ? null
					: record.findScore(VulnScoreType.EPSS).map(VulnScore::getScore).orElse(null);
			if (epss != null) {
				if (epss >= EPSS_HIGH_THRESHOLD) epssHigh++;
				if (maxEpss == null || epss > maxEpss) {
					maxEpss = epss;
					maxEpssVulnId = v.vulnId();
				}
			}
		}
		return new RiskSummary(maxCvss, maxCvssType, maxCvssVulnId, maxEpss, maxEpssVulnId,
				new CvssBands(critical, high, medium, low, none, unscored),
				epssHigh, kev, severityWeightedScore, total - unscored, total);
	}

	/**
	 * Dependency-Track's inherited-risk weighting over the stored severity
	 * counts: critical*10 + high*5 + medium*3 + low*1 + unassigned*5.
	 */
	static int severityWeightedScore(ReleaseMetricsDto m) {
		if (m == null) return 0;
		return intOf(m.getCritical()) * weightOf(VulnerabilitySeverity.CRITICAL)
				+ intOf(m.getHigh()) * weightOf(VulnerabilitySeverity.HIGH)
				+ intOf(m.getMedium()) * weightOf(VulnerabilitySeverity.MEDIUM)
				+ intOf(m.getLow()) * weightOf(VulnerabilitySeverity.LOW)
				+ intOf(m.getUnassigned()) * weightOf(VulnerabilitySeverity.UNASSIGNED);
	}

	/** The same weighting over the open rows' stored severities; a row without one adds nothing. */
	static int severityWeightedScoreOfRows(List<VulnerabilityDto> rows) {
		int score = 0;
		for (VulnerabilityDto v : rows) {
			if (v == null || v.severity() == null || !isOpen(v)) continue;
			score += weightOf(v.severity());
		}
		return score;
	}

	/** Dependency-Track's inherited-risk weight of one finding of {@code severity}. */
	private static int weightOf(VulnerabilitySeverity severity) {
		return switch (severity) {
		case CRITICAL -> 10;
		case HIGH -> 5;
		case MEDIUM -> 3;
		case LOW -> 1;
		case UNASSIGNED -> 5;
		};
	}

	private static List<VulnerabilityDto> rows(ReleaseMetricsDto metrics) {
		if (metrics == null || metrics.getVulnerabilityDetails() == null) return List.of();
		return metrics.getVulnerabilityDetails();
	}

	private static int intOf(Integer i) {
		return i == null ? 0 : i;
	}
}
