/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.TaskAssignment;
import io.reliza.model.AgentTaskData.TaskReturn;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.service.AgentTaskService.WorkerAssignment;
import io.reliza.service.BoardAgentsService.AgentRow;
import io.reliza.service.BoardAgentsService.StateKind;

/**
 * Task TPF-66: parking a hop for the operator ends the hop. The parked task counts against nobody's WIP, a hold
 * parked before the change (the assignment still on it) lifts to QUEUED like one parked since, and the hop's end
 * is the server's to record: a session cannot send it as a return. The rig is {@link AgentSelfHoldTestBase}.
 */
public class AgentParkedHopEndsTest extends AgentSelfHoldTestBase {

	@Test
	public void aParkedTaskIsNotCountedAgainstTheParkersWipAndIsNotOffered() throws Exception {
		Board b = board(1);
		Caller holder = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		AgentTaskData parked = assignedTo(b, holder);
		AgentTaskData second = queued(b);
		assertTrue(hold(holder, parked, QUESTION, "OPERATOR").getErrors().isEmpty());

		// The WIP counts list ASSIGNED tasks only, so this held before TPF-66 too (architecture 3.4): the pin keeps
		// it so, and the parked task, ON_HOLD, is never offered.
		WorkerAssignment offer = agentTaskService.next(List.of(b.board()), holder.agent().getUuid(),
				holder.session().getUuid()).orElseThrow();
		assertEquals(second.getUuid(), offer.task().getUuid());
		assertEquals(TaskStatus.ON_HOLD, task(parked).getStatus());
	}

	@Test
	public void aHoldParkedBeforeTheChangeStillAssignedLiftsToQueuedAndEndsTheHop() throws Exception {
		Board b = board();
		Caller holder = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		AgentTaskData t = assignedTo(b, holder);
		assertTrue(hold(holder, t, QUESTION, "OPERATOR").getErrors().isEmpty());

		// the stored shape a park wrote before TPF-66: ON_HOLD, the hop still assigned, no return
		AgentTaskData td = task(t);
		UUID roleUuid = td.getReturns().get(0).roleUuid();
		td.setReturns(new ArrayList<>());
		td.setAssignment(new TaskAssignment(holder.session().getUuid(), holder.agent().getUuid(), "architect", roleUuid,
				ZonedDateTime.now().minusHours(1), "v"));
		agentTaskService.saveData(td, WU);
		assertTrue(agentTaskService.hasAssignmentsForSession(holder.session().getUuid()));
		assertTrue(task(t).holdParksAHop());
		AgentRow row = boardAgentsService.agents(b.board(), null, null, ZonedDateTime.now()).stream()
				.filter(r -> r.session().equals(holder.session().getUuid())).findFirst().orElseThrow();
		assertNotEquals(StateKind.WORKING, row.state().kind(), "a task ON_HOLD is not worked: " + row.state());

		AgentTaskData released = agentTaskService.liftHoldOrAnswer(t.getUuid(), HoldLevel.OPERATOR,
				AgentActor.ofUser(UUID.randomUUID(), "Pat Operator"), "per-org, rotated yearly", null, WU);
		assertEquals(TaskStatus.QUEUED, released.getStatus());
		assertNull(released.getAssignment());
		assertNull(released.getHold());
		assertEquals("architect", released.getRole());
		assertEquals("lifted by Pat Operator: per-org, rotated yearly", lastRow(released).note());
		assertEquals(1, released.getReturns().size(), released.getReturns().toString());
		TaskReturn ended = released.getReturns().get(0);
		assertEquals(TaskReturnReason.PARKED_FOR_OPERATOR, ended.reason());
		assertEquals(holder.session().getUuid(), ended.session());
		assertEquals(QUESTION, ended.description());
		assertNotNull(ended.usage());
		assertTrue(agentBoardService.recentEvents(b.board().getUuid()).stream()
				.anyMatch(ev -> AgentBoardData.BoardEventKind.INFO == ev.kind()
						&& ev.message().contains(released.label() + " answered by Pat Operator: per-org, rotated yearly")
						&& ev.message().contains("ended now")),
				"the INFO says the leftover hop was ended at the lift");
		assertFalse(agentTaskService.hasAssignmentsForSession(holder.session().getUuid()));
	}

	private static final String RETURN = "mutation($t: ID!, $s: ID!, $r: AgentTaskReturnReason!, $d: String) {"
			+ " agentTaskReturnProgrammatic(taskUuid: $t, sessionUuid: $s, reason: $r, description: $d) { status } }";

	@Test
	public void aSessionCannotSendTheParkAsAReturn() throws Exception {
		Board b = board();
		Caller holder = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		AgentTaskData t = assignedTo(b, holder);

		ExecutionResult forged = asKey(holder.key(), RETURN, Map.of("t", t.getUuid().toString(),
				"s", holder.session().getUuid().toString(), "r", "PARKED_FOR_OPERATOR", "d", QUESTION));
		assertTrue(forged.getErrors().toString().contains("PARKED_FOR_OPERATOR returns are system-generated only"),
				forged.getErrors().toString());
		AgentTaskData after = task(t);
		assertEquals(TaskStatus.ASSIGNED, after.getStatus());
		assertEquals(holder.session().getUuid(), after.getAssignment().session());
		assertTrue(after.getReturns().isEmpty());
	}
}
