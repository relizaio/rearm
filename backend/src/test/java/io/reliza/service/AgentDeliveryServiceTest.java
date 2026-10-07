/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.common.Utils;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentBoardData.DeliveryMode;
import io.reliza.model.AgentBoardData.DeliveryPolicy;
import io.reliza.service.AgentDeliveryService.Delivery;
import io.reliza.service.AgentDeliveryService.TaskPullRequest;
import io.reliza.service.AgentDeliveryService.Verdict;

/** The two pure rules of delivery (task 9af9d722): how a PR URL is matched, and what a set of PRs means. */
public class AgentDeliveryServiceTest {

	@Test
	public void urlNormalisation() {
		String canonical = "https://github.com/relizaio/rearm-saas/pull/593";
		for (String variant : List.of(canonical, "HTTPS://GitHub.com/relizaio/rearm-saas/pull/593",
				canonical + "/", canonical + "//", "  " + canonical + "  ", canonical + "?w=1", canonical + "#discussion")) {
			assertEquals(canonical, AgentDeliveryService.normalise(variant), variant);
		}
		assertEquals("https://git.example.com:8443/team/app", AgentDeliveryService.normalise("https://git.example.com:8443/team/app.git"));
		assertEquals("https://github.com/Acme/App/pull/1", AgentDeliveryService.normalise("https://github.com/Acme/App/pull/1"),
				"the path keeps its case");
		assertEquals(null, AgentDeliveryService.normalise("  "));
	}

	private static TaskPullRequest pr(PullRequestState state) {
		return new TaskPullRequest("u", state, null, null, null != state);
	}

	@Test
	public void theDeliveryRule() {
		assertEquals(Delivery.DELIVERED, AgentDeliveryService.delivery(List.of()), "no PR: completes as before");
		assertEquals(Delivery.DELIVERED, AgentDeliveryService.delivery(List.of(pr(PullRequestState.MERGED), pr(PullRequestState.MERGED))));
		assertEquals(Delivery.WAITING, AgentDeliveryService.delivery(List.of(pr(PullRequestState.MERGED), pr(PullRequestState.OPEN))));
		assertEquals(Delivery.WAITING, AgentDeliveryService.delivery(List.of(pr(null))), "unregistered waits, never merged");
		assertEquals(Delivery.BLOCKED, AgentDeliveryService.delivery(List.of(pr(PullRequestState.OPEN), pr(PullRequestState.CLOSED))),
				"a closed PR blocks even beside an open one");
	}

	/**
	 * Every declare command the waiting INFO prints can be run as printed (task 18c5c293, T-2 of round
	 * 1): the task's own uuid, not a placeholder, and the --session the verb requires.
	 */
	@Test
	public void theWaitingInfosDeclareCommandsAreRunnableAsPrinted() {
		UUID task = UUID.randomUUID();
		TaskPullRequest unregistered = new TaskPullRequest("https://github.com/o/r/pull/1", null, null, null, false);
		String command = "rearm agent task declare-delivery " + task + " --session <seat-session> --unit ";
		String none = AgentDeliveryService.waitingInfo("t", task,
				new Verdict(Delivery.WAITING, List.of(), new DeliveryPolicy(DeliveryMode.NONE, true, null), null));
		String declared = AgentDeliveryService.waitingInfo("t", task,
				new Verdict(Delivery.WAITING, List.of(unregistered), new DeliveryPolicy(DeliveryMode.DECLARED, false, null), null));
		String rows = AgentDeliveryService.waitingInfo("t", task,
				new Verdict(Delivery.WAITING, List.of(unregistered), DeliveryPolicy.DEFAULT, null));
		for (String info : List.of(none, declared, rows)) {
			assertTrue(info.contains(command), info);
			assertFalse(info.contains("<task>"), "the INFO knows the task: " + info);
		}
		assertTrue(none.contains(command + "<branch or release> --commit <sha>"), none);
		assertTrue(declared.contains(command + "<pr> --commit <merge sha>"), declared);
		assertTrue(rows.contains(command + "<pr> --commit <merge sha>, or set delivery.mode: DECLARED"), rows);
		// A person is sent to the page first (RD2-10); the command stays for agents.
		assertTrue(none.contains("on the task page (Declare delivery), or with " + command), none);
		assertTrue(declared.contains("on the task page (Declare merge), or with " + command), declared);
		assertTrue(rows.contains("declare it on the task page (Declare merge), or with " + command), rows);
	}

