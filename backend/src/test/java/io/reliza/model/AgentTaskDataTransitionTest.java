/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentTaskData.AnswerConfirmation;
import io.reliza.model.AgentTaskData.AnswerConfirmationState;
import io.reliza.model.AgentTaskData.SplitRequest;
import io.reliza.model.AgentTaskData.SplitRequestState;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;

/** RD3-16: a status row that changes nothing is refused, so no caller can write one. */
public class AgentTaskDataTransitionTest {

	@Test
	public void aSameStateTransitionIsRefusedAndWritesNothing() {
		AgentTaskData td = new AgentTaskData();
		td.setStatus(TaskStatus.ASSIGNED);
		td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.SIGNOFF, AgentActor.system("test"));
		IllegalStateException e = assertThrows(IllegalStateException.class, () -> td.transitionStatus(
				TaskStatus.AWAITING_COORDINATOR, StatusTrigger.COMPLETE, AgentActor.system("test"), "a note"));
		assertTrue(e.getMessage().contains("AWAITING_COORDINATOR to itself (COMPLETE)"), e.getMessage());
		assertEquals(1, td.getStatusHistory().size());
		assertEquals(TaskStatus.AWAITING_COORDINATOR, td.getStatus());
	}

	/** The queue is the exception: routing a queued task on to another role is a hand-off, with its reason. */
	@Test
	public void aQueuedTaskRoutedOnKeepsItsRow() {
		AgentTaskData td = new AgentTaskData();
		td.setStatus(TaskStatus.QUEUED);
		td.transitionStatus(TaskStatus.QUEUED, StatusTrigger.AUTHORIZE, AgentActor.system("routing"),
				"review items decided; back to designer");
		assertEquals(1, td.getStatusHistory().size());
		assertEquals("review items decided; back to designer", td.getStatusHistory().get(0).note());
	}

	/**
	 * Task TPF-171 (D6 step 5, T-31): the verdict on an answer marked no change records a same-state row on both
	 * triggers, with its actor and note, and lapses nothing (a split request waiting beside it stays).
	 */
	@Test
	public void aVerdictOnAnAnswerKeepsItsSameStateRow() {
		AgentActor seat = AgentActor.ofSession(UUID.randomUUID());
		for (StatusTrigger trigger : List.of(StatusTrigger.ANSWER_CONFIRMED, StatusTrigger.ANSWER_REFUSED)) {
			AgentTaskData td = new AgentTaskData();
			td.setStatus(TaskStatus.AWAITING_COORDINATOR);
			td.setSplitRequest(new SplitRequest(ZonedDateTime.now(), "coder", null, null, "too big", List.of(),
					SplitRequestState.REQUESTED, null, null, null));
			td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, trigger, seat, "n");
			assertEquals(1, td.getStatusHistory().size(), trigger.name());
			StatusChange row = td.getStatusHistory().get(0);
			assertEquals(TaskStatus.AWAITING_COORDINATOR, row.from());
			assertEquals(TaskStatus.AWAITING_COORDINATOR, row.to());
			assertEquals(trigger, row.trigger());
			assertEquals(seat, row.actor());
			assertEquals("n", row.note());
			assertTrue(td.splitRequested(), "the recording row lapses nothing");
		}
		AgentTaskData other = new AgentTaskData();
		other.setStatus(TaskStatus.AWAITING_COORDINATOR);
		assertThrows(IllegalStateException.class, () -> other.transitionStatus(TaskStatus.AWAITING_COORDINATOR,
				StatusTrigger.RETURN, seat, "n"), "RETURN is still refused");
	}

	/**
	 * Task TPF-171 (D9, T-20c): a PENDING answer confirmation lapses on the way to QUEUED, naming the verb, and stays
	 * PENDING on the way to ON_HOLD and on a row that stays AWAITING_COORDINATOR.
	 */
	@Test
	public void aPendingAnswerLapsesOnlyWhenTheTaskLeavesTheCoordinator() {
		AgentActor seat = AgentActor.ofSession(UUID.randomUUID());
		AgentTaskData queued = pendingAt(TaskStatus.AWAITING_COORDINATOR);
		queued.setRole("coder");
		queued.transitionStatus(TaskStatus.QUEUED, StatusTrigger.AUTHORIZE, seat);
		AnswerConfirmation lapsed = queued.getAnswerConfirmations().get(0);
		assertEquals(AnswerConfirmationState.LAPSED, lapsed.state());
		assertEquals("routed to coder", lapsed.note());
		assertEquals(seat, lapsed.settledBy());
		assertNotNull(lapsed.settledAt());
		assertNull(queued.pendingAnswerConfirmation());

		AgentTaskData held = pendingAt(TaskStatus.AWAITING_COORDINATOR);
		held.transitionStatus(TaskStatus.ON_HOLD, StatusTrigger.HOLD, seat);
		assertEquals(AnswerConfirmationState.PENDING, held.getAnswerConfirmations().get(0).state());

		AgentTaskData back = pendingAt(TaskStatus.ON_HOLD);
		back.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.LIFT_HOLD, seat);
		assertEquals(AnswerConfirmationState.PENDING, back.getAnswerConfirmations().get(0).state());

		AgentTaskData recorded = pendingAt(TaskStatus.AWAITING_COORDINATOR);
		recorded.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.ANSWER_REFUSED, seat, "n");
		assertEquals(AnswerConfirmationState.PENDING, recorded.getAnswerConfirmations().get(0).state());
	}

	private static AgentTaskData pendingAt(TaskStatus status) {
		AgentTaskData td = new AgentTaskData();
		td.setStatus(status);
		td.addAnswerConfirmation(new AnswerConfirmation(ZonedDateTime.now(), "architect", null, null, null, null, null,
				List.of(), AnswerConfirmationState.PENDING, null, null, null));
		return td;
	}

	/** Architecture-2 §1: SPLIT records an event, so its same-state row stays; the list says why. */
	@Test
	public void aRecordingTriggerKeepsItsSameStateRow() {
		assertEquals(Set.of(StatusTrigger.SPLIT, StatusTrigger.ANSWER_CONFIRMED, StatusTrigger.ANSWER_REFUSED),
				AgentTaskData.RECORDING_TRIGGERS);
		AgentTaskData td = new AgentTaskData();
		td.setStatus(TaskStatus.AWAITING_COORDINATOR);
		td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.SPLIT, AgentActor.system("test"));
		assertEquals(1, td.getStatusHistory().size());
		assertThrows(IllegalStateException.class, () -> td.transitionStatus(TaskStatus.AWAITING_COORDINATOR,
				StatusTrigger.DELIVERY_ABANDONED, AgentActor.system("test")));
	}
}
