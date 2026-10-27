/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.Branch;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.ChildComponent;
import io.reliza.model.Component;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.BranchDto;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.service.BoardPerspectiveService.PerspectiveConsent;
import io.reliza.service.DeclarativeConfigService.Action;
import io.reliza.service.DeclarativeConfigService.ApplyResult;
import io.reliza.service.DeclarativeConfigService.Change;
import io.reliza.service.saas.PerspectiveService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * The fixtures of the board perspective tests (board-permissions.md §3; task TEA-9 round 2 section 6):
 * an org with a target in perspectives A and B, boards, members, a document publish and the TEA
 * visibility a document component resolves to. Split out of AgentBoardPerspectivesIntegrationTest by
 * task TEA-14 when the TEA gate under the row lock added its own class.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
abstract class AgentBoardPerspectivesTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	protected static final AgentActor PERSON = AgentActor.system("test");
	protected static final PerspectiveConsent ANY = p -> true;
	protected static final String SRC = "github:acme/perspectives";
	protected static final String DOCS_SOURCE = "github:acme/perspectives-docs";
	protected static final String DOCS = "https://github.com/acme/perspectives-docs";
	protected static final AtomicInteger ISSUE = new AtomicInteger(8100);

	@Autowired protected TestInitializer testInitializer;
	@Autowired protected AgentBoardService agentBoardService;
	@Autowired protected AgentTaskService agentTaskService;
	@Autowired protected AgentDocumentService agentDocumentService;
	@Autowired protected ComponentService componentService;
	@Autowired protected GetComponentService getComponentService;
	@Autowired protected BranchService branchService;
	@Autowired protected PerspectiveService perspectiveService;
	@Autowired protected BoardPerspectiveService boardPerspectiveService;
	@Autowired protected UserService userService;
	@Autowired protected AuthorizationService authorizationService;
	@Autowired protected AgentService agentService;
	@Autowired protected AgentSessionService agentSessionService;
	@Autowired protected TeaProfileService teaProfileService;
	@Autowired protected TeaProfileResolver teaProfileResolver;

	/** An org, a target, perspectives A and B holding it, C without it, and a product P built from it. */
	protected record World(Organization org, Component target, String targetName, UUID a, String aName, UUID b,
			String bName, UUID c, String cName, UUID product, String productName) {}

	protected World world() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		String targetName = "persp-node-" + tag;
		Component target = componentService.createComponent(targetName, org.getUuid(), ComponentType.PRODUCT,
				"semver", "Branch.Micro", null, WU);
		UUID a = perspectiveService.createPerspective(org.getUuid(), "A-" + tag, WU).getUuid();
		UUID b = perspectiveService.createPerspective(org.getUuid(), "B-" + tag, WU).getUuid();
		UUID c = perspectiveService.createPerspective(org.getUuid(), "C-" + tag, WU).getUuid();
		componentService.setPerspectives(target.getUuid(), List.of(a, b), WU);
		String productName = "P-" + tag;
		Component product = componentService.createComponent(productName, org.getUuid(), ComponentType.PRODUCT,
				"semver", "Branch.Micro", null, WU);
		compose(product.getUuid(), target.getUuid());
		return new World(org, target, targetName, a, "A-" + tag, b, "B-" + tag, c, "C-" + tag, product.getUuid(), productName);
	}

	protected void compose(UUID parent, UUID child) throws RelizaException {
		Branch base = branchService.getBaseBranchOfComponent(parent).orElseThrow();
		BranchData bd = BranchData.branchDataFromDbRecord(base);
		List<ChildComponent> deps = null != bd.getDependencies() ? new LinkedList<>(bd.getDependencies()) : new LinkedList<>();
		deps.add(ChildComponent.builder().uuid(child)
				.branch(branchService.getBaseBranchOfComponent(child).map(Branch::getUuid).orElse(null))
				.status(StatusEnum.REQUIRED).build());
		branchService.updateBranch(BranchDto.builder().uuid(bd.getUuid()).dependencies(deps).build(), WU);
	}

	protected AgentBoardData board(World w, List<UUID> perspectives, PerspectiveConsent consent) throws RelizaException {
		return agentBoardService.createBoard(w.org().getUuid(), "persp-" + UUID.randomUUID(), "perspectives",
				List.of(SRC, DOCS_SOURCE), "coordinate", 4, null, w.target().getUuid(), null, perspectives, consent, WU);
	}

	protected AgentBoardData reload(AgentBoardData bd) {
		return agentBoardService.getBoardData(bd.getUuid()).orElseThrow();
	}

	/** A member of the org with the given org-level tier and, optionally, grants on perspectives. */
	protected UserData member(World w, PermissionType orgLevel, List<PermissionFunction> orgFunctions) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("P " + tag, "p-" + tag + "@persp.io", true, List.of(w.org().getUuid()),
				"p-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), w.org().getUuid(), PermissionScope.ORGANIZATION, w.org().getUuid(),
				orgLevel, orgFunctions, null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	protected UserData grantOn(UserData u, World w, UUID perspective, PermissionFunction... fns) {
		userService.setUserPermission(u.getUuid(), w.org().getUuid(), PermissionScope.PERSPECTIVE, perspective,
				PermissionType.READ_WRITE, List.of(fns), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	protected static Map<String, Object> spec(String name, Object... kv) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("kind", "BOARD");
		m.put("version", 1);
		m.put("name", name);
		for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
		return m;
	}

	protected ApplyResult apply(World w, Map<String, Object> spec, PerspectiveConsent consent) throws RelizaException {
		return agentBoardService.applyBoard(w.org().getUuid(), AgentBoardService.boardSpecFromInput(spec), false, null,
				PERSON, consent, WU);
	}

	protected static List<String> errors(ApplyResult r) {
		return r.getChanges().stream().filter(c -> Action.ERROR == c.getAction()).map(Change::getMessage).toList();
	}

	protected AgentBoardData named(World w, String name) {
		return agentBoardService.listByOrg(w.org().getUuid()).stream().filter(b -> name.equals(b.getName())).findFirst().orElseThrow();
	}

	protected ReleaseDataHolder publish(AgentBoardData bd, World w) throws RelizaException {
		agentBoardService.upsertRoleConfig(reload(bd), new AgentBoardService.RoleConfigSpec("architect", "design", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE, InputScope.TASK, false)), null, null), true, WU);
		agentBoardService.setDocumentsConfig(bd.getUuid(), DOCS, null, WU);
		AgentData worker = agentService.findOrRegisterRootAgent(w.org().getUuid(), UUID.randomUUID(),
				"w-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(w.org().getUuid(), worker.getUuid(), null,
				"s-" + UUID.randomUUID(), "worker", null, null, WU);
		AgentTaskData t = agentTaskService.register(reload(bd), SRC + "#" + ISSUE.incrementAndGet(), "work",
				null, null, null, null, null, null, WU);
		agentTaskService.authorize(t.getUuid(), reload(bd), "architect", 10, null, null, null, null,
				AgentActor.ofSession(UUID.randomUUID()), WU);
		agentTaskService.assign(t.getUuid(), reload(bd), worker.getUuid(), s.getUuid(), WU);
		String path = "design/" + UUID.randomUUID() + "/architecture-1.md";
		return new ReleaseDataHolder(agentDocumentService.publish(s, new PublishRequest(t.getUuid(),
				RearmSpecificationType.ARCHITECTURE, null, path, "d-" + path, "text/markdown", null, null, null,
				"c-" + UUID.randomUUID(), DOCS, "doc", ZonedDateTime.now(), null), WU).getComponent());
	}

	protected record ReleaseDataHolder(UUID component) {}

	protected Set<UUID> memberships(UUID component) {
		ComponentData cd = getComponentService.getComponentData(component).orElseThrow();
		return null == cd.getPerspectives() ? Set.of() : cd.getPerspectives();
	}

	/** A's row PUBLIC, B's PRIVATE, no organization row; the board's document component D. */
	protected UUID publicAPrivateB(World w, AgentBoardData bd) throws RelizaException {
		teaProfileService.saveProfile(w.org().getUuid(), io.reliza.model.TeaProfileData.TeaProfileScope.PERSPECTIVE, w.a(),
				TeaProfileServiceTestBase.full().toBuilder().visibility(io.reliza.model.TeaProfileData.TeaVisibility.PUBLIC)
						.build(), TeaProfileService.TeaCaller.ORG_ADMIN, WU);
		teaProfileService.saveProfile(w.org().getUuid(), io.reliza.model.TeaProfileData.TeaProfileScope.PERSPECTIVE, w.b(),
				TeaProfileServiceTestBase.full(), TeaProfileService.TeaCaller.ORG_ADMIN, WU);
		return publish(bd, w).component();
	}

	protected String visibility(UUID component) {
		TeaProfileResolver.TeaResolvedProfile r = teaProfileResolver.resolveForComponent(
				getComponentService.getComponentData(component).orElseThrow());
		return null == r.profile() ? "CONFLICT" : r.profile().getVisibility().name();
	}

	protected String nameOf(UUID component) {
		return getComponentService.getComponentData(component).orElseThrow().getName();
	}

}