	private static TaskPullRequest declared(PullRequestState state, AgentTaskData.DeliveryOutcome outcome) {
		return new TaskPullRequest("u", state, null, null, true, null, new AgentTaskData.Delivery("u", null,
				outcome, null, ZonedDateTime.now(), null, AgentTaskData.DeliveryOutcome.SUPERSEDED == outcome
						? "https://github.com/acme/app/pull/2" : null));
	}

	/** Task RD3-13: a superseded unit is neither pending nor blocked; the rest decide, as if it were not linked. */
	@Test
	public void aSupersededUnitCountsAsAbsent() {
		TaskPullRequest superseded = declared(PullRequestState.CLOSED, AgentTaskData.DeliveryOutcome.SUPERSEDED);
		assertEquals(Delivery.SUPERSEDED, superseded.unit(), "closed, but declared superseded: not BLOCKED");
		assertEquals(Delivery.DELIVERED, AgentDeliveryService.delivery(List.of(superseded, pr(PullRequestState.MERGED))));
		assertEquals(Delivery.WAITING, AgentDeliveryService.delivery(List.of(superseded, pr(PullRequestState.OPEN))));
		assertEquals(Delivery.BLOCKED, AgentDeliveryService.delivery(List.of(superseded, pr(PullRequestState.CLOSED))),
				"another closed PR still blocks");
		assertEquals(Delivery.SUPERSEDED, declared(PullRequestState.MERGED,
				AgentTaskData.DeliveryOutcome.SUPERSEDED).unit(), "a superseded PR that merged stays superseded");
		Verdict v = new Verdict(Delivery.DELIVERED, List.of(superseded, pr(PullRequestState.MERGED)));
		assertTrue(v.units(Delivery.BLOCKED).isEmpty() && v.units(Delivery.WAITING).isEmpty());
		assertFalse(v.abandoned());
	}

	/** Task RD3-13: two PRs of one repository share a key; another repository does not. */
	@Test
	public void aPrsRepository() {
		assertEquals(AgentDeliveryService.repositoryOf("https://github.com/relizaio/rearm-saas/pull/692"),
				AgentDeliveryService.repositoryOf("HTTPS://GitHub.com/relizaio/rearm-saas/pull/694/"));
		assertEquals("https://github.com/relizaio/rearm-saas", AgentDeliveryService.repositoryOf("https://github.com/relizaio/rearm-saas/pull/692"));
		assertFalse(AgentDeliveryService.repositoryOf("https://github.com/relizaio/rearm-saas/pull/692")
				.equals(AgentDeliveryService.repositoryOf("https://github.com/relizaio/terraform-provider-rearm/pull/14")));
		assertEquals(null, AgentDeliveryService.repositoryOf(" "));
	}

	/** Task RD3-13: a declaration stored before supersededBy existed still reads, with it null. */
	@Test
	public void anOldDeclarationStillReads() {
		Map<String, Object> old = new HashMap<>();
		old.put("unit", "https://github.com/acme/app/pull/1");
		old.put("outcome", "ABANDONED");
		old.put("note", "gone");
		AgentTaskData.Delivery d = Utils.OM.convertValue(old,
				AgentTaskData.Delivery.class);
		assertEquals(AgentTaskData.DeliveryOutcome.ABANDONED, d.outcome());
		assertEquals(null, d.supersededBy());
		AgentTaskData.Delivery round = Utils.OM.convertValue(
				Utils.OM.convertValue(declared(PullRequestState.CLOSED,
						AgentTaskData.DeliveryOutcome.SUPERSEDED).declaration(), Map.class),
				AgentTaskData.Delivery.class);
		assertEquals("https://github.com/acme/app/pull/2", round.supersededBy());
	}
}
