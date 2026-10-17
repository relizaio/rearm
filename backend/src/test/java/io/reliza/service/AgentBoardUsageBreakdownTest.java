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

import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentSessionUsage;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HopUsage;
import io.reliza.model.AgentTaskData.SignOff;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskAssignment;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.PricingEntry;
import io.reliza.model.PricingEntry.PricingUnit;
import io.reliza.repositories.AgentSessionUsageRepository;
import io.reliza.service.AgentBudgetService.RoleSpend;
import io.reliza.service.AgentBudgetService.SessionSpend;
import io.reliza.service.AgentBudgetService.SpendBreakdown;

/**
 * The Usage tab's breakdown (task RD2-8): every usage row of the board in the window counted once --
 * in the role of the hop it fell in, closed or open, in the coordinator seat's line, or unattributed
 * -- so the parts add up to the total, and the total is the period rollup's. Without a database: the
 * repository and the task service are stubbed, pricing is real (one micro per input token).
 */
class AgentBoardUsageBreakdownTest {

	private final AgentBudgetService service = new AgentBudgetService();
	private final AgentSessionUsageService usageService = new AgentSessionUsageService();

	private final AgentTaskService tasks = mock(AgentTaskService.class);
	private final AgentSessionUsageRepository repository = mock(AgentSessionUsageRepository.class);
	private final ModelOntologyService models = mock(ModelOntologyService.class);

	private final ModelOntologyData priced = new ModelOntologyData();
	private final UUID pricedModel = UUID.randomUUID();
	private final UUID unpricedModel = UUID.randomUUID();

	private final AgentBoardData board = new AgentBoardData();
	private final List<AgentTaskData> boardTasks = new ArrayList<>();
	private final List<AgentSessionUsage> boardRows = new ArrayList<>();

	private final ZonedDateTime now = ZonedDateTime.now();

	@BeforeEach
	void wire() {
		ModelPricingService pricing = new ModelPricingService();
		ReflectionTestUtils.setField(service, "agentTaskService", tasks);
		ReflectionTestUtils.setField(service, "usageRepository", repository);
		ReflectionTestUtils.setField(service, "modelOntologyService", models);
		ReflectionTestUtils.setField(service, "pricingService", pricing);
		ReflectionTestUtils.setField(service, "agentSessionUsageService", usageService);
		ReflectionTestUtils.setField(usageService, "repository", repository);
		ReflectionTestUtils.setField(usageService, "modelOntologyService", models);
		ReflectionTestUtils.setField(usageService, "pricingService", pricing);
		priced.setPricing(new ArrayList<>(List.of(new PricingEntry(UUID.randomUUID(),
				now.minusDays(30), null, "USD", PricingUnit.PER_MILLION_TOKENS,
				1_000_000L, 0L, 0L, 0L, null, null, null, null, null, now.minusDays(30)))));
		when(models.getModelOntologyData(pricedModel)).thenReturn(Optional.of(priced));
		when(models.getModelOntologyData(unpricedModel)).thenReturn(Optional.of(new ModelOntologyData()));
		ReflectionTestUtils.setField(board, "uuid", UUID.randomUUID());
		when(tasks.listByBoard(eq(board.getUuid()), any())).thenReturn(boardTasks);
		// The period read as the database answers it: the board's rows with from <= reportedAt < to.
		when(repository.findForBoardPeriod(eq(board.getUuid()), any(), any())).thenAnswer(inv -> {
			ZonedDateTime from = inv.getArgument(1);
			ZonedDateTime to = inv.getArgument(2);
			return boardRows.stream().filter(r -> !r.getReportedAt().isBefore(from) && r.getReportedAt().isBefore(to)).toList();
		});
	}

	private AgentTaskData task() {
		AgentTaskData td = new AgentTaskData();
		ReflectionTestUtils.setField(td, "uuid", UUID.randomUUID());
		td.setBoard(board.getUuid());
		td.setStatus(TaskStatus.QUEUED);
		boardTasks.add(td);
		return td;
	}

	private UUID closedHop(AgentTaskData td, String role, ZonedDateTime assignedAt) {
		UUID session = UUID.randomUUID();
		td.addSignOff(new SignOff(role, UUID.randomUUID(), UUID.randomUUID(), session, assignedAt, assignedAt.plusMinutes(30),
				SignOffOutcome.PASSED, null, null, null, HopUsage.empty()));
		return session;
	}

	private UUID openHop(AgentTaskData td, String role, ZonedDateTime assignedAt) {
		UUID session = UUID.randomUUID();
		td.setStatus(TaskStatus.ASSIGNED);
		td.setAssignment(new TaskAssignment(session, UUID.randomUUID(), role, UUID.randomUUID(), assignedAt, null));
		return session;
	}

	private AgentSessionUsage row(AgentTaskData td, UUID session, UUID model, long inputTokens, ZonedDateTime at) {
		AgentSessionUsage r = new AgentSessionUsage();
		r.setTask(null == td ? null : td.getUuid());
		r.setBoard(board.getUuid());
		r.setSession(session);
		r.setAgent(UUID.randomUUID());
		r.setModel(model);
		r.setReportedAt(at);
		r.setInputTokens(inputTokens);
		r.setMaxRequestContextTokens(inputTokens);
		r.setMinRequestContextTokens(inputTokens);
		r.setRecordData(Map.of());
		boardRows.add(r);
		return r;
	}

