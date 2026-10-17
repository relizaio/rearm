/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.ApiKeyService;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * The person's side of task RD3-4 through GraphQL: unassigning a task needs BOARD_WRITE on the board,
 * and the board form saves the staleness block and reads it back.
 */
@SpringBootTest(classes = {App.class})
public class AgentTaskUnassignTest {

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private ComponentService componentService;
	@Autowired private LicenseStatus licenseStatus;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private AgentIdentityService agentIdentityService;

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
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

	private ExecutionResult asPerson(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("release-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	private UserData person(Organization org, PermissionType type, List<PermissionFunction> functions) throws Exception {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Rel " + tag, "rel-" + tag + "@tasks.io", true, List.of(org.getUuid()),
				"rel-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), org.getUuid(), PermissionScope.ORGANIZATION, org.getUuid(), type, functions,
				null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private static final String UNASSIGN = "mutation($t: ID!, $r: String!) { agentTaskUnassign(taskUuid: $t, reason: $r) "
			+ "{ status unassignments { session reason unassignedBy { kind } } statusHistory { trigger note } } }";

	@Test
	@SuppressWarnings("unchecked")
	public void unassigningNeedsBoardWriteAndTheFormSavesTheStalenessBlock() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("rel_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "rel-" + UUID.randomUUID(), "release",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics("coder", "build", 10,
				null, false, true, null), true, WU);
		board = agentBoardService.getBoardData(board.getUuid()).orElseThrow();
		AgentTaskData t = agentTaskService.register(board, null, "release " + UUID.randomUUID(), null, null, null,
				null, null, null, COORD, true, WU);
		agentTaskService.authorize(t.getUuid(), board, "coder", 10, null, null, null, null, COORD, WU);
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(), "rel-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(org.getUuid(), agent.getUuid(), null, "s-" + UUID.randomUUID(),
				"session", null, null, WU);
		agentTaskService.assign(t.getUuid(), board, agent.getUuid(), s.getUuid(), WU);

		UserData reader = person(org, PermissionType.READ_WRITE, List.of());
		ExecutionResult refused = asPerson(reader, UNASSIGN, Map.of("t", t.getUuid().toString(), "r", "gone"));
		assertEquals(1, refused.getErrors().size(), refused.getErrors().toString());
		assertEquals("ASSIGNED", agentTaskService.getTaskData(t.getUuid()).orElseThrow().getStatus().name(),
				"a person without BOARD_WRITE unassigns nothing");

		UserData operator = person(org, PermissionType.READ_WRITE, List.of(PermissionFunction.BOARD_WRITE));
		ExecutionResult ok = asPerson(operator, UNASSIGN, Map.of("t", t.getUuid().toString(), "r", "the agent is gone"));
		assertTrue(ok.getErrors().isEmpty(), ok.getErrors().toString());
		Map<String, Object> released = (Map<String, Object>) ((Map<String, Object>) ok.getData()).get("agentTaskUnassign");
		assertEquals("QUEUED", released.get("status"));
		Map<String, Object> ra = ((List<Map<String, Object>>) released.get("unassignments")).get(0);
		assertEquals(s.getUuid().toString(), ra.get("session"));
		assertEquals("the agent is gone", ra.get("reason"));
		assertEquals("USER", ((Map<String, Object>) ra.get("unassignedBy")).get("kind"));
		List<Map<String, Object>> history = (List<Map<String, Object>>) released.get("statusHistory");
		assertEquals("UNASSIGN", history.get(history.size() - 1).get("trigger"));

