/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.PullRequestData;
import io.reliza.model.RearmSpecificationType;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.App;

/**
 * COMPLETED means delivered (task 9af9d722, architecture-1 §4 tests 1-7): a task whose roles have
 * passed waits in DELIVERING until its linked PRs are merged. PR rows are written through the same
 * upsert CI uses, {@code PullRequestService.applyFromInput}.
 *
 * <p>This class: PR rows and events, completion paths and reopen. Fixtures in {@link AgentDeliveryTestBase}.
 *
 * <p>One file per topic: add a test to the class for its topic, or to a new class extending the base, never at the
 * end of a class other tasks are appending to. Task RD4-15 split the old {@code AgentDeliveryIntegrationTest} this
 * way, because tasks appending to its end made each other's PRs conflict at the closing brace; the methods kept
 * their names.
 */
public class AgentDeliveryIntegrationTest extends AgentDeliveryTestBase {

	@Test
	public void aTaskWithAnOpenPrDelivers() throws RelizaException {
		Rig r = rig(false);
		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN);
		AgentTaskData a = queuedCoder(r, null);
		AgentTaskData dependent = queuedCoder(r, List.of(a.getUuid()));

		AgentTaskData d = passedWith(r, a, url);

		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		assertEquals(StatusTrigger.DELIVER_WAIT, last(d).trigger());
		assertNull(d.getCompletedAt());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, "waiting for 1 PR(s) to merge: " + url).size());
		assertFalse(agentTaskService.dependenciesMet(reload(dependent)), "its dependent waits for merged code");
	}

	@Test
	public void thePrMergeEventCompletesIt() throws RelizaException {
		Rig r = rig(false);
		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN);
		AgentTaskData a = queuedCoder(r, null);
		AgentTaskData dependent = queuedCoder(r, List.of(a.getUuid()));
		passedWith(r, a, url + "/");         // linked with a trailing slash: matched all the same

		pr(r, url, PullRequestState.MERGED);

		AgentTaskData done = reload(a);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(StatusTrigger.DELIVERED, last(done).trigger());
		assertNotNull(done.getCompletedAt());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, "delivered").size());
		// The feed names the task by its key and title, not its source reference (RD2-22).
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.INFO, "Task " + done.label() + " delivered").size(),
				agentBoardService.recentEvents(board(r).getUuid()).toString());
		assertTrue(agentTaskService.dependenciesMet(reload(dependent)), "and now its dependent may start");
	}

	@Test
	public void aClosedPrGoesToTheCoordinator() throws RelizaException {
		Rig r = rig(false);
		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN);
		AgentTaskData a = passedWith(r, queuedCoder(r, null), url);
		assertEquals(TaskStatus.DELIVERING, a.getStatus());

		pr(r, url, PullRequestState.CLOSED);

		AgentTaskData back = reload(a);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, back.getStatus());
		assertEquals(StatusTrigger.PR_CLOSED, last(back).trigger());
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.ALERT, "closed without merging: " + url).size());
	}

	/**
	 * The database filters the org's PRs by the normalised endpoint (9af9d722 T-1). The linked URL
	 * matches its row however it is spelled, and the unrelated rows around it are not read as it.
	 */
	@Test
	public void aLinkedUrlFindsItsPrAmongManyWhateverTheSpelling() throws RelizaException {
		Rig r = rig(false);
		for (int i = 0; i < 25; i++) pr(r, newPrUrl(), PullRequestState.OPEN);
		String url = newPrUrl();
		pr(r, url, PullRequestState.MERGED);
		for (int i = 0; i < 25; i++) pr(r, newPrUrl(), PullRequestState.CLOSED);

		AgentTaskData t = queuedCoder(r, null);
		String spelled = url.replace("https://github.com/acme/app", "HTTPS://GitHub.com/Acme/App") + "/?tab=files#top";
		agentTaskService.linkPr(t.getUuid(), spelled, WU);
		List<AgentDeliveryService.TaskPullRequest> prs = agentDeliveryService.pullRequestsOf(reload(t));
		assertEquals(1, prs.size());
		assertTrue(prs.get(0).registered(), "matched despite case, a trailing slash, a query and a fragment");
		assertEquals(PullRequestState.MERGED, prs.get(0).state());
		assertEquals(spelled, prs.get(0).url(), "reported as linked");

		// And the other way round: CI registered the endpoint oddly spelled, the link is plain.
		String plain = newPrUrl();
		pr(r, plain.replace("https://github.com", "https://GitHub.com") + "/", PullRequestState.OPEN);
		AgentTaskData u = queuedCoder(r, null);
		agentTaskService.linkPr(u.getUuid(), plain, WU);
		AgentDeliveryService.TaskPullRequest other = agentDeliveryService.pullRequestsOf(reload(u)).get(0);
		assertTrue(other.registered(), "the stored endpoint is normalised in the query too");
		assertEquals(PullRequestState.OPEN, other.state());
	}

	/**
	 * The GraphQL read batches every task's linked PRs into one lookup per org (9af9d722 T-1): the
	 * loader answers each key, in order, from a single read, and an unregistered URL is empty.
	 */
	@Test
	public void theBatchLoaderAnswersEveryTasksKeysFromOneRead() throws Exception {
		Rig r = rig(false);
		String a = newPrUrl(), b = newPrUrl(), c = newPrUrl();
		pr(r, a, PullRequestState.OPEN);
		pr(r, b, PullRequestState.MERGED);
		pr(r, c, PullRequestState.CLOSED);
		String unregistered = newPrUrl();
		UUID org = r.org().getUuid();
		List<io.reliza.ws.AgentTaskDataFetcher.TaskPrKey> keys = List.of(
				new io.reliza.ws.AgentTaskDataFetcher.TaskPrKey(org, AgentDeliveryService.matchKey(b)),
				new io.reliza.ws.AgentTaskDataFetcher.TaskPrKey(org, AgentDeliveryService.matchKey(unregistered)),
				new io.reliza.ws.AgentTaskDataFetcher.TaskPrKey(org, AgentDeliveryService.matchKey(a + "/")),
				new io.reliza.ws.AgentTaskDataFetcher.TaskPrKey(org, AgentDeliveryService.matchKey(c)));
		List<java.util.Optional<PullRequestData>> rows = taskPullRequestLoader
				.load(keys).toCompletableFuture().get();
		assertEquals(4, rows.size());
		assertEquals(PullRequestState.MERGED, rows.get(0).orElseThrow().getState());
		assertTrue(rows.get(1).isEmpty(), "an unregistered URL has no row");
		assertEquals(PullRequestState.OPEN, rows.get(2).orElseThrow().getState());
		assertEquals(PullRequestState.CLOSED, rows.get(3).orElseThrow().getState());
	}

	@Test
	public void noPrsCompletesAsBefore() throws RelizaException {
		Rig r = rig(false);
		AgentTaskData done = passedWith(r, queuedCoder(r, null));
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(StatusTrigger.COMPLETE, last(done).trigger());
	}

	@Test
	public void anUnregisteredPrWaits() throws RelizaException {
		Rig r = rig(false);
		String url = newPrUrl();          // no PR row: CI never reported it
		AgentTaskData d = passedWith(r, queuedCoder(r, null), url);
		assertEquals(TaskStatus.DELIVERING, d.getStatus(), "an unreported PR is treated as open, never as merged");
		AgentDeliveryService.TaskPullRequest seen = agentDeliveryService.pullRequestsOf(d).get(0);
		assertFalse(seen.registered());
		assertNull(seen.state());
	}

	@Test
	public void aPersonsCompleteDeliversToo() throws RelizaException {
		Rig r = rig(false);
		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN);
		AgentTaskData t = queuedCoder(r, null);
		agentTaskService.linkPr(t.getUuid(), url, WU);

		AgentTaskData d = agentTaskService.complete(t.getUuid(), "the coder is not needed", PERSON, true, WU);
		assertEquals(TaskStatus.DELIVERING, d.getStatus(), "skipping roles is not skipping delivery");

		// Merged by hand where CI does not report: the person completes it outright.
		AgentTaskData done = agentTaskService.complete(t.getUuid(), "merged by hand", PERSON, false, WU);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(StatusTrigger.COMPLETE, last(done).trigger());
		assertEquals("merged by hand", last(done).note());
	}

	@Test
	public void policyCompletionDeliversToo() throws RelizaException {
		Rig r = rig(true);
		// P2 routes (blocking 2) but does not stop completion (completion 1).
		agentBoardService.updateSettings(r.board().getUuid(), null, null, null, null, 2, 1, WU);
		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN);
		AgentTaskData t = queuedCoder(r, null);
		agentTaskService.linkPr(t.getUuid(), url, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.REJECTED, "asks",
				List.of(question(r, t)), WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "answered", WU);
		assertEquals("coder", reload(t).getRole());
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);

		AgentTaskData d = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.REJECTED,
				"asks again", List.of(question(r, t)), WU);

		assertEquals(TaskStatus.DELIVERING, d.getStatus(), "no progress, completed under policy -- once its PR merges");
		assertEquals(1, events(r, AgentBoardData.BoardEventKind.ALERT, "passed under policy").size());
	}

	/** A P2 question about the design, reworded each time so it is a new round asking the same id. */
	private UUID question(Rig r, AgentTaskData t) throws RelizaException {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", "q1");
		item.put("priority", 2);
		item.put("status", "OPEN");
		item.put("title", "which is right? " + UUID.randomUUID());
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", "BOARD_QUESTIONS");
		idx.put("verdict", "REJECTED");
		idx.put("reviewItems", new ArrayList<>(List.of(item)));
		idx.put("about", new LinkedHashMap<>(Map.of("specification", RearmSpecificationType.ARCHITECTURE.name())));
		return agentDocumentService.publish(r.session(), new PublishRequest(t.getUuid(), RearmSpecificationType.BOARD_QUESTIONS,
				null, null, null, null, null, null, idx, null, null, null, null, null), WU).getUuid();
	}

	@Test
	public void reopenFromDelivering() throws RelizaException {
		Rig r = rig(false);
		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN);
		AgentTaskData d = passedWith(r, queuedCoder(r, null), url);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());

		AgentTaskData reopened = agentTaskService.reopen(d.getUuid(), "coder", "the PR conflicts after later merges",
				COORD, WU);
		assertEquals(TaskStatus.QUEUED, reopened.getStatus());
		assertEquals(StatusTrigger.REOPEN, last(reopened).trigger());
		assertEquals(List.of(url), reopened.getPrUrls(), "the PR stays linked; the coder pushes to it or links another");
	}

	@Test
	public void theTickCatchesAMissedEvent() throws RelizaException {
		Rig r = rig(false);
		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN);
		AgentTaskData d = passedWith(r, queuedCoder(r, null), url);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());

		// The merge lands without the upsert's event: the row changes directly.
		PullRequestData row = pullRequestService.listByOrg(r.org().getUuid()).stream()
				.filter(p -> null != p.getEndpoint() && url.equals(p.getEndpoint().toString())).findFirst().orElseThrow();
		row.setState(PullRequestState.MERGED);
		pullRequestService.updatePullRequest(row, WU);
		assertEquals(TaskStatus.DELIVERING, reload(d).getStatus(), "no event, no move");

		agentDeliveryService.sweep(WU);
		assertEquals(TaskStatus.COMPLETED, reload(d).getStatus());
		assertEquals(StatusTrigger.DELIVERED, last(reload(d)).trigger());
		agentDeliveryService.sweep(WU);
		assertEquals(TaskStatus.COMPLETED, reload(d).getStatus(), "idempotent");
	}
}