	private static long sumOfParts(SpendBreakdown b) {
		return b.byRole().stream().mapToLong(RoleSpend::costMicros).sum() + b.coordinatorEstimateMicros() + b.unattributedMicros();
	}

	@Test
	void everyRowIsCountedOnceAndThePartsAddUpToTheRollupsTotal() {
		AgentTaskData a = task();
		UUID coder = closedHop(a, "coder", now.minusHours(3));
		row(a, coder, pricedModel, 1_000, now.minusHours(2));
		// Reported after the sign-off: the snapshot missed it, the hop still owns it.
		row(a, coder, pricedModel, 500, now.minusMinutes(20));
		AgentTaskData b = task();
		UUID reviewer = openHop(b, "reviewer", now.minusHours(1));
		row(b, reviewer, pricedModel, 300, now.minusMinutes(10));
		// Before its hop opened: no hop of that session owns it.
		row(b, reviewer, pricedModel, 70, now.minusHours(2));
		// A session that never held a hop on the task.
		row(b, UUID.randomUUID(), pricedModel, 30, now.minusMinutes(5));
		// The coordinator seat: the board and no task.
		UUID seat = UUID.randomUUID();
		row(null, seat, pricedModel, 200, now.minusMinutes(15));

		SpendBreakdown bd = service.breakdown(board, now.minusDays(1), now);
		assertEquals(2_100, bd.totalMicros());
		assertEquals(bd.totalMicros(), sumOfParts(bd), "the parts add up to the total");
		assertEquals(2_100L, usageService.boardUsage(board.getUuid(), now.minusDays(1), now).derivedCostMicros(),
				"the total is the period rollup's, from the same rows and prices");
		assertTrue(bd.costComplete());
		assertEquals(200, bd.coordinatorEstimateMicros());
		assertEquals(100, bd.unattributedMicros());

		RoleSpend c = bd.byRole().get(0);
		assertEquals("coder", c.role());
		assertEquals(1_500, c.costMicros());
		assertEquals(1, c.closedHops());
		assertEquals(0, c.openHops());
		assertEquals(1_500, c.tokens().inputTokens());
		RoleSpend r = bd.byRole().get(1);
		assertEquals("reviewer", r.role());
		assertEquals(300, r.costMicros());
		assertEquals(0, r.closedHops());
		assertEquals(1, r.openHops(), "an open hop's spend is its role's");

		SessionSpend top = bd.bySession().get(0);
		assertEquals(coder, top.session());
		assertEquals("coder", top.role());
		assertEquals(1_500, top.costMicros());
		assertEquals(4, bd.bySession().size());
		assertEquals("coordinator", bd.bySession().stream().filter(s -> seat.equals(s.session())).findFirst().orElseThrow().role());
		assertEquals(bd.totalMicros(), bd.bySession().stream().mapToLong(SessionSpend::costMicros).sum());
	}

	@Test
	void aRowWithNoPriceMakesEveryFigureALowerBound() {
		AgentTaskData a = task();
		UUID coder = closedHop(a, "coder", now.minusHours(3));
		row(a, coder, pricedModel, 1_000, now.minusHours(2));
		row(a, coder, unpricedModel, 9_999, now.minusHours(1));
		SpendBreakdown bd = service.breakdown(board, now.minusDays(1), now);
		assertFalse(bd.costComplete());
		assertEquals(1_000, bd.totalMicros());
		assertEquals(bd.totalMicros(), sumOfParts(bd));
		assertEquals(10_999, bd.byRole().get(0).tokens().inputTokens(), "tokens count whatever their price");
	}

	@Test
	void theWindowLeavesOutARowReportedBeforeIt() {
		AgentTaskData a = task();
		UUID coder = closedHop(a, "coder", now.minusDays(3));
		row(a, coder, pricedModel, 1_000, now.minusDays(2));
		row(a, coder, pricedModel, 400, now.minusHours(2));
		SpendBreakdown day = service.breakdown(board, now.minusDays(1), now);
		assertEquals(400, day.totalMicros());
		assertEquals(400, day.byRole().get(0).costMicros());
		SpendBreakdown life = service.breakdown(board, null, null);
		assertEquals(1_400, life.totalMicros(), "no window is the board's life");
	}

	@Test
	void aBoardWithNoUsageIsZerosNotAnError() {
		task();
		SpendBreakdown bd = service.breakdown(board, now.minusDays(1), now);
		assertEquals(0, bd.totalMicros());
		assertTrue(bd.byRole().isEmpty());
		assertTrue(bd.bySession().isEmpty());
		assertEquals(0, bd.coordinatorEstimateMicros());
		assertEquals(0, bd.unattributedMicros());
		assertTrue(bd.costComplete());
		assertNull(usageService.boardUsage(board.getUuid(), now.minusDays(1), now).derivedCostMicros(),
				"the rollup says no cost for no rows; the breakdown says 0");
	}
}
