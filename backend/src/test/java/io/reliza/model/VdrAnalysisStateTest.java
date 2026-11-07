/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.VdrAnalysisState.Derived;
import io.reliza.model.VdrAnalysisState.OwnBomFacts;
import io.reliza.model.VdrAnalysisState.Reason;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.BomScanTally;

/**
 * FDAW-2 (B2): the VDR analysis state, per release unit. A leaf's anchor firstScanned (the no-BOM
 * createdDate) never reads as a scan, and a product is derived from its children, never from its own
 * stored firstScanned; completedAt is only ever a real scan. Design T1 (a)-(o).
 */
class VdrAnalysisStateTest {

	private static final ZonedDateTime T1 = ZonedDateTime.parse("2026-11-01T10:00:00Z");
	private static final ZonedDateTime T2 = ZonedDateTime.parse("2026-11-02T10:00:00Z");
	private static final ZonedDateTime T3 = ZonedDateTime.parse("2026-11-03T10:00:00Z");
	private static final ZonedDateTime T4 = ZonedDateTime.parse("2026-11-04T10:00:00Z");

	private static final BomScanTally NO_BOMS = BomScanTally.EMPTY;
	private static final BomScanTally ALL_SCANNABLE = new BomScanTally(2, 0, List.of());
	private static final BomScanTally ALL_NOT_SCANNABLE = new BomScanTally(0, 2,
			List.of(NoMatchableComponentsReason.NO_COMPONENTS));
	private static final BomScanTally SOME_NOT_SCANNABLE = new BomScanTally(1, 1,
			List.of(NoMatchableComponentsReason.NO_COMPONENTS));

	private static ReleaseMetricsDto metrics(ZonedDateTime firstScanned) {
		ReleaseMetricsDto m = new ReleaseMetricsDto();
		m.setFirstScanned(firstScanned);
		return m;
	}

	private static void assertCounts(Derived d, int analysed, int notAnalysed, int pending) {
		assertEquals(analysed, d.analysedReleases(), "analysed");
		assertEquals(notAnalysed, d.notAnalysedReleases(), "not analysed");
		assertEquals(pending, d.pendingReleases(), "pending");
	}

	private static Derived completedLeaf(ZonedDateTime at) {
		return VdrAnalysisState.deriveLeaf(metrics(at), ALL_SCANNABLE);
	}

	private static Derived pendingLeaf() {
		return VdrAnalysisState.deriveLeaf(metrics(null), ALL_SCANNABLE);
	}

	private static Derived noSbomLeaf(ZonedDateTime anchor) {
		return VdrAnalysisState.deriveLeaf(metrics(anchor), NO_BOMS);
	}

	// ---- deriveLeaf ----

	@Test
	void a_aLeafWithoutBomsIsNotAnalysedNoSbom_evenWithItsAnchorFirstScanned() {
		Derived d = noSbomLeaf(T3);
		assertEquals(VdrAnalysisState.NOT_ANALYSED, d.state());
		assertEquals(Reason.NO_SBOM, d.reason());
		assertNull(d.completedAt());
		assertCounts(d, 0, 1, 0);
	}

	@Test
	void b_aLeafWhoseBomsAreAllNotScannableIsSbomNotScannable() {
		Derived d = VdrAnalysisState.deriveLeaf(metrics(T3), ALL_NOT_SCANNABLE);
		assertEquals(VdrAnalysisState.NOT_ANALYSED, d.state());
		assertEquals(Reason.SBOM_NOT_SCANNABLE, d.reason());
		assertNull(d.completedAt());
		assertCounts(d, 0, 1, 0);
	}

	@Test
	void c_aLeafWithAScannableBomAndNoFirstScanIsScanPending() {
		Derived d = pendingLeaf();
		assertEquals(VdrAnalysisState.NOT_ANALYSED, d.state());
		assertEquals(Reason.SCAN_PENDING, d.reason());
		assertNull(d.completedAt());
		assertCounts(d, 0, 0, 1);
	}

	@Test
	void d_aScannedLeafIsCompletedAtItsFirstScan() {
		Derived d = completedLeaf(T2);
		assertEquals(VdrAnalysisState.COMPLETED, d.state());
		assertNull(d.reason());
		assertEquals(T2, d.completedAt());
		assertCounts(d, 1, 0, 0);
	}

	@Test
	void g_aLeafNeverComputedWithAScannableTallyIsScanPending() {
		Derived d = VdrAnalysisState.deriveLeaf(null, ALL_SCANNABLE);
		assertEquals(Reason.SCAN_PENDING, d.reason());
		assertCounts(d, 0, 0, 1);
	}

	@Test
	void h_aLeafWithSomeNotScannableBomsAndAFirstScanIsCompleted() {
		Derived d = VdrAnalysisState.deriveLeaf(metrics(T1), SOME_NOT_SCANNABLE);
		assertEquals(VdrAnalysisState.COMPLETED, d.state());
		assertEquals(T1, d.completedAt());
	}

