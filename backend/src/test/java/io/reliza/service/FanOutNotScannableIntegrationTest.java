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
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.Utils;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactScanState;
import io.reliza.model.FlowControl;
import io.reliza.model.Release;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentFlowControl;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.dto.ArtifactScanStatusDto;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.ws.App;

/**
 * TPF-125 / TPF-126 architecture 6.3 rows 1-7, 13 and 14, on the test database: the not-scannable
 * marker's in-place stamp and clear, a canonical with no matchable component leaving the candidate
 * pool marked (never stamped scanned, other orgs untouched), the marker cleared when the canonical
 * gains a matchable row, a legacy vacuous row gaining the marker, the stall queries keeping the gate's
 * universe, the seed refused on a marked row, and the release carrying a cleared artifact returning
 * to scan pending (F-1).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FanOutNotScannableIntegrationTest extends FanOutCandidateTestBase {

	@Autowired private ReleaseRepository releaseRepository;
	@Autowired private ReleaseMetricsComputeService releaseMetricsComputeService;

	// ---- local fixtures ----

	/** A root (the BOM's own app) component, unbucketed. */
	private SbomComponent rootComponent(UUID org) {
		SbomComponent sc = component(org, uniquePurl("app").replace("pkg:npm/", "pkg:generic/"), null);
		sc.getRecordData().put("isRoot", true);
		return sbomComponentRepository.save(sc);
	}

	/** A component every enrichment path has given up on (enrichment-terminal). */
	private SbomComponent terminalComponent(UUID org, String purl) {
		SbomComponent sc = component(org, purl, null);
		sc.setFlowControl(new SbomComponentFlowControl("2026-10-01T00:00:00Z", "OWN_BOM_UNPULLABLE"));
		return sbomComponentRepository.save(sc);
	}

	@SuppressWarnings("unchecked")
	private Map<String, Object> markerOf(UUID artifactUuid) {
		Map<String, Object> metrics = artifactRepository.findById(artifactUuid).orElseThrow().getMetrics();
		return metrics == null ? null : (Map<String, Object>) metrics.get("notScannable");
	}

	private Object metricOf(UUID artifactUuid, String key) {
		Map<String, Object> metrics = artifactRepository.findById(artifactUuid).orElseThrow().getMetrics();
		return metrics == null ? null : metrics.get(key);
	}

	private String metricsTextOf(UUID uuid) {
		return jdbcTemplate.queryForObject("SELECT metrics::text FROM rearm.artifacts WHERE uuid = ?", String.class,
				uuid);
	}

	private long auditRowsOf(UUID uuid) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.metrics_audit WHERE entity_uuid = ?",
				Long.class, uuid);
	}

	private ArtifactScanStatusDto explain(UUID artifactUuid) {
		return syntheticSbomService.explainArtifactScanStatus(
				ArtifactData.dataFromRecord(artifactRepository.findById(artifactUuid).orElseThrow()));
	}

	private int revisionOf(UUID uuid) {
		return artifactRepository.findById(uuid).orElseThrow().getMetricsRevision();
	}

	/** The one INGESTED bucket a pass needs (fanOutOrg returns without one); covers nothing of ours. */
	private SyntheticDtrackBucket unrelatedBucket(UUID org) {
		return bucketAt(org, 0, nowMicros().minusMinutes(1), uniquePurl("unrelated"));
	}

	private void assertMarker(Map<String, Object> m, String reason, int components, int root, int terminal,
			int unmatchable) {
		assertNotNull(m, "the artifact carries the not-scannable marker");
		assertEquals(reason, m.get("reason"));
		assertEquals(components, ((Number) m.get("components")).intValue());
		assertEquals(root, ((Number) m.get("root")).intValue());
		assertEquals(terminal, ((Number) m.get("terminal")).intValue());
		assertEquals(unmatchable, ((Number) m.get("unmatchable")).intValue());
		assertNotNull(m.get("at"));
	}

	// Release helpers, in the shape of ReleaseScanSelfHealTestBase (the two bases cannot both be extended).

	private Release saveScanPendingRelease(UUID orgUuid) {
		Release r = new Release();
		r.setUuid(UUID.randomUUID());
		r.setCreatedDate(ZonedDateTime.now());
		r.setLastUpdatedDate(ZonedDateTime.now());
		r.setSchemaVersion(0);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("org", orgUuid.toString());
		recordData.put("lifecycle", "ASSEMBLED");
		recordData.put("artifacts", new ArrayList<String>());
		r.setRecordData(recordData);
		r.setMetrics(new HashMap<>());
		return releaseRepository.save(r);
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

	private boolean eligibleByUpdate(UUID releaseUuid) {
		return releaseRepository.isEligibleForMetricsComputeByUpdate(releaseUuid);
	}

	private Release reload(UUID releaseUuid) {
		return releaseRepository.findById(releaseUuid).orElseThrow();
	}

	private void compute(UUID releaseUuid) {
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(reload(releaseUuid).getUuid());
	}

	private static Double epochOf(Release r, String key) {
		Object v = r.getMetrics() == null ? null : r.getMetrics().get(key);
		return v == null ? null : ((Number) v).doubleValue();
	}

	private static boolean notFenced(Release r) {
		FlowControl fc = r.getFlowControl();
		return fc == null || fc.metricsComputeFailureCount() == null || fc.metricsComputeFailureCount() == 0;
	}

	// ---- row 1: the stamp is in place, conditional on content; the clear too ----

	@Test
	public void stampNotScannableIsInPlaceAndIdempotent() {
		UUID org = newOrg();
		UUID a = saveArtifactWithLastScanned(org, null).getUuid();
		int revision = revisionOf(a);

		assertEquals(1, artifactRepository.stampNotScannable(a, "NO_PURL_OR_CPE", 2, 0, 0, 2, 1_790_000_000.25));
		Map<String, Object> m = markerOf(a);
		assertMarker(m, "NO_PURL_OR_CPE", 2, 0, 0, 2);
		assertEquals(1_790_000_000.25, ((Number) m.get("at")).doubleValue(), 1e-6);
		assertEquals(6, m.size(), "reason, at and the four counts, nothing else: " + m);
		assertEquals("keep-me", metricOf(a, "projectName"));
		assertEquals(revision, revisionOf(a), "no metrics_revision bump");
		assertEquals(0, auditRowsOf(a), "no audit row");
		assertTrue(jdbcTemplate.queryForObject("SELECT metrics_totals->'notScannable' = metrics->'notScannable' "
				+ "FROM rearm.artifacts WHERE uuid = ?", Boolean.class, a), "metrics_totals carries the marker");

		assertEquals(0, artifactRepository.stampNotScannable(a, "NO_PURL_OR_CPE", 2, 0, 0, 2, 1_790_000_099.0),
				"the same content writes nothing");
		assertEquals(1_790_000_000.25, ((Number) markerOf(a).get("at")).doubleValue(), 1e-6, "at is kept");

		assertEquals(1, artifactRepository.stampNotScannable(a, "ALL_TERMINAL", 3, 1, 2, 0, 1_790_000_100.5));
		assertMarker(markerOf(a), "ALL_TERMINAL", 3, 1, 2, 0);
		assertEquals(1_790_000_100.5, ((Number) markerOf(a).get("at")).doubleValue(), 1e-6, "a change re-stamps at");

		assertEquals(1, artifactRepository.clearNotScannable(a));
		assertEquals(0, artifactRepository.clearNotScannable(a));
		assertNull(markerOf(a), "the key is gone");
		assertEquals("keep-me", metricOf(a, "projectName"));
		assertEquals(revision, revisionOf(a), "still no revision bump");
		assertEquals(0, auditRowsOf(a));
	}

	// ---- row 2: a JSON null at the key and a NULL column ----

	@Test
	public void stampNotScannableToleratesJsonNullAndNullColumn() {
		UUID org = newOrg();
		UUID jsonNull = saveArtifactWithLastScanned(org, null).getUuid();
		UUID nullColumn = saveArtifactWithLastScanned(org, null).getUuid();
		jdbcTemplate.update("UPDATE rearm.artifacts SET metrics = '{\"notScannable\": null}'::jsonb WHERE uuid = ?",
				jsonNull);
		jdbcTemplate.update("UPDATE rearm.artifacts SET metrics = NULL WHERE uuid = ?", nullColumn);

		assertEquals(1, artifactRepository.stampNotScannable(jsonNull, "ONLY_ROOT", 1, 1, 0, 0, 1_790_000_000.0));
		assertMarker(markerOf(jsonNull), "ONLY_ROOT", 1, 1, 0, 0);
		assertEquals(1, artifactRepository.stampNotScannable(nullColumn, "NO_COMPONENTS", 0, 0, 0, 0,
				1_790_000_000.0));
		assertMarker(markerOf(nullColumn), "NO_COMPONENTS", 0, 0, 0, 0);

		UUID fresh = saveArtifactWithLastScanned(org, null).getUuid();
		jdbcTemplate.update("UPDATE rearm.artifacts SET metrics = '{\"notScannable\": null}'::jsonb WHERE uuid = ?",
				fresh);
		assertEquals(1, artifactRepository.clearNotScannable(fresh), "a JSON null at the key is removed too");
		assertEquals("{}", metricsTextOf(fresh));
	}

	// ---- row 3: marked, stamped fanOutAt, out of the pool; another org untouched ----

	@Test
	public void notScannableArtifactLeavesTheCandidatePool() {
		UUID org = newOrg();
		UUID org2 = newOrg();
		SyntheticDtrackBucket bucket = unrelatedBucket(org);
		Mapped m = mapBom(org, null, null, unbucketedComponent(org, "generic:thing-" + UUID.randomUUID()),
				rootComponent(org));
		UUID other = saveArtifactWithLastScanned(org2, null).getUuid();
		mapRow(org2, other, m.canonical(), null);
		int revision = revisionOf(m.artifact());
		String otherBefore = metricsTextOf(other);
		assertNull(flowControlOf(other));

		syntheticSbomService.fanOutOrg(org);

		assertMarker(markerOf(m.artifact()), "NO_PURL_OR_CPE", 2, 1, 0, 1);
		assertNull(metricOf(m.artifact(), "firstScanned"), "never stamped scanned");
		assertNull(metricOf(m.artifact(), "lastScanned"), "never stamped scanned");
		assertEquals(iso(bucket.getLastUpdatedDate()), fanOutAtOf(m.artifact()));
		assertTrue(neverFannedOut(org).isEmpty(), "the canonical left (A)");
		assertEquals(0, auditRowsOf(m.artifact()));
		assertEquals(revision, revisionOf(m.artifact()));

		String after = metricsTextOf(m.artifact());
		syntheticSbomService.fanOutOrg(org);
		assertEquals(after, metricsTextOf(m.artifact()), "a second pass writes nothing");

		assertEquals(otherBefore, metricsTextOf(other), "another org's artifact on the same canonical uuid (T4)");
		assertNull(flowControlOf(other), "another org's map row is never stamped (T4)");
	}

	// ---- row 4: a matchable row lands and the requeued canonical is scanned ----

	@Test
	public void notScannableThenMatchableIsScannedOnRequeue() {
		UUID org = newOrg();
		unrelatedBucket(org);
		Mapped m = mapBom(org, null, null, unbucketedComponent(org, "generic:thing-" + UUID.randomUUID()),
				rootComponent(org));
		syntheticSbomService.fanOutOrg(org);
		assertNotNull(markerOf(m.artifact()));

		String latePurl = uniquePurl("late");
		holdComponent(org, m.canonical(), component(org, latePurl, 1));
		SyntheticDtrackBucket bucket1 = bucketAt(org, 1, nowMicros(), latePurl);
		artifactCanonicalMapRepository.requeueFanOutForCanonical(org, m.canonical());

		// Case 2 (TPF-121 merged first): between the requeue and the pass the explainer reads the marker.
		ArtifactScanStatusDto before = explain(m.artifact());
		assertEquals(ArtifactScanState.NO_MATCHABLE_COMPONENTS, before.state());
		assertEquals(1, before.matchableComponentCount(), "the live count shows the gained row");
		assertNotNull(before.notScannableAt());
		assertTrue(before.inCandidateSet(), "the requeued canonical qualifies for the clearing pass");

		syntheticSbomService.fanOutOrg(org);

		ArtifactScanStatusDto after = explain(m.artifact());
		assertEquals(ArtifactScanState.SCANNED, after.state());
		assertNull(after.noMatchableComponentsReason());
		assertNull(after.notScannableAt());
		assertNull(markerOf(m.artifact()), "the scan's whole save drops the marker");
		assertNotNull(metricOf(m.artifact(), "firstScanned"));
		assertNotNull(metricOf(m.artifact(), "lastScanned"));
		assertEquals(iso(bucket1.getLastUpdatedDate()), fanOutAtOf(m.artifact()));
		assertEquals(1, auditRowsOf(m.artifact()), "the scan's audited save");
	}

	// ---- row 5: a later BOM version has its own state ----

	@Test
	public void laterBomVersionHasItsOwnState() {
		UUID org = newOrg();
		String coveredPurl = uniquePurl("covered");
		bucketAt(org, 0, nowMicros().minusMinutes(1), uniquePurl("unrelated"), coveredPurl);
		Mapped a = mapBom(org, null, null, unbucketedComponent(org, "generic:thing-" + UUID.randomUUID()));
		Mapped b = mapBom(org, null, null, component(org, coveredPurl, 0));

		syntheticSbomService.fanOutOrg(org);

		assertNotNull(markerOf(a.artifact()));
		assertNull(metricOf(a.artifact(), "firstScanned"));
		assertNull(markerOf(b.artifact()));
		assertNotNull(metricOf(b.artifact(), "firstScanned"));
	}

	// ---- row 6: a legacy vacuous row gains the marker and keeps its stamps ----

	@Test
	public void legacyVacuousRowGetsTheMarkerAndKeepsFirstScanned() {
		UUID org = newOrg();
		unrelatedBucket(org);
		Mapped m = mapBom(org, STALE_EPOCH, null, unbucketedComponent(org, "generic:thing-" + UUID.randomUUID()));
		int revision = revisionOf(m.artifact());

		syntheticSbomService.fanOutOrg(org);

		assertMarker(markerOf(m.artifact()), "NO_PURL_OR_CPE", 1, 0, 0, 1);
		assertEquals(STALE_EPOCH, ((Number) metricOf(m.artifact(), "firstScanned")).doubleValue(), 1e-6);
		assertEquals(STALE_EPOCH, ((Number) metricOf(m.artifact(), "lastScanned")).doubleValue(), 1e-6);
		assertEquals(0, auditRowsOf(m.artifact()));
		assertEquals(revision, revisionOf(m.artifact()));
		assertNotNull(fanOutAtOf(m.artifact()));
	}

	// ---- row 7: the stall counter and the sample keep the gate's universe ----

	@Test
	public void stallQueriesKeepTheGatesUniverse() {
		UUID org = newOrg();
		unrelatedBucket(org);
		Mapped m = mapBom(org, null, null, unbucketedComponent(org, "generic:thing-" + UUID.randomUUID()),
				rootComponent(org));
		syntheticSbomService.fanOutOrg(org);
		assertNotNull(markerOf(m.artifact()));
		jdbcTemplate.update("UPDATE rearm.artifacts SET created_date = now() - interval '3 hours' WHERE uuid = ?",
				m.artifact());
		ZonedDateTime now = ZonedDateTime.now();

		// (a) a not-scannable artifact is neither counted nor sampled
		assertEquals(0, artifactCanonicalMapRepository.countNeverScannedMappedArtifactsOlderThan(org, now));
		assertTrue(artifactCanonicalMapRepository.findNeverScannedMappedArtifactCanonicalsOlderThan(org, now, 10)
				.isEmpty());

		// (b) a pending twin in its own org is counted and sampled
		UUID org2 = newOrg();
		UUID pendingCanonical = mapArtifactWithComponent(org2, uniquePurl("pending"), null);
		jdbcTemplate.update("UPDATE rearm.artifacts a SET created_date = now() - interval '3 hours' "
				+ "FROM rearm.artifact_canonical_map acm WHERE acm.artifact_uuid = a.uuid "
				+ "AND acm.canonical_artifact_uuid = ?", pendingCanonical);
		assertEquals(1, artifactCanonicalMapRepository.countNeverScannedMappedArtifactsOlderThan(org2, now));
		assertEquals(List.of(pendingCanonical),
				artifactCanonicalMapRepository.findNeverScannedMappedArtifactCanonicalsOlderThan(org2, now, 10));

		// (c) a stale marker on a canonical that gained a matchable row is counted and sampled
		holdComponent(org, m.canonical(), unbucketedComponent(org, uniquePurl("gained")));
		assertNotNull(markerOf(m.artifact()), "the marker is stale: no pass ran since");
		assertEquals(1, artifactCanonicalMapRepository.countNeverScannedMappedArtifactsOlderThan(org, now));
		assertEquals(List.of(m.canonical()),
				artifactCanonicalMapRepository.findNeverScannedMappedArtifactCanonicalsOlderThan(org, now, 10));
	}

	// ---- row 13: the carry-forward seed never lands on a marked row ----

	@Test
	public void theSeedNeverLandsOnANotScannableArtifact() throws Exception {
		UUID org = newOrg();
		UUID marked = saveArtifactWithLastScanned(org, null).getUuid();
		assertEquals(1, artifactRepository.stampNotScannable(marked, "NO_PURL_OR_CPE", 2, 0, 0, 2, 1_790_000_000.25));
		UUID unmarked = saveArtifactWithLastScanned(org, null).getUuid();
		String before = metricsTextOf(marked);
		Map<String, Object> seed = new HashMap<>();
		seed.put("projectName", "seeded");
		String json = Utils.OM.writeValueAsString(seed);

		assertEquals(0, artifactRepository.updateMetricsIfStillUnscanned(marked, json));
		assertEquals(before, metricsTextOf(marked), "the marked row is byte-equal");
		assertEquals(1, artifactRepository.updateMetricsIfStillUnscanned(unmarked, json));
	}

	// ---- row 14: the clear returns the carrying release to scan pending (F-1) ----

	@Test
	public void clearedMarkerReturnsTheCarryingReleaseToScanPending() {
		UUID org = newOrg();
		unrelatedBucket(org);
		// (a) a map row whose canonical has zero component rows (the TPF-114 W4 window)
		Mapped m = mapBom(org, null, null);
		Release r = saveScanPendingRelease(org);
		attachArtifact(r.getUuid(), m.artifact());
		// (b) the legacy twin: vacuously stamped, zero rows
		Mapped legacy = mapBom(org, STALE_EPOCH, null);
		Release r2 = saveScanPendingRelease(org);
		attachArtifact(r2.getUuid(), legacy.artifact());

		// tick 1: both marked NO_COMPONENTS
		syntheticSbomService.fanOutOrg(org);
		assertMarker(markerOf(m.artifact()), "NO_COMPONENTS", 0, 0, 0, 0);
		assertMarker(markerOf(legacy.artifact()), "NO_COMPONENTS", 0, 0, 0, 0);
		assertEquals(STALE_EPOCH, ((Number) metricOf(legacy.artifact(), "firstScanned")).doubleValue(), 1e-6);
		String fanOutAtTick1 = fanOutAtOf(m.artifact());
		assertNotNull(fanOutAtTick1);

		compute(r.getUuid());
		compute(r2.getUuid());
		Release anchored = reload(r.getUuid());
		assertEquals(Utils.toFractionalEpochSecond(anchored.getCreatedDate()), epochOf(anchored, "firstScanned"), 1e-3,
				"anchored like a no-BOM release");
		assertNotNull(epochOf(anchored, "lastScanned"));
		assertFalse(eligibleByUpdate(r.getUuid()), "settled");
		assertTrue(notFenced(anchored));
		Release legacyRelease = reload(r2.getUuid());
		assertEquals(STALE_EPOCH, epochOf(legacyRelease, "firstScanned"), 1e-3, "the firstScanned max");
		assertFalse(eligibleByUpdate(r2.getUuid()));

		// an uncovered pkg: row lands on both canonicals, and both are requeued
		holdComponent(org, m.canonical(), unbucketedComponent(org, uniquePurl("late")));
		artifactCanonicalMapRepository.requeueFanOutForCanonical(org, m.canonical());
		holdComponent(org, legacy.canonical(), unbucketedComponent(org, uniquePurl("late-legacy")));
		artifactCanonicalMapRepository.requeueFanOutForCanonical(org, legacy.canonical());

		// tick 2: the gate skip clears both markers
		syntheticSbomService.fanOutOrg(org);
		assertNull(markerOf(m.artifact()));
		assertNull(metricOf(m.artifact(), "firstScanned"));
		assertNull(metricOf(m.artifact(), "lastScanned"));
		assertEquals(fanOutAtTick1, fanOutAtOf(m.artifact()), "the gate skip stamps the same cutoff");
		assertTrue(eligibleByUpdate(r.getUuid()), "the clear touched the carrying release");
		assertNull(markerOf(legacy.artifact()));
		assertFalse(eligibleByUpdate(r2.getUuid()), "CLEARED_STAMPED: the legacy twin's release is not touched");

		compute(r.getUuid());
		Release pending = reload(r.getUuid());
		assertNull(epochOf(pending, "firstScanned"), "back to scan pending");
		assertNotNull(pending.getFlowControl(), "fenced on scan incomplete");
		assertEquals(1, pending.getFlowControl().metricsComputeFailureCount());
		assertNotNull(pending.getFlowControl().metricsComputeSkipUntil());

		compute(r2.getUuid());
		Release legacyAfter = reload(r2.getUuid());
		assertEquals(STALE_EPOCH, epochOf(legacyAfter, "firstScanned"), 1e-3, "the legacy release keeps its value");
		assertTrue(notFenced(legacyAfter));
	}

	/** Rows of a canonical with a terminal component are classified ALL_TERMINAL on the database too. */
	@Test
	public void allTerminalCanonicalIsMarkedOnTheDatabase() {
		UUID org = newOrg();
		unrelatedBucket(org);
		Mapped m = mapBom(org, null, null, rootComponent(org), terminalComponent(org, uniquePurl("t1")));

		syntheticSbomService.fanOutOrg(org);

		assertMarker(markerOf(m.artifact()), "ALL_TERMINAL", 2, 1, 1, 0);
		assertNull(metricOf(m.artifact(), "firstScanned"));
	}
}
