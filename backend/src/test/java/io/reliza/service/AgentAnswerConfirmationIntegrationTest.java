/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.AnswerConfirmation;
import io.reliza.model.AgentTaskData.AnswerConfirmationState;
import io.reliza.model.AgentTaskData.AnswerVerdict;
import io.reliza.model.AgentTaskData.DesignCheckpoint;
import io.reliza.model.AgentTaskData.DesignCheckpointState;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.ReleaseData;
import io.reliza.ws.App;

/**
 * Task TPF-171, built by TPF-172 (D3 to D9): an answer signed off {@code --no-change} stops at the coordinator, and
 * the verdict routes by the forward walk with the confirmed rounds left out of the staleness reads. Rows T-4, T-5,
 * T-6, T-18, T-19, T-22 and T-30.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentAnswerConfirmationIntegrationTest extends AgentAnswerRoutingTestBase {

	/** An ordinary task whose coder asked about ARCHITECTURE and whose architect answered {@code --no-change}. */
	record Asked(AgentTaskData task, ReleaseData round1, ReleaseData questions, ReleaseData answer) {}

	Asked answeredNoChange(Rig r, String title) throws RelizaException {
		AgentTaskData t = ordinaryAtCoder(r, title);
		ReleaseData round1 = archRounds(t).get(0);
		ReleaseData q = coderAsks(r, t, ARCH);
		ReleaseData answer = answer(r, t, Boolean.TRUE, false, ARCH).get(0);
		return new Asked(reload(t), round1, q, answer);
	}

	/** The row records the verdict on the two releases, with the confirmer as actor. */
	static void assertVerdictRow(StatusChange row, StatusTrigger trigger, String words, ReleaseData answer,
			ReleaseData base) {
		assertEquals(TaskStatus.AWAITING_COORDINATOR, row.from(), row.toString());
		assertEquals(TaskStatus.AWAITING_COORDINATOR, row.to(), row.toString());
		assertEquals(trigger, row.trigger());
		assertEquals(CONFIRMER, row.actor());
		assertTrue(row.note().contains(words), row.note());
		assertTrue(row.note().contains(answer.getUuid().toString()), row.note());
		assertTrue(row.note().contains(base.getUuid().toString()), row.note());
	}

	/** T-4, task case 4: NO CHANGE confirmed; the reviewers' passes stand and the coder is queued in one step. */
	@Test
	public void aConfirmedAnswerGoesBackToTheCoderAndThePassesStand() throws RelizaException {
		Rig r = reviewedTpf();
		Asked a = answeredNoChange(r, "T-4 confirmed");
		AgentTaskData t = a.task();

		assertEquals(TaskStatus.AWAITING_COORDINATOR, t.getStatus());
		assertTrue(t.getQuestionStack().isEmpty(), "the frame is popped");
		AnswerConfirmation pending = entry(t);
		assertEquals(1, t.getAnswerConfirmations().size());
		assertEquals(AnswerConfirmationState.PENDING, pending.state());
		assertEquals("architect", pending.role());
		assertEquals(roleConfig(r, "coder").getUuid(), pending.askingRole());
		assertEquals(a.questions().getUuid(), pending.questionsRelease());
		assertEquals(1, pending.rounds().size());
		AgentTaskData.AnswerRound round = pending.rounds().get(0);
		assertEquals(a.answer().getUuid(), round.release());
		assertEquals(ARCH, round.specification());
		assertEquals(a.answer().getVersion(), round.version());
		assertEquals(a.answer().getDocument().path(), round.path());
		assertEquals(a.round1().getUuid(), round.previous());
		assertEquals(a.round1().getVersion(), round.previousVersion());
		assertEquals(a.round1().getDocument().path(), round.previousPath());
		List<String> alerts = confirmationAlerts(r, t);
		assertEquals(1, alerts.size(), alerts.toString());
		assertTrue(alerts.get(0).contains(a.answer().getUuid().toString()), alerts.get(0));
		assertTrue(alerts.get(0).contains(a.round1().getUuid().toString()), alerts.get(0));

		AgentTaskData confirmed = confirm(r, t, AnswerVerdict.CONFIRMED);

		assertQueuedFor(confirmed, "coder");
		AnswerConfirmation settled = entry(t);
		assertEquals(AnswerConfirmationState.CONFIRMED, settled.state());
		assertEquals(CONFIRMER, settled.settledBy());
		assertNotNull(settled.settledAt());
		assertVerdictRow(row(t, 1), StatusTrigger.ANSWER_CONFIRMED, "answer confirmed no change by", a.answer(),
				a.round1());
		assertEquals(TaskStatus.QUEUED, row(t, 0).to());
		assertEquals("architect passed; next coder", row(t, 0).note());
		assertTrue(hasCurrentPass(r, t, "functional_reviewer"));
		assertTrue(hasCurrentPass(r, t, "security_reviewer"));

		assertQueuedFor(code(r, t), "tester");
	}

	/** T-5: T-4 on a design-from-parent child; the base is the carried round and the carries stand. */
	@Test
	public void aConfirmedAnswerOnACarriedChildKeepsTheCarries() throws RelizaException {
		Rig r = reviewedTpf();
		Carried c = coderHolds(r, "T-5 carried, confirmed");
		AgentTaskData t = c.flagged();
		coderAsks(r, t, ARCH);
		answer(r, t, Boolean.TRUE, false, ARCH);

		assertEquals(c.design().get(0).getUuid(), entry(t).rounds().get(0).previous(), "the carried ARCHITECTURE round");
		assertTrue(confirmationAlerts(r, t).get(0).contains("against the round carried from " + c.parent().keyOrUuid()),
				confirmationAlerts(r, t).toString());
		assertQueuedFor(confirm(r, t, AnswerVerdict.CONFIRMED), "coder");
		assertTrue(carryLive(r, t, "functional_reviewer"));
		assertTrue(carryLive(r, t, "security_reviewer"));
		assertTrue(checkpointStands(r, t));
		assertQueuedFor(code(r, t), "tester");
	}

	/** T-6, task case 5: NO CHANGE refused; reviewed as a change, then the existing checkpoint rule before the coder. */
	@Test
	public void aRefusedAnswerIsReviewedAsAChange() throws RelizaException {
		Rig r = reviewedTpf();
		Asked a = answeredNoChange(r, "T-6 refused");
		AgentTaskData t = a.task();

		AgentTaskData refused = confirm(r, t, AnswerVerdict.REFUSED, "it moves the store", CONFIRMER,
				new AgentTaskService.Confirmer(UUID.randomUUID(), null));

		AnswerConfirmation settled = entry(t);
		assertEquals(AnswerConfirmationState.REFUSED, settled.state());
		assertEquals("it moves the store", settled.note());
		assertVerdictRow(row(t, 1), StatusTrigger.ANSWER_REFUSED, "no change refused by", a.answer(), a.round1());
		assertFalse(row(t, 1).note().contains("it moves the store"), "the coordinator's words stay on the record");
		assertQueuedFor(refused, "functional_reviewer");
		assertFalse(hasCurrentPass(r, t, "functional_reviewer"));
		assertFalse(hasCurrentPass(r, t, "security_reviewer"));

		assertQueuedFor(pass(r, t, r.functional()), "security_reviewer");
		AgentTaskData stopped = pass(r, t, r.security());
		assertEquals(TaskStatus.AWAITING_COORDINATOR, stopped.getStatus(), stopped.getStatusHistory().toString());
		assertEquals(DesignCheckpointState.PENDING, stopped.getDesignCheckpoint().state());
	}

	/** T-18a (D9): authorize instead of a verdict lapses the entry; the round counts as a change. */
	@Test
	public void authorizingInsteadOfConfirmingLapsesTheAnswer() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = answeredNoChange(r, "T-18a authorized").task();

		assertQueuedFor(authorize(r, t, "coder", COORD), "coder");
		AnswerConfirmation lapsed = entry(t);
		assertEquals(AnswerConfirmationState.LAPSED, lapsed.state());
		assertTrue(lapsed.note().contains("routed to coder"), lapsed.note());
		assertFalse(hasCurrentPass(r, t, "functional_reviewer"));
		assertFalse(hasCurrentPass(r, t, "security_reviewer"));
		assertQueuedFor(code(r, t), "functional_reviewer");
	}

	/** T-18b (D9): a hold placed and lifted while the answer waits replays the stop without a second record. */
	@Test
	public void aHoldLiftedWhileTheAnswerWaitsReplaysTheStop() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = answeredNoChange(r, "T-18b held").task();
		String reason = confirmationAlerts(r, t).get(0);

		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "a look first", COORD, WU);
		assertEquals(AnswerConfirmationState.PENDING, entry(t).state(), "a hold lapses nothing");
		AgentTaskData lifted = agentTaskService.liftHold(t.getUuid(), HoldLevel.COORDINATOR, COORD, WU);

		assertEquals(TaskStatus.AWAITING_COORDINATOR, lifted.getStatus(), lifted.getStatusHistory().toString());
		assertEquals(1, lifted.getAnswerConfirmations().size());
		assertEquals(AnswerConfirmationState.PENDING, entry(t).state());
		List<String> alerts = confirmationAlerts(r, t);
		assertEquals(List.of(reason, reason), alerts);
	}

	/** T-19 (D8; threat 5): a confirmed round edited afterwards is a new release, which stales the passes. */
	@Test
	public void aConfirmedRoundEditedLaterIsReviewed() throws RelizaException {
		Rig r = reviewedTpf();
		Asked a = answeredNoChange(r, "T-19 edited after");
		AgentTaskData t = a.task();
		assertQueuedFor(confirm(r, t, AnswerVerdict.CONFIRMED), "coder");

		coderAsks(r, t, ARCH);
		assign(r, t, r.architect());
		ReleaseData edited = republish(r.architect(), t, a.answer());
		answerWith(r, t, null, true, List.of(edited.getUuid()));

		assertEquals(a.answer().getDocument().path(), edited.getDocument().path(), "the same document, edited");
		assertTrue(reload(t).confirmedNoChangeRounds().contains(a.answer().getUuid()));
		assertFalse(reload(t).confirmedNoChangeRounds().contains(edited.getUuid()));
		assertQueuedFor(reload(t), "functional_reviewer");
	}

	/** T-22 (D7, D8): a checkpoint that stood still stands after a confirmed round, in its settler's name. */
	@Test
	public void aCheckpointThatStoodStillStandsAfterAConfirmedRound() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = task(r, "T-22 checkpoint stood");
		atCheckpoint(r, t);
		assertQueuedFor(authorize(r, t, "coder", COORD), "coder");
		DesignCheckpoint settled = checkpoint(t);
		assertEquals(DesignCheckpointState.SENT_ON, settled.state());
		coderAsks(r, t, ARCH);
		answer(r, t, Boolean.TRUE, false, ARCH);

		assertQueuedFor(confirm(r, t, AnswerVerdict.CONFIRMED), "coder");
		assertTrue(checkpointStands(r, t));
		assertEquals(settled, checkpoint(t), "the record is the authorize's, untouched");
		assertEquals(COORD, checkpoint(t).settledBy());
		assertEquals(1, checkpointAlerts(r, t).size(), "no second stop");
	}

	/**
	 * T-30a (D2, D7, F-3): an advisory round published before the answering hop is its base; the confirmation spares
	 * the answer round only, so the reviewers review the advisory round before the coder, and the checkpoint it
	 * unsettled is not settled by the confirmer.
	 */
	@Test
	public void aConfirmedAnswerOnAnUnreviewedBaseIsReviewedFirst() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = ordinaryAtCoder(r, "T-30a advisory base");
		ReleaseData advisory = publish(otherArchitectSession(r), t, ARCH, true);
		coderAsks(r, t, ARCH);
		ReleaseData answer = answer(r, t, Boolean.TRUE, false, ARCH).get(0);
		assertEquals(advisory.getUuid(), entry(t).rounds().get(0).previous());

		AgentTaskData confirmed = confirm(r, t, AnswerVerdict.CONFIRMED);

		assertEquals(AnswerConfirmationState.CONFIRMED, entry(t).state());
		assertVerdictRow(row(t, 1), StatusTrigger.ANSWER_CONFIRMED, "answer confirmed no change by", answer, advisory);
		assertQueuedFor(confirmed, "functional_reviewer");
		assertFalse(hasCurrentPass(r, t, "functional_reviewer"));
		assertFalse(hasCurrentPass(r, t, "security_reviewer"));
		assertQueuedFor(pass(r, t, r.functional()), "security_reviewer");
		assertCheckpointStopNotTheConfirmers(r, pass(r, t, r.security()), answer, advisory);
		assertQueuedFor(authorize(r, t, "coder", COORD), "coder");
	}

	/** T-30b: the same with the advisory round published while the answer waits; the base is round 1. */
	@Test
	public void aRoundPublishedWhileTheAnswerWaitsIsReviewedFirst() throws RelizaException {
		Rig r = reviewedTpf();
		Asked a = answeredNoChange(r, "T-30b advisory while pending");
		AgentTaskData t = a.task();
		assertEquals(a.round1().getUuid(), entry(t).rounds().get(0).previous());
		publish(otherArchitectSession(r), t, ARCH, true);

		assertQueuedFor(confirm(r, t, AnswerVerdict.CONFIRMED), "functional_reviewer");
		assertQueuedFor(pass(r, t, r.functional()), "security_reviewer");
		assertCheckpointStopNotTheConfirmers(r, pass(r, t, r.security()), a.answer(), a.round1());
	}

	/** At the checkpoint stop: PENDING, settled by nobody, and the confirmation's row still in the history. */
	void assertCheckpointStopNotTheConfirmers(Rig r, AgentTaskData stopped, ReleaseData answer, ReleaseData base) {
		assertEquals(TaskStatus.AWAITING_COORDINATOR, stopped.getStatus(), stopped.getStatusHistory().toString());
		DesignCheckpoint cp = stopped.getDesignCheckpoint();
		assertEquals(DesignCheckpointState.PENDING, cp.state());
		assertNull(cp.settledBy());
		assertTrue(stopped.getStatusHistory().stream().anyMatch(sc -> StatusTrigger.ANSWER_CONFIRMED == sc.trigger()
				&& sc.note().contains(answer.getUuid().toString()) && sc.note().contains(base.getUuid().toString())),
				stopped.getStatusHistory().toString());
		assertTrue(stopped.getStatusHistory().stream().noneMatch(sc -> CONFIRMER.equals(sc.actor())
				&& StatusTrigger.ANSWER_CONFIRMED != sc.trigger()), "the confirmer settles nothing else");
		assertNotEquals(CONFIRMER, cp.settledBy());
	}
}
