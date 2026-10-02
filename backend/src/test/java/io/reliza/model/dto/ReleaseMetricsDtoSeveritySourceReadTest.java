/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.dto.ReleaseMetricsDto.SeveritySourceDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

/**
 * Metrics written by a later release can name a severity source this one
 * does not know. The entry reads with no source instead of failing the whole
 * metrics read, so a rollback past that release keeps releases readable.
 */
class ReleaseMetricsDtoSeveritySourceReadTest {

	private static final String METRICS_WITH_A_LATER_SOURCE = """
			{
			  "vulnerabilityDetails": [
			    {"purl": "pkg:pypi/django@2.0", "vulnId": "CVE-2018-7536", "severity": "HIGH",
			     "severities": [{"source": "A_LATER_FEED", "severity": "HIGH"}]}
			  ]
			}
			""";

	@Test
	void anUnknownSeveritySourceReadsAsNoSource() {
		ReleaseMetricsDto rmd = Utils.OM.readValue(METRICS_WITH_A_LATER_SOURCE, ReleaseMetricsDto.class);

		VulnerabilityDto finding = rmd.getVulnerabilityDetails().get(0);
		SeveritySourceDto entry = finding.severities().iterator().next();
		assertNull(entry.source());
		assertEquals(VulnerabilitySeverity.HIGH, entry.severity());

		rmd.computeMetricsFromFacts();
		assertEquals(1, rmd.getHigh());
	}

	@Test
	void aKnownFeedStillOutranksAnEntryWithNoSource() {
		ReleaseMetricsDto rmd = Utils.OM.readValue("""
				{
				  "vulnerabilityDetails": [
				    {"purl": "pkg:pypi/django@2.0", "vulnId": "CVE-2018-7536", "severity": "LOW",
				     "severities": [{"source": "A_LATER_FEED", "severity": "CRITICAL"},
				                    {"source": "NVD", "severity": "LOW"}]}
				  ]
				}
				""", ReleaseMetricsDto.class);

		rmd.computeMetricsFromFacts();

		assertEquals(1, rmd.getLow());
		assertEquals(0, rmd.getCritical());
	}
}
