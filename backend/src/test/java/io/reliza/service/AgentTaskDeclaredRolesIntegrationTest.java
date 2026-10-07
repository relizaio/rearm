/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.ComponentData.ComponentType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.Component;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentTaskService.WorkerAssignment;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * A worker declaring the roles it can take when it asks for work. The architect task is always
 * the lower-priority one here, so a result that ignored the declaration would hand out the coder
 * task and fail the assertion rather than pass by luck.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentTaskDeclaredRolesIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:relizaio/roles-demo";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());

	/**
	 * A board with coder and architect roles where the architect task loses on every ordering the
	 * poll applies: its role is later in the pipeline (50 against 10) and its task order is later
	 * (50 against 1).
	 */
	private record Rig(AgentBoardData board, AgentData worker, AgentSessionData session,
			AgentTaskData coderTask, AgentTaskData architectTask) {
		AgentBoardData fresh(AgentBoardService s) { return s.getBoardData(board.getUuid()).orElseThrow(); }
	}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component node = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "roles-board", "test board",
				List.of(SRC), "you are the coordinator", 5, null, node.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics(
				"coder", "you are the coder", 10, null, false, true, null), true, WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics(
				"architect", "you are the architect", 50, null, false, true, null), true, WU);
		AgentData worker = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"worker-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData session = agentSessionService.initialize(org.getUuid(), worker.getUuid(), null,
				"w-" + UUID.randomUUID(), "worker", null, null, WU);
		AgentBoardData b = agentBoardService.getBoardData(board.getUuid()).orElseThrow();
		AgentTaskData coder = agentTaskService.register(b, SRC + "#1", "code it", null, null, null, null, null, null, WU);
		AgentTaskData arch = agentTaskService.register(b, SRC + "#2", "design it", null, null, null, null, null, null, WU);
		agentTaskService.authorize(coder.getUuid(), b, "coder", 1, null, null, null, null, COORD, WU);
		agentTaskService.authorize(arch.getUuid(), b, "architect", 50, null, null, null, null, COORD, WU);
		return new Rig(board, worker, session, coder, arch);
	}

	private UUID offered(Rig r, List<String> roles) {
		return agentTaskService.next(List.of(r.fresh(agentBoardService)), r.worker().getUuid(),
				r.session().getUuid(), roles).map(wa -> wa.task().getUuid()).orElse(null);
	}

	@Test
	public void noDeclarationKeepsTheUsualPoll() throws RelizaException {
		Rig r = rig();
		assertEquals(r.coderTask().getUuid(), offered(r, null));
		assertEquals(r.coderTask().getUuid(), offered(r, List.of()));
	}

	@Test
	public void anArchitectOnlyAgentIsOfferedTheArchitectTaskOverAHigherPriorityCoderTask() throws RelizaException {
		Rig r = rig();
		assertEquals(r.architectTask().getUuid(), offered(r, List.of("architect")));
	}

	@Test
	public void severalRolesKeepPriorityAmongThem() throws RelizaException {
		Rig r = rig();
		assertEquals(r.coderTask().getUuid(), offered(r, List.of("architect", "coder")));
	}

	@Test
	public void aRoleMayBeNamedByUuidOrInAnyCase() throws RelizaException {
		Rig r = rig();
		UUID architectUuid = agentBoardService.getRoleConfig(r.board().getUuid(), "architect").orElseThrow().getUuid();
		assertEquals(r.architectTask().getUuid(), offered(r, List.of(architectUuid.toString())));
		assertEquals(r.architectTask().getUuid(), offered(r, List.of("  Architect ")));
	}

	@Test
	public void rolesThatMatchNothingOfferNothing() throws RelizaException {
		Rig r = rig();
		assertEquals(null, offered(r, List.of("archtect")));
		assertEquals(null, offered(r, List.of(UUID.randomUUID().toString())));
		// declared, but only blanks: still a declaration, and it admits nothing
		assertEquals(null, offered(r, List.of(" ")));
	}

	@Test
	public void aMatchingRoleWithNothingOpenOffersNothing() throws RelizaException {
		Rig r = rig();
		agentBoardService.upsertRoleConfig(r.fresh(agentBoardService), AgentBoardService.RoleConfigSpec.ofBasics(
				"qa", "you are qa", 20, null, false, true, null), true, WU);
		assertEquals(null, offered(r, List.of("qa")));
	}

	@Test
	public void assignRefusesATaskOutsideTheDeclaredRoles() throws RelizaException {
		Rig r = rig();
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.assign(
				r.coderTask().getUuid(), r.fresh(agentBoardService), r.worker().getUuid(), r.session().getUuid(),
				List.of("architect"), WU));
		assertTrue(e.getMessage().contains("coder"), e.getMessage());
	}

	@Test
	public void onAStrictBoardPriorityIsEnforcedAmongTheDeclaredRolesOnly() throws RelizaException {
		Rig r = rig();
		agentBoardService.updateBoard(r.board().getUuid(), null, null, null, null,
				AgentBoardData.PriorityType.STRICT, null, null, null, WU);
		// Undeclared, the coder task is ahead and the architect task is refused -- as before.
		assertThrows(RelizaException.class, () -> agentTaskService.assign(r.architectTask().getUuid(),
				r.fresh(agentBoardService), r.worker().getUuid(), r.session().getUuid(), null, WU));
		// Declared architect-only, nothing the agent could be offered is ahead of it.
		WorkerAssignment wa = agentTaskService.assign(r.architectTask().getUuid(), r.fresh(agentBoardService),
				r.worker().getUuid(), r.session().getUuid(), List.of("architect"), WU);
		assertEquals("architect", wa.role());
		assertEquals(TaskStatus.ASSIGNED,
				agentTaskService.getTaskData(r.architectTask().getUuid()).orElseThrow().getStatus());
	}
}
