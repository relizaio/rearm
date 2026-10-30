/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import graphql.ExecutionResult;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HoldKind;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskReturn;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.NotificationEventType;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.dto.notifications.AgentBoardEventPayload;
import io.reliza.service.AgentBoardRenderSupport;
import io.reliza.service.AgentTaskService.WorkerAssignment;

/**
 * Task RD4-5: a session holds its own hop for an operator decision, and withdraws its own registration in
 * intake. Since TPF-66 the park ends the hop and the person's release queues the task for its role; what the
 * ended hop leaves behind is {@link AgentParkedHopEndsTest}'s. The rig is {@link AgentSelfHoldTestBase}.
 */
public class AgentSelfHoldAndWithdrawTest extends AgentSelfHoldTestBase {

	@Test
	@SuppressWarnings("unchecked")
	public void theHolderParksItsHopAtOperatorLevelOnlyAndThePeopleAreTold() throws Exception {
		Board b = board();
		Caller holder = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		Caller stranger = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		AgentTaskData t = assignedTo(b, holder);

		// a COORDINATOR hold from the holder -- the default level, and said outright -- is the seat's
		for (String level : new String[] {null, "COORDINATOR"}) {
			ExecutionResult coordinatorLevel = hold(holder, t, QUESTION, level);
			assertTrue(coordinatorLevel.getErrors().toString().contains("parks its hop at OPERATOR level only"),
					coordinatorLevel.getErrors().toString());
			assertEquals(TaskStatus.ASSIGNED, task(t).getStatus());
		}
		// another session, of a key that works the board too, holds nothing here
		ExecutionResult notTheHolder = hold(stranger, t, QUESTION, "OPERATOR");
		assertTrue(notTheHolder.getErrors().toString().contains("Only the session holding the task's current assignment"),
				notTheHolder.getErrors().toString());
		assertEquals(TaskStatus.ASSIGNED, task(t).getStatus());
		// the question is the reason, and it is required
		ExecutionResult noQuestion = hold(holder, t, " ", "OPERATOR");
		assertTrue(noQuestion.getErrors().toString().contains("needs the question"), noQuestion.getErrors().toString());

		ExecutionResult parked = hold(holder, t, QUESTION, "OPERATOR");
		assertTrue(parked.getErrors().isEmpty(), parked.getErrors().toString());
		Map<String, Object> out = (Map<String, Object>) ((Map<String, Object>) parked.getData()).get("agentTaskHoldProgrammatic");
		assertEquals("ON_HOLD", out.get("status"));
		Map<String, Object> h = (Map<String, Object>) out.get("hold");
		assertEquals("OPERATOR", h.get("level"));
		assertEquals("MANUAL", h.get("kind"));
		assertEquals("awaiting the operator: " + QUESTION, h.get("reason"));
		assertEquals(holder.session().getUuid().toString(), ((Map<String, Object>) h.get("heldBy")).get("uuid"));

		AgentTaskData held = task(t);
		assertTrue(held.holdParksAHop(), "still told from the seat's park, with no assignment to read");
		// the park ended the hop (TPF-66): nobody holds the task, and the holder's idle window is the plain one
		assertNull(held.getAssignment(), "the hop ended at the park");
		assertFalse(agentTaskService.hasAssignmentsForSession(holder.session().getUuid()));
		assertEquals(1, held.getReturns().size(), held.getReturns().toString());
		TaskReturn ended = held.getReturns().get(0);
		assertEquals(TaskReturnReason.PARKED_FOR_OPERATOR, ended.reason());
		assertEquals(holder.session().getUuid(), ended.session());
		assertEquals(holder.agent().getUuid(), ended.agent());
		assertEquals("architect", ended.role());
		assertEquals(QUESTION, ended.description());
		assertNotNull(ended.usage(), "the hop's usage is frozen at the park");
		assertTrue(ended.outputs().isEmpty());
		assertEquals(StatusTrigger.HOLD, lastRow(held).trigger());
		assertEquals(TaskStatus.ASSIGNED, lastRow(held).from());
		assertEquals("awaiting the operator: " + QUESTION, lastRow(held).note());
		// the board's writers are told, through the needs-a-person path
		List<AgentBoardEventPayload> person = outboxRepository.findRecentByOrg(b.org().getUuid(), 5000).stream()
				.filter(e -> NotificationEventType.AGENT_TASK_NEEDS_PERSON == e.getEventType())
				.map(AgentBoardRenderSupport::payload).filter(p -> held.getUuid().equals(p.task())).toList();
		assertEquals(1, person.size(), "the needs-a-person notification is written once");
		assertEquals(HoldKind.MANUAL.name(), person.get(0).holdKind());
		assertEquals("awaiting the operator: " + QUESTION, person.get(0).message());
		assertTrue(agentBoardService.recentEvents(b.board().getUuid()).stream()
				.anyMatch(ev -> ev.message().contains("parked by its architect hop, awaiting the operator: " + QUESTION)
						&& ev.message().contains("the hop ended")),
				"the feed says the hop is parked, on what, and that it ended");

		// the session that parked cannot sign off, and the refusal names what the task waits on and that the hop ended
		RelizaException signOff = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				holder.session().getUuid(), SignOffOutcome.PASSED, "done", WU));
		assertTrue(signOff.getMessage().contains("is parked, awaiting the operator: " + QUESTION), signOff.getMessage());
		assertTrue(signOff.getMessage().contains("your hop ended when you parked it"), signOff.getMessage());
		// nor park it twice: it holds no assignment any more
		ExecutionResult again = hold(holder, t, "another question", "OPERATOR");
		assertTrue(again.getErrors().toString().contains("Only the session holding"), again.getErrors().toString());
	}

	@Test
	public void aPersonsReleaseNeedsTheAnswerAndRecordsItAndQueuesTheTaskForItsRole() throws Exception {
		Board b = board();
		Caller holder = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		AgentTaskData t = assignedTo(b, holder);
		assertTrue(hold(holder, t, QUESTION, "OPERATOR").getErrors().isEmpty());
		AgentActor person = AgentActor.ofUser(UUID.randomUUID(), "Pat Operator");

		// the coordinator cannot lift it
		RelizaException bySeat = assertThrows(RelizaException.class, () -> agentTaskService.liftHold(t.getUuid(),
				HoldLevel.COORDINATOR, COORD, "go on", null, WU));
		assertTrue(bySeat.getMessage().contains("carries an OPERATOR hold"), bySeat.getMessage());
		// a person's release without words is refused: the words are the answer
		RelizaException noNote = assertThrows(RelizaException.class, () -> agentTaskService.liftHoldOrAnswer(
				t.getUuid(), HoldLevel.OPERATOR, person, "  ", null, WU));
		assertTrue(noNote.getMessage().contains("with your answer as the note"), noNote.getMessage());
		// and the task is queued again for its own role, so no role is routed to
		RelizaException withRole = assertThrows(RelizaException.class, () -> agentTaskService.liftHoldOrAnswer(
				t.getUuid(), HoldLevel.OPERATOR, person, "per-org", "architect", WU));
		assertTrue(withRole.getMessage().contains("queues the task for architect"), withRole.getMessage());
		assertTrue(withRole.getMessage().contains("lift it without a role"), withRole.getMessage());
		assertEquals(TaskStatus.ON_HOLD, task(t).getStatus());

		AgentTaskData released = agentTaskService.liftHoldOrAnswer(t.getUuid(), HoldLevel.OPERATOR, person,
				"per-org, rotated yearly", null, WU);
		assertEquals(TaskStatus.QUEUED, released.getStatus(), "queued again for its role (TPF-66)");
		assertNull(released.getHold());
		assertNull(released.getAssignment());
		assertEquals("architect", released.getRole());
		StatusChange row = lastRow(released);
		assertEquals(StatusTrigger.LIFT_HOLD, row.trigger());
		assertEquals("lifted by Pat Operator: per-org, rotated yearly", row.note());
		assertEquals(person, row.actor());
		assertTrue(agentBoardService.recentEvents(b.board().getUuid()).stream()
				.anyMatch(ev -> AgentBoardData.BoardEventKind.INFO == ev.kind()
						&& ev.message().contains(released.label() + " answered by Pat Operator: per-org, rotated yearly")
						&& ev.message().contains("(the question: " + QUESTION + ")")
						&& ev.message().contains("queued again for architect with the answer on its record")),
				"an INFO names the task, the answer and where the task went");
		// the question stays on the record beside the answer
		StatusChange asked = released.getStatusHistory().get(released.getStatusHistory().size() - 2);
		assertEquals(StatusTrigger.HOLD, asked.trigger());
		assertEquals("awaiting the operator: " + QUESTION, asked.note());

		// the ordinary offer delivers it: the only QUEUED task on the board, no preference for the parker (3.3)
		WorkerAssignment offer = agentTaskService.next(List.of(b.board()), holder.agent().getUuid(),
				holder.session().getUuid()).orElseThrow();
		assertEquals(t.getUuid(), offer.task().getUuid());
		List<StatusChange> history = offer.task().getStatusHistory();
		assertTrue(history.stream().anyMatch(c -> StatusTrigger.HOLD == c.trigger()
				&& ("awaiting the operator: " + QUESTION).equals(c.note())), "the offer carries the question");
		assertTrue(history.stream().anyMatch(c -> StatusTrigger.LIFT_HOLD == c.trigger()
				&& "lifted by Pat Operator: per-org, rotated yearly".equals(c.note())), "and the answer");

		// any session of the role takes it; the session that parked holds nothing on it any more
		Caller next = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		agentTaskService.assign(t.getUuid(), b.board(), next.agent().getUuid(), next.session().getUuid(), WU);
		AgentTaskData taken = task(t);
		assertEquals(TaskStatus.ASSIGNED, taken.getStatus());
		assertEquals(next.session().getUuid(), taken.getAssignment().session());
		RelizaException oldHolder = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				holder.session().getUuid(), SignOffOutcome.PASSED, "designed with the operator's answer", WU));
		assertTrue(oldHolder.getMessage().contains("assigned to a different session"), oldHolder.getMessage());
		assertEquals(1, task(t).getReturns().size(), "the park's return only");
	}

	@Test
	public void aParkedHopWhoseSessionClosesStaysParkedAndTheAnswerQueuesItsRole() throws Exception {
		Board b = board();
		Caller holder = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		AgentTaskData t = assignedTo(b, holder);
		assertTrue(hold(holder, t, QUESTION, "OPERATOR").getErrors().isEmpty());

		agentSessionService.close(holder.session().getUuid(), WU);
		AgentTaskData closed = task(t);
		assertEquals(TaskStatus.ON_HOLD, closed.getStatus(), "the question still waits on a person");
		assertNull(closed.getAssignment());
		assertTrue(closed.holdParksAHop());
		assertEquals(1, closed.getReturns().size(), "the park's return; the session held nothing to close (TPF-66)");

		AgentTaskData released = agentTaskService.liftHoldOrAnswer(t.getUuid(), HoldLevel.OPERATOR,
				AgentActor.ofUser(UUID.randomUUID(), "Pat Operator"), "per-board", null, WU);
		assertEquals(TaskStatus.QUEUED, released.getStatus());
		assertEquals("architect", released.getRole());
		assertEquals("lifted by Pat Operator: per-board", lastRow(released).note());
	}

	/** The seat's OPERATOR hold (task RD4-17) is {@code AgentSeatOperatorHoldTest}'s; this is its COORDINATOR hold. */
	@Test
	public void theSeatStillHoldsAtCoordinatorLevel() throws Exception {
		Board b = board();
		Caller seat = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		agentBoardService.claimCoordinatorSeat(b.board().getUuid(), seat.session().getUuid(), seat.agent().getUuid(), WU);
		AgentTaskData t = agentTaskService.register(b.board(), null, "seat hold " + UUID.randomUUID(), null, null, null,
				null, null, null, COORD, true, WU);

		ExecutionResult held = hold(seat, t, "waiting on the tracker", null);
		assertTrue(held.getErrors().isEmpty(), held.getErrors().toString());
		AgentTaskData after = task(t);
		assertEquals(TaskStatus.ON_HOLD, after.getStatus());
		assertEquals(HoldLevel.COORDINATOR, after.getHold().level());
		assertFalse(after.holdParksAHop());

		// a seat key without BOARD_WRITE holds nothing, as before the verb had a second caller
		Board other = board();
		Caller agentOnly = caller(other.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		agentBoardService.claimCoordinatorSeat(other.board().getUuid(), agentOnly.session().getUuid(),
				agentOnly.agent().getUuid(), WU);
		AgentTaskData t2 = agentTaskService.register(other.board(), null, "seat hold " + UUID.randomUUID(), null, null,
				null, null, null, null, COORD, true, WU);
		ExecutionResult noWrite = hold(agentOnly, t2, "waiting", null);
		assertTrue(noWrite.getErrors().toString().contains("BOARD_WRITE"), noWrite.getErrors().toString());
		assertEquals(TaskStatus.PENDING_INTAKE, task(t2).getStatus());
	}

	private static final String CANCEL = "mutation($t: ID!, $s: ID!, $n: String) {"
			+ " agentTaskCancelProgrammatic(taskUuid: $t, sessionUuid: $s, note: $n) { status statusHistory { trigger note } } }";

	private ExecutionResult cancel(Caller c, AgentTaskData t, String note) {
		Map<String, Object> vars = new java.util.HashMap<>(Map.of("t", t.getUuid().toString(),
				"s", c.session().getUuid().toString()));
		if (null != note) vars.put("n", note);
		return asKey(c.key(), CANCEL, vars);
	}

	@Test
	public void theRegistrantWithdrawsInIntakeOnlyAndNobodyElseDoes() throws Exception {
		Board b = board();
		Caller registrant = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		Caller other = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		AgentTaskData t = agentTaskService.register(b.board(), null, "duplicate " + UUID.randomUUID(), null,
				registrant.session().getUuid(), null, null, null, null, AgentActor.ofSession(registrant.session().getUuid()),
				true, WU);

		ExecutionResult byOther = cancel(other, t, "not mine to withdraw");
		assertTrue(byOther.getErrors().toString().contains("only its registrant withdraws it"), byOther.getErrors().toString());
		assertEquals(TaskStatus.PENDING_INTAKE, task(t).getStatus());
		ExecutionResult noReason = cancel(registrant, t, null);
		assertTrue(noReason.getErrors().toString().contains("A withdrawal needs a reason"), noReason.getErrors().toString());
		assertEquals(TaskStatus.PENDING_INTAKE, task(t).getStatus());

		ExecutionResult withdrawn = cancel(registrant, t, "duplicate of the task registered a minute earlier");
		assertTrue(withdrawn.getErrors().isEmpty(), withdrawn.getErrors().toString());
		AgentTaskData after = task(t);
		assertEquals(TaskStatus.CANCELLED, after.getStatus());
		StatusChange row = lastRow(after);
		assertEquals(StatusTrigger.CANCEL, row.trigger());
		assertEquals("withdrawn by its registrant: duplicate of the task registered a minute earlier", row.note());
		assertEquals(AgentActor.ofSession(registrant.session().getUuid()), row.actor());
		assertTrue(agentBoardService.recentEvents(b.board().getUuid()).stream()
				.anyMatch(ev -> ev.message().contains(after.label() + " withdrawn by its registrant: duplicate")),
				"the withdrawal is on the feed");
	}

	@Test
	public void aWithdrawalIsRefusedOnceTheCoordinatorAuthorisedTheTask() throws Exception {
		Board b = board();
		Caller registrant = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		AgentTaskData t = agentTaskService.register(b.board(), null, "authorised " + UUID.randomUUID(), null,
				registrant.session().getUuid(), null, null, null, null, AgentActor.ofSession(registrant.session().getUuid()),
				true, WU);
		agentTaskService.authorize(t.getUuid(), b.board(), "architect", 10, null, null, null, null, COORD, WU);

		ExecutionResult refused = cancel(registrant, t, "changed my mind");
		assertTrue(refused.getErrors().toString().contains("the coordinator owns this task now; return it or ask the seat"),
				refused.getErrors().toString());
		assertEquals(TaskStatus.QUEUED, task(t).getStatus());

		// the seat still cancels, whoever registered the task
		Caller seat = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		agentBoardService.claimCoordinatorSeat(b.board().getUuid(), seat.session().getUuid(), seat.agent().getUuid(), WU);
		ExecutionResult bySeat = cancel(seat, t, "not needed");
		assertTrue(bySeat.getErrors().isEmpty(), bySeat.getErrors().toString());
		assertEquals(TaskStatus.CANCELLED, task(t).getStatus());
		assertEquals("not needed", lastRow(task(t)).note());
	}
}
