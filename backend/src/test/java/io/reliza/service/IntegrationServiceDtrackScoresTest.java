/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.net.URI;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.IntegrationData.DependencyTrackVersion;
import io.reliza.model.VulnerabilityRecordData.UpstreamSource;
import io.reliza.model.VulnerabilityRecordData.VulnScore;
import io.reliza.model.VulnerabilityRecordData.VulnScoreType;
import io.reliza.model.VulnerabilityRecordData.VulnSourceSnapshot;
import io.reliza.model.VulnerabilityRecordData.VulnSubScoreType;

/**
 * Pins how each Dependency-Track endpoint's score fields land in a
 * snapshot's {@code scores} list. The two endpoints name the same data
 * differently: the 4.x vulnerability endpoint says {@code owaspRR*} and
 * carries CVSS v2 / v3 sub-scores; the 5.x finding endpoint says
 * {@code owasp*Score} (no RR infix) and carries no sub-scores. Payloads
 * follow Dependency-Track's own serializers (4.14.2 Vulnerability, 5.x
 * Finding); the v2 / v3 numbers are CVE-2014-0160 as the sandbox DTrack
 * returns it.
 */
class IntegrationServiceDtrackScoresTest {

	private static final String V4_ROWS_JSON = """
			[
			  {
			    "uuid": "aaaaaaaa-0000-0000-0000-000000000001",
			    "vulnId": "CVE-2014-0160",
			    "source": "NVD",
			    "severity": "HIGH",
			    "components": [ { "purl": "pkg:generic/openssl@1.0.1f" } ],
			    "cvssV2BaseScore": 5.0,
			    "cvssV2ImpactSubScore": 2.9,
			    "cvssV2ExploitabilitySubScore": 10.0,
			    "cvssV2Vector": "AV:N/AC:L/Au:N/C:P/I:N/A:N",
			    "cvssV3BaseScore": 7.5,
			    "cvssV3ImpactSubScore": 3.6,
			    "cvssV3ExploitabilitySubScore": 3.9,
			    "cvssV3Vector": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N",
			    "normalizedCvssV2Vector": "CVSS:2.0/AV:N/AC:L/Au:N/C:P/I:N/A:N",
			    "epssScore": 0.94464,
			    "epssPercentile": 0.99975
			  },
			  {
			    "uuid": "aaaaaaaa-0000-0000-0000-000000000002",
			    "vulnId": "INT-0001",
			    "source": "INTERNAL",
			    "severity": "MEDIUM",
			    "components": [ { "purl": "pkg:generic/internal-lib@1.0.0" } ],
			    "owaspRRLikelihoodScore": 4.5,
			    "owaspRRTechnicalImpactScore": 5.0,
			    "owaspRRBusinessImpactScore": 3.25,
			    "owaspRRVector": "SL:5/M:5/O:4/S:4/ED:5/EE:5/A:4/ID:4/LC:5/LI:5/LAV:5/LAC:5/FD:3/RD:3/NC:3/PV:4"
			  }
			]
			""";

	private static final String V5_FINDINGS_JSON = """
			[
			  {
			    "component": { "purl": "pkg:generic/openssl@1.0.1f" },
			    "vulnerability": {
			      "uuid": "bbbbbbbb-0000-0000-0000-000000000001",
			      "vulnId": "CVE-2014-0160",
			      "source": "NVD",
			      "severity": "HIGH",
			      "severityRank": 1,
			      "cvssV2BaseScore": 5.0,
			      "cvssV2Vector": "AV:N/AC:L/Au:N/C:P/I:N/A:N",
			      "cvssV3BaseScore": 7.5,
			      "cvssV3Vector": "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:N/A:N",
			      "owaspLikelihoodScore": 4.5,
			      "owaspTechnicalImpactScore": 5.0,
			      "owaspBusinessImpactScore": 3.25,
			      "owaspRRVector": "SL:5/M:5/O:4/S:4/ED:5/EE:5/A:4/ID:4/LC:5/LI:5/LAV:5/LAC:5/FD:3/RD:3/NC:3/PV:4",
			      "epssScore": 0.94464,
			      "epssPercentile": 0.99975
			    }
			  }
			]
			""";

	/** Serves one fixed page and hands the vulnerability_records upsert to a mock. */
	private static class PageService extends IntegrationService {
		final String pageJson;
		PageService(String pageJson) { super(null); this.pageJson = pageJson; }

		@Override
		@SuppressWarnings("unchecked")
		DtrackPageResult fetchDtrackPage(String baseUri, String apiToken, String existingParams,
				String separator, int pageNumber, int pageSize) throws RelizaException {
			if (pageNumber > 1) return new DtrackPageResult(List.of(), 0);
			List<Object> page = Utils.OM.readValue(pageJson, List.class);
			return new DtrackPageResult(page, page.size());
		}
	}

