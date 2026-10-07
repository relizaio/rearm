/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.PullRequestState;
import io.reliza.common.VcsType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.Branch;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.Organization;
import io.reliza.model.PullRequestData;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.SceDto;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The board knows which head a review or test passed, and notices a PR moving past it (task
 * 3b97ccfd): the index names the heads, a pass must name every linked PR, and a DELIVERING task
 * whose PR moves past its tested head goes back to the role that passed it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentTestedHeadsIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private AgentDeliveryService agentDeliveryService;
	@Autowired private PullRequestService pullRequestService;
	@Autowired private VcsRepositoryService vcsRepositoryService;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private SourceCodeEntryService sourceCodeEntryService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/tested";
	private static final String DOCS_SOURCE = "github:acme/tested-docs";
	private static final String DOCS = "https://github.com/acme/tested-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AtomicInteger ISSUE = new AtomicInteger(7700);
	private static final AtomicInteger PR_NUMBER = new AtomicInteger(1);

	private static final String A = "aaaaaaa1111111111111111111111111111111aa";
	private static final String B = "bbbbbbb2222222222222222222222222222222bb";
	private static final String C = "ccccccc3333333333333333333333333333333cc";

	private record Rig(Organization org, AgentBoardData board, UUID vcs, Branch branch, AgentSessionData session) {}

	/** A REQUIRED coder, and with {@code tester} a REQUIRED tester after it that produces BOARD_TEST_REPORT. */
	private Rig rig(boolean tester) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "tested-" + UUID.randomUUID(),
				"tested heads", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build", 20,
				null, false, true, null, null, RoleNecessity.REQUIRED, null, List.of(), List.of(), null, null), true, WU);
		if (tester) {
			agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("tester", "test", 30,
					null, false, true, null, null, RoleNecessity.REQUIRED, null, List.of(),
					List.of(new ProducedOutput(RearmSpecificationType.BOARD_TEST_REPORT, InputScope.TASK, true)),
					null, null), true, WU);
		}
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		Component app = componentService.createComponent("app_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		Branch branch = branchService.getBaseBranchOfComponent(app.getUuid()).orElseThrow();
		UUID vcs = vcsRepositoryService.provisionVcsRepository(org.getUuid(),
				"github.com/acme/tested-" + UUID.randomUUID(), VcsType.GIT, WU);
		AgentData w = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"w-" + UUID.randomUUID(), null, null, null, WU);
		return new Rig(org, board, vcs, branch, agentSessionService.initialize(org.getUuid(), w.getUuid(), null,
				"s-" + UUID.randomUUID(), "worker", null, null, WU));
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	private static String newPrUrl() {
		return "https://github.com/acme/app/pull/" + PR_NUMBER.incrementAndGet();
	}

	/** Register or update a PR the way CI does, at {@code head} when given. */
	private void pr(Rig r, String url, PullRequestState state, String head) throws RelizaException {
		Map<String, Object> in = new HashMap<>();
		String bare = url.replaceAll("/+$", "");
		in.put("identity", bare.substring(bare.lastIndexOf('/') + 1));
		in.put("state", state.name());
		in.put("endpoint", url);
		in.put("title", "change");
		in.put("targetBranchName", "main");
		assertTrue(pullRequestService.applyFromInput(in, r.org().getUuid(), null == head ? null : sce(r, head), r.vcs(),
				WU).isPresent());
	}

	private final Map<String, UUID> sces = new HashMap<>();

	/** The commit's source code entry, one per repository and sha. */
	private UUID sce(Rig r, String head) throws RelizaException {
		String key = r.vcs() + "/" + head;
		UUID had = sces.get(key);
		if (null != had) return had;
		UUID made = sourceCodeEntryService.createSourceCodeEntry(SceDto.builder().branch(r.branch().getUuid())
				.vcs(r.vcs()).commit(head).organizationUuid(r.org().getUuid()).build(), WU).getUuid();
		sces.put(key, made);
		return made;
	}

	private AgentTaskData queuedCoder(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		return agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
	}

	/** The coder takes the task, links {@code urls} and passes. */
	private AgentTaskData coded(Rig r, AgentTaskData t, String... urls) throws RelizaException {
		agentTaskService.assign(t.getUuid(), board(r), r.session().getAgent(), r.session().getUuid(), WU);
		for (String u : urls) agentTaskService.linkPr(t.getUuid(), u, WU);
		return agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done", WU);
	}

	private static Map<String, Object> tested(String pr, String head) {
		return new LinkedHashMap<>(Map.of("pr", pr, "head", head));
	}

	/** A BOARD_TEST_REPORT index with this verdict and these tested entries (none when null). */
	private static Map<String, Object> report(String verdict, List<Map<String, Object>> tested) {
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", "BOARD_TEST_REPORT");
		idx.put("verdict", verdict);
		idx.put("counts", Map.of("passed", 10, "failed", "PASSED".equals(verdict) ? 0 : 1, "skipped", 0));
		List<Map<String, Object>> reviewItems = new ArrayList<>();
		if (!"PASSED".equals(verdict)) {
			reviewItems.add(new LinkedHashMap<>(Map.of("id", "T-1", "priority", 1, "status", "OPEN",
					"title", "a case fails " + UUID.randomUUID())));
		}
		idx.put("reviewItems", reviewItems);
		if (null != tested) idx.put("tested", tested);
		return idx;
	}

	private ReleaseData publishReport(Rig r, AgentTaskData t, Map<String, Object> index) throws RelizaException {
		return agentDocumentService.publish(r.session(), new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_TEST_REPORT, null, null, null, null, null, null, index, null, null, null,
				null, null), WU);
	}

	/** The tester takes the task, publishes {@code index} and signs off with {@code outcome}. */
	private AgentTaskData testerSignsOff(Rig r, AgentTaskData t, SignOffOutcome outcome, Map<String, Object> index)
			throws RelizaException {
		agentTaskService.assign(t.getUuid(), board(r), r.session().getAgent(), r.session().getUuid(), WU);
		ReleaseData round = publishReport(r, reload(t), index);
		return agentTaskService.signOff(t.getUuid(), r.session().getUuid(), outcome, "tested",
				List.of(round.getUuid()), WU);
	}

	private List<String> alerts(Rig r, String containing) {
		return agentBoardService.recentEvents(board(r).getUuid()).stream().filter(ev -> ev.kind() == AgentBoardData.BoardEventKind.ALERT)
				.map(AgentBoardData.BoardEvent::message).filter(m -> m.contains(containing)).toList();
	}

	/** A tester's pass at head A of an open PR: the task waits in DELIVERING. */
	private AgentTaskData deliveringAtA(Rig r, String url) throws RelizaException {
		pr(r, url, PullRequestState.OPEN, A);
		AgentTaskData t = coded(r, queuedCoder(r), url);
		assertEquals("tester", t.getRole());
		AgentTaskData d = testerSignsOff(r, t, SignOffOutcome.PASSED, report("PASSED", List.of(tested(url, A.substring(0, 8)))));
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		return d;
	}

	// ---------- the index (design §4.1) ----------

	@Test
	public void aPassNamesTheHeadOfEveryLinkedPr() throws RelizaException {
		Rig r = rig(true);
		String one = newPrUrl();
		String two = newPrUrl();
		pr(r, one, PullRequestState.OPEN, A);
		pr(r, two, PullRequestState.OPEN, B);
		AgentTaskData t = coded(r, queuedCoder(r), one, two);

		agentTaskService.assign(t.getUuid(), board(r), r.session().getAgent(), r.session().getUuid(), WU);
		ReleaseData half = publishReport(r, reload(t), report("PASSED", List.of(tested(one, A))));
		assertEquals(A, half.getDocument().reviewItems().tested().get(0).head(), "tested round-trips on the release");
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.session().getUuid(), SignOffOutcome.PASSED, "tested", List.of(half.getUuid()), WU));
		assertTrue(e.getMessage().contains("say the head you tested for " + two), e.getMessage());
		assertEquals(TaskStatus.ASSIGNED, reload(t).getStatus(), "refused before anything is written");

		ReleaseData both = publishReport(r, reload(t), report("PASSED", List.of(tested(one, A), tested(two + "/", B))));
		AgentTaskData passed = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED,
				"tested", List.of(both.getUuid()), WU);
		assertEquals(TaskStatus.DELIVERING, passed.getStatus());
		AgentDeliveryService.Tested th = agentDeliveryService.testedOf(passed).orElseThrow();
		assertEquals(both.getUuid(), th.round());
		assertEquals("tester", th.role());
		assertEquals(List.of(A, B), th.heads().stream().map(BoardReviewItemIndex.TestedHead::head).toList());
	}

	@Test
	public void aTestedPrMustBeLinkedAndItsHeadASha() throws RelizaException {
		Rig r = rig(true);
		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN, A);
		AgentTaskData t = coded(r, queuedCoder(r), url);
		agentTaskService.assign(t.getUuid(), board(r), r.session().getAgent(), r.session().getUuid(), WU);

		// at publish: the shape
		RelizaException bad = assertThrows(RelizaException.class,
				() -> publishReport(r, reload(t), report("PASSED", List.of(tested(url, "not-a-sha")))));
		assertTrue(bad.getMessage().contains("7 to 40 hex characters"), bad.getMessage());
		RelizaException twice = assertThrows(RelizaException.class,
				() -> publishReport(r, reload(t), report("PASSED", List.of(tested(url, A), tested(url + "/", B)))));
		assertTrue(twice.getMessage().contains("twice"), twice.getMessage());

		// at sign-off: a PR the task does not link
		String other = newPrUrl();
		ReleaseData stray = publishReport(r, reload(t), report("PASSED", List.of(tested(url, A), tested(other, B))));
		RelizaException unlinked = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.session().getUuid(), SignOffOutcome.PASSED, "tested", List.of(stray.getUuid()), WU));
		assertTrue(unlinked.getMessage().contains("tested names " + other + ", which is not linked to this task"),
				unlinked.getMessage());
	}

	@Test
	public void testedIsForReviewAndTestRoundsOnly() throws RelizaException {
		Rig r = rig(true);
		AgentTaskData t = coded(r, queuedCoder(r));
		agentTaskService.assign(t.getUuid(), board(r), r.session().getAgent(), r.session().getUuid(), WU);
		Map<String, Object> q = new LinkedHashMap<>();
		q.put("kind", "BOARD_QUESTIONS");
		q.put("verdict", "REJECTED");
		q.put("reviewItems", new ArrayList<>(List.of(new LinkedHashMap<>(Map.of("id", "q1", "priority", 1,
				"status", "OPEN", "title", "which?")))));
		q.put("about", new LinkedHashMap<>(Map.of("specification", "ARCHITECTURE")));
		q.put("tested", List.of(tested(newPrUrl(), A)));
		RelizaException e = assertThrows(RelizaException.class, () -> agentDocumentService.publish(r.session(),
				new PublishRequest(t.getUuid(), RearmSpecificationType.BOARD_QUESTIONS, null, null, null, null, null, null,
						q, null, null, null, null, null), WU));
		assertTrue(e.getMessage().contains("it is for BOARD_TEST_REPORT and BOARD_REVIEW_ITEMS rounds"), e.getMessage());
	}

	@Test
	public void aTaskWithoutPrsAndARejectionNeedNone() throws RelizaException {
		Rig r = rig(true);
		AgentTaskData noPrs = coded(r, queuedCoder(r));
		AgentTaskData passed = testerSignsOff(r, noPrs, SignOffOutcome.PASSED, report("PASSED", null));
		assertEquals(TaskStatus.COMPLETED, passed.getStatus(), "nothing to name, nothing refused");

		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN, A);
		AgentTaskData withPr = coded(r, queuedCoder(r), url);
		AgentTaskData rejected = testerSignsOff(r, withPr, SignOffOutcome.REJECTED, report("REJECTED", null));
		assertEquals("coder", rejected.getRole(), "a rejection names nothing and goes back as before");
	}

	// ---------- a DELIVERING task's PR moves (design §4.2) ----------

	@Test
	public void aPushPastTheTestedHeadSendsTheTaskBackToTheTester() throws RelizaException {
		Rig r = rig(true);
		String url = newPrUrl();
		AgentTaskData d = deliveringAtA(r, url);
		assertEquals(A, d.getDeliveringHeads().values().iterator().next(), "the head it waits at");

		pr(r, url, PullRequestState.OPEN, A);
		assertEquals(TaskStatus.DELIVERING, reload(d).getStatus(), "the tested head again: nothing");

		pr(r, url, PullRequestState.OPEN, B);
		AgentTaskData back = reload(d);
		assertEquals(TaskStatus.QUEUED, back.getStatus());
		assertEquals("tester", back.getRole());
		AgentTaskData.StatusChange last = back.getStatusHistory().get(back.getStatusHistory().size() - 1);
		assertEquals(StatusTrigger.REOPEN, last.trigger());
		assertEquals(AgentActor.system("delivery"), last.actor());
		String why = "PR " + url + " moved past the tested head aaaaaaa → bbbbbbb after tester's pass";
		assertEquals(why, back.getReopens().get(back.getReopens().size() - 1).reason());
		assertEquals(1, alerts(r, why + "; reopened to the tester").size(), agentBoardService.recentEvents(board(r).getUuid()).toString());
	}

	@Test
	public void theMergeAtTheTestedHeadCompletesAsBefore() throws RelizaException {
		Rig r = rig(true);
		String url = newPrUrl();
		AgentTaskData d = deliveringAtA(r, url);
		pr(r, url, PullRequestState.MERGED, null);
		assertEquals(TaskStatus.COMPLETED, reload(d).getStatus());
		assertEquals(StatusTrigger.DELIVERED, reload(d).getStatusHistory().get(reload(d).getStatusHistory().size() - 1).trigger());
	}

	@Test
	public void aMergeReportedWithItsMergeCommitStillDelivers() throws RelizaException {
		// A squash or merge commit as the PR's newest, reported with the merge: the head seen just
		// before it was the tested one, so what merged is what passed.
		Rig r = rig(true);
		String url = newPrUrl();
		AgentTaskData d = deliveringAtA(r, url);
		pr(r, url, PullRequestState.MERGED, C);
		assertEquals(TaskStatus.COMPLETED, reload(d).getStatus(), "the head before the merge was the tested one");
		assertTrue(alerts(r, "moved").isEmpty(), agentBoardService.recentEvents(board(r).getUuid()).toString());
	}

	// ---------- with delivery declarations (task 18c5c293 merged with 3b97ccfd) ----------

	@Test
	public void aDeclaredPrThatMovesPastTheTestedHeadStillReopensTheTask() throws RelizaException {
		// The moved-head check runs before any evidence counts: a declaration does not cover a new head.
		Rig r = rig(true);
		String one = newPrUrl();
		String two = newPrUrl();
		pr(r, one, PullRequestState.OPEN, A);
		pr(r, two, PullRequestState.OPEN, B);
		AgentTaskData t = coded(r, queuedCoder(r), one, two);
		AgentTaskData d = testerSignsOff(r, t, SignOffOutcome.PASSED,
				report("PASSED", List.of(tested(one, A), tested(two, B))));
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		AgentTaskData declared = agentTaskService.declareDelivery(d.getUuid(), one, A,
				AgentTaskData.DeliveryOutcome.DELIVERED, "merged elsewhere", COORD, WU);
		assertEquals(TaskStatus.DELIVERING, declared.getStatus(), "the other PR still waits");

		pr(r, one, PullRequestState.OPEN, C);
		AgentTaskData back = reload(d);
		assertEquals(TaskStatus.QUEUED, back.getStatus(), "reopened although the PR was declared");
		assertEquals("tester", back.getRole());
		assertEquals(1, alerts(r, "PR " + one + " moved past the tested head aaaaaaa → ccccccc").size(),
				agentBoardService.recentEvents(board(r).getUuid()).toString());
		assertEquals(1, back.getDeliveries().size(), "the declaration stays on the record");
	}

	@Test
	public void aMergeAfterAnUntestedHeadReopensRatherThanCompletes() throws RelizaException {
		// The tester passed A while the PR already sat at B; the merge then lands at C. The merge-commit
		// rule does not cover it (the head before the merge, B, is not the tested one), so the moved
		// check has to run before the merge is counted as a delivery.
		Rig r = rig(true);
		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN, B);
		AgentTaskData t = coded(r, queuedCoder(r), url);
		AgentTaskData d = testerSignsOff(r, t, SignOffOutcome.PASSED, report("PASSED", List.of(tested(url, A))));
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		pr(r, url, PullRequestState.MERGED, C);
		AgentTaskData back = reload(d);
		assertEquals(TaskStatus.QUEUED, back.getStatus(), "not completed at a head nobody tested");
		assertEquals("tester", back.getRole());
	}

	@Test
	public void aDeclarationOfAnotherCommitIsOnlyRecorded() throws RelizaException {
		// A declaration is a recorded claim (design 18c5c293 §5.1): its commit sits beside the tested
		// head for a reader to compare, and is not checked against it.
		Rig r = rig(true);
		String url = newPrUrl();
		AgentTaskData d = deliveringAtA(r, url);
		AgentTaskData done = agentTaskService.declareDelivery(d.getUuid(), url, C,
				AgentTaskData.DeliveryOutcome.DELIVERED, "squashed", COORD, WU);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(C, done.getDeliveries().get(0).commit());
		assertEquals(A.substring(0, 8), agentDeliveryService.testedOf(done).orElseThrow().heads().get(0).head(),
				"the tested head is kept beside it");
		assertTrue(alerts(r, "moved").isEmpty(), agentBoardService.recentEvents(board(r).getUuid()).toString());
	}

	@Test
	public void withNoTestedHeadAMovedPrIsAlertedOnce() throws RelizaException {
		Rig r = rig(false);
		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN, A);
		AgentTaskData d = coded(r, queuedCoder(r), url);
		assertEquals(TaskStatus.DELIVERING, d.getStatus(), "no tester on this board: no round names a head");

		pr(r, url, PullRequestState.OPEN, B);
		assertEquals(TaskStatus.DELIVERING, reload(d).getStatus(), "an ALERT only");
		String alert = "PR " + url + " moved aaaaaaa → bbbbbbb while delivering, and no passing review or test names the head";
		assertEquals(1, alerts(r, alert).size(), agentBoardService.recentEvents(board(r).getUuid()).toString());
		agentDeliveryService.sweep(WU);
		pr(r, url, PullRequestState.OPEN, B);
		assertEquals(1, alerts(r, "PR " + url + " moved").size(), "each new head is looked at once: " + agentBoardService.recentEvents(board(r).getUuid()));
	}

	@Test
	public void theSweepCatchesAMovedHeadToo() throws RelizaException {
		Rig r = rig(true);
		String url = newPrUrl();
		AgentTaskData d = deliveringAtA(r, url);
		// the new head lands without the upsert's event
		pullRequestService.advanceHead(row(r, url).getUuid(), sce(r, B), WU);
		assertEquals(TaskStatus.DELIVERING, reload(d).getStatus(), "no event, no move");

		agentDeliveryService.sweep(WU);
		assertEquals(TaskStatus.QUEUED, reload(d).getStatus());
		assertEquals("tester", reload(d).getRole());
	}

	// ---------- tested heads are the tester's; the verdict rule's PASS side (task 5ec48b02) ----------

	/** A REQUIRED reviewer producing BOARD_REVIEW_ITEMS, between the coder and (with {@code tester}) the tester. */
	private Rig rigWithReviewer(boolean tester) throws RelizaException {
		Rig r = rig(tester);
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("reviewer", "review", 25,
				null, false, true, null, null, RoleNecessity.REQUIRED, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.BOARD_REVIEW_ITEMS, InputScope.TASK, true)),
				null, null), true, WU);
		return r;
	}

	/** A BOARD_REVIEW_ITEMS index with this verdict, these review items, and these tested entries (none when null). */
	private static Map<String, Object> review(String verdict, List<Map<String, Object>> reviewItems,
			List<Map<String, Object>> tested) {
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", "BOARD_REVIEW_ITEMS");
		idx.put("verdict", verdict);
		idx.put("reviewItems", new ArrayList<>(reviewItems));
		if (null != tested) idx.put("tested", tested);
		return idx;
	}

	private static Map<String, Object> reviewItem(String id, int priority) {
		return new LinkedHashMap<>(Map.of("id", id, "priority", priority, "status", "OPEN", "title", "about " + id));
	}

	/** The reviewer takes the task, publishes {@code index} and signs off with {@code outcome}. */
	private AgentTaskData reviewerSignsOff(Rig r, AgentTaskData t, SignOffOutcome outcome, Map<String, Object> index)
			throws RelizaException {
		assertEquals("reviewer", reload(t).getRole());
		agentTaskService.assign(t.getUuid(), board(r), r.session().getAgent(), r.session().getUuid(), WU);
		ReleaseData round = agentDocumentService.publish(r.session(), new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_REVIEW_ITEMS, null, null, null, null, null, null, index, null, null, null,
				null, null), WU);
		return agentTaskService.signOff(t.getUuid(), r.session().getUuid(), outcome, "reviewed",
				List.of(round.getUuid()), WU);
	}

	@Test
	public void aReviewerPassNeedNotNameTheHeadsOfLinkedPrs() throws RelizaException {
		Rig r = rigWithReviewer(true);
		String one = newPrUrl();
		String two = newPrUrl();
		pr(r, one, PullRequestState.OPEN, A);
		pr(r, two, PullRequestState.OPEN, A);
		AgentTaskData t = coded(r, queuedCoder(r), one, two);
		AgentTaskData after = reviewerSignsOff(r, t, SignOffOutcome.PASSED, review("PASSED", List.of(), null));
		assertEquals(SignOffOutcome.PASSED, after.getSignOffs().get(after.getSignOffs().size() - 1).outcome(),
				"reviewing a design is not testing a PR");
		assertEquals("tester", after.getRole(), "on to the tester, who does name them");

		// The tester's pass still must.
		agentTaskService.assign(t.getUuid(), board(r), r.session().getAgent(), r.session().getUuid(), WU);
		ReleaseData untested = publishReport(r, reload(t), report("PASSED", null));
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.session().getUuid(), SignOffOutcome.PASSED, "tested", List.of(untested.getUuid()), WU));
		assertTrue(e.getMessage().startsWith("A pass says what it tested"), e.getMessage());
	}

	@Test
	public void aReviewerMayNameHeadsButOnlyOfLinkedPrs() throws RelizaException {
		Rig r = rigWithReviewer(true);
		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN, A);
		AgentTaskData t = coded(r, queuedCoder(r), url);
		String elsewhere = newPrUrl();
		RelizaException e = assertThrows(RelizaException.class, () -> reviewerSignsOff(r, t, SignOffOutcome.PASSED,
				review("PASSED", List.of(), List.of(tested(elsewhere, A)))));
		assertTrue(e.getMessage().contains("tested names " + elsewhere + ", which is not linked to this task"),
				e.getMessage());
		AgentTaskData named = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED,
				"reviewed", List.of(agentDocumentService.publish(r.session(), new PublishRequest(t.getUuid(),
						RearmSpecificationType.BOARD_REVIEW_ITEMS, null, null, null, null, null, null,
						review("PASSED", List.of(), List.of(tested(url, A))), null, null, null, null, null), WU).getUuid()),
				WU);
		assertEquals("tester", named.getRole(), "a linked head named is fine");
	}

	@Test
	public void theMovedHeadCheckIgnoresAReviewersHeads() throws RelizaException {
		// No tester: the reviewer's pass is the last, and it names head A. The board holds the PR to
		// the tester's heads only, so a push past A alerts and does not send it back to the reviewer.
		Rig r = rigWithReviewer(false);
		String url = newPrUrl();
		pr(r, url, PullRequestState.OPEN, A);
		AgentTaskData t = coded(r, queuedCoder(r), url);
		AgentTaskData d = reviewerSignsOff(r, t, SignOffOutcome.PASSED, review("PASSED", List.of(),
				List.of(tested(url, A.substring(0, 8)))));
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		assertTrue(agentDeliveryService.testedOf(reload(d)).isEmpty(), "testedHeads reads test reports only");

		pr(r, url, PullRequestState.OPEN, B);
		assertEquals(TaskStatus.DELIVERING, reload(d).getStatus(), "not reopened to the reviewer");
		assertEquals(1, alerts(r, "PR " + url + " moved aaaaaaa → bbbbbbb while delivering, and no passing review or"
				+ " test names the head").size(), agentBoardService.recentEvents(board(r).getUuid()).toString());
	}

	@Test
	public void onALaxBoardNothingBlockingIsAPassAndARejectionOverNothingGoesToTheCoordinator() throws RelizaException {
		Rig r = rigWithReviewer(false);
		agentBoardService.updateSettings(r.board().getUuid(), null, null, null, null, 2, null, WU);
		AgentTaskData passed = reviewerSignsOff(r, coded(r, queuedCoder(r)), SignOffOutcome.PASSED,
				review("PASSED", List.of(reviewItem("F-1", 3)), null));
		assertEquals(SignOffOutcome.PASSED, passed.getSignOffs().get(passed.getSignOffs().size() - 1).outcome(),
				"a P3 open under a blocking priority of 2 is a pass");

		AgentTaskData rejected = reviewerSignsOff(r, coded(r, queuedCoder(r)), SignOffOutcome.REJECTED,
				review("REJECTED", List.of(reviewItem("F-1", 3)), null));
		assertEquals(TaskStatus.AWAITING_COORDINATOR, rejected.getStatus(),
				"a rejection that leaves nothing blocking is unexplained: the coordinator, not the coder");
	}

	private PullRequestData row(Rig r, String url) {
		return pullRequestService.listByOrg(r.org().getUuid()).stream()
				.filter(p -> null != p.getEndpoint() && url.equals(p.getEndpoint().toString())).findFirst().orElseThrow();
	}

	/**
	 * RD3-13 architecture-2 §3: a superseded PR is not a unit any more, so a push to its branch after the tester's
	 * pass reopens nothing; the replacement still waits at its tested head.
	 */
	@Test
	public void aPushToASupersededPrReopensNothing() throws RelizaException {
		Rig r = rig(true);
		String old = newPrUrl();
		String replacement = newPrUrl();
		pr(r, old, PullRequestState.OPEN, A);
		pr(r, replacement, PullRequestState.OPEN, B);
		AgentTaskData t = coded(r, queuedCoder(r), old, replacement);
		pr(r, old, PullRequestState.CLOSED, A);
		agentTaskService.supersedePullRequest(t.getUuid(), old, replacement, "replaced", COORD, WU);
		AgentTaskData d = testerSignsOff(r, t, SignOffOutcome.PASSED,
				report("PASSED", List.of(tested(old, A), tested(replacement, B))));
		assertEquals(TaskStatus.DELIVERING, d.getStatus());

		pr(r, old, PullRequestState.CLOSED, C);
		AgentTaskData still = reload(d);
		assertEquals(TaskStatus.DELIVERING, still.getStatus(), "a push to the superseded PR reopens nothing");
		assertTrue(alerts(r, "moved past the tested head").isEmpty(), agentBoardService.recentEvents(board(r).getUuid()).toString());
		pr(r, replacement, PullRequestState.MERGED, null);
		assertEquals(TaskStatus.COMPLETED, reload(d).getStatus(), "the replacement merged at its tested head");
	}
}
