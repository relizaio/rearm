/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentBoardData.GroupStatus;
import io.reliza.model.AgentBoardData.TaskGroup;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.TaskStatus;
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
import io.reliza.service.AgentIdentityService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AgentTaskGroupService;
import io.reliza.service.AgentTaskGroupService.GroupInput;
import io.reliza.service.AgentTaskService;
import io.reliza.service.AgentTaskService.WorkerAssignment;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Task groups (task-groups-and-tags.md §2, task RD2-29): a board's groups by key, their refusals, a
 * task's membership, the group gate at the poll and at assign, the group rung of the level, and the
 * reads on both endpoints.
 */
@SpringBootTest(classes = {App.class})
public class AgentTaskGroupsIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "operator");

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentTaskGroupService agentTaskGroupService;
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

	private Rig rig(Integer defaultWorkLevel) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("grp_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "grp-" + UUID.randomUUID(), "groups",
				List.of(), "coordinate", 4, null, target.getUuid(), defaultWorkLevel, WU);
		// Levels need a ladder (task RD3-6): ten rungs, so every level these tests use is on it.
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

	private AgentTaskData task(UUID uuid) {
		return agentTaskService.getTaskData(uuid).orElseThrow();
	}

	private TaskGroup group(Rig r, String key, String... dependsOn) throws RelizaException {
		return agentTaskGroupService.setGroup(r.board().getUuid(), new GroupInput(null, key, "Group " + key, null, null,
				List.of(dependsOn), null, false, null), WU);
	}

	private AgentTaskData register(Rig r, String group, Integer level) throws RelizaException {
		return agentTaskService.register(board(r.board().getUuid()), null, "task " + UUID.randomUUID(), null, null,
				null, null, null, level, null, COORD, false, group, null, WU);
	}

	private String message(Exception e) {
		return e.getMessage();
	}

	// ---------- 1. the board's groups ----------

	@Test
	public void groupsAreKeyedCheckedClosedAndDeletedOnlyWhenEmpty() throws RelizaException {
		Rig r = rig(null);
		TaskGroup basic = group(r, "Basic-Board");
		assertEquals("basic-board", basic.key(), "keys are lower-cased");
		assertEquals(1, basic.order(), "the first group is order 1");
		TaskGroup perms = group(r, "perms", "basic-board");
		assertEquals(2, perms.order(), "new groups append");
		assertEquals(List.of(basic.uuid()), perms.dependsOn());

		RelizaException self = assertThrows(RelizaException.class, () -> group(r, "perms", "perms"));
		assertEquals("group perms depends on itself", message(self));
		RelizaException cycle = assertThrows(RelizaException.class, () -> group(r, "basic-board", "perms"));
		assertEquals("group cycle: basic-board → perms → basic-board", message(cycle));
		RelizaException unknown = assertThrows(RelizaException.class, () -> group(r, "extra", "nowhere"));
		assertEquals("a group depends on nowhere, which is not a group of this board", message(unknown));
		RelizaException badKey = assertThrows(RelizaException.class, () -> group(r, "x"));
		assertTrue(message(badKey).startsWith("A group key is 2 to 24"), message(badKey));
		RelizaException taken = assertThrows(RelizaException.class, () -> agentTaskGroupService.setGroup(
				r.board().getUuid(), new GroupInput(perms.uuid(), "basic-board", null, null, null, null, null, false, null), WU));
		assertEquals("group basic-board exists on this board", message(taken));

		// An edit by key keeps what it does not name; a null level sent clears it.
		agentTaskGroupService.setGroup(r.board().getUuid(), new GroupInput(null, "perms", null, "the permissions batch",
				null, null, 3, true, null), WU);
		TaskGroup edited = board(r.board().getUuid()).groupByKey("perms").orElseThrow();
		assertEquals("Group perms", edited.name());
		assertEquals(3, edited.defaultWorkLevel());
		assertEquals(List.of(basic.uuid()), edited.dependsOn(), "dependencies not sent are kept");

		// An unreferenced key may change; a referenced one may not.
		agentTaskGroupService.setGroup(r.board().getUuid(), new GroupInput(perms.uuid(), "permissions", null, null, null,
				null, null, false, null), WU);
		assertTrue(board(r.board().getUuid()).groupByKey("permissions").isPresent());
		register(r, "basic-board", null);
		RelizaException renamed = assertThrows(RelizaException.class, () -> agentTaskGroupService.setGroup(
				r.board().getUuid(), new GroupInput(basic.uuid(), "basics", null, null, null, null, null, false, null), WU));
		assertEquals("group basic-board is referenced by 1 task; its key is immutable", message(renamed));

		// Delete: refused while a task names the group; an empty one goes, and its dependents let go of it.
		RelizaException held = assertThrows(RelizaException.class,
				() -> agentTaskGroupService.deleteGroup(r.board().getUuid(), "basic-board", WU));
		assertEquals("group basic-board holds 1 task; move them first", message(held));
		group(r, "scratch");
		group(r, "after-scratch", "scratch");
		agentTaskGroupService.deleteGroup(r.board().getUuid(), "scratch", WU);
		AgentBoardData after = board(r.board().getUuid());
		assertTrue(after.groupByKey("scratch").isEmpty());
		assertEquals(List.of(), after.groupByKey("after-scratch").orElseThrow().dependsOn());

		// Close refuses new members, and nothing else; reopen takes them again.
		agentTaskGroupService.setStatus(r.board().getUuid(), "basic-board", GroupStatus.CLOSED, WU);
		RelizaException closed = assertThrows(RelizaException.class, () -> register(r, "basic-board", null));
		assertEquals("group basic-board is CLOSED; reopen it or register elsewhere", message(closed));
		AgentTaskData outside = register(r, null, null);
		RelizaException moveIn = assertThrows(RelizaException.class,
				() -> agentTaskService.setGroup(outside.getUuid(), "basic-board", PERSON, WU));
		assertEquals("group basic-board is CLOSED; reopen it or register elsewhere", message(moveIn));
		agentTaskGroupService.setStatus(r.board().getUuid(), "basic-board", GroupStatus.OPEN, WU);
		register(r, "basic-board", null);
	}

	// ---------- 2. membership ----------

	@Test
	public void aTaskJoinsAGroupOrderedAfterItsLastTaskAndReadsItsLevel() throws RelizaException {
		Rig r = rig(1);
		group(r, "basic");
		agentTaskGroupService.setGroup(r.board().getUuid(), new GroupInput(null, "basic", null, null, null, null, 2, true,
				null), WU);
		RelizaException unknown = assertThrows(RelizaException.class, () -> register(r, "nope", null));
		assertEquals("no group nope on board " + r.board().getName()
				+ "; groups are created in the board file or with group set", message(unknown));

		AgentTaskData first = register(r, "basic", null);
		AgentTaskData second = register(r, "basic", null);
		assertEquals(0, first.getOrderIndex(), "the first task of an empty group");
		assertEquals(1, second.getOrderIndex(), "after the group's last task (D5)");
		agentTaskService.authorize(second.getUuid(), board(r.board().getUuid()), "coder", 50, null, null, null, null,
				COORD, WU);
		assertEquals(50, task(second.getUuid()).getOrderIndex(), "the coordinator's order wins");
		assertEquals(51, register(r, "basic", null).getOrderIndex(), "and the next one follows it");

		// Level: the task's, else its group's, else the board's.
		AgentBoardData b = board(r.board().getUuid());
		assertEquals(2, AgentTaskService.effectiveWorkLevel(task(first.getUuid()), b), "the group rung");
		assertEquals(4, AgentTaskService.effectiveWorkLevel(register(r, "basic", 4), b), "the task's own wins");
		assertEquals(1, AgentTaskService.effectiveWorkLevel(register(r, null, null), b), "ungrouped reads the board's");

		// Split children inherit the group, or name their own.
		group(r, "other");
		AgentTaskData parent = register(r, "basic", null);
		List<AgentTaskData> children = agentTaskService.split(parent.getUuid(), board(r.board().getUuid()),
				List.of(new SplitChild("inherits", null, null, null, null, null),
						new SplitChild("names its own", null, null, null, null, null, null, "other", null)), COORD, WU);
		UUID basic = b.groupByKey("basic").orElseThrow().uuid();
		assertEquals(basic, task(children.get(0).getUuid()).getGroup());
		assertEquals(board(r.board().getUuid()).groupByKey("other").orElseThrow().uuid(), task(children.get(1).getUuid()).getGroup());

		// Move, ungroup, and a finished task refused.
		AgentTaskData moved = agentTaskService.setGroup(first.getUuid(), "other", PERSON, WU);
		assertEquals(board(r.board().getUuid()).groupByKey("other").orElseThrow().uuid(), moved.getGroup());
		assertTrue(agentBoardService.recentEvents(board(r.board().getUuid()).getUuid()).stream().anyMatch(e -> e.message()
				.equals("Task " + moved.label() + " moved to group other by operator")), "the move is announced");
		assertNull(agentTaskService.setGroup(first.getUuid(), null, PERSON, WU).getGroup());
		assertTrue(agentBoardService.recentEvents(board(r.board().getUuid()).getUuid()).stream().anyMatch(e -> e.message()
				.equals("Task " + moved.label() + " ungrouped by operator")));
		agentTaskService.cancel(first.getUuid(), "done with it", COORD, WU);
		RelizaException finished = assertThrows(RelizaException.class,
				() -> agentTaskService.setGroup(first.getUuid(), "basic", PERSON, WU));
		assertEquals("Task " + moved.getKey() + " is CANCELLED; its group is history", message(finished));
	}

	// ---------- 3. the gate ----------

	private AgentSessionData workerSession(Rig r) throws RelizaException {
		AgentData worker = agentService.findOrRegisterRootAgent(r.org().getUuid(), UUID.randomUUID(),
				"grp-" + UUID.randomUUID(), null, null, null, WU);
		return agentSessionService.initialize(r.org().getUuid(), worker.getUuid(), null, "grp-" + UUID.randomUUID(),
				"worker", null, null, WU);
	}

	private Optional<WorkerAssignment> next(Rig r, AgentSessionData s) {
		return agentTaskService.next(List.of(board(r.board().getUuid())), s.getAgent(), s.getUuid());
	}

	@Test
	public void aDependentGroupWaitsForTheGroupItDependsOn() throws RelizaException {
		Rig r = rig(null);
		group(r, "first");
		group(r, "second", "first");
		AgentTaskData upstream = register(r, "first", null);
		AgentTaskData downstream = register(r, "second", null);
		AgentBoardData b = board(r.board().getUuid());
		// Authorize is not gated (D4): the coordinator lines the work up; the downstream task is ordered first.
		agentTaskService.authorize(downstream.getUuid(), b, "coder", 1, null, null, null, null, COORD, WU);
		agentTaskService.authorize(upstream.getUuid(), b, "coder", 20, null, null, null, null, COORD, WU);
		assertEquals(TaskStatus.QUEUED, task(downstream.getUuid()).getStatus());
		assertEquals(List.of("first"), agentTaskService.waitingOnGroups(task(downstream.getUuid()), board(r.board().getUuid()), null));

		AgentSessionData s = workerSession(r);
		assertEquals(upstream.getUuid(), next(r, s).orElseThrow().task().getUuid(),
				"the downstream task is not offered while its upstream group has an open task");
		RelizaException refused = assertThrows(RelizaException.class, () -> agentTaskService.assign(downstream.getUuid(),
				board(r.board().getUuid()), s.getAgent(), s.getUuid(), WU));
		assertEquals("task " + task(downstream.getUuid()).getKey() + " waits on group first", message(refused));

		agentTaskService.cancel(upstream.getUuid(), "not needed", COORD, WU);
		assertEquals(List.of(), agentTaskService.waitingOnGroups(task(downstream.getUuid()), board(r.board().getUuid()), null));
		assertEquals(downstream.getUuid(), next(r, s).orElseThrow().task().getUuid(),
				"offered once the upstream group has nothing open");
	}

	@Test
	public void groupsDoNotReorderThePoll() throws RelizaException {
		// D5: a group is not a priority. An ungrouped task ordered first is offered before a grouped one.
		Rig r = rig(null);
		group(r, "batch");
		AgentTaskData grouped = register(r, "batch", null);
		AgentTaskData loose = register(r, null, null);
		AgentBoardData b = board(r.board().getUuid());
		agentTaskService.authorize(grouped.getUuid(), b, "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.authorize(loose.getUuid(), b, "coder", 5, null, null, null, null, COORD, WU);
		assertEquals(loose.getUuid(), next(r, workerSession(r)).orElseThrow().task().getUuid());
	}

	// ---------- 4. both endpoints ----------

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
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("groups-test").build();
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

	private record Caller(ApiKey key, AgentData agent, AgentSessionData session) {}

	private Caller caller(Rig r, PermissionFunction... fns) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(r.org().getUuid(), ApiTypeEnum.FREEFORM, r.org().getUuid(),
				UUID.randomUUID().toString(), "groups", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(r.org().getUuid(),
				PermissionScope.ORGANIZATION, r.org().getUuid(), PermissionType.READ_WRITE, Set.of(fns), null)), WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(r.org().getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(r.org().getUuid(), identity, "grp-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(r.org().getUuid(), agent.getUuid(), key.getUuid(),
				"grp-" + UUID.randomUUID(), "groups", null, null, WU);
		return new Caller(key, agent, s);
	}

	private UserData admin(Rig r) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Grp " + tag, "grp-" + tag + "@tasks.io", true, List.of(r.org().getUuid()),
				"grp-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), r.org().getUuid(), PermissionScope.ORGANIZATION, r.org().getUuid(),
				PermissionType.ADMIN, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private static final String GROUP_SET_P = "mutation($b: ID!, $s: ID!, $g: TaskGroupInput!) { agentBoardGroupSetProgrammatic(boardUuid: $b, sessionUuid: $s, group: $g) { key order dependsOn defaultWorkLevel status progress { total done open complete } } }";
	private static final String REGISTER_P = "mutation($i: AgentTaskRegisterInput!) { agentTaskRegisterProgrammatic(input: $i) { uuid key orderIndex group { key name } tags { key value removable } waitingOnGroups effectiveWorkLevel } }";
	private static final String SET_GROUP_P = "mutation($t: ID!, $s: ID!, $g: String) { agentTaskSetGroupProgrammatic(taskUuid: $t, sessionUuid: $s, group: $g) { group { key } } }";

	@Test
	@SuppressWarnings("unchecked")
	public void theSeatAndAPersonWorkGroupsOnBothEndpoints() throws RelizaException {
		Rig r = rig(1);
		Caller seat = caller(r, PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		agentBoardService.claimCoordinatorSeat(r.board().getUuid(), seat.session().getUuid(), seat.agent().getUuid(), WU);
		String b = r.board().getUuid().toString();
		String s = seat.session().getUuid().toString();

		Map<String, Object> first = data(asKey(seat.key(), GROUP_SET_P, Map.of("b", b, "s", s,
				"g", Map.of("key", "first", "name", "First batch", "defaultWorkLevel", 2))), "agentBoardGroupSetProgrammatic");
		assertEquals("first", first.get("key"));
		assertEquals(2, first.get("defaultWorkLevel"));
		assertEquals("OPEN", first.get("status"));
		Map<String, Object> second = data(asKey(seat.key(), GROUP_SET_P, Map.of("b", b, "s", s,
				"g", Map.of("key", "second", "dependsOn", List.of("first")))), "agentBoardGroupSetProgrammatic");
		assertEquals(List.of("first"), second.get("dependsOn"), "dependencies read back as keys");

		Map<String, Object> up = data(asKey(seat.key(), REGISTER_P, Map.of("i", Map.of("boardUuid", b, "sessionUuid", s,
				"title", "upstream", "group", "first", "tags", List.of(Map.of("key", "Client-Req", "value", "REQ-7"))))),
				"agentTaskRegisterProgrammatic");
		assertEquals("first", ((Map<String, Object>) up.get("group")).get("key"));
		assertEquals(2, up.get("effectiveWorkLevel"), "the group rung, served");
		List<Map<String, Object>> tags = (List<Map<String, Object>>) up.get("tags");
		assertEquals("client-req", tags.get(0).get("key"));
		assertEquals("REQ-7", tags.get(0).get("value"), "the value is stored and served");
		assertEquals("YES", tags.get(0).get("removable"));
		Map<String, Object> down = data(asKey(seat.key(), REGISTER_P, Map.of("i", Map.of("boardUuid", b, "sessionUuid", s,
				"title", "downstream", "group", "second"))), "agentTaskRegisterProgrammatic");
		assertEquals(List.of("first"), down.get("waitingOnGroups"));

		// The board's groups: in order, with progress.
		Map<String, Object> read = data(asKey(seat.key(), "query($b: ID!) { agentBoardProgrammatic(boardUuid: $b) { groups { key order progress { total done open complete } spentMicros } } }",
				Map.of("b", b)), "agentBoardProgrammatic");
		List<Map<String, Object>> groups = (List<Map<String, Object>>) read.get("groups");
		assertEquals(List.of("first", "second"), groups.stream().map(g -> g.get("key")).toList());
		assertEquals(Map.of("total", 1, "done", 0, "open", 1, "complete", false), groups.get(0).get("progress"));
		assertEquals(0L, ((Number) groups.get(0).get("spentMicros")).longValue(), "nothing spent yet");

		// The snapshot entry carries it too.
		Map<String, Object> snap = data(asKey(seat.key(),
				"query($b: ID!) { agentBoardSnapshotProgrammatic(boardUuid: $b) { tasks { task { uuid group { key } waitingOnGroups } } } }",
				Map.of("b", b)), "agentBoardSnapshotProgrammatic");
		assertTrue(((List<Map<String, Object>>) snap.get("tasks")).stream().map(x -> (Map<String, Object>) x.get("task"))
				.anyMatch(x -> down.get("uuid").equals(x.get("uuid")) && List.of("first").equals(x.get("waitingOnGroups"))));

		// The list filters: by group, and by tag.
		List<Map<String, Object>> inFirst = data(asKey(seat.key(),
				"query($b: ID!) { agentTasksProgrammatic(boardUuid: $b, group: \"first\") { uuid } }", Map.of("b", b)),
				"agentTasksProgrammatic");
		assertEquals(List.of(up.get("uuid")), inFirst.stream().map(x -> x.get("uuid")).toList());
		List<Map<String, Object>> tagged = data(asKey(seat.key(),
				"query($b: ID!) { agentTasksProgrammatic(boardUuid: $b, tag: [\"client-req\", \"other\"]) { uuid } }", Map.of("b", b)),
				"agentTasksProgrammatic");
		assertEquals(List.of(up.get("uuid")), tagged.stream().map(x -> x.get("uuid")).toList());

		// The seat moves a task; a worker key is refused.
		assertEquals("second", ((Map<String, Object>) ((Map<String, Object>) data(asKey(seat.key(), SET_GROUP_P,
				Map.of("t", up.get("uuid"), "s", s, "g", "second")), "agentTaskSetGroupProgrammatic")).get("group")).get("key"));
		Caller worker = caller(r, PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		ExecutionResult refused = asKey(worker.key(), SET_GROUP_P, Map.of("t", up.get("uuid"),
				"s", worker.session().getUuid().toString(), "g", "first"));
		assertFalse(refused.getErrors().isEmpty(), "a worker key is refused");
		assertTrue(refused.getErrors().toString().contains("BOARD_WRITE"), refused.getErrors().toString());

		// A person: configures a group, moves a task out, and deletes an empty group.
		UserData admin = admin(r);
		Map<String, Object> closed = data(asPerson(admin, "mutation($b: ID!, $g: TaskGroupInput!) { agentBoardGroupSet(boardUuid: $b, group: $g) { key status } }",
				Map.of("b", b, "g", Map.of("key", "second", "status", "CLOSED"))), "agentBoardGroupSet");
		assertEquals("CLOSED", closed.get("status"));
		Map<String, Object> ungroupVars = new java.util.HashMap<>(Map.of("t", up.get("uuid")));
		ungroupVars.put("g", null);
		assertNull(((Map<String, Object>) data(asPerson(admin, "mutation($t: ID!, $g: String) { agentTaskSetGroup(taskUuid: $t, group: $g) { group { key } } }",
				ungroupVars), "agentTaskSetGroup")).get("group"));
		data(asPerson(admin, "mutation($b: ID!, $g: TaskGroupInput!) { agentBoardGroupSet(boardUuid: $b, group: $g) { key } }",
				Map.of("b", b, "g", Map.of("key", "empty-one"))), "agentBoardGroupSet");
		assertEquals(true, data(asPerson(admin, "mutation($b: ID!) { agentBoardGroupDelete(boardUuid: $b, key: \"empty-one\") }",
				Map.of("b", b)), "agentBoardGroupDelete"));
		ExecutionResult held = asPerson(admin, "mutation($b: ID!) { agentBoardGroupDelete(boardUuid: $b, key: \"second\") }",
				Map.of("b", b));
		assertTrue(held.getErrors().toString().contains("group second holds 1 task; move them first"), held.getErrors().toString());
		assertNotEquals(null, board(r.board().getUuid()).groupByKey("second").orElse(null));
	}

	@Test
	public void tagsAreNormalisedLimitedAndReplaced() throws RelizaException {
		Rig r = rig(null);
		AgentTaskData t = agentTaskService.register(board(r.board().getUuid()), null, "tagged", null, null, null, null,
				null, null, null, COORD, false, null, List.of(new TagRecord(" Sandbox-Only ", null),
						new TagRecord("req.7_a", "REQ-7")), WU);
		assertEquals(List.of("sandbox-only", "req.7_a"), t.getTags().stream().map(TagRecord::key).toList());
		assertEquals("REQ-7", t.getTags().get(1).value());

		RelizaException twice = assertThrows(RelizaException.class, () -> agentTaskService.setTags(t.getUuid(),
				List.of(new TagRecord("a", null), new TagRecord("A", null)), WU));
		assertEquals("Tag a is given twice", message(twice));
		RelizaException bad = assertThrows(RelizaException.class, () -> agentTaskService.setTags(t.getUuid(),
				List.of(new TagRecord("-dash-first", null)), WU));
		assertTrue(message(bad).startsWith("A tag is 1 to 40"), message(bad));
		RelizaException tooLong = assertThrows(RelizaException.class, () -> agentTaskService.setTags(t.getUuid(),
				List.of(new TagRecord("x".repeat(41), null)), WU));
		assertTrue(message(tooLong).startsWith("A tag is 1 to 40"), message(tooLong));
		List<TagRecord> many = new java.util.ArrayList<>();
		for (int i = 0; i < 21; i++) many.add(new TagRecord("t" + i, null));
		RelizaException tooMany = assertThrows(RelizaException.class, () -> agentTaskService.setTags(t.getUuid(), many, WU));
		assertEquals("A task carries at most 20 tags (got 21)", message(tooMany));

		assertEquals(List.of("only"), agentTaskService.setTags(t.getUuid(), List.of(new TagRecord("only", null)), WU)
				.getTags().stream().map(TagRecord::key).toList(), "set replaces the list");

		// Split children inherit the parent's tags unless they name their own.
		List<AgentTaskData> children = agentTaskService.split(t.getUuid(), board(r.board().getUuid()),
				List.of(new SplitChild("inherits", null, null, null, null, null),
						new SplitChild("own", null, null, null, null, null, null, null, List.of(new TagRecord("mine", null)))),
				COORD, WU);
		assertEquals(List.of("only"), task(children.get(0).getUuid()).getTags().stream().map(TagRecord::key).toList());
		assertEquals(List.of("mine"), task(children.get(1).getUuid()).getTags().stream().map(TagRecord::key).toList());

		agentTaskService.cancel(children.get(0).getUuid(), "not needed", COORD, WU);
		RelizaException finished = assertThrows(RelizaException.class,
				() -> agentTaskService.setTags(children.get(0).getUuid(), List.of(), WU));
		assertTrue(message(finished).endsWith("is CANCELLED; its tags are history"), message(finished));
	}
}