	/** Runs one drain and returns the snapshots handed to the upsert, one list per canonical vuln. */
	@SuppressWarnings("unchecked")
	private static List<List<VulnSourceSnapshot>> drain(String pageJson, DependencyTrackVersion version,
			int expectedUpserts) throws Exception {
		PageService svc = new PageService(pageJson);
		VulnerabilityRecordService records = mock(VulnerabilityRecordService.class);
		Field f = IntegrationService.class.getDeclaredField("vulnerabilityRecordService");
		f.setAccessible(true);
		f.set(svc, records);
		UUID org = UUID.randomUUID();
		// Today-attributed, so the drain refreshes vulnerability_records.
		svc.fetchDependencyTrackVulnerabilityDetailsWithCpe(URI.create("https://dtrack.example"), "token",
				"cccccccc-0000-0000-0000-000000000001", null, org, ZonedDateTime.now(ZoneOffset.UTC), version);
		ArgumentCaptor<List<VulnSourceSnapshot>> captor = ArgumentCaptor.forClass(List.class);
		verify(records, times(expectedUpserts))
				.upsertFromSnapshots(eq(org), any(), captor.capture(), any());
		return captor.getAllValues();
	}

	@Test
	void v4VulnerabilityEndpointFillsEveryScoreType() throws Exception {
		List<List<VulnSourceSnapshot>> upserts = drain(V4_ROWS_JSON, DependencyTrackVersion.V4, 2);

		VulnSourceSnapshot nvd = upserts.get(0).get(0);
		assertEquals(UpstreamSource.NVD, nvd.getUpstreamSource());
		assertEquals(List.of(VulnScoreType.CVSS_V2, VulnScoreType.CVSS_V3, VulnScoreType.EPSS),
				nvd.getScores().stream().map(VulnScore::getType).toList());
		VulnScore v2 = nvd.findScore(VulnScoreType.CVSS_V2).orElseThrow();
		assertEquals(5.0, v2.getScore());
		assertEquals("AV:N/AC:L/Au:N/C:P/I:N/A:N", v2.getVector());
		assertEquals(2.9, v2.getSubScore(VulnSubScoreType.IMPACT));
		assertEquals(10.0, v2.getSubScore(VulnSubScoreType.EXPLOITABILITY));
		VulnScore v3 = nvd.findScore(VulnScoreType.CVSS_V3).orElseThrow();
		assertEquals(7.5, v3.getScore());
		assertEquals(3.9, v3.getSubScore(VulnSubScoreType.EXPLOITABILITY));
		VulnScore epss = nvd.findScore(VulnScoreType.EPSS).orElseThrow();
		assertEquals(0.94464, epss.getScore());
		assertNull(epss.getVector());
		assertEquals(0.99975, epss.getSubScore(VulnSubScoreType.PERCENTILE));
		// Snapshots carry what upstream said, nothing about a merge pick.
		assertNull(v3.getUpstreamSource());
		assertNull(v3.getScoreSource());

		VulnSourceSnapshot internal = upserts.get(1).get(0);
		assertEquals(UpstreamSource.OTHER, internal.getUpstreamSource());
		assertEquals(1, internal.getScores().size());
		VulnScore owasp = internal.findScore(VulnScoreType.OWASP_RR).orElseThrow();
		assertNull(owasp.getScore());
		assertTrue(owasp.getVector().startsWith("SL:5/"));
		assertEquals(4.5, owasp.getSubScore(VulnSubScoreType.LIKELIHOOD));
		assertEquals(5.0, owasp.getSubScore(VulnSubScoreType.TECHNICAL_IMPACT));
		assertEquals(3.25, owasp.getSubScore(VulnSubScoreType.BUSINESS_IMPACT));
	}

	@Test
	void v5FindingEndpointFillsEveryScoreTypeWithoutSubScores() throws Exception {
		List<List<VulnSourceSnapshot>> upserts = drain(V5_FINDINGS_JSON, DependencyTrackVersion.V5, 1);

		VulnSourceSnapshot nvd = upserts.get(0).get(0);
		assertEquals(List.of(VulnScoreType.CVSS_V2, VulnScoreType.CVSS_V3, VulnScoreType.EPSS, VulnScoreType.OWASP_RR),
				nvd.getScores().stream().map(VulnScore::getType).toList());
		VulnScore v2 = nvd.findScore(VulnScoreType.CVSS_V2).orElseThrow();
		assertEquals(5.0, v2.getScore());
		assertNull(v2.getSubScores());
		// The finding row has no v3 sub-scores; the entry simply has none.
		assertNull(nvd.findScore(VulnScoreType.CVSS_V3).orElseThrow().getSubScores());
		assertEquals(0.99975, nvd.findScore(VulnScoreType.EPSS).orElseThrow().getSubScore(VulnSubScoreType.PERCENTILE));
		VulnScore owasp = nvd.findScore(VulnScoreType.OWASP_RR).orElseThrow();
		assertEquals(4.5, owasp.getSubScore(VulnSubScoreType.LIKELIHOOD));
		assertEquals(5.0, owasp.getSubScore(VulnSubScoreType.TECHNICAL_IMPACT));
		assertEquals(3.25, owasp.getSubScore(VulnSubScoreType.BUSINESS_IMPACT));
		assertTrue(owasp.getVector().startsWith("SL:5/"));
	}
}
