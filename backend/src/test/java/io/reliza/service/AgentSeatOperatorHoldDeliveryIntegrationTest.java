/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;

/**
 * Task RD4-17: a task the coordinator seat parked for an operator decision while it was DELIVERING. The sweep and
 * the PR upsert leave it alone, the delivery verbs are refused for every session, and a person decides either by
 * releasing the hold with the answer or by acting on the task, which releases it and records the action as the
 * answer. The seat's gate and the other three states are {@code io.reliza.ws.AgentSeatOperatorHoldTest}'s.
 * Fixtures in {@link AgentDeliveryTestBase}.
 */
public class AgentSeatOperatorHoldDeliveryIntegrationTest extends AgentDeliveryTestBase {

	private static final UUID SEAT = UUID.randomUUID();
	private static final String QUESTION = "CI is red on the PR. Options: re-run, reopen to the coder. Recommend: re-run.";
	private static final String AWAITING = "awaiting the operator: " + QUESTION;
	private static final String REFUSAL = AWAITING + "; the person lifts the hold or acts";

	private AgentTaskData parked(AgentTaskData t) throws RelizaException {
		AgentTaskData held = agentTaskService.parkForOperator(t.getUuid(), SEAT, QUESTION, WU);
		assertEquals(TaskStatus.ON_HOLD, held.getStatus());
		assertEquals(TaskStatus.DELIVERING, held.getHold().returnTo());
		assertTrue(held.seatParkedForOperator());
		return held;
	}

	/** The answer row, then what the person's action did: the two newest rows. */
	private static StatusChange answerRow(AgentTaskData td, int fromEnd) {
		return td.getStatusHistory().get(td.getStatusHistory().size() - fromEnd);
	}

	@Test
	public void aHeldDeliveryIsLeftAloneByTheSweepAndResumesAfterTheAnswer() throws RelizaException {
		Rig r = rig(false);
		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN);
		AgentTaskData d = passedWith(r, queuedCoder(r, null), url);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		parked(d);

		// the PR merges while a person decides: neither the upsert nor the sweep settles the task
		pr(r, url, PullRequestState.MERGED);
		assertEquals(TaskStatus.ON_HOLD, reload(d).getStatus(), "the upsert leaves a held task alone");
		agentDeliveryService.sweep(WU);
		assertEquals(TaskStatus.ON_HOLD, reload(d).getStatus(), "the sweep leaves a held task alone");
		assertEquals(false, agentDeliveryService.settleDeliveringOnce(d.getUuid(), WU));
		assertEquals(TaskStatus.ON_HOLD, reload(d).getStatus());

