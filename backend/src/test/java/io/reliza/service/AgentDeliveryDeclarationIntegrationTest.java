/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;

/**
 * Delivery by declaration (task 18c5c293) and the refusals around a blocked unit (RD3-16): a PR this
 * instance cannot see, or a board without PRs, settles when someone declares it. Fixtures in
 * {@link AgentDeliveryTestBase}.
 *
 * <p>One file per topic: add a test to the class for its topic, or to a new class extending the base, never at the
 * end of a class other tasks are appending to. Task RD4-15 split the old {@code AgentDeliveryIntegrationTest} this
 * way, because tasks appending to its end made each other's PRs conflict at the closing brace; the methods kept
 * their names.
 */
public class AgentDeliveryDeclarationIntegrationTest extends AgentDeliveryTestBase {

	@Test
	public void theWaitingEventNamesAnUnregisteredPrAndTheRemedy() throws RelizaException {
		Rig r = rig(false);
		String open = newPrUrl();
		String elsewhere = newPrUrl();
		pr(r, open, PullRequestState.OPEN);
		AgentTaskData d = passedWith(r, queuedCoder(r, null), open, elsewhere);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		List<String> info = events(r, AgentBoardData.BoardEventKind.INFO, "waiting for 2 PR(s) to merge");
		assertEquals(1, info.size(), agentBoardService.recentEvents(board(r).getUuid()).toString());
		assertTrue(info.get(0).contains(open + " open"), info.get(0));
		assertTrue(info.get(0).contains(elsewhere + " unregistered on this instance"), info.get(0));
		assertTrue(info.get(0).contains("declare it on the task page (Declare merge), or with rearm agent task declare-delivery " + d.getUuid()
				+ " --session <seat-session> --unit <pr> --commit <merge sha>"
				+ ", or set delivery.mode: DECLARED on the board"), info.get(0));
	}

