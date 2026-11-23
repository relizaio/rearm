/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import io.reliza.common.CommonVariables;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.ConditionSkip;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.AgentTaskRoleConfigData.HumanGate;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.AgentTaskRoleConfigData.RoleKind;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.AgentTaskRoleConfigData.RunWhen;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.service.AgentDocumentService.PublishRequest;

/**
 * The boards the conditional-role tests share (task TPF-155, section 10): helpers only, no tests. The default board is
 * architect (10, REQUIRED) -> coder (40, REQUIRED) -> mutant (45, OPTIONAL, {@code runWhen {taggedAny: [mutation]}},
 * reads DETAILED_DESIGN at ASSEMBLED, produces nothing) -> tester (50, REQUIRED, reads DETAILED_DESIGN, produces
 * BOARD_TEST_REPORT), with no checkpoint. The TPF-shaped board puts mutant_coder (44) and mutant_tester (46) between the
 * coder and the tester as the TPF board does. Each board's workers are in the rig by role name.
 */
abstract class AgentConditionalRoleTestBase extends AgentDesignCheckpointTestBase {

	static final RearmSpecificationType BTR = RearmSpecificationType.BOARD_TEST_REPORT;
	static final String MUTATION = "mutation";
	static final String NO_MUTATION_TAG = "runWhen not met: no mutation tag";
	static final RunWhen TAGGED_MUTATION = new RunWhen(List.of(MUTATION), List.of());

	/** A conditional board: the base rig (architect, coder, tester workers) and every role's worker by name. */
	record CRig(Rig rig, Map<String, Worker> workers) {
		Worker w(String role) {
			Worker w = workers.get(role);
			if (null == w) throw new IllegalArgumentException("no worker for " + role);
			return w;
		}
	}

	/** One role of a conditional board. */
	record RoleDef(String name, int order, RoleNecessity necessity, List<RequiredInput> inputs,
			List<ProducedOutput> outputs, RunWhen runWhen, RoleKind kind, Boolean blind) {
		RoleDef(String name, int order, RoleNecessity necessity, List<RequiredInput> inputs, List<ProducedOutput> outputs,
				RunWhen runWhen) {
			this(name, order, necessity, inputs, outputs, runWhen, null, null);
		}
	}

	static RoleDef architectDef() {
		return new RoleDef("architect", 10, RoleNecessity.REQUIRED, List.of(), List.of(produces(ARCH), produces(TM)), null);
	}

	static RoleDef coderDef() {
		return new RoleDef("coder", 40, RoleNecessity.REQUIRED, List.of(input(ARCH), input(TM)), List.of(produces(DD)), null);
	}

	static RoleDef mutantDef(RunWhen runWhen, List<RequiredInput> inputs, List<ProducedOutput> outputs) {
		return new RoleDef("mutant", 45, RoleNecessity.OPTIONAL, inputs, outputs, runWhen);
	}

	static RoleDef testerDef(List<RequiredInput> inputs, List<ProducedOutput> outputs) {
		return new RoleDef("tester", 50, RoleNecessity.REQUIRED, inputs, outputs, null);
	}

	static RequiredInput assembled(RearmSpecificationType spec) {
		return new RequiredInput(InputKind.DOCUMENT, spec, InputScope.TASK, null, ReleaseLifecycle.ASSEMBLED, null);
	}

	/** The default board of section 10. */
	CRig conditional() throws RelizaException {
		return conditional(TAGGED_MUTATION);
	}

	/** The default board, the mutant carrying {@code runWhen}. */
	CRig conditional(RunWhen runWhen) throws RelizaException {
		return conditional(mutantDef(runWhen, List.of(assembled(DD)), List.of()),
				testerDef(List.of(input(DD)), List.of(produces(BTR))));
	}

	/** architect -> coder -> {@code mutant} -> {@code tester} (and any {@code extra}), as given. */
	CRig conditional(RoleDef mutant, RoleDef tester, RoleDef... extra) throws RelizaException {
		List<RoleDef> defs = new ArrayList<>(List.of(architectDef(), coderDef(), mutant, tester));
		defs.addAll(List.of(extra));
		return board(defs);
	}

	/** The TPF-shaped board of T-97: mutant_coder (44) and mutant_tester (46), both conditional on {@code mutation}. */
	CRig tpfShaped() throws RelizaException {
		return board(List.of(architectDef(), coderDef(),
				new RoleDef("mutant_coder", 44, RoleNecessity.OPTIONAL, List.of(assembled(DD)), List.of(), TAGGED_MUTATION),
				new RoleDef("mutant_tester", 46, RoleNecessity.OPTIONAL, List.of(assembled(DD)), List.of(produces(BTR)),
						TAGGED_MUTATION),
				testerDef(List.of(input(DD)), List.of(produces(BTR)))));
	}

