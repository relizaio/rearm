/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.language.OperationDefinition.Operation;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.AgentCapability;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Who may declare a delivery (task 18c5c293): the coordinator seat, a session that worked the task
 * in a role that merges, or an org admin; not the coder, not another organization, not a writer.
 * Called through the real resolvers with the principal the programmatic filter leaves on the request.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class AgentDeliveryDeclarationAccessTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String SHA = "abcdef0123456789abcdef0123456789abcdef01";

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentTaskDataFetcher fetcher;
	@Autowired private AgentService agentService;
	@Autowired private AgentIdentityService agentIdentityService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private UserService userService;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private ApplicationContext applicationContext;

	private boolean wasSealed;
	private boolean wasLicensed;

	@BeforeEach
	void operational() {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		wasSealed = licenseStatus.isSystemSealed();
		wasLicensed = licenseStatus.isLicenseValid();
		licenseStatus.setSystemSealed(false);
		licenseStatus.setLicenseValid(true);
	}

	@AfterEach
	void restore() {
		SecurityContextHolder.clearContext();
		licenseStatus.setSystemSealed(wasSealed);
		licenseStatus.setLicenseValid(wasLicensed);
	}

	private record Rig(Organization org, ApiKey key, AgentData agent, AgentBoardData board, AgentSessionData seat,
			AgentSessionData coder, AgentSessionData merger) {}

	private ApiKey agentKey(Organization org) throws RelizaException {
		return agentKey(org, Set.of(PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT, PermissionFunction.BOARD_WRITE));
	}

	private ApiKey agentKey(Organization org, Set<PermissionFunction> functions) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "delivery agent", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE, functions,
				null)), WU);
		return key;
	}

	private AgentSessionData session(Organization org, ApiKey key, AgentData agent, String name)
			throws RelizaException {
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), key.getUuid(), name + "-" + UUID.randomUUID(),
				name, null, null, WU);
	}

	/** A coder then a merger (PR_MERGE), both REQUIRED, and a seat; three sessions of one agent. */
	private Rig rig() throws RelizaException {
		return rig(Set.of(PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT, PermissionFunction.BOARD_WRITE));
	}

	/** The same, its sessions opened with a key holding {@code functions}. */
	private Rig rig(Set<PermissionFunction> functions) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		ApiKey key = agentKey(org, functions);
		UUID identity = agentIdentityService.findOrRegisterByCredential(org.getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), identity, "d-" + UUID.randomUUID(),
				null, null, null, WU);
		Component target = componentService.createComponent("dl_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "dl-" + UUID.randomUUID(), "delivery",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build", 10, null, false,
				true, List.of(), null, RoleNecessity.REQUIRED, null, List.of(), List.of(), null, null), true, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("merger", "merge", 20, null, false,
				true, List.of(AgentCapability.PR_MERGE), null, RoleNecessity.REQUIRED, null, List.of(), List.of(), null, null),
				true, WU);
		AgentSessionData seat = session(org, key, agent, "seat");
		board = agentBoardService.claimCoordinatorSeat(board.getUuid(), seat.getUuid(), agent.getUuid(), WU);
		return new Rig(org, key, agent, board, seat, session(org, key, agent, "coder"),
				session(org, key, agent, "merger"));
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	/** Coded and merged by the rig's sessions, its PR unregistered here: DELIVERING. */
	private AgentTaskData delivering(Rig r, String url) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), null, "deliver " + url, null, null, null, null, null, null,
				AgentActor.ofUser(UUID.randomUUID(), "someone"), true, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null,
				AgentActor.ofSession(r.seat().getUuid()), WU);
		agentTaskService.assign(t.getUuid(), board(r), r.agent().getUuid(), r.coder().getUuid(), WU);
		agentTaskService.linkPr(t.getUuid(), url, WU);
		AgentTaskData coded = agentTaskService.signOff(t.getUuid(), r.coder().getUuid(), SignOffOutcome.PASSED, "coded", WU);
		assertEquals("merger", coded.getRole());
		agentTaskService.assign(t.getUuid(), board(r), r.agent().getUuid(), r.merger().getUuid(), WU);
		AgentTaskData merged = agentTaskService.signOff(t.getUuid(), r.merger().getUuid(), SignOffOutcome.PASSED, "merged", WU);
		assertEquals(TaskStatus.DELIVERING, merged.getStatus());
		return merged;
	}

	private DgsDataFetchingEnvironment as(ApiKey key) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.13"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(Map.of()).build(), applicationContext);
	}

	private ApiKey personKey(Organization org, PermissionType level) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Person " + tag, "dl-" + tag + "@deliver.io", true, List.of(org.getUuid()),
				"dl-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), level,
				List.of(), null, WU);
		ApiKey key = apiKeyService.createObjectApiKey(u.getUuid(), ApiTypeEnum.USER, org.getUuid(),
				UUID.randomUUID().toString(), "dl person", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), level, List.of(), WU);
		return key;
	}

	private static String url() {
		return "https://github.com/acme/app/pull/" + UUID.randomUUID().toString().substring(0, 6);
	}

	@Test
	public void theMergingRolesSessionDeclaresAndTheCoderDoesNot() throws RelizaException {
		Rig r = rig();
		String pr = url();
		AgentTaskData d = delivering(r, pr);
		AccessDeniedException coder = assertThrows(AccessDeniedException.class, () -> fetcher.agentTaskDeclareDeliveryProgrammatic(
				d.getUuid(), r.coder().getUuid(), pr, SHA, null, null, as(r.key())));
		assertTrue(coder.getMessage().contains("role that merges (PR_MERGE)"), coder.getMessage());
		AgentTaskData done = fetcher.agentTaskDeclareDeliveryProgrammatic(d.getUuid(), r.merger().getUuid(), pr, SHA, null,
				"merged on reliza.rearmhq.com", as(r.key()));
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(AgentActor.ofSession(r.merger().getUuid()), done.getDeliveries().get(0).by());
	}

	@Test
	public void theSeatDeclaresAndAnotherOrganizationDoesNot() throws RelizaException {
		Rig r = rig();
		String pr = url();
		AgentTaskData d = delivering(r, pr);
		Rig other = rig();
		assertThrows(AccessDeniedException.class, () -> fetcher.agentTaskDeclareDeliveryProgrammatic(d.getUuid(),
				other.seat().getUuid(), pr, SHA, null, null, as(other.key())));
		AgentTaskData abandoned = fetcher.agentTaskDeclareDeliveryProgrammatic(d.getUuid(), r.seat().getUuid(), pr, null,
				"ABANDONED", "superseded", as(r.key()));
		assertEquals(TaskStatus.AWAITING_COORDINATOR, abandoned.getStatus());
	}

	@Test
	public void anOrgAdminDeclaresAndAWriterDoesNot() throws RelizaException {
		Rig r = rig();
		String pr = url();
		AgentTaskData d = delivering(r, pr);
		assertThrows(AccessDeniedException.class, () -> fetcher.agentTaskDeclareDelivery(d.getUuid(), pr, SHA, null, null,
				as(personKey(r.org(), PermissionType.READ_WRITE))));
		AgentTaskData done = fetcher.agentTaskDeclareDelivery(d.getUuid(), pr, SHA, "DELIVERED", null,
				as(personKey(r.org(), PermissionType.ADMIN)));
		assertEquals(TaskStatus.COMPLETED, done.getStatus());
		assertEquals(AgentActor.ActorKind.USER, done.getDeliveries().get(0).by().kind());
	}

	@Test
	public void theEndpointServesBothVerbs() throws Exception {
		ProgrammaticSchemaRegistry registry = new ProgrammaticSchemaRegistry();
		assertTrue(registry.allows(Operation.MUTATION, "agentTaskDeclareDeliveryProgrammatic"));
		assertTrue(registry.allows(Operation.MUTATION, "agentTaskDeclareDelivery"));
	}

	/** The rig's coder holding a new task with an old PR and its replacement linked; with {@code push}, coder has CODE_PUSH. */
	private AgentTaskData heldWithTwoPrs(Rig r, boolean push, String old, String replacement) throws RelizaException {
		if (push) {
			agentBoardService.upsertRoleConfig(board(r), new AgentBoardService.RoleConfigSpec("coder", "build", 10, null, false,
					true, List.of(AgentCapability.CODE_PUSH), null, RoleNecessity.REQUIRED, null, List.of(), List.of(), null, null),
					true, WU);
		}
		AgentTaskData t = agentTaskService.register(board(r), null, "replace " + old, null, null, null, null, null, null,
				AgentActor.ofUser(UUID.randomUUID(), "someone"), true, WU);
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null,
				AgentActor.ofSession(r.seat().getUuid()), WU);
		agentTaskService.assign(t.getUuid(), board(r), r.agent().getUuid(), r.coder().getUuid(), WU);
		agentTaskService.linkPr(t.getUuid(), old, WU);
		agentTaskService.linkPr(t.getUuid(), replacement, WU);
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	/**
	 * RD3-13, as RD3-18 amends it: who declares a PR superseded. The session holding the task in a CODE_PUSH role,
	 * the board's coordinator seat, a session whose key holds BOARD_WRITE on the board, and a person with
	 * BOARD_WRITE reach the rule (here refused for a PR its CI never reported, which proves the gate let them
	 * through); a reader person is refused at the gate.
	 */
	@Test
	public void theCodePushHolderAndABoardWriterDeclareASupersededPr() throws RelizaException {
		Rig r = rig();
		String old = url();
		String replacement = url();
		AgentTaskData t = heldWithTwoPrs(r, true, old, replacement);
		for (UUID session : List.of(r.coder().getUuid(), r.seat().getUuid(), r.merger().getUuid())) {
			RelizaException passed = assertThrows(RelizaException.class, () -> fetcher.agentTaskSupersedePullRequestProgrammatic(
					t.getUuid(), session, old, replacement, null, as(r.key())));
			assertTrue(passed.getMessage().contains("has not been reported by its repository's CI"), passed.getMessage());
		}
		RelizaException admin = assertThrows(RelizaException.class, () -> fetcher.agentTaskSupersedePullRequest(t.getUuid(),
				old, replacement, null, as(personKey(r.org(), PermissionType.ADMIN))));
		assertTrue(admin.getMessage().contains("has not been reported by its repository's CI"), admin.getMessage());
		assertThrows(AccessDeniedException.class, () -> fetcher.agentTaskSupersedePullRequest(t.getUuid(), old, replacement,
				null, as(personKey(r.org(), PermissionType.READ_ONLY))));
	}

	/**
	 * RD3-18: on a task nobody holds -- AWAITING_COORDINATOR, where a replaced PR leaves it -- the seat and a
	 * BOARD_WRITE key declare; with a key lacking BOARD_WRITE, neither a session that holds nothing nor a holder
	 * without CODE_PUSH may, and the refusal names the three who may.
	 */
	@Test
	public void theSeatAndABoardWriteKeyDeclareOnAnUnheldTaskAndAKeyWithoutItDoesNot() throws RelizaException {
		Rig r = rig();
		String old = url();
		String replacement = url();
		AgentTaskData held = heldWithTwoPrs(r, false, old, replacement);
		AgentTaskData unheld = agentTaskService.returnTask(held.getUuid(), r.coder().getUuid(),
				io.reliza.model.AgentTaskData.TaskReturnReason.OTHER, "replaced its PR", WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, unheld.getStatus());
		assertEquals(null, unheld.getAssignment());
		for (UUID session : List.of(r.seat().getUuid(), r.coder().getUuid())) {
			RelizaException passed = assertThrows(RelizaException.class, () -> fetcher.agentTaskSupersedePullRequestProgrammatic(
					unheld.getUuid(), session, old, replacement, null, as(r.key())));
			assertTrue(passed.getMessage().contains("has not been reported by its repository's CI"), passed.getMessage());
		}

		Rig narrow = rig(Set.of(PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT, PermissionFunction.BOARD_READ));
		AgentTaskData noPush = heldWithTwoPrs(narrow, false, url(), url());
		AccessDeniedException holder = assertThrows(AccessDeniedException.class, () -> fetcher
				.agentTaskSupersedePullRequestProgrammatic(noPush.getUuid(), narrow.coder().getUuid(), noPush.getPrUrls().get(0),
						noPush.getPrUrls().get(1), null, as(narrow.key())));
		assertTrue(holder.getMessage().contains("CODE_PUSH") && holder.getMessage().contains("coordinator seat")
				&& holder.getMessage().contains("BOARD_WRITE"), holder.getMessage());
		assertThrows(AccessDeniedException.class, () -> fetcher.agentTaskSupersedePullRequestProgrammatic(noPush.getUuid(),
				narrow.merger().getUuid(), noPush.getPrUrls().get(0), noPush.getPrUrls().get(1), null, as(narrow.key())));
		// The seat needs no BOARD_WRITE: being the seat is enough.
		RelizaException seat = assertThrows(RelizaException.class, () -> fetcher.agentTaskSupersedePullRequestProgrammatic(
				noPush.getUuid(), narrow.seat().getUuid(), noPush.getPrUrls().get(0), noPush.getPrUrls().get(1), null,
				as(narrow.key())));
		assertTrue(seat.getMessage().contains("has not been reported by its repository's CI"), seat.getMessage());
	}

	@Test
	public void theEndpointServesBothSupersedeVerbs() throws Exception {
		ProgrammaticSchemaRegistry registry = new ProgrammaticSchemaRegistry();
		assertTrue(registry.allows(Operation.MUTATION, "agentTaskSupersedePullRequestProgrammatic"));
		assertTrue(registry.allows(Operation.MUTATION, "agentTaskSupersedePullRequest"));
	}
}
