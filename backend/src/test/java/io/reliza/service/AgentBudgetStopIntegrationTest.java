/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * A budget stop never completes a task, and a loop stop completes only when the required roles
 * are done (gaps §1.23, task e4e40529, architecture-1 §4 tests 1-5).
 *
 * <p>The designer produces ARCHITECTURE, an optional reviewer reads it, and the coder builds. Every
 * role's allowance is 1000 micros and nothing reports usage, so every hop that closed is pending at
 * 1000 and every next round is estimated at 1000: a board budget of 1500 affords one round, not two.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentBudgetStopIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/budget-stop";
	private static final String DOCS_SOURCE = "github:acme/budget-stop-docs";
	private static final String DOCS = "https://github.com/acme/budget-stop-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AtomicInteger ISSUE = new AtomicInteger(9300);
	private static final long ALLOWANCE = 1_000L;

	private record Rig(AgentBoardData board, AgentData workerA, AgentSessionData sessA, AgentData workerB,
			AgentSessionData sessB) {}

	/**
	 * @param reviewer a reviewer between the designer and the coder, reading the design; null for none
	 * @param coder the coder's necessity
	 */
	private Rig rig(RoleNecessity reviewer, RoleNecessity coder) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "stop-" + UUID.randomUUID(),
				"budget stops", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		role(board, "designer", 10, RoleNecessity.REQUIRED,
				List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE, InputScope.COMPONENT, false)));
		if (null != reviewer) {
			role(board, "reviewer", 15, reviewer,
					List.of(new ProducedOutput(RearmSpecificationType.BOARD_REVIEW_ITEMS, InputScope.TASK, false)));
		}
		role(board, "coder", 20, coder, List.of());
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData a = register(org, "wa");
		AgentData b = register(org, "wb");
		return new Rig(board, a, open(org, a), b, open(org, b));
	}

	private Rig rig() throws RelizaException {
		return rig(null, RoleNecessity.REQUIRED);
	}

	private void role(AgentBoardData board, String name, int order, RoleNecessity necessity,
			List<ProducedOutput> produces) throws RelizaException {
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec(name, "do " + name,
				order, null, false, true, null, null, necessity, null, List.of(), produces, null, ALLOWANCE),
				true, WU);
	}

	private AgentData register(Organization org, String prefix) throws RelizaException {
		return agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				prefix + "-" + UUID.randomUUID(), null, null, null, WU);
	}

	private AgentSessionData open(Organization org, AgentData agent) throws RelizaException {
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"s-" + UUID.randomUUID(), "test session", null, null, WU);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	/** Budget, cycle cap, blocking and completion priority; null leaves a setting as it is. */
	private void settings(Rig r, Long budget, Integer cycleCap, Integer blocking, Integer completion)
			throws RelizaException {
		agentBoardService.updateSettings(r.board().getUuid(), budget, null, cycleCap, null, blocking, completion, WU);
	}

	/** A task authorised straight to {@code role} and held by {@code session}. */
	private AgentTaskData heldBy(Rig r, String role, AgentData worker, AgentSessionData session)
			throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), role, 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), worker.getUuid(), session.getUuid(), WU);
		return reload(t);
	}

	private static Map<String, Object> item(String id, int priority, String status) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("id", id);
		f.put("priority", priority);
		f.put("status", status);
		f.put("title", "item " + id + " " + UUID.randomUUID());
		return f;
	}

	/** An index about ARCHITECTURE, which is what routes it to the designer. */
	private static Map<String, Object> index(String kind, String verdict, Map<String, Object> item) {
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", kind);
		idx.put("verdict", verdict);
		idx.put("reviewItems", new ArrayList<>(List.of(item)));
		idx.put("about", new LinkedHashMap<>(Map.of("specification", RearmSpecificationType.ARCHITECTURE.name())));
		return idx;
	}

	private ReleaseData publish(AgentSessionData session, AgentTaskData td, RearmSpecificationType type,
			Map<String, Object> index) throws RelizaException {
		return agentDocumentService.publish(session, new PublishRequest(td.getUuid(), type, null, null, null,
				null, null, null, index, null, null, null, null, null), WU);
	}

	private AgentTaskData signOff(AgentTaskData t, AgentSessionData s, SignOffOutcome outcome, ReleaseData out)
			throws RelizaException {
		return agentTaskService.signOff(t.getUuid(), s.getUuid(), outcome, "hop",
				null == out ? List.of() : List.of(out.getUuid()), WU);
	}

	private List<String> alerts(Rig r, String containing) {
		return agentBoardService.recentEvents(board(r).getUuid()).stream()
				.filter(ev -> ev.kind() == AgentBoardData.BoardEventKind.ALERT)
				.map(AgentBoardData.BoardEvent::message)
				.filter(m -> m.contains(containing))
				.toList();
	}

	private static void assertHeldForAPerson(AgentTaskData held, String... reasonContains) {
		assertHeld(held, AgentTaskData.HoldLevel.OPERATOR, reasonContains);
	}

	private static void assertHeld(AgentTaskData held, AgentTaskData.HoldLevel level, String... reasonContains) {
		assertEquals(TaskStatus.ON_HOLD, held.getStatus());
		assertEquals(level, held.getHold().level());
		assertEquals(AgentTaskData.HoldKind.MANUAL, held.getHold().kind());
		for (String s : reasonContains) {
			assertTrue(held.getHold().reason().contains(s), "'" + s + "' in: " + held.getHold().reason());
		}
		assertNull(held.getCompletedAt(), "not completed");
		assertTrue(held.getStatusHistory().stream().noneMatch(sc -> sc.trigger() == StatusTrigger.POLICY_COMPLETE));
	}

	// ---------- budget ----------

	@Test
	public void aBudgetStopOnTheForwardPathHolds() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = heldBy(r, "designer", r.workerA(), r.sessA());
		settings(r, 1_500L, null, null, null);
		int releases = reload(t).getReleases().size();

		AgentTaskData held = signOff(t, r.sessA(), SignOffOutcome.PASSED, null);

		assertHeldForAPerson(held, "budget: the next coder round does not fit",
				"estimated at 1000", "the board has spent 0 plus 1000 not yet reported of 1500");
		assertEquals(releases, held.getReleases().size(), "no policy round");
		assertEquals(1, alerts(r, "stopped by budget: the next coder round").size());
		// the operator's, on a board that grants the coordinator a loop-stop release (task c0a2134c)
		assertTrue(reload(t).getHold() != null && AgentTaskData.HoldStop.BUDGET == held.getHold().stop());
		assertTrue(board(r).getEffectiveCoordinatorStopLift());
	}

	@Test
	public void aBudgetStopOnTheUpstreamPathHolds() throws RelizaException {
		Rig r = rig();
		// P2 blocks routing (blocking priority 2) but not completion (completion priority 1): the
		// shape that used to complete under policy when the designer's round did not fit.
		settings(r, 1_500L, null, 2, 1);
		AgentTaskData t = heldBy(r, "coder", r.workerA(), r.sessA());
		ReleaseData q = publish(r.sessA(), t, RearmSpecificationType.BOARD_QUESTIONS,
				index("BOARD_QUESTIONS", "REJECTED", item("q1", 2, "OPEN")));
		int releases = reload(t).getReleases().size();

		AgentTaskData held = signOff(t, r.sessA(), SignOffOutcome.REJECTED, q);

		assertHeldForAPerson(held, "budget: the next designer round does not fit", "q1");
		assertEquals(releases, held.getReleases().size(), "no policy round accepting q1");
		assertEquals(1, alerts(r, "stopped by budget: the next designer round").size());
	}

	@Test
	public void aBudgetStopOnTheUnwindHolds() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = heldBy(r, "coder", r.workerA(), r.sessA());
		ReleaseData q = publish(r.sessA(), t, RearmSpecificationType.BOARD_QUESTIONS,
				index("BOARD_QUESTIONS", "REJECTED", item("q1", 1, "OPEN")));
		t = signOff(t, r.sessA(), SignOffOutcome.REJECTED, q);
		assertEquals(TaskStatus.QUEUED, t.getStatus());
		assertEquals("designer", t.getRole());
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		// Two closed hops pending at 1000 each plus the coder's 1000: returning does not fit 2500.
		settings(r, 2_500L, null, null, null);
		ReleaseData answer = publish(r.sessB(), reload(t), RearmSpecificationType.BOARD_QUESTIONS,
				index("BOARD_QUESTIONS", "PASSED", item("q1", 1, "RESOLVED")));

		AgentTaskData held = signOff(t, r.sessB(), SignOffOutcome.PASSED, answer);

		assertHeldForAPerson(held, "budget: returning to coder does not fit");
		assertEquals(1, alerts(r, "stopped by budget: returning to coder").size());
	}

	// ---------- loop stops ----------

	/**
	 * The reviewer rejects the design twice with a P2 item under cycle cap 1: the second rejection
	 * stops the loop with nothing that blocks completion. The coder never ran.
	 */
	private AgentTaskData cappedBeforeTheCoderRan(Rig r) throws RelizaException {
		settings(r, null, 1, 2, 1);
		AgentTaskData t = heldBy(r, "reviewer", r.workerA(), r.sessA());
		ReleaseData f1 = publish(r.sessA(), t, RearmSpecificationType.BOARD_REVIEW_ITEMS,
				index("BOARD_REVIEW_ITEMS", "REJECTED", item("f1", 2, "OPEN")));
		t = signOff(t, r.sessA(), SignOffOutcome.REJECTED, f1);
		assertEquals("designer", t.getRole(), "the design goes back to its producer");
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		t = signOff(t, r.sessB(), SignOffOutcome.PASSED, null);
		assertEquals("reviewer", t.getRole(), "and returns to the reviewer");
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData f2 = publish(r.sessA(), reload(t), RearmSpecificationType.BOARD_REVIEW_ITEMS,
				index("BOARD_REVIEW_ITEMS", "REJECTED", item("f1", 2, "OPEN")));
		return signOff(t, r.sessA(), SignOffOutcome.REJECTED, f2);
	}

	@Test
	public void aCycleCapWithARequiredRoleUnpassedHolds() throws RelizaException {
		Rig r = rig(RoleNecessity.OPTIONAL, RoleNecessity.REQUIRED);
		AgentTaskData held = cappedBeforeTheCoderRan(r);
		// the first cycle-cap stop on the task is the coordinator's to lift once (task c0a2134c)
		assertHeld(held, AgentTaskData.HoldLevel.COORDINATOR, "cycle cap with required role(s) not passed: [coder]",
				"; the coordinator may lift it once");
		assertEquals(AgentTaskData.HoldStop.CYCLE_CAP, held.getHold().stop());
		String reason = held.getHold().reason();
		String listed = reason.substring(reason.indexOf("not passed: ") + "not passed: ".length());
		assertTrue(listed.startsWith("[coder]"),
				"the reviewer's own rejection is what the stop is about, not a role that never ran: " + reason);
		// #606's detail stays on the hold: which pair went round, and the cap (tester note on T-1)
		assertTrue(reason.contains("went 1 round(s) (cap 1)"), reason);
	}

	@Test
	public void aCycleCapWithEveryRequiredRolePassedCompletesUnderPolicy() throws RelizaException {
		Rig r = rig(RoleNecessity.OPTIONAL, RoleNecessity.OPTIONAL);
		AgentTaskData done = cappedBeforeTheCoderRan(r);
		assertEquals(TaskStatus.COMPLETED, done.getStatus(), "the only required role, the designer, passed");
		assertTrue(done.getStatusHistory().stream().anyMatch(sc -> sc.trigger() == StatusTrigger.POLICY_COMPLETE));
	}

	@Test
	public void noProgressWithRequiredRolesPassedStillCompletesUnderPolicy() throws RelizaException {
		Rig r = rig();
		settings(r, null, null, 2, 1);
		AgentTaskData t = heldBy(r, "coder", r.workerA(), r.sessA());
		ReleaseData q1 = publish(r.sessA(), t, RearmSpecificationType.BOARD_QUESTIONS,
				index("BOARD_QUESTIONS", "REJECTED", item("q1", 2, "OPEN")));
		t = signOff(t, r.sessA(), SignOffOutcome.REJECTED, q1);
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		t = signOff(t, r.sessB(), SignOffOutcome.PASSED, null);
		assertEquals("coder", t.getRole(), "the designer answered; back to the coder");
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		// The same id again, reworded, so it is a new round asking the same thing.
		ReleaseData q2 = publish(r.sessA(), reload(t), RearmSpecificationType.BOARD_QUESTIONS,
				index("BOARD_QUESTIONS", "REJECTED", item("q1", 2, "OPEN")));

		AgentTaskData done = signOff(t, r.sessA(), SignOffOutcome.REJECTED, q2);

		assertEquals(TaskStatus.COMPLETED, done.getStatus(),
				"the designer passed and the coder is the asker: the loop converged as far as it will");
		assertTrue(done.getStatusHistory().stream().anyMatch(sc -> sc.trigger() == StatusTrigger.POLICY_COMPLETE));
		assertEquals(1, alerts(r, "completed under policy (no progress)").size());
	}
}