	/** A board of exactly these roles, each with a worker. */
	CRig board(List<RoleDef> defs) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("conditional_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "conditional-" + UUID.randomUUID(),
				"conditional roles", List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		Map<String, Worker> workers = new LinkedHashMap<>();
		for (RoleDef d : defs) {
			agentBoardService.upsertRoleConfig(board, spec(d), true, WU);
			workers.put(d.name(), worker(org, d.name()));
		}
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		Worker none = worker(org, "unused");
		Rig rig = new Rig(org, board, workers.getOrDefault("architect", none), none, none,
				workers.getOrDefault("coder", none), workers.getOrDefault("tester", none));
		return new CRig(rig, workers);
	}

	static AgentBoardService.RoleConfigSpec spec(RoleDef d) {
		Long hop = null;
		if ("architect".equals(d.name())) hop = ARCHITECT_HOP;
		if ("coder".equals(d.name())) hop = CODER_HOP;
		return new AgentBoardService.RoleConfigSpec(d.name(), d.name() + " prompt", d.order(), null, false, true, null,
				d.kind(), d.necessity(), RoleKind.HUMAN == d.kind() ? null : HumanGate.NONE, d.inputs(), d.outputs(), null,
				hop, d.blind(), null, null, null == d.runWhen() ? null : Optional.of(d.runWhen()));
	}

	AgentTaskData task(CRig c, String title) throws RelizaException {
		return task(c.rig(), title);
	}

	/** A task tagged {@code tags} before the architect starts. */
	AgentTaskData task(CRig c, String title, String... tags) throws RelizaException {
		AgentTaskData t = task(c.rig(), title);
		tag(t, tags);
		return reload(t);
	}

	void tag(AgentTaskData t, String... keys) throws RelizaException {
		List<CommonVariables.TagRecord> tags = new ArrayList<>();
		for (String k : keys) tags.add(new CommonVariables.TagRecord(k, null));
		agentTaskService.setTags(t.getUuid(), tags, COORD, WU);
	}

	void tagWithValue(AgentTaskData t, String key, String value) throws RelizaException {
		agentTaskService.setTags(t.getUuid(), List.of(new CommonVariables.TagRecord(key, value)), COORD, WU);
	}

	/** The architect designs and the coder builds: the walk after the coder's pass is the one under test. */
	AgentTaskData designAndCode(CRig c, AgentTaskData t) throws RelizaException {
		design(c.rig(), t, "fits one coder pass");
		code(c.rig(), t);
		return reload(t);
	}

	/** A passing BOARD_TEST_REPORT round with no items, published by {@code w} on the task. */
	ReleaseData passingReport(Worker w, AgentTaskData t) throws RelizaException {
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", BTR.name());
		idx.put("verdict", "PASSED");
		idx.put("counts", Map.of("passed", 1, "failed", 0, "skipped", 0));
		idx.put("reviewItems", new ArrayList<>());
		return agentDocumentService.publish(w.session(), new PublishRequest(reload(t).getUuid(), BTR, null, null, null,
				null, null, null, idx, null, null, null, null, null), WU);
	}

	List<ConditionSkip> skips(AgentTaskData t) {
		List<ConditionSkip> s = reload(t).getRolesSkippedByCondition();
		return null == s ? List.of() : s;
	}

	/** The note of the task's newest status row. */
	String lastNote(AgentTaskData t) {
		List<StatusChange> h = reload(t).getStatusHistory();
		return h.get(h.size() - 1).note();
	}

	/** Every status row's note, oldest first. */
	List<String> notes(AgentTaskData t) {
		return reload(t).getStatusHistory().stream().map(StatusChange::note).toList();
	}

	long signOffsBy(AgentTaskData t, String role) {
		return reload(t).getSignOffs().stream().filter(so -> role.equals(so.role())).count();
	}

	AgentTaskRoleConfigData roleOf(CRig c, String name) {
		return agentBoardService.getRoleConfig(c.rig().board().getUuid(), name).orElseThrow();
	}

	void assertQueuedFor(AgentTaskData t, String role) {
		AgentTaskData now = reload(t);
		assertEquals(TaskStatus.QUEUED, now.getStatus(), now.getStatusHistory().toString());
		assertEquals(role, now.getRole(), now.getStatusHistory().toString());
	}

	/** The QUEUED row's clause for one role passed over for {@code reason}. */
	static String passedOver(String role, String reason) {
		return role + " (" + reason + ")";
	}
}