		AgentTaskData answered = agentTaskService.liftHoldOrAnswer(d.getUuid(), HoldLevel.OPERATOR, PERSON,
				"merged after a re-run", null, WU);
		assertEquals(TaskStatus.DELIVERING, answered.getStatus(), "DELIVERING stays DELIVERING");
		assertNull(answered.getHold());
		assertEquals("lifted by ops@acme.example: merged after a re-run", last(answered).note());
		assertEquals(StatusTrigger.LIFT_HOLD, last(answered).trigger());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, " answered by ops@acme.example: merged after a re-run"
				+ " (the question: " + QUESTION + "); it returned to DELIVERING").size());

		// the sweep resumes on its next tick and completes it on the merge it now sees
		agentDeliveryService.sweep(WU);
		AgentTaskData done = reload(d);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(StatusTrigger.DELIVERED, last(done).trigger());
	}

	@Test
	public void theDeliveryVerbsAreRefusedForSessionsWhileHeld() throws RelizaException {
		Rig r = rig(false);
		String url = newPrUrl();
		String replacement = newPrUrl();
		AgentTaskData d = passedWith(r, queuedCoder(r, null), url, replacement);
		parked(d);
		AgentActor seat = AgentActor.ofSession(SEAT);
		List<org.junit.jupiter.api.function.Executable> verbs = List.of(
				() -> agentTaskService.declareDelivery(d.getUuid(), url, SHA, AgentTaskData.DeliveryOutcome.DELIVERED,
						"merged", seat, WU),
				() -> agentTaskService.declareDelivery(d.getUuid(), url, null, AgentTaskData.DeliveryOutcome.ABANDONED,
						"will not merge", seat, WU),
				() -> agentTaskService.supersedePullRequest(d.getUuid(), url, replacement, "replaced", seat, WU),
				() -> agentTaskService.complete(d.getUuid(), "merged by hand", seat, WU),
				() -> agentTaskService.reopen(d.getUuid(), "coder", "conflicts", seat, WU),
				// the worker that passed it, a session too
				() -> agentTaskService.declareDelivery(d.getUuid(), url, SHA, AgentTaskData.DeliveryOutcome.DELIVERED,
						"merged", AgentActor.ofSession(r.session().getUuid()), WU));
		for (org.junit.jupiter.api.function.Executable verb : verbs) {
			RelizaException refused = assertThrows(RelizaException.class, verb);
			assertTrue(refused.getMessage().contains(REFUSAL), refused.getMessage());
		}
		AgentTaskData still = reload(d);
		assertEquals(TaskStatus.ON_HOLD, still.getStatus());
		assertTrue(still.getDeliveries().isEmpty(), "nothing was recorded");
		assertTrue(still.seatParkedForOperator());
	}

	@Test
	public void aPersonsDeclarationAnswersTheHoldAndCompletesTheTask() throws RelizaException {
		Rig r = rig(false);
		String elsewhere = newPrUrl();
		AgentTaskData d = passedWith(r, queuedCoder(r, null), elsewhere);
		parked(d);

		AgentTaskData done = agentTaskService.declareDelivery(d.getUuid(), elsewhere, SHA,
				AgentTaskData.DeliveryOutcome.DELIVERED, "merged by hand", PERSON, WU);
		assertEquals(TaskStatus.COMPLETED, done.getStatus(), "the answer, then the delivery it declares");
		StatusChange answer = answerRow(done, 2);
		assertEquals(TaskStatus.ON_HOLD, answer.from());
		assertEquals(TaskStatus.DELIVERING, answer.to());
		assertEquals(StatusTrigger.LIFT_HOLD, answer.trigger());
		assertEquals("declared by ops@acme.example: " + elsewhere + " delivered at 0123456: merged by hand",
				answer.note());
		assertEquals(PERSON, answer.actor());
		assertEquals(StatusTrigger.DELIVERED, last(done).trigger());
		assertNull(done.getHold());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, " answered: declared by ops@acme.example: "
				+ elsewhere + " delivered at 0123456: merged by hand (the question: " + QUESTION + "); the hold was"
				+ " lifted to DELIVERING and the action went on from there").size(),
				agentBoardService.recentEvents(board(r).getUuid()).toString());
	}

	@Test
	public void aPersonsCompleteOrReopenAnswersTheHold() throws RelizaException {
		Rig r = rig(false);
		String first = newPrUrl();
		AgentTaskData one = passedWith(r, queuedCoder(r, null), first);
		parked(one);
		AgentTaskData completed = agentTaskService.complete(one.getUuid(), "merged by hand", PERSON, WU);
		assertEquals(TaskStatus.COMPLETED, completed.getStatus());
		assertEquals("completed by ops@acme.example: merged by hand",
				answerRow(completed, 2).note());
		assertEquals(TaskStatus.DELIVERING, answerRow(completed, 2).to());

		String second = newPrUrl();
		AgentTaskData two = passedWith(r, queuedCoder(r, null), second);
		parked(two);
		AgentTaskData reopened = agentTaskService.reopen(two.getUuid(), "coder", "conflicts with #401", PERSON, WU);
		assertEquals(TaskStatus.QUEUED, reopened.getStatus());
		assertEquals("coder", reopened.getRole());
		assertEquals("reopened by ops@acme.example: to coder: conflicts with #401",
				answerRow(reopened, 2).note());
		assertEquals(StatusTrigger.REOPEN, last(reopened).trigger());
	}

	@Test
	public void aPersonsSupersedeAnswersAHoldTheSeatPlacedAfterAClosedPr() throws RelizaException {
		Rig r = rig(false);
		String old = newPrUrl();
		String replacement = newPrUrl();
		pr(r, old, PullRequestState.OPEN);
		AgentTaskData d = passedWith(r, queuedCoder(r, null), old);
		pr(r, old, PullRequestState.CLOSED);
		AgentTaskData blocked = reload(d);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, blocked.getStatus(), "a closed PR hands the task back");
		agentTaskService.linkPr(d.getUuid(), replacement, WU);
		AgentTaskData held = agentTaskService.parkForOperator(d.getUuid(), SEAT, QUESTION, WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, held.getHold().returnTo());

		AgentTaskData answered = agentTaskService.supersedePullRequest(d.getUuid(), old, replacement, "replaced", PERSON, WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, answered.getStatus(), "back where it was parked, the supersede recorded");
		assertEquals("declared superseded by ops@acme.example: " + old + ", replaced by " + replacement + ": replaced",
				last(answered).note());
		assertEquals(AgentTaskData.DeliveryOutcome.SUPERSEDED, answered.getDeliveries().get(0).outcome());
	}

	@Test
	public void aPersonsVerbOnAHopsOwnHoldDoesNotAnswerIt() throws RelizaException {
		// RD4-5's hold resumes its holder on the answer; a person's verb on it runs as before, and a session's is
		// refused like on the seat's.
		Rig r = rig(false);
		AgentTaskData t = queuedCoder(r, null);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		agentTaskService.parkHopForOperator(t.getUuid(), r.session().getUuid(), QUESTION, WU);
		assertTrue(reload(t).holdParksAHop());
		RelizaException bySeat = assertThrows(RelizaException.class,
				() -> agentTaskService.complete(t.getUuid(), "done", AgentActor.ofSession(SEAT), WU));
		assertTrue(bySeat.getMessage().contains(REFUSAL), bySeat.getMessage());
		assertEquals(TaskStatus.ON_HOLD, reload(t).getStatus());
	}
}
