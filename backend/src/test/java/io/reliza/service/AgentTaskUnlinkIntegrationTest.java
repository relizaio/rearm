/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;

/**
 * Unlinking a PR that should never have counted (task t20261010-033523-18839): it leaves the task's PRs, is recorded
 * on {@code unlinkedPrs} and posted, and never shrinks what a task has to deliver past its pass. Fixtures in
 * {@link AgentDeliveryTestBase}.
 */
public class AgentTaskUnlinkIntegrationTest extends AgentDeliveryTestBase {

	/** The rig's coder holding a new task with {@code urls} linked, before any pass. */
	private AgentTaskData held(Rig r, String... urls) throws RelizaException {
		AgentTaskData t = queuedCoder(r, null);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		for (String u : urls) agentTaskService.linkPr(t.getUuid(), u, WU);
		return reload(t);
	}

	private AgentActor coder(Rig r) {
		return AgentActor.ofSession(r.session().getUuid());
	}

	private AgentDeliveryService.Delivery unitOf(AgentTaskData td, String url) {
		return agentDeliveryService.pullRequestsOf(td).stream().filter(pr -> pr.url().equals(url)).findFirst()
				.orElseThrow().unit();
	}

	private RelizaException refused(AgentTaskData t, String url, AgentActor by) {
		return assertThrows(RelizaException.class, () -> agentTaskService.unlinkPr(t.getUuid(), url, null, by, WU));
	}

	/** A PR linked by mistake is taken off before the pass: the task then delivers on the other alone. */
	@Test
	public void anUnlinkedPrNoLongerCountsAndIsRecorded() throws RelizaException {
		Rig r = rig(false);
		String mistaken = newPrUrl();
		String real = newPrUrl();
		pr(r, mistaken, PullRequestState.OPEN);
		pr(r, real, PullRequestState.OPEN);
		AgentTaskData t = held(r, mistaken, real);

		AgentTaskData unlinked = agentTaskService.unlinkPr(t.getUuid(), mistaken + "/",
				"  wrong\ntask:\u2028Task X:\u0085forged ", coder(r), WU);
		assertEquals(List.of(real), unlinked.getPrUrls());
		AgentTaskData.UnlinkedPr row = unlinked.getUnlinkedPrs().get(0);
		assertEquals(List.of(mistaken, "wrong task: Task X: forged"), List.of(row.url(), row.note()),
				"recorded as linked, the note on one line, Unicode line breaks too");
		assertEquals(coder(r), row.by());
		assertNotNull(row.at());
		assertNull(row.relinkedAt());
		List<String> info = events(r, AgentBoardData.BoardEventKind.INFO, "PR " + mistaken + " unlinked by");
		assertEquals(1, info.size(), agentBoardService.recentEvents(board(r).getUuid()).toString());
		assertTrue(info.get(0).endsWith(": wrong task: Task X: forged"), info.get(0));

		AgentTaskData passed = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done", WU);
		assertEquals(TaskStatus.DELIVERING, passed.getStatus());
		pr(r, real, PullRequestState.MERGED);
		assertEquals(TaskStatus.COMPLETED, reload(t).getStatus(), "the unlinked PR never merged and is not waited on");
	}

	/** The superseded half of a pair is unlinked while the task delivers on its replacement. */
	@Test
	public void aSupersededPrIsUnlinkedWhileTheTaskDelivers() throws RelizaException {
		Rig r = rig(false);
		String old = newPrUrl();
		String replacement = newPrUrl();
		pr(r, old, PullRequestState.CLOSED);
		pr(r, replacement, PullRequestState.OPEN);
		AgentTaskData t = held(r, old, replacement);
		agentTaskService.supersedePullRequest(t.getUuid(), old, replacement, null, PERSON, WU);
		AgentTaskData d = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done", WU);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());

		AgentTaskData after = agentTaskService.unlinkPr(d.getUuid(), old, "tidy", COORD, WU);
		assertEquals(TaskStatus.DELIVERING, after.getStatus());
		assertEquals(List.of(replacement), after.getPrUrls());
		assertEquals(1, after.getDeliveries().size(), "the supersede stays on the record");
		pr(r, replacement, PullRequestState.MERGED);
		assertEquals(TaskStatus.COMPLETED, reload(d).getStatus());
	}

