/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.AnswerConfirmationState;
import io.reliza.model.AgentTaskData.AnswerVerdict;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.HoldStop;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.ReleaseData;
import io.reliza.model.User;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.service.AgentTaskService.Confirmer;
import io.reliza.ws.App;

/**
 * Task TPF-171, built by TPF-172 (D6, D9, D10, D12): the budget and the cycle cap on the new paths, who may not
 * confirm their own answer, and the verb's gates. Rows T-9, T-10, T-11, T-16 (a, b, d, e, f) and T-17 (a, b, c).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentAnswerConfirmationStopsIntegrationTest extends AgentAnswerRoutingTestBase {

	static final String SELF = "The agent that answered cannot confirm its own answer on task ";

	/** The coder asked, the architect answered --no-change: the task waits on the verdict. */
	AgentTaskData pendingAnswer(Rig r, String title) throws RelizaException {
		AgentTaskData t = ordinaryAtCoder(r, title);
		coderAsks(r, t, ARCH);
		answer(r, t, Boolean.TRUE, false, ARCH);
		AgentTaskData pending = reload(t);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, pending.getStatus(), pending.getStatusHistory().toString());
		assertEquals(AnswerConfirmationState.PENDING, entry(pending).state());
		return pending;
	}

	/** Nothing was written: one PENDING entry, the task waiting on the coordinator, the history as it was. */
	void assertUntouched(AgentTaskData before) {
		AgentTaskData now = reload(before);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, now.getStatus());
		assertEquals(before.getAnswerConfirmations(), now.getAnswerConfirmations());
		assertEquals(before.getStatusHistory().size(), now.getStatusHistory().size());
	}

	private static Map<String, Object> item(String id) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", id);
		item.put("priority", 1);
		item.put("status", "OPEN");
		item.put("title", "question " + id);
		return item;
	}

	/** T-9, task case 8a: the CHANGE walk checks the budget of the role it queues. */
	@Test
	public void theBudgetStopsTheWalkAfterAChangedAnswer() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = ordinaryAtCoder(r, "T-9 budget on the walk");
		coderAsks(r, t, ARCH);
		assign(r, t, r.architect());
		ReleaseData round = publish(r.architect(), t, ARCH);
		agentTaskService.setBudget(t.getUuid(), 1L, PERSON, WU);
		AgentTaskData held = answerWith(r, t, null, true, List.of(round.getUuid()));

		assertEquals(TaskStatus.ON_HOLD, held.getStatus(), held.getStatusHistory().toString());
		assertEquals(HoldLevel.OPERATOR, held.getHold().level());
		assertEquals(HoldStop.BUDGET, held.getHold().stop());
		assertTrue(held.getHold().reason().contains("the next functional_reviewer round does not fit"),
				held.getHold().reason());
	}

	/** T-10, task case 8b: a confirmation is recorded whatever the budget; the walk's stop parks the task after it. */
	@Test
	public void aConfirmationOverBudgetIsRecordedAndTheWalkHolds() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = pendingAnswer(r, "T-10 budget after confirm");
		agentTaskService.setBudget(t.getUuid(), 1L, PERSON, WU);

		AgentTaskData held = confirm(r, t, AnswerVerdict.CONFIRMED);

		assertEquals(AnswerConfirmationState.CONFIRMED, entry(t).state());
		assertEquals(TaskStatus.ON_HOLD, held.getStatus(), held.getStatusHistory().toString());
		assertEquals(HoldStop.BUDGET, held.getHold().stop());
		assertTrue(held.getHold().reason().contains("the next coder round does not fit"), held.getHold().reason());
		assertEquals(StatusTrigger.ANSWER_CONFIRMED, row(t, 1).trigger());
		assertTrue(row(t, 1).note().contains("answer confirmed no change by"), row(t, 1).note());
		assertEquals(TaskStatus.ON_HOLD, row(t, 0).to());

		agentTaskService.setBudget(t.getUuid(), null, PERSON, WU);
		assertQueuedFor(agentTaskService.liftHold(t.getUuid(), HoldLevel.OPERATOR, PERSON, WU), "coder");
	}

	/** T-11, task case 8c: each ask still counts one round of the pair, and the cap stops the second ask. */
	@Test
	public void theCycleCapStillCountsTheAsks() throws RelizaException {
		Rig r = reviewedTpf();
		agentBoardService.updateSettings(r.board().getUuid(), null, null, 1, null, null, null, WU);
		AgentTaskData t = ordinaryAtCoder(r, "T-11 cycle cap");
		String id = "q" + QID.incrementAndGet();
		ReleaseData first = index(r.coder(), reload(t), QUESTIONS, "REJECTED", ARCH, id, 1, "OPEN");
		assertQueuedFor(signOff(r.coder(), t, AgentTaskData.SignOffOutcome.REJECTED, "which store?",
				List.of(first.getUuid())), "architect");
		String pair = AgentTaskData.cycleKey(roleConfig(r, "coder").getUuid(), roleConfig(r, "architect").getUuid());
		assertEquals(Integer.valueOf(1), reload(t).getCycles().get(pair));
		assign(r, t, r.architect());
		assertQueuedFor(answerWith(r, t, null, false, List.of()), "coder");

		assign(r, t, r.coder());
		// The answer published nothing, so nothing closed the id: the second ask carries it, and asks one more.
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", QUESTIONS.name());
		idx.put("verdict", "REJECTED");
		idx.put("reviewItems", new ArrayList<>(List.of(item(id), item(id + "b"))));
		idx.put("about", Map.of("specification", ARCH.name()));
		ReleaseData q = agentDocumentService.publish(r.coder().session(), new PublishRequest(t.getUuid(), QUESTIONS, null,
				null, null, null, null, null, idx, null, null, null, null, null), WU);
		AgentTaskData stopped = signOff(r.coder(), t, AgentTaskData.SignOffOutcome.REJECTED, "again",
				List.of(q.getUuid()));
		assertEquals(TaskStatus.ON_HOLD, stopped.getStatus(), stopped.getStatusHistory().toString());
		assertEquals(HoldStop.CYCLE_CAP, stopped.getHold().stop());
		assertTrue(stopped.getHold().reason().startsWith("stopped by cycle cap: coder "), stopped.getHold().reason());
		assertTrue(stopped.getHold().reason().contains(" architect went 1 round(s) (cap 1)"), stopped.getHold().reason());
	}

	/**
	 * T-16 (a), (b) (D10, F-4; threat 3): the answering agent, as the seat by agent or as a person by the owner of the
	 * USER key it runs on, cannot confirm; it may refuse.
	 */
	@Test
	public void theAnswererCannotConfirmItsOwnAnswer() throws RelizaException {
		Rig base = reviewedTpf();
		User owner = person(base.org(), PermissionType.ADMIN);
		Rig r = architectOn(base, personalKey(base.org(), owner, PermissionType.ADMIN));
		List<Confirmer> selves = List.of(new Confirmer(r.architect().agentUuid(), null), new Confirmer(null, owner.getUuid()));
		for (Confirmer self : selves) {
			AgentTaskData t = pendingAnswer(r, "T-16 self " + self);
			RelizaException e = refused(() -> confirm(r, t, AnswerVerdict.CONFIRMED, null, CONFIRMER, self));
			assertEquals(SELF + t.label() + "; refuse it, or leave it to the coordinator", e.getMessage());
			assertUntouched(t);
			assertQueuedFor(confirm(r, t, AnswerVerdict.REFUSED, null, CONFIRMER, self), "functional_reviewer");
			assertEquals(AnswerConfirmationState.REFUSED, entry(t).state());
		}
	}

	/**
	 * T-16 (d), (e), (f): another user, a browser person, and the owner of nothing the answering agent runs on (its
	 * key is FREEFORM) confirm.
	 */
	@Test
	public void othersConfirm() throws RelizaException {
		Rig base = reviewedTpf();
		User owner = person(base.org(), PermissionType.ADMIN);
		User other = person(base.org(), PermissionType.ADMIN);
		Rig onUser = architectOn(base, personalKey(base.org(), owner, PermissionType.ADMIN));
		for (Confirmer c : List.of(new Confirmer(null, other.getUuid()), new Confirmer(null, null))) {
			AgentTaskData t = pendingAnswer(onUser, "T-16 other " + c);
			assertQueuedFor(confirm(onUser, t, AnswerVerdict.CONFIRMED, null, CONFIRMER, c), "coder");
			assertEquals(AnswerConfirmationState.CONFIRMED, entry(t).state());
		}
		Rig onFreeform = architectOn(base, freeformKey(base.org()));
		AgentTaskData t = pendingAnswer(onFreeform, "T-16 freeform");
		assertQueuedFor(confirm(onFreeform, t, AnswerVerdict.CONFIRMED, null, CONFIRMER,
				new Confirmer(null, owner.getUuid())), "coder");
	}

	/** T-17 (a): no PENDING entry, never asked, or settled already; nothing written. */
	@Test
	public void aVerdictWithNothingPendingIsRefused() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData never = task(r, "T-17a never asked");
		atCheckpoint(r, never);
		AgentTaskData atStop = reload(never);
		RelizaException e = refused(() -> confirm(r, never, AnswerVerdict.CONFIRMED));
		assertEquals("Task " + atStop.label() + " has no answer awaiting confirmation", e.getMessage());
		assertEquals(atStop.getStatusHistory().size(), reload(never).getStatusHistory().size());

		AgentTaskData t = pendingAnswer(r, "T-17a settled already");
		confirm(r, t, AnswerVerdict.REFUSED);
		pass(r, t, r.functional());
		AgentTaskData stopped = pass(r, t, r.security());
		assertEquals(TaskStatus.AWAITING_COORDINATOR, stopped.getStatus(), "at the checkpoint");
		RelizaException again = refused(() -> confirm(r, t, AnswerVerdict.CONFIRMED));
		assertTrue(again.getMessage().contains("has no answer awaiting confirmation"), again.getMessage());
		assertEquals(stopped.getAnswerConfirmations(), reload(t).getAnswerConfirmations());
		assertEquals(stopped.getStatusHistory().size(), reload(t).getStatusHistory().size());
	}

	/** T-17 (b): a task on hold is refused by the status check; the entry stays PENDING. */
	@Test
	public void aVerdictOnAHeldTaskIsRefused() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = pendingAnswer(r, "T-17b held");
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "wait", COORD, WU);
		RelizaException e = refused(() -> confirm(r, t, AnswerVerdict.CONFIRMED));
		assertTrue(e.getMessage().contains("operation requires [AWAITING_COORDINATOR]"), e.getMessage());
		assertEquals(TaskStatus.ON_HOLD, reload(t).getStatus());
		assertEquals(AnswerConfirmationState.PENDING, entry(t).state());
	}

	/** T-17 (c): a note over the limit is refused before anything is written. */
	@Test
	public void aNoteOverTheLimitIsRefused() throws RelizaException {
		Rig r = reviewedTpf();
		AgentTaskData t = pendingAnswer(r, "T-17c long note");
		String note = "x".repeat(AgentTaskService.DESCRIPTION_MAX + 1);
		RelizaException e = refused(() -> confirm(r, t, AnswerVerdict.CONFIRMED, note, CONFIRMER,
				new Confirmer(UUID.randomUUID(), null)));
		assertTrue(e.getMessage().startsWith("note is at most"), e.getMessage());
		assertUntouched(t);
	}
}
