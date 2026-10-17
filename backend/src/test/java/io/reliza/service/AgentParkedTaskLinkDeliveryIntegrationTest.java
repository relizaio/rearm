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
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HoldLink;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;

/**
 * Task RD4-19: a PR linked to a task the coordinator seat parked for the operator is preparation for the person's
 * decision, not the decision. The link is accepted, recorded on the hold and posted as an INFO; the hold stays, and
 * the person's supersede that follows is the answer, which keeps the link on its row. The programmatic link (the
 * linking key's agent, the seat's own key, an unparked task, a hop's own hold) is
 * {@code io.reliza.ws.AgentParkedTaskLinkTest}'s. Fixtures in {@link AgentDeliveryTestBase}.
 */
public class AgentParkedTaskLinkDeliveryIntegrationTest extends AgentDeliveryTestBase {

	private static final UUID SEAT = UUID.randomUUID();
	private static final String QUESTION = "PR is closed unmerged. Options: supersede it, reopen to the coder."
			+ " Recommend: supersede.";
	private static final String LINKER = "Claude Code (agent 2ffebe1a)";
	private static final String REFUSAL = "awaiting the operator: " + QUESTION + "; the person lifts the hold or acts";

	@Test
	public void theSupersedePathRunsEndToEndOnAParkedDelivery() throws RelizaException {
		Rig r = rig(false);
		String old = newPrUrl();
		String replacement = newPrUrl();
		pr(r, old, PullRequestState.OPEN);
		AgentTaskData d = passedWith(r, queuedCoder(r, null), old);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		agentTaskService.parkForOperator(d.getUuid(), SEAT, QUESTION, WU);
		// the PR closes while a person decides: the held task is left alone, still parked from DELIVERING
		pr(r, old, PullRequestState.CLOSED);
		assertEquals(TaskStatus.ON_HOLD, reload(d).getStatus());
		int rowsBefore = reload(d).getStatusHistory().size();

		// a key links the replacement: accepted, recorded on the decision, and the hold stands
		AgentTaskData linked = agentTaskService.linkPr(d.getUuid(), replacement, LINKER, WU);
		assertEquals(TaskStatus.ON_HOLD, linked.getStatus(), "a link never answers the question");
		assertTrue(linked.seatParkedForOperator());
		assertEquals(TaskStatus.DELIVERING, linked.getHold().returnTo(), "returnTo unchanged");
		assertEquals(List.of(old, replacement), linked.getPrUrls(), "the task lists the PR");
		List<HoldLink> onHold = linked.getHold().linked();
		assertEquals(1, onHold.size());
		assertEquals(replacement, onHold.get(0).prUrl());
		assertEquals(LINKER, onHold.get(0).by());
		assertNotNull(onHold.get(0).at());
		assertEquals(rowsBefore, linked.getStatusHistory().size(), "no status row: nothing moved");
		String info = "is parked, awaiting the operator: PR " + replacement + " linked by " + LINKER
				+ "; the delivery set the person is deciding on has changed";
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, info).size(),
				agentBoardService.recentEvents(board(r).getUuid()).toString());
		assertTrue(events(r, AgentBoardData.BoardEventKind.INFO, info).get(0).startsWith("Task " + linked.label() + " "));

		// still a decision for a person: a session's supersede is refused after the link as before it
		RelizaException bySeat = assertThrows(RelizaException.class, () -> agentTaskService.supersedePullRequest(
				d.getUuid(), old, replacement, "replaced", AgentActor.ofSession(SEAT), WU));
		assertTrue(bySeat.getMessage().contains(REFUSAL), bySeat.getMessage());

		// the person's supersede is the answer; its row names the replacement and keeps what was linked
		AgentTaskData answered = agentTaskService.supersedePullRequest(d.getUuid(), old, replacement, "replaced",
				PERSON, WU);
		assertEquals(TaskStatus.DELIVERING, answered.getStatus(), "back to DELIVERING, the replacement still open");
		assertNull(answered.getHold());
		StatusChange answer = last(answered);
		assertEquals(TaskStatus.ON_HOLD, answer.from());
		assertEquals(TaskStatus.DELIVERING, answer.to());
		assertEquals(StatusTrigger.LIFT_HOLD, answer.trigger());
		assertEquals(PERSON, answer.actor());
		assertEquals("declared superseded by ops@acme.example: " + old + ", replaced by " + replacement + ": replaced",
				answer.note());
		// the record of the decision keeps the link once the hold is gone (its instant as stored, to the millisecond)
		assertEquals(1, answer.linked().size());
		assertEquals(replacement, answer.linked().get(0).prUrl());
		assertEquals(LINKER, answer.linked().get(0).by());
		assertEquals(onHold.get(0).at().toInstant().toEpochMilli(), answer.linked().get(0).at().toInstant().toEpochMilli());
		assertEquals(AgentTaskData.DeliveryOutcome.SUPERSEDED, answered.getDeliveries().get(0).outcome());
		assertEquals(replacement, answered.getDeliveries().get(0).supersededBy());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, " answered: declared superseded by ops@acme.example: "
				+ old + ", replaced by " + replacement).size());

		// the delivery settles on the replacement
		pr(r, replacement, PullRequestState.MERGED);
		AgentTaskData done = reload(d);
		assertEquals(TaskStatus.COMPLETED, done.getStatus(), done.getStatusHistory().toString());
		assertEquals(StatusTrigger.DELIVERED, last(done).trigger());
	}

	@Test
	public void aLinkOnAnUnparkedDeliveryRecordsNothingNew() throws RelizaException {
		Rig r = rig(false);
		String first = newPrUrl();
		String second = newPrUrl();
		AgentTaskData d = passedWith(r, queuedCoder(r, null), first);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		int rowsBefore = d.getStatusHistory().size();

		AgentTaskData linked = agentTaskService.linkPr(d.getUuid(), second, LINKER, WU);
		assertEquals(TaskStatus.DELIVERING, linked.getStatus());
		assertNull(linked.getHold(), "no hold to record it on");
		assertEquals(List.of(first, second), linked.getPrUrls());
		assertEquals(rowsBefore, linked.getStatusHistory().size());
		assertTrue(linked.getStatusHistory().stream().allMatch(c -> null == c.linked()));
		assertTrue(events(r, AgentBoardData.BoardEventKind.INFO, "linked by").isEmpty(), "no INFO");
	}

	@Test
	public void aPrAlreadyLinkedRecordsNothingOnTheHold() throws RelizaException {
		Rig r = rig(false);
		String url = newPrUrl();
		AgentTaskData d = passedWith(r, queuedCoder(r, null), url);
		agentTaskService.parkForOperator(d.getUuid(), SEAT, QUESTION, WU);

		AgentTaskData again = agentTaskService.linkPr(d.getUuid(), url, LINKER, WU);
		assertEquals(TaskStatus.ON_HOLD, again.getStatus());
		assertEquals(List.of(url), again.getPrUrls());
		assertNull(again.getHold().linked(), "the delivery set did not change");
		assertTrue(events(r, AgentBoardData.BoardEventKind.INFO, "linked by").isEmpty(), "no INFO");
	}
}
