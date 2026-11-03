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
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.model.FlowControl;
import io.reliza.model.NoMatchableComponentsReason;
import io.reliza.model.Organization;
import io.reliza.model.Release;

/**
 * TPF-125 / TPF-126 architecture 6.3 rows 8-11: a BOM marked not scannable is no scan input of the
 * release metrics compute. It neither blocks nor anchors firstScanned beside a scanned BOM; a release
 * whose BOMs are all not scannable anchors like a no-BOM release (and so do its products); a swap onto
 * such a BOM re-derives the release to zero, not held (threat model T6, pinned on purpose).
 */
public class ReleaseScanSelfHealNotScannableTest extends ReleaseScanSelfHealTestBase {

	private Release reload(UUID uuid) {
		return releaseRepository.findById(uuid).orElseThrow();
	}

	private void compute(UUID uuid) {
		releaseMetricsComputeService.computeReleaseMetricsOnRescan(reload(uuid).getUuid());
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

	private long childNudges() {
		return ((AtomicLong) ReflectionTestUtils.getField(releaseMetricsComputeService, "childNudges")).get();
	}

	// ---- row 8 ----

	@Test
	public void notScannableBomDoesNotBlockTheRelease() {
		Organization org = testInitializer.obtainOrganization();
		Release r = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		UUID scanned = saveUnscannedBomArtifact(org.getUuid());
		markBomScanned(scanned, 2);
		UUID notScannable = notScannableBom(org.getUuid());
		attachArtifacts(r.getUuid(), List.of(scanned, notScannable));

		compute(r.getUuid());

		Release after = reload(r.getUuid());
		Object bomFirstScanned = artifactRepository.findById(scanned).orElseThrow().getMetrics().get("firstScanned");
		assertNotNull(firstScanned(after), "the not-scannable BOM does not block firstScanned");
		assertEquals(epoch(bomFirstScanned), epoch(firstScanned(after)), 1e-3, "the scanned BOM's stamp");
		assertNotNull(lastScanned(after));
		assertEquals(2, ((List<?>) after.getMetrics().get("vulnerabilityDetails")).size());
		assertTrue(notFenced(after));
		assertFalse(eligibleByUpdate(r.getUuid()), "settled");
	}

	// ---- row 9 ----

	@Test
	public void releaseWithOnlyNotScannableBomsAnchorsLikeNoBom() {
		Organization org = testInitializer.obtainOrganization();
		Release r = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		attachArtifact(r.getUuid(), notScannableBom(org.getUuid()));

		compute(r.getUuid());

		Release after = reload(r.getUuid());
		assertNotNull(firstScanned(after), "anchored: nothing to scan");
		assertEquals(Utils.toFractionalEpochSecond(after.getCreatedDate()), epoch(firstScanned(after)), 1e-3);
		assertNotNull(lastScanned(after));
		assertTrue(notFenced(after));

		Release draft = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		setLifecycle(draft.getUuid(), "DRAFT");
		attachArtifact(draft.getUuid(), notScannableBom(org.getUuid()));

		compute(draft.getUuid());

		Release draftAfter = reload(draft.getUuid());
		assertNotNull(lastScanned(draftAfter));
		assertNull(firstScanned(draftAfter), "a DRAFT release is not anchored");
		assertTrue(notFenced(draftAfter));
	}

	// ---- row 10 ----

	@Test
	public void productSettlesWhenItsChildHasOnlyNotScannableBoms() {
		Organization org = testInitializer.obtainOrganization();
		Release child = saveScanPendingRelease(org.getUuid(), new ArrayList<>());
		attachArtifact(child.getUuid(), notScannableBom(org.getUuid()));
		Release product = saveScanPendingRelease(org.getUuid(), parentReleasesOf(child.getUuid()));
		long nudgesBefore = childNudges();

		compute(child.getUuid());
		compute(product.getUuid());

		assertNotNull(firstScanned(reload(product.getUuid())), "the product settles over its anchored child");
		assertTrue(releaseRepository.findSettledUnscannedAmong(List.of(child.getUuid())).isEmpty(),
				"the child is not a settled unscanned child");
		assertEquals(nudgesBefore, childNudges());
	}

	// ---- row 11 (T6, pinned on purpose) ----

	@Test
	public void swapToNotScannableBomReDerivesToZeroNotHeld() {
		Organization org = testInitializer.obtainOrganization();
		Release r = saveReleaseWithFindings(org.getUuid(), "ASSEMBLED", null, 6);
		attachArtifact(r.getUuid(), notScannableBom(org.getUuid()));

		compute(r.getUuid());

		Release after = reload(r.getUuid());
		assertEquals(0, ((List<?>) after.getMetrics().get("vulnerabilityDetails")).size(),
				"no matchable component is a definitive outcome of the new BOM, not a pending input");
		assertNotNull(lastScanned(after));
		assertTrue(notFenced(after));
	}
}