	/** Past the pass an unlink never leaves nothing to deliver, whoever asks; the refusal writes nothing. */
	@Test
	public void unlinkRefusesTheLastCountablePrPastThePass() throws RelizaException {
		Rig r = rig(false);
		String only = newPrUrl();
		pr(r, only, PullRequestState.OPEN);
		AgentTaskData d = passedWith(r, queuedCoder(r, null), only);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		int rows = d.getStatusHistory().size();

		for (AgentActor by : List.of(COORD, PERSON)) {
			RelizaException e = refused(d, only, by);
			assertEquals("Unlinking " + only + " would leave task " + d.label() + " with no PR to deliver; reopen it to the"
					+ " role that redoes the work, supersede it (task supersedepr), or cancel it", e.getMessage());
		}
		AgentTaskData still = reload(d);
		assertEquals(List.of(only), still.getPrUrls());
		assertTrue(still.getUnlinkedPrs().isEmpty());
		assertEquals(rows, still.getStatusHistory().size());
		assertTrue(events(r, AgentBoardData.BoardEventKind.INFO, "unlinked").isEmpty());
	}

	/** Past the pass a session takes out only a PR that will not land; a person may take an open one, out loud. */
	@Test
	public void aSessionCannotUnlinkAnOpenPrPastThePassButAPersonCanWithAnAlert() throws RelizaException {
		Rig r = rig(false);
		String open = newPrUrl();
		String unregistered = newPrUrl();
		String other = newPrUrl();
		pr(r, open, PullRequestState.OPEN);
		pr(r, other, PullRequestState.OPEN);
		AgentTaskData d = passedWith(r, queuedCoder(r, null), open, unregistered, other);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());

		assertEquals(open + " is open and a pass names its head; close it unmerged, or supersede it, then unlink",
				refused(d, open, COORD).getMessage());
		assertEquals(unregistered + " is unregistered and a pass names its head; close it unmerged, or supersede it, then"
				+ " unlink", refused(d, unregistered, coder(r)).getMessage());

