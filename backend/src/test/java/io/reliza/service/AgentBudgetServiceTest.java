/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentSessionUsage;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HopUsage;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskReturn;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.PricingEntry;
import io.reliza.model.PricingEntry.PricingUnit;
import io.reliza.repositories.AgentSessionUsageRepository;

/**
 * The estimate ladder and the over-allowance figure (hop allowance design §4, tests 1-3), and spend
 * and pending read from usage rows (budget at assignment, architecture-2 §4, tests 1-5), without a
 * database: the repository and the task and board services are stubbed, and pricing is real.
 */
class AgentBudgetServiceTest {

	private final AgentBudgetService service = new AgentBudgetService();

	private final AgentTaskService tasks = mock(AgentTaskService.class);
	private final AgentBoardService boards = mock(AgentBoardService.class);
	private final AgentSessionUsageRepository rows = mock(AgentSessionUsageRepository.class);
	private final ModelOntologyService models = mock(ModelOntologyService.class);

	/** Priced at one micro per input token, so a row of N input tokens costs N. */
	private final ModelOntologyData priced = new ModelOntologyData();
	private final UUID pricedModel = UUID.randomUUID();
	private final UUID unpricedModel = UUID.randomUUID();

	private final AgentBoardData board = new AgentBoardData();
	private final List<AgentTaskData> boardTasks = new ArrayList<>();
	private final List<AgentSessionUsage> boardRows = new ArrayList<>();

	@BeforeEach
	void wire() {
		ReflectionTestUtils.setField(service, "agentTaskService", tasks);
		ReflectionTestUtils.setField(service, "agentBoardService", boards);
		ReflectionTestUtils.setField(service, "usageRepository", rows);
		ReflectionTestUtils.setField(service, "modelOntologyService", models);
		ReflectionTestUtils.setField(service, "pricingService", new ModelPricingService());
		priced.setPricing(new ArrayList<>(List.of(new PricingEntry(UUID.randomUUID(),
				ZonedDateTime.now().minusDays(30), null, "USD", PricingUnit.PER_MILLION_TOKENS,
				1_000_000L, 0L, 0L, 0L, null, null, null, null, null, ZonedDateTime.now().minusDays(30)))));
		when(models.getModelOntologyData(pricedModel)).thenReturn(Optional.of(priced));
		when(models.getModelOntologyData(unpricedModel)).thenReturn(Optional.of(new ModelOntologyData()));
		ReflectionTestUtils.setField(board, "uuid", UUID.randomUUID());
		when(tasks.listByBoard(eq(board.getUuid()), any())).thenReturn(boardTasks);
		when(rows.findByBoard(board.getUuid())).thenReturn(boardRows);
	}

	// ---------- fixtures for spend and pending ----------

	private AgentTaskRoleConfigData onBoard(AgentTaskRoleConfigData rc) {
		when(boards.listRoleConfigs(board.getUuid())).thenReturn(List.of(rc));
		return rc;
	}

	private AgentTaskData openTask(Long budget) {
		AgentTaskData td = new AgentTaskData();
		ReflectionTestUtils.setField(td, "uuid", UUID.randomUUID());
		td.setBoard(board.getUuid());
		td.setStatus(TaskStatus.QUEUED);
		td.setBudgetMicros(budget);
		boardTasks.add(td);
		return td;
	}

	/** A closed hop by a fresh session, assigned an hour ago, with nothing in its snapshot. */
	private UUID closedHop(AgentTaskData td, AgentTaskRoleConfigData rc) {
		UUID session = UUID.randomUUID();
		td.addSignOff(new SignOff(rc.getName(), rc.getUuid(), UUID.randomUUID(), session,
				ZonedDateTime.now().minusHours(1), ZonedDateTime.now().minusMinutes(30),
				SignOffOutcome.PASSED, null, null, null, HopUsage.empty()));
		return session;
	}

