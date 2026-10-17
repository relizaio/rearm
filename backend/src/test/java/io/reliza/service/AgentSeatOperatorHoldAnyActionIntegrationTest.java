/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.service.AgentDocumentService.Answer;
import io.reliza.service.AgentDocumentService.PublishRequest;

/**
 * Task RD4-17, architecture round 2 §1: while the coordinator seat's operator hold stands, every action a person
 * takes on the task is the answer, through one path. The hold is lifted to the status it was parked from with
 * a row reading "action by person: note", an INFO names the question and that answer, and the action then runs as
 * on an unheld task, moving the task on when it moves it. A session's action on the task is refused, and a person's
 * action that is refused leaves the question open. The delivery verbs are
 * {@link AgentSeatOperatorHoldDeliveryIntegrationTest}'s; fixtures in {@link AgentDeliveryTestBase}.
 */
public class AgentSeatOperatorHoldAnyActionIntegrationTest extends AgentDeliveryTestBase {

	private static final UUID SEAT = UUID.randomUUID();
	private static final String QUESTION = "Nobody here writes the test plan. Options: answer it, cancel. Recommend: answer.";
	private static final String REFUSAL = "awaiting the operator: " + QUESTION + "; the person lifts the hold or acts";

	private AgentTaskData parked(AgentTaskData t, TaskStatus from) throws RelizaException {
		assertEquals(from, reload(t).getStatus());
		AgentTaskData held = agentTaskService.parkForOperator(t.getUuid(), SEAT, QUESTION, WU);
		assertEquals(TaskStatus.ON_HOLD, held.getStatus());
		assertEquals(from, held.getHold().returnTo());
		assertTrue(held.seatParkedForOperator());
		return held;
	}

	/** The newest LIFT_HOLD row: the answer. */
	private static StatusChange answerRow(AgentTaskData td) {
		List<StatusChange> rows = td.getStatusHistory();
		for (int i = rows.size() - 1; i >= 0; i--) {
			if (StatusTrigger.LIFT_HOLD == rows.get(i).trigger()) return rows.get(i);
		}
		throw new AssertionError("no release row on " + rows);
	}

