/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.AgentCapability;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.dto.SceDto;

/**
 * Registered PRs are consulted (task RD4-2): a linked PR is in play when this organization has a pull-request row
 * for it. For PRs in play the hop records heads and bases at assignment; a PASSED sign-off by a role holding
 * CODE_PUSH is refused while no linked PR moved, unless it says --no-code; the task's PR rows count how far the
 * base moved since the round. A PR not in play behaves as before in every path. Fixtures in
 * {@link AgentDeliveryTestBase}; the PR rows here carry heads, and the base branch carries entries.
 */
public class AgentRegisteredPrsIntegrationTest extends AgentDeliveryTestBase {

	@Autowired SourceCodeEntryService sourceCodeEntryService;
	@Autowired BranchService branchService;

	private static final String A = "aaaaaaa1111111111111111111111111111111aa";
	private static final String B = "bbbbbbb2222222222222222222222222222222bb";
	private static final String MOVED = "moved since your assignment";

	/** A rig whose coder pushes code (CODE_PUSH), and a component branch its commits are recorded on. */
	private record Pushing(Rig r, Branch branch) {}

	private Pushing pushing(boolean codePush) throws RelizaException {
		Rig r = rig(false);
		if (codePush) {
			agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("coder", "build", 20,
					null, false, true, List.of(AgentCapability.CODE_PUSH), null, RoleNecessity.REQUIRED, null, List.of(),
					List.of(), null, null), true, WU);
		}
		Component app = componentService.createComponent("app_" + UUID.randomUUID(), r.org().getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		return new Pushing(r, branchService.getBaseBranchOfComponent(app.getUuid()).orElseThrow());
	}

	/** A commit on the PR's repository and a branch of it: a PR head on a feature branch, a merge on main. */
	private UUID commit(Pushing p, String sha, String vcsBranch) throws RelizaException {
		return sourceCodeEntryService.createSourceCodeEntry(SceDto.builder().branch(p.branch().getUuid())
				.vcs(p.r().vcs()).commit(sha).vcsBranch(vcsBranch).organizationUuid(p.r().org().getUuid()).build(), WU)
				.getUuid();
	}

	private static String sha() {
		return (UUID.randomUUID().toString() + UUID.randomUUID().toString()).replace("-", "").substring(0, 40);
	}

	private final Map<String, UUID> heads = new HashMap<>();

	/** CI registers the PR (target main), at {@code head} when given. */
	private void ci(Pushing p, String url, PullRequestState state, String head) throws RelizaException {
		Map<String, Object> in = new HashMap<>();
		String bare = url.replaceAll("/+$", "");
		in.put("identity", bare.substring(bare.lastIndexOf('/') + 1));
		in.put("state", state.name());
		in.put("endpoint", url);
		in.put("title", "change");
		in.put("targetBranchName", "main");
		UUID sce = null;
		if (null != head) {
			sce = heads.get(p.r().vcs() + "/" + head);
			if (null == sce) {
				sce = commit(p, head, "rd-feature");
				heads.put(p.r().vcs() + "/" + head, sce);
			}
		}
		assertTrue(pullRequestService.applyFromInput(in, p.r().org().getUuid(), sce, p.r().vcs(), WU).isPresent());
	}

	private AgentTaskData assigned(Pushing p, String... linkedBefore) throws RelizaException {
		AgentTaskData t = queuedCoder(p.r(), null);
		for (String u : linkedBefore) agentTaskService.linkPr(t.getUuid(), u, WU);
		agentTaskService.assign(t.getUuid(), board(p.r()), p.r().worker().getUuid(), p.r().session().getUuid(), WU);
		return reload(t);
	}

	private AgentTaskData pass(Pushing p, AgentTaskData t, Boolean noCode) throws RelizaException {
		return agentTaskService.signOff(t.getUuid(), p.r().session().getUuid(), SignOffOutcome.PASSED, "done", List.of(),
				null, null, noCode, WU);
	}

	private Integer baseMovedBy(AgentTaskData t, String url) {
		return agentDeliveryService.baseMovedBy(AgentDeliveryService.roundBase(reload(t), url));
	}

	// ---------- the sign-off head check (design §3.2) ----------

