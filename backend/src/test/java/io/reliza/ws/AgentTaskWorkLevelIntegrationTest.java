/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskInput.SplitChild;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AgentTaskService.WorkerAssignment;

/**
 * Task level on the surfaces people read (RD2-1): the level a board reads is the task's own, else the
 * board's default, resolved on read; a person or the coordinator seat sets or clears it, 0 to 9,
 * stamped and announced; the poll prefers the lower effective level.
 */
@SpringBootTest(classes = {App.class})
public class AgentTaskWorkLevelIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "operator");

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private ApiKeyService apiKeyService;
	@Autowired private AgentIdentityService agentIdentityService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private LicenseStatus licenseStatus;

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

	private record Rig(Organization org, AgentBoardData board) {}

	/**
	 * A board with a coder role, the given default level (null for none) and a ten-rung ladder, 0 to 9 -- levels
	 * mean something only on a board with a ladder (task RD3-6).
	 */
	private Rig rig(Integer defaultWorkLevel) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("lvl_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "lvl-" + UUID.randomUUID(), "levels",
				List.of(), "coordinate", 4, null, target.getUuid(), defaultWorkLevel, WU);
		List<Map<String, Object>> rungs = new java.util.ArrayList<>();
		for (int i = 0; i < 10; i++) rungs.add(Map.of("name", "l" + i));
		agentBoardService.updateSettingsFromInput(board.getUuid(), Map.of("ladder", Map.of("levels", rungs)), WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics("coder", "build", 10,
				null, false, true, null), true, WU);
		return new Rig(org, board(board.getUuid()));
	}

	private AgentBoardData board(UUID uuid) {
		return agentBoardService.getBoardData(uuid).orElseThrow();
	}

	private AgentTaskData register(Rig r, Integer level) throws RelizaException {
		return agentTaskService.register(board(r.board().getUuid()), null, "task " + UUID.randomUUID(), null, null,
				null, null, null, level, null, COORD, false, WU);
	}

	private AgentTaskData task(UUID uuid) {
		return agentTaskService.getTaskData(uuid).orElseThrow();
	}

	// ---------- 1. set, clear, refuse ----------

	@Test
	public void aPersonSetsAndClearsALevelAndTheDefaultIsReadNotWritten() throws RelizaException {
		Rig r = rig(1);
		AgentTaskData t = register(r, null);
		assertNull(task(t.getUuid()).getWorkLevel(), "the board default is not written onto the task");
		assertEquals(1, AgentTaskService.effectiveWorkLevel(task(t.getUuid()), board(r.board().getUuid())));

		AgentTaskData set = agentTaskService.setWorkLevel(t.getUuid(), 2, PERSON, WU);
		assertEquals(2, set.getWorkLevel());
		assertEquals(PERSON.uuid(), set.getWorkLevelSetBy().uuid());
		assertNotNull(set.getWorkLevelSetAt());
		assertTrue(agentBoardService.recentEvents(board(r.board().getUuid()).getUuid()).stream()
				.anyMatch(e -> e.message().contains("Level of task " + set.getKey()) && e.message().contains("set to 2")),
				"an INFO says so");

		AgentTaskData cleared = agentTaskService.setWorkLevel(t.getUuid(), null, PERSON, WU);
		assertNull(cleared.getWorkLevel());
		assertEquals(1, AgentTaskService.effectiveWorkLevel(cleared, board(r.board().getUuid())), "back to the default");
		assertTrue(agentBoardService.recentEvents(board(r.board().getUuid()).getUuid()).stream().anyMatch(e -> e.message().contains("cleared by")));

		for (int bad : new int[] {10, -1}) {
			RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.setWorkLevel(t.getUuid(), bad, PERSON, WU));
			assertTrue(e.getMessage().startsWith("level " + bad + " is not on this board's ladder: 0 l0, 1 l1"), e.getMessage());
		}
		RelizaException atRegister = assertThrows(RelizaException.class, () -> register(r, 12));
		assertTrue(atRegister.getMessage().startsWith("level 12 is not on this board's ladder"), atRegister.getMessage());

		AgentTaskData done = register(r, 3);
		agentTaskService.complete(done.getUuid(), "done by hand", PERSON, true, WU);
		assertEquals(TaskStatus.COMPLETED, task(done.getUuid()).getStatus());
		RelizaException history = assertThrows(RelizaException.class, () -> agentTaskService.setWorkLevel(done.getUuid(), 1, PERSON, WU));
		assertTrue(history.getMessage().contains("its level is history"), history.getMessage());
	}

	@Test
	public void aSplitChildInheritsTheRawLevelAndABoardWithoutADefaultReadsZero() throws RelizaException {
		Rig r = rig(null);
		AgentTaskData unset = register(r, null);
		assertEquals(0, AgentTaskService.effectiveWorkLevel(task(unset.getUuid()), board(r.board().getUuid())),
				"no level, no default: a board with a ladder reads 0 (task RD3-6)");

		AgentTaskData parent = register(r, 2);
		List<AgentTaskData> children = agentTaskService.split(parent.getUuid(), board(r.board().getUuid()), List.of(
				new SplitChild("inherits", null, null, null, null, null),
				new SplitChild("own level", null, null, 4, null, null)), COORD, WU);
		assertEquals(2, task(children.get(0).getUuid()).getWorkLevel());
		assertEquals(4, task(children.get(1).getUuid()).getWorkLevel());
		List<AgentTaskData> fromUnset = agentTaskService.split(unset.getUuid(), board(r.board().getUuid()),
				List.of(new SplitChild("from an unset parent", null, null, null, null, null)), COORD, WU);
		assertNull(task(fromUnset.get(0).getUuid()).getWorkLevel(), "the raw level, so a default still applies");

		// The board default: 0 to 9, set and cleared through the service the form's update uses.
		RelizaException e = assertThrows(RelizaException.class,
				() -> agentBoardService.setDefaultWorkLevel(r.board().getUuid(), 10, WU));
		assertEquals("defaultWorkLevel is 0 to 9", e.getMessage());
		assertEquals(3, agentBoardService.setDefaultWorkLevel(r.board().getUuid(), 3, WU).getDefaultWorkLevel());
		assertEquals(3, AgentTaskService.effectiveWorkLevel(task(unset.getUuid()), board(r.board().getUuid())),
				"every unset task reads the new default at once");
		assertNull(agentBoardService.setDefaultWorkLevel(r.board().getUuid(), null, WU).getDefaultWorkLevel());
	}

	// ---------- 2. the poll ----------

	@Test
	public void thePollPrefersTheLowerEffectiveLevel() throws RelizaException {
		Rig r = rig(1);
		AgentTaskData deeper = register(r, 3);
		AgentTaskData byDefault = register(r, null);
		// Ordered against the level: were the raw level read, the level-3 task would win on order.
		agentTaskService.authorize(deeper.getUuid(), board(r.board().getUuid()), "coder", 1, null, null, null, null, COORD, WU);
		agentTaskService.authorize(byDefault.getUuid(), board(r.board().getUuid()), "coder", 20, null, null, null, null, COORD, WU);
		AgentData worker = agentService.findOrRegisterRootAgent(r.org().getUuid(), UUID.randomUUID(),
				"lvl-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(r.org().getUuid(), worker.getUuid(), null,
				"lvl-" + UUID.randomUUID(), "worker", null, null, WU);
		WorkerAssignment offered = agentTaskService.next(List.of(board(r.board().getUuid())), worker.getUuid(), s.getUuid())
				.orElseThrow();
		assertEquals(byDefault.getUuid(), offered.task().getUuid(), "the unset task reads level 1, below 3");
	}

	// ---------- 3. both endpoints ----------

	private ExecutionResult asKey(ApiKey key, String query, Map<String, Object> vars) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.12"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return dgsQueryExecutor.execute(query, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	private ExecutionResult asPerson(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("level-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	@SuppressWarnings("unchecked")
	private static <T> T data(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (T) ((Map<String, Object>) r.getData()).get(field);
	}

	/** A FREEFORM key with the given org-wide functions, its agent, and a session of it. */
	private record Caller(ApiKey key, AgentData agent, AgentSessionData session) {}

	private Caller caller(Rig r, PermissionFunction... fns) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(r.org().getUuid(), ApiTypeEnum.FREEFORM, r.org().getUuid(),
				UUID.randomUUID().toString(), "levels", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(r.org().getUuid(),
				PermissionScope.ORGANIZATION, r.org().getUuid(), PermissionType.READ_WRITE, Set.of(fns), null)), WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(r.org().getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(r.org().getUuid(), identity, "lvl-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(r.org().getUuid(), agent.getUuid(), key.getUuid(),
				"lvl-" + UUID.randomUUID(), "levels", null, null, WU);
		return new Caller(key, agent, s);
	}

	private static final String SET_P = "mutation($t: ID!, $s: ID!, $l: Int) { agentTaskSetWorkLevelProgrammatic(taskUuid: $t, sessionUuid: $s, workLevel: $l) { uuid workLevel effectiveWorkLevel workLevelSetBy { kind uuid } workLevelSetAt } }";

	@Test
	@SuppressWarnings("unchecked")
	public void theSeatAndAPersonSetItAndBothEndpointsServeIt() throws RelizaException {
		Rig r = rig(1);
		AgentTaskData t = register(r, null);

		Caller seat = caller(r, PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		agentBoardService.claimCoordinatorSeat(r.board().getUuid(), seat.session().getUuid(), seat.agent().getUuid(), WU);
		Map<String, Object> set = data(asKey(seat.key(), SET_P, Map.of("t", t.getUuid().toString(),
				"s", seat.session().getUuid().toString(), "l", 2)), "agentTaskSetWorkLevelProgrammatic");
		assertEquals(2, set.get("workLevel"));
		assertEquals(2, set.get("effectiveWorkLevel"));
		assertEquals(seat.session().getUuid().toString(), ((Map<String, Object>) set.get("workLevelSetBy")).get("uuid"));
		assertNotNull(set.get("workLevelSetAt"));

		Map<String, Object> vars = new java.util.HashMap<>(Map.of("t", t.getUuid().toString(), "s", seat.session().getUuid().toString()));
		vars.put("l", null);
		Map<String, Object> cleared = data(asKey(seat.key(), SET_P, vars), "agentTaskSetWorkLevelProgrammatic");
		assertNull(cleared.get("workLevel"));
		assertEquals(1, cleared.get("effectiveWorkLevel"), "cleared reads the board default");

		Caller worker = caller(r, PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		ExecutionResult refused = asKey(worker.key(), SET_P, Map.of("t", t.getUuid().toString(),
				"s", worker.session().getUuid().toString(), "l", 3));
		assertFalse(refused.getErrors().isEmpty(), "a worker key is refused");
		assertTrue(refused.getErrors().toString().contains("BOARD_WRITE"), refused.getErrors().toString());
		assertNull(task(t.getUuid()).getWorkLevel());

		// The read: the key's and a person's, and the snapshot entry, each with effectiveWorkLevel.
		assertEquals(1, ((Map<String, Object>) data(asKey(seat.key(),
				"query($t: ID!) { agentTaskProgrammatic(taskUuid: $t) { workLevel effectiveWorkLevel } }",
				Map.of("t", t.getUuid().toString())), "agentTaskProgrammatic")).get("effectiveWorkLevel"));
		Map<String, Object> snap = data(asKey(seat.key(),
				"query($b: ID!) { agentBoardSnapshotProgrammatic(boardUuid: $b) { tasks { task { uuid effectiveWorkLevel } } } }",
				Map.of("b", r.board().getUuid().toString())), "agentBoardSnapshotProgrammatic");
		assertTrue(((List<Map<String, Object>>) snap.get("tasks")).stream().map(x -> (Map<String, Object>) x.get("task"))
				.anyMatch(x -> t.getUuid().toString().equals(x.get("uuid")) && Integer.valueOf(1).equals(x.get("effectiveWorkLevel"))));

		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Lvl " + tag, "lvl-" + tag + "@tasks.io", true, List.of(r.org().getUuid()),
				"lvl-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), r.org().getUuid(), PermissionScope.ORGANIZATION, r.org().getUuid(),
				PermissionType.ADMIN, List.of(), null, WU);
		UserData admin = userService.getUserData(u.getUuid()).orElseThrow();
		Map<String, Object> byPerson = data(asPerson(admin,
				"mutation($t: ID!, $l: Int) { agentTaskSetWorkLevel(taskUuid: $t, workLevel: $l) { workLevel effectiveWorkLevel workLevelSetBy { kind } } }",
				Map.of("t", t.getUuid().toString(), "l", 4)), "agentTaskSetWorkLevel");
		assertEquals(4, byPerson.get("workLevel"));
		assertEquals("USER", ((Map<String, Object>) byPerson.get("workLevelSetBy")).get("kind"));
		ExecutionResult tooDeep = asPerson(admin, "mutation($t: ID!, $l: Int) { agentTaskSetWorkLevel(taskUuid: $t, workLevel: $l) { workLevel } }",
				Map.of("t", t.getUuid().toString(), "l", 10));
		assertTrue(tooDeep.getErrors().toString().contains("level 10 is not on this board's ladder"), tooDeep.getErrors().toString());

		// The board form: an update refuses 10, sets 2, and a null clears it.
		String update = "mutation($b: ID!, $i: AgentBoardInput!) { agentBoardUpdate(boardUuid: $b, input: $i) { defaultWorkLevel } }";
		ExecutionResult bad = asPerson(admin, update, Map.of("b", r.board().getUuid().toString(), "i", Map.of("defaultWorkLevel", 10)));
		assertTrue(bad.getErrors().toString().contains("defaultWorkLevel is 0 to 9"), bad.getErrors().toString());
		assertEquals(2, ((Map<String, Object>) data(asPerson(admin, update, Map.of("b", r.board().getUuid().toString(),
				"i", Map.of("defaultWorkLevel", 2))), "agentBoardUpdate")).get("defaultWorkLevel"));
		Map<String, Object> clearInput = new java.util.HashMap<>();
		clearInput.put("defaultWorkLevel", null);
		assertNull(((Map<String, Object>) data(asPerson(admin, update, Map.of("b", r.board().getUuid().toString(),
				"i", clearInput)), "agentBoardUpdate")).get("defaultWorkLevel"), "sent as null it clears");
	}
}
