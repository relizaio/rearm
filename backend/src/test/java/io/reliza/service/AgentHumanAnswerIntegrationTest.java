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

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HoldKind;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItem;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentDocumentService.Answer;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.oss.TestInitializer;

/**
 * Two things the loop could not do before: a human answering a question the board could not route,
 * and a board with no tracker being an ordinary board rather than a degraded one.
 *
 * <p>Integration rather than unit, for the same reason as the rest of the board tests: the answer
 * path crosses the document service, the router and the effects applier, and a test that mocked
 * any of the three would be asserting the mock.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {io.reliza.ws.App.class})
public class AgentHumanAnswerIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String DOCS = "https://github.com/acme/answers-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AgentActor HUMAN = AgentActor.ofUser(UUID.randomUUID(), "operator");
	private static final AtomicInteger SEQ = new AtomicInteger(9000);

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private ComponentService componentService;
	@Autowired private SharedReleaseService sharedReleaseService;
	@Autowired private io.reliza.service.oss.OssReleaseService ossReleaseService;

	/** A board with NO sources: the board is the only record these tasks have. */
	private record Rig(Organization org, AgentBoardData board, AgentTaskRoleConfigData designer,
			AgentTaskRoleConfigData coder, AgentData workerA, AgentSessionData sessA,
			AgentData workerB, AgentSessionData sessB) {}

	private Rig rig() throws RelizaException {
		return rig(List.of());
	}

	private Rig rig(List<String> sources) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		// A board WITH sources must list its documents repository among them; a board without
		// sources is exempt, which is the case these tests mostly exercise.
		List<String> wired = new ArrayList<>(sources);
		if (!wired.isEmpty()) wired.add("github:acme/answers-docs");
		Component target = componentService.createComponent("node_" + UUID.randomUUID(),
				org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(),
				"ans-" + UUID.randomUUID(), "answers board", wired, "coordinate", 4, null,
				target.getUuid(), null, WU);
		AgentTaskRoleConfigData designer = agentBoardService.upsertRoleConfig(board,
				new AgentBoardService.RoleConfigSpec("designer", "design it", 10, null, false, true,
						null, null, null, null, List.of(),
						List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE,
								InputScope.COMPONENT, false))),
				true, WU);
		AgentTaskRoleConfigData coder = agentBoardService.upsertRoleConfig(board,
				new AgentBoardService.RoleConfigSpec("coder", "build it", 20, null, false, true,
						null, null, null, null, List.of(), List.of()),
				true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData a = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"wa-" + UUID.randomUUID(), null, null, null, WU);
		AgentData b = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"wb-" + UUID.randomUUID(), null, null, null, WU);
		return new Rig(org, board, designer, coder, a, openSession(org, a), b, openSession(org, b));
	}

	private AgentSessionData openSession(Organization org, AgentData agent) throws RelizaException {
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"s-" + UUID.randomUUID(), "test session", null, null, WU);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	private static Map<String, Object> item(String id, String status) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("id", id);
		f.put("priority", 1);
		f.put("status", status);
		f.put("title", "question " + id);
		return f;
	}

	@SafeVarargs
	private static Map<String, Object> questions(RearmSpecificationType about,
			Map<String, Object>... items) {
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", "BOARD_QUESTIONS");
		idx.put("verdict", "REJECTED");
		idx.put("reviewItems", new ArrayList<>(List.of(items)));
		Map<String, Object> ab = new LinkedHashMap<>();
		ab.put("specification", about.name());
		ab.put("release", null);
		idx.put("about", ab);
		return idx;
	}

	private ReleaseData publishQuestions(AgentSessionData session, AgentTaskData td,
			Map<String, Object> index) throws RelizaException {
		return agentDocumentService.publish(session,
				new PublishRequest(td.getUuid(), RearmSpecificationType.BOARD_QUESTIONS, null, null, null,
						null, null, null, index, null, null, null, null,
						null), WU);
	}

	/** The latest BOARD_QUESTIONS index on a task, which is what an answer is cut against. */
	private BoardReviewItemIndex latestQuestions(AgentTaskData td) {
		return agentDocumentService.latestIndexes(reload(td)).get(RearmSpecificationType.BOARD_QUESTIONS);
	}

	private static BoardReviewItem find(BoardReviewItemIndex idx, String id) {
		return idx.reviewItems().stream().filter(f -> id.equals(f.id())).findFirst().orElseThrow();
	}

	/**
	 * Drive a task to the escalated state: a question about something no active role produces,
	 * which the board hands to the coordinator, who parks it for a human.
	 */
	private AgentTaskData escalated(Rig r, String title) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), null, title, null, null, null, null,
				null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null,
				COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(),
				WU);
		ReleaseData q = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.TEST_PLAN, item("q1", "OPEN"),
						item("q2", "OPEN")));
		AgentTaskData routed = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "who owns the test plan?", List.of(q.getUuid()), WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, routed.getStatus());
		return agentTaskService.escalateQuestion(t.getUuid(), HoldLevel.OPERATOR,
				"nobody here makes a test plan", COORD, WU);
	}

	@Test
	public void anAnswerThatAlsoNamesARoleIsRefused() throws RelizaException {
		// task 4c566d0d: words on a question hold are the answer, which goes back to the asker;
		// naming a role as well would say two things, so neither silently wins.
		Rig r = rig();
		AgentTaskData t = escalated(r, "answer or route, not both");
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.liftHoldOrAnswer(
				t.getUuid(), HoldLevel.OPERATOR, HUMAN, "no test plan needed", "coder", WU));
		assertTrue(e.getMessage().contains("Answer without a role, or lift it without words to name one"), e.getMessage());
		assertEquals(TaskStatus.ON_HOLD, reload(t).getStatus());
	}

	// ---------- D1: a board with no tracker ----------

	@Test
	public void aTaskLivesItsWholeLifeOnABoardWithNoSources() throws RelizaException {
		Rig r = rig();
		assertTrue(board(r).getSources().isEmpty(), "no tracker is wired");

		AgentTaskData t = agentTaskService.register(board(r), null, "no tracker anywhere", null,
				null, null, null, null, null, WU);
		assertNull(t.getExternalRef(), "and the task carries no external reference");

		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null,
				COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(),
				WU);
		ReleaseData q = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.TEST_PLAN, item("q1", "OPEN")));
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"asking", List.of(q.getUuid()), WU);
		agentTaskService.escalateQuestion(t.getUuid(), HoldLevel.OPERATOR, "nobody makes it",
				COORD, WU);

		AgentTaskData answered = agentTaskService.answer(t.getUuid(),
				List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "we do not test this one")),
				HUMAN, true, WU);

		assertEquals("coder", answered.getRole(), "the asker gets its answer back");
		assertTrue(answered.getQuestionStack().isEmpty());
		assertNull(answered.getHold(), "and the hold is lifted");

		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(),
				WU);
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED, "done",
				List.of(), WU);
		AgentTaskData done = agentTaskService.complete(t.getUuid(), "shipped", COORD, WU);
		assertEquals(TaskStatus.COMPLETED, done.getStatus(),
				"a board with no tracker completes a task");
	}

	// ---------- D2, D4: the answer is a round ----------

	@Test
	public void theAnswerIsARoundOfTheQuestionsIndex() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = escalated(r, "answered as a round");

		agentTaskService.answer(t.getUuid(),
				List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "the platform team owns it"),
						new Answer("q2", BoardReviewItemStatus.WITHDRAWN, "asked in error")),
				HUMAN, true, WU);

		BoardReviewItemIndex latest = latestQuestions(t);
		assertEquals(BoardReviewItemStatus.RESOLVED, find(latest, "q1").status());
		assertEquals("the platform team owns it", find(latest, "q1").resolution());
		assertEquals(BoardReviewItemStatus.WITHDRAWN, find(latest, "q2").status());
		assertEquals("asked in error", find(latest, "q2").resolution());
		assertTrue(latest.openReviewItems().isEmpty(), "nothing is left open");
	}

	@Test
	public void anAnswerRoundRefusesWhatItCannotAnswer() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = escalated(r, "refusals");
		BoardReviewItemIndex previous = latestQuestions(t);

		assertThrows(RelizaException.class, () -> agentDocumentService.publishAnswerRound(
				reload(t), previous, List.of(new Answer("nope", BoardReviewItemStatus.RESOLVED, "x")), WU),
				"an id that is not on the round");
		assertThrows(RelizaException.class, () -> agentDocumentService.publishAnswerRound(
				reload(t), previous, List.of(new Answer("q1", BoardReviewItemStatus.ACCEPTED, "x")), WU),
				"a status that is not an answer");
		assertThrows(RelizaException.class, () -> agentDocumentService.publishAnswerRound(
				reload(t), previous, List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "  ")), WU),
				"an answer with no words");
		assertThrows(RelizaException.class, () -> agentDocumentService.publishAnswerRound(
				reload(t), null, List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "x")), WU),
				"no round to answer");
	}

	@Test
	public void answeringATaskWithNothingWaitingIsRefused() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), null, "nothing asked", null, null,
				null, null, null, null, WU);
		assertThrows(RelizaException.class, () -> agentTaskService.answer(t.getUuid(),
				List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "x")), HUMAN, true, WU));
	}

	// ---------- D14: one writer, and the round is linked ----------

	@Test
	public void theAnswerRoundIsOnTheTaskAfterTheAnswerSaves() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = escalated(r, "single writer");
		int before = reload(t).getReleases().size();

		AgentTaskData answered = agentTaskService.answer(t.getUuid(),
				List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "a"),
						new Answer("q2", BoardReviewItemStatus.RESOLVED, "b")),
				HUMAN, true, WU);

		// The round has to be ON the task: countRounds, previousRoundIndex and the idempotency
		// lookup all read this list, so a round cut and not linked is invisible to every one of
		// them -- numbers collide, and the closure is never seen again.
		assertEquals(before + 1, answered.getReleases().size());
		UUID round = answered.getReleases().get(answered.getReleases().size() - 1);
		ReleaseData rd = sharedReleaseService.getReleaseData(round).orElseThrow();
		assertNotNull(rd.getDocument());
		assertEquals(RearmSpecificationType.BOARD_QUESTIONS, rd.getDocument().specification());
		assertNull(rd.getDocument().session(), "cut without a session, by a human");
		assertEquals(2, rd.getDocument().round(), "numbered against a task that knows its rounds");
	}

	/**
	 * An item closed by a round points at that round.
	 *
	 * <p>Which needs the uuid before the release exists, so the cut reserves the row at PENDING,
	 * names itself in its own items, and then settles at ASSEMBLED.
	 */
	@Test
	public void anItemClosedByTheAnswerRoundPointsAtThatRound() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = escalated(r, "self pointer");

		AgentTaskData answered = agentTaskService.answer(t.getUuid(),
				List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "the platform team owns it")),
				HUMAN, true, WU);

		UUID round = answered.getReleases().get(answered.getReleases().size() - 1);
		ReleaseData rd = sharedReleaseService.getReleaseData(round).orElseThrow();
		assertEquals(ReleaseLifecycle.ASSEMBLED, rd.getLifecycle(), "it settled");
		BoardReviewItem closed = find(rd.getDocument().reviewItems(), "q1");
		assertEquals(round.toString(), closed.resolvedBy(),
				"the round names itself as what closed the item");
		assertEquals("the platform team owns it", closed.resolution());
		// q2 was left open by this answer, so nothing closed it and nothing points at anything.
		assertNull(find(rd.getDocument().reviewItems(), "q2").resolvedBy());
	}

	/**
	 * Cutting the same round twice returns the first one.
	 *
	 * <p>The case the self-pointer could have broken: a round's identity cannot be the digest of
	 * its stored items once those items name the round, because a second attempt has no uuid to
	 * predict and would never match. So identity is recorded on the release when the round is
	 * cut, from the items as built -- and a retry that re-derives the same content from the same
	 * inputs matches it.
	 */
	@Test
	public void cuttingTheSameRoundTwiceReturnsTheFirstRound() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = escalated(r, "retried cut");
		BoardReviewItemIndex previous = latestQuestions(t);
		List<Answer> same = List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "settled"),
				new Answer("q2", BoardReviewItemStatus.RESOLVED, "also settled"));

		AgentTaskData answered = agentTaskService.answer(t.getUuid(), same, HUMAN, true, WU);
		UUID first = answered.getReleases().get(answered.getReleases().size() - 1);

		// The retry: same inputs, re-derived. It must not cut a second round.
		ReleaseData again = agentDocumentService.publishAnswerRound(reload(t), previous, same, WU);

		assertEquals(first, again.getUuid(), "the second attempt returns the round the first cut");
		assertEquals(1, reload(t).getReleases().stream()
				.filter(u -> u.equals(first)).count(), "and the task carries it once");
	}

	/** A reservation mid-cut is not a round, and neither is one the sweeper cancelled. */
	@Test
	public void anUnsettledRoundIsNotTreatedAsAnExistingRound() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = escalated(r, "unsettled");
		AgentTaskData answered = agentTaskService.answer(t.getUuid(),
				List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "answered once")), HUMAN, true, WU);
		UUID round = answered.getReleases().get(answered.getReleases().size() - 1);

        // Walk it back to CANCELLED, which is what rejectPendingReleases leaves behind when a cut
        // was abandoned, and check the equivalence lookup stops recognising it.
		ossReleaseService.updateReleaseLifecycle(round, ReleaseLifecycle.CANCELLED, WU);

		// The chain falls back to the last round that settled -- here the round that asked -- so
		// the questions read OPEN again rather than the task carrying an answer nothing can read.
		// That is the sweeper's CANCELLED working as a stale signal for free.
		BoardReviewItemIndex stillOpen = latestQuestions(t);
		assertNotNull(stillOpen, "the last settled round is still the task's state");
		assertEquals(BoardReviewItemStatus.OPEN, find(stillOpen, "q1").status(),
				"the cancelled answer is not the latest word on q1");
		assertEquals(2, stillOpen.openReviewItems().size(), "both questions are open again");
	}

	// ---------- D11: an agent answer closes the ids too ----------

	@Test
	public void anAgentAnswerClosesTheAskedIdsAsWell() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), null, "agent answers", null, null,
				null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null,
				COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(),
				WU);
		ReleaseData q = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, item("q1", "OPEN")));
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"asking the designer", List.of(q.getUuid()), WU);

		// The designer answers by republishing the design: prose, carrying no index of its own.
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(),
				WU);
		ReleaseData design = agentDocumentService.publish(r.sessB(), new PublishRequest(
				t.getUuid(), RearmSpecificationType.ARCHITECTURE, null, "docs/design.md",
				"sha256:" + UUID.randomUUID().toString().replace("-", ""), "text/markdown", null,
				null, null, "c" + SEQ.incrementAndGet(), DOCS, "design", null,
				null), WU);
		AgentTaskData back = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(),
				SignOffOutcome.PASSED, "answered in the design", List.of(design.getUuid()), WU);

		assertEquals("coder", back.getRole(), "the asker gets it back");
		// And the board closed the id on the asker's behalf, so a human and an agent answering
		// leave the same record rather than one leaving the id open forever.
		BoardReviewItemIndex latest = latestQuestions(t);
		assertEquals(BoardReviewItemStatus.RESOLVED, find(latest, "q1").status());
		assertEquals(design.getUuid().toString(), find(latest, "q1").resolvedBy(),
				"pointing at the release that answered");
	}

	/**
	 * A policy stop has to actually cut its round.
	 *
	 * <p>Here because the effect that carries it is collected by the router and folded into the
	 * hop's effects by a merge that copied some fields and not others: the policy round was
	 * dropped on the floor, the stop was recorded, and the items it stopped on stayed OPEN for the
	 * life of the task. Nothing asserted the round existed, so nothing noticed.
	 */
	@Test
	public void aPolicyStopCutsTheRoundThatClosesWhatItStoppedOn() throws RelizaException {
		Rig r = rig();
		// blockingPriority 1 so a P1 item routes; noProgressRepeats 1 so one repeat stops;
		// completionPriority 0 so nothing blocks completion and the stop completes the task,
		// accepting by policy whatever is still open.
		agentBoardService.updateSettings(r.board().getUuid(), null, null, null, 1, 1, 0, WU);
		AgentTaskData t = agentTaskService.register(board(r), null, "goes nowhere", null, null,
				null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null,
				COORD, WU);

		// The coder asks about the architecture.
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(),
				WU);
		ReleaseData q1 = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, item("q1", "OPEN")));
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"round one", List.of(q1.getUuid()), WU);

		// The designer passes, so the frame unwinds and the coder gets the task back.
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(),
				WU);
		ReleaseData design = agentDocumentService.publish(r.sessB(), new PublishRequest(
				t.getUuid(), RearmSpecificationType.ARCHITECTURE, null, "docs/design.md",
				"sha256:" + UUID.randomUUID().toString().replace("-", ""), "text/markdown", null,
				null, null, "c" + SEQ.incrementAndGet(), DOCS, "design", null,
				null), WU);
		agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED,
				"answered in the design", List.of(design.getUuid()), WU);

		// The coder asks for exactly the same id again: its OWN previous round said the same
		// thing, which is the comparison the no-progress rule makes.
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(),
				WU);
		Map<String, Object> reworded = questions(RearmSpecificationType.ARCHITECTURE,
				item("q1", "OPEN"));
		((List<Map<String, Object>>) reworded.get("reviewItems")).get(0)
				.put("title", "still unclear");
		ReleaseData q2 = publishQuestions(r.sessA(), reload(t), reworded);
		AgentTaskData stopped = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "still unclear", List.of(q2.getUuid()), WU);

		assertEquals(TaskStatus.COMPLETED, stopped.getStatus(), "the loop ran out and ended");
		BoardReviewItemIndex latest = latestQuestions(t);
		assertEquals(BoardReviewItemStatus.POLICY_ACCEPTED, find(latest, "q1").status(),
				"and the round that says so was cut, rather than the item staying open forever");
		assertNotNull(find(latest, "q1").resolution(), "with the stop's reason in words");
		// The stop closed the item, so the item points at the round that recorded the stop --
		// which is how a later round that carries it forward still says who closed it and when.
		UUID policyRound = reload(t).getReleases().get(reload(t).getReleases().size() - 1);
		assertEquals(policyRound.toString(), find(latest, "q1").resolvedBy());
	}

	/**
	 * The no-progress rule compares a role against itself.
	 *
	 * <p>The answering role closes the ids it was asked about when it answers with a round of its
	 * own, so its round always looks like progress. Counting it reset the tally and let a role
	 * re-ask exactly what it asked before; only the cycle cap bounded the loop.
	 */
	@Test
	public void anAnswerersOwnRoundDoesNotResetTheNoProgressCount() throws RelizaException {
		Rig r = rig();
		agentBoardService.updateSettings(r.board().getUuid(), null, null, null, 1, 1, null, WU);
		AgentTaskData t = agentTaskService.register(board(r), null, "round and round", null, null,
				null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null,
				COORD, WU);

		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(),
				WU);
		ReleaseData q1 = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, item("q1", "OPEN")));
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"asking", List.of(q1.getUuid()), WU);

		// The designer answers with a round of its OWN, closing q1. That round belongs to the
		// answerer, and must not count as the asker having made progress.
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(),
				WU);
		ReleaseData answer = publishQuestions(r.sessB(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, item("q1", "RESOLVED")));
		agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED,
				"answered", List.of(answer.getUuid()), WU);

		// The coder is unconvinced and asks for the same id again.
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(),
				WU);
		Map<String, Object> reask = questions(RearmSpecificationType.ARCHITECTURE,
				item("q1", "OPEN"));
		((List<Map<String, Object>>) reask.get("reviewItems")).get(0).put("title", "still unclear");
		ReleaseData q2 = publishQuestions(r.sessA(), reload(t), reask);
		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "same question", List.of(q2.getUuid()), WU);

		assertEquals(TaskStatus.ON_HOLD, after.getStatus(),
				"the re-ask is caught by no-progress, not left to the cycle cap");
		assertTrue(after.getHold().reason().contains("no progress"), after.getHold().reason());
	}

	/** A human must not cancel a hop that is already running. */
	@Test
	public void answeringIsRefusedWhileAnAgentIsWorkingTheTask() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), null, "mid-hop", null, null, null,
				null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null,
				COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(),
				WU);
		ReleaseData q = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, item("q1", "OPEN")));
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"asking", List.of(q.getUuid()), WU);
		// The designer picks the question up and is mid-hop.
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(),
				WU);
		assertEquals(TaskStatus.ASSIGNED, reload(t).getStatus());

		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.answer(
				t.getUuid(), List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "never mind")),
				HUMAN, true, WU));

		assertTrue(e.getMessage().contains("right now"), e.getMessage());
		assertEquals(TaskStatus.ASSIGNED, reload(t).getStatus(), "the hop is left alone");
	}

	/** Releasing a QUESTION hold with words answers; releasing any other hold with words notes. */
	@Test
	public void liftingAQuestionHoldWithWordsAnswersTheOpenIds() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = escalated(r, "release is an answer");

		AgentTaskData released = agentTaskService.liftHoldOrAnswer(t.getUuid(),
				HoldLevel.OPERATOR, HUMAN, "the platform team owns it", WU);

		assertEquals("coder", released.getRole(), "the asker gets it back");
		BoardReviewItemIndex latest = latestQuestions(t);
		assertEquals(BoardReviewItemStatus.RESOLVED, find(latest, "q1").status());
		assertEquals("the platform team owns it", find(latest, "q1").resolution(),
				"the words became the answer rather than being dropped");
	}

	@Test
	public void liftingAnOrdinaryHoldWithWordsDoesNotAnswerAnything() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = escalated(r, "not an answer");
		// Same task, same open questions -- but an ordinary operator hold over the top of it.
		agentTaskService.liftHold(t.getUuid(), HoldLevel.OPERATOR, COORD, null, WU);
		agentTaskService.hold(t.getUuid(), HoldLevel.OPERATOR, "waiting on infra", HUMAN, WU);

		agentTaskService.liftHoldOrAnswer(t.getUuid(), HoldLevel.OPERATOR, HUMAN,
				"infra is back", WU);

		BoardReviewItemIndex latest = latestQuestions(t);
		assertEquals(BoardReviewItemStatus.OPEN, find(latest, "q1").status(),
				"an unrelated resume must never be recorded as an answer");
		assertTrue(agentBoardService.recentEvents(board(r).getUuid()).stream().anyMatch(e -> null != e.message()
				&& e.message().contains("infra is back")), "the words are kept as a note");
	}

	/** Legacy pointer text converts on the agent publish path too, not only on board-cut rounds. */
	@Test
	public void anAgentPublishedIndexConvertsLegacyPointerText() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), null, "legacy on publish", null,
				null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null,
				COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(),
				WU);

		Map<String, Object> idx = questions(RearmSpecificationType.ARCHITECTURE,
				item("q1", "OPEN"));
		Map<String, Object> old = item("legacy", "RESOLVED");
		old.put("resolvedBy", "fixed on the call");
		((List<Map<String, Object>>) idx.get("reviewItems")).add(old);
		ReleaseData rd = publishQuestions(r.sessA(), reload(t), idx);

		BoardReviewItem converted = find(rd.getDocument().reviewItems(), "legacy");
		assertNull(converted.resolvedBy(), "text is not a pointer");
		assertEquals("fixed on the call", converted.resolution(), "and the words are kept");

		// And the same publish repeated is still the same round: identity is taken from the
		// converted form on both sides, so conversion cannot split one round into two.
		ReleaseData again = publishQuestions(r.sessA(), reload(t), idx);
		assertEquals(rd.getUuid(), again.getUuid());
	}

	// ---------- D12: only a question hold turns words into an answer ----------

	@Test
	public void theEscalationHoldIsMarkedAsAQuestion() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = escalated(r, "kinded hold");
		assertEquals(HoldKind.QUESTION, reload(t).getHold().kind());
	}

	@Test
	public void anOrdinaryHoldIsNotAQuestionHold() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), null, "paused for other reasons",
				null, null, null, null, null, null, WU);
		AgentTaskData held = agentTaskService.hold(t.getUuid(), HoldLevel.OPERATOR,
				"waiting on infra", HUMAN, WU);
		assertEquals(HoldKind.MANUAL, held.getHold().kind(),
				"so releasing it with words can never become an answer to open questions");
	}

	@Test
	public void escalatingATaskWithNoQuestionIsRefused() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), null, "nothing asked", null, null,
				null, null, null, null, WU);
		assertThrows(RelizaException.class, () -> agentTaskService.escalateQuestion(t.getUuid(),
				HoldLevel.OPERATOR, "escalating what?", COORD, WU));
	}

	// ---------- D13: legacy pointer text converts rather than refusing ----------

	@Test
	public void legacyResolvedByTextIsConvertedOnCarryForward() {
		BoardReviewItem legacy = new BoardReviewItem("f1", 1, BoardReviewItemStatus.RESOLVED, "old one", null,
				"fixed in commit abc123", null, null, null);
		BoardReviewItem pointer = new BoardReviewItem("f2", 1, BoardReviewItemStatus.RESOLVED, "new one", null,
				UUID.randomUUID().toString(), "done", null, null);

		List<BoardReviewItem> carried = BoardReviewItemIndex.carryForward(List.of(legacy, pointer));

		assertNull(carried.get(0).resolvedBy(), "the text is not a pointer, so it is cleared");
		assertEquals("fixed in commit abc123", carried.get(0).resolution(),
				"and the words are kept rather than lost");
		assertEquals(pointer.resolvedBy(), carried.get(1).resolvedBy(), "a uuid passes through");
		assertEquals("done", carried.get(1).resolution());
	}

	@Test
	public void anAnswerRoundOverLegacyDataIsNotRefused() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = escalated(r, "legacy carry");
		// A round written before resolvedBy became a pointer: text where the uuid now goes.
		BoardReviewItemIndex previous = latestQuestions(t);
		List<BoardReviewItem> withLegacy = new ArrayList<>(previous.reviewItems());
		withLegacy.add(new BoardReviewItem("old", 1, BoardReviewItemStatus.RESOLVED, "settled earlier", null,
				"agreed on the call", null, null, null));
		BoardReviewItemIndex seeded = new BoardReviewItemIndex(previous.kind(), previous.round(),
				previous.verdict(), previous.counts(), withLegacy, previous.about());

		ReleaseData round = agentDocumentService.publishAnswerRound(reload(t), seeded,
				List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "answered")), WU);

		BoardReviewItem converted = find(round.getDocument().reviewItems(), "old");
		assertNull(converted.resolvedBy());
		assertEquals("agreed on the call", converted.resolution());
	}

	// ---------- the role contract: what a role consumes and produces ----------

	/**
	 * A role's produced outputs are the board's routing graph, and nothing could set them.
	 *
	 * <p>The input type had no field for them at all and the resolver built the spec with the
	 * short constructor, so every role on every board declared nothing. Questions then had no
	 * producer to go to and every one of them escalated to the coordinator -- which is what every
	 * board on the sandbox was doing when this was found.
	 */
	@Test
	public void aRoleCarriesWhatItConsumesAndProduces() throws RelizaException {
		Rig r = rig();
		AgentTaskRoleConfigData saved = agentBoardService.upsertRoleConfig(board(r),
				new AgentBoardService.RoleConfigSpec("scribe", "write it", 30, null, false, true,
						null, null, null, null,
						List.of(new RequiredInput(InputKind.DOCUMENT,
								RearmSpecificationType.REQUIREMENTS, InputScope.TASK, null,
								ReleaseLifecycle.ASSEMBLED, InputResolution.STRICT_LATEST)),
						List.of(new ProducedOutput(RearmSpecificationType.DETAILED_DESIGN,
								InputScope.COMPONENT, true))),
				true, WU);

		assertEquals(1, saved.getProducesOutputs().size());
		assertEquals(RearmSpecificationType.DETAILED_DESIGN,
				saved.getProducesOutputs().get(0).specification());
		assertEquals(1, saved.getRequiredInputs().size());
		assertEquals(RearmSpecificationType.REQUIREMENTS,
				saved.getRequiredInputs().get(0).specification());
	}

	/** A preset carries the contract onto every board seeded from it. */
	@Test
	public void seedingCopiesTheContractFromThePreset() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		agentBoardService.upsertPreset(org.getUuid(), new AgentBoardService.RoleConfigSpec(
				"designer", "design it", 10, null, false, true, null, null, null, null, null,
				List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE,
						InputScope.COMPONENT, true))), WU);
		Component target = componentService.createComponent("node_" + UUID.randomUUID(),
				org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData fresh = agentBoardService.createBoard(org.getUuid(),
				"seeded-" + UUID.randomUUID(), "from presets", List.of(), null, 4, null,
				target.getUuid(), null, WU);

		agentBoardService.seedFromPresets(fresh, WU);

		AgentTaskRoleConfigData seeded = agentBoardService
				.getRoleConfig(fresh.getUuid(), "designer").orElseThrow();
		assertEquals(1, seeded.getProducesOutputs().size(),
				"setting the contract once on the org's presets is what configures new boards");
		assertEquals(RearmSpecificationType.ARCHITECTURE,
				seeded.getProducesOutputs().get(0).specification());
	}

	/** The contract is operator-only, like prompts and capabilities. */
	@Test
	public void theCoordinatorCannotEditWhatARoleProduces() throws RelizaException {
		Rig r = rig();
		RelizaException e = assertThrows(RelizaException.class,
				() -> agentBoardService.upsertRoleConfig(board(r),
						new AgentBoardService.RoleConfigSpec("coder", null, null, null, null, null,
								null, null, null, null, null,
								List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE,
										InputScope.COMPONENT, true))),
						false, WU));
		assertTrue(e.getMessage().contains("operator-only"), e.getMessage());
	}

	/** The export carries both halves, so a board spec round-trips what routing depends on. */
	@Test
	public void theBoardExportCarriesProducedOutputs() throws RelizaException {
		Rig r = rig();
		AgentBoardService.BoardSpecDto spec = agentBoardService.exportBoard(r.board().getUuid());
		AgentBoardService.BoardRoleSpecDto designer = spec.getRoles().stream()
				.filter(x -> "designer".equals(x.getName())).findFirst().orElseThrow();
		assertEquals(1, designer.getProducesOutputs().size(),
				"declared on the schema type since the start, and null for every caller until now");
		assertEquals(RearmSpecificationType.ARCHITECTURE,
				designer.getProducesOutputs().get(0).getSpecification());
	}

	/**
	 * BOARD_QUESTIONS is refused as a required input, on every path that accepts one.
	 *
	 * <p>Service-level, because the rule belongs to what the value MEANS to a role rather than to
	 * one resolver: a question is what a hop emits when it cannot proceed, so a role that declared
	 * it as a prerequisite could never start, and one that declared it as an output would be its
	 * own answerer.
	 */
	@Test
	public void aQuestionIsNotSomethingARoleCanContractOn() throws RelizaException {
		Rig r = rig();
		RelizaException asInput = assertThrows(RelizaException.class,
				() -> agentBoardService.upsertRoleConfig(board(r),
						new AgentBoardService.RoleConfigSpec("scribe", "write it", 30, null, false,
								true, null, null, null, null,
								List.of(new RequiredInput(InputKind.DOCUMENT,
										RearmSpecificationType.BOARD_QUESTIONS, InputScope.TASK, null,
										ReleaseLifecycle.ASSEMBLED, InputResolution.STRICT_LATEST)),
								null),
						true, WU));
		assertTrue(asInput.getMessage().contains("BOARD_QUESTIONS"), asInput.getMessage());

		RelizaException asOutput = assertThrows(RelizaException.class,
				() -> agentBoardService.upsertRoleConfig(board(r),
						new AgentBoardService.RoleConfigSpec("scribe", "write it", 30, null, false,
								true, null, null, null, null, null,
								List.of(new ProducedOutput(RearmSpecificationType.BOARD_QUESTIONS,
										InputScope.TASK, true))),
						true, WU));
		assertTrue(asOutput.getMessage().contains("BOARD_QUESTIONS"), asOutput.getMessage());
	}

	// ---------- D8: preset selection, with no fallback ----------

	@Test
	public void aBoardWithSourcesWantsTheTrackerPreset() throws RelizaException {
		Rig r = rig(List.of("github:acme/thing"));
		assertEquals("coordinator-tracker", AgentBoardService.coordinatorPresetFor(board(r)));
	}

	@Test
	public void aBoardWithNoSourcesWantsTheBoardTruthPreset() throws RelizaException {
		Rig r = rig();
		assertEquals("coordinator-board-truth", AgentBoardService.coordinatorPresetFor(board(r)));
	}

	@Test
	public void aBoardWhoseCoordinatorPresetIsMissingSaysSoAndSeedsNothing() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(),
				org.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData bare = agentBoardService.createBoard(org.getUuid(),
				"bare-" + UUID.randomUUID(), "no presets here", List.of(), null, 4, null,
				target.getUuid(), null, WU);

		AgentBoardData seeded = agentBoardService.seedFromPresets(bare, WU);

		assertTrue(null == seeded.getCoordinatorPrompt() || seeded.getCoordinatorPrompt().isBlank(),
				"no preset of that name exists, so nothing was copied -- and nothing wrong was");
		AgentBoardData reloaded = agentBoardService.getBoardData(bare.getUuid()).orElseThrow();
		assertTrue(agentBoardService.recentEvents(reloaded.getUuid()).stream().anyMatch(e -> null != e.message()
				&& e.message().contains("coordinator-board-truth")),
				"and the board says which preset it wanted");
	}

	// ---------- D7: the coordinator names who answers ----------

	@Test
	public void authorisingAnUnroutedFrameNamesTheAnsweringRole() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = escalated(r, "coordinator names the answerer");
		agentTaskService.liftHold(t.getUuid(), HoldLevel.OPERATOR, COORD, WU);

		AgentTaskData authorised = agentTaskService.authorize(t.getUuid(), board(r), "designer",
				10, null, null, null, null, COORD, WU);

		assertEquals(r.designer().getUuid(),
				authorised.getQuestionStack().get(authorised.getQuestionStack().size() - 1)
						.answeringRole(),
				"without this the unwind never matches and the asker never hears back");
		assertTrue(authorised.getRequiredInputs().stream()
				.anyMatch(ri -> RearmSpecificationType.BOARD_QUESTIONS == ri.specification()),
				"and the hop is handed what it is being asked");
	}

	@Test
	public void theNamedAnswererPassingUnwindsToTheAsker() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = escalated(r, "named answerer answers");
		agentTaskService.liftHold(t.getUuid(), HoldLevel.OPERATOR, COORD, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "designer", 10, null, null, null, null,
				COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(),
				WU);
		ReleaseData answer = publishQuestions(r.sessB(), reload(t),
				questions(RearmSpecificationType.TEST_PLAN, item("q1", "RESOLVED"),
						item("q2", "RESOLVED")));

		AgentTaskData back = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(),
				SignOffOutcome.PASSED, "answered", List.of(answer.getUuid()), WU);

		assertEquals("coder", back.getRole());
		assertTrue(back.getQuestionStack().isEmpty());
	}

	// ---------- the release note is kept, but is not an answer ----------

	@Test
	public void liftingAnOrdinaryHoldWithWordsKeepsThemOnTheBoard() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), null, "note on release", null, null,
				null, null, null, null, WU);
		agentTaskService.hold(t.getUuid(), HoldLevel.OPERATOR, "waiting on infra", HUMAN, WU);

		agentTaskService.liftHold(t.getUuid(), HoldLevel.OPERATOR, HUMAN, "infra is back", WU);

		assertTrue(agentBoardService.recentEvents(board(r).getUuid()).stream().anyMatch(e -> null != e.message()
				&& e.message().contains("infra is back")),
				"the words are kept where a human can see them");
		assertFalse(agentDocumentService.latestIndexes(reload(t))
				.containsKey(RearmSpecificationType.BOARD_QUESTIONS),
				"and no round was cut, because nothing was being answered");
	}
}