	@Test
	public void aDeclarationSettlesAnUnregisteredPrOnADefaultBoard() throws RelizaException {
		Rig r = rig(false);
		String elsewhere = newPrUrl();
		AgentTaskData d = passedWith(r, queuedCoder(r, null), elsewhere);
		assertEquals(TaskStatus.DELIVERING, d.getStatus(), "unregistered: waits, as before");
		AgentTaskData done = declare(d, elsewhere + "/", AgentTaskData.DeliveryOutcome.DELIVERED);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(StatusTrigger.DELIVERED, last(done).trigger());
		assertEquals(elsewhere, done.getDeliveries().get(0).unit(), "recorded as linked");
		assertEquals(SHA, done.getDeliveries().get(0).commit());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, "delivered: its delivery is declared").size());
	}

	@Test
	public void aDeclaredBoardWaitsForEveryPrAndAnAbandonedOneGoesToTheCoordinator() throws RelizaException {
		Rig r = rig(false);
		mode(r, AgentBoardData.DeliveryMode.DECLARED, false);
		String one = newPrUrl();
		String two = newPrUrl();
		AgentTaskData d = passedWith(r, queuedCoder(r, null), one, two);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, "waiting for 2 PR(s) to be declared as merged").size());

		AgentTaskData half = declare(d, one, AgentTaskData.DeliveryOutcome.DELIVERED);
		assertEquals(TaskStatus.DELIVERING, half.getStatus(), "one of two");
		assertEquals(List.of(two), agentDeliveryService.deliveryOf(half).units(AgentDeliveryService.Delivery.WAITING)
				.stream().map(AgentDeliveryService.TaskPullRequest::url).toList(), "still waiting, on the other");

		AgentTaskData abandoned = declare(half, two, AgentTaskData.DeliveryOutcome.ABANDONED);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, abandoned.getStatus());
		assertEquals(StatusTrigger.DELIVERY_ABANDONED, last(abandoned).trigger());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.ALERT, two + " declared abandoned (by hand)").size(),
				agentBoardService.recentEvents(board(r).getUuid()).toString());
	}

	@Test
	public void onADeclaredBoardBothDeclaredOrAMergedRowDeliver() throws RelizaException {
		Rig r = rig(false);
		mode(r, AgentBoardData.DeliveryMode.DECLARED, false);
		String declared = newPrUrl();
		String merged = newPrUrl();
		pr(r, merged, PullRequestState.OPEN);
		AgentTaskData d = passedWith(r, queuedCoder(r, null), declared, merged);
		pr(r, merged, PullRequestState.MERGED);
		assertEquals(TaskStatus.DELIVERING, reload(d).getStatus(), "the merged row counts; the other still waits");
		assertEquals(TaskStatus.COMPLETED, declare(reload(d), declared, AgentTaskData.DeliveryOutcome.DELIVERED).getStatus());
	}

	@Test
	public void aBoardWithoutPrsCompletesAtThePassOrWaitsForADeclaration() throws RelizaException {
		Rig r = rig(false);
		mode(r, AgentBoardData.DeliveryMode.NONE, false);
		assertEquals(TaskStatus.COMPLETED, passedWith(r, queuedCoder(r, null)).getStatus(), "as before 9af9d722");

		mode(r, AgentBoardData.DeliveryMode.NONE, true);
		AgentTaskData d = passedWith(r, queuedCoder(r, null));
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, "waiting for its delivery to be declared").size());
		AgentTaskData done = declare(d, "main", AgentTaskData.DeliveryOutcome.DELIVERED);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals("main", done.getDeliveries().get(0).unit());
	}

	@Test
	public void aDeclarationIsChecked() throws RelizaException {
		Rig r = rig(false);
		String url = newPrUrl();
		AgentTaskData d = passedWith(r, queuedCoder(r, null), url);
		RelizaException noCommit = assertThrows(RelizaException.class, () -> agentTaskService.declareDelivery(
				d.getUuid(), url, null, AgentTaskData.DeliveryOutcome.DELIVERED, null, COORD, WU));
		assertTrue(noCommit.getMessage().contains("names the commit that landed"), noCommit.getMessage());
		RelizaException unlinked = assertThrows(RelizaException.class, () -> agentTaskService.declareDelivery(
				d.getUuid(), newPrUrl(), SHA, AgentTaskData.DeliveryOutcome.DELIVERED, null, COORD, WU));
		assertTrue(unlinked.getMessage().contains("is not a PR linked to this task"), unlinked.getMessage());
		AgentTaskData done = declare(d, url, AgentTaskData.DeliveryOutcome.DELIVERED);
		RelizaException finished = assertThrows(RelizaException.class, () -> declare(done, url,
				AgentTaskData.DeliveryOutcome.DELIVERED));
		assertTrue(finished.getMessage().contains("nothing waits on its delivery"), finished.getMessage());
		assertTrue(reload(d).getDeliveries().size() == 1, "a refused declaration writes nothing");
	}

	/**
	 * RD3-16: complete refuses a task whose delivery will not land, with the reason, and writes nothing -- no
	 * history row, no board event. On RD3-4 it returned the task unchanged, added a same-state row and
	 * re-posted the ALERT each time.
	 */
	@Test
	public void completeRefusesAnAbandonedUnitOutLoudAndWritesNothing() throws RelizaException {
		Rig r = rig(false);
		mode(r, AgentBoardData.DeliveryMode.DECLARED, false);
		String url = newPrUrl();
		AgentTaskData d = passedWith(r, queuedCoder(r, null), url);
		AgentTaskData abandoned = declare(d, url, AgentTaskData.DeliveryOutcome.ABANDONED);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, abandoned.getStatus());
		int rows = abandoned.getStatusHistory().size();
		int events = agentBoardService.recentEvents(board(r).getUuid()).size();

		for (AgentActor actor : List.of(COORD, PERSON)) {
			RelizaException e = assertThrows(RelizaException.class,
					() -> agentTaskService.complete(d.getUuid(), "ship it", actor, false, WU));
			assertEquals("Task " + abandoned.label() + " passed, but its delivery will not land: " + url
					+ " declared abandoned (by hand). Reopen it to the role that must redo the work, or have the role that pushes"
					+ " code link the PR that replaces it and declare this one superseded with task supersedepr.", e.getMessage());
		}
		AgentTaskData after = reload(d);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		assertEquals(rows, after.getStatusHistory().size(), "a refused complete writes no history row");
		assertEquals(events, agentBoardService.recentEvents(board(r).getUuid()).size(), "and posts nothing");
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.ALERT, url + " declared abandoned").size(),
				"the ALERT was posted once, by the declaration");
	}

	/**
	 * RD3-16: a pass routed to the coordinator over a PR already closed lands there once. The sign-off's row
	 * says how it got there; settle adds no AWAITING_COORDINATOR to AWAITING_COORDINATOR row, and the ALERT
	 * says why. A complete then refuses with the same reason.
	 */
	@Test
	public void aPassOverAClosedPrWritesNoSameStateRowAndCompleteRefuses() throws RelizaException {
		Rig r = rig(false);
		String url = newPrUrl();
		pr(r, url, PullRequestState.CLOSED);
		AgentTaskData back = passedWith(r, queuedCoder(r, null), url);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, back.getStatus());
		assertTrue(back.getStatusHistory().stream().noneMatch(c -> c.from() == c.to()), back.getStatusHistory().toString());
		assertEquals(StatusTrigger.SIGNOFF, last(back).trigger());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.ALERT, "closed without merging: " + url).size());

		RelizaException e = assertThrows(RelizaException.class,
				() -> agentTaskService.complete(back.getUuid(), null, COORD, WU));
		assertTrue(e.getMessage().startsWith("Task " + back.label() + " passed, but its PR(s) were closed without merging: "
				+ url), e.getMessage());
		assertEquals(back.getStatusHistory().size(), reload(back).getStatusHistory().size());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.ALERT, "closed without merging: " + url).size(),
				"no repeat ALERT");
	}

	/**
	 * RD3-16: the DELIVERING shortcut completes over a unit it cannot see merge, not over one it knows will
	 * not land. A second PR linked while delivering, closed without merging, blocks it.
	 */
	@Test
	public void theDeliveringShortcutRefusesABlockedUnit() throws RelizaException {
		Rig r = rig(false);
		String open = newPrUrl();
		String closed = newPrUrl();
		pr(r, open, PullRequestState.OPEN);
		pr(r, closed, PullRequestState.CLOSED);
		AgentTaskData d = passedWith(r, queuedCoder(r, null), open);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		agentTaskService.linkPr(d.getUuid(), closed, WU);
		AgentTaskData delivering = reload(d);
		assertEquals(TaskStatus.DELIVERING, delivering.getStatus(), "linking does not settle");
		assertEquals(AgentDeliveryService.Delivery.BLOCKED, agentDeliveryService.deliveryOf(delivering).delivery());

		RelizaException e = assertThrows(RelizaException.class,
				() -> agentTaskService.complete(d.getUuid(), "merged by hand", PERSON, false, WU));
		assertTrue(e.getMessage().contains("closed without merging: " + closed), e.getMessage());
		assertEquals(delivering.getStatusHistory().size(), reload(d).getStatusHistory().size());
		assertEquals(TaskStatus.DELIVERING, reload(d).getStatus());
	}
}
