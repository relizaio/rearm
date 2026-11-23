/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData.BoardEventKind;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.DesignCheckpoint;
import io.reliza.model.AgentTaskData.DesignCheckpointState;
import io.reliza.model.AgentTaskData.DesignCheckpointWaiver;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.HumanGate;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.ws.App;

/**
 * The architect's waiver of the design checkpoint (task TPF-152, W1-W7, section 4.3): a waived design reaches the coder
 * with an INFO and a SENT_ON record, the reviews still run first, the waiver is per design round (a new round without
 * the flag stops, a bounce round with it keeps moving, an advisory round stops), a reopen to the architect clears it,
 * the board switch off ignores it with the ALERT, and a gate on the architect consumes it at the verdict. T-30, T-31,
 * T-33 to T-36, T-40 and T-41.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDesignCheckpointWaiverIntegrationTest extends AgentDesignCheckpointTestBase {

	static final String NOTE = "fits one coder pass";

	/** T-30: on T-1's board the architect's --no-split pass queues the coder with the INFO and a SENT_ON record. */
	@Test
	public void aWaivedDesignQueuesTheCoder() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "waived");
		List<ReleaseData> design = designNoSplit(r, t, NOTE);

		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.QUEUED, after.getStatus(), after.getStatusHistory().toString());
		assertEquals("coder", after.getRole());
		assertTrue(checkpointAlerts(r, after).isEmpty(), "no checkpoint ALERT");
		assertEquals(List.of(waiverInfo(after, "ARCHITECTURE round 1", "ASSEMBLED", NOTE)), waivedInfos(r, after));

		DesignCheckpoint cp = after.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, cp.state());
		assertEquals(AgentActor.ofSession(r.architect().sessionUuid()), cp.settledBy(), "in the architect's name");
		assertEquals("waived by architect: " + NOTE, cp.note());
		assertEquals("architect", cp.afterRole());
		assertEquals("coder", cp.beforeRole());
		assertEquals(List.of(design.get(0).getUuid(), design.get(1).getUuid()), cp.design());
		assertNotNull(cp.settledAt());

		DesignCheckpointWaiver w = after.getDesignCheckpointWaiver();
		assertEquals(r.architect().sessionUuid(), w.bySession());
		assertEquals(r.architect().agentUuid(), w.byAgent());
		assertEquals("architect", w.role());
		assertNotNull(w.roleUuid());
		assertNotNull(w.at());
		assertEquals(design.get(0).getUuid(), w.designRound());
		assertEquals(NOTE, w.note());
		assertEquals(Boolean.TRUE, after.getSignOffs().get(after.getSignOffs().size() - 1).noSplit());

		List<StatusChange> history = after.getStatusHistory();
		StatusChange signOff = history.get(history.size() - 2);
		StatusChange routed = history.get(history.size() - 1);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, signOff.to());
		assertEquals(StatusTrigger.SIGNOFF, signOff.trigger());
		assertEquals(TaskStatus.QUEUED, routed.to());
		assertEquals(StatusTrigger.AUTHORIZE, routed.trigger());
		assertEquals(AgentActor.system("routing"), routed.actor());
	}

	/** T-31: on T-2's board the reviews run first; the waiver is consumed when the coder is queued. */
	@Test
	public void theReviewsStillRun() throws RelizaException {
		Rig r = reviewed();
		AgentTaskData t = task(r, "waived, reviewed");
		designNoSplit(r, t, NOTE);
		AgentTaskData atReview = reload(t);
		assertEquals(TaskStatus.QUEUED, atReview.getStatus());
		assertEquals("functional_reviewer", atReview.getRole(), "the waiver does not skip the reviews");
		assertTrue(waivedInfos(r, atReview).isEmpty(), "nothing consumed yet");
		assertNull(atReview.getDesignCheckpoint());

		reviewsPass(r, t);
		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.QUEUED, after.getStatus());
		assertEquals("coder", after.getRole());
		assertEquals(List.of(waiverInfo(after, "ARCHITECTURE round 1", "READY_TO_SHIP", NOTE)), waivedInfos(r, after));
		assertTrue(checkpointAlerts(r, after).isEmpty());
		assertEquals("security_reviewer", after.getDesignCheckpoint().afterRole());
		assertEquals(DesignCheckpointState.SENT_ON, after.getDesignCheckpoint().state());
	}

	/** T-33: a new ARCHITECTURE round without the flag clears the waiver and stops the task as today. */
	@Test
	public void aNewRoundWithoutTheFlagStops() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "redesigned without the flag");
		designNoSplit(r, t, NOTE);
		codersQuestion(r, t);
		ReleaseData round2 = redesign(r, t, ARCH);

		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		List<String> alerts = checkpointAlerts(r, after);
		assertEquals(1, alerts.size(), alerts.toString());
		assertTrue(alerts.get(0).contains("ARCHITECTURE round 2"), alerts.get(0));
		assertFalse(alerts.get(0).contains("ignored"), alerts.get(0));
		assertEquals(DesignCheckpointState.PENDING, after.getDesignCheckpoint().state());
		assertEquals(round2.getUuid(), after.getDesignCheckpoint().design().get(0));
		assertNull(after.getDesignCheckpointWaiver(), "cleared by the round without the flag");
		assertEquals(BoardReviewItemStatus.RESOLVED, latestItems(after, RearmSpecificationType.BOARD_QUESTIONS).get("q1"));

		AgentTaskData queued = authorize(r, t, "coder", COORD);
		assertEquals(TaskStatus.QUEUED, queued.getStatus());
		assertEquals(DesignCheckpointState.SENT_ON, queued.getDesignCheckpoint().state());
		assertEquals(COORD, queued.getDesignCheckpoint().settledBy());
		assertEquals(1, sentOnInfos(r, queued).size());
		assertEquals(1, waivedInfos(r, queued).size(), "only round 1's");
	}

	/** T-34: a bounce round with the flag keeps the task moving with a second INFO and a fresh record. */
	@Test
	public void aBounceRoundWithTheFlagKeepsItMoving() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "bounced with the flag");
		designNoSplit(r, t, NOTE);
		DesignCheckpoint first = checkpoint(t);
		codersQuestion(r, t);
		ReleaseData round2 = redesignNoSplit(r, t, "round 2 fits");

		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.QUEUED, after.getStatus());
		assertEquals("coder", after.getRole());
		List<String> infos = waivedInfos(r, after);
		assertEquals(2, infos.size(), infos.toString());
		assertEquals(waiverInfo(after, "ARCHITECTURE round 2", "ASSEMBLED", "round 2 fits"), infos.get(1));
		DesignCheckpoint cp = after.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, cp.state());
		assertTrue(cp.reachedAt().isAfter(first.settledAt()), "a fresh record");
		assertEquals(round2.getUuid(), cp.design().get(0));
		assertEquals(round2.getUuid(), after.getDesignCheckpointWaiver().designRound());
		assertTrue(checkpointAlerts(r, after).isEmpty());
	}

	/** T-34, the no-reviewer toBuilder variant (T-26): the tester's items stay OPEN. */
	@Test
	public void aReviewItemAnsweredWithAFlaggedRoundGoesToTheCoder() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "tester rejects the design, flagged answer");
		designNoSplit(r, t, NOTE);
		code(r, t);
		assertEquals("architect", testRejects(r, t, ARCH, "T-1").getRole());
		redesignNoSplit(r, t, "round 2 fits");

		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.QUEUED, after.getStatus());
		assertEquals("coder", after.getRole());
		assertEquals(2, waivedInfos(r, after).size(), waivedInfos(r, after).toString());
		assertEquals(1, messages(r, BoardEventKind.INFO, "passed with a new round; to coder").stream()
				.filter(m -> m.contains(after.label())).count(), "toBuilder's INFO too");
		assertEquals(BoardReviewItemStatus.OPEN, latestItems(after, RearmSpecificationType.BOARD_TEST_REPORT).get("T-1"));
		assertTrue(checkpointAlerts(r, after).isEmpty());
	}

	/** T-34: authorize --role architect from a stop (T-4), then a round with the flag: consumed, reachedAt kept. */
	@Test
	public void aFlaggedRoundAfterTheCoordinatorSentTheArchitectBackIsConsumed() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "stopped, then waived");
		DesignCheckpoint pending = atCheckpoint(r, t).getDesignCheckpoint();
		authorize(r, t, "architect", COORD);
		redesignNoSplit(r, t, "round 2 fits");

		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.QUEUED, after.getStatus());
		assertEquals("coder", after.getRole());
		assertEquals(1, waivedInfos(r, after).size());
		DesignCheckpoint cp = after.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.SENT_ON, cp.state());
		assertTrue(pending.reachedAt().isEqual(cp.reachedAt()), "reachedAt kept (F-7)");
		assertEquals(1, checkpointAlerts(r, after).size(), "only the first stop");
	}

	/** T-35: a reopen to the architect clears the waiver; a reopen to the coder leaves it and the record. */
	@Test
	public void aReopenToTheArchitectClearsIt() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "reopened after a waiver");
		designNoSplit(r, t, NOTE);
		code(r, t);
		assertEquals(TaskStatus.COMPLETED, pass(r, t, r.tester()).getStatus());
		AgentTaskData reopened = agentTaskService.reopen(t.getUuid(), "architect", "the design missed a case", COORD, WU);
		assertNull(reopened.getDesignCheckpointWaiver(), "cleared at the reopen");
		assertNull(waiver(t));
		redesign(r, t, ARCH);
		AgentTaskData again = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, again.getStatus());
		assertEquals(1, checkpointAlerts(r, again).size());

		AgentTaskData other = task(r, "reopened to the coder after a waiver");
		designNoSplit(r, other, NOTE);
		code(r, other);
		assertEquals(TaskStatus.COMPLETED, pass(r, other, r.tester()).getStatus());
		DesignCheckpointWaiver w = waiver(other);
		DesignCheckpoint cp = checkpoint(other);
		AgentTaskData toCoder = agentTaskService.reopen(other.getUuid(), "coder", "the PR no longer merges", COORD, WU);
		assertEquals(TaskStatus.QUEUED, toCoder.getStatus());
		assertEquals("coder", toCoder.getRole());
		assertEquals(w, toCoder.getDesignCheckpointWaiver(), "the waiver stays");
		assertEquals(cp, toCoder.getDesignCheckpoint(), "the record stands");
		assertEquals(1, waivedInfos(r, toCoder).size(), "no second waiver INFO");
		assertTrue(sentOnInfos(r, toCoder).isEmpty());
	}

	/** T-36: the switch off records the flag, stops the task and says so in the ALERT; switched on again, it works. */
	@Test
	public void theSwitchOffIgnoresTheFlagWithTheAlert() throws RelizaException {
		Rig r = plain();
		waiverSwitch(r, false);
		AgentTaskData t = task(r, "switch off");
		List<ReleaseData> design = designNoSplit(r, t, NOTE);

		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		String expected = alertFor(after, "ARCHITECTURE round 1 (ASSEMBLED), " + TM + " round 1 (ASSEMBLED)", NOTE)
				.replace("; read the design", "; --no-split by architect on ARCHITECTURE round 1 ignored:"
						+ " architectCheckpointWaiver is off on this board; read the design");
		assertEquals(List.of(expected), checkpointAlerts(r, after));
		assertTrue(waivedInfos(r, after).isEmpty(), "no INFO");
		assertEquals(DesignCheckpointState.PENDING, after.getDesignCheckpoint().state());
		assertNotNull(after.getDesignCheckpointWaiver(), "recorded all the same");
		assertEquals(design.get(0).getUuid(), after.getDesignCheckpointWaiver().designRound());

		AgentTaskData sent = authorize(r, t, "coder", COORD);
		assertEquals(DesignCheckpointState.SENT_ON, sent.getDesignCheckpoint().state());
		assertEquals(COORD, sent.getDesignCheckpoint().settledBy());
		assertEquals(1, sentOnInfos(r, sent).size());

		waiverSwitch(r, null);
		codersQuestion(r, t);
		redesignNoSplit(r, t, "round 2 fits");
		AgentTaskData moving = reload(t);
		assertEquals(TaskStatus.QUEUED, moving.getStatus());
		assertEquals("coder", moving.getRole());
		assertEquals(1, waivedInfos(r, moving).size(), "consumed once the switch is on again");
		assertEquals(1, checkpointAlerts(r, moving).size());
	}

	/** T-40: a gate on the architect: accepted, consumed at READY_TO_SHIP; rejected, the waiver is inert. */
	@Test
	public void aGateOnTheArchitect() throws RelizaException {
		Rig r = rig(false, true, HumanGate.ON_PASS);
		AgentTaskData accepted = task(r, "gated, accepted");
		List<ReleaseData> design = designNoSplit(r, accepted, NOTE);
		AgentTaskData held = reload(accepted);
		assertEquals(TaskStatus.ON_HOLD, held.getStatus());
		assertEquals(design.get(0).getUuid(), held.getDesignCheckpointWaiver().designRound());
		assertEquals(ReleaseLifecycle.DRAFT, release(design.get(0).getUuid()).getLifecycle(), "named while DRAFT");
		agentTaskService.humanReview(accepted.getUuid(), true, "good", PERSON, WU);
		AgentTaskData after = reload(accepted);
		assertEquals(TaskStatus.QUEUED, after.getStatus());
		assertEquals("coder", after.getRole());
		assertEquals(List.of(waiverInfo(after, "ARCHITECTURE round 1", "READY_TO_SHIP", NOTE)), waivedInfos(r, after));
		assertEquals(AgentActor.ofSession(r.architect().sessionUuid()), after.getDesignCheckpoint().settledBy());

		AgentTaskData rejected = task(r, "gated, rejected");
		List<ReleaseData> rejectedDesign = designNoSplit(r, rejected, NOTE);
		agentTaskService.humanReview(rejected.getUuid(), false, "not yet", PERSON, WU);
		AgentTaskData back = reload(rejected);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, back.getStatus());
		assertNull(back.getDesignCheckpoint());
		assertNotNull(back.getDesignCheckpointWaiver());
		assertEquals(ReleaseLifecycle.DRAFT, release(back.getDesignCheckpointWaiver().designRound()).getLifecycle());
		assertEquals(rejectedDesign.get(0).getUuid(), back.getDesignCheckpointWaiver().designRound());
		AgentTaskData queued = authorize(r, rejected, "coder", COORD);
		assertEquals(DesignCheckpointState.SENT_ON, queued.getDesignCheckpoint().state());
		assertEquals(COORD, queued.getDesignCheckpoint().settledBy(), "settled by the seat");
		assertTrue(waivedInfos(r, queued).isEmpty(), "no waiver INFO");
	}

	/** T-41: an advisory round after a waived pass stops the task at the next routing queue of the coder. */
	@Test
	public void anAdvisoryRoundAfterAWaivedPassStops() throws RelizaException {
		Rig r = plain();
		AgentTaskData t = task(r, "advisory after a waiver");
		designNoSplit(r, t, NOTE);
		ReleaseData advisory = publish(r.architect(), t, ARCH, true);
		assertEquals("tester", code(r, t).getRole());
		testRejects(r, t, DD, "T-1");

		AgentTaskData after = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		List<String> alerts = checkpointAlerts(r, after);
		assertEquals(1, alerts.size(), alerts.toString());
		assertTrue(alerts.get(0).contains("ARCHITECTURE round 2 (ASSEMBLED)"), alerts.get(0));
		assertFalse(alerts.get(0).contains("ignored"), alerts.get(0));
		assertEquals(advisory.getUuid(), after.getDesignCheckpoint().design().get(0));
		assertTrue(after.checkpointPending());
		assertEquals(1, waivedInfos(r, after).size(), "only round 1's");

		AgentTaskData queued = authorize(r, t, "coder", COORD);
		assertEquals(DesignCheckpointState.SENT_ON, queued.getDesignCheckpoint().state());
		assertEquals(COORD, queued.getDesignCheckpoint().settledBy());
	}
}
