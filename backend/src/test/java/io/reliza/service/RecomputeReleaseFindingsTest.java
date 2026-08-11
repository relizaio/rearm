/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.Artifact;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.KevAssertionData;
import io.reliza.model.KevRansomwareStatus;
import io.reliza.model.KevSource;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Pins the semantics behind the {@code recomputeReleaseFindings} mutation:
 * {@code ReleaseService.computeReleaseMetrics(uuid, true)} must rebuild the
 * release's metrics from its artifacts' current metrics and re-stamp
 * known-exploited membership against kev_assertions.
 *
 * <p>This is the operator remedy for the KEV bootstrap gap: findings that were
 * already KEV at the org's first catalog sync are never re-stamped into stored
 * release metrics (the sync deliberately skips the bootstrap re-stamp storm),
 * so a release scanned before KEV data arrived carries
 * {@code knownExploited=false} / {@code kevCount=0} forever unless something
 * recomputes -- which is exactly what the mutation exposes.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class RecomputeReleaseFindingsTest {

	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private ReleaseService releaseService;
	@Autowired private KevAssertionService kevAssertionService;
	@Autowired private ArtifactRepository artifactRepository;
	@Autowired private TestInitializer testInitializer;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String LOG4SHELL = "CVE-2021-44228";

	private static KevAssertionData kevEntry(String cveId) {
		KevAssertionData kad = new KevAssertionData();
		kad.setCveId(cveId);
		kad.setVendorProject("Apache");
		kad.setProduct("Log4j2");
		kad.setVulnerabilityName(cveId + " vuln");
		kad.setDateAdded("2021-12-10");
		kad.setShortDescription("desc");
		kad.setRequiredAction("Apply updates per vendor instructions.");
		kad.setDueDate("2021-12-24");
		kad.setRansomwareStatus(KevRansomwareStatus.KNOWN);
		kad.setCwes(new ArrayList<>(List.of("CWE-502")));
		return kad;
	}

	@Test
	public void recomputeRestampsKevFromCurrentAssertions() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		// Deterministic KEV state: assert log4shell is KEV for this org, without
		// relying on the live CISA feed having been fetched.
		kevAssertionService.applyCatalog(org.getUuid(), KevSource.CISA, List.of(kevEntry(LOG4SHELL)));

		// Artifact whose CURRENT metrics carry the finding with a STALE stamp
		// (knownExploited=false) -- the shape a pre-KEV scan left behind.
		Map<String, Object> vuln = new LinkedHashMap<>();
		vuln.put("purl", "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1");
		vuln.put("vulnId", LOG4SHELL);
		vuln.put("severity", "CRITICAL");
		vuln.put("knownExploited", Boolean.FALSE);
		Map<String, Object> artMetrics = new LinkedHashMap<>();
		artMetrics.put("critical", 1);
		artMetrics.put("vulnerabilities", 1);
		artMetrics.put("kevCount", 0);
		artMetrics.put("vulnerabilityDetails", List.of(vuln));
		Artifact art = new Artifact();
		Map<String, Object> artRecord = new LinkedHashMap<>();
		artRecord.put("org", org.getUuid().toString());
		artRecord.put("type", "BOM");
		art.setRecordData(artRecord);
		art.setMetrics(artMetrics);
		art = artifactRepository.save(art);

		String slug = "it-recompute-" + UUID.randomUUID().toString().substring(0, 8);
		UUID componentUuid = componentService.createComponent(CreateComponentDto.builder()
				.organization(org.getUuid())
				.name(slug)
				.type(ComponentType.COMPONENT)
				.versionSchema("semver")
				.featureBranchVersioning("Branch.Micro")
				.build(), WU).getUuid();
		Branch branch = branchService.findBranchByName(componentUuid, "main", true, WU).get();
		UUID releaseUuid = ossReleaseService.createRelease(ReleaseDto.builder()
				.component(componentUuid)
				.branch(branch.getUuid())
				.org(org.getUuid())
				.status(ReleaseData.ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseData.ReleaseLifecycle.ASSEMBLED)
				.version("1.0.0")
				.artifacts(List.of(art.getUuid()))
				.build(), WU).getUuid();

		// The semantic the mutation exposes.
		releaseService.computeReleaseMetrics(releaseUuid, true);

		ReleaseMetricsDto after = sharedReleaseService.getReleaseData(releaseUuid)
				.orElseThrow().getMetrics();
		assertEquals(1, after.getKevCount(),
				"recompute must re-stamp KEV membership from current kev_assertions");
		assertEquals(1, after.getCritical(), "artifact metrics must be merged into the release");
		assertTrue(after.getVulnerabilityDetails().stream()
				.anyMatch(v -> LOG4SHELL.equals(v.vulnId()) && Boolean.TRUE.equals(v.knownExploited())),
				"the stale knownExploited=false stamp must be flipped to true");
	}
}