	private void assertAnswered(Rig r, AgentTaskData td, TaskStatus back, String answer) {
		StatusChange row = answerRow(td);
		assertEquals(TaskStatus.ON_HOLD, row.from());
		assertEquals(back, row.to(), "lifted to where the seat parked it");
		assertEquals(answer, row.note());
		assertEquals(PERSON, row.actor());
		assertNull(td.getHold());
		assertFalse(td.seatParkedForOperator());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, " answered: " + answer + " (the question: "
				+ QUESTION + "); the hold was lifted to " + back + " and the action went on from there").size(),
				agentBoardService.recentEvents(board(r).getUuid()).toString());
	}

	/** The coder asks about a test plan no role on the board produces, so the task waits on the coordinator. */
	private AgentTaskData asking(Rig r) throws RelizaException {
		AgentTaskData t = queuedCoder(r, null);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		Map<String, Object> q1 = new LinkedHashMap<>();
		q1.put("id", "q1");
		q1.put("priority", 1);
		q1.put("status", "OPEN");
		q1.put("title", "Who writes the test plan?");
		Map<String, Object> about = new LinkedHashMap<>();
		about.put("specification", RearmSpecificationType.TEST_PLAN.name());
		about.put("release", null);
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", "BOARD_QUESTIONS");
		idx.put("verdict", "REJECTED");
		idx.put("reviewItems", new ArrayList<>(List.of(q1)));
		idx.put("about", about);
		ReleaseData round = agentDocumentService.publish(r.session(), new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_QUESTIONS, null, null, null, null, null, null, idx, null, null, null, null, null),
				WU);
		AgentTaskData asked = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.REJECTED,
				"who writes the test plan?", List.of(round.getUuid()), WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, asked.getStatus());
		assertFalse(asked.getQuestionStack().isEmpty());
		return asked;
	}

	@Test
	public void aPersonsAnswerToTheOpenQuestionsAnswersTheSeatAndRoutesAsTheAnswerDoes() throws RelizaException {
		// The tester's T-1 (run 1): the answer mutation released the seat's hold with a bare row and no answer.
		Rig r = rig(false);
		AgentTaskData t = asking(r);
		parked(t, TaskStatus.AWAITING_COORDINATOR);

		AgentTaskData answered = agentTaskService.answer(t.getUuid(),
				List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "no test plan for this one")), PERSON, true, WU);
		assertAnswered(r, answered, TaskStatus.AWAITING_COORDINATOR,
				"answered q1 by ops@acme.example: q1: no test plan for this one");
		assertEquals(TaskStatus.QUEUED, answered.getStatus(), "then the answer routes back to the asker");
		assertEquals("coder", answered.getRole());
		assertTrue(answered.getQuestionStack().isEmpty());
	}

	@Test
	public void aPersonsAnswerThatAsksToKeepTheHoldStillAnswersTheSeat() throws RelizaException {
		// Routing the answer moves the task on, so the flag cannot keep the seat's hold without bypassing the record.
		Rig r = rig(false);
		AgentTaskData t = asking(r);
		parked(t, TaskStatus.AWAITING_COORDINATOR);

		AgentTaskData answered = agentTaskService.answer(t.getUuid(),
				List.of(new Answer("q1", BoardReviewItemStatus.WITHDRAWN, "asked in error")), PERSON, false, WU);
		assertAnswered(r, answered, TaskStatus.AWAITING_COORDINATOR,
				"answered q1 by ops@acme.example: q1 WITHDRAWN: asked in error");
		assertEquals(TaskStatus.QUEUED, answered.getStatus());
	}

	@Test
	public void aPersonsCancelAnswersTheSeatAndCancels() throws RelizaException {
		Rig r = rig(false);
		AgentTaskData t = queuedCoder(r, null);
		parked(t, TaskStatus.QUEUED);

		AgentTaskData cancelled = agentTaskService.cancel(t.getUuid(), "a duplicate of RD4-3", PERSON, WU);
		assertAnswered(r, cancelled, TaskStatus.QUEUED, "cancelled by ops@acme.example: a duplicate of RD4-3");
		assertEquals(TaskStatus.CANCELLED, cancelled.getStatus());
		assertEquals(StatusTrigger.CANCEL, last(cancelled).trigger());
	}

	@Test
	public void aPersonsOrderChangeAnswersTheSeatAndTheTaskWaitsWhereItWas() throws RelizaException {
		Rig r = rig(false);
		AgentTaskData t = queuedCoder(r, null);
		parked(t, TaskStatus.QUEUED);

		AgentTaskData reordered = agentTaskService.setOrder(t.getUuid(), 3, PERSON, WU);
		assertAnswered(r, reordered, TaskStatus.QUEUED, "reordered by ops@acme.example: order 3");
		assertEquals(TaskStatus.QUEUED, reordered.getStatus());
		assertEquals(3, reordered.getOrderIndex());
		assertEquals(StatusTrigger.LIFT_HOLD, last(reordered).trigger(), "nothing else moved it");
	}

	@Test
	public void everyOtherActionOfAPersonAnswersTheSeatThroughTheSamePath() throws RelizaException {
		Rig r = rig(false);
		record Act(String answer, TaskStatus then, Executable on) {}
		List<AgentTaskData> tasks = new ArrayList<>();
		for (int i = 0; i < 7; i++) tasks.add(parked(queuedCoder(r, null), TaskStatus.QUEUED));
		List<Act> acts = List.of(
				// the rig's board has no ladder, so a level is cleared rather than set
				new Act("level set by ops@acme.example: cleared to the board's default", TaskStatus.QUEUED,
						() -> agentTaskService.setWorkLevel(tasks.get(0).getUuid(), null, PERSON, WU)),
				new Act("budget set by ops@acme.example: $5.00", TaskStatus.QUEUED,
						() -> agentTaskService.setBudget(tasks.get(1).getUuid(), 5_000_000L, PERSON, WU)),
				new Act("strength set by ops@acme.example: cleared to the role's", TaskStatus.QUEUED,
						() -> agentTaskService.setRequiredStrength(tasks.get(2).getUuid(), null, PERSON, WU)),
				new Act("retagged by ops@acme.example: area=boards", TaskStatus.QUEUED,
						() -> agentTaskService.setTags(tasks.get(3).getUuid(), List.of(new TagRecord("area", "boards")),
								PERSON, WU)),
				new Act("human review required by ops@acme.example: the next sign-off waits for a person", TaskStatus.QUEUED,
						() -> agentTaskService.setRequireHumanReview(tasks.get(4).getUuid(), true, true, PERSON, WU)),
				new Act("review items decided by ops@acme.example: BOARD_REVIEW_ITEMS: filed the banner hides the question", TaskStatus.AWAITING_COORDINATOR,
						() -> agentTaskService.decideReviewItems(tasks.get(5).getUuid(), RearmSpecificationType.BOARD_REVIEW_ITEMS,
								List.of(new AgentDocumentService.BoardReviewItemDecision(
										AgentDocumentService.BoardReviewItemDecisionAction.FILE, null, 1, "the banner hides the"
												+ " question", null, null)), null, PERSON, WU)),
				new Act("authorized by ops@acme.example: for coder", TaskStatus.QUEUED, () -> agentTaskService.authorize(
						tasks.get(6).getUuid(), board(r), "coder", null, null, null, null, null, PERSON, WU)));
		for (int i = 0; i < acts.size(); i++) {
			Act act = acts.get(i);
			if (i == acts.size() - 1) {
				// authorize runs from AWAITING_COORDINATOR or PENDING_INTAKE: parked from QUEUED, the release is
				// rolled back with the refusal and the question stays open
				RelizaException refused = assertThrows(RelizaException.class, act.on());
				assertFalse(refused.getMessage().contains("awaiting the operator"), refused.getMessage());
				assertTrue(reload(tasks.get(i)).seatParkedForOperator(), "a refused action answers nothing");
				continue;
			}
			try {
				act.on().execute();
			} catch (Throwable e) {
				throw new AssertionError(act.answer(), e);
			}
			AgentTaskData after = reload(tasks.get(i));
			assertAnswered(r, after, TaskStatus.QUEUED, act.answer());
			// then the action as on an unheld task: a review item filed with nothing to send it to goes to the coordinator
			assertEquals(act.then(), after.getStatus(), act.answer());
		}
		assertTrue(events(r, AgentBoardData.BoardEventKind.INFO, " answered: authorized by").isEmpty(),
				"no INFO for the refused action");
	}

	@Test
	public void aRefusedActionOfAPersonLeavesTheQuestionOpen() throws RelizaException {
		Rig r = rig(false);
		AgentTaskData t = queuedCoder(r, null);
		parked(t, TaskStatus.QUEUED);
		// a reopen needs a COMPLETED or DELIVERING task: released to QUEUED, the reopen is refused, and both roll back
		RelizaException refused = assertThrows(RelizaException.class,
				() -> agentTaskService.reopen(t.getUuid(), "coder", "redo it", PERSON, WU));
		assertTrue(refused.getMessage().contains("only a COMPLETED or DELIVERING task is reopened"), refused.getMessage());
		AgentTaskData still = reload(t);
		assertEquals(TaskStatus.ON_HOLD, still.getStatus());
		assertTrue(still.seatParkedForOperator());
		assertEquals(StatusTrigger.HOLD, last(still).trigger());
		assertTrue(events(r, AgentBoardData.BoardEventKind.INFO, " answered: reopened").isEmpty());
	}

	@Test
	public void aSessionsActionOnAHeldTaskIsStillRefused() throws RelizaException {
		Rig r = rig(false);
		AgentTaskData t = queuedCoder(r, null);
		parked(t, TaskStatus.QUEUED);
		AgentActor seat = AgentActor.ofSession(SEAT);
		List<Executable> verbs = List.of(
				() -> agentTaskService.cancel(t.getUuid(), "not needed", seat, WU),
				() -> agentTaskService.setOrder(t.getUuid(), 1, seat, WU),
				() -> agentTaskService.setWorkLevel(t.getUuid(), 1, seat, WU),
				() -> agentTaskService.setTags(t.getUuid(), List.of(new TagRecord("area", "x")), seat, WU),
				() -> agentTaskService.setRequireHumanReview(t.getUuid(), true, false, seat, WU),
				() -> agentTaskService.complete(t.getUuid(), "done", seat, WU),
				() -> agentTaskService.authorize(t.getUuid(), board(r), "coder", null, null, null, null, null, seat, WU));
		for (Executable verb : verbs) {
			RelizaException refused = assertThrows(RelizaException.class, verb);
			assertTrue(refused.getMessage().contains(REFUSAL), refused.getMessage());
		}
		AgentTaskData still = reload(t);
		assertEquals(TaskStatus.ON_HOLD, still.getStatus());
		assertTrue(still.seatParkedForOperator());
		assertTrue(still.getTags() == null || still.getTags().isEmpty(), "nothing was written");
	}
}
