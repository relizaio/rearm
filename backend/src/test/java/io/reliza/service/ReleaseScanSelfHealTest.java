/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.Artifact;
import io.reliza.model.FlowControl;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Regression test for the "product release stranded in Scan pending" self-heal
 * bug (see V55 migration + the scanIncomplete guard in
 * {@link ReleaseMetricsComputeService#computeReleaseMetricsOnRescan}).
 *
 * <p>The "Scan pending" badge is driven by {@code metrics.firstScanned} being
 * null. A product release re-derives its metrics only while it stays matched by
 * a metrics finder; the catch-all is
 * {@code FIND_RELEASES_FOR_METRICS_COMPUTE_BY_UPDATE}, which matches while
 * {@code last_updated_date > to_timestamp(metrics->>'lastScanned')}.
 *
 * <p>The pre-fix bug: computing a product's metrics while a child was still
 * unscanned wrote {@code firstScanned=null} (correct) but also stamped
 * {@code lastScanned=now()}. A metrics write does not touch
 * {@code last_updated_date}, so {@code lastScanned} overtook it and the product
 * fell out of the finder — never to be re-derived when the child finally
 * scanned. This test pins the two halves of the fix:
 * <ol>
 *   <li>an incomplete scan must NOT stamp {@code lastScanned}, so the release
 *       stays finder-eligible (self-heal-able); and</li>
 *   <li>once the child is scanned, the next compute sets the product's
 *       {@code firstScanned} (the release heals) and settles it out.</li>
 * </ol>
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class ReleaseScanSelfHealTest {

	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private ArtifactRepository artifactRepository;
	@Autowired private ReleaseMetricsComputeService releaseMetricsComputeService;
	@Autowired private TestInitializer testInitializer;

	// Epoch-seconds sentinel for a "scanned" child. Stored the way the metrics
	// JSONB stores firstScanned/lastScanned (epoch-seconds number).
	private static final double CHILD_FIRST_SCANNED_EPOCH = 1_700_000_000.0; // ~2023-11

	@Test
	public void productWithUnscannedChild_staysFinderEligible_thenHealsWhenChildScans() {
		Organization org = testInitializer.obtainOrganization();

		// Child release with no firstScanned == still "Scan pending".
		Release child = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		// Product release bundling the (as-yet unscanned) child.
		List<Map<String, Object>> parents = parentReleasesOf(child.getUuid());
		Release product = saveScanPendingRelease(org.getUuid(), parents);

		// --- Phase 1: child unscanned. Compute the product's metrics. ---
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(product);

		Release afterPhase1 = releaseRepository.findById(product.getUuid()).orElseThrow();
		// All-or-nothing rollup: product must remain scan-pending while a child is unscanned.
		assertNull(firstScanned(afterPhase1),
				"product.firstScanned must stay null while a child release is unscanned");
		// THE FIX: lastScanned must not be stamped for an incomplete scan. Pre-fix, the
		// compute stamped lastScanned=now() (directly or via touchReleaseLastScanned),
		// which is exactly what evicted the release from the BY_UPDATE finder.
		assertNull(lastScanned(afterPhase1),
				"incomplete scan must not stamp lastScanned (would evict from the self-heal finder)");
		// And the observable consequence: the product is still picked up by the finder,
		// so the every-minute sweep will retry it until the child scans.
		assertTrue(byUpdateFinderUuids().contains(product.getUuid()),
				"product must remain eligible for FIND_RELEASES_FOR_METRICS_COMPUTE_BY_UPDATE "
				+ "so it can self-heal once the child is scanned");

		// --- Phase 2: child finishes scanning. Re-derive the product. ---
		markScanned(child.getUuid(), CHILD_FIRST_SCANNED_EPOCH);
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(product.getUuid()).orElseThrow());

		Release afterPhase2 = releaseRepository.findById(product.getUuid()).orElseThrow();
		// Healed: with every child scanned, the rollup sets the product's firstScanned.
		assertNotNull(firstScanned(afterPhase2),
				"product.firstScanned must be set once every child release is scanned (self-heal)");
		// Settled: a completed scan records lastScanned, taking the release out of the finder.
		assertNotNull(lastScanned(afterPhase2),
				"completed scan must stamp lastScanned so the release settles out of the finder");
	}

	@Test
	public void rejectedReleaseWithUnscannedBom_settlesOutOfTheQueue() {
		Organization org = testInitializer.obtainOrganization();
		// REJECTED release with a never-scanned BOM: pre-fix these were minted
		// continuously by rejectPendingReleases and squatted at the head of the
		// BY_UPDATE finder forever (non-scannable lifecycle -> the BOM will never
		// be scanned -> pre-fence logic kept them "dirty" indefinitely).
		Release rejected = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		UUID bomUuid = saveUnscannedBomArtifact(org.getUuid());
		attachArtifact(rejected.getUuid(), bomUuid);
		setLifecycle(rejected.getUuid(), "REJECTED");

		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(rejected.getUuid()).orElseThrow());

		Release after = releaseRepository.findById(rejected.getUuid()).orElseThrow();
		// Nothing to wait for: non-scannable lifecycle settles (stamps lastScanned)
		// instead of waiting on a scan that will never come.
		assertNotNull(lastScanned(after),
				"non-scannable (REJECTED) release must settle: lastScanned stamped even with an unscanned BOM");
		assertFalse(byUpdateFinderUuids().contains(rejected.getUuid()),
				"settled REJECTED release must leave the BY_UPDATE finder (no more head-squatting)");
	}

	@Test
	public void backoffFence_excludesFromFinder_andClearRestores() {
		Organization org = testInitializer.obtainOrganization();
		Release child = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		Release product = saveScanPendingRelease(org.getUuid(), parentReleasesOf(child.getUuid()));
		assertTrue(byUpdateFinderUuids().contains(product.getUuid()),
				"pre-fence sanity: scan-pending product is finder-eligible");

		releaseRepository.recordMetricsComputeIncomplete(product.getUuid(), 3600);

		Release fenced = releaseRepository.findById(product.getUuid()).orElseThrow();
		assertNotNull(fenced.getFlowControl(), "fence must be recorded on flow_control");
		assertNotNull(fenced.getFlowControl().metricsComputeSkipUntil());
		assertEquals(1, fenced.getFlowControl().metricsComputeFailureCount());
		assertFalse(byUpdateFinderUuids().contains(product.getUuid()),
				"fenced release must not consume a finder slot while backing off");

		releaseRepository.clearMetricsComputeBackoff(product.getUuid());
		assertTrue(byUpdateFinderUuids().contains(product.getUuid()),
				"cleared fence must re-admit the release to the finder");
	}

	@Test
	public void childCompletion_liftsParentFence_soParentHealsNextTick() {
		Organization org = testInitializer.obtainOrganization();
		// Child: ASSEMBLED, no BOMs, no children -> its compute anchors
		// firstScanned to createdDate (the no-BOM anchor), i.e. a genuine
		// null -> set transition through the rescan path.
		Release child = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		Release product = saveScanPendingRelease(org.getUuid(), parentReleasesOf(child.getUuid()));
		// Parent is deep in backoff, e.g. its child took hours to scan.
		releaseRepository.recordMetricsComputeIncomplete(product.getUuid(), 3600);
		assertFalse(byUpdateFinderUuids().contains(product.getUuid()));

		releaseMetricsComputeService.computeReleaseMetricsOnRescan(
				releaseRepository.findById(child.getUuid()).orElseThrow());

		Release childAfter = releaseRepository.findById(child.getUuid()).orElseThrow();
		assertNotNull(firstScanned(childAfter), "sanity: child's firstScanned must land via the no-BOM anchor");
		Release productAfter = releaseRepository.findById(product.getUuid()).orElseThrow();
		assertTrue(productAfter.getFlowControl() == null
						|| productAfter.getFlowControl().metricsComputeSkipUntil() == null,
				"child's firstScanned landing must lift the parent's fence (push, not poll)");
		assertTrue(byUpdateFinderUuids().contains(product.getUuid()),
				"parent must be finder-eligible again so it heals on the next tick, not after its backoff");
	}

	@Test
	public void backoffSchedule_gracethenEscalateToCap() {
		assertEquals(0, ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(null),
				"first attempt is within the grace window");
		assertEquals(0, ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(fcWithFailures(4)),
				"grace window: first 5 attempts are unfenced (per-minute retries)");
		assertEquals(60, ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(fcWithFailures(5)));
		assertEquals(120, ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(fcWithFailures(6)));
		assertEquals(3600, ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(fcWithFailures(11)),
				"cap: escalation tops out at one hour");
		assertEquals(3600, ReleaseMetricsComputeService.nextMetricsComputeBackoffSeconds(fcWithFailures(500)),
				"cap holds for arbitrarily large failure counts");
	}

	private static FlowControl fcWithFailures(int failureCount) {
		return new FlowControl(null, null, null, null, null, null, null, null, failureCount);
	}

	// ---- helpers (local, mirroring ReleaseMetricsFinderQueryTest's fixture style) ----

	private Set<UUID> byUpdateFinderUuids() {
		return releaseRepository.findReleasesForMetricsComputeByUpdate(1000)
				.stream().map(Release::getUuid).collect(Collectors.toSet());
	}

	private static Object firstScanned(Release r) {
		return r.getMetrics() == null ? null : r.getMetrics().get("firstScanned");
	}

	private static Object lastScanned(Release r) {
		return r.getMetrics() == null ? null : r.getMetrics().get("lastScanned");
	}

	private static List<Map<String, Object>> parentReleasesOf(UUID childUuid) {
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
	private Release saveScanPendingRelease(UUID orgUuid, List<Map<String, Object>> parentReleases) {
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

	private void markScanned(UUID releaseUuid, double firstScannedEpoch) {
		Release r = releaseRepository.findById(releaseUuid).orElseThrow();
		Map<String, Object> metrics = r.getMetrics() != null ? r.getMetrics() : new HashMap<>();
		metrics.put("firstScanned", firstScannedEpoch);
		metrics.put("lastScanned", firstScannedEpoch);
		r.setMetrics(metrics);
		releaseRepository.save(r);
	}

	/** BOM artifact with no metrics at all — i.e. never scanned. */
	private UUID saveUnscannedBomArtifact(UUID orgUuid) {
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

	private void attachArtifact(UUID releaseUuid, UUID artifactUuid) {
		Release r = releaseRepository.findById(releaseUuid).orElseThrow();
		Map<String, Object> recordData = r.getRecordData();
		List<String> artifacts = new ArrayList<>();
		artifacts.add(artifactUuid.toString());
		recordData.put("artifacts", artifacts);
		r.setRecordData(recordData);
		releaseRepository.save(r);
	}

	private void setLifecycle(UUID releaseUuid, String lifecycle) {
		Release r = releaseRepository.findById(releaseUuid).orElseThrow();
		Map<String, Object> recordData = r.getRecordData();
		recordData.put("lifecycle", lifecycle);
		r.setRecordData(recordData);
		releaseRepository.save(r);
	}
}
