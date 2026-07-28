/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.IntegrationData.DependencyTrackVersion;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityAliasType;

/**
 * Guards the Dependency-Track 5 vulnerability drain: with
 * {@link DependencyTrackVersion#V5} the fetch reads
 * {@code /api/v1/finding/project/...} and parses the finding shape
 * (component + vulnerability with aliases) into the same
 * {@link IntegrationService.VulnWithCpe} the V4 path produces.
 *
 * <p>V5 dropped aliases from the vulnerability endpoint and left it
 * unpaginated; the finding endpoint carries aliases and pre-flattens to one
 * row per (component, canonical vulnerability). These tests pin that the V5
 * branch targets the finding endpoint and that aliases / purl / severity
 * survive the parse.
 */
class IntegrationServiceDtrackV5FindingTest {

	// One V5 finding: a GHSA-sourced vuln aliased to a CVE, on a purl'd
	// component (the %40 is DTrack's URL-encoded @ in the purl).
	private static final String V5_FINDING_JSON = """
			[
			  {
			    "component": {
			      "uuid": "11111111-1111-1111-1111-111111111111",
			      "name": "jackson-databind",
			      "purl": "pkg:maven/com.fasterxml.jackson.core/jackson-databind%402.9.10",
			      "cpe": "cpe:2.3:a:fasterxml:jackson-databind:2.9.10:*:*:*:*:*:*:*"
			    },
			    "vulnerability": {
			      "uuid": "22222222-2222-2222-2222-222222222222",
			      "vulnId": "GHSA-abcd-1234-wxyz",
			      "source": "GITHUB",
			      "severity": "HIGH",
			      "aliases": [ { "cveId": "CVE-2020-8000", "ghsaId": "GHSA-abcd-1234-wxyz" } ],
			      "cvssV3BaseScore": 7.5,
			      "epssScore": 0.42
			    },
			    "analysis": { "state": "NOT_SET", "isSuppressed": false }
			  }
			]
			""";

	/**
	 * Captures the URI the drain fetched so the test can assert the V5 branch
	 * hit the finding endpoint, and returns one parsed finding page.
	 */
	private static class CapturingService extends IntegrationService {
		String fetchedBaseUri;
		final String pageJson;
		CapturingService(String pageJson) { super(null); this.pageJson = pageJson; }

		@Override
		@SuppressWarnings("unchecked")
		DtrackPageResult fetchDtrackPage(String baseUri, String apiToken, String existingParams,
				String separator, int pageNumber, int pageSize) throws RelizaException {
			this.fetchedBaseUri = baseUri;
			if (pageNumber > 1 || pageJson == null) return new DtrackPageResult(List.of(), 0);
			List<Object> page = Utils.OM.readValue(pageJson, List.class);
			return new DtrackPageResult(page, page.size());
		}
	}

	@Test
	void v5DrainReadsFindingEndpointAndParsesAliases() throws Exception {
		CapturingService svc = new CapturingService(V5_FINDING_JSON);

		// orgUuid null so the vulnerability_records refresh (which needs a real
		// service) is skipped -- this test isolates the finding parse.
		List<IntegrationService.VulnWithCpe> results = svc.fetchDependencyTrackVulnerabilityDetailsWithCpe(
				URI.create("https://dtrack5.example"), "fake-token",
				"33333333-3333-3333-3333-333333333333", null, null, null,
				DependencyTrackVersion.V5);

		assertTrue(svc.fetchedBaseUri.contains("/api/v1/finding/project/"),
				"V5 drain must hit the finding endpoint, got: " + svc.fetchedBaseUri);

		assertEquals(1, results.size());
		var vwc = results.get(0);
		assertEquals("cpe:2.3:a:fasterxml:jackson-databind:2.9.10:*:*:*:*:*:*:*", vwc.cpe());
		var vuln = vwc.vuln();
		assertEquals("GHSA-abcd-1234-wxyz", vuln.vulnId());
		// %40 decoded back to @ in the purl.
		assertEquals("pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.9.10", vuln.purl());
		assertNotNull(vuln.severity());

		// Both the CVE and GHSA aliases are present (this is exactly what the V5
		// vulnerability endpoint would NOT have supplied).
		assertTrue(vuln.aliases().stream().anyMatch(a ->
				a.type() == VulnerabilityAliasType.CVE && "CVE-2020-8000".equals(a.aliasId())),
				"CVE alias missing");
		assertTrue(vuln.aliases().stream().anyMatch(a ->
				a.type() == VulnerabilityAliasType.GHSA && "GHSA-abcd-1234-wxyz".equals(a.aliasId())),
				"GHSA alias missing");
	}

	/** V4 default still targets the vulnerability endpoint (no regression). */
	@Test
	void v4DrainStillReadsVulnerabilityEndpoint() throws Exception {
		CapturingService svc = new CapturingService(null);
		svc.fetchDependencyTrackVulnerabilityDetailsWithCpe(
				URI.create("https://dtrack4.example"), "fake-token",
				"44444444-4444-4444-4444-444444444444", null, null, null,
				DependencyTrackVersion.V4);
		assertTrue(svc.fetchedBaseUri.contains("/api/v1/vulnerability/project/"),
				"V4 drain must hit the vulnerability endpoint, got: " + svc.fetchedBaseUri);
	}
}
