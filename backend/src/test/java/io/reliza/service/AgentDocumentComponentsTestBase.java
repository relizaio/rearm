/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.model.dto.ComponentDto;
import io.reliza.model.dto.CreateComponentDto;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.service.AgentTaskInputService.InputVerdict;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The fixtures of the board document component tests (board-documents.md §2): a board with an architect
 * role on a target, its publish, the legacy components a board from before the map adopts. Split out of
 * AgentDocumentComponentsIntegrationTest by task TEA-14 when the TEA standing of the join added its own
 * class.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
abstract class AgentDocumentComponentsTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	protected static final String SRC = "github:acme/doc-components";
	protected static final String DOCS_SOURCE = "github:acme/doc-components-docs";
	protected static final String DOCS = "https://github.com/acme/doc-components-docs";
	protected static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	protected static final AtomicInteger ISSUE = new AtomicInteger(4200);
	protected static final RearmSpecificationType ARCH = RearmSpecificationType.ARCHITECTURE;

	@Autowired protected TestInitializer testInitializer;
	@Autowired protected AgentBoardService agentBoardService;
	@Autowired protected AgentTaskService agentTaskService;
	@Autowired protected AgentDocumentService agentDocumentService;
	@Autowired protected AgentTaskInputService agentTaskInputService;
	@Autowired protected ComponentService componentService;
	@Autowired protected DeclarativeConfigService declarativeConfigService;
	@Autowired protected GetComponentService getComponentService;
	@Autowired protected BranchService branchService;
	@Autowired protected AgentService agentService;
	@Autowired protected AgentSessionService agentSessionService;
	@Autowired protected BoardDocumentComponentService boardDocumentComponentService;
	@Autowired protected org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

	protected record Rig(Organization org, Component target, AgentBoardData board, AgentData worker,
			AgentSessionData session) {}

	/** A board with an architect role on a target, publishing to the shared documents repository. */
	protected Rig rig(Organization org, Component target, String boardName) throws RelizaException {
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), boardName, "documents",
				List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("architect", "design", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(ARCH, InputScope.TASK, false)), null, null), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData w = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(),
				"w-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(org.getUuid(), w.getUuid(), null,
				"s-" + UUID.randomUUID(), "worker", null, null, WU);
		return new Rig(org, target, board, w, s);
	}

	protected Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		return rig(org, newTarget(org), "Doc Board " + UUID.randomUUID());
	}

	protected Component newTarget(Organization org) throws RelizaException {
		return componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
	}

	protected AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	protected ComponentData component(UUID uuid) {
		return getComponentService.getComponentData(uuid).orElseThrow();
	}

	/** A task held by the rig's architect session. */
	protected AgentTaskData withArchitect(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "architect", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.worker().getUuid(), r.session().getUuid(), WU);
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	protected ReleaseData publishArchitecture(Rig r) throws RelizaException {
		AgentTaskData t = withArchitect(r);
		String path = "design/" + UUID.randomUUID() + "/architecture-1.md";
		return agentDocumentService.publish(r.session(), new PublishRequest(t.getUuid(), ARCH, null, path,
				"digest-of-" + path, "text/markdown", null, null, null, "c-" + UUID.randomUUID(), DOCS, "doc",
				ZonedDateTime.now(), null), WU);
	}

	/**
	 * A component the way boards made them before the map: GENERIC, named after the target, carrying
	 * the specification identifier and composed into the target's base feature set.
	 */
	protected UUID legacyComponent(Component target, Organization org) throws RelizaException {
		return legacyComponent(target, org, ARCH);
	}

	protected UUID legacyComponent(Component target, Organization org, RearmSpecificationType spec) throws RelizaException {
		Component legacy = componentService.createComponent(CreateComponentDto.builder()
				.name(component(target.getUuid()).getName() + "-" + spec.name().toLowerCase())
				.organization(org.getUuid())
				.type(ComponentType.COMPONENT)
				.versionSchema("Micro")
				.featureBranchVersioning("Branch.Micro")
				.identifiers(List.of(new RearmIdentifier(RearmIdentifierType.SPECIFICATION, spec.name())))
				.build(), WU);
		compose(target.getUuid(), legacy.getUuid(), true);
		return legacy.getUuid();
	}

	/**
	 * A board from before the flag (RD2-33): the field taken off its stored record, as every board
	 * created before the fix has it, so it adopts its target's legacy components.
	 */
	protected Rig legacy(Rig r) {
		jdbcTemplate.update("UPDATE rearm.agent_boards SET record_data = record_data - 'adoptsLegacyDocuments' WHERE uuid = ?",
				r.board().getUuid());
		assertTrue(board(r).getAdoptsLegacyDocuments(), "a board without the field reads as legacy");
		return r;
	}

	/** Add a component to, or take it out of, the target's base feature set. */
	protected void compose(UUID target, UUID component, boolean add) throws RelizaException {
		Branch base = branchService.getBaseBranchOfComponent(target).orElseThrow();
		BranchData bd = BranchData.branchDataFromDbRecord(base);
		List<ChildComponent> deps = null != bd.getDependencies() ? new LinkedList<>(bd.getDependencies())
				: new LinkedList<>();
		deps.removeIf(cc -> component.equals(cc.getUuid()));
		if (add) {
			deps.add(ChildComponent.builder().uuid(component)
					.branch(branchService.getBaseBranchOfComponent(component).map(Branch::getUuid).orElse(null))
					.status(StatusEnum.IGNORED).build());
		}
		branchService.updateBranch(BranchDto.builder().uuid(bd.getUuid()).dependencies(deps).build(), WU);
	}

	/** A component-scoped ARCHITECTURE requirement, evaluated for a task on the board. */
	protected InputVerdict architectureInput(AgentBoardData board, AgentTaskData td) {
		td.setRequiredInputs(List.of(new RequiredInput(InputKind.DOCUMENT, ARCH, InputScope.COMPONENT,
				null, null, null)));
		return agentTaskInputService.evaluate(board, null, td).get(0);
	}

}
