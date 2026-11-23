/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.AnswerConfirmation;
import io.reliza.model.AgentTaskData.AnswerConfirmationState;
import io.reliza.model.AgentTaskData.AnswerVerdict;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.ws.App;

/**
 * Task TPF-171, built by TPF-172 (sections 4 and 7.1): a pass that answers a question goes through the forward walk,
 * so every role whose pass the answer made stale reviews it before the asker gets the task back, and a
 * {@code --no-change} answer is compared with the round handed over before the answering hop. Rows T-1, T-2, T-3,
 * T-8, T-15 and T-29.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentAnswerRoutingIntegrationTest extends AgentAnswerRoutingTestBase {

	/** T-1, task case 1: the OOL-5 shape on a design-from-parent child; the reviewers go before the coder. */
	@Test
	public void aChangedAnswerOnACarriedChildIsReviewedBeforeTheCoder() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = coderHolds(r, "T-1 carried, a change").flagged();
		coderAsks(r, t, ARCH);
		answer(r, t, null, true, ARCH);

		AgentTaskData after = reload(t);
		assertQueuedFor(after, "functional_reviewer");
		assertTrue(after.getQuestionStack().isEmpty(), "the frame is popped");
		assertFalse(carryLive(r, t, "functional_reviewer"), "the round ended the carry");
		assertFalse(carryLive(r, t, "security_reviewer"));
		assertTrue(after.getAnswerConfirmations().isEmpty(), "no confirmation is asked for");
		assertEquals(1, infos(r, t, "every role whose pass no longer stands goes before coder").size());

		assertQueuedFor(pass(r, t, r.functional()), "security_reviewer");
		assertQueuedFor(pass(r, t, r.security()), "coder");
	}

	/** T-2, task case 2: the same on an ordinary task; the misrouting was not specific to a carried child. */
	@Test
	public void aChangedAnswerOnAnOrdinaryTaskIsReviewedBeforeTheCoder() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = ordinaryAtCoder(r, "T-2 ordinary, a change");
		coderAsks(r, t, ARCH);
		answer(r, t, null, true, ARCH);

		assertQueuedFor(reload(t), "functional_reviewer");
		assertQueuedFor(pass(r, t, r.functional()), "security_reviewer");
		assertQueuedFor(pass(r, t, r.security()), "coder");
		assertTrue(reload(t).getAnswerConfirmations().isEmpty());
	}

	/** T-3, task case 3: the reviewer rejects the answer round; the architect, the reviewer again, then the coder. */
	@Test
	public void aRejectedAnswerRoundGoesBackToTheArchitectBeforeTheCoder() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = ordinaryAtCoder(r, "T-3 the answer rejected");
		coderAsks(r, t, ARCH);
		answer(r, t, null, true, ARCH);
		assertQueuedFor(reload(t), "functional_reviewer");

		assign(r, t, r.functional());
		ReleaseData items = index(r.functional(), reload(t), RearmSpecificationType.BOARD_REVIEW_ITEMS, "REJECTED", ARCH,
				"F-1", 1, "OPEN");
		assertQueuedFor(signOff(r.functional(), t, SignOffOutcome.REJECTED, "the round misses a case",
				List.of(items.getUuid())), "architect");

		answer(r, t, null, true, ARCH);
		assertQueuedFor(reload(t), "functional_reviewer");
		assign(r, t, r.functional());
		ReleaseData fixed = index(r.functional(), reload(t), RearmSpecificationType.BOARD_REVIEW_ITEMS, "PASSED", ARCH,
				"F-1", 1, "RESOLVED");
		assertQueuedFor(signOff(r.functional(), t, SignOffOutcome.PASSED, "fixed", List.of(fixed.getUuid())),
				"security_reviewer");
		assertQueuedFor(pass(r, t, r.security()), "coder");
	}

	/**
	 * T-8, task case 7: the asker upstream of the stale role. The walk is in board order: the asking coder first, the
	 * reviewer whose pass the answer made stale after it.
	 */
	@Test
	public void anAskerBeforeTheStaleRoleGetsItsAnswerFirst() throws RelizaException {
		Rig r = askerFirst(true);
		AgentTaskData t = task(r, "T-8 asker upstream");
		assign(r, t, r.architect());
		ReleaseData round1 = publish(r.architect(), t, ARCH);
		assertQueuedFor(signOff(r.architect(), t, SignOffOutcome.PASSED, "designed", List.of(round1.getUuid())), "coder");
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "the reviewer first", COORD, WU);
		assertQueuedFor(agentTaskService.liftHold(t.getUuid(), HoldLevel.COORDINATOR, COORD, null, "reviewer", WU),
				"reviewer");
		assertQueuedFor(pass(r, t, r.functional()), "coder");

		coderAsks(r, t, ARCH);
		answer(r, t, null, false, ARCH);
		assertQueuedFor(reload(t), "coder");
		assertTrue(hasCurrentPass(r, t, "architect"));
		assertFalse(hasCurrentPass(r, t, "reviewer"), "the reviewer's pass on round 1 is stale");

		assertQueuedFor(code(r, t), "reviewer");
	}

	/** T-15 (D2): a --no-change pass that declares no output does not stop; the walk finds every pass standing. */
	@Test
	public void aNoChangeAnswerWithoutAnOutputDoesNotStop() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = ordinaryAtCoder(r, "T-15 no output");
		coderAsks(r, t, ARCH);
		assign(r, t, r.architect());
		AgentTaskData after = answerWith(r, t, Boolean.TRUE, false, List.of());

		assertQueuedFor(after, "coder");
		assertTrue(after.getAnswerConfirmations().isEmpty(), "no confirmation record");
		assertTrue(confirmationAlerts(r, t).isEmpty(), "no ALERT");
		assertTrue(hasCurrentPass(r, t, "functional_reviewer"));
		assertTrue(hasCurrentPass(r, t, "security_reviewer"));
	}

	/**
	 * T-29a (D2, F-1): a path republished in the answering hop, both versions declared; the base is round 1, never the
	 * hop's own first version, and only the newest version is recorded and named.
	 */
	@Test
	public void theBaseIsTheRoundHandedOverBeforeTheHopNotItsFirstVersion() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = ordinaryAtCoder(r, "T-29a republished, both declared");
		ReleaseData round1 = archRounds(t).get(0);
		coderAsks(r, t, ARCH);
		assign(r, t, r.architect());
		ReleaseData first = publish(r.architect(), t, ARCH);
		ReleaseData second = republish(r.architect(), t, first);
		answerWith(r, t, Boolean.TRUE, false, List.of(first.getUuid(), second.getUuid()));

		AnswerConfirmation e = entry(t);
		assertEquals(AnswerConfirmationState.PENDING, e.state());
		assertEquals(1, e.rounds().size(), e.rounds().toString());
		assertEquals(second.getUuid(), e.rounds().get(0).release());
		assertEquals(round1.getUuid(), e.rounds().get(0).previous());
		List<String> alerts = confirmationAlerts(r, t);
		assertEquals(1, alerts.size(), alerts.toString());
		assertTrue(alerts.get(0).contains(second.getUuid().toString()), alerts.get(0));
		assertTrue(alerts.get(0).contains(round1.getUuid().toString()), alerts.get(0));
		assertFalse(alerts.get(0).contains(first.getUuid().toString()), alerts.get(0));

		assertQueuedFor(confirm(r, t, AnswerVerdict.CONFIRMED), "coder");
		AgentTaskData built = code(r, t);
		assertNotEquals("functional_reviewer", built.getRole(), built.getStatusHistory().toString());
		assertNotEquals("security_reviewer", built.getRole(), built.getStatusHistory().toString());
	}

	/** T-29b: as T-29a, the second version alone declared. */
	@Test
	public void theBaseIsRoundOneWhenOnlyTheNewestVersionIsDeclared() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = ordinaryAtCoder(r, "T-29b republished, newest declared");
		ReleaseData round1 = archRounds(t).get(0);
		coderAsks(r, t, ARCH);
		assign(r, t, r.architect());
		ReleaseData first = publish(r.architect(), t, ARCH);
		ReleaseData second = republish(r.architect(), t, first);
		answerWith(r, t, Boolean.TRUE, false, List.of(second.getUuid()));

		AnswerConfirmation e = entry(t);
		assertEquals(1, e.rounds().size(), e.rounds().toString());
		assertEquals(second.getUuid(), e.rounds().get(0).release());
		assertEquals(round1.getUuid(), e.rounds().get(0).previous());
	}

	/** T-29c: a DRAFT round left by a hop that returned is never the base: the floor is ASSEMBLED. */
	@Test
	public void aDraftLeftByAReturnedHopIsNeverTheBase() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = ordinaryAtCoder(r, "T-29c returned draft");
		ReleaseData round1 = archRounds(t).get(0);
		coderAsks(r, t, ARCH);
		assign(r, t, r.architect());
		ReleaseData draft = publish(r.architect(), t, ARCH);
		agentTaskService.returnTask(t.getUuid(), r.architect().sessionUuid(), TaskReturnReason.OTHER, "not ready", WU);
		assertEquals(ReleaseData.ReleaseLifecycle.DRAFT, release(draft.getUuid()).getLifecycle());
		assertQueuedFor(authorize(r, t, "architect", COORD), "architect");
		answer(r, t, Boolean.TRUE, false, ARCH);

		AnswerConfirmation e = entry(t);
		assertEquals(round1.getUuid(), e.rounds().get(0).previous());
		assertEquals(round1.getDocument().round(), e.rounds().get(0).previousRound());
		assertEquals(Integer.valueOf(1), e.rounds().get(0).previousRound());
	}

	/** T-29d: an answer with no round handed over before it has nothing to compare with: a change, no stop. */
	@Test
	public void anAnswerWithNoBaseIsAChange() throws RelizaException {
		Rig r = askerFirst(false);
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "T-29d no base", null,
				null, null, null, null, null, WU);
		assertQueuedFor(authorize(r, t, "coder", COORD), "coder");
		coderAsks(r, t, ARCH);
		answer(r, t, Boolean.TRUE, false, ARCH);

		AgentTaskData after = reload(t);
		assertTrue(after.getAnswerConfirmations().isEmpty(), "no record");
		assertNotEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus(), "no stop");
		assertQueuedFor(after, "coder");
		assertEquals(1, infos(r, t, "--no-change not taken").size(), boardEvents(r).toString());
	}
}
