/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.Component;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.ComponentData;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.BranchData;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.BranchData.BranchType;
import io.reliza.model.Branch;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.AgentBoardData.BoardPauseLevel;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.SplitChild;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentTaskService.WorkerAssignment;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Pins the hub-and-spoke board semantics against real Postgres:
 * board-scoped roles with the implicit coordinator, the singleton
 * seat, two-tier pauses (assignment-blocking only, operator pause not
 * coordinator-liftable), source-validated idempotent intake,
 * authorize/assign/sign-off redirects, WIP limits, separation of
 * duties, returns, split lineage with explicit completion, and the
 * session-close release path (assignments + seat).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentTaskBoardIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentTaskInputService agentTaskInputService;
	@Autowired private GetComponentService getComponentService;
	@Autowired private io.reliza.service.oss.OssReleaseService ossReleaseService;
	@Autowired private BranchService branchService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:relizaio/board-demo";

	/** Typed stand-ins for the identity strings these tests used to pass. */
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AgentActor OPERATOR = AgentActor.ofUser(UUID.randomUUID(), "op@example.com");
	private static final AgentActor REVIEWER = AgentActor.ofUser(UUID.randomUUID(), "reviewer@example.com");
	private static final AgentActor PM = AgentActor.ofUser(UUID.randomUUID(), "pm@example.com");

	private record Rig(Organization org, Component targetNode, AgentBoardData board, AgentData coordAgent,
			AgentSessionData coordSession, AgentData workerA, AgentSessionData sessA,
			AgentData workerB, AgentSessionData sessB) {}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component targetNode = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "demo-board",
				"test board", List.of(SRC), "you are the coordinator", 2, null,
				targetNode.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics("coder", "you are the coder", 10, null, false, true, null), true, WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics("qa", "you are qa", 20, 1, true, true, null), true, WU);
		AgentData coordAgent = registerAgent(org.getUuid(), "coord");
		AgentData workerA = registerAgent(org.getUuid(), "worker-a");
		AgentData workerB = registerAgent(org.getUuid(), "worker-b");
		AgentSessionData coordSession = openSession(org.getUuid(), coordAgent, "coord-s");
		AgentSessionData sessA = openSession(org.getUuid(), workerA, "a-s");
		AgentSessionData sessB = openSession(org.getUuid(), workerB, "b-s");
		board = agentBoardService.claimCoordinatorSeat(board.getUuid(), coordSession.getUuid(),
				coordAgent.getUuid(), WU);
		return new Rig(org, targetNode, board, coordAgent, coordSession, workerA, sessA, workerB, sessB);
	}

	private AgentData registerAgent(UUID orgUuid, String prefix) throws RelizaException {
		return agentService.findOrRegisterRootAgent(orgUuid, UUID.randomUUID(),
				prefix + "-" + UUID.randomUUID(), null, null, null, WU);
	}

	private AgentSessionData openSession(UUID orgUuid, AgentData agent, String prefix) throws RelizaException {
		return agentSessionService.initialize(orgUuid, agent.getUuid(), null,
				prefix + "-" + UUID.randomUUID(), "test session", null, null, WU);
	}

	/** A split child with nothing but a title, which is the only required field. */
	private static SplitChild child(String title) {
		return new SplitChild(title, null, null, null, null, null);
	}

	private static SplitChild childAtLevel(String title, int level) {
		return new SplitChild(title, null, null, level, null, null);
	}

	private static SplitChild childDependingOn(String title, int siblingIndex) {
		return new SplitChild(title, null, null, null, null, List.of(siblingIndex));
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	@Test
	public void boardConfig_implicitCoordinatorAndSeatSingleton() throws RelizaException {
		Rig r = rig();
		// coordinator can never be configured as a task role
		assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(
				r.board(), AgentBoardService.RoleConfigSpec.ofBasics("coordinator", "nope", 5, null, false, true, null), true, WU));
		// seat is singleton: another session cannot claim while held
		assertThrows(RelizaException.class, () -> agentBoardService.claimCoordinatorSeat(
				r.board().getUuid(), r.sessA().getUuid(), r.workerA().getUuid(), WU));
		// seat-holder session takes no assignments
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#1", "task one", null,
				r.coordSession().getUuid(), null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				t.getUuid(), board(r), r.coordAgent().getUuid(), r.coordSession().getUuid(), WU));
		// closing the coordinator session frees the seat
		agentSessionService.close(r.coordSession().getUuid(), WU);
		assertNull(board(r).getCoordinatorSeat());
		AgentBoardData reclaimed = agentBoardService.claimCoordinatorSeat(
				r.board().getUuid(), r.sessA().getUuid(), r.workerA().getUuid(), WU);
		assertEquals(r.sessA().getUuid(), reclaimed.getCoordinatorSeat().session());
	}

	@Test
	public void intake_sourceValidationAndIdempotency() throws RelizaException {
		Rig r = rig();
		assertThrows(RelizaException.class, () -> agentTaskService.register(board(r),
				"github:other/repo#1", "stray", null, null, null, null, null, null, WU));
		AgentTaskData t1 = agentTaskService.register(board(r), SRC + "#2", "task", null,
				r.coordSession().getUuid(), null, null, null, null, WU);
		assertEquals(TaskStatus.PENDING_INTAKE, t1.getStatus());
		assertEquals(r.coordSession().getUuid(), t1.getRegisteredBySession());
		AgentTaskData t2 = agentTaskService.register(board(r), SRC + "#2", "other title", null, null, null, null, null, null, WU);
		assertEquals(t1.getUuid(), t2.getUuid());
	}

	@Test
	public void theServedPromptEndsWithTheBoardsRoutingRules() throws RelizaException {
		// gaps §1.28: the templates said PASSED with a P3 open is fine and the board routed it back
		// as a REJECTED. The served prompt states the board's thresholds, from its settings, so the
		// agent is judged by rules it was told.
		Rig r = rig();
		AgentTaskData t1 = agentTaskService.register(board(r), SRC + "#9101", "one", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t1.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		WorkerAssignment offered = agentTaskService.next(List.of(board(r)), r.workerA().getUuid(),
				r.sessA().getUuid()).orElseThrow();
		String strict = AgentBoardService.routingRules(board(r), agentBoardService.effectiveMerge(board(r)));
		assertTrue(strict.contains("every open item blocks"), strict);
		// task 71a3dd22: the block ends with the board's merge procedure, the default one here
		assertTrue(strict.endsWith("- Delivery: merges on this board are made by a person, method MERGE, at the tested head,"
				+ " declared: no; order: as the notes say.\n"), strict);
		assertEquals("you are the coder\n\n" + strict, offered.rolePrompt(), "the poll serves the block");
		WorkerAssignment wa = agentTaskService.assign(t1.getUuid(), board(r), r.workerA().getUuid(),
				r.sessA().getUuid(), WU);
		assertEquals("you are the coder\n\n" + strict, wa.rolePrompt(), "and so does the assignment");
		assertEquals(AgentBoardService.promptVersion("you are the coder"), wa.promptVersion(),
				"the version is the operator's prompt, not the served text");

		agentBoardService.updateSettings(r.board().getUuid(), null, null, null, null, 2, null, WU);
		AgentTaskData t2 = agentTaskService.register(board(r), SRC + "#9102", "two", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t2.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		WorkerAssignment wa2 = agentTaskService.assign(t2.getUuid(), board(r), r.workerB().getUuid(),
				r.sessB().getUuid(), WU);
		String thresholded = AgentBoardService.routingRules(board(r), agentBoardService.effectiveMerge(board(r)));
		assertTrue(thresholded.contains("items with priority ≤ 2 block"), thresholded);
		assertEquals("you are the coder\n\n" + thresholded, wa2.rolePrompt(), "the block follows the settings");
		assertEquals(wa.promptVersion(), wa2.promptVersion(), "a settings change is not a prompt edit");
		assertEquals("you are the coder", agentBoardService.getRoleConfig(r.board().getUuid(), "coder")
				.orElseThrow().getPrompt(), "the stored prompt is untouched");
		assertEquals(wa.promptVersion(), agentTaskService.getTaskData(t2.getUuid()).orElseThrow()
				.getAssignment().promptVersion(), "and the assignment records the prompt's version");
	}

	// ---------- settings and allowances from the forms (task 40f270be) ----------

	private static Map<String, Object> settings(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	@Test
	public void theBoardFormSetsClearsAndLeavesSettingsAsAFileDoes() throws RelizaException {
		Rig r = rig();
		UUID b = r.board().getUuid();
		agentBoardService.updateSettings(b, null, 70, 4, 2, 2, 1, WU);

		AgentBoardData set = agentBoardService.updateSettingsFromInput(b, settings("budgetMicros", 5_000_000L), WU);
		assertEquals(5_000_000L, set.getBudgetMicros());
		assertEquals(70, set.getSoftAlertPercent(), "a setting left out stays");
		assertEquals(4, set.getCycleCap());
		assertEquals(2, set.getBlockingPriority());

		AgentBoardData cleared = agentBoardService.updateSettingsFromInput(b, settings("cycleCap", null), WU);
		assertNull(cleared.getCycleCap(), "a setting sent as null is cleared");
		assertEquals(5_000_000L, cleared.getBudgetMicros());

		AgentBoardData none = agentBoardService.updateSettingsFromInput(b, null, WU);
		assertEquals(5_000_000L, none.getBudgetMicros(), "no settings, no change");

		RelizaException refused = assertThrows(RelizaException.class,
				() -> agentBoardService.updateSettingsFromInput(b, settings("budgetMicros", -1L), WU));
		assertTrue(refused.getMessage().contains("settings.budgetMicros cannot be negative"), refused.getMessage());
		assertThrows(RelizaException.class,
				() -> agentBoardService.updateSettingsFromInput(b, settings("softAlertPercent", 150), WU),
				"the same checks as a board file");
		assertEquals(5_000_000L, board(r).getBudgetMicros(), "a refused form writes nothing");
	}

	@Test
	public void aRoleAndAPresetTakeTheirAllowanceFromTheForm() throws RelizaException {
		Rig r = rig();
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("coder", null, null, null, null,
				null, null, null, null, null, null, null, null, 1_500_000L), true, WU);
		AgentTaskRoleConfigData coder = agentBoardService.getRoleConfig(r.board().getUuid(), "coder").orElseThrow();
		assertEquals(1_500_000L, coder.getHopBudgetMicros());
		assertEquals(1_500_000L, agentBoardService.exportBoard(r.board().getUuid()).getRoles().stream()
				.filter(x -> "coder".equals(x.getName())).findFirst().orElseThrow().getHopBudgetMicros(),
				"the export shows what the form set: one source of truth");
		// the coordinator seat may not touch it
		assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(board(r),
				new AgentBoardService.RoleConfigSpec("coder", null, null, null, null, null, null, null, null, null,
						null, null, null, 2_000_000L), false, WU));
		AgentTaskRoleConfigData cleared = agentBoardService.clearHopBudget(coder, WU);
		assertNull(cleared.getHopBudgetMicros());
		assertNull(agentBoardService.getRoleConfig(r.board().getUuid(), "coder").orElseThrow().getHopBudgetMicros());

		AgentTaskRoleConfigData preset = agentBoardService.upsertPreset(r.org().getUuid(),
				new AgentBoardService.RoleConfigSpec("allowance-preset-" + UUID.randomUUID(), "p", 10, null, null, true,
						null, null, null, null, null, null, null, 700_000L), WU);
		assertEquals(700_000L, preset.getHopBudgetMicros());
		assertNull(agentBoardService.clearHopBudget(preset, WU).getHopBudgetMicros());
	}

	@Test
	public void hubFlow_authorizeAssignSignOffRedirect() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#3", "feature", null, null, null, null, null, null, WU);
		// cannot assign before authorization
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU));
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		WorkerAssignment wa = agentTaskService.assign(t.getUuid(), board(r),
				r.workerA().getUuid(), r.sessA().getUuid(), WU);
		assertEquals("coder", wa.role());
		assertEquals("you are the coder\n\n" + AgentBoardService.routingRules(board(r), agentBoardService.effectiveMerge(board(r))), wa.rolePrompt());
		assertNotNull(wa.promptVersion());
		// second session cannot take the assigned task
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU));
		// sign-off must come from the assigned session
		assertThrows(RelizaException.class, () -> agentTaskService.signOff(
				t.getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED, null, WU));
		AgentTaskData afterSign = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.PASSED, "done", WU);
		// The board routes a clean hop to the next role whose inputs are satisfied, so this no
		// longer waits for the coordinator to read the sign-off and say the obvious thing.
		assertEquals(TaskStatus.QUEUED, afterSign.getStatus());
		assertEquals("qa", afterSign.getRole(), "routed onward, not parked");
		assertNull(afterSign.getAssignment());
		assertEquals(1, afterSign.getSignOffs().size());
		// qa hop: distinct agent enforced (worker A signed off last)
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU));
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		// Every role has passed and nothing is open, so the board completes the task itself --
		// the coordinator no longer has to notice and say so.
		AgentTaskData done = agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(),
				SignOffOutcome.PASSED, "verified", WU);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertNotNull(done.getCompletedAt());
		assertEquals(List.of(r.sessA().getUuid(), r.sessB().getUuid()), done.getSessions());
	}

	@Test
	public void wipLimits_perRoleAndPerAgent() throws RelizaException {
		Rig r = rig();
		// per-role: qa wipLimit=1
		AgentTaskData q1 = agentTaskService.register(board(r), SRC + "#10", "q1", null, null, null, null, null, null, WU);
		AgentTaskData q2 = agentTaskService.register(board(r), SRC + "#11", "q2", null, null, null, null, null, null, WU);
		agentTaskService.authorize(q1.getUuid(), board(r), "qa", 1, null, null, null, null, COORD, WU);
		agentTaskService.authorize(q2.getUuid(), board(r), "qa", 2, null, null, null, null, COORD, WU);
		agentTaskService.assign(q1.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				q2.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU));
		// per-agent: board limit 2 -- worker A holds q1 (qa) + c1 (coder); c2 must be refused
		AgentTaskData c1 = agentTaskService.register(board(r), SRC + "#12", "c1", null, null, null, null, null, null, WU);
		AgentTaskData c2 = agentTaskService.register(board(r), SRC + "#13", "c2", null, null, null, null, null, null, WU);
		agentTaskService.authorize(c1.getUuid(), board(r), "coder", 3, null, null, null, null, COORD, WU);
		agentTaskService.authorize(c2.getUuid(), board(r), "coder", 4, null, null, null, null, COORD, WU);
		agentTaskService.assign(c1.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				c2.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU));
		// the poll honors the same constraints: worker A is offered nothing, worker B gets c2
		assertTrue(agentTaskService.next(List.of(board(r)), r.workerA().getUuid(), r.sessA().getUuid()).isEmpty());
		WorkerAssignment nb = agentTaskService.next(List.of(board(r)), r.workerB().getUuid(), r.sessB().getUuid())
				.orElseThrow();
		assertEquals(c2.getUuid(), nb.task().getUuid());
	}

	@Test
	public void pauses_blockNewAssignmentsOnly_operatorPauseNotCoordinatorLiftable() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#20", "locked away", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 1, null, null, null, null, COORD, WU);
		AgentTaskData inFlight = agentTaskService.register(board(r), SRC + "#21", "in flight", null, null, null, null, null, null, WU);
		agentTaskService.authorize(inFlight.getUuid(), board(r), "coder", 2, null, null, null, null, COORD, WU);
		agentTaskService.assign(inFlight.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		// coordinator pause: poll dry, assignment refused, in-flight sign-off still lands
		agentBoardService.setPause(r.board().getUuid(), BoardPauseLevel.COORDINATOR, true, "waiting on human", COORD, WU);
		assertTrue(agentTaskService.next(List.of(board(r)), r.workerA().getUuid(), r.sessA().getUuid()).isEmpty());
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU));
		AgentTaskData signed = agentTaskService.signOff(inFlight.getUuid(), r.sessB().getUuid(),
				SignOffOutcome.PASSED, "finished under a pause", WU);
		// In flight work finishes under a lock; where it goes next is routing's business, and the
		// point here is only that the lock did not stop the sign-off.
		assertNotEquals(TaskStatus.ASSIGNED, signed.getStatus());
		// operator escalates the lock; the coordinator can neither lift nor override it
		agentBoardService.setPause(r.board().getUuid(), BoardPauseLevel.OPERATOR, true, "operator hold", OPERATOR, WU);
		assertThrows(RelizaException.class, () -> agentBoardService.setPause(
				r.board().getUuid(), BoardPauseLevel.COORDINATOR, false, null, COORD, WU));
		assertThrows(RelizaException.class, () -> agentBoardService.setPause(
				r.board().getUuid(), BoardPauseLevel.COORDINATOR, true, "re-pause", COORD, WU));
		agentBoardService.setPause(r.board().getUuid(), BoardPauseLevel.OPERATOR, false, null, OPERATOR, WU);
		assertNull(board(r).getPause());
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
	}

	@Test
	public void returns_and_sessionCloseRelease() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#30", "confusing", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 1, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		// OTHER requires a description
		assertThrows(RelizaException.class, () -> agentTaskService.returnTask(
				t.getUuid(), r.sessA().getUuid(), TaskReturnReason.OTHER, " ", WU));
		AgentTaskData returned = agentTaskService.returnTask(t.getUuid(), r.sessA().getUuid(),
				TaskReturnReason.TASK_UNCLEAR, "acceptance criteria missing", WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, returned.getStatus());
		assertEquals(TaskReturnReason.TASK_UNCLEAR, returned.getReturns().get(0).reason());
		// re-queue, assign to B, then close B's session: assignment released with SESSION_CLOSED, and
		// the task goes back to the queue for its role (task 6e7fe6fe), not to the coordinator
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 1, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		agentSessionService.close(r.sessB().getUuid(), WU);
		AgentTaskData released = agentTaskService.getTaskData(t.getUuid()).orElseThrow();
		assertEquals(TaskStatus.QUEUED, released.getStatus());
		assertEquals("coder", released.getRole());
		assertNull(released.getAssignment());
		assertEquals(TaskReturnReason.SESSION_CLOSED,
				released.getReturns().get(released.getReturns().size() - 1).reason());
	}

	// ---------- a return posts a board event (gaps §1.9) ----------

	private List<AgentBoardData.BoardEvent> eventsAbout(AgentBoardData board, String fragment) {
		return agentBoardService.recentEvents(agentBoardService.getBoardData(board.getUuid()).orElseThrow().getUuid()).stream()
				.filter(ev -> ev.message().contains(fragment)).toList();
	}

	private AgentTaskData assignedCoder(Rig r, AgentBoardData board, String ref, AgentSessionData s,
			AgentData agent) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board, SRC + "#" + ref, "task " + ref, null, null, null, null, null,
				null, WU);
		agentTaskService.authorize(t.getUuid(), board, "coder", 1, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board, agent.getUuid(), s.getUuid(), WU);
		return t;
	}

	@Test
	public void aReturnForClarityPostsAnAlert() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedCoder(r, board(r), "140", r.sessA(), r.workerA());
		AgentTaskData returned = agentTaskService.returnTask(t.getUuid(), r.sessA().getUuid(),
				TaskReturnReason.TASK_UNCLEAR, "which branch?", WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, returned.getStatus(), "routing unchanged");
		List<AgentBoardData.BoardEvent> events = eventsAbout(board(r), "returned by");
		assertEquals(1, events.size());
		assertEquals(AgentBoardData.BoardEventKind.ALERT, events.get(0).kind());
		// Events name the task by key and title (board-documents.md D12, task 3d1f9dd7).
		assertEquals("Task " + returned.label() + " returned by coder (TASK_UNCLEAR): which branch?", events.get(0).message());
	}

	@Test
	public void aRoleMismatchReturnIsInformational() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedCoder(r, board(r), "141", r.sessA(), r.workerA());
		agentTaskService.returnTask(t.getUuid(), r.sessA().getUuid(), TaskReturnReason.ROLE_MISMATCH, "  ", WU);
		List<AgentBoardData.BoardEvent> events = eventsAbout(board(r), "returned by");
		assertEquals(1, events.size());
		assertEquals(AgentBoardData.BoardEventKind.INFO, events.get(0).kind());
		assertEquals("Task " + agentTaskService.getTaskData(t.getUuid()).orElseThrow().label()
				+ " returned by coder (ROLE_MISMATCH)", events.get(0).message(),
				"a blank description is left out");
	}

	@Test
	public void aLongDescriptionIsTrimmed() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedCoder(r, board(r), "142", r.sessA(), r.workerA());
		agentTaskService.returnTask(t.getUuid(), r.sessA().getUuid(), TaskReturnReason.NEEDS_HUMAN, "x".repeat(500), WU);
		String message = eventsAbout(board(r), "returned by").get(0).message();
		assertTrue(message.endsWith(": " + "x".repeat(197) + "..."), message);
	}

	@Test
	public void aSessionCloseReleasePostsOneInfoPerTask() throws RelizaException {
		Rig r = rig();
		AgentTaskData one = assignedCoder(r, board(r), "143", r.sessA(), r.workerA());
		AgentTaskData two = assignedCoder(r, board(r), "144", r.sessA(), r.workerA());
		// The same session also holds a task on a second board of the org.
		AgentBoardData other = agentBoardService.createBoard(r.org().getUuid(), "other-" + UUID.randomUUID(), "second board",
				List.of(SRC), "coordinate", 2, null, r.targetNode().getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(other, AgentBoardService.RoleConfigSpec.ofBasics("coder", "you are the coder",
				10, null, false, true, null), true, WU);
		assignedCoder(r, other, "145", r.sessA(), r.workerA());

		agentSessionService.close(r.sessA().getUuid(), WU);

		List<AgentBoardData.BoardEvent> here = eventsAbout(board(r), " unassigned");
		assertEquals(2, here.size(), "one line per task");
		assertTrue(here.stream().allMatch(ev -> ev.kind() == AgentBoardData.BoardEventKind.INFO));
		assertTrue(here.stream().anyMatch(ev -> ev.message().startsWith("Task " + one.label() + " unassigned")));
		assertTrue(here.stream().anyMatch(ev -> ev.message().startsWith("Task " + two.label() + " unassigned")));
		assertEquals(1, eventsAbout(other, " unassigned").size(), "each board gets its own");
		// back to the queue for their role (task 6e7fe6fe), not to the coordinator
		assertEquals(TaskStatus.QUEUED, agentTaskService.getTaskData(one.getUuid()).orElseThrow().getStatus());
		assertEquals(TaskStatus.QUEUED, agentTaskService.getTaskData(two.getUuid()).orElseThrow().getStatus());
	}

	@Test
	public void theEventLandsOnlyIfTheReturnCommits() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = assignedCoder(r, board(r), "146", r.sessA(), r.workerA());
		assertThrows(RelizaException.class, () -> agentTaskService.returnTask(
				t.getUuid(), r.sessA().getUuid(), TaskReturnReason.OTHER, " ", WU));
		assertTrue(eventsAbout(board(r), "returned by").isEmpty());
		assertEquals(TaskStatus.ASSIGNED, agentTaskService.getTaskData(t.getUuid()).orElseThrow().getStatus());
	}

	@Test
	public void split_childrenPendingIntake_parentCompletionExplicit() throws RelizaException {
		Rig r = rig();
		AgentTaskData parent = agentTaskService.register(board(r), SRC + "#40", "epic", null, null, null, null, null, null, WU);
		List<AgentTaskData> children = agentTaskService.split(parent.getUuid(), board(r),
				List.of(child("part 1"), child("part 2")), COORD, WU);
		assertEquals(2, children.size());
		assertEquals(TaskStatus.PENDING_INTAKE, children.get(0).getStatus());
		assertEquals(parent.getUuid(), children.get(0).getParentTask());
		// parent cannot complete while children are incomplete
		assertThrows(RelizaException.class, () -> agentTaskService.complete(parent.getUuid(), null, COORD, WU));
		for (AgentTaskData child : children) {
			agentTaskService.authorize(child.getUuid(), board(r), "coder", 1, null, null, null, null, COORD, WU);
			agentTaskService.assign(child.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
			agentTaskService.signOff(child.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED, null, WU);
			agentTaskService.complete(child.getUuid(), null, COORD, WU);
		}
		AgentTaskData done = agentTaskService.complete(parent.getUuid(), "children delivered", COORD, WU);
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
	}

	@Test
	public void v11_dependencyGating_underParallelWorkers() throws RelizaException {
		Rig r = rig();
		AgentTaskData parent = agentTaskService.register(board(r), SRC + "#50", "dep epic", null, null, null, null, null, null, WU);
		// split with sibling-index deps: child1 depends on child0, child2 on child1
		List<AgentTaskData> ch = agentTaskService.split(parent.getUuid(), board(r), List.of(
				child("backend"),
				childDependingOn("frontend", 0),
				childDependingOn("tests", 1)), COORD, WU);
		assertEquals(List.of(ch.get(0).getUuid()), ch.get(1).getDependsOn());
		// coordinator lays out the WHOLE plan up front
		for (AgentTaskData c : ch) {
			agentTaskService.authorize(c.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		}
		// only child0 is eligible: poll offers it, direct assign of child1 is refused
		WorkerAssignment next = agentTaskService.next(List.of(board(r)), r.workerA().getUuid(),
				r.sessA().getUuid()).orElseThrow();
		assertEquals(ch.get(0).getUuid(), next.task().getUuid());
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				ch.get(1).getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU));
		// complete child0 -> child1 becomes eligible for a DIFFERENT worker in parallel
		agentTaskService.assign(ch.get(0).getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		agentTaskService.signOff(ch.get(0).getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED, null, WU);
		agentTaskService.complete(ch.get(0).getUuid(), null, COORD, WU);
		WorkerAssignment nb = agentTaskService.next(List.of(board(r)), r.workerB().getUuid(),
				r.sessB().getUuid()).orElseThrow();
		assertEquals(ch.get(1).getUuid(), nb.task().getUuid());
		// cycle rejection: child1 cannot gain a dep back onto child2
		agentTaskService.assign(ch.get(1).getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		agentTaskService.signOff(ch.get(1).getUuid(), r.sessB().getUuid(), SignOffOutcome.PASSED, null, WU);
		assertThrows(RelizaException.class, () -> agentTaskService.authorize(
				ch.get(1).getUuid(), board(r), "coder", 10, List.of(ch.get(2).getUuid()), null, null, null, COORD, WU));
	}

	// ---------- a dependency-cycle refusal names the whole cycle (e769e9a2) ----------

	private AgentTaskData task(Rig r, String ref, String title) throws RelizaException {
		return agentTaskService.register(board(r), SRC + ref, title, null, null, null, null, null, null, WU);
	}

	private void dependOn(Rig r, AgentTaskData t, AgentTaskData... deps) throws RelizaException {
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10,
				java.util.Arrays.stream(deps).map(AgentTaskData::getUuid).toList(), null, null, null, COORD, WU);
	}

	private static String shortId(AgentTaskData t) {
		return t.getUuid().toString().substring(0, 8);
	}

	@Test
	public void aThreeTaskCycleIsNamedInOrder() throws RelizaException {
		Rig r = rig();
		AgentTaskData a = task(r, "#9101", "Run the budget projection before the hop is queued");
		AgentTaskData b = task(r, "#9102", "Make role hopBudgetMicros an allowance");
		AgentTaskData c = task(r, "#9103", "Re-route or complete");
		dependOn(r, a, b);
		dependOn(r, b, c);
		RelizaException e = assertThrows(RelizaException.class, () -> dependOn(r, c, a));
		// C depends on A, A on B, B on C: read from the task being authorised, each on the next.
		assertEquals("Dependency cycle: " + shortId(c) + " (Re-route or complete) → "
				+ shortId(a) + " (Run the budget projection before the hop…) → "
				+ shortId(b) + " (Make role hopBudgetMicros an allowance) → " + shortId(c)
				+ ". Each task depends on the next. [uuids: " + c.getUuid() + ", " + a.getUuid() + ", "
				+ b.getUuid() + "]", e.getMessage());
		// Refused, not recorded: C still depends on nothing.
		assertTrue(agentTaskService.getTaskData(c.getUuid()).orElseThrow().getDependsOn().isEmpty());
	}

	@Test
	public void aDiamondIsNotACycle() throws RelizaException {
		Rig r = rig();
		AgentTaskData a = task(r, "#9201", "top");
		AgentTaskData b = task(r, "#9202", "left");
		AgentTaskData c = task(r, "#9203", "right");
		AgentTaskData d = task(r, "#9204", "bottom");
		dependOn(r, b, d);
		dependOn(r, c, d);
		dependOn(r, a, b, c);
		assertEquals(List.of(b.getUuid(), c.getUuid()),
				agentTaskService.getTaskData(a.getUuid()).orElseThrow().getDependsOn());
	}

	@Test
	public void aSelfDependencyKeepsItsMessage() throws RelizaException {
		Rig r = rig();
		AgentTaskData a = task(r, "#9301", "alone");
		RelizaException e = assertThrows(RelizaException.class, () -> dependOn(r, a, a));
		assertEquals("Task cannot depend on itself", e.getMessage());
	}

	@Test
	public void aLongerCycleThroughASplitPlanIsNamed() throws RelizaException {
		Rig r = rig();
		AgentTaskData parent = task(r, "#9401", "ring epic");
		// five children, each depending on the next and the last on the first
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.split(parent.getUuid(),
				board(r), List.of(childDependingOn("ring 0", 1), childDependingOn("ring 1", 2),
						childDependingOn("ring 2", 3), childDependingOn("ring 3", 4), childDependingOn("ring 4", 0)),
				COORD, WU));
		String m = e.getMessage();
		assertTrue(m.startsWith("Dependency cycle: "), m);
		// The fifth child's dependency closes the ring: it leads, then 0, 1, 2, 3, and back to 4.
		List<String> order = List.of("ring 4", "ring 0", "ring 1", "ring 2", "ring 3");
		int at = -1;
		for (String t : order) {
			int i = m.indexOf("(" + t + ")");
			assertTrue(i > at, t + " in order in: " + m);
			at = i;
		}
		String tail = m.substring(m.indexOf("[uuids: ") + 8, m.length() - 1);
		assertEquals(5, tail.split(", ").length, m);
		// The split is refused as a whole: the parent has no children.
		assertTrue(agentTaskService.getTaskData(parent.getUuid()).orElseThrow().getChildTasks().isEmpty());
	}

	@Test
	public void theCycleMessageTrimsTitlesAndSurvivesAMissingOne() {
		UUID start = UUID.fromString("487d02f1-0000-0000-0000-000000000001");
		UUID dep = UUID.fromString("6589725f-0000-0000-0000-000000000002");
		Map<UUID, UUID> parent = Map.of(dep, start, start, dep);
		String m = AgentTaskService.cycleMessage(start, parent, Map.of(start, "x".repeat(60)));
		assertEquals("Dependency cycle: 487d02f1 (" + "x".repeat(40) + "…) → 6589725f → 487d02f1."
				+ " Each task depends on the next. [uuids: " + start + ", " + dep + "]", m);
	}

	@Test
	public void v11_holdExcludedFromPoll_releaseReturnsToCoordinator() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#60", "needs human", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.hold(t.getUuid(), AgentTaskData.HoldLevel.COORDINATOR, "waiting for author to clarify acceptance criteria", COORD, WU);
		AgentTaskData held = agentTaskService.getTaskData(t.getUuid()).orElseThrow();
		assertEquals(TaskStatus.ON_HOLD, held.getStatus());
		assertNotNull(held.getHold());
		assertEquals(AgentTaskData.HoldKind.MANUAL, held.getHold().kind());
		assertTrue(agentTaskService.next(List.of(board(r)), r.workerA().getUuid(),
				r.sessA().getUuid()).isEmpty());
		AgentTaskData released = agentTaskService.liftHold(t.getUuid(), AgentTaskData.HoldLevel.COORDINATOR, COORD, WU);
		// Releasing a hold hands the task back to the BOARD, not to the coordinator: whatever
		// parked it has been dealt with, and the last hop still says what happens next.
		assertEquals(TaskStatus.QUEUED, released.getStatus());
		assertNull(released.getHold());
	}

	@Test
	public void v11_promptGovernance_seatCannotCreateOrEditPrompts() throws RelizaException {
		Rig r = rig();
		// seat path (allowPromptEdit=false): prompt change refused, creation refused
		assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(
				board(r), AgentBoardService.RoleConfigSpec.ofBasics("coder", "rewritten prompt", null, null, null, null, null), false, WU));
		assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(
				board(r), AgentBoardService.RoleConfigSpec.ofBasics("newrole", "prompt", 5, null, false, true, null), false, WU));
		// but tuning an existing role's order/wip is allowed
		var tuned = agentBoardService.upsertRoleConfig(
				board(r), AgentBoardService.RoleConfigSpec.ofBasics("coder", null, 15, 3, null, null, null), false, WU);
		assertEquals(15, tuned.getOrderIndex());
		assertEquals(3, tuned.getWipLimit());
		assertEquals("you are the coder", tuned.getPrompt());
	}

	@Test
	public void v11_presetsSeedBoards_missingCapabilitiesAlert() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		// D8: the board below is created WITH sources, so the prompt it seeds from is the tracker
		// template. The legacy name is seeded too and must NOT be consulted -- falling back to it
		// would hand a tracker board the prompt written for a board that has none.
		agentBoardService.upsertPreset(org.getUuid(), AgentBoardService.RoleConfigSpec.ofBasics(
				"coordinator-tracker", "preset coordinator prompt", 0, null, false, true, null), WU);
		agentBoardService.upsertPreset(org.getUuid(), AgentBoardService.RoleConfigSpec.ofBasics(
				"coordinator", "the pre-D8 prompt", 0, null, false, true, null), WU);
		agentBoardService.upsertPreset(org.getUuid(), AgentBoardService.RoleConfigSpec.ofBasics(
				"coder", "preset coder prompt", 10, null, false, true,
				List.of(io.reliza.model.AgentTaskRoleConfigData.AgentCapability.CODE_PUSH,
						io.reliza.model.AgentTaskRoleConfigData.AgentCapability.TRACKER_WRITE)), WU);
		agentBoardService.upsertPreset(org.getUuid(), AgentBoardService.RoleConfigSpec.ofBasics(
				"reviewer", "preset reviewer prompt", 20, null, false, true,
				List.of(io.reliza.model.AgentTaskRoleConfigData.AgentCapability.PR_MERGE)), WU);
		Component seededTarget = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "seeded", null,
				List.of(SRC), null, null, null, seededTarget.getUuid(), null, WU);
		board = agentBoardService.seedFromPresets(board, WU);
		assertEquals("preset coordinator prompt", board.getCoordinatorPrompt());
		var roles = agentBoardService.listRoleConfigs(board.getUuid());
		// coder and reviewer. Neither coordinator preset becomes a role: they are prompts for the
		// implicit seat, and a board that grew a "coordinator-tracker" role would be wrong twice.
		assertEquals(2, roles.size());
		// delivery minimum covered -> no alert
		assertTrue(agentBoardService.missingCapabilities(board.getUuid()).isEmpty());
		// deactivate reviewer -> PR_MERGE goes missing
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics("reviewer", null, null, null, null, false, null), true, WU);
		assertEquals(List.of(io.reliza.model.AgentTaskRoleConfigData.AgentCapability.PR_MERGE),
				agentBoardService.missingCapabilities(board.getUuid()));
	}

	@Test
	public void v11_statusHistory_recordsEveryTransitionWithTriggers() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#70", "history probe", null,
				r.coordSession().getUuid(), null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED, null, WU);
		agentTaskService.hold(t.getUuid(), AgentTaskData.HoldLevel.COORDINATOR, "probe hold", COORD, WU);
		agentTaskService.liftHold(t.getUuid(), AgentTaskData.HoldLevel.COORDINATOR, COORD, WU);
		agentTaskService.complete(t.getUuid(), null, COORD, WU);
		AgentTaskData td = agentTaskService.getTaskData(t.getUuid()).orElseThrow();
		var hist = td.getStatusHistory();
		// The AUTHORIZE after SIGNOFF is the board routing the task to the next role: the same
		// trigger a coordinator would have used, with a SYSTEM actor saying who did it, so
		// cycle-time queries over this history read exactly as before.
		// Two of the AUTHORIZE rows are the board routing: once after the sign-off, once after
		// the hold was released -- a released hold puts the task back in the loop rather than in
		// the coordinator's lap. Same trigger a coordinator would use, SYSTEM actor saying who.
		assertEquals(List.of(StatusTrigger.REGISTER, StatusTrigger.AUTHORIZE, StatusTrigger.ASSIGN,
						StatusTrigger.SIGNOFF, StatusTrigger.AUTHORIZE, StatusTrigger.HOLD,
						StatusTrigger.LIFT_HOLD, StatusTrigger.AUTHORIZE, StatusTrigger.COMPLETE),
				hist.stream().map(AgentTaskData.StatusChange::trigger).toList());
		// each row's from matches the previous row's to; first from is null
		assertNull(hist.get(0).from());
		for (int i = 1; i < hist.size(); i++) {
			assertEquals(hist.get(i - 1).to(), hist.get(i).from());
		}
		// worker transitions carry the worker session; coordinator ops carry the coordinator,
		// which they could not before -- the field was a session uuid and a coordinator op wrote null
		assertEquals(r.sessA().getUuid(), hist.get(2).actor().uuid());
		assertEquals(r.sessA().getUuid(), hist.get(3).actor().uuid());
		assertEquals(COORD, hist.get(1).actor());
		// current status always equals the last row's to
		assertEquals(td.getStatus(), hist.get(hist.size() - 1).to());
		// the SESSION_CLOSED release path records too
		AgentTaskData t2 = agentTaskService.register(board(r), SRC + "#71", "release probe", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t2.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t2.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		agentSessionService.close(r.sessB().getUuid(), WU);
		AgentTaskData released = agentTaskService.getTaskData(t2.getUuid()).orElseThrow();
		var last = released.getStatusHistory().get(released.getStatusHistory().size() - 1);
		assertEquals(StatusTrigger.SESSION_CLOSED, last.trigger());
		assertEquals(r.sessB().getUuid(), last.actor().uuid());
	}

	@Test
	public void theCoordinatorsOrderOutranksTheRolesPlaceInThePipeline() throws RelizaException {
		Rig r = rig();
		// qa (20) is later in the pipeline than coder (10); the coordinator ranks the qa task first
		AgentTaskData hotfix = agentTaskService.register(board(r), SRC + "#90", "prod hotfix", null, null, null, null, null, null, WU);
		AgentTaskData refactor = agentTaskService.register(board(r), SRC + "#91", "nice-to-have refactor", null, null, null, null, null, null, WU);
		agentTaskService.authorize(hotfix.getUuid(), board(r), "qa", 1, null, null, null, null, COORD, WU);
		agentTaskService.authorize(refactor.getUuid(), board(r), "coder", 90, null, null, null, null, COORD, WU);
		assertEquals(hotfix.getUuid(), agentTaskService.next(List.of(board(r)), r.workerA().getUuid(),
				r.sessA().getUuid()).orElseThrow().task().getUuid());

		// On a STRICT board the coordinator's top task is the one that may be taken first.
		agentBoardService.updateBoard(r.board().getUuid(), null, null, null, null,
				AgentBoardData.PriorityType.STRICT, null, null, null, WU);
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				refactor.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU));
		agentTaskService.assign(hotfix.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
	}

	@Test
	public void rolePlaceInThePipelineBreaksATieInTheCoordinatorsOrder() throws RelizaException {
		Rig r = rig();
		// registered qa-first, so the list's own order would pick qa if role order were ignored
		AgentTaskData qaTask = agentTaskService.register(board(r), SRC + "#92", "check it", null, null, null, null, null, null, WU);
		AgentTaskData coderTask = agentTaskService.register(board(r), SRC + "#93", "write it", null, null, null, null, null, null, WU);
		agentTaskService.authorize(qaTask.getUuid(), board(r), "qa", 5, null, null, null, null, COORD, WU);
		agentTaskService.authorize(coderTask.getUuid(), board(r), "coder", 5, null, null, null, null, COORD, WU);
		assertEquals(coderTask.getUuid(), agentTaskService.next(List.of(board(r)), r.workerA().getUuid(),
				r.sessA().getUuid()).orElseThrow().task().getUuid());
	}

	@Test
	public void aTieNothingElseBreaksGoesToTheOldestTask() throws RelizaException {
		Rig r = rig();
		// Same role, same order. This passes without the explicit created-date key too, since the
		// repository returns queued tasks oldest first; the key is there so the rule no longer
		// rests on that sort and on min() keeping the first of two equal elements.
		AgentTaskData older = agentTaskService.register(board(r), SRC + "#94", "waited longest", null, null, null, null, null, null, WU);
		AgentTaskData newer = agentTaskService.register(board(r), SRC + "#95", "just arrived", null, null, null, null, null, null, WU);
		agentTaskService.authorize(newer.getUuid(), board(r), "coder", 7, null, null, null, null, COORD, WU);
		agentTaskService.authorize(older.getUuid(), board(r), "coder", 7, null, null, null, null, COORD, WU);
		assertEquals(older.getUuid(), agentTaskService.next(List.of(board(r)), r.workerA().getUuid(),
				r.sessA().getUuid()).orElseThrow().task().getUuid());
	}

	@Test
	public void v11_priorityType_strictRefusesOutOfOrderAssignment() throws RelizaException {
		Rig r = rig();
		// LAX (default): a worker may take a lower-priority eligible task
		AgentTaskData hi = agentTaskService.register(board(r), SRC + "#80", "high priority", null, null, null, null, null, null, WU);
		AgentTaskData lo = agentTaskService.register(board(r), SRC + "#81", "low priority", null, null, null, null, null, null, WU);
		agentTaskService.authorize(hi.getUuid(), board(r), "coder", 1, null, null, null, null, COORD, WU);
		agentTaskService.authorize(lo.getUuid(), board(r), "coder", 99, null, null, null, null, COORD, WU);
		assertEquals(hi.getUuid(), agentTaskService.next(List.of(board(r)), r.workerA().getUuid(),
				r.sessA().getUuid()).orElseThrow().task().getUuid());
		agentTaskService.assign(lo.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		agentTaskService.returnTask(lo.getUuid(), r.sessA().getUuid(),
				TaskReturnReason.ROLE_MISMATCH, null, WU);
		agentTaskService.authorize(lo.getUuid(), board(r), "coder", 99, null, null, null, null, COORD, WU);

		// STRICT: the same out-of-order assignment is refused, the top offer works
		agentBoardService.updateBoard(r.board().getUuid(), null, null, null, null,
				AgentBoardData.PriorityType.STRICT, null, null, null, WU);
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				lo.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU));
		agentTaskService.assign(hi.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		// with the top task taken, the next one down becomes assignable
		agentTaskService.assign(lo.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		assertEquals(TaskStatus.ASSIGNED,
				agentTaskService.getTaskData(lo.getUuid()).orElseThrow().getStatus());
	}

	@Test
	public void v12_humanGate_parksSignOffForVerdictOnlyWhenRoleDidSomething() throws RelizaException {
		Rig r = rig();
		// gate the coder role on PASS (operator path)
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"coder", null, null, null, null, null, null, null, null,
				AgentTaskRoleConfigData.HumanGate.ON_PASS), true, WU);
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#90", "gated work", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		AgentTaskData gated = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.PASSED, "done", WU);
		// parked ON_HOLD at OPERATOR level with an explicit HUMAN_GATE marker -- no guessing
		assertEquals(TaskStatus.ON_HOLD, gated.getStatus());
		assertEquals(AgentTaskData.HoldKind.HUMAN_GATE, gated.getHold().kind());
		assertEquals(AgentTaskData.HoldLevel.OPERATOR, gated.getHold().level());
		assertEquals("coder", gated.getHold().gateRole());
		assertEquals(StatusTrigger.HUMAN_GATE,
				gated.getStatusHistory().get(gated.getStatusHistory().size() - 1).trigger());
		// a board ALERT was auto-posted
		assertTrue(agentBoardService.recentEvents(board(r).getUuid()).stream().anyMatch(ev ->
				ev.kind() == AgentBoardData.BoardEventKind.ALERT && ev.message().contains("human review")));
		// neither a plain coordinator nor operator release resolves a gate
		assertThrows(RelizaException.class, () -> agentTaskService.liftHold(
				t.getUuid(), AgentTaskData.HoldLevel.COORDINATOR, COORD, WU));
		assertThrows(RelizaException.class, () -> agentTaskService.liftHold(
				t.getUuid(), AgentTaskData.HoldLevel.OPERATOR, COORD, WU));
		// The human verdict is a recorded sign-off and the board routes what follows, exactly as
		// after an agent hop -- otherwise a board with a gate would fall back to manual routing
		// after every verdict.
		AgentTaskData reviewed = agentTaskService.humanReview(t.getUuid(), true, "looks right",
				REVIEWER, WU);
		assertNotEquals(TaskStatus.ON_HOLD, reviewed.getStatus());
		assertNull(reviewed.getHold());
		var verdict = reviewed.getSignOffs().get(reviewed.getSignOffs().size() - 1);
		assertEquals(REVIEWER, verdict.reviewedBy());
		assertEquals(SignOffOutcome.PASSED, verdict.outcome());
		assertNull(verdict.agent());
		// "only if it did something": a REJECTED coder sign-off does not gate under ON_PASS
		AgentTaskData t2 = agentTaskService.register(board(r), SRC + "#91", "rejected work", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t2.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t2.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		AgentTaskData rejected = agentTaskService.signOff(
				t2.getUuid(), r.sessA().getUuid(), SignOffOutcome.REJECTED, null, WU);
		assertNull(rejected.getHold(), "a REJECTED coder hop does not fire an ON_PASS gate");
		assertNotEquals(TaskStatus.ON_HOLD, rejected.getStatus());
		// per-task add-only flag gates the next sign-off in an UNgated role, then clears
		AgentTaskData t3 = agentTaskService.register(board(r), SRC + "#92", "risky one-off", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t3.getUuid(), board(r), "qa", 10, null, null, null, null, COORD, WU);
		agentTaskService.setRequireHumanReview(t3.getUuid(), true, false, WU);
		// coordinator cannot clear the flag it set
		assertThrows(RelizaException.class, () -> agentTaskService.setRequireHumanReview(
				t3.getUuid(), false, false, WU));
		agentTaskService.assign(t3.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		AgentTaskData g3 = agentTaskService.signOff(t3.getUuid(), r.sessB().getUuid(),
				SignOffOutcome.PASSED, null, WU);
		assertEquals(AgentTaskData.HoldKind.HUMAN_GATE, g3.getHold().kind());
		assertTrue(!g3.isRequireHumanReview());
		// human rejection also releases to the coordinator (who re-queues for rework)
		AgentTaskData r3 = agentTaskService.humanReview(t3.getUuid(), false, "needs another pass",
				REVIEWER, WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, r3.getStatus());
		assertEquals(StatusTrigger.HUMAN_REJECT, r3.getStatusHistory().get(r3.getStatusHistory().size() - 1).trigger());
	}

	@Test
	public void v13_humanRole_neverPolledDirectSignOffFromQueue() throws RelizaException {
		Rig r = rig();
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"product-review", "check the demo against the acceptance criteria", 30, null, null, null,
				null, AgentTaskRoleConfigData.RoleKind.HUMAN, null, null), true, WU);
		// agent concepts are rejected on a HUMAN role
		assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(
				board(r), new AgentBoardService.RoleConfigSpec("product-review", null, null, 2, null,
						null, null, null, null, null), true, WU));
		assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(
				board(r), new AgentBoardService.RoleConfigSpec("product-review", null, null, null, null,
						null, null, null, null, AgentTaskRoleConfigData.HumanGate.ON_PASS), true, WU));
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#95", "human stage", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "product-review", 5, null, null, null, null, COORD, WU);
		// never offered to agent polls, never agent-assignable
		assertTrue(agentTaskService.next(List.of(board(r)), r.workerA().getUuid(),
				r.sessA().getUuid()).isEmpty());
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU));
		// direct human sign-off from the queue, same hop contract
		AgentTaskData signed = agentTaskService.humanSignOff(t.getUuid(), board(r),
				SignOffOutcome.PASSED, "matches the criteria", PM, WU);
		assertNotEquals(TaskStatus.ASSIGNED, signed.getStatus());
		var so = signed.getSignOffs().get(signed.getSignOffs().size() - 1);
		assertEquals("product-review", so.role());
		assertEquals(PM, so.reviewedBy());
		// direct human sign-off is refused on an AGENTIC role's queue
		AgentTaskData t2 = agentTaskService.register(board(r), SRC + "#96", "agent stage", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t2.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		assertThrows(RelizaException.class, () -> agentTaskService.humanSignOff(
				t2.getUuid(), board(r), SignOffOutcome.PASSED, null, PM, WU));
	}

	@Test
	public void v14_requiredRole_blocksCompletionUntilPassingSignOff() throws RelizaException {
		Rig r = rig();
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"qa", null, null, null, null, null, null, null,
				AgentTaskRoleConfigData.RoleNecessity.REQUIRED, null), true, WU);
		// a REQUIRED role cannot be deactivated
		assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(
				board(r), new AgentBoardService.RoleConfigSpec("qa", null, null, null, null, false,
						null, null, null, null), true, WU));
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#97", "must be reviewed", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED, null, WU);
		// coder passed, but qa (REQUIRED) never signed -> completion refused, gap named
		assertThrows(RelizaException.class, () -> agentTaskService.complete(t.getUuid(), null, COORD, WU));
		assertEquals(List.of("qa"), agentTaskService.missingRequiredRoles(
				agentTaskService.getTaskData(t.getUuid()).orElseThrow()));
		// qa rejects -> still blocked (most recent qa sign-off must be PASSED). The board already
		// queued qa when coder passed, so there is nothing for the coordinator to authorise.
		assertEquals("qa", agentTaskService.getTaskData(t.getUuid()).orElseThrow().getRole());
		agentTaskService.assign(t.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU);
		agentTaskService.signOff(t.getUuid(), r.sessB().getUuid(), SignOffOutcome.REJECTED, "broken", WU);
		assertThrows(RelizaException.class, () -> agentTaskService.complete(t.getUuid(), null, COORD, WU));
		// qa passes -> the board completes it (workerA: distinct from workerB's last sign-off).
		//
		// The rejection above carried no index, so the board could not route on it and handed the
		// task to the coordinator -- which is the point of that rule: a hop saying "no" without
		// saying why is an exception, not a route. So the coordinator authorises qa again here.
		assertEquals(TaskStatus.AWAITING_COORDINATOR,
				agentTaskService.getTaskData(t.getUuid()).orElseThrow().getStatus());
		agentTaskService.authorize(t.getUuid(), board(r), "qa", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		assertEquals(TaskStatus.COMPLETED, agentTaskService.signOff(
				t.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED, "fixed", WU).getStatus());
	}

	@Test
	public void v15_boardTarget_requiredAndOrgScoped() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Organization other = testInitializer.obtainOrganization();
		Component foreign = componentService.createComponent("foreign_" + UUID.randomUUID(), other.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		// a board must name the node it builds
		assertThrows(RelizaException.class, () -> agentBoardService.createBoard(org.getUuid(),
				"no-target-" + UUID.randomUUID(), null, List.of(SRC), null, null, null, null, null, WU));
		// and it must be a node of its own organization
		assertThrows(RelizaException.class, () -> agentBoardService.createBoard(org.getUuid(),
				"foreign-target-" + UUID.randomUUID(), null, List.of(SRC), null, null, null,
				foreign.getUuid(), null, WU));
	}

	@Test
	public void v15_taskLevel_defaultsFromBoardAndIsInheritedBySplitChildren() throws RelizaException {
		Rig r = rig();
		agentBoardService.updateBoard(r.board().getUuid(), null, null, null, null, null, null, null, 1, WU);
		// Levels need a ladder (task RD3-6).
		agentBoardService.updateSettingsFromInput(r.board().getUuid(), Map.of("ladder", Map.of("levels",
				List.of(Map.of("name", "requirements"), Map.of("name", "solution"), Map.of("name", "components"),
						Map.of("name", "modules")))), WU);
		AgentBoardData board = agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();

		AgentTaskData inherited = agentTaskService.register(board, SRC + "#800", "inherits the board level",
				null, null, null, null, null, null, WU);
		// RD2-1: the default is resolved on read, never written onto the task.
		assertNull(inherited.getWorkLevel(), "an undeclared level is not stamped with the board default");
		assertEquals(1, AgentTaskService.effectiveWorkLevel(inherited, board), "it reads the board default");

		AgentTaskData declared = agentTaskService.register(board, SRC + "#801", "declares its own",
				null, null, null, null, 2, null, WU);
		assertEquals(2, declared.getWorkLevel(), "a declared level wins over the board default");

		agentTaskService.authorize(declared.getUuid(), board, "coder", 10, null, null, null, null, COORD, WU);
		List<AgentTaskData> children = agentTaskService.split(declared.getUuid(), board,
				List.of(child("child keeps the level"),
						childAtLevel("child declares its own", 3)), COORD, WU);
		assertEquals(2, children.get(0).getWorkLevel(), "split children inherit the parent's level");
		assertEquals(3, children.get(1).getWorkLevel(), "unless the coordinator names another");
	}

	@Test
	public void v15_taskLinksWhatItProducesAndTheReleasesItMade() throws RelizaException {
		Rig r = rig();
		Component produced = componentService.createComponent("doc_" + UUID.randomUUID(), r.org().getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#810", "writes a document",
				null, null, null, produced.getUuid(), null, null, WU);
		assertEquals(produced.getUuid(), t.getProducesComponent());

		Branch branch = branchService.getBaseBranchOfComponent(produced.getUuid()).orElseThrow();
		ReleaseDto dto = ReleaseDto.builder().component(produced.getUuid()).org(r.org().getUuid())
				.branch(branch.getUuid()).version("1.0.0").status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED).build();
		UUID releaseUuid = ossReleaseService.createRelease(dto, WU).getUuid();

		AgentTaskData linked = agentTaskService.linkRelease(t.getUuid(), releaseUuid, WU);
		assertEquals(List.of(releaseUuid), linked.getReleases());
		// idempotent: the same release does not accumulate
		linked = agentTaskService.linkRelease(t.getUuid(), releaseUuid, WU);
		assertEquals(1, linked.getReleases().size());
	}

	// ---------- v16: what a task must be able to read before it starts ----------

	/**
	 * Hang a document component carrying the given specification off the board's target node, the
	 * way boards made them before the map; the board is made one from before too (the flag taken off
	 * its record, RD2-33), since only such a board reads a component it did not make.
	 */
	private Component documentOfNode(Rig r, RearmSpecificationType spec, String version,
			ReleaseLifecycle lifecycle) throws RelizaException {
		jdbcTemplate.update("UPDATE rearm.agent_boards SET record_data = record_data - 'adoptsLegacyDocuments' WHERE uuid = ?",
				r.board().getUuid());
		Component doc = componentService.createComponent("doc_" + spec + "_" + UUID.randomUUID(),
				r.org().getUuid(), ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		ComponentData docData = getComponentService.getComponentData(doc.getUuid()).orElseThrow();
		componentService.updateComponent(ComponentDto.builder().uuid(doc.getUuid()).name(docData.getName())
				.identifiers(List.of(new RearmIdentifier(RearmIdentifierType.SPECIFICATION, spec.name())))
				.build(), WU);
		Branch docBranch = branchService.getBaseBranchOfComponent(doc.getUuid()).orElseThrow();
		ossReleaseService.createRelease(ReleaseDto.builder().component(doc.getUuid())
				.org(r.org().getUuid()).branch(docBranch.getUuid()).version(version)
				.status(ReleaseStatus.ACTIVE).lifecycle(lifecycle).build(), WU);
		Branch nodeBranch = branchService.getBaseBranchOfComponent(r.targetNode().getUuid()).orElseThrow();
		BranchData nbd = BranchData.branchDataFromDbRecord(nodeBranch);
		List<ChildComponent> deps = new java.util.ArrayList<>(
				nbd.getDependencies() == null ? List.of() : nbd.getDependencies());
		deps.add(ChildComponent.builder().uuid(doc.getUuid()).status(StatusEnum.REQUIRED).build());
		branchService.updateBranch(BranchDto.builder().uuid(nodeBranch.getUuid())
				.dependencies(deps).build(), WU);
		return doc;
	}

	@Test
	public void v16_documentRequirement_withholdsWorkUntilTheDocumentIsMatureEnough() throws RelizaException {
		Rig r = rig();
		documentOfNode(r, RearmSpecificationType.TEST_PLAN, "0.1.0", ReleaseLifecycle.DRAFT);
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"coder", null, null, null, null, null, null, null, null, null,
				List.of(new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.TEST_PLAN,
						null, null, ReleaseLifecycle.ASSEMBLED, null))), true, WU);
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#820", "needs a test plan",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);

		// a draft test plan is not enough: the task is not offered and assignment is refused
		assertTrue(agentTaskService.next(List.of(board(r)), r.workerA().getUuid(), r.sessA().getUuid()).isEmpty(),
				"a task whose inputs are unmet is never offered");
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.assign(
				t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU));
		assertTrue(e.getMessage().contains("waiting on its inputs"), "the refusal says what it waits on");

		// publish an assembled revision and the same task becomes workable
		documentOfNode(r, RearmSpecificationType.TEST_PLAN, "1.0.0", ReleaseLifecycle.ASSEMBLED);
		var offered = agentTaskService.next(List.of(board(r)), r.workerA().getUuid(), r.sessA().getUuid());
		assertTrue(offered.isPresent(), "with the document assembled the task is offered");
		var wa = agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		assertEquals(1, wa.resolvedInputs().size(), "the assignment is bound to a version");
		assertEquals("1.0.0", wa.resolvedInputs().get(0).version(), "and it is the passing one");
	}

	@Test
	public void v16_taskMayDemandMoreThanItsRoleNeverLess() throws RelizaException {
		Rig r = rig();
		documentOfNode(r, RearmSpecificationType.REQUIREMENTS, "1.0.0", ReleaseLifecycle.ASSEMBLED);
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"coder", null, null, null, null, null, null, null, null, null,
				List.of(new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.REQUIREMENTS,
						null, null, ReleaseLifecycle.ASSEMBLED, null))), true, WU);
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#821", "architect wants more",
				null, null, null, null, null,
				// the architect says this particular change also waits on the requirements being baselined
				List.of(new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.REQUIREMENTS,
						null, null, ReleaseLifecycle.READY_TO_SHIP, null)), WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU),
				"the stricter of the two thresholds governs");

		// a weaker task requirement cannot loosen the role's
		AgentTaskData t2 = agentTaskService.register(board(r), SRC + "#822", "tries to weaken",
				null, null, null, null, null,
				List.of(new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.REQUIREMENTS,
						null, null, ReleaseLifecycle.DRAFT, null)), WU);
		agentTaskService.authorize(t2.getUuid(), board(r), "coder", 11, null, null, null, null, COORD, WU);
		var wa = agentTaskService.assign(t2.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		assertEquals(ReleaseLifecycle.ASSEMBLED, wa.resolvedInputs().get(0).lifecycle(),
				"the role's threshold still applied");
	}

	@Test
	public void v16_latestPassingIgnoresAFreshDraftWhileStrictLatestDoesNot() throws RelizaException {
		Rig r = rig();
		Component doc = documentOfNode(r, RearmSpecificationType.ARCHITECTURE, "1.0.0", ReleaseLifecycle.ASSEMBLED);
		// a new revision opens as a draft, superseding nothing that was already approved
		Branch docBranch = branchService.getBaseBranchOfComponent(doc.getUuid()).orElseThrow();
		ossReleaseService.createRelease(ReleaseDto.builder().component(doc.getUuid())
				.org(r.org().getUuid()).branch(docBranch.getUuid()).version("2.0.0")
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.DRAFT).build(), WU);

		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"coder", null, null, null, null, null, null, null, null, null,
				List.of(new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.ARCHITECTURE,
						null, null, ReleaseLifecycle.ASSEMBLED, InputResolution.LATEST_PASSING))), true, WU);
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#823", "works from the approved one",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		var wa = agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		assertEquals("1.0.0", wa.resolvedInputs().get(0).version(),
				"LATEST_PASSING binds the newest release that clears the threshold");

		// under STRICT_LATEST the draft at the head blocks the work instead
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"qa", null, null, null, null, null, null, null, null, null,
				List.of(new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.ARCHITECTURE,
						null, null, ReleaseLifecycle.ASSEMBLED, InputResolution.STRICT_LATEST))), true, WU);
		AgentTaskData t2 = agentTaskService.register(board(r), SRC + "#824", "refuses a superseded version",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(t2.getUuid(), board(r), "qa", 11, null, null, null, null, COORD, WU);
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				t2.getUuid(), board(r), r.workerB().getUuid(), r.sessB().getUuid(), WU));
	}

	@Test
	public void v16_qaWaitsForTheReleaseTheTaskItselfProduced() throws RelizaException {
		Rig r = rig();
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"qa", null, null, null, false, null, null, null, null, null,
				List.of(new RequiredInput(InputKind.RELEASE, null, InputScope.TASK, null,
						ReleaseLifecycle.ASSEMBLED, null))), true, WU);
		Component impl = componentService.createComponent("impl_" + UUID.randomUUID(), r.org().getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#830", "build then test",
				null, null, null, impl.getUuid(), null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "qa", 10, null, null, null, null, COORD, WU);

		// nothing built yet: QA has nothing to test
		assertThrows(RelizaException.class, () -> agentTaskService.assign(
				t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU));

		Branch implBranch = branchService.getBaseBranchOfComponent(impl.getUuid()).orElseThrow();
		UUID rel = ossReleaseService.createRelease(ReleaseDto.builder().component(impl.getUuid())
				.org(r.org().getUuid()).branch(implBranch.getUuid()).version("1.4.0")
				.status(ReleaseStatus.ACTIVE).lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU).getUuid();
		agentTaskService.linkRelease(t.getUuid(), rel, WU);

		var wa = agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		assertEquals(rel, wa.resolvedInputs().get(0).release(),
				"QA is bound to the very release this task produced");
	}

	@Test
	public void v17_boardSpec_isConfigurationWithPromptsAndWithoutTasks() throws RelizaException {
		Rig r = rig();
		documentOfNode(r, RearmSpecificationType.TEST_PLAN, "1.0.0", ReleaseLifecycle.ASSEMBLED);
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"coder", "you are the coder", null, null, null, null, null, null, null, null,
				List.of(new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.TEST_PLAN,
						null, null, ReleaseLifecycle.ASSEMBLED, InputResolution.STRICT_LATEST))), true, WU);
		// a task exists, and must not appear in the spec
		agentTaskService.register(board(r), SRC + "#840", "runtime work", null, null, null, null, null, null, WU);

		AgentBoardService.BoardSpecDto spec = agentBoardService.exportBoard(r.board().getUuid());
		assertEquals(DeclarativeConfigService.DeclarativeKind.BOARD, spec.getKind());
		assertEquals("demo-board", spec.getName());
		assertEquals("you are the coordinator", spec.getCoordinatorPrompt(), "prompts are configuration");
		ComponentData targetData = getComponentService.getComponentData(r.targetNode().getUuid()).orElseThrow();
		assertEquals(targetData.getName(), spec.getTarget(), "the target travels by name, not uuid");

		var coder = spec.getRoles().stream().filter(x -> "coder".equals(x.getName())).findFirst().orElseThrow();
		assertEquals("you are the coder", coder.getPrompt());
		assertEquals(1, coder.getRequiredInputs().size());
		assertEquals(RearmSpecificationType.TEST_PLAN, coder.getRequiredInputs().get(0).getSpecification());
		assertEquals(InputResolution.STRICT_LATEST, coder.getRequiredInputs().get(0).getResolution());
	}

	@Test
	public void v17_regressionIsVisibleOnAnAssignedTaskWithoutInterruptingIt() throws RelizaException {
		Rig r = rig();
		Component doc = documentOfNode(r, RearmSpecificationType.REQUIREMENTS, "1.0.0", ReleaseLifecycle.ASSEMBLED);
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"coder", null, null, null, null, null, null, null, null, null,
				List.of(new RequiredInput(InputKind.DOCUMENT, RearmSpecificationType.REQUIREMENTS,
						null, null, ReleaseLifecycle.ASSEMBLED, null))), true, WU);
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#841", "builds on the requirements",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		var wa = agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		assertTrue(agentTaskInputService.regressedBindings(wa.task()).isEmpty(), "nothing has moved yet");

		// the document the agent was bound to is rejected while the work is in flight
		UUID boundRelease = wa.task().getAssignment().resolvedInputs().get(0).release();
		ossReleaseService.updateReleaseLifecycle(boundRelease, ReleaseLifecycle.REJECTED, WU);

		AgentTaskData after = agentTaskService.getTaskData(t.getUuid()).orElseThrow();
		assertEquals(TaskStatus.ASSIGNED, after.getStatus(), "the agent is not interrupted");
		List<String> regressed = agentTaskInputService.regressedBindings(after);
		assertEquals(1, regressed.size(), "but the coordinator can see what moved");
		assertTrue(regressed.get(0).contains("REQUIREMENTS"), regressed.get(0));
	}

	@Test
	public void theTwoChecksOnASourceNowAgree() throws RelizaException {
		// A board wired in URL form -- which is what boards created before the grammar hold.
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "url-sourced-" + UUID.randomUUID(),
				"test board", List.of("https://github.com/acme/widget"), "coordinate", 2, null,
				target.getUuid(), null, WU);

		// Before the parser these disagreed: the documents check canonicalised and accepted, the
		// registration check compared raw text and refused.
		agentBoardService.setDocumentsConfig(board.getUuid(), "github:acme/widget", null, WU);
		// And in capitals, which is the pair D9 exists for: canonicalVcsUri preserves case while a
		// source renders its project lowercase, so comparing one against the other refused a
		// repository the board is demonstrably wired to.
		agentBoardService.setDocumentsConfig(board.getUuid(), "https://github.com/Acme/Widget", null, WU);
		agentBoardService.setDocumentsConfig(board.getUuid(), "git@github.com:acme/widget.git", null, WU);
		AgentTaskData t = agentTaskService.register(agentBoardService.getBoardData(board.getUuid()).orElseThrow(),
				"github:acme/widget#42", "same repository, other spelling",
				null, null, null, null, null, null, WU);
		assertEquals("github:acme/widget#42", t.getExternalRef());
	}

	@Test
	public void oneIssueIsOneTaskWhateverTheSpelling() throws RelizaException {
		Rig r = rig();
		AgentBoardData board = agentBoardService.updateBoard(r.board().getUuid(), null,
				List.of("github:acme/widget"), null, null, null, null, null, null, WU);
		AgentTaskData first = agentTaskService.register(board, "github:acme/widget#42",
				"first sighting", null, null, null, null, null, null, WU);
		// What a re-scan can produce for the same issue: different provider case, different owner
		// case, a zero-padded number. Each used to be a new natural key, so a new task.
		for (String spelling : List.of("github:acme/widget#42", "GitHub:acme/widget#42",
				"github:Acme/Widget#042")) {
			AgentTaskData again = agentTaskService.register(board, spelling, "re-scan",
					null, null, null, null, null, null, WU);
			assertEquals(first.getUuid(), again.getUuid(), spelling + " is the same issue");
		}
		assertEquals("github:acme/widget#42", first.getExternalRef(), "stored as the rendering");
	}

	@Test
	public void aSelfHostedSourceDoesNotMatchTheHostedOne() throws RelizaException {
		Rig r = rig();
		AgentBoardData board = agentBoardService.updateBoard(r.board().getUuid(), null,
				List.of("gitlab:git.acme.com/team/repo"), null, null, null, null, null, null, WU);
		assertEquals("gitlab:git.acme.com/team/repo#7", agentTaskService.register(board,
				"gitlab:git.acme.com/team/repo#7", "on the self-hosted instance",
				null, null, null, null, null, null, WU).getExternalRef());
		assertThrows(RelizaException.class, () -> agentTaskService.register(board,
				"gitlab:team/repo#7", "same path on gitlab.com is a different repository",
				null, null, null, null, null, null, WU));
	}

	@Test
	public void aReferenceThatDoesNotParseIsRefusedAtRegistration() throws RelizaException {
		Rig r = rig();
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.register(
				board(r), "https://github.com/acme/widget/issues/42", "a browser URL, not a reference",
				null, null, null, null, null, null, WU));
		assertTrue(e.getMessage().contains("provider:") || e.getMessage().contains("does not belong"),
				e.getMessage());
	}

	@Test
	public void everyTransitionSaysWhoMovedTheTask() throws RelizaException {
		Rig r = rig();
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"coder", null, null, null, null, null, null, null, null,
				AgentTaskRoleConfigData.HumanGate.ON_PASS), true, WU);
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#130", "who moved it",
				null, r.coordSession().getUuid(), null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED, "done", WU);
		AgentTaskData reviewed = agentTaskService.humanReview(t.getUuid(), true, "fine", REVIEWER, WU);

		Map<StatusTrigger, AgentActor> by = new LinkedHashMap<>();
		reviewed.getStatusHistory().forEach(sc -> by.put(sc.trigger(), sc.actor()));

		assertEquals(AgentActor.ActorKind.SESSION, by.get(StatusTrigger.REGISTER).kind());
		// The FIRST AUTHORIZE is the coordinator's; later ones are the board routing, which is
		// the distinction the actor exists to record. Reading the map here would take the last.
		AgentActor firstAuthorize = reviewed.getStatusHistory().stream()
				.filter(sc -> StatusTrigger.AUTHORIZE == sc.trigger())
				.findFirst().orElseThrow().actor();
		assertEquals(COORD, firstAuthorize, "the coordinator queued it");
		assertTrue(reviewed.getStatusHistory().stream()
				.filter(sc -> StatusTrigger.AUTHORIZE == sc.trigger())
				.anyMatch(sc -> AgentActor.ActorKind.SYSTEM == sc.actor().kind()),
				"and the board queued it again after the hop, as itself");
		assertEquals(r.sessA().getUuid(), by.get(StatusTrigger.ASSIGN).uuid(), "the worker took it");
		assertEquals(r.sessA().getUuid(), by.get(StatusTrigger.SIGNOFF) != null
				? by.get(StatusTrigger.SIGNOFF).uuid() : by.get(StatusTrigger.HUMAN_GATE).uuid(),
				"and the same session's sign-off parked it at the gate");

		// The whole point: a human verdict used to record null here, so the one transition a
		// PERSON caused was the one the history could not attribute.
		AgentActor approver = by.get(StatusTrigger.HUMAN_ACCEPT);
		assertNotNull(approver, "a human acceptance is attributed");
		assertEquals(AgentActor.ActorKind.USER, approver.kind());
		assertEquals(REVIEWER.name(), approver.name());
	}

	@Test
	public void aTransitionStoredAsABareSessionUuidStillReads() {
		// What StatusChange.actor held before it was typed: the session uuid, unadorned.
		UUID session = UUID.randomUUID();
		String legacy = """
				{"from":"QUEUED","to":"ASSIGNED","at":"2026-09-01T10:00:00Z",
				 "trigger":"ASSIGN","actor":"%s"}""".formatted(session);
		AgentTaskData.StatusChange sc = Utils.OM.readValue(legacy, AgentTaskData.StatusChange.class);
		assertEquals(StatusTrigger.ASSIGN, sc.trigger());
		assertEquals(AgentActor.ActorKind.SESSION, sc.actor().kind(),
				"a bare uuid there was always a session, and still reads as one");
		assertEquals(session, sc.actor().uuid());
	}

	@Test
	public void everyHopRecordsTheRoleRowItsNameResolvedTo() throws RelizaException {
		Rig r = rig();
		UUID coderRole = agentBoardService.getRoleConfig(r.board().getUuid(), "coder")
				.orElseThrow().getUuid();
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#120", "role identity", null, null, null, null, null, null, WU);

		AgentTaskData authorized = agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		assertEquals("coder", authorized.getRole());
		assertEquals(coderRole, authorized.getRoleUuid(),
				"the task points at the row, not only at the name it was asked for");

		var assigned = agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		assertEquals(coderRole, assigned.task().getAssignment().roleUuid(), "on the stored assignment");
		assertEquals(coderRole, assigned.roleUuid(),
				"and on what the worker is handed, which is a different record with its own field");

		AgentTaskData signed = agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.PASSED, "done", WU);
		var hop = signed.getSignOffs().get(signed.getSignOffs().size() - 1);
		assertEquals("coder", hop.role());
		assertEquals(coderRole, hop.roleUuid(),
				"a hop carries the row, so a reader reaches its prompt and gate without a name lookup");
	}

	@Test
	public void aReturnedHopCarriesTheRoleRowToo() throws RelizaException {
		Rig r = rig();
		UUID coderRole = agentBoardService.getRoleConfig(r.board().getUuid(), "coder")
				.orElseThrow().getUuid();
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#121", "handed back", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);

		AgentTaskData returned = agentTaskService.returnTask(t.getUuid(), r.sessA().getUuid(),
				TaskReturnReason.BLOCKED_ON_DEPENDENCY, "waiting on an answer", WU);
		var ret = returned.getReturns().get(returned.getReturns().size() - 1);
		assertEquals(coderRole, ret.roleUuid());
	}

	@Test
	public void aHumanGateVerdictResolvesTheGatedRole() throws RelizaException {
		Rig r = rig();
		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"coder", null, null, null, null, null, null, null, null,
				AgentTaskRoleConfigData.HumanGate.ON_PASS), true, WU);
		UUID coderRole = agentBoardService.getRoleConfig(r.board().getUuid(), "coder")
				.orElseThrow().getUuid();
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#122", "gated", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED, "done", WU);

		AgentTaskData reviewed = agentTaskService.humanReview(t.getUuid(), true, "looks right", REVIEWER, WU);
		var verdict = reviewed.getSignOffs().get(reviewed.getSignOffs().size() - 1);
		// The hold carries the gated role as a NAME; the verdict resolves it, so a human sign-off
		// joins to the same row as the agent hop it gates rather than only matching its text.
		assertEquals(coderRole, verdict.roleUuid());
		assertNull(verdict.agent(), "still a human verdict");
	}

	@Test
	public void aHopStoredBeforeThisFieldExistedStillReads() {
		// Exactly the JSON a pre-change sign-off holds: no roleUuid at all.
		String legacy = """
				{"role":"coder","agent":null,"session":null,"assignedAt":null,
				 "signedOffAt":"2026-09-01T10:00:00Z","outcome":"PASSED","note":"done",
				 "promptVersion":"v1","reviewedBy":null}""";
		AgentTaskData.SignOff so = Utils.OM.readValue(legacy, AgentTaskData.SignOff.class);
		assertEquals("coder", so.role());
		assertNull(so.roleUuid(), "absent, not a failure to read the row");
	}

	@Test
	public void theCompletionRuleMatchesTheRoleRowAndStillHonoursOlderHops() throws RelizaException {
		Rig r = rig();
		AgentTaskRoleConfigData qa = agentBoardService.upsertRoleConfig(board(r),
				new AgentBoardService.RoleConfigSpec("qa", null, null, null, null, null, null, null,
						AgentTaskRoleConfigData.RoleNecessity.REQUIRED, null), true, WU);
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#123", "needs qa", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "qa", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		agentTaskService.signOff(t.getUuid(), r.sessA().getUuid(), SignOffOutcome.PASSED, "checked", WU);

		AgentTaskData signed = agentTaskService.getTaskData(t.getUuid()).orElseThrow();
		assertEquals(qa.getUuid(), signed.lastSignOffForRole(qa.getUuid(), qa.getName()).roleUuid());
		assertTrue(agentTaskService.missingRequiredRoles(signed).isEmpty(),
				"the required role is satisfied, matched on its row");

		// A hop from before roleUuid existed carries only the name. Matching on the uuid alone
		// would read it as never signed off, so a task already mid-flight when this shipped could
		// never be completed -- the name has to remain reachable as the fallback.
		AgentTaskData legacy = agentTaskService.getTaskData(t.getUuid()).orElseThrow();
		var hop = legacy.getSignOffs().get(0);
		legacy.setSignOffs(new java.util.ArrayList<>(List.of(new AgentTaskData.SignOff(
				hop.role(), null, hop.agent(), hop.session(), hop.assignedAt(), hop.signedOffAt(),
				hop.outcome(), hop.note(), hop.promptVersion(), hop.reviewedBy()))));
		assertNotNull(legacy.lastSignOffForRole(qa.getUuid(), qa.getName()),
				"a hop with no row still answers for its name");
		assertTrue(agentTaskService.missingRequiredRoles(legacy).isEmpty(),
				"so an in-flight task does not become uncompletable");
	}

	/**
	 * RD3-16: complete names each rule it refuses on -- an open child by its key, a required role by name --
	 * and a refusal writes no history row and posts no event.
	 */
	@Test
	public void completeRefusesAnOpenChildAndAMissingRoleByNameAndWritesNothing() throws RelizaException {
		Rig r = rig();
		AgentTaskData parent = agentTaskService.register(board(r), SRC + "#1601", "epic", null, null, null, null, null, null, WU);
		List<AgentTaskData> children = agentTaskService.split(parent.getUuid(), board(r), List.of(child("part 1")), COORD, WU);
		AgentTaskData split = agentTaskService.getTaskData(parent.getUuid()).orElseThrow();
		int events = agentBoardService.recentEvents(board(r).getUuid()).size();
		RelizaException e = assertThrows(RelizaException.class,
				() -> agentTaskService.complete(parent.getUuid(), null, COORD, WU));
		assertEquals("Task " + split.label() + " has incomplete child task(s): " + children.get(0).label(), e.getMessage());
		assertEquals(split.getStatusHistory().size(),
				agentTaskService.getTaskData(parent.getUuid()).orElseThrow().getStatusHistory().size());
		assertEquals(events, agentBoardService.recentEvents(board(r).getUuid()).size());

		agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(
				"qa", null, null, null, null, null, null, null,
				AgentTaskRoleConfigData.RoleNecessity.REQUIRED, null), true, WU);
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#1602", "must be checked", null, null, null, null, null, null, WU);
		AgentTaskData pending = agentTaskService.getTaskData(t.getUuid()).orElseThrow();
		int rows = pending.getStatusHistory().size();
		events = agentBoardService.recentEvents(board(r).getUuid()).size();
		e = assertThrows(RelizaException.class, () -> agentTaskService.complete(t.getUuid(), null, COORD, WU));
		assertTrue(e.getMessage().startsWith("Task " + pending.label() + " cannot be completed: required role(s)"
				+ " without a passing sign-off: "), e.getMessage());
		assertTrue(e.getMessage().contains("qa"), e.getMessage());
		assertEquals(rows, agentTaskService.getTaskData(t.getUuid()).orElseThrow().getStatusHistory().size());
		assertEquals(events, agentBoardService.recentEvents(board(r).getUuid()).size());
	}

	/**
	 * RD3-16 run 1 T-1, architecture-2 §2: a task returned as too big waits on the coordinator, and splitting it
	 * there works; its history carries the SPLIT row, a recording trigger. From PENDING_INTAKE and QUEUED too.
	 */
	@Test
	public void aTaskReturnedToTheCoordinatorSplitsAndKeepsTheSplitInItsHistory() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#1603", "too big", null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		AgentTaskData returned = agentTaskService.returnTask(t.getUuid(), r.sessA().getUuid(),
				TaskReturnReason.TASK_UNCLEAR, "too big, split it", WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, returned.getStatus());
		List<AgentTaskData> children = agentTaskService.split(t.getUuid(), board(r),
				List.of(child("part 1"), child("part 2")), COORD, WU);
		assertEquals(2, children.size());
		AgentTaskData parent = agentTaskService.getTaskData(t.getUuid()).orElseThrow();
		assertEquals(TaskStatus.AWAITING_COORDINATOR, parent.getStatus());
		assertEquals(2, parent.getChildTasks().size());
		assertEquals(returned.getStatusHistory().size() + 1, parent.getStatusHistory().size(), "one row, for the split");
		AgentTaskData.StatusChange row = parent.getStatusHistory().get(parent.getStatusHistory().size() - 1);
		assertEquals(StatusTrigger.SPLIT, row.trigger());
		assertEquals(TaskStatus.AWAITING_COORDINATOR, row.from());
		assertEquals(TaskStatus.AWAITING_COORDINATOR, row.to());

		// From the queue and from intake, the split is a move and is recorded as one.
		AgentTaskData q = agentTaskService.register(board(r), SRC + "#1604", "queued", null, null, null, null, null, null, WU);
		agentTaskService.authorize(q.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.split(q.getUuid(), board(r), List.of(child("part a")), COORD, WU);
		AgentTaskData fromQueue = agentTaskService.getTaskData(q.getUuid()).orElseThrow();
		AgentTaskData.StatusChange qRow = fromQueue.getStatusHistory().get(fromQueue.getStatusHistory().size() - 1);
		assertEquals(List.of(StatusTrigger.SPLIT, TaskStatus.QUEUED, TaskStatus.AWAITING_COORDINATOR),
				List.of(qRow.trigger(), qRow.from(), qRow.to()));
		AgentTaskData in = agentTaskService.register(board(r), SRC + "#1606", "in intake", null, null, null, null, null, null, WU);
		agentTaskService.split(in.getUuid(), board(r), List.of(child("part b")), COORD, WU);
		AgentTaskData fromIntake = agentTaskService.getTaskData(in.getUuid()).orElseThrow();
		AgentTaskData.StatusChange iRow = fromIntake.getStatusHistory().get(fromIntake.getStatusHistory().size() - 1);
		assertEquals(List.of(StatusTrigger.SPLIT, TaskStatus.PENDING_INTAKE, TaskStatus.AWAITING_COORDINATOR),
				List.of(iRow.trigger(), iRow.from(), iRow.to()));
	}

	/** RD3-16: a second cancel is refused by name, not recorded again as a row that changes nothing. */
	@Test
	public void aSecondCancelIsRefused() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#1605", "not needed", null, null, null, null, null, null, WU);
		AgentTaskData cancelled = agentTaskService.cancel(t.getUuid(), "not needed", COORD, WU);
		assertEquals(TaskStatus.CANCELLED, cancelled.getStatus());
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.cancel(t.getUuid(), "again", COORD, WU));
		assertEquals("Task " + cancelled.label() + " is already cancelled", e.getMessage());
		assertEquals(cancelled.getStatusHistory().size(),
				agentTaskService.getTaskData(t.getUuid()).orElseThrow().getStatusHistory().size());
	}
}
