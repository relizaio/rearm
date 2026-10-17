/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.reliza.model.AnalysisState;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.model.dto.ReleaseMetricsDto.WeaknessDto;

/**
 * The counting contract DependencyTrackMetrics documents: the severity counts and vulnerabilities
 * count open vulnerabilities AND open weaknesses, weaknesses is the weakness share, and findings
 * analysed as non-affecting are listed but not counted. A reader comparing vulnerabilities with
 * vulnerabilityDetails (the UI CVE sweep did) gets vulnerabilities - weaknesses open rows.
 */
class ReleaseMetricsWeaknessCountTest {

	private static VulnerabilityDto vuln(String id, VulnerabilitySeverity s, AnalysisState state) {
		return new VulnerabilityDto("pkg:npm/lib@1.0.0", id, s, Set.of(), Set.of(), Set.of(), state, null, null,
				null, Set.of(), Set.of(), null, null, false);
	}

	private static WeaknessDto weakness(String rule, VulnerabilitySeverity s) {
		return new WeaknessDto("CWE-79", rule, "src/" + rule + ".ts:1", "fp-" + rule, s, Set.of(), null, null, null);
	}

	@Test
	void weaknessesAreCountedIntoTheTotalsAndReportedOnTheirOwn() {
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setVulnerabilityDetails(new java.util.LinkedList<>(List.of(
				vuln("CVE-2026-0001", VulnerabilitySeverity.HIGH, null),
				vuln("CVE-2026-0002", VulnerabilitySeverity.MEDIUM, null),
				vuln("CVE-2026-0003", VulnerabilitySeverity.CRITICAL, AnalysisState.NOT_AFFECTED))));
		m.setWeaknessDetails(new java.util.LinkedList<>(List.of(
				weakness("js/xss-a", VulnerabilitySeverity.MEDIUM),
				weakness("js/xss-b", VulnerabilitySeverity.MEDIUM),
				weakness("js/redos", VulnerabilitySeverity.LOW))));
		m.computeMetricsFromFacts();

		assertEquals(0, m.getCritical(), "the NOT_AFFECTED critical is listed but not counted");
		assertEquals(1, m.getHigh());
		assertEquals(3, m.getMedium(), "one vulnerability and two weaknesses");
		assertEquals(1, m.getLow(), "the weakness");
		assertEquals(5, m.getVulnerabilities(), "open vulnerabilities and open weaknesses");
		assertEquals(3, m.getWeaknesses());
		assertEquals(2, m.getVulnerabilities() - m.getWeaknesses(), "the open vulnerability rows");
		assertEquals(3, m.getVulnerabilityDetails().size(), "every vulnerability stays listed");
	}
}