	private AgentSessionUsage row(AgentTaskData td, UUID session, UUID model, long inputTokens,
			ZonedDateTime at) {
		AgentSessionUsage r = new AgentSessionUsage();
		r.setTask(null == td ? null : td.getUuid());
		r.setBoard(board.getUuid());
		r.setSession(session);
		r.setModel(model);
		r.setReportedAt(at);
		r.setInputTokens(inputTokens);
		r.setMaxRequestContextTokens(inputTokens);
		r.setMinRequestContextTokens(inputTokens);
		r.setRecordData(Map.of());
		boardRows.add(r);
		return r;
	}

	private static AgentTaskRoleConfigData role(Long allowance) {
		AgentTaskRoleConfigData rc = new AgentTaskRoleConfigData();
		// The uuid has no public setter; a role's identity is what the estimate matches hops on.
		ReflectionTestUtils.setField(rc, "uuid", UUID.randomUUID());
		rc.setName("coder");
		rc.setHopBudgetMicros(allowance);
		return rc;
	}

	private static HopUsage cost(Long micros) {
		return new HopUsage(1000, 100, 0, 0, 1, 1, 1, micros, List.of(), true);
	}

	private static AgentTaskData taskWithHops(AgentTaskRoleConfigData rc, long... costs) {
		AgentTaskData td = new AgentTaskData();
		for (long c : costs) {
			td.addSignOff(new SignOff(rc.getName(), rc.getUuid(), UUID.randomUUID(), UUID.randomUUID(),
					ZonedDateTime.now().minusHours(1), ZonedDateTime.now(), SignOffOutcome.PASSED, null,
					null, null, cost(c)));
		}
		return td;
	}

	@Test
	void theTasksOwnHistoryComesFirst() {
		// A task's own hops are the most specific evidence of its size (gaps §1.25). The board list
		// holds the task too, as it does in a real projection, and does not outvote it.
		AgentTaskRoleConfigData rc = role(5_000L);
		AgentTaskData td = taskWithHops(rc, 100, 300);
		List<AgentTaskData> board = List.of(taskWithHops(rc, 800, 1_000), td);
		assertEquals(200, service.estimateFor(td, rc, board));
	}

	@Test
	void thenTheBoardsHistoryForTheRole() {
		AgentTaskRoleConfigData rc = role(5_000L);
		AgentTaskData td = taskWithHops(rc);
		// Other roles' hops on the board are not this role's history.
		List<AgentTaskData> board = List.of(taskWithHops(rc, 800, 1_000), taskWithHops(role(null), 7_000), td);
		assertEquals(900, service.estimateFor(td, rc, board));
	}

	@Test
	void thenTheAllowance() {
		AgentTaskRoleConfigData rc = role(5_000L);
		assertEquals(5_000, service.estimateFor(taskWithHops(rc), rc, List.of()));
	}

	@Test
	void thenNothing() {
		AgentTaskRoleConfigData rc = role(null);
		assertEquals(0, service.estimateFor(taskWithHops(rc), rc, List.of()));
	}

	@Test
	void anUnpricedHopIsNotHistory() {
		AgentTaskRoleConfigData rc = role(5_000L);
		AgentTaskData unpriced = new AgentTaskData();
		unpriced.addSignOff(new SignOff(rc.getName(), rc.getUuid(), null, null, null, ZonedDateTime.now(),
				SignOffOutcome.PASSED, null, null, null, cost(null)));
		assertEquals(5_000, service.estimateFor(unpriced, rc, List.of(unpriced)));
	}

	@Test
	void overAllowanceNeedsBothAnAllowanceAndACost() {
		assertNull(AgentBudgetService.overAllowanceMicros(null));
		assertNull(AgentBudgetService.overAllowanceMicros(cost(900L)), "no allowance");
		assertNull(AgentBudgetService.overAllowanceMicros(cost(null).withAllowance(500L)), "no cost");
		assertEquals(0L, AgentBudgetService.overAllowanceMicros(cost(400L).withAllowance(500L)));
		assertEquals(0L, AgentBudgetService.overAllowanceMicros(cost(500L).withAllowance(500L)),
				"spending exactly the allowance is within it");
		assertEquals(400L, AgentBudgetService.overAllowanceMicros(cost(900L).withAllowance(500L)));
	}

