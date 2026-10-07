/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

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

	/** Architecture-2 §1: SPLIT records an event, so its same-state row stays; the list says why. */
	@Test
	public void aRecordingTriggerKeepsItsSameStateRow() {
		assertEquals(java.util.Set.of(StatusTrigger.SPLIT), AgentTaskData.RECORDING_TRIGGERS);
		AgentTaskData td = new AgentTaskData();
		td.setStatus(TaskStatus.AWAITING_COORDINATOR);
		td.transitionStatus(TaskStatus.AWAITING_COORDINATOR, StatusTrigger.SPLIT, AgentActor.system("test"));
		assertEquals(1, td.getStatusHistory().size());
		assertThrows(IllegalStateException.class, () -> td.transitionStatus(TaskStatus.AWAITING_COORDINATOR,
				StatusTrigger.DELIVERY_ABANDONED, AgentActor.system("test")));
	}
}
