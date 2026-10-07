/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.AgentTaskRoleConfigData.CommissionIntake;
import io.reliza.model.AgentTaskRoleConfigData.Commissions;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.RearmSpecificationType;
import io.reliza.service.DeclarativeConfigService.Action;
import io.reliza.service.DeclarativeConfigService.ApplyResult;
import io.reliza.ws.App;

/**
 * A role's commissions (task RD4-12, design §3.2): refused when a commissioned role does not produce the report, on
 * the upsert, in a board file and in a presets file; the report only at TASK scope and never a role's input; the
 * coordinator seat may not set them; and the board file's round trip -- stored, exported, re-applied unchanged, a
 * change seen by a dry run, declared null cleared.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
@SuppressWarnings("unchecked")
public class InvestigationRoleConfigIntegrationTest extends InvestigationTestBase {

	private static Map<String, Object> map(Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	private static Map<String, Object> outputs(String... specs) {
		return map("producesOutputs", java.util.Arrays.stream(specs)
				.map(s -> map("specification", s, "scope", "TASK", "required", true)).toList());
	}

	private static Map<String, Object> roleEntry(String name, Map<String, Object> more) {
		Map<String, Object> m = map("name", name, "prompt", name + " prompt");
		m.putAll(more);
		return m;
	}

	private ApplyResult apply(UUID org, Map<String, Object> spec, boolean dryRun) throws RelizaException {
		return agentBoardService.applyBoard(org, AgentBoardService.boardSpecFromInput(spec), dryRun, null, PERSON, WU);
	}

	private static String messages(ApplyResult r) {
		return r.getChanges().stream().map(c -> String.valueOf(c.getMessage())).reduce("", (a, b) -> a + " | " + b);
	}

	@Test
	public void theUpsertRefusesACommissionOfARoleThatDoesNotProduceTheReport() throws RelizaException {
		Rig r = rig();
		RelizaException e = assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(r.board(),
				role("architect", 10, null, null, new Commissions(List.of("coder"), null, null, null)), true, WU));
		assertTrue(e.getMessage().contains("commissions coder, which does not produce BOARD_INVESTIGATION_REPORT"), e.getMessage());
		RelizaException unknown = assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(r.board(),
				role("architect", 10, null, null, new Commissions(List.of("nobody"), null, null, null)), true, WU));
		assertTrue(unknown.getMessage().contains("not a role on board"), unknown.getMessage());
		RelizaException review = assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(r.board(),
				role("architect", 10, null, null, new Commissions(List.of("tester"), null, null, "nobody")), true, WU));
		assertTrue(review.getMessage().contains("to review its investigations"), review.getMessage());
		assertEquals(List.of("tester", "researcher"), roleNamed(r, "architect").getCommissions().roles(), "left as it was");
	}

	@Test
	public void aCommissionedRoleCannotDropTheReport() throws RelizaException {
		Rig r = rig();
		RelizaException e = assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(r.board(),
				role("tester", 30, null, List.of(produces(RearmSpecificationType.BOARD_TEST_REPORT)), null), true, WU));
		assertTrue(e.getMessage().contains("role architect commissions tester"), e.getMessage());
	}

	@Test
	public void theReportIsTaskScopedAndNeverARolesInput() throws RelizaException {
		Rig r = rig();
		RelizaException scope = assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(r.board(),
				role("coder", 20, null, List.of(new ProducedOutput(REPORT, InputScope.COMPONENT, true)), null), true, WU));
		assertTrue(scope.getMessage().contains("TASK scope"), scope.getMessage());
		RelizaException input = assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(r.board(),
				role("coder", 20, List.of(new RequiredInput(InputKind.DOCUMENT, REPORT, InputScope.TASK, null, null, null)),
						null, null), true, WU));
		assertTrue(input.getMessage().contains("cannot be a required input"), input.getMessage());
		assertEquals(InputScope.TASK, AgentBoardService.documentScope(REPORT, List.of()));
		assertEquals("investigations/{key}/report-{round}.md",
				agentBoardService.effectiveDocumentPaths(board(r)).get(REPORT));
	}

	@Test
	public void theCoordinatorSeatMayNotSetCommissionsAndEmptyOnesAreNone() throws RelizaException {
		Rig r = rig();
		RelizaException seat = assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(r.board(),
				new AgentBoardService.RoleConfigSpec("architect", null, null, null, null, null, null, null, null, null, null,
						null, null, null, null, new Commissions(List.of("tester"), null, null, null)), false, WU));
		assertTrue(seat.getMessage().contains("commissions"), seat.getMessage());
		agentBoardService.upsertRoleConfig(r.board(), role("architect", 10, null, null,
				new Commissions(List.of(), CommissionIntake.COORDINATOR, 5L, null)), true, WU);
		assertNull(roleNamed(r, "architect").getCommissions(), "a block naming no role commissions nobody");
		RelizaException twice = assertThrows(RelizaException.class, () -> agentBoardService.upsertRoleConfig(r.board(),
				role("architect", 10, null, null, new Commissions(List.of("tester", "Tester"), null, null, null)), true, WU));
		assertTrue(twice.getMessage().contains("twice"), twice.getMessage());
	}

	@Test
	public void aBoardFileCommissioningARoleThatDoesNotProduceTheReportIsRefusedWhole() throws RelizaException {
		String name = "inv-file-" + UUID.randomUUID();
		String target = "inv_" + UUID.randomUUID();
		UUID org = testInitializer.obtainOrganization().getUuid();
		componentService.createComponent(target, org, io.reliza.model.ComponentData.ComponentType.PRODUCT, "semver",
				"Branch.Micro", null, WU);
		ApplyResult r = apply(org, map("kind", "BOARD", "version", 1, "name", name, "target", target, "roles", List.of(
				roleEntry("coder", outputs("DETAILED_DESIGN")),
				roleEntry("architect", map("commissions", map("roles", List.of("coder")))))), false);
		assertTrue(r.getErrors() > 0, r.getChanges().toString());
		assertTrue(messages(r).contains("does not produce BOARD_INVESTIGATION_REPORT"), messages(r));
		assertTrue(agentBoardService.listByOrg(org).stream().noneMatch(b -> name.equals(b.getName())),
				"nothing is written");
	}

	@Test
	public void aBoardFileStoresExportsAndReappliesCommissionsAndADryRunSeesTheChange() throws RelizaException {
		String name = "inv-file-" + UUID.randomUUID();
		String target = "inv_" + UUID.randomUUID();
		UUID org = testInitializer.obtainOrganization().getUuid();
		componentService.createComponent(target, org, io.reliza.model.ComponentData.ComponentType.PRODUCT, "semver",
				"Branch.Micro", null, WU);
		Map<String, Object> commissions = map("roles", List.of("tester"), "intake", "COORDINATOR",
				"defaultBudgetMicros", 3_000_000, "review", "lead");
		ApplyResult first = apply(org, map("kind", "BOARD", "version", 1, "name", name, "target", target, "roles", List.of(
				roleEntry("tester", outputs("BOARD_TEST_REPORT", "BOARD_INVESTIGATION_REPORT")),
				roleEntry("lead", outputs("BOARD_REVIEW_ITEMS")),
				roleEntry("architect", map("commissions", commissions)))), false);
		assertEquals(0, first.getErrors(), messages(first));
		AgentBoardData b = agentBoardService.listByOrg(org).stream().filter(x -> name.equals(x.getName())).findFirst()
				.orElseThrow();
		AgentTaskRoleConfigData architect = agentBoardService.getRoleConfig(b.getUuid(), "architect").orElseThrow();
		assertEquals(new Commissions(List.of("tester"), CommissionIntake.COORDINATOR, 3_000_000L, "lead"),
				architect.getCommissions());

		Map<String, Object> exported = Utils.OM.convertValue(agentBoardService.exportBoard(b.getUuid()), Map.class);
		Map<String, Object> exportedArchitect = ((List<Map<String, Object>>) exported.get("roles")).stream()
				.filter(x -> "architect".equals(x.get("name"))).findFirst().orElseThrow();
		assertEquals(map("roles", List.of("tester"), "intake", "COORDINATOR", "defaultBudgetMicros", 3_000_000L,
				"review", "lead"), exportedArchitect.get("commissions"));
		ApplyResult again = apply(org, exported, false);
		assertEquals(0, again.getErrors(), messages(again));
		assertTrue(again.getChanges().stream().allMatch(c -> Action.UNCHANGED == c.getAction()), again.getChanges().toString());

		// Drift: a dry run of a file that moves the intake reports the commissions as changed.
		Map<String, Object> moved = Utils.OM.convertValue(exported, Map.class);
		((List<Map<String, Object>>) moved.get("roles")).stream().filter(x -> "architect".equals(x.get("name")))
				.forEach(x -> x.put("commissions", map("roles", List.of("tester"), "intake", "AUTO")));
		ApplyResult dry = apply(org, moved, true);
		assertEquals(0, dry.getErrors(), messages(dry));
		assertTrue(dry.getChanges().stream().anyMatch(c -> "architect".equals(c.getName())
				&& c.getFields().contains("commissions")), dry.getChanges().toString());
		assertEquals(CommissionIntake.COORDINATOR, agentBoardService.getRoleConfig(b.getUuid(), "architect").orElseThrow()
				.getCommissions().intake(), "a dry run writes nothing");

		// Declared null commissions nobody.
		((List<Map<String, Object>>) moved.get("roles")).stream().filter(x -> "architect".equals(x.get("name")))
				.forEach(x -> x.put("commissions", null));
		assertEquals(0, apply(org, moved, false).getErrors());
		assertNull(agentBoardService.getRoleConfig(b.getUuid(), "architect").orElseThrow().getCommissions());
	}

	@Test
	public void presetsCommissionPresetsThatProduceTheReport() throws RelizaException {
		UUID org = testInitializer.obtainOrganization().getUuid();
		String asker = "asker-" + UUID.randomUUID();
		String maker = "maker-" + UUID.randomUUID();
		ApplyResult bad = agentBoardService.applyRolePresets(org, AgentBoardService.rolePresetsSpecFromInput(map(
				"kind", "ROLE_PRESETS", "version", 1, "presets", List.of(
						roleEntry(maker, outputs("BOARD_TEST_REPORT")),
						roleEntry(asker, map("commissions", map("roles", List.of(maker))))))), false, null, WU);
		assertTrue(bad.getErrors() > 0, bad.getChanges().toString());
		assertTrue(messages(bad).contains("does not produce BOARD_INVESTIGATION_REPORT"), messages(bad));

		ApplyResult good = agentBoardService.applyRolePresets(org, AgentBoardService.rolePresetsSpecFromInput(map(
				"kind", "ROLE_PRESETS", "version", 1, "presets", List.of(
						roleEntry(maker, outputs("BOARD_TEST_REPORT", "BOARD_INVESTIGATION_REPORT")),
						roleEntry(asker, map("commissions", map("roles", List.of(maker))))))), false, null, WU);
		assertEquals(0, good.getErrors(), messages(good));
		AgentTaskRoleConfigData preset = agentBoardService.listPresets(org).stream()
				.filter(p -> asker.equals(p.getName())).findFirst().orElseThrow();
		assertEquals(List.of(maker), preset.getCommissions().roles());
		assertEquals(CommissionIntake.AUTO, preset.getCommissions().intake(), "AUTO by default");
		assertTrue(agentBoardService.exportRolePresets(org).getPresets().stream()
				.anyMatch(p -> asker.equals(p.getName()) && null != p.getCommissions()));
	}
}
