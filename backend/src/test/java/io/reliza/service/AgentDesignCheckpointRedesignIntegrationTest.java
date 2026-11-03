/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.service.AgentTaskService.WorkerAssignment;
import io.reliza.ws.App;

/**
 * When the coordinator's look stands and when it does not (task SPLIT-5, D5, D12, section 3.7): the ordinary review loop
 * after SENT_ON, a redesign that reaches the coder again by every routing path (the forward pass, a review item
 * answered with a new round, a coder's question answered with one, an advisory round), a reopen, and a person's
 * own act past a changed design. T-5, T-6, T-11, T-23, T-25, T-26, T-27 and T-29.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDesignCheckpointRedesignIntegrationTest extends AgentDesignCheckpointTestBase {

	/** T-5: after SENT_ON the coder-tester loop runs as before: no second record, no second ALERT. */
	@Test
	public void aSentOnCheckpointStandsThroughTheTestLoop() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "stands");
		sentOn(r, t);
		DesignCheckpoint settled = checkpoint(t);
		assertEquals("tester", code(r, t).getRole());
		AgentTaskData back = testRejects(r, t, DD, "T-1");
		assertEquals(TaskStatus.QUEUED, back.getStatus());
		assertEquals("coder", back.getRole(), "no checkpoint on a rejection about the coder's own output");
		assertEquals("tester", code(r, t).getRole());
		assertEquals(1, messages(r, BoardEventKind.INFO, "coder passed on task " + back.label() + "; back to tester to verify")
				.size(), "the review-item frame's INFO, posted because the tester is queued");
		AgentTaskData done = pass(r, t, r.tester());
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(settled, done.getDesignCheckpoint(), "no second record");
		assertEquals(1, checkpointAlerts(r, done).size());
		assertEquals(checkpointAlerts(r, done), boardAlerts(r), "no ALERT on the board but the first checkpoint's");
	}

	/**
	 * T-31 (tester run 1): on the review-item branch of popToAsker the "back to the filer" INFO is posted only when the
	 * filer is queued (3.3, "Events the callers post"): a budget hold there posts the hold, not the INFO.
	 */
	@Test
	public void theReviewItemAnswersInfoWaitsForTheFilerToBeQueued() throws RelizaException {
		Rig r = plain();
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("tester", null, null, null, null,
				null, null, null, null, null, null, null, null, CODER_HOP * 5, null, null, null), true, WU);
		AgentTaskData t = task(r, "filer over budget");
		sentOn(r, t);
		code(r, t);
		assertEquals("coder", testRejects(r, t, DD, "T-1").getRole());
		// The coder's round fits (the hops so far plus the coder's, 71000), the tester's next one does not.
		agentTaskService.setBudget(t.getUuid(), CODER_HOP * 9, PERSON, WU);
		AgentTaskData held = code(r, t);
		assertEquals(TaskStatus.ON_HOLD, held.getStatus(), held.getStatusHistory().toString());
		assertTrue(held.getHold().reason().contains("budget"), held.getHold().reason());
		assertTrue(messages(r, BoardEventKind.INFO, "back to tester to verify").isEmpty(),
				messages(r, BoardEventKind.INFO, "").toString());
	}

	/** T-6: on the reviewed board a new ARCHITECTURE round after SENT_ON goes round the reviewers and stops again. */
	@Test
	public void aNewArchitectureRoundAfterSentOnStopsAgainAfterTheReviews() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData t = task(r, "redesigned after sent on");
		sentOn(r, t);
		DesignCheckpoint first = checkpoint(t);
		code(r, t);
		assertEquals("architect", testRejects(r, t, ARCH, "T-1").getRole());
		ReleaseData round2 = redesign(r, t, ARCH);
		AgentTaskData toReview = reload(t);
		assertEquals(TaskStatus.QUEUED, toReview.getStatus());
		assertEquals("functional_reviewer", toReview.getRole(), "toBuilder: not flagged, no stop");
		assertEquals(1, checkpointAlerts(r, t).size());
		reviewsPass(r, t);

		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		DesignCheckpoint cp = after.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.PENDING, cp.state());
		assertTrue(cp.reachedAt().isAfter(first.reachedAt()), "a fresh record");
		assertEquals(round2.getUuid(), cp.design().get(0));
		List<String> alerts = checkpointAlerts(r, after);
		assertEquals(2, alerts.size(), alerts.toString());
		assertTrue(alerts.get(1).contains("ARCHITECTURE round 2 (READY_TO_SHIP)"), alerts.get(1));
	}

	@Test
	public void aNewSecondDesignRoundAloneDoesNotStopIt() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData t = task(r, "second document only");
		sentOn(r, t);
		DesignCheckpoint first = checkpoint(t);
		code(r, t);
		assertEquals("architect", testRejects(r, t, TM, "T-1").getRole());
		redesign(r, t, TM);
		reviewsPass(r, t);
		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.QUEUED, after.getStatus());
		assertEquals("coder", after.getRole(), "only an ARCHITECTURE round unsettles the checkpoint");
		assertEquals(first, after.getDesignCheckpoint());
		assertEquals(1, checkpointAlerts(r, after).size());
	}

	/** T-11: a reopen to a design role brings the checkpoint back; a reopen to the coder leaves it standing. */
	@Test
	public void aReopenToTheArchitectBringsTheCheckpointBack() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "reopened");
		sentOn(r, t);
		code(r, t);
		assertEquals(TaskStatus.COMPLETED, pass(r, t, r.tester()).getStatus());
		DesignCheckpoint settled = checkpoint(t);

		AgentTaskData reopened = agentTaskService.reopen(t.getUuid(), "architect", "the design missed a case", COORD, WU);
		assertEquals(TaskStatus.QUEUED, reopened.getStatus());
		assertEquals(settled, reopened.getDesignCheckpoint(), "a reopen to a design role writes nothing");
		redesign(r, t, ARCH);
		AgentTaskData again = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, again.getStatus());
		assertTrue(again.checkpointPending());
		assertEquals(2, checkpointAlerts(r, again).size());

		authorize(r, t, "coder", COORD);
		DesignCheckpoint second = checkpoint(t);
		code(r, t);
		assertEquals(TaskStatus.COMPLETED, pass(r, t, r.tester()).getStatus());
		AgentTaskData toCoder = agentTaskService.reopen(t.getUuid(), "coder", "the PR no longer merges", COORD, WU);
		assertEquals(TaskStatus.QUEUED, toCoder.getStatus());
		assertEquals("coder", toCoder.getRole());
		assertEquals(second, toCoder.getDesignCheckpoint(), "it stands: unchanged");
	}

	/**
	 * T-30 (tester run 1): the reopen branch of checkpointStands on its own (D5). The reopened architect publishes no
	 * new ARCHITECTURE round, only a new round of the second document, so only the Reopen row unsettles it.
	 */
	@Test
	public void aReopenToTheArchitectAloneBringsTheCheckpointBack() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "reopened, second document only");
		sentOn(r, t);
		code(r, t);
		assertEquals(TaskStatus.COMPLETED, pass(r, t, r.tester()).getStatus());
		DesignCheckpoint settled = checkpoint(t);
		agentTaskService.reopen(t.getUuid(), "architect", "the design missed a case", COORD, WU);
		redesign(r, t, TM);
		AgentTaskData again = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, again.getStatus(), "status " + again.getStatus() + " role "
				+ again.getRole());
		DesignCheckpoint cp = again.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.PENDING, cp.state());
		assertTrue(cp.reachedAt().isAfter(settled.settledAt()), "a fresh record");
		assertEquals(2, checkpointAlerts(r, again).size());
	}

	@Test
	public void aReopenToTheCoderOfATaskCompletedAtTheCheckpointSendsItOn() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "completed at the checkpoint");
		atCheckpoint(r, t);
		assertEquals(TaskStatus.COMPLETED, agentTaskService.complete(t.getUuid(), "design only", COORD, WU).getStatus());
		assertTrue(reload(t).checkpointPending(), "complete leaves the record as it is");

		AgentTaskData reopened = agentTaskService.reopen(t.getUuid(), "coder", "build it after all", COORD, WU);
		assertEquals(TaskStatus.QUEUED, reopened.getStatus());
		DesignCheckpoint cp = reopened.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, cp.state());
		assertEquals("reopened to coder", cp.note());
		assertEquals(COORD, cp.settledBy());
		assertEquals(1, sentOnInfos(r, reopened).size());
	}

	/** T-23: a functional review rejection loop about the design stops once, after the security review. */
	@Test
	public void aReviewRejectionLoopStopsOnce() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData t = task(r, "review loop");
		design(r, t, "fits one coder pass");
		assign(r, t, r.functional());
		ReleaseData items = index(r.functional(), reload(t), RearmSpecificationType.BOARD_REVIEW_ITEMS, "REJECTED", ARCH,
				"F-1", 1, "OPEN");
		AgentTaskData back = signOff(r.functional(), t, SignOffOutcome.REJECTED, "the store is wrong",
				List.of(items.getUuid()));
		assertEquals("architect", back.getRole());
		assertNull(back.getDesignCheckpoint());
		ReleaseData round2 = redesign(r, t, ARCH);
		assertNull(checkpoint(t));
		assertEquals("functional_reviewer", reload(t).getRole());
		pass(r, t, r.functional());
		assertNull(checkpoint(t));
		assertTrue(checkpointAlerts(r, t).isEmpty(), "no stop on any design-stage pass");
		pass(r, t, r.security());

		AgentTaskData after = reload(t);
		List<String> alerts = checkpointAlerts(r, after);
		assertEquals(1, alerts.size(), alerts.toString());
		assertTrue(alerts.get(0).contains("ARCHITECTURE round 2"), alerts.get(0));
		DesignCheckpoint cp = after.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.PENDING, cp.state());
		assertEquals(round2.getUuid(), cp.design().get(0));
		assertEquals("security_reviewer", cp.afterRole());
	}

	/** T-25: a coder's question answered with a new ARCHITECTURE round stops at the coordinator, on both boards. */
	@Test
	public void aCodersQuestionAnsweredWithANewRoundStops() throws RelizaException {
		for (boolean reviewers : new boolean[] {false, true}) {
			Rig r = reviewers ? reviewed() : plain();
			AgentTaskData t = task(r, "asked");
			sentOn(r, t);
			DesignCheckpoint first = checkpoint(t);
			ReleaseData asked = ask(r, t);
			AgentTaskData toArchitect = reload(t);
			assertEquals(TaskStatus.QUEUED, toArchitect.getStatus());
			assertEquals("architect", toArchitect.getRole());
			assertEquals(1, toArchitect.getQuestionStack().size(), "the frame pushed");
			assertEquals(asked.getUuid(), toArchitect.getQuestionStack().get(0).questionsRelease());
			ReleaseData round2 = redesign(r, t, ARCH);

			AgentTaskData after = reload(t);
			assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus(), "reviewers " + reviewers);
			List<String> alerts = checkpointAlerts(r, after);
			assertEquals(2, alerts.size(), alerts.toString());
			assertTrue(alerts.get(1).contains("ARCHITECTURE round 2"), alerts.get(1));
			DesignCheckpoint cp = after.getDesignCheckpoint();
			assertEquals(DesignCheckpointState.PENDING, cp.state());
			assertTrue(cp.reachedAt().isAfter(first.settledAt()));
			assertEquals(round2.getUuid(), cp.design().get(0));
			assertEquals("architect", cp.afterRole());
			assertTrue(after.getQuestionStack().isEmpty());
			assertTrue(messages(r, BoardEventKind.INFO, "back to coder").isEmpty());
			assertEquals(BoardReviewItemStatus.RESOLVED, latestQuestions(after).get("q1"), "closeQuestions answered it");

			AgentTaskData queued = authorize(r, t, "coder", COORD);
			assertEquals(TaskStatus.QUEUED, queued.getStatus());
			assertEquals(DesignCheckpointState.SENT_ON, queued.getDesignCheckpoint().state());
		}
	}

	@Test
	public void aCodersQuestionAnsweredWithNoChangeGoesStraightBack() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "asked, no change");
		sentOn(r, t);
		DesignCheckpoint first = checkpoint(t);
		ask(r, t);
		assign(r, t, r.architect());
		agentTaskService.signOff(t.getUuid(), r.architect().sessionUuid(), SignOffOutcome.PASSED, "as designed",
				List.of(), null, Boolean.TRUE, WU);
		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.QUEUED, after.getStatus());
		assertEquals("coder", after.getRole());
		assertEquals(first, after.getDesignCheckpoint());
		assertEquals(1, checkpointAlerts(r, after).size());
	}

	/** T-26: on architect -> coder a tester's ARCHITECTURE rejection answered with a new round stops before the coder. */
	@Test
	public void aReviewItemAnsweredWithANewRoundStopsBeforeTheCoderWithoutReviewers() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "no design reviewers");
		sentOn(r, t);
		ReleaseData round1 = release(checkpoint(t).design().get(0));
		code(r, t);
		assertEquals("architect", testRejects(r, t, ARCH, "T-1").getRole());
		ReleaseData round2 = redesign(r, t, ARCH);

		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus(), "toBuilder would have queued the coder");
		assertEquals(2, checkpointAlerts(r, after).size());
		assertTrue(checkpointAlerts(r, after).get(1).contains("ARCHITECTURE round 2"));
		assertEquals(round2.getUuid(), after.getDesignCheckpoint().design().get(0));
		assertTrue(after.checkpointPending());
		assertTrue(messages(r, BoardEventKind.INFO, "passed with a new round; to coder").isEmpty());
		assertEquals(BoardReviewItemStatus.OPEN, latestIndex(after, RearmSpecificationType.BOARD_TEST_REPORT).get("T-1"),
				"the tester's items stay open");

		authorize(r, t, "coder", COORD);
		WorkerAssignment hop = agentTaskService.assign(t.getUuid(), board(r), r.coder().agentUuid(),
				r.coder().sessionUuid(), WU);
		List<UUID> archInputs = hop.resolvedInputs().stream().filter(ri -> ARCH == ri.specification())
				.map(ri -> ri.release()).toList();
		assertEquals(round2.getUuid(), archInputs.get(0),
				"the coder reads round 2 through its own ARCHITECTURE requirement: " + hop.resolvedInputs());
		assertTrue(!archInputs.contains(round1.getUuid()), "not round 1: " + hop.resolvedInputs());
		ReleaseData dd = publish(r.coder(), t, DD);
		AgentTaskData coded = signOff(r.coder(), t, SignOffOutcome.PASSED, "built", List.of(dd.getUuid()));
		assertEquals("tester", coded.getRole(), "the tester re-checks");
	}

	/** T-27: an advisory round lets the queued hop run and stops the task at the next routing queue of the coder. */
	@Test
	public void anAdvisoryRoundStopsAtTheNextRoutingQueueOfTheCoder() throws RelizaException {
		for (boolean whileAssigned : new boolean[] {false, true}) {
			Rig r = plain();
			AgentTaskData t = task(r, "advisory");
			sentOn(r, t);
			DesignCheckpoint first = checkpoint(t);
			if (whileAssigned) assign(r, t, r.coder());
			ReleaseData advisory = publish(r.architect(), t, ARCH, true);
			AgentTaskData unmoved = reload(t);
			assertEquals(whileAssigned ? TaskStatus.ASSIGNED : TaskStatus.QUEUED, unmoved.getStatus());
			assertEquals(first, unmoved.getDesignCheckpoint(), "nothing routes on a publish");
			assertEquals(1, checkpointAlerts(r, unmoved).size());

			if (!whileAssigned) assign(r, t, r.coder());
			ReleaseData dd = publish(r.coder(), t, DD);
			assertEquals("tester", signOff(r.coder(), t, SignOffOutcome.PASSED, "built", List.of(dd.getUuid())).getRole(),
					"the coder is not being queued: no stop");
			testRejects(r, t, DD, "T-1");

			AgentTaskData after = reload(t);
			assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus(), "assigned " + whileAssigned);
			List<String> alerts = checkpointAlerts(r, after);
			assertEquals(2, alerts.size(), alerts.toString());
			assertTrue(alerts.get(1).contains("ARCHITECTURE round 2 (ASSEMBLED)"), alerts.get(1));
			assertTrue(!alerts.get(1).contains("architect: \""), "an advisory round has no architect note: " + alerts.get(1));
			assertEquals(advisory.getUuid(), after.getDesignCheckpoint().design().get(0));
			assertTrue(after.checkpointPending());
			assertEquals(1, after.getQuestionStack().size(), "the tester's frame stays");
			assertEquals(1, after.getCycles().values().stream().mapToInt(Integer::intValue).sum(), "the cycle counted");

			authorize(r, t, "coder", COORD);
			AgentTaskData back = code(r, t);
			assertEquals("tester", back.getRole(), "back to the tester");
			assertTrue(back.getQuestionStack().isEmpty(), "through popToAsker: the tester's frame popped");
		}
	}

	/** T-29: a person's lift past a changed design settles afresh; so does the seat's authorize after a return. */
	@Test
	public void aLiftPastAChangedDesignSettlesAfresh() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "lifted past a redesign");
		sentOn(r, t);
		DesignCheckpoint first = checkpoint(t);
		ReleaseData advisory = publish(r.architect(), t, ARCH, true);
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "wait", COORD, WU);
		AgentTaskData lifted = agentTaskService.liftHold(t.getUuid(), HoldLevel.OPERATOR, PERSON, "go on", "coder", WU);

		assertEquals(TaskStatus.QUEUED, lifted.getStatus());
		assertEquals("coder", lifted.getRole());
		assertEquals(1, checkpointAlerts(r, lifted).size(), "no checkpoint ALERT");
		DesignCheckpoint cp = lifted.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, cp.state());
		assertEquals(PERSON, cp.settledBy());
		assertEquals(advisory.getUuid(), cp.design().get(0));
		assertTrue(cp.reachedAt().isAfter(first.settledAt()));
		assertTrue(cp.settledAt().isAfter(first.settledAt()));
		assertTrue(cp.note().contains("lifted"), cp.note());
		assertEquals(2, sentOnInfos(r, lifted).size(), sentOnInfos(r, lifted).toString());

		AgentTaskData other = task(r, "returned past a redesign");
		sentOn(r, other);
		DesignCheckpoint before = checkpoint(other);
		assign(r, other, r.coder());
		ReleaseData later = publish(r.architect(), other, ARCH, true);
		agentTaskService.returnTask(other.getUuid(), r.coder().sessionUuid(), TaskReturnReason.OTHER, "the design moved", WU);
		AgentTaskData queued = authorize(r, other, "coder", COORD);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		DesignCheckpoint fresh = queued.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, fresh.state());
		assertEquals(COORD, fresh.settledBy());
		assertEquals(later.getUuid(), fresh.design().get(0));
		assertTrue(fresh.settledAt().isAfter(before.settledAt()));
		assertTrue(fresh.reachedAt().isAfter(before.settledAt()), "a fresh record");
		assertEquals(1, checkpointAlerts(r, queued).size(), "no checkpoint ALERT");
		assertEquals(2, sentOnInfos(r, queued).size(), "one more sent-on INFO");
	}

	/** The coder, holding the task, asks about the ARCHITECTURE and signs off REJECTED with the question. */
	private ReleaseData ask(Rig r, AgentTaskData t) throws RelizaException {
		assign(r, t, r.coder());
		ReleaseData q = index(r.coder(), reload(t), RearmSpecificationType.BOARD_QUESTIONS, "REJECTED", ARCH, "q1", 1,
				"OPEN");
		signOff(r.coder(), t, SignOffOutcome.REJECTED, "which store?", List.of(q.getUuid()));
		return q;
	}

	private Map<String, BoardReviewItemStatus> latestQuestions(AgentTaskData t) {
		return latestIndex(t, RearmSpecificationType.BOARD_QUESTIONS);
	}

	private Map<String, BoardReviewItemStatus> latestIndex(AgentTaskData t, RearmSpecificationType spec) {
		ReleaseData rd = agentDocumentService.latestRoundRelease(reload(t), spec).orElseThrow();
		Map<String, BoardReviewItemStatus> out = new LinkedHashMap<>();
		rd.getDocument().reviewItems().reviewItems().forEach(i -> out.put(i.id(), i.status()));
		return out;
	}
}