	@Test
	void aHopStoredBeforeAllowancesWereKeptReadsWithNone() {
		// Exactly the JSON a pre-change hop snapshot holds.
		String legacy = """
				{"inputTokens":10,"outputTokens":2,"cacheReadTokens":0,"cacheWriteTokens":0,
				 "requests":1,"turns":1,"reports":1,"derivedCostMicros":42,"priceVersions":[],
				 "costComplete":true}""";
		HopUsage old = Utils.OM.readValue(legacy, HopUsage.class);
		assertEquals(42L, old.derivedCostMicros());
		assertNull(old.allowanceMicros());

		HopUsage stamped = cost(42L).withAllowance(30L);
		HopUsage back = Utils.OM.readValue(Utils.OM.writeValueAsString(stamped), HopUsage.class);
		assertEquals(stamped, back);
		assertEquals(30L, back.allowanceMicros());
	}

	// ---------- spend and pending, from rows (budget at assignment) ----------

	@Test
	void spendIsTheRowsWheneverTheyArrivedPlusTheCoordinatorShare() {
		AgentTaskRoleConfigData rc = onBoard(role(null));
		AgentTaskData td = openTask(null);
		UUID session = closedHop(td, rc);
		td.setCoordinatorEstimateMicros(50L);
		// One row during the hop, one reported after the sign-off: both are spend.
		row(td, session, pricedModel, 300, ZonedDateTime.now().minusMinutes(40));
		row(td, session, pricedModel, 200, ZonedDateTime.now());
		AgentBudgetService.BoardSpend spend = service.boardSpend(board);
		assertEquals(550, service.spentOnTask(td, spend));
		assertTrue(service.project(td, board, rc, spend).costComplete());
	}

	@Test
	void aRowWithoutAPriceCountsZeroAndSaysSo() {
		AgentTaskRoleConfigData rc = onBoard(role(null));
		AgentTaskData td = openTask(100L);
		UUID session = closedHop(td, rc);
		row(td, session, pricedModel, 150, ZonedDateTime.now());
		row(td, session, unpricedModel, 9_000, ZonedDateTime.now());
		AgentBudgetService.Projection p = service.project(td, board, rc);
		assertEquals(150, p.spent());
		assertFalse(p.costComplete());
		assertFalse(p.fits());
		assertTrue(service.refusal(p, rc).contains("cost incomplete"), service.refusal(p, rc));
	}

	@Test
	void aClosedHopWithNoRowIsPendingAtItsRolesEstimate() {
		AgentTaskRoleConfigData rc = onBoard(role(700L));
		AgentTaskData td = openTask(null);
		closedHop(td, rc);
		assertEquals(700, service.pendingOn(td, service.boardSpend(board)),
				"a first hop with no history is pending at the role's allowance");
	}

	@Test
	void oneRowSinceTheAssignmentClearsTheHop() {
		AgentTaskRoleConfigData rc = onBoard(role(700L));
		AgentTaskData td = openTask(null);
		UUID session = closedHop(td, rc);
		// Before the hop opened: another hop's usage, which says nothing about this one.
		row(td, session, pricedModel, 10, ZonedDateTime.now().minusHours(2));
		assertEquals(700, service.pendingOn(td, service.boardSpend(board)));
		// A late report, well after the sign-off: no upper bound.
		row(td, session, pricedModel, 10, ZonedDateTime.now());
		assertEquals(0, service.pendingOn(td, service.boardSpend(board)));
	}