	// ---- combine ----

	@Test
	void e_twoCompletedChildrenAreCompletedAtTheLaterScan() {
		Derived d = VdrAnalysisState.combine(null, List.of(completedLeaf(T1), completedLeaf(T2)));
		assertEquals(VdrAnalysisState.COMPLETED, d.state());
		assertEquals(T2, d.completedAt());
		assertCounts(d, 2, 0, 0);
	}

	@Test
	void f_onePendingChildMakesTheProductScanPending() {
		Derived d = VdrAnalysisState.combine(null, List.of(pendingLeaf(), completedLeaf(T1)));
		assertEquals(VdrAnalysisState.NOT_ANALYSED, d.state());
		assertEquals(Reason.SCAN_PENDING, d.reason());
		assertNull(d.completedAt());
		assertEquals(1, d.pendingReleases());
		assertEquals(2, d.totalReleases());
	}

	@Test
	void i_childrenWithoutSbomsAreNoSbom_neverCompletedAtTheirAnchors() {
		Derived d = VdrAnalysisState.combine(null, List.of(noSbomLeaf(T1), noSbomLeaf(T2)));
		assertEquals(VdrAnalysisState.NOT_ANALYSED, d.state());
		assertEquals(Reason.NO_SBOM, d.reason());
		assertNull(d.completedAt());
		assertCounts(d, 0, 2, 0);
	}

	@Test
	void j_childrenWithNotScannableSbomsAreSbomNotScannable() {
		Derived d = VdrAnalysisState.combine(null, List.of(
				VdrAnalysisState.deriveLeaf(metrics(T1), ALL_NOT_SCANNABLE),
				VdrAnalysisState.deriveLeaf(metrics(T2), ALL_NOT_SCANNABLE)));
		assertEquals(Reason.SBOM_NOT_SCANNABLE, d.reason());
	}

	@Test
	void k_aScannedAndAnSbomLessChildArePartial_completedAtTheRealScanNotTheAnchor() {
		Derived d = VdrAnalysisState.combine(null, List.of(completedLeaf(T1), noSbomLeaf(T3)));
		assertEquals(VdrAnalysisState.PARTIAL, d.state());
		assertNull(d.reason());
		assertEquals(T1, d.completedAt());
		assertCounts(d, 1, 1, 0);
	}

	@Test
	void l_theProductsOwnScannedBomsAreAUnit() {
		Derived d = VdrAnalysisState.combine(new OwnBomFacts(ALL_SCANNABLE, T4, false), List.of(completedLeaf(T1)));
		assertEquals(VdrAnalysisState.COMPLETED, d.state());
		assertEquals(T4, d.completedAt());
		assertCounts(d, 2, 0, 0);
	}

	@Test
	void m_theProductsOwnUnscannedBomMakesItScanPending() {
		Derived d = VdrAnalysisState.combine(new OwnBomFacts(ALL_SCANNABLE, T4, true), List.of(completedLeaf(T1)));
		assertEquals(Reason.SCAN_PENDING, d.reason());
		assertCounts(d, 1, 0, 1);
	}

	@Test
	void n_theProductsOwnNotScannableBomsAreANotAnalysedUnit() {
		Derived d = VdrAnalysisState.combine(new OwnBomFacts(ALL_NOT_SCANNABLE, null, false),
				List.of(completedLeaf(T1)));
		assertEquals(VdrAnalysisState.PARTIAL, d.state());
		assertEquals(T1, d.completedAt());
		assertCounts(d, 1, 1, 0);
	}

	@Test
	void n2_theProductsOwnNoBomsTallyIsNoUnit() {
		Derived d = VdrAnalysisState.combine(new OwnBomFacts(NO_BOMS, null, false), List.of(completedLeaf(T1)));
		assertEquals(VdrAnalysisState.COMPLETED, d.state());
		assertCounts(d, 1, 0, 0);
	}

	@Test
	void n3_anSbomLessProductOverNotScannableAndSbomLessChildrenIsSbomNotScannable() {
		Derived d = VdrAnalysisState.combine(null, List.of(noSbomLeaf(T1),
				VdrAnalysisState.deriveLeaf(metrics(T2), ALL_NOT_SCANNABLE)));
		assertEquals(Reason.SBOM_NOT_SCANNABLE, d.reason());
		assertCounts(d, 0, 2, 0);
	}

	@Test
	void o_aNestedPartialChildsCountsAreSummed() {
		Derived nested = VdrAnalysisState.combine(null, List.of(completedLeaf(T1), noSbomLeaf(T3)));
		Derived d = VdrAnalysisState.combine(null, List.of(nested, completedLeaf(T2), noSbomLeaf(T4)));
		assertEquals(VdrAnalysisState.PARTIAL, d.state());
		assertEquals(T2, d.completedAt());
		assertCounts(d, 2, 2, 0);
		assertEquals(4, d.totalReleases());
	}
}
