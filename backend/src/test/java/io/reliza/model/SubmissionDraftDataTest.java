/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.EnumSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.ReleaseData.ReleaseUpdateScope;

/** FDAW-29 row 15: the draft label the release events and the key-lane filter share, and the draft kinds. */
class SubmissionDraftDataTest {

	private static ReleaseUpdateEvent event(ReleaseUpdateScope scope, String oldValue, String newValue) {
		return new ReleaseUpdateEvent(scope, ReleaseUpdateAction.CHANGED, oldValue, newValue, UUID.randomUUID(), "why",
				ZonedDateTime.now(), null);
	}

	@Test
	void aDraftEventCarriesTheLabelOfAKindAsItsOldOrNewValue() {
		for (PackageKind k : PackageKind.values()) {
			String label = SubmissionDraftData.eventLabel(k);
			assertEquals(k.name() + " draft", label);
			assertTrue(SubmissionDraftData.isDraftEvent(event(ReleaseUpdateScope.SUBMISSION, null, label)));
			assertTrue(SubmissionDraftData.isDraftEvent(event(ReleaseUpdateScope.SUBMISSION, label, null)));
		}
	}

	@Test
	void anyOtherEventIsNotADraftEvent() {
		assertFalse(SubmissionDraftData.isDraftEvent(event(ReleaseUpdateScope.SUBMISSION, null, "FDA_PREMARKET v1")));
		assertFalse(SubmissionDraftData.isDraftEvent(event(ReleaseUpdateScope.SUBMISSION, "FINAL v1", "CANCELLED")));
		assertFalse(SubmissionDraftData.isDraftEvent(event(ReleaseUpdateScope.SUBMISSION, null, null)));
		assertFalse(SubmissionDraftData.isDraftEvent(event(ReleaseUpdateScope.LIFECYCLE, null, "FDA_PREMARKET draft")));
		assertFalse(SubmissionDraftData.isDraftEvent(null));
	}

	@Test
	void theDraftLaneIsExactlyTheFourDraftKinds() {
		assertEquals(EnumSet.of(SubmissionActKind.START_DRAFT, SubmissionActKind.CONFIRM,
				SubmissionActKind.RETURN_TO_DRAFT, SubmissionActKind.DISCARD_DRAFT), SubmissionActKind.DRAFT_LANE);
	}
}
