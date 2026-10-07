/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import io.reliza.model.AgentBoardData.PriorityType;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.Organization;
import io.reliza.model.PricingEntry;
import io.reliza.model.PricingEntry.PricingUnit;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.SessionUsageSource;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.service.AgentTaskService.WorkerAssignment;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The budget projection at the poll, at assignment and at authorize (board-mechanics D25, task
 * 487d02f1, architecture-2 §4 tests 6-13), with spend read from usage rows so a report that lands
 * after a sign-off still counts.
 *
 * <p>Every role's allowance is 1000 micros, so a hop with no history is estimated, and pending, at
 * 1000; usage is priced at one micro per input token on a model of the test's own.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentBudgetIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentBudgetService budgetService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentSessionUsageService usageService;
	@Autowired private ModelOntologyService modelOntologyService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/budget";
	private static final String DOCS_SOURCE = "github:acme/budget-docs";
	private static final String DOCS = "https://github.com/acme/budget-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AtomicInteger ISSUE = new AtomicInteger(7000);
	private static final long ALLOWANCE = 1_000L;

	/** A reviewer that produces BOARD_REVIEW_ITEMS and a coder after it, each with an allowance. */
	private record Rig(Organization org, AgentBoardData board, AgentData workerA, AgentSessionData sessA,
			AgentData workerB, AgentSessionData sessB, String model) {}

	private Rig rig(PriorityType priority) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "budget-" + UUID.randomUUID(),
				"budget board", List.of(SRC, DOCS_SOURCE), "coordinate", 4, priority, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("reviewer",
				"review it", 10, null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.BOARD_REVIEW_ITEMS, InputScope.TASK, false)),
				null, ALLOWANCE), true, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder",
				"build it", 20, null, false, true, null, null, null, null, List.of(), List.of(),
				null, ALLOWANCE), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData coord = register(org, "coord");
		agentBoardService.claimCoordinatorSeat(board.getUuid(), open(org, coord).getUuid(), coord.getUuid(), WU);
		String model = "budget-model-" + UUID.randomUUID();
		ModelOntologyData m = modelOntologyService.resolve(org.getUuid(), model, null, WU).model();
		modelOntologyService.addModelPricing(m.getUuid(), new PricingEntry(null,
				ZonedDateTime.now().minusDays(1), null, "USD", PricingUnit.PER_MILLION_TOKENS,
				1_000_000L, 0L, 0L, 0L, null, null, null, null, null, null), WU);
		AgentData a = register(org, "wa");
		AgentData b = register(org, "wb");
		return new Rig(org, board, a, open(org, a), b, open(org, b), model);
	}

	private Rig rig() throws RelizaException {
		return rig(null);
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

	private AgentTaskData task(Rig r) throws RelizaException {
		return agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "budgeted work",
				null, null, null, null, null, null, WU);
	}

	private AgentTaskData authorized(Rig r, String role, int order) throws RelizaException {
		AgentTaskData t = task(r);
		return agentTaskService.authorize(t.getUuid(), board(r), role, order, null, null, null, null, COORD, WU);
	}

	private void boardBudget(Rig r, long micros, Integer blockingPriority) throws RelizaException {
		agentBoardService.updateSettings(r.board().getUuid(), micros, null, null, null, blockingPriority,
				null, WU);
	}

	/** Task budgets have no setter yet (task 6f1b348d), so the tests write the field. */
	private void taskBudget(AgentTaskData t, long micros) {
		AgentTaskData td = reload(t);
		td.setBudgetMicros(micros);
		agentTaskService.saveData(td, WU);
	}

	/** Usage the session reports for a task it worked, at one micro per input token. */
	private void report(Rig r, AgentSessionData s, AgentTaskData t, long micros) throws RelizaException {
		AgentSessionData fresh = agentSessionService.getSessionData(s.getUuid()).orElseThrow();
		usageService.report(fresh, new UsageReport(s.getUuid(), null, System.nanoTime(),
				SessionUsageSource.TRANSCRIPT, ZonedDateTime.now().minusMinutes(1), ZonedDateTime.now(), 1, 1,
				60, null, t.getUuid(), Map.of(), List.of(new UsageLine(r.model(), null, 0L, 1, micros, 0, 0, 0,
						null, micros, micros, null))), WU);
	}

	private static Map<String, Object> item(String id, int priority) {
		Map<String, Object> f = new LinkedHashMap<>();
		f.put("id", id);
		f.put("priority", priority);
		f.put("status", "OPEN");
		f.put("title", "review item " + id);
		return f;
	}

	/**
	 * A reviewer hop that publishes one open item at {@code priority} and signs off, reporting
	 * nothing: its cost is pending until a report lands.
	 */
	private AgentTaskData reviewedWithoutReporting(Rig r, String verdict, int priority) throws RelizaException {
		AgentTaskData t = authorized(r, "reviewer", 10);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", "BOARD_REVIEW_ITEMS");
		idx.put("verdict", verdict);
		idx.put("reviewItems", new ArrayList<>(List.of(item("f1", priority))));
		agentDocumentService.publish(r.sessA(), new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_REVIEW_ITEMS, null, null, null, null, null, null, idx, null, null,
				null, null, null), WU);
		return agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				"PASSED".equals(verdict) ? SignOffOutcome.PASSED : SignOffOutcome.REJECTED, "reviewed", WU);
	}

	/**
	 * Board budget 3000. The reviewer rejects with a blocking item and reports nothing; the
	 * coordinator queues the coder (0 spent + 1000 pending + 1000 estimate fits); then the reviewer's
	 * session reports 2500 for the hop it already closed, so the coder round no longer fits.
	 */
	private AgentTaskData queuedThenOverByALateReport(Rig r) throws RelizaException {
		boardBudget(r, 3_000L, null);
		AgentTaskData t = reviewedWithoutReporting(r, "REJECTED", 1);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, t.getStatus(), "items about the work go to the coordinator");
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 20, null, null, null, null, COORD, WU);
		report(r, r.sessA(), t, 2_500);
		return reload(t);
	}

	private List<AgentBoardData.BoardEvent> budgetAlerts(Rig r) {
		return agentBoardService.recentEvents(board(r).getUuid()).stream()
				.filter(ev -> ev.kind() == AgentBoardData.BoardEventKind.ALERT)
				.filter(ev -> ev.message().contains("budget at assignment"))
				.toList();
	}

	private AgentTaskRoleConfigData role(Rig r, String name) {
		return agentBoardService.getRoleConfig(r.board().getUuid(), name).orElseThrow();
	}

	@Test
	public void aLateReportRefusesTheNextRoundAtAssignment() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = queuedThenOverByALateReport(r);
		assertEquals(TaskStatus.QUEUED, t.getStatus());

		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.assign(
				t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU));
		assertTrue(e.getMessage().contains("does not fit") && e.getMessage().contains("on hold"), e.getMessage());

		AgentTaskData held = reload(t);
		assertEquals(TaskStatus.ON_HOLD, held.getStatus());
		assertEquals(AgentTaskData.HoldLevel.OPERATOR, held.getHold().level());
		assertEquals(AgentTaskData.HoldKind.MANUAL, held.getHold().kind());
		assertTrue(held.getHold().reason().contains("budget at assignment"), held.getHold().reason());
		assertTrue(held.getHold().reason().contains("f1"), "names the blocking item: " + held.getHold().reason());
		assertNull(held.getAssignment());
		assertEquals(1, budgetAlerts(r).size());
	}

	@Test
	public void parkingSurvivesTheRefusedAssignment() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = queuedThenOverByALateReport(r);
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU));
		// The refusal rolled back the assignment's transaction; the park had its own and committed,
		// so a second attempt meets the hold, not the budget.
		RelizaException again = assertThrows(RelizaException.class, () -> agentTaskService.assign(
				t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU));
		assertTrue(again.getMessage().contains("ON_HOLD"), again.getMessage());
		assertEquals(StatusTrigger.HOLD, reload(t).getStatusHistory().get(reload(t).getStatusHistory().size() - 1).trigger());
	}

	@Test
	public void aLateReportClearsTheHopFromPending() throws RelizaException {
		Rig r = rig();
		boardBudget(r, 3_000L, null);
		AgentTaskData t = reviewedWithoutReporting(r, "REJECTED", 1);
		AgentBudgetService.Projection before = budgetService.project(reload(t), board(r), role(r, "coder"));
		assertEquals(0, before.spent());
		assertEquals(ALLOWANCE, before.pending(), "the unreported reviewer hop counts at its allowance");

		report(r, r.sessA(), t, 2_500);
		AgentBudgetService.Projection after = budgetService.project(reload(t), board(r), role(r, "coder"));
		assertEquals(2_500, after.spent(), "the derived cost of the rows, reported after the sign-off");
		assertEquals(0, after.pending());
	}

	@Test
	public void thePollParksAnUnaffordableTaskAndOffersTheRest() throws RelizaException {
		Rig r = rig();
		boardBudget(r, 100_000L, null);
		AgentTaskData unaffordable = reviewedWithoutReporting(r, "REJECTED", 1);
		agentTaskService.authorize(unaffordable.getUuid(), board(r), "coder", 20, null, null, null, null, COORD, WU);
		report(r, r.sessA(), unaffordable, 2_500);
		taskBudget(unaffordable, 3_000L);       // 2500 spent + 1000 estimate
		AgentTaskData affordable = authorized(r, "coder", 30);

		Optional<WorkerAssignment> offered = agentTaskService.next(List.of(board(r)), r.workerB().getUuid(),
				r.sessB().getUuid());
		assertTrue(offered.isPresent());
		assertEquals(affordable.getUuid(), offered.get().task().getUuid());
		assertEquals(TaskStatus.ON_HOLD, reload(unaffordable).getStatus(), "parked by the poll, not left queued");
		assertEquals(TaskStatus.QUEUED, reload(affordable).getStatus(), "a poll offers; it does not assign");
	}

	@Test
	public void anAffordableTaskStillAssigns() throws RelizaException {
		Rig r = rig();
		boardBudget(r, 100_000L, null);
		AgentTaskData t = authorized(r, "coder", 20);
		WorkerAssignment wa = agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(),
				r.sessB().getUuid(), WU);
		assertEquals(TaskStatus.ASSIGNED, wa.task().getStatus());
		assertNull(reload(t).getHold());
		assertTrue(budgetAlerts(r).isEmpty());
	}

	@Test
	public void authorizeRefusesARoundThatDoesNotFit() throws RelizaException {
		Rig r = rig();
		boardBudget(r, 3_000L, null);
		AgentTaskData spender = authorized(r, "coder", 20);
		agentTaskService.assign(spender.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		report(r, r.sessA(), spender, 2_500);

		AgentTaskData t = task(r);
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.authorize(
				t.getUuid(), board(r), "coder", 20, null, null, null, null, COORD, WU));
		assertTrue(e.getMessage().contains("the board has spent 2500"), e.getMessage());
		assertEquals(TaskStatus.PENDING_INTAKE, reload(t).getStatus(), "refused, not parked");
		assertNull(reload(t).getRole());
	}

	@Test
	public void nothingBlockingStillHoldsAtThePoll() throws RelizaException {
		Rig r = rig();
		boardBudget(r, 100_000L, 2);
		// A P3 item is open but below the board's blocking line, so the reviewer's pass routes on.
		AgentTaskData t = reviewedWithoutReporting(r, "PASSED", 3);
		assertEquals(TaskStatus.QUEUED, t.getStatus());
		assertEquals("coder", t.getRole());
		int releasesBefore = reload(t).getReleases().size();
		taskBudget(t, 500L);                    // below the coder's 1000 estimate

		assertTrue(agentTaskService.next(List.of(board(r)), r.workerB().getUuid(), r.sessB().getUuid()).isEmpty());
		// The coder round never ran, so nothing converged: a hold for a person, not a completion
		// under policy (gaps §1.23), and no policy round accepting the open item.
		AgentTaskData held = reload(t);
		assertEquals(TaskStatus.ON_HOLD, held.getStatus());
		assertEquals(AgentTaskData.HoldLevel.OPERATOR, held.getHold().level());
		assertTrue(held.getHold().reason().contains("budget at assignment"), held.getHold().reason());
		assertEquals(releasesBefore, held.getReleases().size(), "no policy round");
		assertNull(held.getCompletedAt());
		assertEquals(1, budgetAlerts(r).size());
	}

	@Test
	public void strictAssignDoesNotParkWhileHoldingTheLock() throws RelizaException {
		Rig r = rig(PriorityType.STRICT);
		AgentTaskData top = authorized(r, "coder", 10);
		AgentTaskData lower = authorized(r, "coder", 20);
		taskBudget(top, 500L);

		// The STRICT check computes the top task without projecting, so it holds the lower one back
		// and changes nothing -- a park there would need the lock this assignment already holds.
		RelizaException strict = assertThrows(RelizaException.class, () -> agentTaskService.assign(
				lower.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU));
		assertTrue(strict.getMessage().contains("STRICT"), strict.getMessage());
		assertEquals(TaskStatus.QUEUED, reload(top).getStatus());

		RelizaException refused = assertThrows(RelizaException.class, () -> agentTaskService.assign(
				top.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU));
		assertTrue(refused.getMessage().contains("on hold for an operator"), refused.getMessage());
		// Parked by its own refusal, and a budget park always holds (gaps §1.23) -- never a
		// completion, and never anything that merely left QUEUED.
		AgentTaskData parked = reload(top);
		assertEquals(TaskStatus.ON_HOLD, parked.getStatus());
		assertEquals(AgentTaskData.HoldLevel.OPERATOR, parked.getHold().level());
		assertEquals(AgentTaskData.HoldKind.MANUAL, parked.getHold().kind());
		assertTrue(parked.getHold().reason().contains("budget at assignment"), parked.getHold().reason());

		WorkerAssignment wa = agentTaskService.assign(lower.getUuid(), board(r), r.workerB().getUuid(),
				r.sessB().getUuid(), WU);
		assertNotNull(wa);
		assertEquals(TaskStatus.ASSIGNED, reload(lower).getStatus());
	}
}
