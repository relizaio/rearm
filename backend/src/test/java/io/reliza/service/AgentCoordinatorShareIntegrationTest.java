/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.Organization;
import io.reliza.model.PricingEntry;
import io.reliza.model.PricingEntry.PricingUnit;
import io.reliza.model.SessionUsageAttribution;
import io.reliza.model.SessionUsageSource;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentSessionUsageService.UsageAck;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The coordinator's spend reaches the tasks it coordinated, and every projected round carries
 * the board's coordinator overhead (D16, board-mechanics §5.3; gaps §1.24, task 44a489b3,
 * architecture-1 §4 tests 1-7).
 *
 * <p>The seat reports through the real usage path, attributed COORDINATOR because it holds the seat
 * and no assignment. Usage is priced at one micro per input token; the coder's allowance is 1000.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentCoordinatorShareIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentBudgetService budgetService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentSessionUsageService usageService;
	@Autowired private ModelOntologyService modelOntologyService;
	@Autowired private io.reliza.ws.AgentTaskDataFetcher agentTaskDataFetcher;
	@Autowired private org.springframework.context.ApplicationContext applicationContext;
	@MockitoSpyBean private CoordinatorShareService shares;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:acme/coord-share";
	private static final String DOCS_SOURCE = "github:acme/coord-share-docs";
	private static final String DOCS = "https://github.com/acme/coord-share-docs";
	private static final AtomicInteger ISSUE = new AtomicInteger(9500);
	private static final long ALLOWANCE = 1_000L;

	private record Rig(AgentBoardData board, AgentSessionData seat, String model) {
		AgentActor seatActor() {
			return AgentActor.ofSession(seat.getUuid());
		}
	}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "share-" + UUID.randomUUID(),
				"coordinator shares", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder",
				"build it", 10, null, false, true, null, null, null, null, List.of(), List.of(),
				null, ALLOWANCE), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData coord = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"coord-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData seat = agentSessionService.initialize(org.getUuid(), coord.getUuid(), null,
				"s-" + UUID.randomUUID(), "coordinator", null, null, WU);
		agentBoardService.claimCoordinatorSeat(board.getUuid(), seat.getUuid(), coord.getUuid(), WU);
		String model = "share-model-" + UUID.randomUUID();
		ModelOntologyData m = modelOntologyService.resolve(org.getUuid(), model, null, WU).model();
		modelOntologyService.addModelPricing(m.getUuid(), new PricingEntry(null,
				ZonedDateTime.now().minusDays(1), null, "USD", PricingUnit.PER_MILLION_TOKENS,
				1_000_000L, 0L, 0L, 0L, null, null, null, null, null, null), WU);
		return new Rig(board, seat, model);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	private AgentTaskData registered(Rig r) throws RelizaException {
		return agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
	}

	/** A task the seat authorised: one coordinator-caused transition. */
	private AgentTaskData movedBySeat(Rig r) throws RelizaException {
		AgentTaskData t = registered(r);
		return agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null,
				r.seatActor(), WU);
	}

	/** The seat reports {@code micros} input tokens, which price at that many micros. */
	private UsageAck seatReports(Rig r, long micros) throws RelizaException {
		AgentSessionData fresh = agentSessionService.getSessionData(r.seat().getUuid()).orElseThrow();
		return usageService.report(fresh, new UsageReport(r.seat().getUuid(), null, System.nanoTime(),
				SessionUsageSource.TRANSCRIPT, ZonedDateTime.now().minusMinutes(1), ZonedDateTime.now(), 1, 1,
				60, null, null, Map.of(), List.of(new UsageLine(r.model(), null, 0L, 1, micros, 0, 0, 0,
						null, micros, micros, null))), WU);
	}

	private static Long share(AgentTaskData td) {
		return td.getCoordinatorEstimateMicros();
	}

	private AgentTaskRoleConfigData coder(Rig r) {
		return agentBoardService.getRoleConfig(r.board().getUuid(), "coder").orElseThrow();
	}

	@Test
	public void aDeltaIsSplitAmongTheTasksTheCoordinatorMoved() throws RelizaException {
		Rig r = rig();
		AgentTaskData a = movedBySeat(r);
		AgentTaskData b = movedBySeat(r);
		AgentTaskData untouched = registered(r);

		UsageAck ack = seatReports(r, 1_000);
		assertEquals(SessionUsageAttribution.COORDINATOR, ack.attribution());

		assertEquals(500L, share(reload(a)));
		assertEquals(500L, share(reload(b)));
		assertNull(share(reload(untouched)), "the seat never moved it");
		AgentBoardData bd = board(r);
		assertEquals(1_000L, bd.getCoordinatorSharesMicros());
		assertEquals(2L, bd.getCoordinatorTransitions());
		assertNotNull(bd.getCoordinatorShareCursor(), "the cursor advanced");
	}

	@Test
	public void withNoCoordinatorTransitionsTheDeltaGoesToNonTerminalTasks() throws RelizaException {
		Rig r = rig();
		AgentTaskData a = registered(r);
		AgentTaskData b = registered(r);
		seatReports(r, 600);
		assertEquals(300L, share(reload(a)));
		assertEquals(300L, share(reload(b)));
		assertEquals(0L, board(r).getCoordinatorTransitions(), "a fallback counts no transition");
		assertEquals(0L, board(r).getCoordinatorOverheadMicros());

		Rig empty = rig();
		seatReports(empty, 400);
		AgentBoardData bd = board(empty);
		assertEquals(0L, bd.getCoordinatorSharesMicros(), "nobody to give it to");
		assertNotNull(bd.getCoordinatorShareCursor(), "and the cursor still advanced");
		assertEquals(400L, budgetService.spentOnBoard(bd), "the rows count at board level regardless");
	}

	@Test
	public void sharesAreFrozen() throws RelizaException {
		Rig r = rig();
		AgentTaskData a = movedBySeat(r);
		AgentTaskData b = movedBySeat(r);
		seatReports(r, 1_000);

		// A later move of a third task: the next delta is its alone, and the first shares stay.
		AgentTaskData c = movedBySeat(r);
		seatReports(r, 300);
		assertEquals(500L, share(reload(a)));
		assertEquals(500L, share(reload(b)));
		assertEquals(300L, share(reload(c)));

		// A task leaving the board's open set changes nothing already written.
		agentTaskService.cancel(a.getUuid(), "no longer needed", r.seatActor(), WU);
		assertEquals(500L, share(reload(a)));
		AgentBoardData bd = board(r);
		assertEquals(1_300L, bd.getCoordinatorSharesMicros());
		assertEquals(3L, bd.getCoordinatorTransitions());
	}

	@Test
	public void theRemainderIsDeterministic() throws RelizaException {
		Rig r = rig();
		List<AgentTaskData> moved = new ArrayList<>(List.of(movedBySeat(r), movedBySeat(r), movedBySeat(r)));
		seatReports(r, 100);
		moved.sort(Comparator.comparing(t -> t.getUuid().toString()));
		assertEquals(34L, share(reload(moved.get(0))), "the earliest by uuid order takes the remainder");
		assertEquals(33L, share(reload(moved.get(1))));
		assertEquals(33L, share(reload(moved.get(2))));
	}

	@Test
	public void overheadIsTheAverageSharePerTransition() throws RelizaException {
		Rig r = rig();
		AgentTaskData a = movedBySeat(r);
		movedBySeat(r);
		seatReports(r, 1_000);
		assertEquals(500L, board(r).getCoordinatorOverheadMicros(), "1000 micros over 2 transitions");
		// A second task move by the seat: now 1300 micros over 3 transitions.
		agentTaskService.hold(a.getUuid(), AgentTaskData.HoldLevel.COORDINATOR, "wait", r.seatActor(), WU);
		seatReports(r, 300);
		assertEquals(433L, board(r).getCoordinatorOverheadMicros());
	}

	@Test
	public void projectAddsOverheadToNext() throws RelizaException {
		Rig r = rig();
		AgentTaskData a = movedBySeat(r);
		movedBySeat(r);
		seatReports(r, 1_000);      // overhead 500; each task's share 500

		// Task level: 500 spent (its share) + 1000 estimate + 500 overhead = 2000 against 1800.
		AgentTaskData td = reload(a);
		td.setBudgetMicros(1_800L);
		agentTaskService.saveData(td, WU);
		AgentBudgetService.Projection p = budgetService.project(reload(a), board(r), coder(r));
		assertFalse(p.fits());
		assertEquals("task", p.refusedBy());
		assertEquals(500L, p.overhead());
		assertEquals(1_500L, p.next());
		String refusal = budgetService.refusal(p, coder(r));
		assertTrue(refusal.contains("estimated at 1000 micros plus 500 micros of coordinator overhead"), refusal);

		// Board level: 1000 of seat rows + 1000 estimate fits 2400, the overhead does not.
		td = reload(a);
		td.setBudgetMicros(null);
		agentTaskService.saveData(td, WU);
		agentBoardService.updateSettings(r.board().getUuid(), 2_400L, null, null, null, null, null, WU);
		AgentBudgetService.Projection pb = budgetService.project(reload(a), board(r), coder(r));
		assertFalse(pb.fits());
		assertEquals("board", pb.refusedBy());
		assertEquals(2_500L, pb.wouldSpend());
	}

	/** AgentTask.spentMicros as a task read resolves it. */
	private Long served(AgentTaskData td) {
		return agentTaskDataFetcher.taskSpentMicros(new com.netflix.graphql.dgs.DgsDataFetchingEnvironment(
				graphql.schema.DataFetchingEnvironmentImpl.newDataFetchingEnvironment().source(td).build(),
				applicationContext));
	}

	@Test
	public void aTaskReadServesWhatTheBoardChargesTheTask() throws RelizaException {
		// task 02bfab7c: the task's rows plus its coordinator share, the figure its budget is held to
		Rig r = rig();
		AgentTaskData fresh = registered(r);
		assertEquals(0L, served(reload(fresh)), "a task that has spent nothing");

		AgentTaskData a = movedBySeat(r);
		movedBySeat(r);
		seatReports(r, 1_000);
		assertEquals(500L, served(reload(a)), "a coordinator share alone is spend");

		AgentData worker = agentService.findOrRegisterRootAgent(board(r).getOrg(), UUID.randomUUID(),
				"worker-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData ws = agentSessionService.initialize(board(r).getOrg(), worker.getUuid(), null,
				"s-" + UUID.randomUUID(), "worker", null, null, WU);
		agentTaskService.assign(a.getUuid(), board(r), worker.getUuid(), ws.getUuid(), WU);
		usageService.report(agentSessionService.getSessionData(ws.getUuid()).orElseThrow(), new UsageReport(ws.getUuid(),
				null, System.nanoTime(), SessionUsageSource.TRANSCRIPT, ZonedDateTime.now().minusMinutes(1),
				ZonedDateTime.now(), 1, 1, 60, null, a.getUuid(), Map.of(), List.of(new UsageLine(r.model(), null, 0L, 1,
						300, 0, 0, 0, null, 300L, 300L, null))), WU);

		AgentTaskData now = reload(a);
		assertEquals(800L, served(now), "its rows plus its share");
		assertEquals(budgetService.boardSpend(board(r)).spentOn(now), served(now), "the budget's own figure");
	}

	@Test
	public void boardSpendHasNoEstimates() throws RelizaException {
		Rig r = rig();
		AgentTaskData a = movedBySeat(r);
		movedBySeat(r);
		seatReports(r, 1_000);
		AgentBoardData bd = board(r);
		assertEquals(1_000L, budgetService.spentOnBoard(bd), "the seat's rows, not rows plus shares");
		AgentBudgetService.BoardSpend spend = budgetService.boardSpend(bd);
		assertEquals(500L, budgetService.spentOnTask(reload(a), spend), "a task's spend includes its share");
	}

	@Test
	public void aFailedApportionmentDoesNotFailTheReport() throws RelizaException {
		Rig r = rig();
		AgentTaskData a = movedBySeat(r);
		doThrow(new IllegalStateException("forced")).when(shares)
				.apportionOnce(any(), any(), anyLong(), any(), any());
		// The console appender holds the original stdout, so the error is read off the logger itself.
		List<String> errors = new CopyOnWriteArrayList<>();
		AbstractAppender capture = new AbstractAppender("share-" + UUID.randomUUID(), null, null, true,
				Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent e) {
				if (e.getLevel() == Level.ERROR) errors.add(e.getMessage().getFormattedMessage());
			}
		};
		capture.start();
		org.apache.logging.log4j.core.Logger logger =
				(org.apache.logging.log4j.core.Logger) LogManager.getLogger(CoordinatorShareService.class);
		logger.addAppender(capture);
		try {
			UsageAck ack = seatReports(r, 1_000);
			assertEquals(1, ack.accepted(), "the report is acknowledged");
			assertEquals(1_000L, budgetService.spentOnBoard(board(r)), "and its row committed");
			assertNull(share(reload(a)), "nothing apportioned");
			assertNull(board(r).getCoordinatorShareCursor());
			assertTrue(errors.stream().anyMatch(m -> m.contains("Apportioning a coordinator delta of 1000 micros")
					&& m.contains(r.board().getUuid().toString())), "and said so at error level: " + errors);
		} finally {
			logger.removeAppender(capture);
			capture.stop();
		}
	}
}