	@Test
	public void anUnmovedHeadIsRefusedAndAPushLetsThePassThrough() throws RelizaException {
		Pushing p = pushing(true);
		String url = newPrUrl();
		ci(p, url, PullRequestState.OPEN, A);
		AgentTaskData t = assigned(p, url);
		assertEquals(A, t.getAssignment().prHeads().get(AgentDeliveryService.matchKey(url)), "the head at assignment");

		RelizaException refused = assertThrows(RelizaException.class, () -> pass(p, t, null));
		assertTrue(refused.getMessage().contains("No linked PR " + MOVED), refused.getMessage());
		assertTrue(refused.getMessage().contains("sign off with --no-code for a round that changed no code"), refused.getMessage());
		assertTrue(refused.getMessage().contains(url + " at aaaaaaa"), refused.getMessage());
		assertEquals(TaskStatus.ASSIGNED, reload(t).getStatus(), "a refusal leaves the hop where it was");

		ci(p, url, PullRequestState.OPEN, B);
		AgentTaskData passed = pass(p, t, null);
		assertEquals(TaskStatus.DELIVERING, passed.getStatus());
		assertNull(passed.getSignOffs().get(passed.getSignOffs().size() - 1).noCode());
	}

	@Test
	public void aNoteOnlyRoundSignsOffWithNoCodeAndItIsRecorded() throws RelizaException {
		Pushing p = pushing(true);
		String url = newPrUrl();
		ci(p, url, PullRequestState.OPEN, A);
		AgentTaskData t = assigned(p, url);
		RelizaException onRejection = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				p.r().session().getUuid(), SignOffOutcome.REJECTED, "asks", List.of(), null, null, true, WU));
		assertTrue(onRejection.getMessage().contains("--no-code says a passed round changed no code"), onRejection.getMessage());

		AgentTaskData passed = pass(p, t, true);
		assertEquals(TaskStatus.DELIVERING, passed.getStatus());
		assertEquals(Boolean.TRUE, passed.getSignOffs().get(passed.getSignOffs().size() - 1).noCode());
	}

	// ---------- a PR linked during the hop (design round 2 §1) ----------

	@Test
	public void aPrOpenedDuringTheHopAndLinkedCountsAsMoved() throws RelizaException {
		Pushing p = pushing(true);
		String old = newPrUrl();
		String fresh = newPrUrl();
		ci(p, old, PullRequestState.OPEN, A);
		AgentTaskData t = assigned(p, old);
		// the hop pushes a new branch and opens a PR, CI reports it, and the hop links it
		ci(p, fresh, PullRequestState.OPEN, B);
		agentTaskService.linkPr(t.getUuid(), fresh, WU);
		AgentTaskData linked = reload(t);
		String key = AgentDeliveryService.matchKey(fresh);
		assertEquals(B, linked.getAssignment().linkedHeads().get(key), "the head at link time");
		assertTrue(!linked.getAssignment().prHeads().containsKey(key), "not a head the hop started from");
		assertNotNull(linked.getAssignment().baseHeads().get(key), "it records the base it starts from");

		// the old PR sat still, but the fresh PR is this hop's work
		assertEquals(TaskStatus.DELIVERING, pass(p, t, null).getStatus());
	}

	@Test
	public void aPrOpenedBeforeTheHopAndLinkedWithoutAPushDoesNotCount() throws RelizaException {
		Pushing p = pushing(true);
		String old = newPrUrl();
		String stale = newPrUrl();
		ci(p, old, PullRequestState.OPEN, A);
		ci(p, stale, PullRequestState.OPEN, B);
		AgentTaskData t = assigned(p, old);
		agentTaskService.linkPr(t.getUuid(), stale, WU);
		assertEquals(B, reload(t).getAssignment().linkedHeads().get(AgentDeliveryService.matchKey(stale)));

		RelizaException refused = assertThrows(RelizaException.class, () -> pass(p, t, null));
		assertTrue(refused.getMessage().contains("No linked PR " + MOVED), refused.getMessage());
		assertTrue(refused.getMessage().contains("(" + old + " at aaaaaaa)."), refused.getMessage());
		assertTrue(refused.getMessage().endsWith(" The PR you linked predates your assignment and has not moved: " + stale
				+ " at bbbbbbb."), refused.getMessage());
		assertEquals(TaskStatus.ASSIGNED, reload(t).getStatus());

		// linking it again does not move the head it was linked at
		agentTaskService.linkPr(t.getUuid(), stale, WU);
		assertThrows(RelizaException.class, () -> pass(p, t, null));
	}

	@Test
	public void aPrLinkedAndThenPushedCounts() throws RelizaException {
		Pushing p = pushing(true);
		String stale = newPrUrl();
		ci(p, stale, PullRequestState.OPEN, B);
		// no PR linked at assignment: the heads are recorded, and empty
		AgentTaskData t = assigned(p);
		assertTrue(t.getAssignment().prHeads().isEmpty());
		agentTaskService.linkPr(t.getUuid(), stale, WU);
		RelizaException refused = assertThrows(RelizaException.class, () -> pass(p, t, null));
		assertTrue(refused.getMessage().endsWith("changed no code. The PR you linked predates your assignment and has not"
				+ " moved: " + stale + " at bbbbbbb."), refused.getMessage());

		ci(p, stale, PullRequestState.OPEN, sha());
		// a second link after the push keeps the head it was first linked at
		agentTaskService.linkPr(t.getUuid(), stale, WU);
		assertEquals(TaskStatus.DELIVERING, pass(p, t, null).getStatus());
	}

	@Test
	public void aSupersededPrIsIgnoredByTheHeadCheck() throws RelizaException {
		Pushing p = pushing(true);
		String old = newPrUrl();
		String replacement = newPrUrl();
		String elsewhere = newPrUrl();
		ci(p, old, PullRequestState.OPEN, A);
		ci(p, replacement, PullRequestState.OPEN, B);
		AgentTaskData t = assigned(p, old, replacement, elsewhere);
		// the old PR is closed unmerged and declared superseded; a superseded PR cannot move
		ci(p, old, PullRequestState.CLOSED, A);
		agentTaskService.supersedePullRequest(t.getUuid(), old, replacement, null, PERSON, WU);

		RelizaException refused = assertThrows(RelizaException.class, () -> pass(p, t, null));
		assertTrue(refused.getMessage().contains("(" + replacement + " at bbbbbbb)."), refused.getMessage());
		assertTrue(!refused.getMessage().contains(old + " at"), "the superseded PR is not named: " + refused.getMessage());

		// replaced by a PR not in play, the superseded PR alone leaves nothing to check
		Pushing q = pushing(true);
		String closed = newPrUrl();
		String unreported = newPrUrl();
		ci(q, closed, PullRequestState.OPEN, A);
		AgentTaskData u = assigned(q, closed, unreported);
		ci(q, closed, PullRequestState.CLOSED, A);
		agentTaskService.supersedePullRequest(u.getUuid(), closed, unreported, null, PERSON, WU);
		assertEquals(TaskStatus.DELIVERING, pass(q, u, null).getStatus());
	}

	@Test
	public void aPrRegisteredDuringTheHopCountsAsMoved() throws RelizaException {
		Pushing p = pushing(true);
		String url = newPrUrl();
		AgentTaskData t = assigned(p, url);
		assertTrue(t.getAssignment().prHeads().isEmpty(), "not in play at assignment");
		// CI reports the PR for the first time during the hop: the hop's push
		ci(p, url, PullRequestState.OPEN, A);
		assertEquals(TaskStatus.DELIVERING, pass(p, t, null).getStatus());
	}

	@Test
	public void aHopWithNoPrInPlayIsNotChecked() throws RelizaException {
		Pushing p = pushing(true);
		// unregistered: CI reports elsewhere, as on the dogfood boards
		String elsewhere = newPrUrl();
		AgentTaskData t = assigned(p, elsewhere);
		assertEquals(TaskStatus.DELIVERING, pass(p, t, null).getStatus());

		// registered without a head: nothing to compare
		Pushing q = pushing(true);
		String headless = newPrUrl();
		ci(q, headless, PullRequestState.OPEN, null);
		AgentTaskData u = assigned(q, headless);
		assertEquals(TaskStatus.DELIVERING, pass(q, u, null).getStatus());

		// no PR at all
		Pushing s = pushing(true);
		AgentTaskData v = assigned(s);
		assertEquals(TaskStatus.COMPLETED, pass(s, v, null).getStatus());
	}

	@Test
	public void aRoleThatDoesNotPushCodeIsNotChecked() throws RelizaException {
		Pushing p = pushing(false);
		String url = newPrUrl();
		ci(p, url, PullRequestState.OPEN, A);
		AgentTaskData t = assigned(p, url);
		assertEquals(TaskStatus.DELIVERING, pass(p, t, null).getStatus());
	}

	@Test
	public void anAssignmentRecordedBeforeTheHeadsIsNotChecked() throws RelizaException {
		Pushing p = pushing(true);
		String url = newPrUrl();
		ci(p, url, PullRequestState.OPEN, A);
		AgentTaskData t = assigned(p, url);
		// as stored before task RD4-2: no heads on the assignment
		AgentTaskData.TaskAssignment a = t.getAssignment();
		t.setAssignment(a.withHeads(null, null));
		agentTaskService.saveData(t, WU);
		assertEquals(TaskStatus.DELIVERING, pass(p, t, null).getStatus());
	}

	// ---------- base moved since your round (design §3.3) ----------

	@Test
	public void baseMovedCountsNewerEntriesOnTheTargetBranchAndSurvivesTheSignOff() throws RelizaException {
		Pushing p = pushing(true);
		String url = newPrUrl();
		commit(p, sha(), "main");
		ci(p, url, PullRequestState.OPEN, A);
		AgentTaskData t = assigned(p, url);
		AgentTaskData.BaseHead base = t.getAssignment().baseHeads().get(AgentDeliveryService.matchKey(url));
		assertNotNull(base.commit(), "the newest entry on main");
		assertEquals("main", base.branch());
		assertEquals(0, baseMovedBy(t, url), "nothing newer: zero, not null");

		// a sibling merges twice onto main, and CI reports both builds
		commit(p, sha(), "main");
		commit(p, sha(), "main");
		// a push to the PR's own branch is not the base
		ci(p, url, PullRequestState.OPEN, B);
		assertEquals(2, baseMovedBy(t, url));

		AgentTaskData passed = pass(p, t, null);
		assertNull(passed.getAssignment());
		assertEquals(2, baseMovedBy(passed, url), "the sign-off keeps the round's base");
	}

	@Test
	public void baseMovedIsNullWithoutEntriesAndCountsFromNothingOnceThereAreSome() throws RelizaException {
		Pushing p = pushing(false);
		String url = newPrUrl();
		ci(p, url, PullRequestState.OPEN, A);
		AgentTaskData t = assigned(p, url);
		AgentTaskData.BaseHead base = t.getAssignment().baseHeads().get(AgentDeliveryService.matchKey(url));
		assertNull(base.commit(), "main had no entry at assignment");
		assertNull(baseMovedBy(t, url), "no entry at all is unknown, never zero");
		commit(p, sha(), "main");
		assertEquals(1, baseMovedBy(t, url));
	}

	@Test
	public void aPrNotInPlayHasNoBase() throws RelizaException {
		Pushing p = pushing(true);
		String url = newPrUrl();
		commit(p, sha(), "main");
		AgentTaskData t = assigned(p, url);
		assertTrue(t.getAssignment().baseHeads().isEmpty());
		assertNull(AgentDeliveryService.roundBase(t, url));
		assertNull(agentDeliveryService.baseMovedBy(null));
	}

	// ---------- stale deliveries read the row (design §3.4) ----------

	@Autowired BoardStalenessService staleness;

	@Test
	public void aStuckDeliveryNamesOnlyThePrsInPlay() throws RelizaException {
		Pushing p = pushing(false);
		Map<String, Object> settings = new HashMap<>();
		settings.put("staleness", Map.of("deliveryStuckMinutes", 120));
		agentBoardService.updateSettingsFromInput(p.r().board().getUuid(), settings, WU);
		String reported = newPrUrl();
		String elsewhere = newPrUrl();
		ci(p, reported, PullRequestState.OPEN, A);
		AgentTaskData t = assigned(p, reported, elsewhere);
		AgentTaskData d = pass(p, t, null);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());

		java.time.ZonedDateTime later = java.time.ZonedDateTime.now().plusMinutes(121);
		List<BoardStalenessService.Breach> b = staleness.breaches(board(p.r()), board(p.r()).getStaleness(), later);
		assertEquals(1, b.size(), b.toString());
		assertEquals(d.label() + " delivery stuck: " + reported + " open for 2 h", b.get(0).message(),
				"the unregistered PR is not named");

		// the registered PR merges; the task waits on the unregistered one alone, and the rule falls silent
		ci(p, reported, PullRequestState.MERGED, A);
		assertEquals(TaskStatus.DELIVERING, reload(t).getStatus());
		assertTrue(staleness.breaches(board(p.r()), board(p.r()).getStaleness(), later).isEmpty());
	}

	// ---------- supersede reads the row (design §3.4) ----------

	@Test
	public void aRegisteredPrClosedUnmergedIsSupersededFromItsRow() throws RelizaException {
		Pushing p = pushing(true);
		String old = newPrUrl();
		String replacement = newPrUrl();
		ci(p, old, PullRequestState.CLOSED, A);
		ci(p, replacement, PullRequestState.OPEN, B);
		AgentTaskData t = assigned(p, old, replacement);
		AgentTaskData declared = agentTaskService.supersedePullRequest(t.getUuid(), old, replacement, null, PERSON, WU);
		AgentTaskData.Delivery d = declared.getDeliveries().get(declared.getDeliveries().size() - 1);
		assertEquals(AgentTaskData.DeliveryOutcome.SUPERSEDED, d.outcome());
		assertNull(d.observed(), "read from the row, not from a tracker");
	}
}
