/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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
import io.reliza.model.AgentTaskData.HopUsage;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.Organization;
import io.reliza.model.PricingEntry;
import io.reliza.model.PricingEntry.PricingUnit;
import io.reliza.model.SessionUsageSource;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentSessionUsageService.UsageAck;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.service.AgentTaskService.WorkerAssignment;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * A role's {@code hopBudgetMicros} is an allowance, not a cap (hop allowance design, gaps §1.7):
 * the worker is told it at assignment and on every usage report, a hop that ends over it is
 * stamped and alerted on the board, and nothing is refused for it.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentHopAllowanceIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentSessionUsageService usageService;
	@Autowired private ModelOntologyService modelOntologyService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:relizaio/allowance-demo";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final long ALLOWANCE = 1_000L;

	/** A board whose coder role sets an allowance and whose qa role does not. */
	private record Rig(Organization org, AgentBoardData board, AgentData worker, AgentSessionData session,
			String model) {}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "allowance-board",
				"test board", List.of(SRC), "you are the coordinator", 2, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder",
				"you are the coder", 10, null, false, true, null, null, null, null, null, null, null,
				ALLOWANCE), true, WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics("qa",
				"you are qa", 20, null, false, true, null), true, WU);
		AgentData coord = register(org, "coord");
		AgentSessionData coordSession = open(org, coord, "coord-s");
		board = agentBoardService.claimCoordinatorSeat(board.getUuid(), coordSession.getUuid(),
				coord.getUuid(), WU);
		AgentData worker = register(org, "worker");
		// A model of this test's own, priced at one micro per input token, so a report of N input
		// tokens costs exactly N micros and no other test's prices are touched.
		String model = "allowance-model-" + UUID.randomUUID();
		ModelOntologyData m = modelOntologyService.resolve(org.getUuid(), model, null, WU).model();
		modelOntologyService.addModelPricing(m.getUuid(), new PricingEntry(null,
				ZonedDateTime.now().minusDays(1), null, "USD", PricingUnit.PER_MILLION_TOKENS,
				1_000_000L, 0L, 0L, 0L, null, null, null, null, null, null), WU);
		return new Rig(org, board, worker, open(org, worker, "worker-s"), model);
	}

	private AgentData register(Organization org, String prefix) throws RelizaException {
		return agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				prefix + "-" + UUID.randomUUID(), null, null, null, WU);
	}

	private AgentSessionData open(Organization org, AgentData agent, String prefix) throws RelizaException {
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				prefix + "-" + UUID.randomUUID(), "test session", null, null, WU);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData authorized(Rig r, String ref, String role) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ref, "task " + ref, null, null,
				null, null, null, null, WU);
		return agentTaskService.authorize(t.getUuid(), board(r), role, 10, null, null, null, null, COORD, WU);
	}

	private AgentTaskData assigned(Rig r, String ref) throws RelizaException {
		AgentTaskData t = authorized(r, ref, "coder");
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		return t;
	}

	private UsageAck spend(Rig r, AgentSessionData s, UUID task, long seq, long inputTokens)
			throws RelizaException {
		AgentSessionData fresh = agentSessionService.getSessionData(s.getUuid()).orElseThrow();
		return usageService.report(fresh, new UsageReport(s.getUuid(), null, seq, SessionUsageSource.TRANSCRIPT,
				ZonedDateTime.now().minusMinutes(1), ZonedDateTime.now(), 1, 1, 60, null, task, Map.of(),
				List.of(new UsageLine(r.model(), null, 0L, 1, inputTokens, 0, 0, 0, null, inputTokens,
						inputTokens, null))), WU);
	}

	private List<AgentBoardData.BoardEvent> allowanceAlerts(Rig r) {
		return agentBoardService.recentEvents(board(r).getUuid()).stream()
				.filter(ev -> ev.kind() == AgentBoardData.BoardEventKind.ALERT)
				.filter(ev -> ev.message().contains("against an allowance of"))
				.toList();
	}

	@Test
	public void theAssignmentCarriesTheRoleAllowance() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = authorized(r, "1", "coder");
		WorkerAssignment wa = agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(),
				r.session().getUuid(), WU);
		assertEquals(ALLOWANCE, wa.hopBudgetMicros());

		authorized(r, "2", "qa");
		AgentData other = register(r.org(), "polling");
		WorkerAssignment polled = agentTaskService.next(List.of(board(r)), other.getUuid(),
				open(r.org(), other, "polling-s").getUuid()).orElseThrow();
		assertEquals("qa", polled.role());
		assertNull(polled.hopBudgetMicros(), "a role without an allowance hands out none");
	}

	@Test
	public void aHopOverItsAllowanceIsFlaggedAndAlerted() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assigned(r, "3");
		spend(r, r.session(), t.getUuid(), 1, 5_000);

		AgentTaskData signed = agentTaskService.signOff(t.getUuid(), r.session().getUuid(),
				SignOffOutcome.PASSED, "done", WU);
		HopUsage usage = signed.getSignOffs().get(signed.getSignOffs().size() - 1).usage();
		assertEquals(5_000L, usage.derivedCostMicros());
		assertEquals(ALLOWANCE, usage.allowanceMicros());
		assertEquals(4_000L, AgentBudgetService.overAllowanceMicros(usage));

		List<AgentBoardData.BoardEvent> alerts = allowanceAlerts(r);
		assertEquals(1, alerts.size(), "one alert per hop");
		String msg = alerts.get(0).message();
		assertTrue(msg.contains("hop of coder") && msg.contains("spent 5000 micros")
				&& msg.contains("allowance of 1000"), msg);
	}

	@Test
	public void aHopUnderItsAllowanceIsNotFlagged() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assigned(r, "4");
		spend(r, r.session(), t.getUuid(), 1, 400);

		AgentTaskData signed = agentTaskService.signOff(t.getUuid(), r.session().getUuid(),
				SignOffOutcome.PASSED, "done", WU);
		HopUsage usage = signed.getSignOffs().get(signed.getSignOffs().size() - 1).usage();
		assertEquals(ALLOWANCE, usage.allowanceMicros());
		assertEquals(0L, AgentBudgetService.overAllowanceMicros(usage));
		assertTrue(allowanceAlerts(r).isEmpty());
	}

	@Test
	public void aReturnIsFlaggedLikeASignOff() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assigned(r, "5");
		spend(r, r.session(), t.getUuid(), 1, 2_500);

		AgentTaskData returned = agentTaskService.returnTask(t.getUuid(), r.session().getUuid(),
				TaskReturnReason.OTHER, "this will cost more than the allowance", WU);
		HopUsage usage = returned.getReturns().get(returned.getReturns().size() - 1).usage();
		assertEquals(1_500L, AgentBudgetService.overAllowanceMicros(usage));
		assertEquals(1, allowanceAlerts(r).size());
	}

	@Test
	public void theUsageAckSaysHowMuchOfTheAllowanceIsLeft() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assigned(r, "6");
		UsageAck first = spend(r, r.session(), t.getUuid(), 1, 300);
		assertEquals(ALLOWANCE, first.hopAllowanceMicros());
		assertEquals(300L, first.hopSpentMicros());
		UsageAck second = spend(r, r.session(), t.getUuid(), 2, 900);
		assertEquals(1_200L, second.hopSpentMicros(), "what the hop has cost so far, not this report");

		// Outside an assignment there is no hop to measure against.
		agentTaskService.signOff(t.getUuid(), r.session().getUuid(), SignOffOutcome.PASSED, "done", WU);
		UsageAck after = spend(r, r.session(), null, 3, 100);
		assertNull(after.hopAllowanceMicros());
		assertNull(after.hopSpentMicros());
	}

	@Test
	public void theSignOffIsNotRefusedForBlowingTheAllowance() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assigned(r, "7");
		spend(r, r.session(), t.getUuid(), 1, 1_000_000);
		AgentTaskData signed = agentTaskService.signOff(t.getUuid(), r.session().getUuid(),
				SignOffOutcome.PASSED, "done", WU);
		// Routed onward exactly as an in-allowance hop would be: the allowance is not a cap.
		assertEquals(TaskStatus.QUEUED, signed.getStatus());
		assertEquals("qa", signed.getRole());
	}
}
