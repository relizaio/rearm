/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;

/**
 * A replaced PR is superseded, not abandoned (RD3-13, RD3-18, RD3-20): the replacement delivers, the
 * declaration is checked, and an unreported PR is read from its tracker. Fixtures in {@link AgentDeliveryTestBase}.
 *
 * <p>One file per topic: add a test to the class for its topic, or to a new class extending the base, never at the
 * end of a class other tasks are appending to. Task RD4-15 split the old {@code AgentDeliveryIntegrationTest} this
 * way, because tasks appending to its end made each other's PRs conflict at the closing brace; the methods kept
 * their names.
 */
public class AgentDeliverySupersedeIntegrationTest extends AgentDeliveryTestBase {

	/** A coder holding the task with an old PR and its replacement linked, both on acme/app. */
	private AgentTaskData replacing(Rig r, String old, String replacement) throws RelizaException {
		AgentTaskData t = queuedCoder(r, null);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		agentTaskService.linkPr(t.getUuid(), old, WU);
		agentTaskService.linkPr(t.getUuid(), replacement, WU);
		return reload(t);
	}

	/**
	 * RD3-13: the RD3-4 shape. A PR closed unmerged and replaced is declared superseded; the pass then waits on
	 * the replacement alone, and the task completes when it merges -- no person declares anything abandoned.
	 */
	@Test
	public void aSupersededPrLetsTheReplacementDeliver() throws RelizaException {
		Rig r = rig(false);
		String old = newPrUrl();
		String replacement = newPrUrl();
		pr(r, old, PullRequestState.CLOSED);
		pr(r, replacement, PullRequestState.OPEN);
		AgentTaskData t = replacing(r, old, replacement);

		AgentTaskData declared = agentTaskService.supersedePullRequest(t.getUuid(), old, replacement, "trailer-less merge dropped",
				PERSON, WU);
		AgentTaskData.Delivery a = declared.getDeliveries().get(declared.getDeliveries().size() - 1);
		assertEquals(List.of(old, "SUPERSEDED", replacement, "trailer-less merge dropped"),
				List.of(a.unit(), a.outcome().name(), a.supersededBy(), a.note()));
		assertEquals(PERSON, a.by());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, old + " superseded by " + replacement).size());

		AgentTaskData passed = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done", WU);
		assertEquals(TaskStatus.DELIVERING, passed.getStatus(), "waits on the replacement; the closed old PR does not block");
		assertEquals(AgentDeliveryService.Delivery.SUPERSEDED, agentDeliveryService.pullRequestsOf(passed).stream()
				.filter(pr -> pr.url().equals(old)).findFirst().orElseThrow().unit());

		pr(r, replacement, PullRequestState.MERGED);
		AgentTaskData done = reload(t);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(StatusTrigger.DELIVERED, last(done).trigger());
		assertTrue(events(r, AgentBoardData.BoardEventKind.ALERT, old).isEmpty(), "nothing asked of a person");
	}

	/** RD3-13: each condition of the declaration refuses by name, and a refusal records nothing. */
	@Test
	public void supersedeRefusesEachUnmetCondition() throws RelizaException {
		Rig r = rig(false);
		String open = newPrUrl();
		String merged = newPrUrl();
		String closed = newPrUrl();
		String replacement = newPrUrl();
		String unregistered = newPrUrl();
		String elsewhere = "https://github.com/acme/other/pull/" + PR_NUMBER.incrementAndGet();
		pr(r, open, PullRequestState.OPEN);
		pr(r, merged, PullRequestState.MERGED);
		pr(r, closed, PullRequestState.CLOSED);
		pr(r, replacement, PullRequestState.OPEN);
		AgentTaskData t = replacing(r, closed, replacement);
		for (String u : List.of(open, merged, unregistered, elsewhere)) agentTaskService.linkPr(t.getUuid(), u, WU);
		String unlinked = newPrUrl();
		java.util.Map<String, String[]> cases = new java.util.LinkedHashMap<>();
		cases.put("close it without merging, or declare it abandoned, then supersede", new String[] {open, replacement});
		cases.put("is merged; a merged PR is never superseded", new String[] {merged, replacement});
		cases.put("has not been reported by its repository's CI", new String[] {unregistered, replacement});
		cases.put("The superseded PR " + unlinked + " is not linked to task", new String[] {unlinked, replacement});
		cases.put("; link it first", new String[] {closed, unlinked});
		cases.put("is not on the same repository as", new String[] {closed, elsewhere});
		cases.put("A PR cannot supersede itself", new String[] {closed, closed});
		int declarations = reload(t).getDeliveries().size();
		for (var c : cases.entrySet()) {
			RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.supersedePullRequest(t.getUuid(),
					c.getValue()[0], c.getValue()[1], null, PERSON, WU), c.getKey());
			assertTrue(e.getMessage().contains(c.getKey()), c.getKey() + " / " + e.getMessage());
		}
		assertEquals(declarations, reload(t).getDeliveries().size(), "no refusal recorded anything");

		agentTaskService.supersedePullRequest(t.getUuid(), closed, replacement, null, PERSON, WU);
		RelizaException again = assertThrows(RelizaException.class,
				() -> agentTaskService.supersedePullRequest(t.getUuid(), closed, replacement, null, PERSON, WU));
		assertTrue(again.getMessage().contains("is already declared superseded by " + replacement), again.getMessage());
		RelizaException viaDelivered = assertThrows(RelizaException.class, () -> agentTaskService.declareDelivery(t.getUuid(),
				closed, null, AgentTaskData.DeliveryOutcome.SUPERSEDED, null, PERSON, WU));
		assertTrue(viaDelivered.getMessage().contains("task supersedepr"), viaDelivered.getMessage());
	}

	/**
	 * RD3-13: a superseded PR that merges after all is a conflict. The board is alerted once, naming both PRs,
	 * however often CI reports it, and the unit stays superseded.
	 */
	@Test
	public void aSupersededPrThatMergesAfterAllIsAlertedOnce() throws RelizaException {
		Rig r = rig(false);
		String old = newPrUrl();
		String replacement = newPrUrl();
		pr(r, old, PullRequestState.CLOSED);
		pr(r, replacement, PullRequestState.OPEN);
		AgentTaskData t = replacing(r, old, replacement);
		agentTaskService.supersedePullRequest(t.getUuid(), old, replacement, null, PERSON, WU);
		assertEquals(TaskStatus.DELIVERING,
				agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done", WU).getStatus());

		pr(r, old, PullRequestState.MERGED);
		pr(r, old, PullRequestState.MERGED);
		agentDeliveryService.sweep(WU);
		String alert = old + " was declared superseded by " + replacement + " but has merged after all";
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.ALERT, alert).size(),
				agentBoardService.recentEvents(board(r).getUuid()).toString());
		AgentTaskData still = reload(t);
		assertEquals(TaskStatus.DELIVERING, still.getStatus(), "still waits on the replacement");
		assertEquals(AgentDeliveryService.Delivery.SUPERSEDED, agentDeliveryService.pullRequestsOf(still).stream()
				.filter(pr -> pr.url().equals(old)).findFirst().orElseThrow().unit());
	}

	/**
	 * RD3-13 run 1 T-1, architecture-2: the blocked-delivery ALERT and a complete's refusal name the remedy as it
	 * now is -- linking a replacement alone leaves the PR blocking -- for a PR closed unmerged and for one declared
	 * abandoned alike, since both may be superseded.
	 */
	@Test
	public void theBlockedDeliveryMessageNamesSupersedepr() throws RelizaException {
		String remedy = ". Reopen it to the role that must redo the work, or have the role that pushes code link the PR"
				+ " that replaces it and declare this one superseded with task supersedepr.";
		Rig r = rig(false);
		String closed = newPrUrl();
		pr(r, closed, PullRequestState.CLOSED);
		AgentTaskData back = passedWith(r, queuedCoder(r, null), closed);
		String alert = events(r, AgentBoardData.BoardEventKind.ALERT, "closed without merging: " + closed).get(0);
		assertTrue(alert.endsWith(remedy), alert);
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.complete(back.getUuid(), null, COORD, WU));
		assertTrue(e.getMessage().endsWith(remedy), e.getMessage());
		assertFalse(e.getMessage().contains("or link the PR that replaces it."), e.getMessage());

		Rig a = rig(false);
		mode(a, AgentBoardData.DeliveryMode.DECLARED, false);
		String url = newPrUrl();
		AgentTaskData d = passedWith(a, queuedCoder(a, null), url);
		declare(d, url, AgentTaskData.DeliveryOutcome.ABANDONED);
		String abandoned = events(a, AgentBoardData.BoardEventKind.ALERT, url + " declared abandoned").get(0);
		assertTrue(abandoned.endsWith(remedy), abandoned);
	}

	/**
	 * RD3-13 architecture-2 §1: a PR declared abandoned -- the only verb there was on RD3-4 -- may be declared
	 * superseded; the newer declaration wins, and the task completes on the merged replacement. The completion
	 * INFO says the PRs merged, not that the delivery was declared (§2).
	 */
	@Test
	public void anAbandonedPrMayBeSupersededAndTheTaskCompletesOnItsReplacement() throws RelizaException {
		Rig r = rig(false);
		String old = newPrUrl();
		String replacement = newPrUrl();
		pr(r, old, PullRequestState.CLOSED);
		pr(r, replacement, PullRequestState.OPEN);
		AgentTaskData t = replacing(r, old, replacement);
		agentTaskService.declareDelivery(t.getUuid(), old, null, AgentTaskData.DeliveryOutcome.ABANDONED, "no other verb", COORD, WU);

		AgentTaskData declared = agentTaskService.supersedePullRequest(t.getUuid(), old, replacement, "correcting the workaround",
				PERSON, WU);
		assertEquals(List.of("ABANDONED", "SUPERSEDED"), declared.getDeliveries().stream().map(x -> x.outcome().name()).toList(),
				"both stay on the record; the newer wins");
		AgentTaskData passed = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done", WU);
		assertEquals(TaskStatus.DELIVERING, passed.getStatus(), "not blocked by the abandonment it superseded");
		pr(r, replacement, PullRequestState.MERGED);
		AgentTaskData done = reload(t);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, "Task " + done.label() + " delivered: its PR(s) merged").size(),
				agentBoardService.recentEvents(board(r).getUuid()).toString());
	}

	// ---------- task RD3-20: an unreported PR, abandoned or read from its tracker ----------

	/** Runs {@code body} with {@code reader} answering tracker reads for the task service, then puts the real one back. */
	private void withTracker(PullRequestTrackerHook reader, org.junit.jupiter.api.function.Executable body) throws Throwable {
		Object target = org.springframework.test.util.AopTestUtils.getTargetObject(agentTaskService);
		Object real = org.springframework.test.util.ReflectionTestUtils.getField(target, "pullRequestTrackerHook");
		org.springframework.test.util.ReflectionTestUtils.setField(target, "pullRequestTrackerHook", reader);
		try {
			body.execute();
		} finally {
			org.springframework.test.util.ReflectionTestUtils.setField(target, "pullRequestTrackerHook", real);
		}
	}

	/**
	 * RD3-20, the RD3-4 shape: on a board whose PRs report elsewhere, the replaced PR was never reported here and is
	 * declared abandoned; the abandonment stands in for the tracker, the supersede is accepted with no read, and the
	 * task completes on the declared replacement.
	 */
	@Test
	public void anUnreportedPrDeclaredAbandonedIsSupersededAndTheTaskCompletesOnItsReplacement() throws Throwable {
		Rig r = rig(false);
		mode(r, AgentBoardData.DeliveryMode.DECLARED, false);
		String old = newPrUrl();
		String replacement = newPrUrl();
		AgentTaskData t = replacing(r, old, replacement);
		agentTaskService.declareDelivery(t.getUuid(), old, null, AgentTaskData.DeliveryOutcome.ABANDONED, "trailer-less merges",
				COORD, WU);
		withTracker((org, url) -> { throw new AssertionError("an abandoned PR needs no tracker read: " + url); }, () -> {
			AgentTaskData declared = agentTaskService.supersedePullRequest(t.getUuid(), old, replacement, null, COORD, WU);
			AgentTaskData.Delivery a = declared.getDeliveries().get(declared.getDeliveries().size() - 1);
			assertEquals(AgentTaskData.DeliveryOutcome.SUPERSEDED, a.outcome());
			assertNull(a.observed(), "nothing was read");
		});
		AgentTaskData passed = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done", WU);
		assertEquals(TaskStatus.DELIVERING, passed.getStatus(), "waits for the replacement's declaration");
		AgentTaskData done = declare(passed, replacement, AgentTaskData.DeliveryOutcome.DELIVERED);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
	}

	/**
	 * RD3-20: an unreported PR nobody abandoned is read from its tracker. Closed without merging is accepted and the
	 * read recorded; open or merged is refused naming where it was read; a read that fails leaves the refusal, with
	 * its reason and the remedy that works.
	 */
	@Test
	public void anUnreportedPrIsReadFromItsTracker() throws Throwable {
		Rig r = rig(false);
		mode(r, AgentBoardData.DeliveryMode.DECLARED, false);
		java.util.Map<String, PullRequestTrackerHook.Read> answers = new java.util.HashMap<>();
		withTracker((org, url) -> answers.getOrDefault(url, PullRequestTrackerHook.Read.failed("no answer")), () -> {
			String open = newPrUrl();
			String merged = newPrUrl();
			String unreadable = newPrUrl();
			String closed = newPrUrl();
			String replacement = newPrUrl();
			answers.put(open, PullRequestTrackerHook.Read.of("OPEN", "github.com"));
			answers.put(merged, PullRequestTrackerHook.Read.of("MERGED", "github.com"));
			answers.put(unreadable, PullRequestTrackerHook.Read.failed("the organization has no GitHub integration"));
			answers.put(closed, PullRequestTrackerHook.Read.of("CLOSED", "github.com"));
			AgentTaskData t = replacing(r, closed, replacement);
			for (String u : List.of(open, merged, unreadable)) agentTaskService.linkPr(t.getUuid(), u, WU);

			RelizaException e = assertThrows(RelizaException.class,
					() -> agentTaskService.supersedePullRequest(t.getUuid(), open, replacement, null, COORD, WU));
			assertEquals(open + " is open on github.com; close it unmerged, or declare it abandoned, then supersede", e.getMessage());
			e = assertThrows(RelizaException.class,
					() -> agentTaskService.supersedePullRequest(t.getUuid(), merged, replacement, null, COORD, WU));
			assertEquals(merged + " is merged on github.com; a merged PR is never superseded", e.getMessage());
			e = assertThrows(RelizaException.class,
					() -> agentTaskService.supersedePullRequest(t.getUuid(), unreadable, replacement, null, COORD, WU));
			assertEquals(unreadable + " has not been reported by its repository's CI, is not declared abandoned, and its"
					+ " tracker could not be read (the organization has no GitHub integration); declare it abandoned, then"
					+ " supersede", e.getMessage());

			AgentTaskData declared = agentTaskService.supersedePullRequest(t.getUuid(), closed, replacement, null, COORD, WU);
			AgentTaskData.Delivery a = declared.getDeliveries().get(declared.getDeliveries().size() - 1);
			assertEquals(List.of("SUPERSEDED", "CLOSED", "github.com"), List.of(a.outcome().name(), a.observed().state(),
					a.observed().source()));
			assertNotNull(a.observed().at());

			// The same read backs an abandonment of an unreported PR: recorded, never refusing.
			AgentTaskData abandoned = agentTaskService.declareDelivery(t.getUuid(), open, null,
					AgentTaskData.DeliveryOutcome.ABANDONED, "will not land", COORD, WU);
			AgentTaskData.Delivery ab = abandoned.getDeliveries().get(abandoned.getDeliveries().size() - 1);
			assertEquals("OPEN", ab.observed().state(), "the abandonment records what the tracker said");
			AgentTaskData superseded = agentTaskService.supersedePullRequest(t.getUuid(), open, replacement, null, COORD, WU);
			assertNull(superseded.getDeliveries().get(superseded.getDeliveries().size() - 1).observed(),
					"after an abandonment the supersede reads nothing");
		});
	}

	/** RD3-20: a PR CI reported merged is never superseded, abandoned or not. */
	@Test
	public void aReportedMergedPrIsNeverSupersededEvenAbandoned() throws RelizaException {
		Rig r = rig(false);
		String merged = newPrUrl();
		String replacement = newPrUrl();
		pr(r, merged, PullRequestState.MERGED);
		AgentTaskData t = replacing(r, merged, replacement);
		agentTaskService.declareDelivery(t.getUuid(), merged, null, AgentTaskData.DeliveryOutcome.ABANDONED, "said so", COORD, WU);
		RelizaException e = assertThrows(RelizaException.class,
				() -> agentTaskService.supersedePullRequest(t.getUuid(), merged, replacement, null, COORD, WU));
		assertEquals(merged + " is merged; a merged PR is never superseded", e.getMessage());
	}
}
