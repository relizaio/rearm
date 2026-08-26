package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;
import io.reliza.service.FindingChangeEventBackfillService.RepairCause;
import io.reliza.service.FindingComparisonService.EventAttribution;
import io.reliza.service.FindingComparisonService.RevisionProduction;
import io.reliza.service.FindingComparisonService.V3Production;
import io.reliza.service.FindingDimBackfillService.RevisionWrite;
import io.reliza.service.FindingDimBackfillService.V3WriteResult;

/**
 * Pins the verdict the repair sweep's alert reports.
 *
 * <p>This expression is what an operator acts on: {@code emit_never_ran} sends them hunting a dropped write,
 * {@code emit_disagreed} tells them nothing was lost, {@code emit_skipped_lifecycle} tells them to ignore it.
 * Getting the arms the wrong way round is therefore worse than not reporting at all -- and until this class
 * existed, swapping two of them passed the entire suite.
 *
 * <p>Classification is per metrics REVISION, so the cases below are built as revisions, and the multi-revision
 * case asserts that one benign revision cannot excuse a lost emit sitting beside it.
 */
class RepairCauseClassificationTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final EventAttribution ATTR = new EventAttribution(
			UUID.randomUUID(), UUID.randomUUID(), "1.0.0", UUID.randomUUID(), "probe-comp", UUID.randomUUID());
	private static final UUID RELEASE = UUID.randomUUID();
	private static final ZonedDateTime T_CANCELLED = ZonedDateTime.parse("2026-08-02T00:00:00Z");
	private static final ZonedDateTime T_LIVE = ZonedDateTime.parse("2026-08-05T00:00:00Z");

	private final FindingChangeEventBackfillService svc = new FindingChangeEventBackfillService();

	/** Healthy release, no lifecycle history -- so lifecycle never explains anything away. */
	private static ReleaseData healthy() {
		ReleaseData rd = new ReleaseData();
		rd.setLifecycle(ReleaseLifecycle.ASSEMBLED);
		return rd;
	}

	/** REJECTED at T_CANCELLED, revived to ASSEMBLED before T_LIVE. */
	private static ReleaseData revived() {
		ReleaseData rd = new ReleaseData();
		rd.setLifecycle(ReleaseLifecycle.ASSEMBLED);
		rd.setUpdateEvents(List.of(
				new ReleaseUpdateEvent(ReleaseUpdateScope.LIFECYCLE, ReleaseUpdateAction.CHANGED,
						"PENDING", "REJECTED", null, T_CANCELLED.minusDays(1), null),
				new ReleaseUpdateEvent(ReleaseUpdateScope.LIFECYCLE, ReleaseUpdateAction.CHANGED,
						"REJECTED", "ASSEMBLED", null, T_CANCELLED.plusDays(1), null)));
		return rd;
	}

	/** Still PENDING at T_CANCELLED, settled to ASSEMBLED before T_LIVE -- the CI-rebuild shape. */
	private static ReleaseData settledFromPending() {
		ReleaseData rd = new ReleaseData();
		rd.setLifecycle(ReleaseLifecycle.ASSEMBLED);
		rd.setUpdateEvents(List.of(
				new ReleaseUpdateEvent(ReleaseUpdateScope.LIFECYCLE, ReleaseUpdateAction.CHANGED,
						"PENDING", "ASSEMBLED", null, T_CANCELLED.plusDays(1), null)));
		return rd;
	}

	private static V3WriteResult wrote(Map<Integer, RevisionWrite> byRevision) {
		return new V3WriteResult(Map.of(), byRevision);
	}

	private static V3Production produced(Map<Integer, RevisionProduction> byRevision) {
		return new V3Production(List.of(), byRevision, 0, 0);
	}

	@Test
	void nothingOfferedWasPresentAndTheEmitWouldHaveKeptSome_readsAsNeverRan() {
		Map<RepairCause, Integer> causes = svc.classifyRepairedRevisions(healthy(),
				wrote(Map.of(7, new RevisionWrite(3, 3))),
				produced(Map.of(7, new RevisionProduction(T_LIVE, 3, false, 3, 0, 0))));
		assertEquals(Map.of(RepairCause.EMIT_NEVER_RAN, 1), causes);
	}

	@Test
	void someRowsAlreadyPresent_readsAsDisagreedNotAsALostWrite() {
		// offered 3, landed 1 -> two rows were already there, so the emit demonstrably ran.
		Map<RepairCause, Integer> causes = svc.classifyRepairedRevisions(healthy(),
				wrote(Map.of(7, new RevisionWrite(3, 1))),
				produced(Map.of(7, new RevisionProduction(T_LIVE, 3, false, 3, 0, 0))));
		assertEquals(Map.of(RepairCause.EMIT_DISAGREED, 1), causes,
				"partially-present rows prove the emit ran; calling this a lost write would send an "
				+ "operator hunting a pod restart that never happened");
	}

	@Test
	void emitRuleWouldHaveKeptNothing_readsAsDisagreedEvenThoughNothingWasPresent() {
		// The store being empty is CORRECT here -- the emit would have written nothing. The rows the sweep
		// adds come from its own, different rule.
		Map<RepairCause, Integer> causes = svc.classifyRepairedRevisions(healthy(),
				wrote(Map.of(7, new RevisionWrite(2, 2))),
				produced(Map.of(7, new RevisionProduction(T_LIVE, 0, true, 2, 0, 0))));
		assertEquals(Map.of(RepairCause.EMIT_DISAGREED, 1), causes);
	}

	/**
	 * The trap in adding PENDING to the emit skip.
	 *
	 * <p>The rule lives in {@link ReleaseLifecycle#isFindingChangeEmitSuppressed} and has four
	 * consumers. If the live emitter suppresses PENDING but this classification does not, every
	 * revision the emitter correctly skipped is reported to the operator as EMIT_NEVER_RAN -- a lost
	 * write that never happened, on the same nightly ERROR alert this work exists to silence. We
	 * would have traded one alert for another.
	 *
	 * <p>The codebase has already been bitten by exactly this: one site omitted the lifecycle arm and
	 * a benign skip printed as a disagreement. Hence one predicate, and this test.
	 */
	@Test
	void pendingAtThatTransition_isNOTExcused_becausePendingStillEmits() {
		Map<RepairCause, Integer> causes = svc.classifyRepairedRevisions(settledFromPending(),
				wrote(Map.of(7, new RevisionWrite(4, 4))),
				produced(Map.of(7, new RevisionProduction(T_CANCELLED, 4, false, 4, 0, 0))));

		assertEquals(Map.of(RepairCause.EMIT_NEVER_RAN, 1), causes,
				"PENDING is NOT in the suppression set, so a revision written while PENDING that has no "
				+ "events is a genuine lost write and must be reported as one. An earlier revision of "
				+ "this change suppressed PENDING and this test asserted the opposite; that was reverted "
				+ "because PENDING is where a new release's FIRST scan lands, so suppressing there "
				+ "silently dropped real first-APPEARED events and hid them behind a benign label");
	}

	@Test
	void theEmitSuppressionSetIsExactly_cancelledAndRejected() {
		assertEquals(true, ReleaseLifecycle.isFindingChangeEmitSuppressed(ReleaseLifecycle.CANCELLED));
		assertEquals(true, ReleaseLifecycle.isFindingChangeEmitSuppressed(ReleaseLifecycle.REJECTED));
		assertEquals(false, ReleaseLifecycle.isFindingChangeEmitSuppressed(ReleaseLifecycle.PENDING),
				"PENDING keeps emitting. It is where a brand-new release's FIRST scan lands -- CI "
				+ "creates the release PENDING and Dependency-Track answers minutes later, still "
				+ "PENDING -- so suppressing here drops the genuine first-APPEARED for the whole "
				+ "finding set, and nothing re-emits when the release settles");
		assertEquals(false, ReleaseLifecycle.isFindingChangeEmitSuppressed(ReleaseLifecycle.DRAFT),
				"DRAFT keeps emitting: it is a deliberate user state that can hold real scanned BOMs "
				+ "indefinitely. The set is NOT the same as isScannableLifecycle, which additionally "
				+ "excludes DRAFT and PENDING");
		assertEquals(false, ReleaseLifecycle.isFindingChangeEmitSuppressed(ReleaseLifecycle.ASSEMBLED));
		assertEquals(false, ReleaseLifecycle.isFindingChangeEmitSuppressed(
				ReleaseLifecycle.GENERAL_AVAILABILITY));
	}

	@Test
	void cancelledAtThatTransition_readsAsBenignSkip() {
		Map<RepairCause, Integer> causes = svc.classifyRepairedRevisions(revived(),
				wrote(Map.of(7, new RevisionWrite(4, 4))),
				produced(Map.of(7, new RevisionProduction(T_CANCELLED, 4, false, 4, 0, 0))));
		assertEquals(Map.of(RepairCause.EMIT_SKIPPED_LIFECYCLE, 1), causes,
				"the release was REJECTED then, so the emit's early return was correct -- and the CURRENT "
				+ "lifecycle is ASSEMBLED, so reading that instead would misreport this as a lost emit");
	}

	@Test
	void oneBenignRevisionMustNotExcuseALostEmitBesideIt() {
		// The defect that per-release classification had: revision 7 is benign, revision 9 is a real loss.
		Map<RepairCause, Integer> causes = svc.classifyRepairedRevisions(revived(),
				wrote(Map.of(7, new RevisionWrite(4, 4), 9, new RevisionWrite(2, 2))),
				produced(Map.of(7, new RevisionProduction(T_CANCELLED, 4, false, 4, 0, 0),
						9, new RevisionProduction(T_LIVE, 2, false, 2, 0, 0))));
		assertEquals(Map.of(RepairCause.EMIT_SKIPPED_LIFECYCLE, 1, RepairCause.EMIT_NEVER_RAN, 1), causes,
				"each revision is its own emit, so they must be classified independently");
	}

	@Test
	void revisionsWithNothingRepairedAreNotCounted() {
		assertEquals(Map.of(), svc.classifyRepairedRevisions(healthy(),
				wrote(Map.of(7, new RevisionWrite(3, 0))),
				produced(Map.of(7, new RevisionProduction(T_LIVE, 3, false, 3, 0, 0)))));
	}

	/**
	 * The operator-facing diagnostic must describe EXACTLY the revisions the classifier called DISAGREED.
	 *
	 * <p>This is the case that caught the first cut of that method: it re-derived the verdict from the same
	 * inputs and omitted the lifecycle arm, so revision 7 -- a benign REJECTED-at-that-transition skip -- was
	 * counted as SKIPPED_LIFECYCLE and simultaneously PRINTED as a disagreement. Because the sample is
	 * bounded, such lines can also crowd out the real ones, which is the exact failure the separate sample
	 * exists to prevent. Both revisions here are partially-present writes, so the write SHAPE alone cannot
	 * tell them apart -- only the lifecycle check can.
	 */
	@Test
	void theDisagreedSampleMustNotDescribeALifecycleSkip() {
		ReleaseData rd = revived();
		V3WriteResult landed = wrote(Map.of(7, new RevisionWrite(3, 1), 9, new RevisionWrite(3, 1)));
		V3Production production = produced(Map.of(
				7, new RevisionProduction(T_CANCELLED, 3, false, 3, 0, 0),
				9, new RevisionProduction(T_LIVE, 3, false, 3, 0, 0)));

		assertEquals(Map.of(RepairCause.EMIT_SKIPPED_LIFECYCLE, 1, RepairCause.EMIT_DISAGREED, 1),
				svc.classifyRepairedRevisions(rd, landed, production),
				"precondition: one benign lifecycle skip beside one real disagreement");

		List<String> described = svc.describeDisagreements(ORG, RELEASE, ATTR, landed, production,
				svc.causeByRevision(rd, landed, production), 5);
		assertEquals(1, described.size(),
				"only the DISAGREED revision may be described; describing the lifecycle skip sends the "
				+ "operator after a divergence that never happened");
		assertTrue(described.get(0).contains("@rev9"),
				"the described revision must be the disagreed one (9), not the lifecycle skip (7); was: "
				+ described.get(0));
	}

	@Test
	void theDisagreedSampleStopsAtTheBudget() {
		ReleaseData rd = healthy();
		V3WriteResult landed = wrote(Map.of(7, new RevisionWrite(3, 1), 9, new RevisionWrite(3, 1)));
		V3Production production = produced(Map.of(
				7, new RevisionProduction(T_LIVE, 3, false, 3, 0, 0),
				9, new RevisionProduction(T_LIVE, 3, false, 3, 0, 0)));
		assertEquals(1, svc.describeDisagreements(ORG, RELEASE, ATTR, landed, production,
						svc.causeByRevision(rd, landed, production), 1).size(),
				"the run's sample is bounded, so formatting beyond the remaining budget is wasted work "
				+ "done inside every repaired release's transaction");
		assertEquals(List.of(), svc.describeDisagreements(ORG, RELEASE, ATTR, landed, production,
				svc.causeByRevision(rd, landed, production), 0));
	}
}
