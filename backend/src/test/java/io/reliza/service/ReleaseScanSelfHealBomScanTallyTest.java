/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.common.Utils;
import io.reliza.model.FlowControl;
import io.reliza.model.NoMatchableComponentsReason;
import io.reliza.model.Organization;
import io.reliza.model.Release;
import io.reliza.model.dto.ReleaseMetricsDto.BomScanTally;

/**
 * TPF-129 architecture 6.2 row 1: the metrics compute persists the BOM tally at release
 * metrics.bomScan. The tally is the compute's own gathering counted with isScanInput; a tally-only
 * change is stamped in place (no metrics_revision bump, no metrics_audit row) and reported so the
 * trigger pass runs; a legacy row without a tally is backfilled silently; a findings change carries
 * the tally in the audited save.
 */
public class ReleaseScanSelfHealBomScanTallyTest extends ReleaseScanSelfHealTestBase {

	private static final List<NoMatchableComponentsReason> NO_PURL = List.of(NoMatchableComponentsReason.NO_PURL_OR_CPE);

	@Autowired private JdbcTemplate jdbcTemplate;

	private Release reload(UUID uuid) {
		return releaseRepository.findById(uuid).orElseThrow();
	}

	private boolean compute(UUID uuid) {
		return releaseMetricsComputeService.computeReleaseMetricsOnRescan(reload(uuid).getUuid());
	}

	private String bomScanJson(UUID release) {
		return jdbcTemplate.queryForObject("SELECT cast(metrics->'bomScan' AS text) FROM rearm.releases WHERE uuid = ?",
				String.class, release);
	}

	private BomScanTally bomScan(UUID release) {
		String json = bomScanJson(release);
		return null == json ? null : Utils.OM.readValue(json, BomScanTally.class);
	}

	private long revision(UUID release) {
		return jdbcTemplate.queryForObject("SELECT metrics_revision FROM rearm.releases WHERE uuid = ?", Long.class, release);
	}

	private long auditRows(UUID release) {
		return jdbcTemplate.queryForObject("SELECT count(*) FROM rearm.metrics_audit WHERE entity_uuid = ?", Long.class,
				release);
	}

	private static double epoch(Object v) {
		return ((Number) v).doubleValue();
	}

	private static boolean notFenced(Release r) {
		FlowControl fc = r.getFlowControl();
		return fc == null || fc.metricsComputeFailureCount() == null || fc.metricsComputeFailureCount() == 0;
	}

	private UUID notScannableBom(UUID org) {
		UUID bom = saveUnscannedBomArtifact(org);
		markBomNotScannable(bom, NoMatchableComponentsReason.NO_PURL_OR_CPE);
		return bom;
	}

	private UUID scannedBom(UUID org, int findings) {
		UUID bom = saveUnscannedBomArtifact(org);
		markBomScanned(bom, findings);
		return bom;
	}

	/** (a)'s shape: an ASSEMBLED release whose only BOM is not scannable. */
	private Release allNotScannableRelease(UUID org) {
		Release r = saveScanPendingRelease(org, new ArrayList<>());
		attachArtifact(r.getUuid(), notScannableBom(org));
		return r;
	}

	@Test
	@SuppressWarnings("unchecked")
	public void releaseWithOnlyNotScannableBomsCarriesTheTally() {
		Organization org = testInitializer.obtainOrganization();
		Release r = allNotScannableRelease(org.getUuid());

		assertTrue(compute(r.getUuid()), "the first save");

		assertEquals(Map.of("scannableBoms", 0, "notScannableBoms", 1, "notScannableReasons", List.of("NO_PURL_OR_CPE")),
				Utils.OM.readValue(bomScanJson(r.getUuid()), Map.class));
		Release after = reload(r.getUuid());
		assertEquals(Utils.toFractionalEpochSecond(after.getCreatedDate()), epoch(firstScanned(after)), 1e-3,
				"anchored like a no-BOM release (TPF-126 row 9)");
	}

	@Test
	public void mixedReleaseCountsBoth() {
		Organization org = testInitializer.obtainOrganization();
		Release r = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		attachArtifacts(r.getUuid(), List.of(scannedBom(org.getUuid(), 2), notScannableBom(org.getUuid())));

		compute(r.getUuid());

		assertEquals(new BomScanTally(1, 1, NO_PURL), bomScan(r.getUuid()));
	}

	@Test
	public void noBomReleaseIsNoBoms() {
		Organization org = testInitializer.obtainOrganization();
		Release r = saveScanPendingRelease(org.getUuid(), new ArrayList<>());

		compute(r.getUuid());

		assertEquals(new BomScanTally(0, 0, List.of()), bomScan(r.getUuid()));
	}

	@Test
	public void scannedReleaseIsAllScannable() {
		Organization org = testInitializer.obtainOrganization();
		Release scanned = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		attachArtifact(scanned.getUuid(), scannedBom(org.getUuid(), 1));
		Release pending = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		attachArtifact(pending.getUuid(), saveUnscannedBomArtifact(org.getUuid()));

		compute(scanned.getUuid());
		compute(pending.getUuid());

		assertEquals(new BomScanTally(1, 0, List.of()), bomScan(scanned.getUuid()));
		assertEquals(new BomScanTally(1, 0, List.of()), bomScan(pending.getUuid()), "a pending BOM is scannable");
		assertFalse(notFenced(reload(pending.getUuid())), "the pending twin is fenced as today");
		assertNull(firstScanned(reload(pending.getUuid())));
	}