		// the board form: the block through the settings input, read back on the board
		String update = "mutation($b: ID!, $i: AgentBoardInput!) { agentBoardUpdate(boardUuid: $b, input: $i) "
				+ "{ staleness { roleUnstaffedMinutes hopNoProgressMinutes deliveryStuckMinutes seatSilentMinutes repeatMinutes } } }";
		UserData admin = person(org, PermissionType.ADMIN, List.of());
		ExecutionResult saved = asPerson(admin, update, Map.of("b", board.getUuid().toString(), "i",
				Map.of("settings", Map.of("staleness", Map.of("hopNoProgressMinutes", 90, "repeatMinutes", 60)))));
		assertTrue(saved.getErrors().isEmpty(), saved.getErrors().toString());
		Map<String, Object> st = (Map<String, Object>) ((Map<String, Object>) ((Map<String, Object>) saved.getData())
				.get("agentBoardUpdate")).get("staleness");
		assertEquals(90, st.get("hopNoProgressMinutes"));
		assertEquals(60, st.get("repeatMinutes"));
		assertEquals(null, st.get("roleUnstaffedMinutes"));
		ExecutionResult zero = asPerson(admin, update, Map.of("b", board.getUuid().toString(), "i",
				Map.of("settings", Map.of("staleness", Map.of("seatSilentMinutes", 0)))));
		assertTrue(zero.getErrors().toString().contains("settings.staleness.seatSilentMinutes must be at least 1 minute"),
				zero.getErrors().toString());
	}

	private ExecutionResult asKey(ApiKey key, String query, Map<String, Object> vars) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.12"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return dgsQueryExecutor.execute(query, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	private record Caller(ApiKey key, AgentData agent, AgentSessionData session) {}

	private Caller caller(Organization org, PermissionFunction... fns) throws Exception {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "release", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE, java.util.Set.of(fns), null)), WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(org.getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), identity, "rel-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(org.getUuid(), agent.getUuid(), key.getUuid(),
				"rel-" + UUID.randomUUID(), "release", null, null, WU);
		return new Caller(key, agent, s);
	}

	private static final String UNASSIGN_P = "mutation($t: ID!, $s: ID!, $r: String!) { agentTaskUnassignProgrammatic(taskUuid: $t, "
			+ "sessionUuid: $s, reason: $r) { status unassignments { reason unassignedBy { kind uuid } } } }";

	@Test
	@SuppressWarnings("unchecked")
	public void theSeatUnassignsThroughTheProgrammaticEndpointAndAWorkerKeyIsRefused() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("relp_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "relp-" + UUID.randomUUID(), "release",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics("coder", "build", 10,
				null, false, true, null), true, WU);
		board = agentBoardService.getBoardData(board.getUuid()).orElseThrow();
		AgentTaskData t = agentTaskService.register(board, null, "release " + UUID.randomUUID(), null, null, null,
				null, null, null, COORD, true, WU);
		agentTaskService.authorize(t.getUuid(), board, "coder", 10, null, null, null, null, COORD, WU);
		Caller worker = caller(org, PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		agentTaskService.assign(t.getUuid(), board, worker.agent().getUuid(), worker.session().getUuid(), WU);

		ExecutionResult byWorker = asKey(worker.key(), UNASSIGN_P, Map.of("t", t.getUuid().toString(),
				"s", worker.session().getUuid().toString(), "r", "gone"));
		assertTrue(byWorker.getErrors().toString().contains("BOARD_WRITE"), byWorker.getErrors().toString());
		assertEquals("ASSIGNED", agentTaskService.getTaskData(t.getUuid()).orElseThrow().getStatus().name());

		Caller seat = caller(org, PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		agentBoardService.claimCoordinatorSeat(board.getUuid(), seat.session().getUuid(), seat.agent().getUuid(), WU);
		ExecutionResult bySeat = asKey(seat.key(), UNASSIGN_P, Map.of("t", t.getUuid().toString(),
				"s", seat.session().getUuid().toString(), "r", "the worker went silent"));
		assertTrue(bySeat.getErrors().isEmpty(), bySeat.getErrors().toString());
		Map<String, Object> released = (Map<String, Object>) ((Map<String, Object>) bySeat.getData()).get("agentTaskUnassignProgrammatic");
		assertEquals("QUEUED", released.get("status"));
		Map<String, Object> by = (Map<String, Object>) ((List<Map<String, Object>>) released.get("unassignments")).get(0).get("unassignedBy");
		assertEquals("SESSION", by.get("kind"));
		assertEquals(seat.session().getUuid().toString(), by.get("uuid"));
	}
}
