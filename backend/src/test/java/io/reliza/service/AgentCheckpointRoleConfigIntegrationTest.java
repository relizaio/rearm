/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
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
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.service.DeclarativeConfigService.Action;
import io.reliza.service.DeclarativeConfigService.ApplyResult;
import io.reliza.service.DeclarativeConfigService.Change;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Who may set {@code coordinatorCheckpoint} and where (task SPLIT-5, D1, D11, section 3.5; T-13): the seat's upsert refuses
 * it, a board allows one active flagged role, a declarative apply changes which role carries it only from a person,
 * presets never carry it; and the routing-rules bullet a flagged board serves (T-15, F-1). Specs go in as the
 * argument maps the endpoints receive, as {@code AgentBoardDeclarativeIntegrationTest}'s do.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
@SuppressWarnings("unchecked")
public class AgentCheckpointRoleConfigIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "operator");
	private static final AgentActor SYSTEM = AgentActor.system("declarative apply");
	private static final String OPERATOR_ONLY = "operator-only";
	private static final String CHECKPOINT = "coordinator checkpoint";

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private ComponentService componentService;
	@Autowired private AgentTaskService agentTaskService;

	private record Org(UUID uuid, String target) {}

	private Org org() throws RelizaException {
		Organization o = testInitializer.obtainOrganization();
		String target = "cp-node-" + UUID.randomUUID();
		componentService.createComponent(target, o.getUuid(), ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		return new Org(o.getUuid(), target);
	}

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	private static Map<String, Object> board(String name, Object... more) {
		Map<String, Object> m = map("kind", "BOARD", "version", 1, "name", name);
		m.putAll(map(more));
		return m;
	}

	private static Map<String, Object> architect() {
		return map("name", "architect", "prompt", "design it", "orderIndex", 10,
				"producesOutputs", List.of(map("specification", "ARCHITECTURE", "scope", "TASK", "required", false)));
	}

	private static Map<String, Object> coder(Object... more) {
		Map<String, Object> m = map("name", "coder", "prompt", "build it", "orderIndex", 40,
				"requiredCapabilities", List.of("CODE_PUSH"));
		m.putAll(map(more));
		return m;
	}

	private static Map<String, Object> builder() {
		return map("name", "builder", "prompt", "build it too", "orderIndex", 50, "requiredCapabilities", List.of("CODE_PUSH"));
	}

	private ApplyResult apply(Org o, Map<String, Object> spec, AgentActor actor) throws RelizaException {
		return agentBoardService.applyBoard(o.uuid(), AgentBoardService.boardSpecFromInput(spec), false, null, actor, WU);
	}

	private AgentBoardData boardNamed(Org o, String name) {
		return agentBoardService.listByOrg(o.uuid()).stream().filter(b -> name.equals(b.getName())).findFirst()
				.orElseThrow();
	}

	private Optional<AgentTaskRoleConfigData> role(Org o, String board, String name) {
		return agentBoardService.getRoleConfig(boardNamed(o, board).getUuid(), name);
	}

	private static List<Change> errors(ApplyResult r) {
		return r.getChanges().stream().filter(c -> Action.ERROR == c.getAction()).toList();
	}

	/** A board the person applied: architect and coder, the coder flagged, wipLimit unset. */
	private String flaggedBoard(Org o) throws RelizaException {
		String name = "cp-" + UUID.randomUUID();
		ApplyResult r = apply(o, board(name, "description", "flagged", "target", o.target(),
				"roles", List.of(architect(), coder("coordinatorCheckpoint", true))), PERSON);
		assertEquals(0, r.getErrors(), r.getChanges().toString());
		assertTrue(role(o, name, "coder").orElseThrow().isCoordinatorCheckpoint());
		return name;
	}

	/** The error the apply lists for {@code role}, which must name the checkpoint as operator-only. */
	private static Change refusalOn(ApplyResult r, String role) {
		assertTrue(r.getErrors() > 0, r.getChanges().toString());
		Change c = errors(r).stream().filter(e -> role.equals(e.getName())).findFirst().orElseThrow(
				() -> new AssertionError("no error on " + role + ": " + r.getChanges()));
		assertTrue(c.getMessage().contains(CHECKPOINT) && c.getMessage().contains(OPERATOR_ONLY), c.getMessage());
		return c;
	}

	// ---------- the upsert (T-13) ----------

	@Test
	public void theUpsertAllowsOneActiveFlaggedRoleOnTheResultingSet() throws RelizaException {
		Org o = org();
		String name = flaggedBoard(o);
		AgentBoardData b = boardNamed(o, name);
		RelizaException second = assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(b,
				flag("architect", true), true, WU));
		assertEquals("Board " + name + " already has a coordinator checkpoint before coder; clear it first",
				second.getMessage());
		assertFalse(role(o, name, "architect").orElseThrow().isCoordinatorCheckpoint());

		// null leaves it; clearing then setting on another role moves it
		agentBoardService.upsertRoleConfig(b, AgentBoardService.RoleConfigSpec.ofBasics("coder", null, null, 3, null, null,
				null), true, WU);
		assertTrue(role(o, name, "coder").orElseThrow().isCoordinatorCheckpoint(), "null in the input leaves it");
		agentBoardService.upsertRoleConfig(b, flag("coder", false), true, WU);
		agentBoardService.upsertRoleConfig(b, flag("architect", true), true, WU);
		assertTrue(role(o, name, "architect").orElseThrow().isCoordinatorCheckpoint());
		assertFalse(role(o, name, "coder").orElseThrow().isCoordinatorCheckpoint());
	}

	@Test
	public void theSeatsUpsertCannotChangeTheFlag() throws RelizaException {
		Org o = org();
		String name = flaggedBoard(o);
		AgentBoardData b = boardNamed(o, name);
		for (boolean value : new boolean[] {true, false}) {
			RelizaException e = assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(b,
					flag("coder", value), false, WU));
			assertTrue(e.getMessage().contains(CHECKPOINT) && e.getMessage().contains(OPERATOR_ONLY), e.getMessage());
			assertEquals(AgentBoardService.ROUTING_GRAPH_OPERATOR_ONLY, e.getMessage());
		}
		assertTrue(role(o, name, "coder").orElseThrow().isCoordinatorCheckpoint(), "unchanged");
		agentBoardService.upsertRoleConfig(b, flag("coder", false), true, WU);
		assertFalse(role(o, name, "coder").orElseThrow().isCoordinatorCheckpoint(), "the operator's upsert sets it");
	}

	private static AgentBoardService.RoleConfigSpec flag(String role, boolean value) {
		return new AgentBoardService.RoleConfigSpec(role, null, null, null, null, null, null, null, null, null, null,
				null, null, null, null, null, value);
	}

	// ---------- the apply (T-13 a-c, e-g) ----------

	@Test
	public void aFileSetsTheFlagAndItsExportRoundTrips() throws RelizaException {
		Org o = org();
		String name = flaggedBoard(o);
		Map<String, Object> exported = Utils.OM.convertValue(agentBoardService.exportBoard(boardNamed(o, name).getUuid()),
				Map.class);
		List<Map<String, Object>> roles = (List<Map<String, Object>>) exported.get("roles");
		Map<String, Object> coder = roles.stream().filter(m -> "coder".equals(m.get("name"))).findFirst().orElseThrow();
		assertEquals(true, coder.get("coordinatorCheckpoint"));
		assertEquals(false, roles.stream().filter(m -> "architect".equals(m.get("name"))).findFirst().orElseThrow()
				.get("coordinatorCheckpoint"));

		// (c) the export re-applied by a non-person is accepted, the flag listed as no change
		ApplyResult again = apply(o, exported, SYSTEM);
		assertEquals(0, again.getErrors(), again.getChanges().toString());
		assertTrue(again.getChanges().stream().allMatch(c -> Action.UNCHANGED == c.getAction()), again.getChanges().toString());
		// a file that leaves the key out leaves the flag alone
		ApplyResult without = apply(o, board(name, "roles", List.of(architect(), coder())), SYSTEM);
		assertEquals(0, without.getErrors(), without.getChanges().toString());
		assertTrue(role(o, name, "coder").orElseThrow().isCoordinatorCheckpoint());
	}

	@Test
	public void aNonPersonsFileThatSetsOrClearsTheFlagIsRefusedWhole() throws RelizaException {
		Org o = org();
		String unflagged = "cp-" + UUID.randomUUID();
		assertEquals(0, apply(o, board(unflagged, "description", "plain", "target", o.target(),
				"roles", List.of(architect(), coder())), PERSON).getErrors());

		// (a) set
		ApplyResult set = apply(o, board(unflagged, "roles", List.of(architect(),
				coder("coordinatorCheckpoint", true, "wipLimit", 2))), SYSTEM);
		refusalOn(set, "coder");
		AgentTaskRoleConfigData kept = role(o, unflagged, "coder").orElseThrow();
		assertFalse(kept.isCoordinatorCheckpoint());
		assertEquals(null, kept.getWipLimit(), "nothing of the file is applied");

		// (a) clear
		String flagged = flaggedBoard(o);
		ApplyResult cleared = apply(o, board(flagged, "roles", List.of(architect(),
				coder("coordinatorCheckpoint", false, "wipLimit", 2))), SYSTEM);
		refusalOn(cleared, "coder");
		assertTrue(role(o, flagged, "coder").orElseThrow().isCoordinatorCheckpoint());
		assertEquals(null, role(o, flagged, "coder").orElseThrow().getWipLimit());

		// (b) the same files from a person
		assertEquals(0, apply(o, board(unflagged, "roles", List.of(architect(),
				coder("coordinatorCheckpoint", true))), PERSON).getErrors());
		assertTrue(role(o, unflagged, "coder").orElseThrow().isCoordinatorCheckpoint());
		assertEquals(0, apply(o, board(flagged, "roles", List.of(architect(),
				coder("coordinatorCheckpoint", false))), PERSON).getErrors());
		assertFalse(role(o, flagged, "coder").orElseThrow().isCoordinatorCheckpoint());
	}

	@Test
	public void aFileThatMovesTheFlagIsAcceptedInEitherOrder() throws RelizaException {
		Org o = org();
		String name = flaggedBoard(o);
		ApplyResult moved = apply(o, board(name, "roles", List.of(
				coder("coordinatorCheckpoint", false), map("name", "architect", "coordinatorCheckpoint", true))), PERSON);
		assertEquals(0, moved.getErrors(), moved.getChanges().toString());
		assertTrue(role(o, name, "architect").orElseThrow().isCoordinatorCheckpoint());
		ApplyResult back = apply(o, board(name, "roles", List.of(
				coder("coordinatorCheckpoint", true), map("name", "architect", "coordinatorCheckpoint", false))), PERSON);
		assertEquals(0, back.getErrors(), back.getChanges().toString());
		assertTrue(role(o, name, "coder").orElseThrow().isCoordinatorCheckpoint());
		ApplyResult two = apply(o, board(name, "roles", List.of(
				coder(), map("name", "architect", "coordinatorCheckpoint", true))), PERSON);
		assertTrue(errors(two).stream().anyMatch(c -> c.getMessage().equals("Board " + name
				+ " already has a coordinator checkpoint before architect; clear it first")
				|| c.getMessage().equals("Board " + name + " already has a coordinator checkpoint before coder; clear it first")),
				two.getChanges().toString());
		assertFalse(role(o, name, "architect").orElseThrow().isCoordinatorCheckpoint(), "refused whole");
	}

	/** (e)-(g): the role, not the value: deactivating or leaving out the flagged role is the same change. */
	@Test
	public void aNonPersonsFileCannotSwitchTheCheckpointOffByTheRole() throws RelizaException {
		Org o = org();
		String inactive = flaggedBoard(o);
		ApplyResult off = apply(o, board(inactive, "roles", List.of(architect(), coder("active", false), builder())), SYSTEM);
		refusalOn(off, "coder");
		assertTrue(role(o, inactive, "coder").orElseThrow().isActive());
		assertTrue(role(o, inactive, "coder").orElseThrow().isCoordinatorCheckpoint());
		assertTrue(role(o, inactive, "builder").isEmpty(), "builder not created");

		String absent = flaggedBoard(o);
		ApplyResult left = apply(o, board(absent, "roles", List.of(architect(), builder())), SYSTEM);
		Change c = refusalOn(left, "coder");
		assertTrue(c.getMessage().contains("absent"), c.getMessage());
		assertTrue(role(o, absent, "coder").orElseThrow().isActive(), "not archived");
		assertTrue(role(o, absent, "builder").isEmpty());

		// (g) the same files from a person; the ALERT warnStranded posts for work waiting on the coder, as today
		for (String b : List.of(inactive, absent)) {
			AgentBoardData bd = boardNamed(o, b);
			AgentTaskData waiting = agentTaskService.register(bd, null, "waits on the coder", null, null, null, null, null,
					null, WU);
			agentTaskService.authorize(waiting.getUuid(), bd, "coder", 10, null, null, null, null, PERSON, WU);
		}
		assertEquals(0, apply(o, board(inactive, "roles", List.of(architect(), coder("active", false), builder())),
				PERSON).getErrors());
		assertFalse(role(o, inactive, "coder").orElseThrow().isActive());
		assertTrue(stranded(o, inactive), "the ALERT for the waiting task");
		assertEquals(null, AgentBoardService.checkpointRole(agentBoardService.listRoleConfigs(
				boardNamed(o, inactive).getUuid())), "the board has no checkpoint");
		assertEquals(0, apply(o, board(absent, "roles", List.of(architect(), builder())), PERSON).getErrors());
		assertFalse(role(o, absent, "coder").orElseThrow().isActive(), "archived");
		assertTrue(stranded(o, absent), "the ALERT for the waiting task");
		assertEquals(null, AgentBoardService.checkpointRole(agentBoardService.listRoleConfigs(
				boardNamed(o, absent).getUuid())));
	}

	private boolean stranded(Org o, String board) {
		return agentBoardService.recentEvents(boardNamed(o, board).getUuid()).stream()
				.filter(e -> AgentBoardData.BoardEventKind.ALERT == e.kind())
				.anyMatch(e -> e.message().startsWith("Role coder is deactivated while 1 task(s) wait on it"));
	}

	// ---------- presets (T-13 h) ----------

	@Test
	public void aPresetNeverCarriesTheFlag() throws RelizaException {
		Org o = org();
		String preset = "cp-preset-" + UUID.randomUUID().toString().substring(0, 8);
		Map<String, Object> file = map("kind", "ROLE_PRESETS", "version", 1, "presets",
				List.of(map("name", preset, "prompt", "p", "coordinatorCheckpoint", true)));
		ApplyResult refused = agentBoardService.applyRolePresets(o.uuid(), AgentBoardService.rolePresetsSpecFromInput(file),
				false, null, WU);
		assertTrue(refused.getErrors() > 0, refused.getChanges().toString());
		Change e = refused.getChanges().stream().filter(c -> preset.equals(c.getName())).findFirst().orElseThrow();
		assertTrue(e.getMessage().contains("not on a preset"), e.getMessage());
		assertTrue(agentBoardService.listPresets(o.uuid()).stream().noneMatch(p -> preset.equals(p.getName())));

		Map<String, Object> plain = map("kind", "ROLE_PRESETS", "version", 1, "presets",
				List.of(map("name", preset, "prompt", "p")));
		ApplyResult applied = agentBoardService.applyRolePresets(o.uuid(), AgentBoardService.rolePresetsSpecFromInput(plain),
				false, null, WU);
		assertEquals(0, applied.getErrors(), applied.getChanges().toString());
		AgentTaskRoleConfigData stored = agentBoardService.listPresets(o.uuid()).stream()
				.filter(p -> preset.equals(p.getName())).findFirst().orElseThrow();

		for (boolean value : new boolean[] {true, false}) {
			RelizaException up = assertThrows(RelizaException.class, () -> agentBoardService.upsertPreset(o.uuid(),
					new AgentBoardService.RoleConfigSpec(preset, "changed", null, null, null, null, null, null, null, null,
							null, null, null, null, null, null, value), WU));
			assertEquals(AgentBoardService.CHECKPOINT_NOT_ON_PRESET, up.getMessage());
		}
		assertEquals(stored.getPrompt(), agentBoardService.listPresets(o.uuid()).stream()
				.filter(p -> preset.equals(p.getName())).findFirst().orElseThrow().getPrompt(), "the preset unchanged");
		// and a preset export, which carries no flag, re-applies
		Map<String, Object> exported = Utils.OM.convertValue(agentBoardService.exportRolePresets(o.uuid()), Map.class);
		assertEquals(0, agentBoardService.applyRolePresets(o.uuid(), AgentBoardService.rolePresetsSpecFromInput(exported),
				false, null, WU).getErrors());
	}

	// ---------- the routing-rules bullet through the served prompt (T-15, F-1) ----------

	@Test
	public void theServedPromptCarriesTheCheckpointBulletOnlyWhileARoleIsFlagged() throws RelizaException {
		Org o = org();
		String name = flaggedBoard(o);
		AgentBoardData b = boardNamed(o, name);
		String served = agentBoardService.servedPromptFor(b, "you are the coder");
		assertTrue(served.contains(AgentBoardService.checkpointLine("coder")), served);
		assertTrue(served.contains("- Design checkpoint: before the coder is queued, every task stops at the coordinator"
				+ " with an ALERT; the coordinator reads the reviewed design and splits the task or sends it on. No pass"
				+ " goes straight to the coder. An architect that recommends a split says so in the design's last section"
				+ " and in its sign-off note.\n"), served);

		agentBoardService.upsertRoleConfig(b, flag("coder", false), true, WU);
		String cleared = agentBoardService.servedPromptFor(boardNamed(o, name), "you are the coder");
		assertFalse(cleared.contains("Design checkpoint"), cleared);
	}
}
