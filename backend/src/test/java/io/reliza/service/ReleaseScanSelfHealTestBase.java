/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.Utils;
import io.reliza.model.Artifact;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.FlowControl;
import io.reliza.model.Release;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The release fixtures of the metrics self-heal rigs: {@link ReleaseScanSelfHealTest} (the fence,
 * settle and self-heal cases) and {@link ProductChildNudgeIntegrationTest} (TPF-68, a product at the
 * backoff cap re-queuing its settled unscanned children). Rows are written straight through the
 * repository, mirroring ReleaseMetricsFinderQueryTest's fixture style.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public abstract class ReleaseScanSelfHealTestBase {

	@Autowired protected ReleaseRepository releaseRepository;
	@Autowired protected ReleaseMetricsComputeService releaseMetricsComputeService;
	@Autowired protected TestInitializer testInitializer;
	@Autowired protected ArtifactRepository artifactRepository;

	static FlowControl fcWithFailures(int failureCount) {
		return new FlowControl(null, null, null, null, null, null, null, null, null, failureCount,
				null, null, null, null, null, null, null, null);
	}

	/**
	 * Whether the BY_UPDATE finder's condition holds for this release. Not "is it in
	 * findReleasesForMetricsComputeByUpdate(1000)": that returns only the 1000 OLDEST eligible
	 * releases, so in a shared test database a freshly created release falls outside it once
	 * other tests leave more than 1000 eligible rows -- a false negative for assertTrue, and an
	 * assertFalse that passes without checking anything.
	 */
	protected boolean eligibleByUpdate(UUID releaseUuid) {
		return releaseRepository.isEligibleForMetricsComputeByUpdate(releaseUuid);
	}

	protected static Object firstScanned(Release r) {
		return r.getMetrics() == null ? null : r.getMetrics().get("firstScanned");
	}

	protected static Object lastScanned(Release r) {
		return r.getMetrics() == null ? null : r.getMetrics().get("lastScanned");
	}

	protected static List<Map<String, Object>> parentReleasesOf(UUID childUuid) {
		Map<String, Object> entry = new HashMap<>();
		entry.put("release", childUuid.toString());
		entry.put("deliverables", new ArrayList<>());
		List<Map<String, Object>> parents = new ArrayList<>();
		parents.add(entry);
		return parents;
	}

	/**
	 * Persist an ASSEMBLED release with no firstScanned (== "Scan pending") and an
	 * empty metrics map. {@code parentReleases} makes it a product/aggregate release.
	 */
	protected Release saveScanPendingRelease(UUID orgUuid, List<Map<String, Object>> parentReleases) {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setCreatedDate(ZonedDateTime.now());
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("lifecycle", "ASSEMBLED");
		recordData.put("artifacts", new ArrayList<String>());
		recordData.put("parentReleases", parentReleases);
		r.setRecordData(recordData);
		r.setMetrics(new HashMap<>());
		return releaseRepository.save(r);
	}

	protected void markScanned(UUID releaseUuid, double firstScannedEpoch) {
		Release r = releaseRepository.findById(releaseUuid).orElseThrow();
		Map<String, Object> metrics = r.getMetrics() != null ? r.getMetrics() : new HashMap<>();
		metrics.put("firstScanned", firstScannedEpoch);
		metrics.put("lastScanned", firstScannedEpoch);
		r.setMetrics(metrics);
		releaseRepository.save(r);
	}

	protected void setLifecycle(UUID releaseUuid, String lifecycle) {
		Release r = releaseRepository.findById(releaseUuid).orElseThrow();
		Map<String, Object> recordData = r.getRecordData();
		recordData.put("lifecycle", lifecycle);
		r.setRecordData(recordData);
		releaseRepository.save(r);
	}

	protected static LinkedList<VulnerabilityDto> buildVulns(int findings, String prefix) {
		LinkedList<VulnerabilityDto> vulns = new LinkedList<>();
		for (int i = 0; i < findings; i++) {
			vulns.add(new VulnerabilityDto("pkg:maven/org.example/" + prefix.toLowerCase() + i + "@1.0.0",
					prefix + "-" + i, VulnerabilitySeverity.HIGH, Set.of(), Set.of(), Set.of(),
					null, null, ZonedDateTime.now().minusHours(3), null, null, null, null, null, false));
		}
		return vulns;
	}

	/** BOM artifact with no metrics at all -- i.e. never scanned. */
	protected UUID saveUnscannedBomArtifact(UUID orgUuid) {
		Artifact a = new Artifact();
		a.setUuid(UUID.randomUUID());
		a.setCreatedDate(ZonedDateTime.now());
		a.setLastUpdatedDate(ZonedDateTime.now());
		a.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("type", "BOM");
		a.setRecordData(recordData);
		return artifactRepository.save(a).getUuid();
	}

	protected void attachArtifact(UUID releaseUuid, UUID artifactUuid) {
		attachArtifacts(releaseUuid, List.of(artifactUuid));
	}

	protected void attachArtifacts(UUID releaseUuid, List<UUID> artifactUuids) {
		Release r = releaseRepository.findById(releaseUuid).orElseThrow();
		Map<String, Object> recordData = r.getRecordData();
		List<String> artifacts = new ArrayList<>();
		artifactUuids.forEach(a -> artifacts.add(a.toString()));
		recordData.put("artifacts", artifacts);
		r.setRecordData(recordData);
		releaseRepository.save(r);
	}

	/** Turn a previously-unscanned BOM into a scanned one carrying {@code findings} -- the swap's own
	 *  scan landing, which releases the hold. */
	protected void markBomScanned(UUID bomUuid, int findings) {
		Artifact a = artifactRepository.findById(bomUuid).orElseThrow();
		DependencyTrackIntegration dti = new DependencyTrackIntegration();
		dti.setVulnerabilityDetails(buildVulns(findings, "SCAN"));
		dti.computeMetricsFromFacts();
		ZonedDateTime scanned = ZonedDateTime.now();
		dti.setFirstScanned(scanned);
		dti.setLastScanned(scanned);
		a.setMetrics(Utils.OM.convertValue(dti, LinkedHashMap.class));
		artifactRepository.save(a);
	}
}
