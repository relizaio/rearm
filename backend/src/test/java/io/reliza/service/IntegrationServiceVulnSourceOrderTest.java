/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.VulnerabilityRecordData.UpstreamSource;

/**
 * Pins the Dependency-Track source guess used by the single-vulnerability
 * refresh: DT keys a vulnerability by (source, id), so the order decides how
 * many 404 round-trips a refresh costs before it finds the row.
 */
class IntegrationServiceVulnSourceOrderTest {

	@Test
	void ghsaTriesGithubThenOsv() {
		assertEquals(List.of(UpstreamSource.GITHUB, UpstreamSource.OSV),
				IntegrationService.dtrackSourcesForVulnId("GHSA-f2jv-r9rf-7988"));
	}

	@Test
	void ecosystemIdsAreOsvOnly() {
		for (String id : List.of("PYSEC-2018-5", "DEBIAN-CVE-2015-3276", "ALPINE-CVE-2023-1",
				"RUSTSEC-2021-0001", "GO-2022-0001")) {
			assertEquals(List.of(UpstreamSource.OSV), IntegrationService.dtrackSourcesForVulnId(id), id);
		}
	}

	@Test
	void cveTriesNvdGithubOsv() {
		assertEquals(List.of(UpstreamSource.NVD, UpstreamSource.GITHUB, UpstreamSource.OSV),
				IntegrationService.dtrackSourcesForVulnId("CVE-2021-23369"));
	}

	@Test
	void unknownPrefixTriesOsvGithubNvd() {
		assertEquals(List.of(UpstreamSource.OSV, UpstreamSource.GITHUB, UpstreamSource.NVD),
				IntegrationService.dtrackSourcesForVulnId("MAL-2024-1"));
	}

	@Test
	void prefixMatchIsCaseInsensitive() {
		assertEquals(List.of(UpstreamSource.GITHUB, UpstreamSource.OSV),
				IntegrationService.dtrackSourcesForVulnId("ghsa-f2jv-r9rf-7988"));
		assertEquals(List.of(UpstreamSource.OSV), IntegrationService.dtrackSourcesForVulnId("pysec-2018-5"));
	}
}
