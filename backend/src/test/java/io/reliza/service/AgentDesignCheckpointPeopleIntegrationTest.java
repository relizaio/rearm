/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData.BoardEventKind;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.DesignCheckpoint;
import io.reliza.model.AgentTaskData.DesignCheckpointState;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.BoardReviewItemIndex.About;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.service.AgentDocumentService.Answer;
import io.reliza.service.AgentDocumentService.BoardReviewItemDecision;
import io.reliza.service.AgentDocumentService.BoardReviewItemDecisionAction;
import io.reliza.ws.App;

/**
 * The paths that end at the coordinator, or that a person or the seat takes, at and after the checkpoint (task
 * SPLIT-5, section 3.3 "what never settles or stops it", D9, D12): holds and lifts, a person's review item decisions, an
 * escalated answer, a coder's return, and the budget. T-9, T-10, T-20, T-21, T-22, T-24 and T-28.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDesignCheckpointPeopleIntegrationTest extends AgentDesignCheckpointTestBase {

	/** T-9: a lift without a role stops again with the record kept; a lift naming the coder sends it on. */
	@Test
	public void aLiftAtTheCheckpointStopsAgainOrSendsItOn() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "held at the checkpoint");
		DesignCheckpoint first = atCheckpoint(r, t).getDesignCheckpoint();
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "reading", COORD, WU);
		AgentTaskData lifted = agentTaskService.liftHold(t.getUuid(), HoldLevel.COORDINATOR, COORD, WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, lifted.getStatus());
		assertEquals(first, lifted.getDesignCheckpoint(), "PENDING, as it was");
		assertEquals(2, checkpointAlerts(r, lifted).size(), "one more ALERT");

		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "reading again", COORD, WU);
		AgentTaskData sent = agentTaskService.liftHold(t.getUuid(), HoldLevel.COORDINATOR, COORD, null, "coder", WU);
		assertEquals(TaskStatus.QUEUED, sent.getStatus());
		assertEquals("coder", sent.getRole());
		DesignCheckpoint cp = sent.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, cp.state());
		assertEquals(COORD, cp.settledBy());
		assertEquals("hold lifted to coder", cp.note());
		assertEquals(1, sentOnInfos(r, sent).size());
	}

	/** T-10: a person's blocking item about the design goes to the architect; a non-blocking one stops it again. */
	@Test
	public void aPersonsDecisionsAtTheCheckpoint() throws RelizaException {
		Rig r = plain();
		agentBoardService.updateSettings(r.board().getUuid(), null, null, null, null, 2, null, WU);
		AgentTaskData t = task(r, "decided at the checkpoint");
		DesignCheckpoint first = atCheckpoint(r, t).getDesignCheckpoint();

		AgentTaskData p3 = decide(t, null, 3);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, p3.getStatus(), "routeCleared stops again");
		assertEquals(first, p3.getDesignCheckpoint(), "unchanged");
		assertEquals(2, checkpointAlerts(r, p3).size());

		AgentTaskData p1 = decide(t, RearmSpecificationType.ARCHITECTURE, 1);
		assertEquals(TaskStatus.QUEUED, p1.getStatus());
		assertEquals("architect", p1.getRole());
		assertEquals(first, p1.getDesignCheckpoint(), "a design role named by a person leaves it PENDING");
		assertTrue(sentOnInfos(r, p1).isEmpty());
	}

	/** T-20: a settled record is not re-held by a person's non-blocking item; the coder is queued again. */
	@Test
	public void aSettledRecordIsNotReheldByANonBlockingDecision() throws RelizaException {
		Rig r = plain();
		agentBoardService.updateSettings(r.board().getUuid(), null, null, null, null, 2, null, WU);
		AgentTaskData t = task(r, "settled, then a P3");
		sentOn(r, t);
		DesignCheckpoint settled = checkpoint(t);
		int infos = messages(r, BoardEventKind.INFO, "").size();

		AgentTaskData after = decide(t, null, 3);
		assertEquals(TaskStatus.QUEUED, after.getStatus());
		assertEquals("coder", after.getRole());
		assertEquals(settled, after.getDesignCheckpoint(), "settledAt and settledBy unchanged");
		assertEquals(1, checkpointAlerts(r, after).size(), "no checkpoint ALERT");
		assertEquals(checkpointAlerts(r, after), boardAlerts(r), "no other ALERT on the board either");
		List<String> newInfos = messages(r, BoardEventKind.INFO, "");
		assertEquals(infos + 1, newInfos.size(), newInfos.toString());
		assertTrue(newInfos.get(newInfos.size() - 1).contains("nothing blocks -- queued for coder"),
				newInfos.get(newInfos.size() - 1));
	}

	/** T-21: routeDecided's coordinator branch leaves a PENDING record alone; its coder branch settles it. */
	@Test
	public void aPersonsBlockingItemNobodyProducesLeavesThePendingRecordAlone() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "about nothing produced");
		DesignCheckpoint first = atCheckpoint(r, t).getDesignCheckpoint();
		AgentTaskData after = decide(t, RearmSpecificationType.TEST_PLAN, 1);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		DesignCheckpoint cp = after.getDesignCheckpoint();
		assertEquals(first, cp);
		assertNull(cp.settledBy());
		assertTrue(sentOnInfos(r, after).isEmpty());
		assertTrue(messages(r, BoardEventKind.ALERT, "which no active role on this board produces").stream()
				.anyMatch(m -> m.contains(after.label())), "routing's ALERT, not the checkpoint's");
		assertEquals(1, checkpointAlerts(r, after).size());
	}

	@Test
	public void aPersonsBlockingItemAboutTheCodersOutputSendsItOnInTheirName() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "about the coder's output");
		atCheckpoint(r, t);
		AgentTaskData after = decide(t, DD, 1);
		assertEquals(TaskStatus.QUEUED, after.getStatus());
		assertEquals("coder", after.getRole());
		DesignCheckpoint cp = after.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, cp.state());
		assertEquals(PERSON, cp.settledBy());
		assertEquals("review items decided; queued for coder", cp.note());
		assertEquals(1, sentOnInfos(r, after).size());
		assertEquals(1, checkpointAlerts(r, after).size(), "a person's choice is never stopped");
	}

	/** T-22: an escalated question of a design role, answered by a person, never settles a PENDING record. */
	@Test
	public void anAnswerToADesignRoleLeavesThePendingRecordAlone() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "the architect asks");
		DesignCheckpoint first = atCheckpoint(r, t).getDesignCheckpoint();
		architectAsksWhatNobodyProduces(r, t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, reload(t).getStatus(), "escalated: nobody produces it");

		AgentTaskData answered = agentTaskService.answer(t.getUuid(),
				List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "use postgres")), PERSON, false, WU);
		assertEquals(TaskStatus.QUEUED, answered.getStatus());
		assertEquals("architect", answered.getRole());
		assertEquals(first, answered.getDesignCheckpoint(), "PENDING, unchanged");
		assertTrue(sentOnInfos(r, answered).isEmpty());

		pass(r, t, r.architect());
		AgentTaskData again = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, again.getStatus());
		assertTrue(again.checkpointPending());
		assertTrue(first.reachedAt().isEqual(again.getDesignCheckpoint().reachedAt()));
		assertEquals(2, checkpointAlerts(r, again).size());
	}

	@Test
	public void anAnswerTheBudgetStopsLeavesTheRecordAlone() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "answered over budget");
		DesignCheckpoint first = atCheckpoint(r, t).getDesignCheckpoint();
		architectAsksWhatNobodyProduces(r, t);
		agentTaskService.setBudget(t.getUuid(), ARCHITECT_HOP / 2, PERSON, WU);
		AgentTaskData held = agentTaskService.answer(t.getUuid(),
				List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "use postgres")), PERSON, false, WU);
		assertEquals(TaskStatus.ON_HOLD, held.getStatus(), "the asker's next round does not fit");
		assertEquals(first, held.getDesignCheckpoint());
		assertTrue(sentOnInfos(r, held).isEmpty());
	}

	/** T-24: a coder return re-authorized on a standing checkpoint settles nothing and posts nothing. */
	@Test
	public void aCoderReturnReauthorizedSettlesNothing() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "returned by the coder");
		sentOn(r, t);
		assign(r, t, r.coder());
		DesignCheckpoint settled = checkpoint(t);
		agentTaskService.returnTask(t.getUuid(), r.coder().sessionUuid(), TaskReturnReason.OTHER, "not today", WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, reload(t).getStatus());
		assertEquals(settled, checkpoint(t), "a return writes nothing to the record");
		int infos = sentOnInfos(r, t).size();
		List<String> eventsAfterReturn = boardEvents(r);

		AgentTaskData queued = authorize(r, t, "coder", COORD);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals(settled, queued.getDesignCheckpoint(), "settledAt, settledBy and note identical");
		assertEquals(infos, sentOnInfos(r, queued).size(), "no sent-on INFO");
		assertEquals(eventsAfterReturn, boardEvents(r), "the authorize posts nothing: the only events on the board are the return's");
	}

	/** T-28: the budget runs before the guard: the task parks at the budget stop, then reaches the checkpoint. */
	@Test
	public void theBudgetRunsBeforeTheCheckpoint() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "budgeted");
		agentTaskService.setBudget(t.getUuid(), ARCHITECT_HOP * 2, PERSON, WU);
		design(r, t, "fits one coder pass");
		AgentTaskData held = reload(t);
		assertEquals(TaskStatus.ON_HOLD, held.getStatus(), held.getStatusHistory().toString());
		assertTrue(held.getHold().reason().contains("budget"), held.getHold().reason());
		assertNull(held.getDesignCheckpoint());
		assertTrue(checkpointAlerts(r, held).isEmpty());

		agentTaskService.setBudget(t.getUuid(), CODER_HOP * 10, PERSON, WU);
		AgentTaskData lifted = agentTaskService.liftHold(t.getUuid(), HoldLevel.OPERATOR, PERSON, WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, lifted.getStatus());
		assertTrue(lifted.checkpointPending());
		assertEquals(1, checkpointAlerts(r, lifted).size());
	}

	/** A person files one item at {@code priority}, about {@code about} or about nothing. */
	private AgentTaskData decide(AgentTaskData t, RearmSpecificationType about, int priority) throws RelizaException {
		return agentTaskService.decideReviewItems(t.getUuid(), RearmSpecificationType.BOARD_REVIEW_ITEMS,
				List.of(new BoardReviewItemDecision(BoardReviewItemDecisionAction.FILE, null, priority,
						"filed at P" + priority, null, null)),
				null == about ? null : new About(about, null), PERSON, WU);
	}

	/** From the checkpoint the seat sends the task back to the architect, which asks about what nobody produces. */
	private void architectAsksWhatNobodyProduces(Rig r, AgentTaskData t) throws RelizaException {
		authorize(r, t, "architect", COORD);
		assertTrue(reload(t).checkpointPending());
		assign(r, t, r.architect());
		ReleaseData q = index(r.architect(), reload(t), RearmSpecificationType.BOARD_QUESTIONS, "REJECTED",
				RearmSpecificationType.REQUIREMENTS, "q1", 1, "OPEN");
		signOff(r.architect(), t, SignOffOutcome.REJECTED, "which requirement?", List.of(q.getUuid()));
	}
}
