/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.AgentTaskRoleConfigData.ModelStrength;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ModelOntologyData;
import io.reliza.model.ModelOntologyData.RoleCategory;
import io.reliza.model.ModelOntologyData.RoleStrength;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService.StrengthSpec;
import io.reliza.service.ModelOntologyService.StrengthUpdate;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Model strength as decimals, per kind of work, per board role. The model in these tests is rated
 * 3 at base and 5 for ARCHITECT, so every assertion distinguishes "read the category" from "read
 * the base".
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentModelStrengthIntegrationTest {

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private ModelOntologyService modelOntologyService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SRC = "github:relizaio/strength-demo";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final java.util.concurrent.atomic.AtomicInteger ISSUE = new java.util.concurrent.atomic.AtomicInteger();

	private record Rig(Organization org, AgentBoardData board, ModelOntologyData model, AgentData worker,
			AgentSessionData session) {}

	private ModelOntologyData model(Organization org, Double base, List<RoleStrength> byRole) throws RelizaException {
		ModelOntologyData m = modelOntologyService.resolve(org.getUuid(), "strength-test-" + UUID.randomUUID(),
				"1", WU).model();
		return modelOntologyService.updateModelOntology(m.getUuid(), null, null, null, null,
				new StrengthUpdate(base, null == base, byRole), WU);
	}

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component node = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "strength-board", "test board",
				List.of(SRC), "you are the coordinator", 5, null, node.getUuid(), null, WU);
		ModelOntologyData model = model(org, 3.0, List.of(new RoleStrength(RoleCategory.ARCHITECT, 5.0)));
		AgentData worker = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"worker-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData session = agentSessionService.initialize(org.getUuid(), worker.getUuid(), null,
				"w-" + UUID.randomUUID(), "worker", null, model.getUuid(), WU);
		return new Rig(org, board, model, worker, session);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskRoleConfigData role(Rig r, String name, StrengthSpec strength) throws RelizaException {
		return agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec(name,
				"you are " + name, 10, null, false, true, null, null, null, null, null, null, strength), true, WU);
	}

	private static StrengthSpec floor(double required, Double headroom, RoleCategory category,
			List<ModelStrength> overrides) {
		return new StrengthSpec(Optional.of(required), headroom, Optional.ofNullable(category), overrides);
	}

	/** Category and overrides only, no floor: for reading strength rather than gating on it. */
	private static StrengthSpec mapping(RoleCategory category, List<ModelStrength> overrides) {
		return new StrengthSpec(null, null, Optional.ofNullable(category), overrides);
	}

	private AgentTaskData queued(Rig r, String role, int order) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "task", null,
				null, null, null, null, null, WU);
		return agentTaskService.authorize(t.getUuid(), board(r), role, order, null, null, null, null, COORD, WU);
	}

	private AgentTaskData pending(Rig r) throws RelizaException {
		return agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "task", null,
				null, null, null, null, null, WU);
	}

	private UUID offered(Rig r) {
		return agentTaskService.next(List.of(board(r)), r.worker().getUuid(), r.session().getUuid())
				.map(wa -> wa.task().getUuid()).orElse(null);
	}

	// ---------- resolution ----------

	@Test
	public void aRoleReadsItsOverrideThenItsCategoryThenTheBase() throws RelizaException {
		Rig r = rig();
		AgentTaskRoleConfigData plain = role(r, "plain", null);
		AgentTaskRoleConfigData planner = role(r, "planner", mapping(RoleCategory.ARCHITECT, null));
		AgentTaskRoleConfigData pinned = role(r, "pinned", mapping(RoleCategory.ARCHITECT,
				List.of(new ModelStrength(r.model().getUuid(), 4.25))));
		assertEquals(3.0, modelOntologyService.effectiveStrength(r.session(), plain), "no category: the base");
		assertEquals(5.0, modelOntologyService.effectiveStrength(r.session(), planner),
				"a board PLANNER mapped to ARCHITECT reads the model's ARCHITECT strength");
		assertEquals(4.25, modelOntologyService.effectiveStrength(r.session(), pinned), "the override wins");
		assertEquals(3.0, modelOntologyService.effectiveStrength(r.session(), null), "no role: the base");
	}

	@Test
	public void theQueueJudgesTheModelPerRole() throws RelizaException {
		Rig r = rig();
		// Both require 5. Only the one reading ARCHITECT sees a model rated 5 for it.
		role(r, "coder", floor(5, null, null, null));
		role(r, "planner", floor(5, null, RoleCategory.ARCHITECT, null));
		AgentTaskData coderTask = queued(r, "coder", 1);
		AgentTaskData plannerTask = queued(r, "planner", 2);
		assertEquals(plannerTask.getUuid(), offered(r));
		assertThrows(RelizaException.class, () -> agentTaskService.assign(coderTask.getUuid(), board(r),
				r.worker().getUuid(), r.session().getUuid(), null, WU),
				"assign enforces the floor too, not only the poll");
		agentTaskService.assign(plannerTask.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), null, WU);
	}

	@Test
	public void theCheapestSufficientMatchIsPreferred() throws RelizaException {
		Rig r = rig();
		// The model rates 3 for both. The 3-task fits exactly; the 2.5-task needs half a point
		// less than the model has, admitted by headroom. The coordinator ranked them equal, so fit
		// decides, and the exact fit comes first although the looser one was registered earlier.
		role(r, "exact", floor(3, 1.0, null, null));
		role(r, "looser", floor(2.5, 1.0, null, null));
		AgentTaskData looser = queued(r, "looser", 5);
		AgentTaskData exact = queued(r, "exact", 5);
		assertEquals(exact.getUuid(), offered(r));
		agentTaskService.assign(exact.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), null, WU);
		assertEquals(looser.getUuid(), offered(r), "and the looser fit is still eligible");
	}

	@Test
	public void theCoordinatorsOrderOutranksStrengthFit() throws RelizaException {
		Rig r = rig();
		// The review's board: coder has a floor of 3 and headroom 2, qa has no floor, and the
		// polling model rates 5 for coder. The refactor is a fit at distance 2; the hotfix has no
		// requirement at all, which ranks after every fit. The coordinator put the hotfix first.
		role(r, "coder", floor(3, 2.0, null, List.of(new ModelStrength(r.model().getUuid(), 5.0))));
		role(r, "qa", null);
		AgentTaskData refactor = queued(r, "coder", 90);
		AgentTaskData hotfix = queued(r, "qa", 1);
		assertEquals(hotfix.getUuid(), offered(r), "the coordinator's order decides, not how the model fits");

		agentBoardService.updateBoard(r.board().getUuid(), null, null, null, null,
				AgentBoardData.PriorityType.STRICT, null, null, null, WU);
		assertThrows(RelizaException.class, () -> agentTaskService.assign(refactor.getUuid(), board(r),
				r.worker().getUuid(), r.session().getUuid(), null, WU));
		agentTaskService.assign(hotfix.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), null, WU);
	}

	@Test
	public void theCoordinatorSetsATasksStrengthWhenItAuthorizes() throws RelizaException {
		Rig r = rig();
		role(r, "coder", null);
		AgentTaskData t = pending(r);
		t = agentTaskService.authorize(t.getUuid(), board(r), "coder", 1, null, null, null, null, true, 4.5, COORD, WU);
		assertEquals(4.5, t.getRequiredStrength());
		assertEquals(COORD.uuid(), t.getStrengthSetBy().uuid());
		assertNull(offered(r), "the model rates 3, the task now needs 4.5");
	}

	@Test
	public void authorizingWithoutAStrengthLeavesTheTaskAsItWas() throws RelizaException {
		Rig r = rig();
		role(r, "coder", null);
		AgentTaskData t = pending(r);
		t = agentTaskService.authorize(t.getUuid(), board(r), "coder", 1, null, null, null, null, false, null, COORD, WU);
		assertNull(t.getRequiredStrength());
		assertNull(t.getStrengthSetBy(), "nothing was set, so nobody set it");
	}

	@Test
	public void aBadStrengthIsRefusedBeforeTheTaskIsAuthorized() throws RelizaException {
		Rig r = rig();
		role(r, "coder", null);
		AgentTaskData t = pending(r);
		// Refused before anything is written: the task is not left authorized without the
		// requirement the coordinator asked for.
		assertThrows(RelizaException.class, () -> agentTaskService.authorize(t.getUuid(), board(r), "coder", 1,
				null, null, null, null, true, 4.125, COORD, WU));
		assertEquals(io.reliza.model.AgentTaskData.TaskStatus.PENDING_INTAKE,
				agentTaskService.getTaskData(t.getUuid()).orElseThrow().getStatus());
	}

	@Test
	public void theCoordinatorCannotLowerARolesFloor() throws RelizaException {
		Rig r = rig();
		// The review's case: a role asking for 4 with headroom 1, lowered to 2, would admit 2 to 3
		// and shut out the strong model the operator wanted.
		role(r, "coder", floor(4, 1.0, null, null));
		AgentTaskData t = pending(r);
		assertThrows(RelizaException.class, () -> agentTaskService.authorize(t.getUuid(), board(r), "coder", 1,
				null, null, null, null, true, 2.0, COORD, WU));
		assertEquals(io.reliza.model.AgentTaskData.TaskStatus.PENDING_INTAKE,
				agentTaskService.getTaskData(t.getUuid()).orElseThrow().getStatus(), "refused before authorizing");
		AgentTaskData raised = agentTaskService.authorize(t.getUuid(), board(r), "coder", 1, null, null, null, null,
				true, 4.0, COORD, WU);
		assertEquals(4.0, raised.getRequiredStrength(), "the floor itself is not lower");
	}

	@Test
	public void theCoordinatorCannotLowerWhatAPersonSet() throws RelizaException {
		Rig r = rig();
		role(r, "coder", floor(4, null, null, null));
		AgentTaskData t = pending(r);
		AgentActor operator = AgentActor.ofUser(UUID.randomUUID(), "op@example.com");
		agentTaskService.setRequiredStrength(t.getUuid(), 5.0, operator, WU);
		// 4.5 is above the role's floor but below what the operator set on this task.
		assertThrows(RelizaException.class, () -> agentTaskService.authorize(t.getUuid(), board(r), "coder", 1,
				null, null, null, null, true, 4.5, COORD, WU));
		AgentTaskData same = agentTaskService.authorize(t.getUuid(), board(r), "coder", 1, null, null, null, null,
				true, 5.0, COORD, WU);
		assertEquals(5.0, same.getRequiredStrength());
	}

	@Test
	public void theCoordinatorCannotClearARequirement() throws RelizaException {
		Rig r = rig();
		role(r, "coder", null);
		AgentTaskData t = pending(r);
		agentTaskService.setRequiredStrength(t.getUuid(), 4.0, AgentActor.ofUser(UUID.randomUUID(), "op@example.com"), WU);
		assertThrows(RelizaException.class, () -> agentTaskService.authorize(t.getUuid(), board(r), "coder", 1,
				null, null, null, null, true, null, COORD, WU));
		assertEquals(4.0, agentTaskService.getTaskData(t.getUuid()).orElseThrow().getRequiredStrength());
	}

	@Test
	public void aTaskRequirementWithinTheToleranceCounts() throws RelizaException {
		Rig r = rig();
		role(r, "coder", null);
		AgentTaskData t = queued(r, "coder", 1);
		agentTaskService.setRequiredStrength(t.getUuid(), 3.0, COORD, WU);
		assertEquals(t.getUuid(), offered(r));
		agentTaskService.setRequiredStrength(t.getUuid(), 3.01, COORD, WU);
		assertNull(offered(r), "one input step above what the model has");
		assertThrows(RelizaException.class, () -> agentTaskService.setRequiredStrength(t.getUuid(), 3.001, COORD, WU),
				"three decimals are refused on write");
	}

	// ---------- validation ----------

	@Test
	public void roleOverridesMustNameThisOrgsModelsOnceEach() throws RelizaException {
		Rig r = rig();
		ModelOntologyData elsewhere = model(testInitializer.obtainOrganization(), 4.0, List.of());
		assertThrows(RelizaException.class, () -> role(r, "coder", floor(3, null, null,
				List.of(new ModelStrength(elsewhere.getUuid(), 4.0)))));
		assertThrows(RelizaException.class, () -> role(r, "coder", floor(3, null, null,
				List.of(new ModelStrength(r.model().getUuid(), 4.0), new ModelStrength(r.model().getUuid(), 3.0)))));
		assertThrows(RelizaException.class, () -> role(r, "coder", floor(3.125, null, null, null)));
	}

	@Test
	public void theCoordinatorCannotChangeARolesStrength() throws RelizaException {
		Rig r = rig();
		role(r, "coder", null);
		assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(board(r),
				new AgentBoardService.RoleConfigSpec("coder", null, null, null, null, null, null, null, null, null,
						null, null, floor(1, null, null, null)), false, WU));
	}

	@Test
	public void aFloorIsRemovedBySendingItAsEmpty() throws RelizaException {
		Rig r = rig();
		role(r, "coder", floor(9, null, RoleCategory.CODER, null));
		AgentTaskRoleConfigData cleared = role(r, "coder",
				new StrengthSpec(Optional.empty(), null, Optional.empty(), null));
		assertNull(cleared.getRequiredStrength());
		assertNull(cleared.getStrengthCategory());
	}

	@Test
	public void modelStrengthsAreSetClearedAndValidated() throws RelizaException {
		Rig r = rig();
		ModelOntologyData m = modelOntologyService.updateModelOntology(r.model().getUuid(), null, null, null, null,
				new StrengthUpdate(4.5, false, List.of(new RoleStrength(RoleCategory.QA, 2.0))), WU);
		assertEquals(4.5, m.getStrength());
		assertEquals(2.0, m.strengthFor(RoleCategory.QA));
		assertEquals(4.5, m.strengthFor(RoleCategory.ARCHITECT), "the list was replaced, ARCHITECT falls to base");
		m = modelOntologyService.updateModelOntology(r.model().getUuid(), null, null, null, null,
				new StrengthUpdate(null, true, null), WU);
		assertNull(m.getStrength(), "unrated");
		assertThrows(RelizaException.class, () -> modelOntologyService.updateModelOntology(r.model().getUuid(),
				null, null, null, null, new StrengthUpdate(null, false, List.of(new RoleStrength(RoleCategory.QA, 2.0),
						new RoleStrength(RoleCategory.QA, 3.0))), WU));
	}

	// ---------- presets and merges ----------

	@Test
	public void aBoardSeededFromPresetsCarriesTheirStrength() throws RelizaException {
		Rig r = rig();
		agentBoardService.upsertPreset(r.org().getUuid(), new AgentBoardService.RoleConfigSpec("planner",
				"plan it", 10, null, false, true, null, null, null, null, null, null,
				floor(4.5, 0.5, RoleCategory.ARCHITECT, List.of(new ModelStrength(r.model().getUuid(), 4.75)))), WU);
		AgentBoardData seeded = agentBoardService.seedFromPresets(board(r), WU);
		AgentTaskRoleConfigData planner = agentBoardService.getRoleConfig(seeded.getUuid(), "planner").orElseThrow();
		assertEquals(4.5, planner.getRequiredStrength());
		assertEquals(0.5, planner.getStrengthHeadroom());
		assertEquals(RoleCategory.ARCHITECT, planner.getStrengthCategory());
		assertEquals(4.75, planner.modelStrengthOverride(r.model().getUuid()));
	}

	@Test
	public void mergingAModelRepointsRoleAndPresetOverridesAndTheSurvivorsOwnWins() throws RelizaException {
		Rig r = rig();
		ModelOntologyData folded = model(r.org(), 2.0, List.of());
		ModelOntologyData survivor = r.model();
		role(r, "moves", mapping(null, List.of(new ModelStrength(folded.getUuid(), 4.0))));
		role(r, "keeps", mapping(null, List.of(new ModelStrength(folded.getUuid(), 4.0),
				new ModelStrength(survivor.getUuid(), 1.5))));
		agentBoardService.upsertPreset(r.org().getUuid(), new AgentBoardService.RoleConfigSpec("preset-moves",
				"p", 10, null, false, true, null, null, null, null, null, null,
				mapping(null, List.of(new ModelStrength(folded.getUuid(), 3.5)))), WU);

		modelOntologyService.mergeModelOntology(folded.getUuid(), survivor.getUuid(), WU);

		AgentTaskRoleConfigData moves = agentBoardService.getRoleConfig(r.board().getUuid(), "moves").orElseThrow();
		assertEquals(4.0, moves.modelStrengthOverride(survivor.getUuid()));
		assertNull(moves.modelStrengthOverride(folded.getUuid()));
		AgentTaskRoleConfigData keeps = agentBoardService.getRoleConfig(r.board().getUuid(), "keeps").orElseThrow();
		assertEquals(1.5, keeps.modelStrengthOverride(survivor.getUuid()), "set against the survivor, so it stands");
		assertEquals(1, keeps.getModelStrengths().size(), "the folded model's override is dropped, not duplicated");
		AgentTaskRoleConfigData preset = agentBoardService.listPresets(r.org().getUuid()).stream()
				.filter(p -> "preset-moves".equals(p.getName())).findFirst().orElseThrow();
		assertEquals(3.5, preset.modelStrengthOverride(survivor.getUuid()));
	}

	@Test
	public void wholeNumbersStoredBeforeThisReadAsDecimals() {
		ModelOntologyData m = Utils.OM.convertValue(Map.of("strength", 4), ModelOntologyData.class);
		assertEquals(4.0, m.getStrength());
		AgentTaskRoleConfigData rc = Utils.OM.convertValue(Map.of("requiredStrength", 3, "strengthHeadroom", 1),
				AgentTaskRoleConfigData.class);
		assertEquals(3.0, rc.getRequiredStrength());
		assertEquals(1.0, rc.getStrengthHeadroom());
		assertTrue(rc.getModelStrengths().isEmpty());
	}
}
