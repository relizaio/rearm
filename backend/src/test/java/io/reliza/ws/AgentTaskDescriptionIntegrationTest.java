/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

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

/**
 * Titles capped at 120 characters with a description beside them (board-documents.md §4.5, test 7;
 * task fceb1e57): one line at registration and for every split child; the description served on both
 * bundles and in the snapshot; a task from before the cap keeps its title.
 */
@SpringBootTest(classes = {App.class})
public class AgentTaskDescriptionIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private ComponentService componentService;
	@Autowired private ApiKeyService apiKeyService;
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

	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("desc_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "desc-" + UUID.randomUUID(), "descriptions",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics("coder", "build", 10,
				null, false, true, null), true, WU);
		return new Rig(org, agentBoardService.getBoardData(board.getUuid()).orElseThrow());
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData register(Rig r, String title, String description) throws RelizaException {
		return agentTaskService.register(board(r), null, title, description, null, null, null, null, null, null,
				COORD, false, WU);
	}

	private static String of(int n) {
		return "t".repeat(n);
	}

	@Test
	public void aTitleIsOneLineOfAtMost120() throws RelizaException {
		Rig r = rig();
		assertEquals(of(120), register(r, of(120), null).getTitle(), "120 is allowed");
		assertEquals("padded", register(r, "  padded  ", null).getTitle(), "trimmed");

		RelizaException long121 = assertThrows(RelizaException.class, () -> register(r, of(121), null));
		assertTrue(long121.getMessage().contains("Titles are at most 120 characters"), long121.getMessage());
		assertTrue(long121.getMessage().contains("--description"), long121.getMessage());
		for (String broken : List.of("first line\nsecond", "first\r\nsecond")) {
			RelizaException e = assertThrows(RelizaException.class, () -> register(r, broken, null));
			assertTrue(e.getMessage().contains("A title is one line"), e.getMessage());
		}
		RelizaException longDescription = assertThrows(RelizaException.class, () -> register(r, "ok", "d".repeat(4001)));
		assertTrue(longDescription.getMessage().contains("at most 4000"), longDescription.getMessage());
		assertEquals("d".repeat(4000), register(r, "ok", "d".repeat(4000)).getDescription());
	}

	@Test
	public void aTaskFromBeforeTheCapKeepsItsTitle() throws RelizaException {
		Rig r = rig();
		AgentTaskData td = agentTaskService.register(board(r), "github:acme/desc#" + Math.abs(UUID.randomUUID().hashCode()),
				"short", null, null, null, null, null, null, WU);
		AgentTaskData old = agentTaskService.getTaskData(td.getUuid()).orElseThrow();
		old.setTitle(of(200));
		agentTaskService.saveData(old, WU);
		AgentTaskData read = agentTaskService.getTaskData(td.getUuid()).orElseThrow();
		assertEquals(200, read.getTitle().length(), "D14: existing titles are untouched");
		assertTrue(read.label().startsWith(read.getKey() + " "), read.label());
		assertTrue(read.label().length() < 80, "the label cuts it: " + read.label());
		// Re-registering its issue returns the task, long title and all: only creation is held to the rule.
		assertEquals(td.getUuid(), agentTaskService.register(board(r), read.getExternalRef(), of(200), null, null,
				null, null, null, null, WU).getUuid());
	}

	@Test
	public void splitChildrenCarryDescriptionsAndObeyTheCap() throws RelizaException {
		Rig r = rig();
		AgentTaskData parent = register(r, "parent", "the whole plan");
		List<AgentTaskData> children = agentTaskService.split(parent.getUuid(), board(r), List.of(
				new SplitChild("child one", null, null, null, null, null, "what the first child does"),
				new SplitChild("child two", null, null, null, null, null)), COORD, WU);
		assertEquals("what the first child does", agentTaskService.getTaskData(children.get(0).getUuid()).orElseThrow().getDescription());
		assertNull(agentTaskService.getTaskData(children.get(1).getUuid()).orElseThrow().getDescription());

		AgentTaskData other = register(r, "another parent", null);
		RelizaException e = assertThrows(RelizaException.class, () -> agentTaskService.split(other.getUuid(), board(r),
				List.of(new SplitChild(of(121), null, null, null, null, null, null)), COORD, WU));
		assertTrue(e.getMessage().contains("Titles are at most 120 characters"), e.getMessage());
		assertTrue(agentTaskService.getTaskData(other.getUuid()).orElseThrow().getChildTasks().isEmpty(),
				"a refused split creates no child");
	}

	// ---------- both bundles and the snapshot ----------

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
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("desc-test").build();
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

	@Test
	@SuppressWarnings("unchecked")
	public void theDescriptionIsServedOnBothEndpointsAndInTheSnapshot() throws RelizaException {
		Rig r = rig();
		ApiKey key = apiKeyService.createObjectApiKey(r.org().getUuid(), ApiTypeEnum.FREEFORM, r.org().getUuid(),
				UUID.randomUUID().toString(), "descriptions", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(r.org().getUuid(),
				PermissionScope.ORGANIZATION, r.org().getUuid(), PermissionType.READ_WRITE,
				Set.of(PermissionFunction.AGENT, PermissionFunction.BOARD_READ, PermissionFunction.BOARD_AGENT,
						PermissionFunction.BOARD_WRITE), null)), WU);

		Map<String, Object> registered = data(asKey(key,
				"mutation($i: AgentTaskRegisterInput!) { agentTaskRegisterProgrammatic(input: $i) { uuid title description } }",
				Map.of("i", Map.of("boardUuid", r.board().getUuid().toString(), "title", "Keep titles short",
						"description", "Everything the card has no room for."))), "agentTaskRegisterProgrammatic");
		assertEquals("Everything the card has no room for.", registered.get("description"));
		String uuid = (String) registered.get("uuid");

		ExecutionResult tooLong = asKey(key,
				"mutation($i: AgentTaskRegisterInput!) { agentTaskRegisterProgrammatic(input: $i) { uuid } }",
				Map.of("i", Map.of("boardUuid", r.board().getUuid().toString(), "title", of(121))));
		assertFalse(tooLong.getErrors().isEmpty());
		assertTrue(tooLong.getErrors().toString().contains("Titles are at most 120 characters"), tooLong.getErrors().toString());

		assertEquals("Everything the card has no room for.", ((Map<String, Object>) data(asKey(key,
				"query($t: ID!) { agentTaskProgrammatic(taskUuid: $t) { description } }", Map.of("t", uuid)),
				"agentTaskProgrammatic")).get("description"));
		Map<String, Object> snapshot = data(asKey(key,
				"query($b: ID!) { agentBoardSnapshotProgrammatic(boardUuid: $b) { tasks { task { uuid description } } } }",
				Map.of("b", r.board().getUuid().toString())), "agentBoardSnapshotProgrammatic");
		assertTrue(((List<Map<String, Object>>) snapshot.get("tasks")).stream()
				.map(t -> (Map<String, Object>) t.get("task"))
				.anyMatch(t -> uuid.equals(t.get("uuid")) && "Everything the card has no room for.".equals(t.get("description"))),
				"the snapshot entry carries it");

		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Admin " + tag, "desc-" + tag + "@tasks.io", true, List.of(r.org().getUuid()),
				"desc-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), r.org().getUuid(), PermissionScope.ORGANIZATION, r.org().getUuid(),
				PermissionType.ADMIN, List.of(), null, WU);
		UserData admin = userService.getUserData(u.getUuid()).orElseThrow();
		assertEquals("Everything the card has no room for.", ((Map<String, Object>) data(asPerson(admin,
				"query($t: ID!) { agentTask(uuid: $t) { description } }", Map.of("t", uuid)), "agentTask")).get("description"));
		Map<String, Object> byPerson = data(asPerson(admin,
				"mutation($b: ID!, $i: AgentTaskUserRegisterInput!) { agentTaskRegister(boardUuid: $b, input: $i) { description } }",
				Map.of("b", r.board().getUuid().toString(), "i", Map.of("title", "Registered by a person",
						"description", "and described"))), "agentTaskRegister");
		assertEquals("and described", byPerson.get("description"));
	}
}