	@Test
	void aReturnedHopIsPendingFromItsAssignTransition() {
		AgentTaskRoleConfigData rc = onBoard(role(400L));
		AgentTaskData td = openTask(null);
		UUID session = UUID.randomUUID();
		td.transitionStatus(TaskStatus.ASSIGNED, StatusTrigger.ASSIGN, AgentActor.ofSession(session));
		td.addReturn(new TaskReturn(rc.getName(), rc.getUuid(), UUID.randomUUID(), session,
				TaskReturnReason.OTHER, "handed back", ZonedDateTime.now(), HopUsage.empty(), List.of()));
		assertEquals(400, service.pendingOn(td, service.boardSpend(board)));
		row(td, session, pricedModel, 10, ZonedDateTime.now());
		assertEquals(0, service.pendingOn(td, service.boardSpend(board)));
	}

	@Test
	void aPersonsVerdictIsNeverPending() {
		AgentTaskRoleConfigData rc = onBoard(role(700L));
		AgentTaskData td = openTask(null);
		td.addSignOff(new SignOff(rc.getName(), rc.getUuid(), null, null, null, ZonedDateTime.now(),
				SignOffOutcome.PASSED, "looks right", null, AgentActor.ofUser(UUID.randomUUID(), "op@example.com"),
				null));
		assertEquals(0, service.pendingOn(td, service.boardSpend(board)));
	}

	@Test
	void theTaskLevelRefusesAboveItsBudgetAndFitsAtEquality() {
		AgentTaskRoleConfigData rc = onBoard(role(100L));
		AgentTaskData td = openTask(1_000L);
		UUID session = closedHop(td, rc);
		row(td, session, pricedModel, 900, ZonedDateTime.now());
		// spent 900, nothing pending, next round estimated from the task's history: 0 priced hop
		// snapshots, so the allowance, 100 -> exactly 1000.
		assertTrue(service.project(td, board, rc).fits());
		row(td, session, pricedModel, 1, ZonedDateTime.now());
		AgentBudgetService.Projection p = service.project(td, board, rc);
		assertFalse(p.fits());
		assertEquals("task", p.refusedBy());
	}

	@Test
	void theBoardLevelCountsEveryOpenTasksPending() {
		AgentTaskRoleConfigData rc = onBoard(role(300L));
		ReflectionTestUtils.setField(board, "budgetMicros", 1_000L);
		AgentTaskData other = openTask(null);
		closedHop(other, rc);                   // pending 300 on another task
		AgentTaskData done = openTask(null);
		closedHop(done, rc);
		done.setStatus(TaskStatus.COMPLETED);   // a finished task's pending is not the board's
		row(null, UUID.randomUUID(), pricedModel, 350, ZonedDateTime.now()); // the coordinator's spend
		AgentTaskData td = openTask(null);
		// 350 coordinator + 300 pending + 300 estimate = 950: fits.
		assertTrue(service.project(td, board, rc).fits());
		closedHop(td, rc);                      // and this task's own unreported hop: 1250
		AgentBudgetService.Projection p = service.project(td, board, rc);
		assertFalse(p.fits());
		assertEquals("board", p.refusedBy());
		assertEquals(350, p.spent());
		assertEquals(600, p.pending());
	}

	@Test
	void theRefusalNamesTheNumbersAndTheLevel() {
		AgentTaskRoleConfigData rc = role(null);
		String msg = service.refusal(new AgentBudgetService.Projection(800, 150, 300, null, 1_000L,
				false, "board", true), rc);
		assertEquals("the next coder round is estimated at 300 micros; the board has spent 800 plus 150"
				+ " not yet reported of 1000, so the round does not fit", msg);
	}

	@Test
	void noBudgetMeansNoReads() {
		AgentTaskRoleConfigData rc = role(null);
		AgentTaskData td = openTask(null);
		assertTrue(service.nextRoundFits(td, board, rc));
		org.mockito.Mockito.verifyNoInteractions(rows);
	}
}
