/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.ReleaseData.DocumentRef;
import io.reliza.model.Release;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.AnswerConfirmation;
import io.reliza.model.AgentTaskData.AnswerConfirmationState;
import io.reliza.model.AgentTaskData.AnswerRound;
import io.reliza.model.AgentTaskData.AnswerVerdict;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.AgentTaskRoleConfigData.HumanGate;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.RearmSpecificationType;
import io.reliza.service.AgentBoardService.RoleConfigSpec;
import io.reliza.service.AgentRoutingService.Routed;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.service.AgentTaskService.Confirmer;
import io.reliza.ws.App;

/**
 * TPF-172 mutation round (TPF-171 D2, D5, D6, D7): what the answer routing leaves open. The stop reason and the
 * verdict row word for word, the INFO an answer that does not stop leaves, a mix of rounds with and without a base,
 * a reader's floor above every round (no base), a verdict of null, a role that asked and is not active, an answering
 * sign-off that is not the task's last hop, and the effects of a verdict.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentAnswerEdgesIntegrationTest extends AgentAnswerRoutingTestBase {

	@Autowired OssReleaseService ossReleaseService;

	record Asked(AgentTaskData task, ReleaseData round1, ReleaseData questions, ReleaseData answer) {}

	Asked answeredNoChange(Rig r, String title) throws RelizaException {
		AgentTaskData t = ordinaryAtCoder(r, title);
		ReleaseData round1 = archRounds(t).get(0);
		ReleaseData q = coderAsks(r, t, ARCH);
		ReleaseData answer = answer(r, t, Boolean.TRUE, false, ARCH).get(0);
		return new Asked(reload(t), round1, q, answer);
	}

	private static String rounds(AnswerRound r) {
		return r.specification() + " round " + r.round() + " (release " + r.release() + ") against round "
				+ r.previousRound() + " (release " + r.previous() + ")";
	}

	@Test
	public void theStopNamesTheRoundsTheBaseAndTheVerbWordForWord() throws RelizaException {
		Rig r = reviewedTpf();
		Asked a = answeredNoChange(r, "edge: the stop text");
		AnswerRound round = entry(a.task()).rounds().get(0);

		String expected = "architect answered " + QUESTIONS + " round " + a.questions().getDocument().round()
				+ " with --no-change: ARCHITECTURE round " + round.round() + " (version " + round.version() + ", release "
				+ round.release() + ", " + round.path() + ") against round " + round.previousRound() + " (version "
				+ round.previousVersion() + ", release " + round.previous() + ", " + round.previousPath()
				+ "); compare them, then confirm it changes nothing (back to coder) or refuse it (reviewed as a change):"
				+ " rearm agent task confirm-answer " + a.task().keyOrUuid() + " --session <seat> --verdict CONFIRMED|REFUSED";
		List<String> alerts = confirmationAlerts(r, a.task());
		assertEquals(1, alerts.size(), alerts.toString());
		assertTrue(alerts.get(0).contains(expected), alerts.get(0) + "\n!contains\n" + expected);
	}

	@Test
	public void theStopNamesTheAskingRoleOnlyWhenItIsStillActive() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = ordinaryAtCoder(r, "edge: the asker retired");
		coderAsks(r, t, ARCH);
		agentBoardService.upsertRoleConfig(board(r), new RoleConfigSpec("coder", null, null, null, null, Boolean.FALSE, null,
				null, null, null, null, null, null, null, null, null, null), true, WU);

		answer(r, t, Boolean.TRUE, false, ARCH);

		List<String> alerts = confirmationAlerts(r, t);
		assertEquals(1, alerts.size(), alerts.toString());
		assertTrue(alerts.get(0).contains("confirm it changes nothing (back to the asking role)"), alerts.get(0));
	}

	@Test
	public void theVerdictRowsNameTheRoundsWordForWord() throws RelizaException {
		Rig r = reviewedTpf();
		Asked confirmed = answeredNoChange(r, "edge: the confirmed row");
		AnswerRound c = entry(confirmed.task()).rounds().get(0);
		confirm(r, confirmed.task(), AnswerVerdict.CONFIRMED);
		assertEquals("answer confirmed no change by " + CONFIRMER.display() + ": " + rounds(c), row(confirmed.task(), 1).note());
		assertEquals(1, infos(r, confirmed.task(), "answer confirmed no change by").size(), boardEvents(r).toString());

		Asked refused = answeredNoChange(r, "edge: the refused row");
		AnswerRound f = entry(refused.task()).rounds().get(0);
		confirm(r, refused.task(), AnswerVerdict.REFUSED);
		assertEquals("no change refused by " + CONFIRMER.display() + ": " + rounds(f) + "; reviewed as a change",
				row(refused.task(), 1).note());
		assertEquals(1, infos(r, refused.task(), "no change refused by").size(), boardEvents(r).toString());
	}

	@Test
	public void anAnswerThatDoesNotStopLeavesAnInfoSayingWhatItHandedOver() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData withRound = ordinaryAtCoder(r, "edge: info with a round");
		coderAsks(r, withRound, ARCH);
		ReleaseData round = answer(r, withRound, null, false, ARCH).get(0);
		List<String> a = infos(r, withRound, "architect answered with ARCHITECTURE");
		assertEquals(1, a.size(), boardEvents(r).toString());
		assertTrue(a.get(0).contains("answered with ARCHITECTURE round " + round.getDocument().round() + " (release "
				+ round.getUuid() + "); every role whose pass no longer stands goes before coder"), a.get(0));
		assertFalse(a.get(0).contains("not taken"), a.get(0));

		AgentTaskData none = ordinaryAtCoder(r, "edge: info without a round");
		coderAsks(r, none, ARCH);
		assign(r, none, r.architect());
		answerWith(r, none, null, false, List.of());
		List<String> b = infos(r, none, "answered without a new round");
		assertEquals(1, b.size(), boardEvents(r).toString());
		assertTrue(b.get(0).contains("answered without a new round; every role whose pass no longer stands goes before coder"),
				b.get(0));

		AgentTaskData claimed = ordinaryAtCoder(r, "edge: no change claimed, nothing handed over");
		coderAsks(r, claimed, ARCH);
		assign(r, claimed, r.architect());
		answerWith(r, claimed, Boolean.TRUE, false, List.of());
		List<String> c = infos(r, claimed, "answered without a new round");
		assertEquals(1, c.size(), boardEvents(r).toString());
		assertFalse(c.get(0).contains("not taken"), "there was nothing to compare, and nothing was claimed over: " + c.get(0));
	}

	@Test
	public void roundsWithAndWithoutABaseAreAChange() throws RelizaException {
		Rig r = reviewedTpf();
		agentBoardService.upsertRoleConfig(board(r), role("architect", 10, HumanGate.NONE, List.of(),
				List.of(produces(ARCH), produces(TM), produces(DD)), ARCHITECT_HOP, null), true, WU);
		AgentTaskData t = ordinaryAtCoder(r, "edge: one round with a base, one without");
		coderAsks(r, t, ARCH);
		assign(r, t, r.architect());
		ReleaseData arch = publish(r.architect(), t, ARCH);
		ReleaseData dd = publish(r.architect(), t, DD);

		AgentTaskData after = answerWith(r, t, Boolean.TRUE, false, List.of(arch.getUuid(), dd.getUuid()));

		assertTrue(after.getAnswerConfirmations().isEmpty(), "a round with nothing to compare with: no stop");
		assertNotEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus(), after.getStatusHistory().toString());
		assertEquals(1, infos(r, t, "--no-change not taken").size(), boardEvents(r).toString());
	}

	/**
	 * On a board with no reviewer and no checkpoint a design round stays at ASSEMBLED, the most a sign-off hands over. When the reader
	 * of ARCHITECTURE asks for READY_TO_SHIP no round clears the floor: there is nothing to compare an answer with, so it
	 * is a change, however the answer is marked.
	 */
	@Test
	public void aFloorAboveWhatASignOffHandsOverLeavesNoBase() throws RelizaException {
		Rig r = rig(false, false, HumanGate.NONE, null, true);
		AgentTaskData t = task(r, "edge: no round clears the readers' floor");
		assign(r, t, r.architect());
		ReleaseData round1 = publish(r.architect(), t, ARCH);
		ReleaseData threats = publish(r.architect(), t, TM);
		signOff(r.architect(), t, SignOffOutcome.PASSED, "designed", List.of(round1.getUuid(), threats.getUuid()));
		assertQueuedFor(reload(t), "coder");
		assign(r, t, r.coder());
		assertEquals(ReleaseLifecycle.ASSEMBLED, release(round1.getUuid()).getLifecycle(), "set-up: no review or checkpoint shipped it");
		coderAsks(r, t, ARCH);
		RequiredInput shippable = new RequiredInput(InputKind.DOCUMENT, ARCH, InputScope.TASK, null,
				ReleaseLifecycle.READY_TO_SHIP, InputResolution.LATEST_PASSING);
		agentBoardService.upsertRoleConfig(board(r), new RoleConfigSpec("coder", null, null, null, null, null, null, null,
				null, null, List.of(shippable, inputAt(TM)), null, null, null, null, null, null), true, WU);

		answer(r, t, Boolean.TRUE, false, ARCH);

		assertTrue(reload(t).getAnswerConfirmations().isEmpty(), "nothing at the readers' floor: no base, so no stop");
		assertEquals(1, infos(r, t, "--no-change not taken").size(), boardEvents(r).toString());
	}

	@Test
	public void theReasonNamesTheQuestionAndTheAskerOnlyWhenItCanRead() throws RelizaException {
		Rig r = reviewedTpf();
		Asked a = answeredNoChange(r, "edge: a reason from a record that cannot name its question");
		AgentTaskData td = reload(a.task());
		AnswerConfirmation e = entry(td);
		List<AgentTaskRoleConfigData> roles = agentBoardService.listRoleConfigs(board(r).getUuid());
		AnswerConfirmation noQuestion = new AnswerConfirmation(e.answeredAt(), e.role(), e.roleUuid(), e.session(), e.agent(),
				e.askingRole(), null, e.rounds(), AnswerConfirmationState.PENDING, null, null, null);
		AnswerConfirmation unreadable = new AnswerConfirmation(e.answeredAt(), e.role(), e.roleUuid(), e.session(), e.agent(),
				UUID.randomUUID(), UUID.randomUUID(), e.rounds(), AnswerConfirmationState.PENDING, null, null, null);

		assertTrue(routingService.confirmationReason(td, roles, noQuestion).startsWith("architect answered the question with --no-change: "));
		String reason = routingService.confirmationReason(td, roles, unreadable);
		assertTrue(reason.startsWith("architect answered the question with --no-change: "), reason);
		assertTrue(reason.contains("confirm it changes nothing (back to the asking role)"), reason);
	}

	/** Rewrites a round's document as the board cut it: no session. */
	void boardCut(ReleaseData rd) throws RelizaException {
		Release row = sharedReleaseService.getRelease(rd.getUuid()).orElseThrow();
		ReleaseData data = ReleaseData.dataFromRecord(row);
		DocumentRef d = data.getDocument();
		data.setDocument(new DocumentRef(d.specification(), d.path(), d.digest(), d.mediaType(), d.indexPath(),
				d.indexDigest(), d.task(), null, d.round(), d.reviewItems(), d.elements(), d.elementChecks(), d.advisory(),
				d.publishedByRole(), d.supersededBy()));
		ossReleaseService.saveRelease(row, data, WU, false);
	}

	/** T-1: a second answer on the task: the pending entry is the last one, and the verdict settles that one. */
	@Test
	public void aSecondAnswerIsTheLastEntryAndItsVerdictSettlesIt() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = ordinaryAtCoder(r, "edge: two answers");
		coderAsks(r, t, ARCH);
		ReleaseData first = answer(r, t, Boolean.TRUE, false, ARCH).get(0);
		assertQueuedFor(confirm(r, t, AnswerVerdict.CONFIRMED), "coder");
		coderAsks(r, t, ARCH);
		ReleaseData second = answer(r, t, Boolean.TRUE, false, ARCH).get(0);

		List<AnswerConfirmation> before = reload(t).getAnswerConfirmations();
		assertEquals(2, before.size(), before.toString());
		assertEquals(AnswerConfirmationState.CONFIRMED, before.get(0).state());
		assertEquals(AnswerConfirmationState.PENDING, before.get(1).state());
		assertEquals(second.getUuid(), before.get(1).rounds().get(0).release());
		assertEquals(first.getUuid(), before.get(1).rounds().get(0).previous(), "the confirmed round is the base of the next answer");

		confirm(r, t, AnswerVerdict.REFUSED);

		List<AnswerConfirmation> after = reload(t).getAnswerConfirmations();
		assertEquals(2, after.size(), after.toString());
		assertEquals(before.get(0), after.get(0), "the first answer's entry is left as it was");
		assertEquals(AnswerConfirmationState.REFUSED, after.get(1).state());
		assertEquals(second.getUuid(), after.get(1).rounds().get(0).release());
		assertTrue(reload(t).confirmedNoChangeRounds().contains(first.getUuid()), "the first answer's rounds stay out of the staleness reads");
		assertFalse(reload(t).confirmedNoChangeRounds().contains(second.getUuid()), "a refused answer's rounds do not");
	}

	/** T-2: a confirmation is refused when the asker could not read its inputs, whole message; a refusal still works. */
	@Test
	public void aConfirmationIsRefusedWhenTheAskerCouldNotReadItsInputs() throws RelizaException {
		Rig r = reviewedTpf();
		Asked a = answeredNoChange(r, "edge: the asker's inputs are gone");
		for (ReleaseData rd : archRounds(a.task())) ossReleaseService.updateReleaseLifecycle(rd.getUuid(), ReleaseLifecycle.CANCELLED, WU);

		RelizaException e = refused(() -> confirm(r, a.task(), AnswerVerdict.CONFIRMED));

		assertEquals("Confirming would queue coder without its inputs (no ARCHITECTURE round of this task reaches ASSEMBLED);"
				+ " refuse the answer so it is reviewed", e.getMessage());
		assertEquals(AnswerConfirmationState.PENDING, entry(a.task()).state());
		assertEquals(TaskStatus.AWAITING_COORDINATOR, reload(a.task()).getStatus());
		confirm(r, a.task(), AnswerVerdict.REFUSED);
		assertEquals(AnswerConfirmationState.REFUSED, entry(a.task()).state());
	}

	/** T-3: a round the board cut is nobody's base; with nothing else the answer is a change. */
	@Test
	public void aRoundTheBoardCutIsNotTheBaseOfAnAnswer() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = ordinaryAtCoder(r, "edge: every round was cut by the board");
		coderAsks(r, t, ARCH);
		for (ReleaseData rd : archRounds(t)) boardCut(rd);

		answer(r, t, Boolean.TRUE, false, ARCH);

		assertTrue(reload(t).getAnswerConfirmations().isEmpty(), "no base: not comparable, so no stop");
		assertEquals(1, infos(r, t, "--no-change not taken").size(), boardEvents(r).toString());
	}

	/** T-3, the twin: an older agent round and a newer board-cut one; the base is the agent's. */
	@Test
	public void theBaseIsTheAgentsRoundWhenTheNewerOneWasCutByTheBoard() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = ordinaryAtCoder(r, "edge: a newer round cut by the board");
		ReleaseData round1 = archRounds(t).get(0);
		coderAsks(r, t, ARCH);
		ReleaseData round2 = answer(r, t, null, false, ARCH).get(0);
		AgentTaskData after = reviewsPass(r, t);
		if (TaskStatus.AWAITING_COORDINATOR == after.getStatus()) authorize(r, t, "coder", COORD);
		assertQueuedFor(reload(t), "coder");
		boardCut(round2);
		coderAsks(r, t, ARCH);

		answer(r, t, Boolean.TRUE, false, ARCH);

		assertEquals(AnswerConfirmationState.PENDING, entry(t).state());
		assertEquals(round1.getUuid(), entry(t).rounds().get(0).previous(), "the agent's round 1, not the newer round the board cut");
	}

	/** T-5: the carried base is named in the stop with its round, version, release and path, and the record keeps them. */
	@Test
	public void theCarriedBaseIsNamedWithItsDetailsInTheStopAndTheRecord() throws RelizaException {
		Rig r = reviewedTpf();
		Carried c = coderHolds(r, "edge: a carried base");
		AgentTaskData t = c.flagged();
		coderAsks(r, t, ARCH);
		answer(r, t, Boolean.TRUE, false, ARCH);

		ReleaseData carried = release(c.design().get(0).getUuid());
		AnswerRound round = entry(t).rounds().get(0);
		assertEquals(carried.getUuid(), round.previous());
		assertEquals(carried.getDocument().round(), round.previousRound());
		assertEquals(carried.getVersion(), round.previousVersion());
		assertEquals(carried.getDocument().path(), round.previousPath());
		List<String> alerts = confirmationAlerts(r, t);
		assertEquals(1, alerts.size(), alerts.toString());
		assertTrue(alerts.get(0).contains("against the round carried from " + c.parent().keyOrUuid() + " round "
				+ carried.getDocument().round() + " (version " + carried.getVersion() + ", release " + carried.getUuid() + ", "
				+ carried.getDocument().path() + ")"), alerts.get(0));
	}

	@Test
	public void aVerdictOfNullIsRefusedAndNothingIsSettled() throws RelizaException {
		Rig r = reviewedTpf();
		Asked a = answeredNoChange(r, "edge: a null verdict");

		RelizaException e = refused(() -> agentTaskService.confirmAnswer(a.task().getUuid(), board(r), null, null, CONFIRMER,
				new Confirmer(UUID.randomUUID(), null), WU));

		assertEquals("verdict is CONFIRMED or REFUSED", e.getMessage());
		assertEquals(AnswerConfirmationState.PENDING, entry(a.task()).state());
		assertEquals(TaskStatus.AWAITING_COORDINATOR, reload(a.task()).getStatus());
	}

	@Test
	public void aConfirmationIsRefusedWhenTheRoleThatAskedIsNoLongerActive() throws RelizaException {
		Rig r = reviewedTpf();
		Asked a = answeredNoChange(r, "edge: the asker retired after the stop");
		agentBoardService.upsertRoleConfig(board(r), new RoleConfigSpec("coder", null, null, null, null, Boolean.FALSE, null,
				null, null, null, null, null, null, null, null, null, null), true, WU);

		RelizaException e = refused(() -> confirm(r, a.task(), AnswerVerdict.CONFIRMED));

		assertEquals("Confirming would queue the role that asked, which is no longer active on this board; refuse the answer"
				+ " so it is reviewed", e.getMessage());
		assertEquals(AnswerConfirmationState.PENDING, entry(a.task()).state());
		assertQueuedFor(confirm(r, a.task(), AnswerVerdict.REFUSED), "functional_reviewer");
	}

	@Test
	public void aVerdictRoutedFromAnAnsweringSignOffThatIsNotTheLastHopGoesToTheCoordinator() throws RelizaException {
		Rig r = reviewedTpf();
		Asked a = answeredNoChange(r, "edge: the answering hop is not the last");
		AnswerConfirmation e = entry(a.task());
		AgentTaskData td = reload(a.task());
		ZonedDateTime other = e.answeredAt().plusSeconds(7);
		AnswerConfirmation otherTime = new AnswerConfirmation(other, e.role(), e.roleUuid(), e.session(), e.agent(),
				e.askingRole(), e.questionsRelease(), e.rounds(), AnswerConfirmationState.CONFIRMED, CONFIRMER,
				ZonedDateTime.now(), null);
		AnswerConfirmation otherRole = new AnswerConfirmation(e.answeredAt(), "coder", roleConfig(r, "coder").getUuid(),
				e.session(), e.agent(), e.askingRole(), e.questionsRelease(), e.rounds(), AnswerConfirmationState.CONFIRMED,
				CONFIRMER, ZonedDateTime.now(), null);
		AnswerConfirmation noRoleUuid = new AnswerConfirmation(e.answeredAt(), "coder", null, e.session(), e.agent(),
				e.askingRole(), e.questionsRelease(), e.rounds(), AnswerConfirmationState.CONFIRMED, CONFIRMER,
				ZonedDateTime.now(), null);

		for (AnswerConfirmation crafted : List.of(otherTime, otherRole, noRoleUuid)) {
			Routed routed = routingService.routeAnswerConfirmed(reload(a.task()), board(r), crafted, "the verdict line");
			assertEquals(TaskStatus.AWAITING_COORDINATOR, routed.task().getStatus());
			assertTrue(routed.effects().events().stream().anyMatch(ev -> ev.message().contains(
					"the answering sign-off of the confirmed answer is not the task's last hop; nothing was routed")),
					routed.effects().events().toString());
			assertTrue(routed.effects().events().stream().noneMatch(ev -> ev.message().contains("the verdict line")),
					"no verdict INFO for a verdict that routed nothing");
		}
		assertEquals(td.getStatus(), reload(a.task()).getStatus());
	}
}
