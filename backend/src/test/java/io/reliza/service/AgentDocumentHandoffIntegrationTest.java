/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
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

import io.reliza.common.Utils;
import io.reliza.exceptions.ActionRefusedException;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.AttestationData.ActorType;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItem;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.dto.SceDto;
import io.reliza.model.SourceCodeEntry;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.model.VcsRepositoryData;
import io.reliza.service.VcsRepositoryService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Document handoff against real Postgres: publishing a round, the idempotency key, round counting,
 * the carry-forward rule at the publish boundary, TASK-scope resolution, and sign-off enforcement.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDocumentHandoffIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/widget";
	/**
	 * The board's source for the documents repository, in the tracker shorthand a board is
	 * conventionally configured with.
	 */
	private static final String DOCS_SOURCE = "github:acme/widget-docs";
	/**
	 * The documents repository itself, as a git URI -- which is what an operator configures and
	 * what the CLI reads from a checkout's remote. Deliberately a DIFFERENT SHAPE from the source
	 * above: the two name one repository and must compare equal everywhere. Using the shorthand on
	 * both sides is what let an earlier version of these tests pass while production was wrong.
	 */
	private static final String DOCS = "https://github.com/acme/widget-docs";

	/**
	 * A distinct issue number per task.
	 *
	 * <p>These fixtures used a random uuid as the issue key, which no tracker could produce -- a
	 * GitHub issue is numbered. The grammar refuses it now, and rightly: registration is
	 * idempotent on the reference, so the tests need keys that are unique AND well-formed.
	 */
	private static final AtomicInteger ISSUE_SEQ = new AtomicInteger(1000);

	private static int nextIssue() {
		return ISSUE_SEQ.incrementAndGet();
	}

	/** The coordinator session these tests act as, for the transitions it causes. */
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private AgentTaskInputService agentTaskInputService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private ComponentLockService componentLockService;
	@Autowired private VcsRepositoryService vcsRepositoryService;

	private record Rig(Organization org, Component target, AgentBoardData board,
			AgentData worker, AgentSessionData session) {}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "docs-board-" + UUID.randomUUID(),
				"test board", List.of(SRC, DOCS_SOURCE), "coordinate", 2, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board,
				AgentBoardService.RoleConfigSpec.ofBasics("reviewer", "review it", 10, null, false, true, null),
				true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData worker = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"worker-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData session = agentSessionService.initialize(org.getUuid(), worker.getUuid(), null,
				"s-" + UUID.randomUUID(), "test session", null, null, WU);
		return new Rig(org, target, board, worker, session);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	/** A task assigned to the rig's session, ready to publish against. */
	private AgentTaskData assignedTask(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + nextIssue(),
				"task", null, r.session().getUuid(), null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "reviewer", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	private static Map<String, Object> reviewItem(String id, int priority, String status) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("id", id);
		f.put("priority", priority);
		f.put("status", status);
		f.put("title", "issue " + id);
		return f;
	}

	@SafeVarargs
	private static Map<String, Object> index(String verdict, Map<String, Object>... reviewItems) {
		Map<String, Object> i = new LinkedHashMap<>();
		i.put("kind", "BOARD_REVIEW_ITEMS");
		i.put("verdict", verdict);
		i.put("reviewItems", new ArrayList<>(List.of(reviewItems)));
		return i;
	}

	/**
	 * A round at its own path, as a board's {round} template gives every round: a hop that publishes the
	 * same path twice makes a new version of that round instead (task RD4-7), which {@link #reqAt} tests.
	 */
	private PublishRequest req(UUID task, String commit, String digest, Map<String, Object> index) {
		return reqAt("review-items/x/round-" + commit + ".md", task, commit, digest, index);
	}

	private PublishRequest reqAt(String path, UUID task, String commit, String digest, Map<String, Object> index) {
		return new PublishRequest(task, RearmSpecificationType.BOARD_REVIEW_ITEMS, null,
				path, digest, "text/markdown", path.replace(".md", ".json"), "idx-" + digest,
				index, commit, DOCS, "review round", ZonedDateTime.now(), null);
	}

	// ---------- publish ----------

	/**
	 * Two tasks citing the same document commit.
	 *
	 * <p>One commit has one source code entry -- a unique index says so -- and the documents
	 * repository is shared by every board that writes to it, so a component-scoped design written
	 * once and read by several tasks is the ordinary case rather than an odd one. Creating a second
	 * entry for the same commit failed that index, and the agent that had done nothing wrong got
	 * "Request violates data constraints" with no mention of a commit.
	 */
	@Test
	public void twoTasksCanCiteTheSameDocumentCommit() throws RelizaException {
		Rig r = rig();
		AgentTaskData first = assignedTask(r);
		ReleaseData one = agentDocumentService.publish(r.session(),
				req(first.getUuid(), "shared-commit", "d1",
						index("PASSED", reviewItem("F-1", 1, "RESOLVED"))), WU);

		AgentTaskData second = assignedTask(r);
		ReleaseData two = agentDocumentService.publish(r.session(),
				req(second.getUuid(), "shared-commit", "d2",
						index("PASSED", reviewItem("F-2", 1, "RESOLVED"))), WU);

		assertNotNull(two, "the second task may cite the same commit");
		assertEquals(one.getSourceCodeEntry(), two.getSourceCodeEntry(),
				"and both point at the one entry that commit has");
	}


	@Test
	public void aFirstPublishCreatesTheSharedComponentAndLinksTheRoundToTheTask() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedTask(r);

		ReleaseData rd = agentDocumentService.publish(r.session(), req(t.getUuid(), "c1", "d1",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);

		assertNotNull(rd.getDocument(), "the release must carry its document pointer");
		assertEquals(RearmSpecificationType.BOARD_REVIEW_ITEMS, rd.getDocument().specification());
		assertEquals(1, rd.getDocument().round(), "the first round of a task is round 1");
		assertEquals(t.getUuid(), rd.getDocument().task());
		assertEquals(r.session().getUuid(), rd.getDocument().session());

		// Linked to the task, which is what makes TASK-scope resolution able to find it at all.
		AgentTaskData after = agentTaskService.getTaskData(t.getUuid()).orElseThrow();
		assertTrue(after.getReleases().contains(rd.getUuid()));

		// And the component it was created under carries the specification identifier, so the
		// board target's document components include it from now on.
		assertFalse(agentTaskInputService.documentComponentsOfRepo(board(r)).isEmpty(),
				"the new document component should be visible under the board's documents repo");
	}

	@Test
	public void twoTasksShareOneDocumentComponent() throws RelizaException {
		// The shared component is why TASK-scope resolution has to filter by task: every task's
		// rounds live in one component per board target and type.
		Rig r = rig();
		AgentTaskData t1 = assignedTask(r);
		ReleaseData a = agentDocumentService.publish(r.session(), req(t1.getUuid(), "c1", "d1",
				index("PASSED")), WU);
		agentTaskService.signOff(t1.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done",
				List.of(a.getUuid()), WU);

		AgentTaskData t2 = assignedTask(r);
		ReleaseData b = agentDocumentService.publish(r.session(), req(t2.getUuid(), "c2", "d2",
				index("PASSED")), WU);

		assertEquals(a.getComponent(), b.getComponent(), "one component per board target and type");
		assertEquals(1, b.getDocument().round(), "rounds count per TASK, not per component");
	}

	@Test
	public void roundsCountUpPerTask() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		agentDocumentService.publish(r.session(), req(t.getUuid(), "c1", "d1",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		ReleaseData two = agentDocumentService.publish(r.session(), req(t.getUuid(), "c2", "d2",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		assertEquals(2, two.getDocument().round());
	}

	// ---------- idempotency ----------

	@Test
	public void aRetryAtTheSameCommitAndDigestReturnsTheFirstReleaseAndMintsNothing()
			throws RelizaException {
		// The retry case the key exists for. Without it a timed-out publish mints round 2 whose
		// path template says "round-2.md" while the repository only ever got round-1.md — a
		// release pointing at a file that does not exist.
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		Map<String, Object> idx = index("REJECTED", reviewItem("F-1", 1, "OPEN"));

		ReleaseData first = agentDocumentService.publish(r.session(), req(t.getUuid(), "c1", "d1", idx), WU);
		ReleaseData retry = agentDocumentService.publish(r.session(), req(t.getUuid(), "c1", "d1", idx), WU);

		assertEquals(first.getUuid(), retry.getUuid(), "a retry must get its original release back");
		assertEquals(1, retry.getDocument().round(), "and must not advance the round");

		AgentTaskData after = agentTaskService.getTaskData(t.getUuid()).orElseThrow();
		long rounds = after.getReleases().stream()
				.filter(u -> u.equals(first.getUuid())).count();
		assertEquals(1, rounds, "the task must link the release once");
	}

	@Test
	public void newContentAtANewCommitIsANewRound() throws RelizaException {
		// The other side of the key: an amended document really is a new round.
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		ReleaseData first = agentDocumentService.publish(r.session(), req(t.getUuid(), "c1", "d1",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		ReleaseData second = agentDocumentService.publish(r.session(), req(t.getUuid(), "c2", "d2",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		assertFalse(first.getUuid().equals(second.getUuid()));
		assertEquals(2, second.getDocument().round());
	}

	// ---------- validation at the boundary ----------

	@Test
	public void aRoundDroppingAnOpenReviewItemIsRefusedAtPublish() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		agentDocumentService.publish(r.session(), req(t.getUuid(), "c1", "d1",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"), reviewItem("F-2", 2, "OPEN"))), WU);

		RelizaException e = assertThrows(RelizaException.class, () -> agentDocumentService.publish(
				r.session(), req(t.getUuid(), "c2", "d2", index("PASSED", reviewItem("F-1", 1, "RESOLVED"))), WU));
		assertTrue(e.getMessage().contains("F-2"), "the dropped id must be named: " + e.getMessage());
	}

	@Test
	public void publishingToAnotherRepositoryIsRefused() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		PublishRequest wrong = new PublishRequest(t.getUuid(), RearmSpecificationType.BOARD_REVIEW_ITEMS,
				null, "f.md", "d1", null, null, null, index("PASSED"), "c1",
				"github:acme/somewhere-else", null, ZonedDateTime.now(), null);
		assertThrows(RelizaException.class, () -> agentDocumentService.publish(r.session(), wrong, WU));
	}

	@Test
	public void onlyTheSessionHoldingTheAssignmentMayPublish() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		AgentSessionData other = agentSessionService.initialize(r.org().getUuid(), r.worker().getUuid(),
				null, "other-" + UUID.randomUUID(), "other", null, null, WU);
		assertThrows(RelizaException.class, () -> agentDocumentService.publish(other,
				req(t.getUuid(), "c1", "d1", index("PASSED")), WU));
	}

	// ---------- TASK-scope resolution (D7) ----------

	@Test
	public void aTaskScopedRequirementBindsThisTasksRoundAndNotAnothers() throws RelizaException {
		// The bug D7 exists to prevent. Both tasks' rounds live in ONE component, so a resolver
		// that looked at the component rather than the task would hand task two's reviewer the
		// review items from task one.
		Rig r = rig();
		AgentTaskData t1 = assignedTask(r);
		ReleaseData r1 = agentDocumentService.publish(r.session(), req(t1.getUuid(), "c1", "d1",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		agentTaskService.signOff(t1.getUuid(), r.session().getUuid(), SignOffOutcome.REJECTED, "n",
				List.of(r1.getUuid()), WU);

		AgentTaskData t2 = assignedTask(r);
		ReleaseData r2 = agentDocumentService.publish(r.session(), req(t2.getUuid(), "c2", "d2",
				index("REJECTED", reviewItem("F-9", 1, "OPEN"))), WU);

		List<ReleaseData> docsOfTwo = agentDocumentService.documentsOfTask(
				agentTaskService.getTaskData(t2.getUuid()).orElseThrow());
		assertEquals(1, docsOfTwo.size(), "task two has exactly one round of its own");
		assertEquals(r2.getUuid(), docsOfTwo.get(0).getUuid());
		assertFalse(docsOfTwo.stream().anyMatch(d -> d.getUuid().equals(r1.getUuid())),
				"task one's round must not appear among task two's documents");
	}

	@Test
	public void openReviewItemsComeFromTheNewestRoundOnly() throws RelizaException {
		// Each round carries forward what is still open, so the newest IS the current state.
		// Summing rounds would count a review item carried across three rounds three times.
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		agentDocumentService.publish(r.session(), req(t.getUuid(), "c1", "d1",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"), reviewItem("F-2", 2, "OPEN"))), WU);
		agentDocumentService.publish(r.session(), req(t.getUuid(), "c2", "d2",
				index("REJECTED", reviewItem("F-1", 1, "RESOLVED"), reviewItem("F-2", 2, "OPEN"))), WU);

		List<BoardReviewItem> open = agentDocumentService.openReviewItemsOfTask(
				agentTaskService.getTaskData(t.getUuid()).orElseThrow());
		assertEquals(List.of("F-2"), open.stream().map(BoardReviewItem::id).toList());
	}

	// ---------- sign-off enforcement ----------

	@Test
	public void aRoleThatDeclaresAnOutputCannotSignOffWithoutIt() throws RelizaException {
		Rig r = rig();
		requireReviewItemsOfReviewer(r);
		AgentTaskData t = assignedTask(r);

		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.signOff(
				t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "looks fine", List.of(), WU));
		assertTrue(e.getMessage().contains("BOARD_REVIEW_ITEMS"),
				"the refusal must name the type: " + e.getMessage());

		// And the task is still assigned, so the agent can publish and try again.
		AgentTaskData after = agentTaskService.getTaskData(t.getUuid()).orElseThrow();
		assertNotNull(after.getAssignment(), "the failing hop keeps its assignment");
	}

	@Test
	public void publishingThenSigningOffWithTheOutputSucceeds() throws RelizaException {
		Rig r = rig();
		requireReviewItemsOfReviewer(r);
		AgentTaskData t = assignedTask(r);
		ReleaseData doc = agentDocumentService.publish(r.session(), req(t.getUuid(), "c1", "d1",
				index("PASSED")), WU);

		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.session().getUuid(),
				SignOffOutcome.PASSED, "reviewed", List.of(doc.getUuid()), WU);

		assertEquals(1, after.getSignOffs().size());
		assertEquals(List.of(doc.getUuid()), after.getSignOffs().get(0).outputs(),
				"the hop record freezes what it produced");
	}

	@Test
	public void anotherSessionsDocumentCannotBeClaimedAsAnOutput() throws RelizaException {
		Rig r = rig();
		AgentTaskData t1 = assignedTask(r);
		ReleaseData mine = agentDocumentService.publish(r.session(), req(t1.getUuid(), "c1", "d1",
				index("PASSED")), WU);
		agentTaskService.signOff(t1.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "n",
				List.of(mine.getUuid()), WU);

		// A second session on a second task tries to pass off the first task's document.
		AgentSessionData other = agentSessionService.initialize(r.org().getUuid(), r.worker().getUuid(),
				null, "other-" + UUID.randomUUID(), "other", null, null, WU);
		AgentTaskData t2 = agentTaskService.register(board(r), SRC + "#" + nextIssue(),
				"task", null, other.getUuid(), null, null, null, null, WU);
		agentTaskService.authorize(t2.getUuid(), board(r), "reviewer", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t2.getUuid(), board(r), r.worker().getUuid(), other.getUuid(), WU);

		assertThrows(RelizaException.class, () -> agentTaskService.signOff(t2.getUuid(),
				other.getUuid(), SignOffOutcome.PASSED, "n", List.of(mine.getUuid()), WU));
	}

	@Test
	public void aReturnRequiresNothingButStillRecordsWhatWasProduced() throws RelizaException {
		// An agent returning because the task was unclear has nothing to publish; demanding a
		// document would leave it unable to finish OR hand back. Partial review items are still worth
		// keeping when there are some.
		Rig r = rig();
		requireReviewItemsOfReviewer(r);
		AgentTaskData t = assignedTask(r);

		AgentTaskData after = agentTaskService.returnTask(t.getUuid(), r.session().getUuid(),
				TaskReturnReason.TASK_UNCLEAR, "cannot tell what is wanted", List.of(), WU);
		assertEquals(1, after.getReturns().size());
		assertTrue(after.getReturns().get(0).outputs().isEmpty());
	}

	@Test
	public void aNonDocumentReleaseIsNotAnOutput() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		// The board target's own release is a real release, but it is not a document.
		assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.session().getUuid(), SignOffOutcome.PASSED, "n", List.of(UUID.randomUUID()), WU));
	}

	/**
	 * Make the reviewer role declare that it must publish review items.
	 *
	 * <p>As the operator, not the coordinator: what a role produces is the board's routing graph,
	 * so it sits with prompts and capabilities on the operator-only side. A coordinator that could
	 * edit it could decide who answers its own questions.
	 */
	private void requireReviewItemsOfReviewer(Rig r) throws RelizaException {
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"reviewer", "review it", 10, null, false, true, null, null, null, null, null,
				List.of(new ProducedOutput(RearmSpecificationType.BOARD_REVIEW_ITEMS, InputScope.TASK, true))),
				true, WU);
	}

	// ---------- the sign-off lock (D5, second clause) ----------

	@Test
	public void anActiveLockOnADocumentComponentRefusesSignOff() throws RelizaException {
		// The control the URI mismatch had silently disabled, exercised end to end with a real
		// lock raised through the lock service rather than a stubbed component list. A governance
		// control that quietly does nothing looks exactly like one that is working, so the only
		// test worth having here is one that would have failed while it was broken.
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		ReleaseData doc = agentDocumentService.publish(r.session(), req(t.getUuid(), "c1", "d1",
				index("PASSED")), WU);

		// Lock the document component the publish just created.
		UUID docComponent = doc.getComponent();
		componentLockService.lockComponent(docComponent, "docs frozen for the release",
				null, null, WU);

		// ActionRefusedException, not RelizaException: a lock is a refusal to act, and the
		// distinction is what lets the API surface it as a 409 rather than a generic error.
		ActionRefusedException e = assertThrows(ActionRefusedException.class,
				() -> agentTaskService.signOff(t.getUuid(), r.session().getUuid(),
						SignOffOutcome.PASSED, "done", List.of(doc.getUuid()), WU));
		assertTrue(e.getMessage().contains("sign off a task"),
				"the refusal should name the operation it blocked: " + e.getMessage());
		assertTrue(e.getMessage().contains("docs frozen"),
				"and carry the lock's reason, which is the only actionable part: " + e.getMessage());

		// The hop keeps its assignment: the work is done and only the lock stands in the way.
		AgentTaskData held = agentTaskService.getTaskData(t.getUuid()).orElseThrow();
		assertNotNull(held.getAssignment());

		// Returns are never lock-checked -- handing work back is not a write, and a lock that
		// trapped a task with its assignment held would be worse than one that stops sign-offs.
		AgentTaskData returned = agentTaskService.returnTask(t.getUuid(), r.session().getUuid(),
				TaskReturnReason.BLOCKED_ON_DEPENDENCY, "documents repo is locked", List.of(), WU);
		assertEquals(1, returned.getReturns().size());
	}

	@Test
	public void unlockingLetsTheSignOffThrough() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		ReleaseData doc = agentDocumentService.publish(r.session(), req(t.getUuid(), "c1", "d1",
				index("PASSED")), WU);
		componentLockService.lockComponent(doc.getComponent(), "brief freeze", null, null, WU);
		assertThrows(ActionRefusedException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.session().getUuid(), SignOffOutcome.PASSED, "done", List.of(doc.getUuid()), WU));

		// Release the lock we raised, through the real path, and the sign-off goes through.
		var located = componentLockService.listActive(r.org().getUuid()).stream()
				.filter(l -> doc.getComponent().equals(l.componentUuid()))
				.findFirst().orElseThrow();
		componentLockService.releaseLock(r.org().getUuid(), located.lock().uuid(), "freeze over",
				true, ActorType.USER, null, null, WU);

		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.session().getUuid(),
				SignOffOutcome.PASSED, "done", List.of(doc.getUuid()), WU);
		assertEquals(1, after.getSignOffs().size());
	}

	// ---------- the assignment window ----------

	@Test
	public void aDocumentFromAnEarlierHopCannotBeOfferedAsThisHopsOutput() throws RelizaException {
		// Session and task ownership both hold here -- the same session, the same task -- so
		// without the window check this would satisfy a required output having published nothing
		// in the hop that claims it.
		Rig r = rig();
		requireReviewItemsOfReviewer(r);
		AgentTaskData t = assignedTask(r);
		ReleaseData roundOne = agentDocumentService.publish(r.session(), req(t.getUuid(), "c1", "d1",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		agentTaskService.returnTask(t.getUuid(), r.session().getUuid(),
				TaskReturnReason.TASK_UNCLEAR, "need more detail", List.of(roundOne.getUuid()), WU);

		// The same session picks the task up again: a genuinely new hop.
		agentTaskService.authorize(t.getUuid(), board(r), "reviewer", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);

		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.signOff(
				t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "reusing round one",
				List.of(roundOne.getUuid()), WU));
		assertTrue(e.getMessage().contains("before this hop began"),
				"the refusal should say why: " + e.getMessage());
	}

	@Test
	public void aRemoteSpelledDifferentlyFromTheBoardStillPublishes() throws RelizaException {
		// The property the uuid pointer buys. The board was configured with an https URI; an agent
		// whose checkout has an ssh remote sends that, and both resolve to ONE repository row, so
		// the publish succeeds. While the board held a string this only worked if the CLI's
		// canonicaliser matched the server's byte for byte -- a coupling between two codebases
		// that could drift silently, and did, for ssh:// remotes.
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		PublishRequest viaSsh = new PublishRequest(t.getUuid(), RearmSpecificationType.BOARD_REVIEW_ITEMS,
				null, "review-items/x/round.md", "d1", "text/markdown", null, null,
				index("PASSED"), "c1", "git@github.com:acme/widget-docs.git", null,
				ZonedDateTime.now(), null);

		ReleaseData rd = agentDocumentService.publish(r.session(), viaSsh, WU);
		assertNotNull(rd.getDocument());

		// And the document's source code entry hangs off the SAME row the board names, which is
		// what makes commit recognition and signature verification apply to it as they do to code.
		AgentBoardData board = board(r);
		assertEquals(board.getDocumentsRepo(),
				vcsRepositoryService.getVcsRepository(board.getDocumentsRepo())
						.map(v -> VcsRepositoryData.dataFromRecord(v).getUuid()).orElseThrow());
	}

	@Test
	public void aDifferentRepositoryIsStillRefusedAndLeavesNoRowBehind() throws RelizaException {
		// Resolving by row makes equal things equal; it must not make everything equal.
		//
		// And it must not CREATE. Provisioning the uri the CLI sent would mint a repository row
		// for a mistyped --repo and leave it in the org after refusing the publish -- debris an
		// operator would later find and have to reason about, from a call that failed.
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		int before = vcsRepositoryService.listVcsReposByOrg(r.org().getUuid()).size();

		PublishRequest elsewhere = new PublishRequest(t.getUuid(), RearmSpecificationType.BOARD_REVIEW_ITEMS,
				null, "f.md", "d1", null, null, null, index("PASSED"), "c1",
				"https://github.com/acme/somewhere-else", null, ZonedDateTime.now(), null);
		assertThrows(RelizaException.class, () -> agentDocumentService.publish(r.session(), elsewhere, WU));

		assertEquals(before, vcsRepositoryService.listVcsReposByOrg(r.org().getUuid()).size(),
				"a refused publish must not register the repository it refused");
	}

	@Test
	public void theExportedSpecCarriesTheDocumentsConfigurationAsAUri() throws RelizaException {
		Rig r = rig();
		agentBoardService.setDocumentsConfig(r.board().getUuid(), null,
				Map.of(RearmSpecificationType.BOARD_REVIEW_ITEMS, "reviews/{task}/round-{round}.md"), WU);

		AgentBoardService.BoardSpecDto spec = agentBoardService.exportBoard(r.board().getUuid());
		// The URI, not the row uuid. A spec is applied in whatever org holds the manifest, where
		// this org's repository uuid resolves to nothing -- the same reason target is a name.
		assertEquals(Utils.canonicalVcsUri(DOCS), Utils.canonicalVcsUri(spec.getDocumentsRepo()),
				"the documents repository travels as a URI");
		assertEquals("reviews/{key}/round-{round}.md",
				spec.getDocumentPaths().get(RearmSpecificationType.BOARD_REVIEW_ITEMS),
				"path templates are configuration and belong in the spec; {task} is written as {key}");
	}

	// ---------- task RD4-7: the same path twice in one hop is a new version of the round ----------

	@Autowired private SharedReleaseService sharedReleaseService;

	private ReleaseData reread(ReleaseData rd) {
		return sharedReleaseService.getReleaseData(rd.getUuid()).orElseThrow();
	}

	private AgentTaskData reread(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	@Test
	public void aSamePathRepublishInOneHopIsANewVersionOfTheRound() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		String next = agentDocumentService.resolveDocumentPath(board(r), RearmSpecificationType.BOARD_REVIEW_ITEMS,
				t.getUuid(), null);
		ReleaseData v1 = agentDocumentService.publish(r.session(), reqAt("notes-1.md", t.getUuid(), "c1", "d1",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		String afterOne = agentDocumentService.resolveDocumentPath(board(r), RearmSpecificationType.BOARD_REVIEW_ITEMS,
				t.getUuid(), null);
		ReleaseData v2 = agentDocumentService.publish(r.session(), reqAt("notes-1.md", reread(t).getUuid(), "c2", "d2",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"), reviewItem("F-2", 2, "OPEN"))), WU);

		assertEquals(1, v1.getDocument().round());
		assertEquals(1, v2.getDocument().round(), "the correction keeps its round number");
		assertTrue(Integer.parseInt(v2.getVersion()) > Integer.parseInt(v1.getVersion()),
				"and takes the next version: " + v1.getVersion() + " then " + v2.getVersion());
		assertEquals(v2.getUuid(), reread(v1).getDocument().supersededBy(), "the earlier version names its replacement");
		assertEquals(null, reread(v2).getDocument().supersededBy());
		assertEquals(ReleaseData.ReleaseLifecycle.DRAFT, reread(v1).getLifecycle(), "the earlier version stays as it was");

		AgentTaskData now = reread(t);
		assertEquals(v2.getUuid(), agentDocumentService.latestRoundRelease(now, RearmSpecificationType.BOARD_REVIEW_ITEMS)
				.orElseThrow().getUuid(), "the newest round reads the new version");
		assertEquals(List.of("F-1", "F-2"), agentDocumentService.openReviewItemsOfTask(now).stream().map(BoardReviewItem::id).toList());
		assertEquals(List.of(v2.getUuid(), v1.getUuid()),
				agentDocumentService.documentsOfTask(now).stream().map(ReleaseData::getUuid).toList(),
				"both versions stay in the task's documents, newest first");
		assertEquals(afterOne, agentDocumentService.resolveDocumentPath(board(r), RearmSpecificationType.BOARD_REVIEW_ITEMS,
				t.getUuid(), null), "the round counter did not move");
		assertFalse(next.equals(afterOne), "while the first publish did move it");

		// A retry of the new version returns it; a different path in the same hop is the next round.
		assertEquals(v2.getUuid(), agentDocumentService.publish(r.session(), reqAt("notes-1.md", t.getUuid(), "c2", "d2",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"), reviewItem("F-2", 2, "OPEN"))), WU).getUuid());
		ReleaseData two = agentDocumentService.publish(r.session(), reqAt("notes-2.md", t.getUuid(), "c3", "d3",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"), reviewItem("F-2", 2, "OPEN"))), WU);
		assertEquals(2, two.getDocument().round());
		assertEquals(null, reread(v2).getDocument().supersededBy(), "another path replaces nothing");
	}

	@Test
	public void aNewVersionIsCheckedAgainstTheRoundBeforeItsRoundNotTheVersionItReplaces() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		agentDocumentService.publish(r.session(), reqAt("run-1.md", t.getUuid(), "c1", "d1",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		agentDocumentService.publish(r.session(), reqAt("run-2.md", t.getUuid(), "c2", "d2",
				index("PASSED", reviewItem("F-1", 1, "RESOLVED"))), WU);
		// Against round 2 v1, where F-1 is RESOLVED, dropping it would be allowed; against round 1, the
		// round before round 2, it is still OPEN and cannot silently disappear.
		RelizaException e = assertThrows(RelizaException.class, () -> agentDocumentService.publish(r.session(),
				reqAt("run-2.md", t.getUuid(), "c3", "d3", index("PASSED")), WU));
		assertTrue(e.getMessage().contains("F-1"), e.getMessage());
	}

	@Test
	public void theSignOffHandsOverTheNewestVersionOnly() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		ReleaseData v1 = agentDocumentService.publish(r.session(), reqAt("notes-1.md", t.getUuid(), "c1", "d1",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		ReleaseData v2 = agentDocumentService.publish(r.session(), reqAt("notes-1.md", t.getUuid(), "c2", "d2",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);

		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.REJECTED,
				"offers both, as a CLI that recorded both would", List.of(v1.getUuid(), v2.getUuid()), WU);
		assertEquals(List.of(v2.getUuid()), after.getSignOffs().get(after.getSignOffs().size() - 1).outputs());
		assertEquals(ReleaseData.ReleaseLifecycle.ASSEMBLED, reread(v2).getLifecycle());
		assertEquals(ReleaseData.ReleaseLifecycle.DRAFT, reread(v1).getLifecycle(), "the replaced version is not handed over");
	}

	@Test
	public void aSignOffOfferingOnlyTheReplacedVersionHandsOverItsReplacement() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		ReleaseData v1 = agentDocumentService.publish(r.session(), reqAt("notes-1.md", t.getUuid(), "c1", "d1",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		ReleaseData v2 = agentDocumentService.publish(r.session(), reqAt("notes-1.md", t.getUuid(), "c2", "d2",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.REJECTED,
				"n", List.of(v1.getUuid()), WU);
		assertEquals(List.of(v2.getUuid()), after.getSignOffs().get(after.getSignOffs().size() - 1).outputs());
	}

	@Test
	public void theSamePathInALaterHopIsANewRound() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		ReleaseData one = agentDocumentService.publish(r.session(), reqAt("notes.md", t.getUuid(), "c1", "d1",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		agentTaskService.returnTask(t.getUuid(), r.session().getUuid(), TaskReturnReason.TASK_UNCLEAR, "more",
				List.of(one.getUuid()), WU);
		agentTaskService.authorize(t.getUuid(), board(r), "reviewer", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);

		ReleaseData two = agentDocumentService.publish(r.session(), reqAt("notes.md", t.getUuid(), "c2", "d2",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		assertEquals(2, two.getDocument().round(), "a new hop starts a new round, whatever the path");
		assertEquals(null, reread(one).getDocument().supersededBy());
	}

	@Test
	public void anotherSessionsRepublishOfThePathIsANewRound() throws RelizaException {
		// Same task and path, but the second publish comes from another session's hop after the first returned.
		Rig r = rig();
		AgentTaskData t = assignedTask(r);
		ReleaseData one = agentDocumentService.publish(r.session(), reqAt("notes.md", t.getUuid(), "c1", "d1",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		agentTaskService.returnTask(t.getUuid(), r.session().getUuid(), TaskReturnReason.TASK_UNCLEAR, "n",
				List.of(one.getUuid()), WU);
		AgentSessionData other = agentSessionService.initialize(r.org().getUuid(), r.worker().getUuid(), null,
				"s-" + UUID.randomUUID(), "second", null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "reviewer", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), other.getUuid(), WU);
		ReleaseData two = agentDocumentService.publish(other, reqAt("notes.md", t.getUuid(), "c2", "d2",
				index("REJECTED", reviewItem("F-1", 1, "OPEN"))), WU);
		assertEquals(2, two.getDocument().round());
		assertEquals(null, reread(one).getDocument().supersededBy());
	}

	/** Two roles on the rig's board: a designer writing ARCHITECTURE and a builder reading it. */
	private void designerThenBuilder(Rig r) throws RelizaException {
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("designer", "design", 5,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE, InputScope.TASK, false)), null, null), true, WU);
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("builder", "build", 7,
				null, false, true, null, null, null, null,
				List.of(new io.reliza.model.AgentTaskInput.RequiredInput(io.reliza.model.AgentTaskInput.InputKind.DOCUMENT,
						RearmSpecificationType.ARCHITECTURE, InputScope.TASK, null, null, null)),
				List.of(), null, null), true, WU);
	}

	private static String sha256(String s) {
		try {
			return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
					.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
		} catch (java.security.NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private ReleaseData design(Rig r, AgentTaskData t, String path, String commit, String elements) throws RelizaException {
		return agentDocumentService.publish(r.session(), new PublishRequest(t.getUuid(), RearmSpecificationType.ARCHITECTURE,
				null, path, "digest-" + commit, "text/markdown", null, null, null, commit, DOCS, "design",
				ZonedDateTime.now(), null, elements, sha256(elements)), WU);
	}

	@Test
	public void aNewVersionIsCheckedAgainAndIsWhatTheElementsTheCheckScopeAndTheNextHopRead() throws RelizaException {
		String first = "{\"grammarVersion\":\"1\",\"elements\":[{\"id\":\"REQ-1\",\"title\":\"One\","
				+ "\"contentDigest\":\"a1\",\"line\":3}],\"warnings\":[]}";
		String second = "{\"grammarVersion\":\"1\",\"elements\":[{\"id\":\"REQ-1\",\"title\":\"One, corrected\","
				+ "\"contentDigest\":\"a2\",\"line\":3}],\"warnings\":[]}";
		Rig r = rig();
		designerThenBuilder(r);
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + nextIssue(), "task", null, null, null, null,
				null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "designer", 5, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		ReleaseData v1 = design(r, reread(t), "design/architecture-1.md", "c1", first);
		ReleaseData v2 = design(r, reread(t), "design/architecture-1.md", "c2", second);
		assertEquals(1, v2.getDocument().round());

		AgentTaskData now = reread(t);
		assertTrue(agentDocumentService.latestElementCheckReport(now, v1.getUuid()).isPresent(), "the first version was checked");
		ReleaseData report = agentDocumentService.latestElementCheckReport(now, v2.getUuid()).orElseThrow();
		assertEquals(List.of(v2.getUuid()), report.getDocument().elementChecks().scope().releases().stream()
				.map(io.reliza.model.ElementCheckReport.ScopedRelease::release).toList(),
				"the new version is checked on its own, the replaced one is not a second definition of REQ-1");
		assertEquals(List.of(v2.getUuid()), agentDocumentService.elementChecksOfTask(now).stream()
				.map(c -> c.scope().checked()).toList(), "only the current version's report is the task's");
		assertEquals(List.of("One, corrected"), agentDocumentService.taskElements(now).stream()
				.map(AgentDocumentService.TaskElement::title).toList());
		assertEquals(1, agentDocumentService.elementHistory(now, "REQ-1").size(), "one round, one entry");

		agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "designed",
				List.of(v1.getUuid(), v2.getUuid()), WU);
		AgentSessionData builder = agentSessionService.initialize(r.org().getUuid(), r.worker().getUuid(), null,
				"s-" + UUID.randomUUID(), "builder", null, null, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), builder.getUuid(), WU);
		AgentTaskData building = reread(t);
		assertEquals("builder", building.getAssignment().role());
		assertEquals(List.of(v2.getUuid()), building.getAssignment().resolvedInputs().stream()
				.map(io.reliza.model.AgentTaskInput.ResolvedInput::release).toList(),
				"the next hop's input is the newest version");
	}
}