		AgentTaskData after = agentTaskService.unlinkPr(d.getUuid(), open, "split out", PERSON, WU);
		assertEquals(TaskStatus.DELIVERING, after.getStatus());
		assertEquals(List.of(unregistered, other), after.getPrUrls());
		assertEquals(List.of("Task " + d.label() + ": PR " + open + " unlinked by ops@acme.example: split out"),
				events(r, AgentBoardData.BoardEventKind.ALERT, "unlinked"), "an ALERT, and no tested head to name here");
		assertTrue(events(r, AgentBoardData.BoardEventKind.INFO, "unlinked").isEmpty());
	}

	/** A merged or delivered PR is history: it stays linked. */
	@Test
	public void unlinkRefusesAMergedOrDeliveredPr() throws RelizaException {
		Rig r = rig(false);
		String merged = newPrUrl();
		String declared = newPrUrl();
		String open = newPrUrl();
		pr(r, merged, PullRequestState.MERGED);
		pr(r, open, PullRequestState.OPEN);
		AgentTaskData t = held(r, merged, declared, open);
		declare(t, declared, AgentTaskData.DeliveryOutcome.DELIVERED);

		assertEquals(merged + " merged; a delivered PR stays linked", refused(t, merged, PERSON).getMessage());
		assertEquals(declared + " is declared delivered; a delivered PR stays linked",
				refused(t, declared, PERSON).getMessage());
		assertEquals(3, reload(t).getPrUrls().size());
	}

	/** The replacement a linked PR is superseded by stays while that supersede does; unlink the superseded one first. */
	@Test
	public void unlinkRefusesTheReplacementOfASupersededPr() throws RelizaException {
		Rig r = rig(false);
		String old = newPrUrl();
		String replacement = newPrUrl();
		String third = newPrUrl();
		pr(r, old, PullRequestState.CLOSED);
		pr(r, replacement, PullRequestState.OPEN);
		pr(r, third, PullRequestState.OPEN);
		AgentTaskData t = held(r, old, replacement, third);
		agentTaskService.supersedePullRequest(t.getUuid(), old, replacement, null, PERSON, WU);
		AgentTaskData d = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done", WU);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());

		assertEquals(replacement + " is the replacement of " + old + " (declared superseded by it); unlink " + old
				+ " first, or supersede it by another PR", refused(d, replacement, PERSON).getMessage());
		agentTaskService.unlinkPr(d.getUuid(), old, null, COORD, WU);
		AgentTaskData after = agentTaskService.unlinkPr(d.getUuid(), replacement, "not needed", PERSON, WU);
		assertEquals(List.of(third), after.getPrUrls());
		assertEquals(TaskStatus.DELIVERING, after.getStatus(), "the third PR still delivers");
	}

	/**
	 * Refused on a terminal task, on a task parked for the operator by the seat or by its hop (the hold stays), and
	 * for a PR the task does not link.
	 */
	@Test
	public void unlinkRefusesATerminalTaskAParkedTaskAndAnUnlinkedUrl() throws RelizaException {
		Rig r = rig(false);
		String merged = newPrUrl();
		pr(r, merged, PullRequestState.MERGED);
		AgentTaskData done = passedWith(r, queuedCoder(r, null), merged);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals("Task " + done.label() + " is COMPLETED; its links are history", refused(done, merged, PERSON).getMessage());

		String one = newPrUrl();
		String two = newPrUrl();
		pr(r, one, PullRequestState.OPEN);
		pr(r, two, PullRequestState.CLOSED);
		AgentTaskData back = passedWith(r, queuedCoder(r, null), one, two);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, back.getStatus());
		agentTaskService.parkForOperator(back.getUuid(), COORD.uuid(), "merge the open one by hand?", WU);
		for (AgentActor by : List.of(COORD, PERSON)) {
			assertEquals("Task " + back.label() + " is parked, awaiting the operator; release the hold first, then unlink",
					refused(back, two, by).getMessage());
		}
		AgentTaskData parked = reload(back);
		assertEquals(TaskStatus.ON_HOLD, parked.getStatus(), "a person's unlink does not answer the question");
		assertEquals(2, parked.getPrUrls().size());

		AgentTaskData hop = held(r, newPrUrl(), newPrUrl());
		agentTaskService.parkHopForOperator(hop.getUuid(), r.session().getUuid(), "which of the two?", WU);
		assertEquals("Task " + hop.label() + " is parked, awaiting the operator; release the hold first, then unlink",
				refused(hop, hop.getPrUrls().get(0), coder(r)).getMessage(), "a hop parked by its holder too");

		AgentTaskData t = held(r, one);
		String elsewhere = newPrUrl();
		assertTrue(refused(t, elsewhere, PERSON).getMessage().startsWith("The PR to unlink " + elsewhere + " is not linked to"
				+ " task " + t.label()));
		assertEquals("Name the PR to unlink (--pr)", refused(t, " ", PERSON).getMessage());
		RelizaException longNote = assertThrows(RelizaException.class,
				() -> agentTaskService.unlinkPr(t.getUuid(), one, "x".repeat(4001), PERSON, WU));
		assertEquals("note is at most 4000 characters", longNote.getMessage());
	}

	/**
	 * A plain hold records no status to return to, but lifts to AWAITING_COORDINATOR: a task held there once its final
	 * role passed is still past the pass, so an unlink cannot leave a later complete with nothing to deliver.
	 */
	@Test
	public void aTaskHeldPastItsPassKeepsThePastThePassRules() throws RelizaException {
		Rig r = rig(false);
		String closed = newPrUrl();
		pr(r, closed, PullRequestState.CLOSED);
		AgentTaskData back = passedWith(r, queuedCoder(r, null), closed);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, back.getStatus());
		AgentTaskData held = agentTaskService.hold(back.getUuid(), HoldLevel.COORDINATOR, "looking into it", COORD, WU);
		assertEquals(TaskStatus.ON_HOLD, held.getStatus());
		assertNull(held.getHold().returnTo());

		assertTrue(refused(held, closed, COORD).getMessage().contains("with no PR to deliver"));
		assertEquals(List.of(closed), reload(back).getPrUrls());
	}

	/** On a board delivering without PRs the PRs are not units: nothing about them is past a pass. */
	@Test
	public void aBoardDeliveringWithoutPrsUnlinksFreely() throws RelizaException {
		Rig r = rig(false);
		mode(r, AgentBoardData.DeliveryMode.NONE, true);
		String only = newPrUrl();
		pr(r, only, PullRequestState.OPEN);
		AgentTaskData d = passedWith(r, queuedCoder(r, null), only);
		assertEquals(TaskStatus.DELIVERING, d.getStatus(), "waiting for its delivery to be declared");

		AgentTaskData after = agentTaskService.unlinkPr(d.getUuid(), only, null, COORD, WU);
		assertTrue(after.getPrUrls().isEmpty());
		assertEquals(TaskStatus.DELIVERING, after.getStatus());
		assertEquals(TaskStatus.COMPLETED, declare(after, "main", AgentTaskData.DeliveryOutcome.DELIVERED).getStatus());
	}

	/** Linking an unlinked PR again stamps its row; another unlink appends a new row, and the stamped one stays. */
	@Test
	public void aRelinkedUndeclaredPrCountsAgainAndTheRowIsStamped() throws RelizaException {
		Rig r = rig(false);
		String a = newPrUrl();
		String b = newPrUrl();
		pr(r, a, PullRequestState.OPEN);
		pr(r, b, PullRequestState.OPEN);
		AgentTaskData t = held(r, a, b);
		agentTaskService.unlinkPr(t.getUuid(), a, "not this one", coder(r), WU);

		AgentTaskData relinked = agentTaskService.linkPr(t.getUuid(), a, "relinker (agent 1234abcd)", WU);
		assertEquals(List.of(b, a), relinked.getPrUrls());
		assertEquals(AgentDeliveryService.Delivery.WAITING, unitOf(relinked, a));
		AgentTaskData.UnlinkedPr row = relinked.getUnlinkedPrs().get(0);
		assertEquals("relinker (agent 1234abcd)", row.relinkedBy());
		assertNotNull(row.relinkedAt());
		List<String> info = events(r, AgentBoardData.BoardEventKind.INFO, "re-linked");
		assertEquals(1, info.size(), agentBoardService.recentEvents(board(r).getUuid()).toString());
		assertTrue(info.get(0).matches("Task " + Pattern.quote(t.label()) + ": PR " + Pattern.quote(a) + " re-linked by relinker \\(agent"
				+ " 1234abcd\\) \\(unlinked \\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z by "
				+ Pattern.quote(coder(r).display()) + ": not this one\\)"), info.get(0));

		agentTaskService.linkPr(t.getUuid(), a, "again", WU);
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, "re-linked").size(), "a link of a linked PR stamps nothing");
		agentTaskService.unlinkPr(t.getUuid(), a, null, coder(r), WU);
		List<AgentTaskData.UnlinkedPr> rows = reload(t).getUnlinkedPrs();
		assertEquals(2, rows.size(), "append-only");
		assertEquals("relinker (agent 1234abcd)", rows.get(0).relinkedBy());
		assertNull(rows.get(1).relinkedAt());
	}

	/** A relinked PR keeps its newest declaration: unlink and relink do not undo a supersede. */
	@Test
	public void aRelinkedSupersededPrStaysSuperseded() throws RelizaException {
		Rig r = rig(false);
		String old = newPrUrl();
		String replacement = newPrUrl();
		pr(r, old, PullRequestState.CLOSED);
		pr(r, replacement, PullRequestState.OPEN);
		AgentTaskData t = held(r, old, replacement);
		agentTaskService.supersedePullRequest(t.getUuid(), old, replacement, null, PERSON, WU);
		agentTaskService.unlinkPr(t.getUuid(), old, null, coder(r), WU);

		AgentTaskData relinked = agentTaskService.linkPr(t.getUuid(), old, WU);
		assertEquals(AgentDeliveryService.Delivery.SUPERSEDED, unitOf(relinked, old));
		assertEquals("an API key", relinked.getUnlinkedPrs().get(0).relinkedBy());
	}

	/**
	 * A PR closed past the pass sends the task to the coordinator; unlinking it clears the block, and the
	 * coordinator's complete then waits on the PR that is left.
	 */
	@Test
	public void anUnlinkedClosedPrClearsAPrClosedBlock() throws RelizaException {
		Rig r = rig(false);
		String a = newPrUrl();
		String b = newPrUrl();
		pr(r, a, PullRequestState.OPEN);
		pr(r, b, PullRequestState.OPEN);
		AgentTaskData d = passedWith(r, queuedCoder(r, null), a, b);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		pr(r, a, PullRequestState.CLOSED);
		AgentTaskData back = reload(d);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, back.getStatus());
		assertEquals(StatusTrigger.PR_CLOSED, last(back).trigger());
		assertThrows(RelizaException.class, () -> agentTaskService.complete(d.getUuid(), null, COORD, WU));

		AgentTaskData unlinked = agentTaskService.unlinkPr(d.getUuid(), a, "abandoned approach", COORD, WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, unlinked.getStatus(), "left for the coordinator");
		AgentTaskData completing = agentTaskService.complete(d.getUuid(), null, COORD, WU);
		assertEquals(TaskStatus.DELIVERING, completing.getStatus());
		pr(r, b, PullRequestState.MERGED);
		assertEquals(TaskStatus.COMPLETED, reload(d).getStatus());
	}
}
