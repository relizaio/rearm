package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;

/**
 * Pins the lifecycle reconstruction the repair sweep uses to decide whether the live emit was RIGHT to write
 * nothing.
 *
 * <p>The emit returns early for a CANCELLED/REJECTED release. So a release cancelled when it was scanned and
 * revived afterwards legitimately has no events -- and the sweep, seeing a healthy release with rows missing,
 * would report a lost emit for something the emit got right. Reading the CURRENT lifecycle is exactly the
 * mistake: it is not the one the emit saw. These cases fix the reconstruction that avoids it.
 */
class RepairSweepLifecycleAtTest {

	private static final ZonedDateTime T0 = ZonedDateTime.parse("2026-08-01T00:00:00Z");
	private static final ZonedDateTime T1 = ZonedDateTime.parse("2026-08-02T00:00:00Z");
	private static final ZonedDateTime T2 = ZonedDateTime.parse("2026-08-03T00:00:00Z");

	private static ReleaseUpdateEvent lifecycleChange(String from, String to, ZonedDateTime when) {
		return new ReleaseUpdateEvent(ReleaseUpdateScope.LIFECYCLE, ReleaseUpdateAction.CHANGED,
				from, to, null, when, null);
	}

	/** PENDING, REJECTED at T1, revived to ASSEMBLED at T2 -- the shape that misreports as a lost emit. */
	private static ReleaseData revivedRelease() {
		ReleaseData rd = new ReleaseData();
		// GENERAL_AVAILABILITY, deliberately different from every reconstructed answer below: a stub that
		// returned the CURRENT lifecycle must not be able to satisfy any of these cases.
		rd.setLifecycle(ReleaseLifecycle.GENERAL_AVAILABILITY);
		rd.setUpdateEvents(List.of(
				// Deliberately NOT in date order, and with an unrelated scope mixed in -- a real release
				// carries many non-LIFECYCLE events, and nothing guarantees ordering.
				lifecycleChange("REJECTED", "ASSEMBLED", T2),
				new ReleaseUpdateEvent(ReleaseUpdateScope.TAGS, ReleaseUpdateAction.CHANGED,
						"ASSEMBLED", "CANCELLED", null, T1.plusHours(1), null),
				lifecycleChange("PENDING", "REJECTED", T1)));
		return rd;
	}

	private final FindingChangeEventBackfillService svc = new FindingChangeEventBackfillService();

	@Test
	void beforeAnyTransition_readsTheStateItMovedAwayFrom() {
		assertEquals(ReleaseLifecycle.PENDING, svc.lifecycleAt(revivedRelease(), T0),
				"before the first recorded transition the release was whatever that transition moved away "
				+ "from -- not its current lifecycle");
	}

	@Test
	void whileRejected_reportsRejectedNotTheCurrentLifecycle() {
		assertEquals(ReleaseLifecycle.REJECTED, svc.lifecycleAt(revivedRelease(), T1.plusHours(6)),
				"this is the whole point: a scan here produced NO events on purpose, so the sweep must not "
				+ "report the missing rows as a lost emit");
	}

	@Test
	void afterRevival_reportsTheRevivedLifecycleNotTheCurrentOne() {
		assertEquals(ReleaseLifecycle.ASSEMBLED, svc.lifecycleAt(revivedRelease(), T2.plusHours(6)),
				"the release has since moved to GENERAL_AVAILABILITY, so returning the current lifecycle "
				+ "would be wrong here too");
	}

	@Test
	void ignoresNonLifecycleEvents() {
		// The TAGS event above carries lifecycle-looking values on purpose. Reading it would report
		// CANCELLED and wrongly excuse this release as a benign skip.
		assertEquals(ReleaseLifecycle.REJECTED, svc.lifecycleAt(revivedRelease(), T1.plusHours(6)),
				"only LIFECYCLE-scoped events describe the lifecycle; other scopes reuse oldValue/newValue "
				+ "for their own purposes");
	}

	@Test
	void noLifecycleHistory_fallsBackToCurrent_soClassificationStaysConservative() {
		ReleaseData rd = new ReleaseData();
		rd.setLifecycle(ReleaseLifecycle.ASSEMBLED);
		assertEquals(ReleaseLifecycle.ASSEMBLED, svc.lifecycleAt(rd, T0),
				"with no history we must NOT excuse the release -- it stays a candidate lost emit");
	}
}