	@Test
	public void tallyOnlyChangeIsStampedInPlaceAndReported() {
		Organization org = testInitializer.obtainOrganization();
		Release r = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		UUID scanned = scannedBom(org.getUuid(), 1);
		attachArtifact(r.getUuid(), scanned);
		assertTrue(compute(r.getUuid()));
		assertFalse(compute(r.getUuid()), "nothing moved on the second compute");
		long revision = revision(r.getUuid());
		long audits = auditRows(r.getUuid());
		Release before = reload(r.getUuid());
		Object firstScannedBefore = firstScanned(before);
		double lastScannedBefore = epoch(lastScanned(before));

		attachArtifacts(r.getUuid(), List.of(scanned, notScannableBom(org.getUuid())));
		assertTrue(compute(r.getUuid()), "a tally-only change is reported so the trigger pass runs");

		assertEquals(new BomScanTally(1, 1, NO_PURL), bomScan(r.getUuid()));
		assertEquals(revision, revision(r.getUuid()), "no metrics_revision bump");
		assertEquals(audits, auditRows(r.getUuid()), "no metrics_audit row");
		Release after = reload(r.getUuid());
		assertEquals(epoch(firstScannedBefore), epoch(firstScanned(after)), 1e-6, "firstScanned unchanged");
		assertTrue(epoch(lastScanned(after)) > lastScannedBefore, "lastScanned stamped");
		assertFalse(eligibleByUpdate(r.getUuid()), "settled");
	}

	@Test
	public void legacyRowIsBackfilledSilently() {
		Organization org = testInitializer.obtainOrganization();
		UUID bom = scannedBom(org.getUuid(), 0);
		Object bomFirstScanned = artifactRepository.findById(bom).orElseThrow().getMetrics().get("firstScanned");
		// A release computed before TPF-129: its metrics carry the scan stamps (the BOM's own, so nothing
		// in equals moves) and no bomScan key.
		Release r = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		Map<String, Object> metrics = new HashMap<>();
		metrics.put("firstScanned", bomFirstScanned);
		metrics.put("lastScanned", epoch(bomFirstScanned) - 60);
		r.setMetrics(metrics);
		releaseRepository.save(r);
		attachArtifact(r.getUuid(), bom);
		assertNull(bomScanJson(r.getUuid()));
		long revision = revision(r.getUuid());
		long audits = auditRows(r.getUuid());

		assertFalse(compute(r.getUuid()), "a backfill is not a change: no trigger pass");

		assertEquals(new BomScanTally(1, 0, List.of()), bomScan(r.getUuid()));
		assertEquals(revision, revision(r.getUuid()));
		assertEquals(audits, auditRows(r.getUuid()));
		assertEquals(epoch(bomFirstScanned), epoch(firstScanned(reload(r.getUuid()))), 1e-6);
	}

	@Test
	@SuppressWarnings("unchecked")
	public void findingsChangeCarriesTheTally() {
		Organization org = testInitializer.obtainOrganization();
		Release r = allNotScannableRelease(org.getUuid());
		compute(r.getUuid());
		long revision = revision(r.getUuid());
		List<String> arts = (List<String>) reload(r.getUuid()).getRecordData().get("artifacts");
		UUID marked = UUID.fromString(arts.get(0));

		attachArtifacts(r.getUuid(), List.of(marked, scannedBom(org.getUuid(), 2)));
		assertTrue(compute(r.getUuid()));

		assertEquals(revision + 1, revision(r.getUuid()), "the audited save");
		assertEquals(new BomScanTally(1, 1, NO_PURL), bomScan(r.getUuid()));
		assertEquals(2, ((List<?>) reload(r.getUuid()).getMetrics().get("vulnerabilityDetails")).size());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void clearedMarkerMovesTheTally() {
		Organization org = testInitializer.obtainOrganization();
		Release r = allNotScannableRelease(org.getUuid());
		compute(r.getUuid());
		assertNotNull(firstScanned(reload(r.getUuid())));
		UUID bom = UUID.fromString(((List<String>) reload(r.getUuid()).getRecordData().get("artifacts")).get(0));

		artifactRepository.clearNotScannable(bom);
		attachArtifact(r.getUuid(), bom);
		assertTrue(compute(r.getUuid()));

		Release after = reload(r.getUuid());
		assertNull(firstScanned(after), "the BOM is a pending scan input again (TPF-126 row 14)");
		assertFalse(notFenced(after), "fenced while pending");
		assertEquals(new BomScanTally(1, 0, List.of()), bomScan(r.getUuid()));
	}

	@Test
	public void productTallyIsItsOwn() {
		Organization org = testInitializer.obtainOrganization();
		Release child = allNotScannableRelease(org.getUuid());
		Release product = saveScanPendingRelease(org.getUuid(), parentReleasesOf(child.getUuid()));

		compute(child.getUuid());
		compute(product.getUuid());

		assertEquals(new BomScanTally(0, 1, NO_PURL), bomScan(child.getUuid()));
		assertEquals(new BomScanTally(0, 0, List.of()), bomScan(product.getUuid()), "children are not aggregated");
		assertNotNull(firstScanned(reload(product.getUuid())), "the product settles (TPF-126 row 10)");
	}
}
