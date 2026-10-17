/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
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

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentTaskData.QuestionFrame;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.TaskHold;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
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
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.oss.TestInitializer;

/**
 * The loop the board runs: who gets a task next, how a question travels and comes back, and what
 * stops a loop that is not converging.
 *
 * <p>These are integration tests rather than unit tests of the router because the decision depends
 * on the whole arrangement -- what the roles declare, which documents exist and at what maturity,
 * what the previous round said. A router tested against mocks of all three would be testing the
 * mocks.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {io.reliza.ws.App.class})
public class AgentBoardMechanicsIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/mechanics";
	private static final String DOCS_SOURCE = "github:acme/mechanics-docs";
	private static final String DOCS = "https://github.com/acme/mechanics-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AtomicInteger ISSUE = new AtomicInteger(5000);

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private ComponentService componentService;
	@Autowired private SharedReleaseService sharedReleaseService;

	/**
	 * A board with a designer that produces the architecture and a coder that consumes it.
	 *
	 * <p>Two roles with a real input relationship, because that relationship IS the graph: nothing
	 * declares an edge from coder back to designer, and the router has to derive it from what the
	 * designer says it produces.
	 */
	private record Rig(Organization org, AgentBoardData board, AgentTaskRoleConfigData designer,
			AgentTaskRoleConfigData coder, AgentData workerA, AgentSessionData sessA,
			AgentData workerB, AgentSessionData sessB) {}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(),
				"mech-" + UUID.randomUUID(), "mechanics board", List.of(SRC, DOCS_SOURCE),
				"coordinate", 4, null, target.getUuid(), null, WU);
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

	private AgentTaskData task(Rig r, String title) throws RelizaException {
		return agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), title,
				null, null, null, null, null, null, WU);
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	// ---------- questions ----------

	private static Map<String, Object> item(String id, String status) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("id", id);
		f.put("priority", 1);
		f.put("status", status);
		f.put("title", "question " + id);
		return f;
	}

	/** A BOARD_QUESTIONS index about an input, which is what makes it routable. */
	@SafeVarargs
	private static Map<String, Object> questions(RearmSpecificationType about, UUID release,
			Map<String, Object>... items) {
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", "BOARD_QUESTIONS");
		idx.put("verdict", "REJECTED");
		idx.put("reviewItems", new ArrayList<>(List.of(items)));
		Map<String, Object> ab = new LinkedHashMap<>();
		ab.put("specification", about.name());
		ab.put("release", null != release ? release.toString() : null);
		idx.put("about", ab);
		return idx;
	}

	/** Publish an index with no file, which is the usual shape for questions. */
	private ReleaseData publishQuestions(AgentSessionData session, AgentTaskData td,
			Map<String, Object> index) throws RelizaException {
		return agentDocumentService.publish(session,
				new PublishRequest(td.getUuid(), RearmSpecificationType.BOARD_QUESTIONS, null,
						null, null, null, null, null, index, null, null, null, null,
						null), WU);
	}

	@Test
	public void anIndexOnlyRoundNeedsNoFileAndNoRepository() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "index only");
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);

		ReleaseData rd = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));

		assertNotNull(rd.getDocument());
		assertNull(rd.getDocument().path(), "the items are the document");
		assertNull(rd.getDocument().digest());
		assertNotNull(rd.getDocument().reviewItems());
		assertEquals(RearmSpecificationType.ARCHITECTURE,
				rd.getDocument().reviewItems().about().specification());
	}

	@Test
	public void theSameQuestionsPublishedTwiceAreOneRound() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "idempotent questions");
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		Map<String, Object> idx = questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN"));

		ReleaseData first = publishQuestions(r.sessA(), reload(t), idx);
		ReleaseData again = publishQuestions(r.sessA(), reload(t), idx);

		// No commit and no file digest, so identity is the index itself: a retry after a dropped
		// response must not mint a second round of the same questions.
		assertEquals(first.getUuid(), again.getUuid());
	}

	@Test
	public void openQuestionsGoToTheRoleThatProducesWhatTheyAreAbout() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "coder asks the designer");
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData q = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));

		AgentTaskData routed = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "need the design settled", List.of(q.getUuid()), WU);

		// Nothing declares coder -> designer. The board derived it: the items are about the
		// architecture, and the designer is the role that produces one.
		assertEquals(TaskStatus.QUEUED, routed.getStatus());
		assertEquals("designer", routed.getRole());
		assertEquals(1, routed.getQuestionStack().size(), "who is waiting on whom is recorded");
		assertEquals(r.designer().getUuid(), routed.getQuestionStack().get(0).answeringRole());
		assertEquals(r.coder().getUuid(), routed.getQuestionStack().get(0).askingRole());
	}

	@Test
	public void anAnswerUnwindsTheStackBackToTheRoleThatAsked() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "and back again");
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData q = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"asking", List.of(q.getUuid()), WU);

		// the designer answers: a round of the questioned document that closes the id
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData answer = publishQuestions(r.sessB(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "RESOLVED")));
		AgentTaskData back = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(),
				SignOffOutcome.PASSED, "answered", List.of(answer.getUuid()), WU);

		assertEquals("coder", back.getRole(), "the asker gets its answer back");
		assertTrue(back.getQuestionStack().isEmpty(), "and the frame is popped");
	}

	@Test
	public void questionsAboutSomethingNoRoleProducesGoToTheCoordinator() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "nobody makes a test plan here");
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData q = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.TEST_PLAN, null, item("q1", "OPEN")));

		AgentTaskData routed = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "who owns the test plan?", List.of(q.getUuid()), WU);

		assertEquals(TaskStatus.AWAITING_COORDINATOR, routed.getStatus());
		assertEquals(1, routed.getQuestionStack().size(), "the question is still outstanding");
		assertNull(routed.getQuestionStack().get(0).answeringRole(),
				"nobody is named as the answerer, which is what the coordinator decides");
	}

	// ---------- stops ----------

	@Test
	public void aLoopThatRepeatsTheSameQuestionsParksForAHuman() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "going nowhere");
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData q1 = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"round one", List.of(q1.getUuid()), WU);

		// The designer answers, so the frame unwinds and the coder gets its task back.
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData answer = publishQuestions(r.sessB(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "RESOLVED")));
		agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED,
				"answered", List.of(answer.getUuid()), WU);

		// The coder asks for the same id again, reworded.
		//
		// Reworded deliberately. A byte-identical index is the SAME round -- publish deduplicates
		// it -- so a loop that is going nowhere does not look like one repeated release; it looks
		// like successive rounds asking for the same ids in different words, which is exactly
		// what the no-progress rule compares. Ids, never prose, never digests.
		//
		// And compared against THIS ROLE's previous round, not the task's. The round in between
		// belongs to the designer and closed q1, so counting it would read as progress no matter
		// what the coder did. Two roles bouncing the same ids between them is a different shape
		// and the cycle cap's job.
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		Map<String, Object> reworded = questions(RearmSpecificationType.ARCHITECTURE, null,
				item("q1", "OPEN"));
		((List<Map<String, Object>>) reworded.get("reviewItems")).get(0)
				.put("title", "still unclear: which is right?");
		ReleaseData q2 = publishQuestions(r.sessA(), reload(t), reworded);
		AgentTaskData parked = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "still unclear", List.of(q2.getUuid()), WU);

		// Under the default tolerance the first repeat parks: another turn of this loop costs a
		// hop to learn the same thing.
		// The first such stop on the task parks for the coordinator (task c0a2134c).
		assertEquals(TaskStatus.ON_HOLD, parked.getStatus());
		assertEquals(AgentTaskData.HoldLevel.COORDINATOR, parked.getHold().level());
		assertEquals(AgentTaskData.HoldStop.NO_PROGRESS, parked.getHold().stop());
		assertTrue(parked.getHold().reason().contains("no progress"), parked.getHold().reason());
	}

	/**
	 * The coder asks q1 about the design, the designer answers, and the coder is back: the state
	 * both stop tests start from.
	 */
	private AgentTaskData askedAndAnswered(Rig r, String title) throws RelizaException {
		AgentTaskData t = task(r, title);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData q1 = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"round one", List.of(q1.getUuid()), WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData answer = publishQuestions(r.sessB(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "RESOLVED")));
		agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED,
				"answered", List.of(answer.getUuid()), WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		return reload(t);
	}

	@Test
	public void aNoProgressHoldNamesTheItemTheRoundAndTheRule() throws RelizaException {
		// gaps §1.28, seen on d0ee3624: a narrowed review item carried under its id parked the task as
		// "no progress" with nothing saying which rule tripped or what would have avoided it.
		Rig r = rig();
		AgentTaskData t = askedAndAnswered(r, "narrowed, same id");
		Map<String, Object> narrowed = questions(RearmSpecificationType.ARCHITECTURE, null,
				item("q1", "OPEN"));
		((List<Map<String, Object>>) narrowed.get("reviewItems")).get(0).put("title", "q1, the part that remains");
		ReleaseData q2 = publishQuestions(r.sessA(), reload(t), narrowed);
		AgentTaskData parked = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "narrowed", List.of(q2.getUuid()), WU);

		assertEquals(TaskStatus.ON_HOLD, parked.getStatus());
		String reason = parked.getHold().reason();
		assertTrue(reason.startsWith("stopped by no progress: "), reason);
		assertTrue(reason.contains("[q1] stayed OPEN after a designer round (repeat 1 of 1)"), reason);
		assertTrue(reason.contains("re-raised under a new id"), reason);
		assertTrue(reason.contains("1 item(s) still open: [q1]"), reason);
		// the first stop of its kind is the coordinator's: an INFO it reads, nobody paged (task c0a2134c)
		assertTrue(agentBoardService.recentEvents(board(r).getUuid()).stream().anyMatch(ev -> ev.kind() == AgentBoardData.BoardEventKind.INFO
				&& ev.message().contains("stopped by no progress")
				&& ev.message().contains("[q1] stayed OPEN after a designer round (repeat 1 of 1)")
				&& ev.message().contains("re-raised under a new id")),
				"the alert names the rule as the hold does: " + agentBoardService.recentEvents(board(r).getUuid()));
	}

	@Test
	public void theCoordinatorIsToldWhatItCanDoWithAStopAndAPersonStillReleases() throws RelizaException {
		// task bb2b4bcb: a loop stop the coordinator may not release stays the operator's; the refusal
		// says what the seat can do. Here because the board keeps every stop for the operator
		// (coordinatorStopLift false, task c0a2134c).
		Rig r = rig();
		agentBoardService.updateSettingsFromInput(r.board().getUuid(), Map.of("coordinatorStopLift", false), WU);
		AgentTaskData t = askedAndAnswered(r, "stopped, then released by a person");
		ReleaseData q2 = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));
		AgentTaskData parked = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "same again", List.of(q2.getUuid()), WU);
		AgentTaskData.TaskHold hold = parked.getHold();
		assertEquals(AgentTaskData.HoldLevel.OPERATOR, hold.level());

		RelizaException refused = assertThrows(RelizaException.class, () -> agentTaskService.liftHold(
				t.getUuid(), AgentTaskData.HoldLevel.COORDINATOR, COORD, WU));
		assertTrue(refused.getMessage().contains("the coordinator cannot lift it"), refused.getMessage());
		assertTrue(refused.getMessage().contains("Post an ALERT with your recommendation for the operator, or cancel the task"),
				refused.getMessage());
		AgentTaskData.TaskHold after = reload(t).getHold();
		assertEquals(TaskStatus.ON_HOLD, reload(t).getStatus());
		assertEquals(hold.level(), after.level(), "the refusal leaves the hold as it was");
		assertEquals(hold.kind(), after.kind());
		assertEquals(hold.reason(), after.reason());

		AgentActor person = AgentActor.ofUser(UUID.randomUUID(), "operator");
		agentTaskService.liftHold(t.getUuid(), AgentTaskData.HoldLevel.OPERATOR, person, WU);
		assertTrue(reload(t).getStatusHistory().stream().anyMatch(sc -> sc.trigger() == AgentTaskData.StatusTrigger.LIFT_HOLD
				&& person.equals(sc.actor())), "a person's release goes through and routes from the last hop");
	}

	// ---------- releasing a stop hold overrides that stop once (task 4c566d0d) ----------

	private static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "operator");

	/** The coder asks q1 again after the designer answered it: parked by no progress. */
	private AgentTaskData stoppedByNoProgress(Rig r, String title) throws RelizaException {
		AgentTaskData t = askedAndAnswered(r, title);
		ReleaseData q2 = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));
		AgentTaskData parked = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "same again", List.of(q2.getUuid()), WU);
		assertEquals(TaskStatus.ON_HOLD, parked.getStatus());
		assertTrue(parked.getHold().reason().startsWith("stopped by no progress"), parked.getHold().reason());
		return parked;
	}

	private int cycles(AgentTaskData t) {
		return reload(t).getCycles().values().stream().mapToInt(Integer::intValue).sum();
	}

	private List<String> infos(Rig r) {
		return agentBoardService.recentEvents(board(r).getUuid()).stream().filter(ev -> ev.kind() == AgentBoardData.BoardEventKind.INFO)
				.map(ev -> ev.message()).toList();
	}

	@Test
	public void liftingANoProgressHoldRoutesToTheProducer() throws RelizaException {
		Rig r = rig();
		AgentTaskData parked = stoppedByNoProgress(r, "released past the stop");
		int before = cycles(parked);
		UUID stoppingRound = parked.getSignOffs().get(parked.getSignOffs().size() - 1).outputs().get(0);

		AgentTaskData released = agentTaskService.liftHold(parked.getUuid(), AgentTaskData.HoldLevel.OPERATOR,
				PERSON, "continue with the designer, then test round", null, WU);
		assertEquals(TaskStatus.QUEUED, released.getStatus(), "routed, not re-held by the same stop");
		assertEquals("designer", released.getRole(), "to the role routing picks: the producer of what was asked about");
		assertNull(released.getHold());
		assertEquals(before + 1, cycles(released), "the cycle is still counted");
		QuestionFrame top = released.getQuestionStack().get(released.getQuestionStack().size() - 1);
		assertEquals(stoppingRound, top.questionsRelease(), "the round that stopped the task is what the designer reads");
		assertTrue(infos(r).stream().anyMatch(m -> m.contains("by operator (1 of 1 for no progress): continue with the designer, then test round")
				&& m.contains("stop overridden once, routed to designer")), infos(r).toString());
	}

	@Test
	public void liftingWithARoleRoutesThere() throws RelizaException {
		Rig r = rig();
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("architect", "decide it", 5,
				null, false, true, null, null, null, null, List.of(), List.of()), true, WU);
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("retired", "gone", 30,
				null, false, false, null, null, null, null, List.of(), List.of()), true, WU);
		AgentTaskData parked = stoppedByNoProgress(r, "released to a named role");
		String reason = parked.getHold().reason();

		for (String bad : List.of("nobody", "retired")) {
			RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.liftHold(parked.getUuid(),
					AgentTaskData.HoldLevel.OPERATOR, PERSON, null, bad, WU));
			assertEquals("Role " + bad + " is not active on this board", e.getMessage());
			assertEquals(TaskStatus.ON_HOLD, reload(parked).getStatus(), "refused before anything is written");
			assertEquals(reason, reload(parked).getHold().reason());
		}

		AgentTaskData released = agentTaskService.liftHold(parked.getUuid(), AgentTaskData.HoldLevel.OPERATOR,
				PERSON, null, "architect", WU);
		assertEquals(TaskStatus.QUEUED, released.getStatus());
		assertEquals("architect", released.getRole(), "the person's choice, not routing's");
		assertTrue(infos(r).stream().anyMatch(m -> m.contains("stop overridden once, routed to architect")), infos(r).toString());
	}

	@Test
	public void aSecondIdenticalStopStillFires() throws RelizaException {
		Rig r = rig();
		AgentTaskData parked = stoppedByNoProgress(r, "stopped twice");
		agentTaskService.liftHold(parked.getUuid(), AgentTaskData.HoldLevel.OPERATOR, PERSON, "once more", null, WU);
		// the designer answers again, the coder asks the same thing again
		agentTaskService.assign(parked.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		Map<String, Object> answeredAgain = questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "RESOLVED"));
		((List<Map<String, Object>>) answeredAgain.get("reviewItems")).get(0).put("resolution", "the same answer, again");
		ReleaseData answer = publishQuestions(r.sessB(), reload(parked), answeredAgain);
		agentTaskService.signOff(parked.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED, "answered again",
				List.of(answer.getUuid()), WU);
		agentTaskService.assign(parked.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		Map<String, Object> askedAgain = questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN"));
		((List<Map<String, Object>>) askedAgain.get("reviewItems")).get(0).put("title", "q1, asked a third time");
		ReleaseData q3 = publishQuestions(r.sessA(), reload(parked), askedAgain);
		AgentTaskData again = agentTaskService.signOff(parked.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"and again", List.of(q3.getUuid()), WU);
		assertEquals(TaskStatus.ON_HOLD, again.getStatus(), "the override was for one pass");
		assertTrue(again.getHold().reason().startsWith("stopped by no progress"), again.getHold().reason());
	}

	@Test
	public void aCycleCapReleaseOverridesOnce() throws RelizaException {
		Rig r = rig();
		agentBoardService.updateSettings(r.board().getUuid(), null, null, 1, null, null, null, WU);
		AgentTaskData t = askedAndAnswered(r, "capped, released, capped");
		ReleaseData q2 = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q2", "OPEN")));
		AgentTaskData parked = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "another question", List.of(q2.getUuid()), WU);
		assertTrue(parked.getHold().reason().startsWith("stopped by cycle cap"), parked.getHold().reason());

		AgentTaskData released = agentTaskService.liftHold(t.getUuid(), AgentTaskData.HoldLevel.OPERATOR,
				PERSON, null, null, WU);
		assertEquals(TaskStatus.QUEUED, released.getStatus());
		assertEquals("designer", released.getRole());
		assertEquals(2, cycles(released), "counted past the cap: the next round stops again");

		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData answer = publishQuestions(r.sessB(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q2", "RESOLVED")));
		agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED, "answered",
				List.of(answer.getUuid()), WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData q3 = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q3", "OPEN")));
		AgentTaskData again = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"a third question", List.of(q3.getUuid()), WU);
		assertTrue(again.getHold().reason().startsWith("stopped by cycle cap"), again.getHold().reason());
	}

	@Test
	public void aManualHoldReleaseIsUnchangedAndTakesARole() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "held by hand");
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.hold(t.getUuid(), AgentTaskData.HoldLevel.OPERATOR, "waiting on legal", PERSON, WU);
		assertFalse(AgentTaskService.isLoopStop(reload(t).getHold()), "a person's hold is not a loop stop");
		AgentTaskData plain = agentTaskService.liftHold(t.getUuid(), AgentTaskData.HoldLevel.OPERATOR, PERSON, WU);
		assertEquals(TaskStatus.QUEUED, plain.getStatus(), "routes from nothing, as before");
		assertEquals("designer", plain.getRole());

		AgentTaskData u = task(r, "held by hand, released to a role");
		agentTaskService.authorize(u.getUuid(), board(r), "designer", 10, null, null, null, null, COORD, WU);
		agentTaskService.hold(u.getUuid(), AgentTaskData.HoldLevel.OPERATOR, "waiting", PERSON, WU);
		AgentTaskData named = agentTaskService.liftHold(u.getUuid(), AgentTaskData.HoldLevel.OPERATOR, PERSON,
				null, "coder", WU);
		assertEquals(TaskStatus.QUEUED, named.getStatus());
		assertEquals("coder", named.getRole(), "a role on a person's hold is honoured too");
		assertTrue(infos(r).stream().anyMatch(m -> m.contains("— routed to coder") && !m.contains("overridden")),
				infos(r).toString());
	}

	@Test
	public void aCycleCapHoldNamesThePairAndTheCap() throws RelizaException {
		Rig r = rig();
		agentBoardService.updateSettings(r.board().getUuid(), null, null, 1, null, null, null, WU);
		AgentTaskData t = askedAndAnswered(r, "one round allowed");
		// A new id is movement, so this is the cap stopping it, not no-progress.
		ReleaseData q2 = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q2", "OPEN")));
		AgentTaskData parked = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "another question", List.of(q2.getUuid()), WU);

		assertEquals(TaskStatus.ON_HOLD, parked.getStatus());
		String reason = parked.getHold().reason();
		assertTrue(reason.startsWith("stopped by cycle cap: coder ↔ designer went 1 round(s) (cap 1)"), reason);
		assertTrue(reason.contains("1 item(s) still open: [q2]"), reason);
		assertTrue(agentBoardService.recentEvents(board(r).getUuid()).stream().anyMatch(ev -> ev.kind() == AgentBoardData.BoardEventKind.INFO
				&& ev.message().contains("coder ↔ designer went 1 round(s) (cap 1)")),
				"the alert names the pair and the cap: " + agentBoardService.recentEvents(board(r).getUuid()));
	}

	@Test
	public void aTaskWithNothingBlockingCompletesUnderAPolicyStop() throws RelizaException {
		Rig r = rig();
		// P2 items do not block, and nothing at P1 is open at exhaustion, so the loop that ran out
		// completes rather than waking a human.
		agentBoardService.updateSettings(r.board().getUuid(), null, null, null, null, 1, 1, WU);
		AgentTaskData t = task(r, "low priority leftovers");
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);

		Map<String, Object> idx = questions(RearmSpecificationType.ARCHITECTURE, null,
				item("q1", "OPEN"));
		((List<Map<String, Object>>) idx.get("reviewItems")).get(0).put("priority", 2);
		ReleaseData q = publishQuestions(r.sessA(), reload(t), idx);
		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.PASSED, "nothing blocking", List.of(q.getUuid()), WU);

		// A P2 item under blockingPriority=1 does not route and does not stop anything.
		assertFalse(TaskStatus.ON_HOLD == after.getStatus(),
				"an item below the blocking priority is recorded, not routed on");
	}

	// ---------- strength ----------

	@Test
	public void aRoleWithAStrengthFloorDeclinesAWeakerModelAndAcceptsTheFloor() throws RelizaException {
		Rig r = rig();
		AgentTaskRoleConfigData coder = agentBoardService.getRoleConfig(r.board().getUuid(), "coder")
				.orElseThrow();
		assertTrue(coder.admitsStrength(null), "no floor admits everything, which is today's behaviour");
		coder.setRequiredStrength(3.0);
		coder.setStrengthHeadroom(1.0);
		assertFalse(coder.admitsStrength(2.0), "below the floor");
		assertTrue(coder.admitsStrength(3.0), "at the floor");
		assertTrue(coder.admitsStrength(4.0), "within headroom");
		assertFalse(coder.admitsStrength(5.0), "above headroom: a frontier model is not a free upgrade");
		assertFalse(coder.admitsStrength(null),
				"unrated is unknown, not weak, and a role with a floor declines it");
	}

	// ---------- snapshot ----------

	@Test
	public void theSnapshotShowsHoldersDependenciesAndWhatIsWaiting() throws RelizaException {
		Rig r = rig();
		AgentTaskData first = task(r, "first");
		AgentTaskData second = task(r, "second");
		agentTaskService.authorize(first.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(first.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		agentTaskService.authorize(second.getUuid(), board(r), "coder", 20,
				List.of(first.getUuid()), null, null, null, COORD, WU);

		AgentTaskService.BoardSnapshot snap = agentTaskService.snapshot(board(r));

		assertEquals(2, snap.tasks().size());
		var firstSnap = snap.tasks().stream()
				.filter(ts -> ts.task().getUuid().equals(first.getUuid())).findFirst().orElseThrow();
		assertNotNull(firstSnap.holder(), "who holds it, in one read");
		assertEquals(AgentActor.ActorKind.SESSION, firstSnap.holder().kind());
		var secondSnap = snap.tasks().stream()
				.filter(ts -> ts.task().getUuid().equals(second.getUuid())).findFirst().orElseThrow();
		assertEquals(1, secondSnap.dependsOn().size());
		assertEquals(first.getUuid(), secondSnap.dependsOn().get(0).task());
		assertNotNull(secondSnap.dependsOn().get(0).status(),
				"with its status, so a reader can tell whether the wait is over");
	}

	@Test
	public void theGraphqlInputAllowsAnIndexWithNoFile() throws Exception {
		// The service accepted an index-only round before the schema did: path, digest, commit and
		// vcsUri were all String!, so the shape was unreachable through the API. A service that
		// accepts what the API cannot express is a feature nobody can use.
		String schema = new String(getClass().getResourceAsStream("/schema/programmatic.graphqls")
				.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
		int start = schema.indexOf("input AgentDocumentPublishInput {");
		String block = schema.substring(start, schema.indexOf("\n}", start));
		for (String field : List.of("path", "digest", "commit", "vcsUri")) {
			assertFalse(block.contains(field + ": String!"),
					field + " is required in the schema, which makes an index-only round impossible");
		}
	}

	@Test
	public void aRejectionWithNothingToRouteGoesToTheCoordinator() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "refused without saying why");
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);

		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "cannot do this", WU);

		// The bug this pins: with no blocking items the router fell through to the forward path,
		// which picks the first role with no current pass -- and the role that just rejected has
		// none. It re-queued the task to the role that had just refused it, forever, paying for a
		// hop each time, with no cycle counted and no budget checked on that path.
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		assertFalse("coder".equals(after.getRole()) && TaskStatus.QUEUED == after.getStatus(),
				"a rejection must never re-queue the role that rejected");
	}

	@Test
	public void aPassGoesStaleWhenWhatItReadIsRepublished() throws RelizaException {
		Rig r = rig();
		// coder and reviewer both read the architecture; the designer produces it.
		requireArchitecture(r, "coder", 20);
		AgentTaskRoleConfigData reviewer = requireArchitecture(r, "reviewer", 30);
		AgentTaskData t = task(r, "a design that moves under a finished hop");

		// designer publishes the architecture and passes -> coder is queued
		agentTaskService.authorize(t.getUuid(), board(r), "designer", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData v1 = publishArchitecture(r.sessA(), reload(t), "c1", "d1");
		assertEquals("coder", agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.PASSED, "designed", List.of(v1.getUuid()), WU).getRole());

		// coder passes against v1 -> reviewer is queued
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		assertEquals("reviewer", agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(),
				SignOffOutcome.PASSED, "built against v1", WU).getRole());

		// the reviewer asks the designer, who republishes the architecture
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData q = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, v1.getUuid(), item("q1", "OPEN")));
		assertEquals("designer", agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "the design is wrong", List.of(q.getUuid()), WU).getRole());
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData v2 = publishArchitecture(r.sessB(), reload(t), "c2", "d2");
		agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED,
				"redesigned", List.of(v2.getUuid()), WU);

		// the reviewer, back with its answer, passes
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.PASSED, "reviewed the new design", WU);

		// The coder's pass was made against v1 and the design has moved since, so it describes
		// something nobody built. Under strict routing the coder runs again rather than the task
		// completing on a stale pass.
		assertEquals(TaskStatus.QUEUED, after.getStatus());
		assertEquals("coder", after.getRole(), "the stale pass is re-run, not counted");
		assertNotNull(reviewer);
	}

	/** A role that reads the architecture, so a new architecture release invalidates its pass. */
	private AgentTaskRoleConfigData requireArchitecture(Rig r, String name, int order)
			throws RelizaException {
		return agentBoardService.upsertRoleConfig(board(r),
				new AgentBoardService.RoleConfigSpec(name, "do " + name, order, null, false, true,
						null, null, null, null,
						List.of(new RequiredInput(InputKind.DOCUMENT,
								RearmSpecificationType.ARCHITECTURE, InputScope.TASK, null, null,
								InputResolution.STRICT_LATEST)),
						List.of()),
				true, WU);
	}

	/** An architecture round: prose with a pointer, so it carries a file and no index. */
	private ReleaseData publishArchitecture(AgentSessionData session, AgentTaskData td,
			String commit, String digest) throws RelizaException {
		return agentDocumentService.publish(session,
				new PublishRequest(td.getUuid(), RearmSpecificationType.ARCHITECTURE, null,
						"design/" + digest + ".md", digest, "text/markdown", null, null, null,
						commit, DOCS, "design round", ZonedDateTime.now(),
						null), WU);
	}

	// ---------- a hop that asks owes no output (gaps §1.17) ----------

	/** Give a role one required output of its own. */
	private void requireOutput(Rig r, String role, int order, RearmSpecificationType spec) throws RelizaException {
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(role, "do " + role, order,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(spec, InputScope.TASK, true))), true, WU);
	}

	/** A prose document with a pointer, as a coder's note or a tester's report would be. */
	private ReleaseData publishProse(AgentSessionData session, AgentTaskData td, RearmSpecificationType spec)
			throws RelizaException {
		String digest = "sha256:" + UUID.randomUUID().toString().replace("-", "");
		return agentDocumentService.publish(session, new PublishRequest(td.getUuid(), spec, null,
				"impl/" + digest + ".md", digest, "text/markdown", null, null, null,
				"c" + UUID.randomUUID().toString().substring(0, 8), DOCS, "note", ZonedDateTime.now(), null), WU);
	}

	/** The coder, with a required note, assigned on a fresh task and asking about the architecture. */
	private AgentTaskData coderAsking(Rig r, String title) throws RelizaException {
		requireOutput(r, "coder", 20, RearmSpecificationType.DETAILED_DESIGN);
		AgentTaskData t = task(r, title);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		return t;
	}

	@Test
	public void aCoderMayAskWithoutItsDesign() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = coderAsking(r, "unclear design");
		ReleaseData q = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));
		AgentTaskData asked = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"asking", List.of(q.getUuid()), WU);
		assertEquals(TaskStatus.QUEUED, asked.getStatus(), "accepted with no DETAILED_DESIGN");
		assertEquals("designer", asked.getRole());
		assertEquals(1, asked.getQuestionStack().size());
		assertTrue(asked.getRequiredInputs().stream().anyMatch(ri -> ri.specification() == RearmSpecificationType.BOARD_QUESTIONS),
				"the questions are pinned for the designer");
	}

	@Test
	public void askingWithPassedIsRefused() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = coderAsking(r, "asking but passing");
		ReleaseData q = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.sessA().getUuid(), SignOffOutcome.PASSED, "done?", List.of(q.getUuid()), WU));
		assertTrue(e.getMessage().contains("sign off REJECTED"), e.getMessage());
		assertEquals(TaskStatus.ASSIGNED, reload(t).getStatus());
	}

	@Test
	public void aQuestionThatAsksNothingWaivesNothing() throws RelizaException {
		Rig r = rig();
		agentBoardService.updateSettings(r.board().getUuid(), null, null, null, null, 1, null, WU);
		AgentTaskData t = coderAsking(r, "a question below the line");
		// Open, but below the blocking line: it does not route, so it asks nothing.
		Map<String, Object> idx = questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN"));
		((List<Map<String, Object>>) idx.get("reviewItems")).get(0).put("priority", 2);
		ReleaseData q = publishQuestions(r.sessA(), reload(t), idx);
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.sessA().getUuid(), SignOffOutcome.REJECTED, "asking?", List.of(q.getUuid()), WU));
		assertTrue(e.getMessage().contains("DETAILED_DESIGN"), "the note is still owed: " + e.getMessage());
	}

	@Test
	public void theAnsweringHopOwesItsOutputAgain() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = coderAsking(r, "asked, answered, owed");
		ReleaseData q = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED, "asking",
				List.of(q.getUuid()), WU);

		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData answer = publishQuestions(r.sessB(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "RESOLVED")));
		AgentTaskData back = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED,
				"answered", List.of(answer.getUuid()), WU);
		assertEquals("coder", back.getRole(), "the answer brings the task back to the coder");

		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.PASSED, "done", List.of(), WU), "the waiver does not carry over");
		ReleaseData note = publishProse(r.sessA(), reload(t), RearmSpecificationType.DETAILED_DESIGN);
		AgentTaskData done = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED,
				"done", List.of(note.getUuid()), WU);
		assertTrue(TaskStatus.ASSIGNED != done.getStatus() && TaskStatus.ON_HOLD != done.getStatus(),
				"with its note the hop passes and the task moves on: " + done.getStatus());
	}

	@Test
	public void aTesterMayAskToo() throws RelizaException {
		Rig r = rig();
		requireOutput(r, "tester", 30, RearmSpecificationType.BOARD_TEST_REPORT);
		AgentTaskData t = task(r, "untestable as specified");
		agentTaskService.authorize(t.getUuid(), board(r), "tester", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData q = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));
		AgentTaskData asked = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"what should this do?", List.of(q.getUuid()), WU);
		assertEquals(TaskStatus.QUEUED, asked.getStatus(), "accepted with no BOARD_TEST_REPORT");
		assertEquals("designer", asked.getRole());
	}

	@Test
	public void aQuestionOutranksReviewItemsOnTheSameHop() throws RelizaException {
		Rig r = rig();
		requireOutput(r, "reviewer", 30, RearmSpecificationType.BOARD_REVIEW_ITEMS);
		for (boolean questionFirst : List.of(true, false)) {
			AgentTaskData t = task(r, "rejects and asks, question " + (questionFirst ? "first" : "last"));
			agentTaskService.authorize(t.getUuid(), board(r), "reviewer", 10, null, null, null, null, COORD, WU);
			agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
			Map<String, Object> reviewItems = new LinkedHashMap<>();
			reviewItems.put("kind", "BOARD_REVIEW_ITEMS");
			reviewItems.put("verdict", "REJECTED");
			reviewItems.put("reviewItems", new ArrayList<>(List.of(item("F-1", "OPEN"))));
			ReleaseData review = agentDocumentService.publish(r.sessA(), new PublishRequest(t.getUuid(),
					RearmSpecificationType.BOARD_REVIEW_ITEMS, null, null, null, null, null, null, reviewItems, null, null,
					null, null, null), WU);
			ReleaseData q = publishQuestions(r.sessA(), reload(t),
					questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));
			List<UUID> outputs = questionFirst ? List.of(q.getUuid(), review.getUuid())
					: List.of(review.getUuid(), q.getUuid());
			AgentTaskData routed = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
					"rejected, and one question", outputs, WU);
			assertEquals(TaskStatus.QUEUED, routed.getStatus(), "question " + (questionFirst ? "first" : "last"));
			assertEquals("designer", routed.getRole(), "routed on the question, whatever the order");
		}
	}

	@Test
	public void theOrientationSaysRejected() throws Exception {
		String orientation = io.reliza.ws.AgentOrientation.full();
		int start = orientation.indexOf("### 2.5b");
		String section = orientation.substring(start, orientation.indexOf("\n### ", start + 1));
		assertTrue(section.contains("sign off REJECTED"), "§2.5b must tell an asking hop to sign off REJECTED");
		assertFalse(section.contains("sign off as usual"), "the old wording must not come back");

		// T-1 (d0ee3624 round 1): an agent following §2.5b verbatim has to be able to ask. The
		// command uses the CLI's real flags, and the example index is one the server accepts.
		assertTrue(section.contains("rearm agent doc publish --session <session-uuid> --task <task-uuid> \\\n"
				+ "  --type BOARD_QUESTIONS --index-only --index questions.json"),
				"the command carries the session and uses --type and --index-only");
		assertTrue(section.contains("rearm agent task signoff <task-uuid> --session <session-uuid> \\\n"
				+ "  --outcome REJECTED"), "and the sign-off that asks is REJECTED");
		// Every publish and sign-off the orientation shows carries its session: rearm refuses one
		// without it ("--session is required"), so an agent copying it could not act (T-1, round 2).
		java.util.regex.Matcher bash = java.util.regex.Pattern.compile("```bash\n(.*?)```", java.util.regex.Pattern.DOTALL)
				.matcher(orientation);
		int commands = 0;
		while (bash.find()) {
			for (String cmd : bash.group(1).replaceAll("\\\\\n\\s*", " ").split("\n")) {
				if (cmd.contains("rearm agent doc publish") || cmd.contains("rearm agent task signoff")) {
					commands++;
					assertTrue(cmd.contains("--session <session-uuid>"), "no --session: " + cmd);
				}
			}
		}
		assertTrue(commands >= 4, "the publish and sign-off commands were found: " + commands);
		assertFalse(section.contains("--spec"), "rearm-cli has no --spec flag");
		int json = section.indexOf("```json");
		String example = section.substring(section.indexOf('\n', json) + 1, section.indexOf("```", json + 7))
				.replace("<release uuid>", UUID.randomUUID().toString());
		io.reliza.model.BoardReviewItemIndex index = io.reliza.common.Utils.OM.readValue(example, io.reliza.model.BoardReviewItemIndex.class);
		BoardReviewItemIndexValidator.validate(index, RearmSpecificationType.BOARD_QUESTIONS, 3, null);
	}

	// ---------- review items with no about go to the maker (gaps §1.16) ----------

	/** A role that makes nothing but the given index: a tester or a reviewer. */
	private AgentTaskRoleConfigData indexRole(Rig r, String name, int order, RearmSpecificationType kind)
			throws RelizaException {
		return agentBoardService.upsertRoleConfig(board(r),
				new AgentBoardService.RoleConfigSpec(name, "do " + name, order, null, false, true,
						null, null, null, null, List.of(),
						List.of(new ProducedOutput(kind, InputScope.TASK, false))),
				true, WU);
	}

	/** A BOARD_REVIEW_ITEMS or BOARD_TEST_REPORT round, rejecting over one P1, with or without an about. */
	private ReleaseData publishRejection(AgentSessionData session, AgentTaskData td, RearmSpecificationType kind,
			RearmSpecificationType about, String id) throws RelizaException {
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", kind.name());
		idx.put("verdict", "REJECTED");
		idx.put("reviewItems", new ArrayList<>(List.of(item(id, "OPEN"))));
		if (null != about) idx.put("about", Map.of("specification", about.name()));
		return agentDocumentService.publish(session, new PublishRequest(td.getUuid(), kind, null, null, null,
				null, null, null, idx, null, null, null, null, null), WU);
	}

	/** One hop: assign to the session, sign off with the given outcome and outputs. */
	private AgentTaskData hop(Rig r, AgentTaskData t, AgentData agent, AgentSessionData session,
			SignOffOutcome outcome, List<UUID> outputs) throws RelizaException {
		agentTaskService.assign(t.getUuid(), board(r), agent.getUuid(), session.getUuid(), WU);
		return agentTaskService.signOff(t.getUuid(), session.getUuid(), outcome, "hop", outputs, WU);
	}

	/** designer, then coder, each passing; the task is then QUEUED for {@code next}. */
	private AgentTaskData designedAndCoded(Rig r, String title, String next) throws RelizaException {
		AgentTaskData t = task(r, title);
		agentTaskService.authorize(t.getUuid(), board(r), "designer", 10, null, null, null, null, COORD, WU);
		hop(r, t, r.workerA(), r.sessA(), SignOffOutcome.PASSED, List.of());
		AgentTaskData coded = hop(r, t, r.workerB(), r.sessB(), SignOffOutcome.PASSED, List.of());
		assertEquals(TaskStatus.QUEUED, coded.getStatus());
		assertEquals(next, coded.getRole());
		return coded;
	}

	private List<String> alerts(Rig r) {
		return agentBoardService.recentEvents(board(r).getUuid()).stream().filter(ev -> ev.kind() == AgentBoardData.BoardEventKind.ALERT)
				.map(AgentBoardData.BoardEvent::message).toList();
	}

	@Test
	public void aFailedTestRunGoesBackToTheCoder() throws RelizaException {
		Rig r = rig();
		indexRole(r, "tester", 30, RearmSpecificationType.BOARD_TEST_REPORT);
		AgentTaskData t = designedAndCoded(r, "red run", "tester");

		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData run = publishRejection(r.sessA(), reload(t), RearmSpecificationType.BOARD_TEST_REPORT, null, "T-1");
		AgentTaskData back = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"red", List.of(run.getUuid()), WU);

		assertEquals(TaskStatus.QUEUED, back.getStatus());
		assertEquals("coder", back.getRole(), "no about: back to the role that made the work");
		assertTrue(back.getRequiredInputs().stream().anyMatch(ri -> ri.specification() == RearmSpecificationType.BOARD_TEST_REPORT
				&& ri.resolution() == InputResolution.STRICT_LATEST), "the run is pinned for the coder");
		AgentTaskData.QuestionFrame frame = back.getQuestionStack().get(back.getQuestionStack().size() - 1);
		assertEquals(r.coder().getUuid(), frame.answeringRole());
		assertEquals(agentBoardService.getRoleConfig(r.board().getUuid(), "tester").orElseThrow().getUuid(),
				frame.askingRole());

		AgentTaskData unwound = hop(r, back, r.workerB(), r.sessB(), SignOffOutcome.PASSED, List.of());
		assertEquals(TaskStatus.QUEUED, unwound.getStatus());
		assertEquals("tester", unwound.getRole(), "the coder's fix unwinds to the tester (rule 3)");
	}

	// ---------- a review item frame pops without an unwind round (task bc7fc25a) ----------

	/** An index round with these ids OPEN, about nothing: about the work itself. */
	private ReleaseData publishOpen(AgentSessionData session, AgentTaskData td, RearmSpecificationType kind,
			String... ids) throws RelizaException {
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", kind.name());
		idx.put("verdict", "REJECTED");
		List<Map<String, Object>> items = new ArrayList<>();
		for (String id : ids) items.add(item(id, "OPEN"));
		idx.put("reviewItems", items);
		return agentDocumentService.publish(session, new PublishRequest(td.getUuid(), kind, null, null, null,
				null, null, null, idx, null, null, null, null, null), WU);
	}

	/** The maker's fix: a prose note, the output its pass hands over. */
	private ReleaseData publishNote(AgentSessionData session, AgentTaskData td) throws RelizaException {
		String digest = "sha256:" + UUID.randomUUID().toString().replace("-", "");
		return agentDocumentService.publish(session, new PublishRequest(td.getUuid(),
				RearmSpecificationType.DETAILED_DESIGN, null, "impl/" + digest + ".md", digest, "text/markdown",
				null, null, null, "c" + digest.substring(7, 15), DOCS, "the fix", ZonedDateTime.now(), null), WU);
	}

	private long questionsRounds(AgentTaskData t) {
		return reload(t).getReleases().stream()
				.map(u -> sharedReleaseService.getReleaseData(u).orElse(null))
				.filter(rd -> null != rd && null != rd.getDocument()
						&& rd.getDocument().specification() == RearmSpecificationType.BOARD_QUESTIONS)
				.count();
	}

	private List<String> infoMessages(Rig r) {
		return agentBoardService.recentEvents(board(r).getUuid()).stream().filter(ev -> ev.kind() == AgentBoardData.BoardEventKind.INFO)
				.map(AgentBoardData.BoardEvent::message).toList();
	}

	@Test
	public void aFixedReviewItemsFramePopsWithoutARound() throws RelizaException {
		Rig r = rig();
		indexRole(r, "tester", 30, RearmSpecificationType.BOARD_TEST_REPORT);
		AgentTaskData t = designedAndCoded(r, "red run, fixed", "tester");
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData run = publishOpen(r.sessA(), reload(t), RearmSpecificationType.BOARD_TEST_REPORT, "T-1", "T-2");
		AgentTaskData back = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"red", List.of(run.getUuid()), WU);
		assertEquals("coder", back.getRole());

		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData note = publishNote(r.sessB(), reload(t));
		AgentTaskData fixed = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED,
				"fixed", List.of(note.getUuid()), WU);

		assertEquals(TaskStatus.QUEUED, fixed.getStatus());
		assertEquals("tester", fixed.getRole(), "back to the tester to verify");
		assertTrue(fixed.getQuestionStack().isEmpty(), "the frame is popped");
		assertEquals(0, questionsRounds(t), "no board round is filed as questions");
		BoardReviewItemIndex latest = agentDocumentService.latestIndexes(reload(t)).get(RearmSpecificationType.BOARD_TEST_REPORT);
		assertEquals(List.of("T-1", "T-2"), latest.openReviewItems().stream().map(BoardReviewItem::id).toList(),
				"a fix claim closes nothing; the tester's next round does");
		assertTrue(infoMessages(r).stream().anyMatch(m -> m.contains("coder passed on task")
				&& m.contains("back to tester to verify T-1, T-2")), infoMessages(r).toString());

		// the tester's next round resolves T-1 and keeps T-2: routed back as ever
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		Map<String, Object> rerun = new LinkedHashMap<>();
		rerun.put("kind", "BOARD_TEST_REPORT");
		rerun.put("verdict", "REJECTED");
		rerun.put("reviewItems", new ArrayList<>(List.of(item("T-1", "RESOLVED"), item("T-2", "OPEN"))));
		ReleaseData run2 = agentDocumentService.publish(r.sessA(), new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_TEST_REPORT, null, null, null, null, null, null, rerun, null, null, null, null,
				null), WU);
		AgentTaskData again = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"still red", List.of(run2.getUuid()), WU);
		assertEquals("coder", again.getRole());
		assertEquals(0, questionsRounds(t));
	}

	@Test
	public void aReviewerFrameBehavesTheSame() throws RelizaException {
		Rig r = rig();
		indexRole(r, "reviewer", 15, RearmSpecificationType.BOARD_REVIEW_ITEMS);
		AgentTaskData t = task(r, "design review, redesigned");
		agentTaskService.authorize(t.getUuid(), board(r), "designer", 10, null, null, null, null, COORD, WU);
		hop(r, t, r.workerA(), r.sessA(), SignOffOutcome.PASSED, List.of());
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData review = publishOpen(r.sessB(), reload(t), RearmSpecificationType.BOARD_REVIEW_ITEMS, "F-1");
		AgentTaskData back = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.REJECTED,
				"redo", List.of(review.getUuid()), WU);
		assertEquals("designer", back.getRole());

		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		String digest = "sha256:" + UUID.randomUUID().toString().replace("-", "");
		ReleaseData v2 = publishArchitecture(r.sessA(), reload(t), "c" + digest.substring(7, 15), digest);
		AgentTaskData redesigned = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED,
				"redesigned", List.of(v2.getUuid()), WU);

		assertEquals("reviewer", redesigned.getRole());
		assertEquals(0, questionsRounds(t));
		assertEquals(List.of("F-1"), agentDocumentService.latestIndexes(reload(t)).get(RearmSpecificationType.BOARD_REVIEW_ITEMS)
				.openReviewItems().stream().map(BoardReviewItem::id).toList());
	}

	@Test
	public void aRealQuestionStillUnwinds() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "a real question");
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData q1 = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED, "asking",
				List.of(q1.getUuid()), WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		String digest = "sha256:" + UUID.randomUUID().toString().replace("-", "");
		ReleaseData design = publishArchitecture(r.sessB(), reload(t), "c" + digest.substring(7, 15), digest);
		AgentTaskData answered = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED,
				"answered in the design", List.of(design.getUuid()), WU);

		assertEquals("coder", answered.getRole());
		assertEquals(2, questionsRounds(t), "the question and the board's unwind round (D11)");
		BoardReviewItemIndex latest = agentDocumentService.latestIndexes(reload(t)).get(RearmSpecificationType.BOARD_QUESTIONS);
		BoardReviewItem q = latest.reviewItems().stream().filter(f -> "q1".equals(f.id())).findFirst().orElseThrow();
		assertEquals(BoardReviewItemStatus.RESOLVED, q.status());
		assertEquals(design.getUuid().toString(), String.valueOf(q.resolvedBy()), "answered by the designer's release");
	}

	@Test
	public void legacyMislabelledRoundsAreNotQuestions() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "legacy round");
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData q1 = publishQuestions(r.sessA(), reload(t),
				questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN")));
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED, "asking",
				List.of(q1.getUuid()), WU);
		// what the board cut before this task for a tester's frame: a BOARD_QUESTIONS-filed round with the
		// BOARD_TEST_REPORT index inside, every item marked answered
		Map<String, Object> run = new LinkedHashMap<>();
		run.put("kind", "BOARD_TEST_REPORT");
		run.put("verdict", "REJECTED");
		run.put("reviewItems", new ArrayList<>(List.of(item("T-9", "OPEN"))));
		ReleaseData legacy = agentDocumentService.publishUnwindRound(reload(t),
				io.reliza.common.Utils.OM.convertValue(run, BoardReviewItemIndex.class), q1.getUuid(), WU);
		assertEquals(RearmSpecificationType.BOARD_QUESTIONS, legacy.getDocument().specification());
		assertEquals(RearmSpecificationType.BOARD_TEST_REPORT, legacy.getDocument().reviewItems().kind());
		// linked to the task as the applier's cutUnwindRound did, so it is the newest BOARD_QUESTIONS-filed release
		AgentTaskData linked = reload(t);
		linked.addRelease(legacy.getUuid());
		agentTaskService.saveData(linked, WU);
		assertEquals(legacy.getUuid(), reload(t).getReleases().get(reload(t).getReleases().size() - 1));

		BoardReviewItemIndex latest = agentDocumentService.latestIndexes(reload(t)).get(RearmSpecificationType.BOARD_QUESTIONS);
		assertEquals(List.of("q1"), latest.openReviewItems().stream().map(BoardReviewItem::id).toList(),
				"the real questions round beneath it is the latest questions round");
	}

		@Test
	public void aRejectedDesignReviewGoesBackToTheArchitect() throws RelizaException {
		Rig r = rig();
		indexRole(r, "reviewer", 15, RearmSpecificationType.BOARD_REVIEW_ITEMS);
		AgentTaskData t = task(r, "design review");
		agentTaskService.authorize(t.getUuid(), board(r), "designer", 10, null, null, null, null, COORD, WU);
		AgentTaskData designed = hop(r, t, r.workerA(), r.sessA(), SignOffOutcome.PASSED, List.of());
		assertEquals("reviewer", designed.getRole());

		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData review = publishRejection(r.sessB(), reload(t), RearmSpecificationType.BOARD_REVIEW_ITEMS, null, "F-1");
		AgentTaskData back = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.REJECTED,
				"the design leaks", List.of(review.getUuid()), WU);
		assertEquals(TaskStatus.QUEUED, back.getStatus());
		assertEquals("designer", back.getRole(), "before any coder hop, the maker is the architect");
	}

	@Test
	public void anExplicitAboutStillWins() throws RelizaException {
		Rig r = rig();
		indexRole(r, "tester", 30, RearmSpecificationType.BOARD_TEST_REPORT);
		AgentTaskData t = designedAndCoded(r, "the design is wrong", "tester");
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData run = publishRejection(r.sessA(), reload(t), RearmSpecificationType.BOARD_TEST_REPORT,
				RearmSpecificationType.ARCHITECTURE, "T-1");
		AgentTaskData back = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"red because of the design", List.of(run.getUuid()), WU);
		assertEquals("designer", back.getRole(), "about ARCHITECTURE goes to its producer, not the maker");
	}

	@Test
	public void aReviewAsTheFirstHopGoesToTheCoordinatorSayingWhy() throws RelizaException {
		Rig r = rig();
		indexRole(r, "reviewer", 15, RearmSpecificationType.BOARD_REVIEW_ITEMS);
		AgentTaskData t = task(r, "reviewed first");
		agentTaskService.authorize(t.getUuid(), board(r), "reviewer", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData review = publishRejection(r.sessB(), reload(t), RearmSpecificationType.BOARD_REVIEW_ITEMS, null, "F-1");
		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.REJECTED,
				"nothing to review yet", List.of(review.getUuid()), WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus());
		assertTrue(alerts(r).stream().anyMatch(m -> m.contains("no earlier hop")), alerts(r).toString());
	}

	@Test
	public void aPersonsBlockingReviewItemWithNoAboutGoesToTheMaker() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "a person files against the design");
		agentTaskService.authorize(t.getUuid(), board(r), "designer", 10, null, null, null, null, COORD, WU);
		AgentTaskData designed = hop(r, t, r.workerA(), r.sessA(), SignOffOutcome.PASSED, List.of());
		assertEquals("coder", designed.getRole());

		AgentTaskData routed = agentTaskService.decideReviewItems(t.getUuid(), RearmSpecificationType.BOARD_REVIEW_ITEMS,
				List.of(new AgentDocumentService.BoardReviewItemDecision(AgentDocumentService.BoardReviewItemDecisionAction.FILE,
						null, 1, "the cache is not invalidated", null, null)),
				null, AgentActor.ofUser(UUID.randomUUID(), "operator"), WU);
		assertEquals(TaskStatus.QUEUED, routed.getStatus());
		assertEquals("designer", routed.getRole(), "the newest earlier hop by a maker");
	}

	@Test
	public void questionsWithoutAboutStillGoToTheCoordinator() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "a question about nothing");
		agentTaskService.authorize(t.getUuid(), board(r), "designer", 10, null, null, null, null, COORD, WU);
		assertEquals("coder", hop(r, t, r.workerB(), r.sessB(), SignOffOutcome.PASSED, List.of()).getRole());
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		Map<String, Object> idx = questions(RearmSpecificationType.ARCHITECTURE, null, item("q1", "OPEN"));
		idx.remove("about");
		ReleaseData q = publishQuestions(r.sessA(), reload(t), idx);
		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"asking", List.of(q.getUuid()), WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus(), "questions get no maker default");
	}

	@Test
	public void theMakerMustStillBeActive() throws RelizaException {
		Rig r = rig();
		indexRole(r, "tester", 30, RearmSpecificationType.BOARD_TEST_REPORT);
		AgentTaskData t = designedAndCoded(r, "coder retired", "tester");
		agentBoardService.upsertRoleConfig(board(r), AgentBoardService.RoleConfigSpec.ofBasics("coder", "build it",
				20, null, false, false, null), true, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData run = publishRejection(r.sessA(), reload(t), RearmSpecificationType.BOARD_TEST_REPORT, null, "T-1");
		AgentTaskData after = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"red", List.of(run.getUuid()), WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus(),
				"not a queue for an inactive role, and not the older maker either");
		assertTrue(alerts(r).stream().anyMatch(m -> m.contains("coder, is no longer active")), alerts(r).toString());
	}

	@Test
	public void aDefaultedRouteStillCountsCyclesAndStops() throws RelizaException {
		Rig r = rig();
		agentBoardService.updateSettings(r.board().getUuid(), null, null, 1, null, null, null, WU);
		indexRole(r, "tester", 30, RearmSpecificationType.BOARD_TEST_REPORT);
		AgentTaskData t = designedAndCoded(r, "red twice", "tester");

		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData run1 = publishRejection(r.sessA(), reload(t), RearmSpecificationType.BOARD_TEST_REPORT, null, "T-1");
		AgentTaskData back = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"red", List.of(run1.getUuid()), WU);
		assertEquals("coder", back.getRole());
		assertEquals(1, back.getCycles().values().stream().mapToInt(Integer::intValue).sum(), "the round is counted");
		hop(r, back, r.workerB(), r.sessB(), SignOffOutcome.PASSED, List.of());

		// A second red run would be a second tester -> coder round, over the cap of one.
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		Map<String, Object> idx2 = new LinkedHashMap<>();
		idx2.put("kind", "BOARD_TEST_REPORT");
		idx2.put("verdict", "REJECTED");
		Map<String, Object> still = item("T-1", "OPEN");
		still.put("title", "still red");
		idx2.put("reviewItems", new ArrayList<>(List.of(still)));
		ReleaseData run2 = agentDocumentService.publish(r.sessA(), new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_TEST_REPORT, null, null, null, null, null, null, idx2, null, null, null, null,
				null), WU);
		AgentTaskData stopped = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"still red", List.of(run2.getUuid()), WU);
		assertEquals(TaskStatus.ON_HOLD, stopped.getStatus(), "the cycle cap applies to the defaulted route");
		assertTrue(stopped.getHold().reason().contains("cycle cap") || stopped.getHold().reason().contains("no progress"),
				stopped.getHold().reason());
	}

	@Test
	public void aBoardCompletionRespectsOpenChildren() throws RelizaException {
		Rig r = rig();
		AgentTaskData parent = task(r, "epic");
		agentTaskService.split(parent.getUuid(), board(r),
				List.of(new io.reliza.model.AgentTaskInput.SplitChild("part 1", null, null, null,
						null, null)), COORD, WU);
		agentTaskService.authorize(parent.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(parent.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);

		// Both roles pass, so the parent reaches the point where it would otherwise complete.
		agentTaskService.signOff(parent.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED,
				"coder hop done", WU);
		agentTaskService.assign(parent.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		AgentTaskData after = agentTaskService.signOff(parent.getUuid(), r.sessB().getUuid(),
				SignOffOutcome.PASSED, "designer hop done", WU);

		// The coordinator's complete refuses a parent with open children; the board's has to
		// refuse it too, or a split epic auto-completes over parts nobody has built.
		assertFalse(TaskStatus.COMPLETED == after.getStatus(),
				"a parent with open children must not auto-complete");
		assertEquals(TaskStatus.AWAITING_COORDINATOR, after.getStatus(),
				"it goes to the coordinator, which is who decides what to do about the children");
	}

	// ---------- the coordinator may release a loop stop once, and escalate its holds (task c0a2134c) ----------

	/** After a release to the designer: it answers {@code answered}, the coder then asks {@code id} as {@code title}. */
	private AgentTaskData askAgain(Rig r, AgentTaskData t, String answeredId, String id, String title)
			throws RelizaException {
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		Map<String, Object> answered = questions(RearmSpecificationType.ARCHITECTURE, null, item(answeredId, "RESOLVED"));
		((List<Map<String, Object>>) answered.get("reviewItems")).get(0).put("resolution", "answered again: " + title);
		ReleaseData answer = publishQuestions(r.sessB(), reload(t), answered);
		agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED, "answered again",
				List.of(answer.getUuid()), WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		Map<String, Object> asked = questions(RearmSpecificationType.ARCHITECTURE, null, item(id, "OPEN"));
		((List<Map<String, Object>>) asked.get("reviewItems")).get(0).put("title", title);
		ReleaseData q = publishQuestions(r.sessA(), reload(t), asked);
		return agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED, title,
				List.of(q.getUuid()), WU);
	}

	@Test
	public void theFirstLoopStopIsTheCoordinatorsToReleaseOnce() throws RelizaException {
		Rig r = rig();
		AgentTaskData parked = stoppedByNoProgress(r, "the coordinator's release");
		TaskHold hold = parked.getHold();
		assertEquals(AgentTaskData.HoldLevel.COORDINATOR, hold.level(), "the board's default grants the coordinator one release");
		assertEquals(AgentTaskData.HoldKind.MANUAL, hold.kind());
		assertEquals(AgentTaskData.HoldStop.NO_PROGRESS, hold.stop());
		assertTrue(hold.reason().endsWith("; the coordinator may lift it once"), hold.reason());
		assertTrue(infos(r).stream().anyMatch(m -> m.contains("stopped by no progress; the coordinator may lift it once or escalate it")),
				infos(r).toString());
		assertTrue(alerts(r).stream().noneMatch(m -> m.contains("stopped by no progress")), "nobody paged: " + alerts(r));

		AgentTaskData released = agentTaskService.liftHold(parked.getUuid(), AgentTaskData.HoldLevel.COORDINATOR,
				COORD, "one more round settles q1", null, WU);
		assertEquals(TaskStatus.QUEUED, released.getStatus(), "routed past the stop, not re-held by it");
		assertEquals("designer", released.getRole());
		assertEquals(1, released.stopLiftsOf(AgentTaskData.HoldStop.NO_PROGRESS));
		assertTrue(infos(r).stream().anyMatch(m -> m.contains("(1 of 1 for no progress): one more round settles q1"
				+ " — stop overridden once, routed to designer; the next no-progress stop on this task is the operator's")),
				infos(r).toString());

		AgentTaskData again = askAgain(r, parked, "q1", "q1", "q1, asked a third time");
		assertEquals(TaskStatus.ON_HOLD, again.getStatus());
		assertEquals(AgentTaskData.HoldLevel.OPERATOR, again.getHold().level(), "the next identical stop is the operator's");
		assertEquals(AgentTaskData.HoldStop.NO_PROGRESS, again.getHold().stop());
		assertTrue(again.getHold().reason().endsWith("; lifted once already"), again.getHold().reason());
		assertTrue(alerts(r).stream().anyMatch(m -> m.contains("stopped by no progress") && m.contains("lifted once already")),
				alerts(r).toString());
		RelizaException refused = assertThrows(RelizaException.class, () -> agentTaskService.liftHold(
				parked.getUuid(), AgentTaskData.HoldLevel.COORDINATOR, COORD, WU));
		assertTrue(refused.getMessage().contains("the coordinator cannot lift it"), refused.getMessage());
	}

	@Test
	public void releasesAreCountedPerStopKind() throws RelizaException {
		Rig r = rig();
		agentBoardService.updateSettings(r.board().getUuid(), null, null, 2, null, null, null, WU);
		AgentTaskData parked = stoppedByNoProgress(r, "no progress, then the cap");
		agentTaskService.liftHold(parked.getUuid(), AgentTaskData.HoldLevel.COORDINATOR, COORD, null, null, WU);
		// the designer answers, the coder asks something new: movement, so the cap is what stops it
		AgentTaskData capped = askAgain(r, parked, "q1", "q2", "a new question, q2");
		assertEquals(TaskStatus.ON_HOLD, capped.getStatus());
		assertEquals(AgentTaskData.HoldStop.CYCLE_CAP, capped.getHold().stop(), capped.getHold().reason());
		assertEquals(AgentTaskData.HoldLevel.COORDINATOR, capped.getHold().level(),
				"a no-progress release does not use up the cycle cap's");
		assertEquals(1, capped.stopLiftsOf(AgentTaskData.HoldStop.NO_PROGRESS));
		assertEquals(0, capped.stopLiftsOf(AgentTaskData.HoldStop.CYCLE_CAP));
	}

	@Test
	public void aPersonsReleaseCountsToo() throws RelizaException {
		Rig r = rig();
		AgentTaskData parked = stoppedByNoProgress(r, "released by a person first");
		assertEquals(AgentTaskData.HoldLevel.COORDINATOR, parked.getHold().level());
		agentTaskService.liftHold(parked.getUuid(), AgentTaskData.HoldLevel.OPERATOR, PERSON, "go on", null, WU);
		AgentTaskData again = askAgain(r, parked, "q1", "q1", "q1, once more");
		assertEquals(AgentTaskData.HoldLevel.OPERATOR, again.getHold().level(),
				"one lift per stop kind per task, whoever gave it");
	}

	@Test
	public void withTheSettingOffEveryStopIsTheOperators() throws RelizaException {
		Rig r = rig();
		agentBoardService.updateSettingsFromInput(r.board().getUuid(), Map.of("coordinatorStopLift", false), WU);
		assertTrue(AgentBoardService.routingRules(board(r)).contains("Every stop parks for the operator"),
				AgentBoardService.routingRules(board(r)));
		AgentTaskData parked = stoppedByNoProgress(r, "the operator's from the first");
		assertEquals(AgentTaskData.HoldLevel.OPERATOR, parked.getHold().level());
		assertEquals(AgentTaskData.HoldStop.NO_PROGRESS, parked.getHold().stop());
		assertFalse(parked.getHold().reason().contains("the coordinator may lift it once"), parked.getHold().reason());
		assertFalse(parked.getHold().reason().contains("lifted once already"), "not why, on this board");
		assertTrue(alerts(r).stream().anyMatch(m -> m.contains("stopped by no progress") && m.contains("needs a human")),
				alerts(r).toString());

		Map<String, Object> restore = new HashMap<>();
		restore.put("coordinatorStopLift", null);
		agentBoardService.updateSettingsFromInput(r.board().getUuid(), restore, WU);
		assertNull(board(r).getCoordinatorStopLift());
		assertTrue(board(r).getEffectiveCoordinatorStopLift(), "null is the default, on");
		assertTrue(AgentBoardService.routingRules(board(r)).contains("parks for the coordinator first"),
				AgentBoardService.routingRules(board(r)));
	}

	@Test
	public void escalatingHandsACoordinatorHoldToTheOperator() throws RelizaException {
		Rig r = rig();
		AgentTaskData parked = stoppedByNoProgress(r, "escalated");
		String before = parked.getHold().reason();
		AgentTaskData escalated = agentTaskService.escalateHold(parked.getUuid(),
				"coder and designer disagree on q1; accept it or decide", COORD, WU);
		TaskHold h = escalated.getHold();
		assertEquals(TaskStatus.ON_HOLD, escalated.getStatus());
		assertEquals(AgentTaskData.HoldLevel.OPERATOR, h.level());
		assertEquals(AgentTaskData.HoldKind.MANUAL, h.kind(), "the kind stays");
		assertEquals(AgentTaskData.HoldStop.NO_PROGRESS, h.stop(), "and so does the stop");
		assertEquals(COORD, h.heldBy());
		// The stop said the coordinator may release it; escalated, it says it is the operator's instead of both (RD2-21).
		assertTrue(before.endsWith("; the coordinator may lift it once"), before);
		assertEquals(before.replace("; the coordinator may lift it once", "; escalated to the operator")
				+ " — escalated by the coordinator: coder and designer disagree on q1; accept it or decide", h.reason());
		assertFalse(h.reason().contains("may lift it once"), h.reason());
		List<String> escalation = alerts(r).stream().filter(m -> m.contains("escalated to the operator by the coordinator:"
				+ " coder and designer disagree on q1") && m.contains("[q1]")).toList();
		assertEquals(1, escalation.size(), alerts(r).toString());
		assertTrue(escalation.get(0).contains("; escalated to the operator)"), "the alert quotes the rewritten hold: " + escalation.get(0));
		assertFalse(escalation.get(0).contains("may lift it once"), escalation.get(0));

		assertThrows(RelizaException.class, () -> agentTaskService.liftHold(parked.getUuid(),
				AgentTaskData.HoldLevel.COORDINATOR, COORD, WU), "the operator's now");
		RelizaException twice = assertThrows(RelizaException.class, () -> agentTaskService.escalateHold(
				parked.getUuid(), "again", COORD, WU));
		assertTrue(twice.getMessage().contains("not on a coordinator hold") && twice.getMessage().contains("OPERATOR hold"),
				twice.getMessage());

		AgentTaskData released = agentTaskService.liftHold(parked.getUuid(), AgentTaskData.HoldLevel.OPERATOR,
				PERSON, "accepted", null, WU);
		assertEquals(TaskStatus.QUEUED, released.getStatus(), "the operator's release still routes past the stop");
		assertEquals(1, released.stopLiftsOf(AgentTaskData.HoldStop.NO_PROGRESS));
	}

	@Test
	public void escalationIsForCoordinatorHoldsOnly() throws RelizaException {
		Rig r = rig();
		// not on hold
		AgentTaskData fresh = task(r, "not held");
		assertThrows(RelizaException.class, () -> agentTaskService.escalateHold(fresh.getUuid(), "why", COORD, WU));
		// a reason is required
		agentTaskService.hold(fresh.getUuid(), AgentTaskData.HoldLevel.COORDINATOR, "waiting on the tracker", COORD, WU);
		assertThrows(RelizaException.class, () -> agentTaskService.escalateHold(fresh.getUuid(), " ", COORD, WU));
		// the coordinator's own hold, not a stop: escalated, with no stop recorded
		AgentTaskData own = agentTaskService.escalateHold(fresh.getUuid(), "the tracker issue needs a person", COORD, WU);
		assertEquals(AgentTaskData.HoldLevel.OPERATOR, own.getHold().level());
		assertNull(own.getHold().stop());

		// a person's hold
		AgentTaskData held = task(r, "held by a person");
		agentTaskService.hold(held.getUuid(), AgentTaskData.HoldLevel.OPERATOR, "legal", PERSON, WU);
		RelizaException operators = assertThrows(RelizaException.class,
				() -> agentTaskService.escalateHold(held.getUuid(), "why", COORD, WU));
		assertTrue(operators.getMessage().contains("not on a coordinator hold"), operators.getMessage());

		// a question hold, even at COORDINATOR level: it already waits on a person's answer
		AgentTaskData asked = task(r, "a question nobody here answers");
		agentTaskService.authorize(asked.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(asked.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData q = publishQuestions(r.sessA(), reload(asked),
				questions(RearmSpecificationType.TEST_PLAN, null, item("q1", "OPEN")));
		agentTaskService.signOff(asked.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED, "asking",
				List.of(q.getUuid()), WU);
		agentTaskService.escalateQuestion(asked.getUuid(), AgentTaskData.HoldLevel.COORDINATOR,
				"nobody makes a test plan", COORD, WU);
		assertEquals(AgentTaskData.HoldKind.QUESTION, reload(asked).getHold().kind());
		RelizaException question = assertThrows(RelizaException.class,
				() -> agentTaskService.escalateHold(asked.getUuid(), "why", COORD, WU));
		assertTrue(question.getMessage().contains("question hold"), question.getMessage());
		assertEquals(AgentTaskData.HoldLevel.COORDINATOR, reload(asked).getHold().level(), "the refusal changes nothing");
	}

	// ---------- a PASSED that leaves a blocking item OPEN is a rejection (task de91c937) ----------

	/** A BOARD_TEST_REPORT round claiming PASSED with one item OPEN at this priority. */
	private ReleaseData publishPassWithOpen(AgentSessionData session, AgentTaskData td, String id, int priority)
			throws RelizaException {
		Map<String, Object> f = item(id, "OPEN");
		f.put("priority", priority);
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", RearmSpecificationType.BOARD_TEST_REPORT.name());
		idx.put("verdict", "PASSED");
		idx.put("reviewItems", new ArrayList<>(List.of(f)));
		return agentDocumentService.publish(session, new PublishRequest(td.getUuid(), RearmSpecificationType.BOARD_TEST_REPORT,
				null, null, null, null, null, null, idx, null, null, null, null, null), WU);
	}

	@Test
	public void aPassWithABlockingItemOpenIsRefused() throws RelizaException {
		Rig r = rig();
		indexRole(r, "tester", 30, RearmSpecificationType.BOARD_TEST_REPORT);
		AgentTaskData t = designedAndCoded(r, "passed with an open P2", "tester");
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData run = publishPassWithOpen(r.sessA(), reload(t), "T-1", 2);
		int signOffs = reload(t).getSignOffs().size();

		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.sessA().getUuid(), SignOffOutcome.PASSED, "threshold: my P1", List.of(run.getUuid()), WU));
		assertEquals("That is a rejection: your BOARD_TEST_REPORT index has T-1 at P2 OPEN, which blocks on this board."
				+ " Publish the round again, which returns the same release, and sign off REJECTED with it in"
				+ " --outputs; or RESOLVE / WITHDRAW it in a new round.", e.getMessage());
		assertEquals(TaskStatus.ASSIGNED, reload(t).getStatus(), "nothing recorded");
		assertEquals(signOffs, reload(t).getSignOffs().size());

		// T-2 of round 1: the refusal says to publish again, which a CLI that dropped its outputs needs.
		ReleaseData again = publishPassWithOpen(r.sessA(), reload(t), "T-1", 2);
		assertEquals(run.getUuid(), again.getUuid(), "publishing the round again returns the same release");
		AgentTaskData rejected = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"T-1 open", List.of(again.getUuid()), WU);
		assertEquals(SignOffOutcome.REJECTED, rejected.getSignOffs().get(rejected.getSignOffs().size() - 1).outcome());
		assertEquals("coder", rejected.getRole(), "recorded and routed as the rejection it is");
	}

	@Test
	public void aBoardWithABlockingPriorityStillPassesBelowIt() throws RelizaException {
		Rig r = rig();
		agentBoardService.updateSettings(r.board().getUuid(), null, null, null, null, 2, null, WU);
		indexRole(r, "tester", 30, RearmSpecificationType.BOARD_TEST_REPORT);
		AgentTaskData t = designedAndCoded(r, "a P3 left open", "tester");
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData run = publishPassWithOpen(r.sessA(), reload(t), "T-1", 3);
		AgentTaskData passed = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED,
				"T-1 open at P3, below the line", List.of(run.getUuid()), WU);
		assertEquals(SignOffOutcome.PASSED, passed.getSignOffs().get(passed.getSignOffs().size() - 1).outcome());
	}

	// ---------- an answer to a review item about the design goes to the builder first (task RD4-13) ----------

	/** A new architecture round published by this session on the task. */
	private ReleaseData newDesignRound(AgentSessionData session, AgentTaskData td) throws RelizaException {
		String digest = "sha256:" + UUID.randomUUID().toString().replace("-", "");
		return publishArchitecture(session, reload(td), "c" + digest.substring(7, 15), digest);
	}

	/**
	 * The Dogfood board's shape: designer (makes ARCHITECTURE), coder (reads it), tester (files BOARD_TEST_REPORT). The
	 * designer and coder have passed, the tester has rejected T-1 about ARCHITECTURE, and the task is QUEUED for the
	 * designer. Returns the tester's run as the last element's release through {@code run}.
	 */
	private AgentTaskData testerAskedTheDesigner(Rig r, String title, ReleaseData[] run) throws RelizaException {
		requireArchitecture(r, "coder", 20);
		indexRole(r, "tester", 30, RearmSpecificationType.BOARD_TEST_REPORT);
		AgentTaskData t = task(r, title);
		agentTaskService.authorize(t.getUuid(), board(r), "designer", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData v1 = newDesignRound(r.sessA(), t);
		assertEquals("coder", agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED,
				"designed", List.of(v1.getUuid()), WU).getRole());
		assertEquals("tester", hop(r, t, r.workerB(), r.sessB(), SignOffOutcome.PASSED, List.of()).getRole());
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		run[0] = publishRejection(r.sessA(), reload(t), RearmSpecificationType.BOARD_TEST_REPORT,
				RearmSpecificationType.ARCHITECTURE, "T-1");
		AgentTaskData asked = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"the design lacks it", List.of(run[0].getUuid()), WU);
		assertEquals("designer", asked.getRole(), "about ARCHITECTURE goes to the designer first");
		return asked;
	}

	private List<String> openTestIds(AgentTaskData t) {
		return agentDocumentService.latestIndexes(reload(t)).get(RearmSpecificationType.BOARD_TEST_REPORT)
				.openReviewItems().stream().map(BoardReviewItem::id).toList();
	}

	@Test
	public void aDesignAnswerWithANewRoundGoesToTheCoderBeforeTheTester() throws RelizaException {
		Rig r = rig();
		ReleaseData[] run = new ReleaseData[1];
		AgentTaskData t = testerAskedTheDesigner(r, "answered with a round", run);

		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData v2 = newDesignRound(r.sessB(), t);
		AgentTaskData answered = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED,
				"the design now says it", List.of(v2.getUuid()), WU);

		assertEquals(TaskStatus.QUEUED, answered.getStatus());
		assertEquals("coder", answered.getRole(), "the round changes what is built: the coder goes before the tester");
		assertTrue(answered.getQuestionStack().isEmpty(), "the frame is popped");
		assertEquals(List.of("T-1"), openTestIds(t), "the review item stays open: the tester re-checks it");
		assertTrue(infoMessages(r).stream().anyMatch(m -> m.contains("designer passed with a new round; to coder;"
				+ " T-1 stays open until the tester re-checks")), infoMessages(r).toString());
		assertTrue(answered.getRequiredInputs().stream().anyMatch(ri -> ri.specification() == RearmSpecificationType.ARCHITECTURE
				&& ri.resolution() == InputResolution.STRICT_LATEST), "the new round is pinned for the coder");
		assertTrue(answered.getRequiredInputs().stream().anyMatch(ri -> ri.specification() == RearmSpecificationType.BOARD_TEST_REPORT),
				"the run naming why is still pinned");
		AgentTaskData.SignOff designerHop = answered.getSignOffs().get(answered.getSignOffs().size() - 1);
		assertEquals(run[0].getUuid(), designerHop.answered(), "the designer's hop is recorded as the answer to the run");
		assertNull(designerHop.noChange());

		AgentTaskData built = hop(r, t, r.workerA(), r.sessA(), SignOffOutcome.PASSED, List.of());
		assertEquals(TaskStatus.QUEUED, built.getStatus());
		assertEquals("tester", built.getRole(), "once the coder has passed, the tester re-checks");
		assertEquals(List.of("T-1"), openTestIds(t));

		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		Map<String, Object> green = new LinkedHashMap<>();
		green.put("kind", "BOARD_TEST_REPORT");
		green.put("verdict", "PASSED");
		green.put("reviewItems", new ArrayList<>(List.of(item("T-1", "RESOLVED"))));
		ReleaseData run2 = agentDocumentService.publish(r.sessB(), new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_TEST_REPORT, null, null, null, null, null, null, green, null, null, null, null,
				null), WU);
		AgentTaskData done = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED,
				"T-1 re-checked", List.of(run2.getUuid()), WU);
		assertEquals(TaskStatus.COMPLETED, done.getStatus(), "tester -> designer -> coder -> tester converges");
	}

	@Test
	public void aNoChangeAnswerGoesBackToTheTester() throws RelizaException {
		Rig r = rig();
		ReleaseData[] run = new ReleaseData[1];
		AgentTaskData t = testerAskedTheDesigner(r, "answered, nothing to build", run);

		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData v2 = newDesignRound(r.sessB(), t);
		RelizaException refused = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				r.sessB().getUuid(), SignOffOutcome.REJECTED, "no", List.of(v2.getUuid()), null, true, WU));
		assertTrue(refused.getMessage().contains("--no-change"), refused.getMessage());
		assertEquals(TaskStatus.ASSIGNED, reload(t).getStatus(), "the refusal records nothing");

		AgentTaskData answered = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED,
				"departure accepted", List.of(v2.getUuid()), null, true, WU);

		assertEquals(TaskStatus.QUEUED, answered.getStatus());
		assertEquals("tester", answered.getRole(), "nothing to build: back to the filer");
		assertEquals(List.of("T-1"), openTestIds(t));
		assertTrue(infoMessages(r).stream().anyMatch(m -> m.contains("designer passed, nothing to build; back to tester")),
				infoMessages(r).toString());
		AgentTaskData.SignOff designerHop = answered.getSignOffs().get(answered.getSignOffs().size() - 1);
		assertEquals(Boolean.TRUE, designerHop.noChange(), "the statement is recorded on the sign-off");
		assertEquals(run[0].getUuid(), designerHop.answered());
	}

	@Test
	public void anAnswerNoRoleBuildsFromGoesBackToTheTester() throws RelizaException {
		Rig r = rig();
		ReleaseData[] run = new ReleaseData[1];
		AgentTaskData t = testerAskedTheDesigner(r, "answered, nobody reads it", run);
		// Neither the coder nor the tester reads the architecture any more: no role after the designer qualifies.
		RequiredInput note = new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.DETAILED_DESIGN,
				InputScope.TASK, null, null, InputResolution.STRICT_LATEST);
		for (String[] role : new String[][] {{"coder", "20"}, {"tester", "30"}}) {
			agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(role[0], "do " + role[0],
					Integer.valueOf(role[1]), null, false, true, null, null, null, null, List.of(note),
					"tester".equals(role[0]) ? List.of(new ProducedOutput(RearmSpecificationType.BOARD_TEST_REPORT,
							InputScope.TASK, false)) : List.of()), true, WU);
		}

		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData v2 = newDesignRound(r.sessB(), t);
		AgentTaskData answered = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED,
				"the design now says it", List.of(v2.getUuid()), WU);

		assertEquals(TaskStatus.QUEUED, answered.getStatus());
		assertEquals("tester", answered.getRole(), "no role builds from the round: back to the filer");
		assertTrue(infoMessages(r).stream().anyMatch(m -> m.contains("designer passed on task")
				&& m.contains("back to tester to verify T-1")), infoMessages(r).toString());
	}

	@Test
	public void aPassNotReachedThroughAReviewItemRoutesAsBefore() throws RelizaException {
		Rig r = rig();
		requireArchitecture(r, "coder", 20);
		indexRole(r, "tester", 30, RearmSpecificationType.BOARD_TEST_REPORT);
		AgentTaskData t = task(r, "no review item about the design");
		agentTaskService.authorize(t.getUuid(), board(r), "designer", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData v1 = newDesignRound(r.sessA(), t);
		AgentTaskData designed = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED,
				"designed", List.of(v1.getUuid()), null, true, WU);
		assertEquals("coder", designed.getRole(), "a first design goes forward, --no-change or not");
		assertNull(designed.getSignOffs().get(designed.getSignOffs().size() - 1).answered());
		assertEquals("tester", hop(r, t, r.workerB(), r.sessB(), SignOffOutcome.PASSED, List.of()).getRole());

		// a review item about the work goes to the coder, and the coder's fix back to the tester, as ever
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData red = publishRejection(r.sessA(), reload(t), RearmSpecificationType.BOARD_TEST_REPORT, null, "T-1");
		assertEquals("coder", agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"red", List.of(red.getUuid()), WU).getRole());
		AgentTaskData fixed = hop(r, t, r.workerB(), r.sessB(), SignOffOutcome.PASSED, List.of());
		assertEquals("tester", fixed.getRole());
		assertNull(fixed.getSignOffs().get(fixed.getSignOffs().size() - 1).answered(),
				"a fix of the work is not an answer about the fixer's own document");
		assertTrue(infoMessages(r).stream().anyMatch(m -> m.contains("coder passed on task")
				&& m.contains("back to tester to verify T-1")), infoMessages(r).toString());
	}

	@Test
	public void aRejectionWithoutAboutAfterTheDesignersAnswerGoesToTheCoder() throws RelizaException {
		Rig r = rig();
		ReleaseData[] run = new ReleaseData[1];
		AgentTaskData t = testerAskedTheDesigner(r, "answered, then new items", run);
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		ReleaseData v2 = newDesignRound(r.sessB(), t);
		assertEquals("tester", agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED,
				"departure accepted", List.of(v2.getUuid()), null, true, WU).getRole());

		// the tester settles T-1 and files what the code must now do, about nothing: the designer's pass was an
		// answer, not the work, so the items go to the coder
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		Map<String, Object> next = new LinkedHashMap<>();
		next.put("kind", "BOARD_TEST_REPORT");
		next.put("verdict", "REJECTED");
		next.put("reviewItems", new ArrayList<>(List.of(item("T-1", "RESOLVED"), item("T-2", "OPEN"))));
		ReleaseData run2 = agentDocumentService.publish(r.sessA(), new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_TEST_REPORT, null, null, null, null, null, null, next, null, null, null, null,
				null), WU);
		AgentTaskData back = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED,
				"T-2 for the coder", List.of(run2.getUuid()), WU);
		assertEquals(TaskStatus.QUEUED, back.getStatus());
		assertEquals("coder", back.getRole(), "items about the work go to the maker, never to the answering designer");
	}
}
