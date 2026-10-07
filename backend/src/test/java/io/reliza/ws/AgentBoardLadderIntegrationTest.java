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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import com.netflix.graphql.dgs.DgsQueryExecutor;
import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.exceptions.RelizaException;
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
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AgentTaskGroupService;
import io.reliza.service.AgentTaskGroupService.GroupInput;
import io.reliza.service.AgentTaskService;
import io.reliza.service.AgentTaskService.WorkerAssignment;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * The level ladder, opt-in per board (task RD3-6): without one, levels stay null and every way of setting one
 * is refused; with one, the default is 0, a level off it is refused naming it, and every served prompt carries
 * the ladder section with the board's levels rendered in.
 */
@SpringBootTest(classes = {App.class})
@SuppressWarnings("unchecked")
public class AgentBoardLadderIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "operator");
	private static final String LADDER_HEADING = "## This board's level ladder";

	@MockitoSpyBean private UserService userService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentTaskGroupService agentTaskGroupService;
	@Autowired private ComponentService componentService;
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

	/** Three rungs, the second with a description. */
	private static Map<String, Object> threeRungs() {
		return Map.of("levels", List.of(Map.of("name", "requirements"),
				Map.of("name", "solution", "description", "the decisions that meet them"), Map.of("name", "components")));
	}

	/** A board with a coder role and the given default level; with the given ladder, or none when null. */
	private Rig rig(Integer defaultWorkLevel, Map<String, Object> ladder) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("ldr_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "ldr-" + UUID.randomUUID(), "ladder",
				List.of(), "coordinate", 4, null, target.getUuid(), defaultWorkLevel, WU);
		if (null != ladder) agentBoardService.updateSettingsFromInput(board.getUuid(), Map.of("ladder", ladder), WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics("coder", "build it", 10,
				null, false, true, null), true, WU);
		return new Rig(org, board(board.getUuid()));
	}

	private AgentBoardData board(UUID uuid) {
		return agentBoardService.getBoardData(uuid).orElseThrow();
	}

	private AgentBoardData board(Rig r) {
		return board(r.board().getUuid());
	}

	private AgentTaskData register(Rig r, Integer level) throws RelizaException {
		return agentTaskService.register(board(r), null, "task " + UUID.randomUUID(), null, null,
				null, null, null, level, null, COORD, false, WU);
	}

	private AgentTaskData task(UUID uuid) {
		return agentTaskService.getTaskData(uuid).orElseThrow();
	}

	private static GroupInput groupAt(String key, Integer defaultWorkLevel) {
		return new GroupInput(null, key, "Group " + key, null, null, List.of(), defaultWorkLevel, true, null);
	}

	private WorkerAssignment poll(Rig r) throws RelizaException {
		AgentData worker = agentService.findOrRegisterRootAgent(r.org().getUuid(), UUID.randomUUID(),
				"ldr-" + UUID.randomUUID(), null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(r.org().getUuid(), worker.getUuid(), null,
				"ldr-" + UUID.randomUUID(), "worker", null, null, WU);
		return agentTaskService.next(List.of(board(r)), worker.getUuid(), s.getUuid()).orElseThrow();
	}

	// ---------- 1. without a ladder ----------

	@Test
	public void withoutALadderEveryLevelIsRefusedAndReadsNull() throws RelizaException {
		Rig r = rig(3, null);
		assertFalse(board(r).hasLadder());
		RelizaException atRegister = assertThrows(RelizaException.class, () -> register(r, 1));
		assertEquals(AgentTaskService.NO_LADDER, atRegister.getMessage());
		assertTrue(AgentTaskService.NO_LADDER.startsWith("this board has no ladder"), AgentTaskService.NO_LADDER);

		AgentTaskData t = register(r, null);
		assertNull(AgentTaskService.effectiveWorkLevel(task(t.getUuid()), board(r)),
				"the board's defaultWorkLevel 3 is ignored without a ladder");
		RelizaException atAuthorize = assertThrows(RelizaException.class, () -> agentTaskService.authorize(t.getUuid(),
				board(r), "coder", 1, null, null, 2, null, COORD, WU));
		assertEquals(AgentTaskService.NO_LADDER, atAuthorize.getMessage());
		assertNull(task(t.getUuid()).getWorkLevel(), "nothing is written");
		agentTaskService.authorize(t.getUuid(), board(r), "coder", 1, null, null, null, null, COORD, WU);

		assertEquals(AgentTaskService.NO_LADDER, assertThrows(RelizaException.class,
				() -> agentTaskService.setWorkLevel(t.getUuid(), 0, PERSON, WU)).getMessage());
		assertNull(agentTaskService.setWorkLevel(t.getUuid(), null, PERSON, WU).getWorkLevel(), "clearing is not a level");

		RelizaException atGroup = assertThrows(RelizaException.class,
				() -> agentTaskGroupService.setGroup(r.board().getUuid(), groupAt("deep", 1), WU));
		assertEquals(AgentTaskService.NO_LADDER, atGroup.getMessage());
		assertNull(agentTaskGroupService.setGroup(r.board().getUuid(), groupAt("plain", null), WU).defaultWorkLevel());
	}

	@Test
	public void withoutALadderThePollIgnoresALevelKeptFromBefore() throws RelizaException {
		Rig r = rig(null, null);
		AgentTaskData kept = register(r, null);
		AgentTaskData plain = register(r, null);
		// A level from before the ladder was opt-in (D11: no migration), written as the old code did.
		AgentTaskData withLevel = task(kept.getUuid());
		withLevel.setWorkLevel(0);
		agentTaskService.saveData(withLevel, WU);
		agentTaskService.authorize(kept.getUuid(), board(r), "coder", 20, null, null, null, null, COORD, WU);
		agentTaskService.authorize(plain.getUuid(), board(r), "coder", 1, null, null, null, null, COORD, WU);
		assertNull(AgentTaskService.effectiveWorkLevel(task(kept.getUuid()), board(r)), "the kept level reads null");
		assertEquals(plain.getUuid(), poll(r).task().getUuid(), "the order decides, not the kept level 0");
	}

	// ---------- 2. with a ladder ----------

	@Test
	public void withALadderTheDefaultIsZeroAndALevelOffItIsRefusedNamingIt() throws RelizaException {
		Rig r = rig(null, threeRungs());
		assertEquals(List.of(0, 1, 2), board(r).getLadder().levels().stream().map(AgentBoardData.LadderLevel::number).toList());
		AgentTaskData t = register(r, null);
		assertNull(task(t.getUuid()).getWorkLevel(), "the default is read, not written");
		assertEquals(0, AgentTaskService.effectiveWorkLevel(task(t.getUuid()), board(r)));
		assertEquals(2, register(r, 2).getWorkLevel());

		String ladder = "0 requirements, 1 solution, 2 components";
		assertEquals("level 3 is not on this board's ladder: " + ladder,
				assertThrows(RelizaException.class, () -> register(r, 3)).getMessage());
		assertEquals("level 3 is not on this board's ladder: " + ladder, assertThrows(RelizaException.class,
				() -> agentTaskService.authorize(t.getUuid(), board(r), "coder", 1, null, null, 3, null, COORD, WU)).getMessage());
		assertEquals("level -1 is not on this board's ladder: " + ladder, assertThrows(RelizaException.class,
				() -> agentTaskService.setWorkLevel(t.getUuid(), -1, PERSON, WU)).getMessage());
		assertEquals("level 5 is not on this board's ladder: " + ladder, assertThrows(RelizaException.class,
				() -> agentTaskGroupService.setGroup(r.board().getUuid(), groupAt("deep", 5), WU)).getMessage());

		agentTaskService.authorize(t.getUuid(), board(r), "coder", 1, null, null, 1, null, COORD, WU);
		assertEquals(1, task(t.getUuid()).getWorkLevel(), "the coordinator sets it at authorize");
		assertEquals(2, agentTaskGroupService.setGroup(r.board().getUuid(), groupAt("comp", 2), WU).defaultWorkLevel());
	}

	@Test
	public void theLadderCannotDropALevelATaskOrGroupCarries() throws RelizaException {
		Rig r = rig(null, threeRungs());
		AgentTaskData t = register(r, 2);
		RelizaException shrink = assertThrows(RelizaException.class, () -> agentBoardService.updateSettingsFromInput(
				r.board().getUuid(), Map.of("ladder", Map.of("levels", List.of(Map.of("name", "a"), Map.of("name", "b")))), WU));
		assertEquals("settings.ladder cannot drop below 2 level(s) while 1 task(s) or group(s) carry a level it does not have: "
				+ task(t.getUuid()).label() + " (level 2); clear the levels first", shrink.getMessage());
		agentTaskService.setWorkLevel(t.getUuid(), 0, PERSON, WU);
		agentTaskGroupService.setGroup(r.board().getUuid(), groupAt("comp", 1), WU);
		Map<String, Object> removal = new HashMap<>();
		removal.put("ladder", null);
		RelizaException remove = assertThrows(RelizaException.class,
				() -> agentBoardService.updateSettingsFromInput(r.board().getUuid(), removal, WU));
		assertTrue(remove.getMessage().startsWith("settings.ladder cannot be removed while 2 task(s) or group(s)"), remove.getMessage());
		assertTrue(remove.getMessage().contains("group comp (default level 1)"), remove.getMessage());
		assertEquals(3, board(r).getLadder().levels().size(), "nothing refused is written");

		agentTaskService.setWorkLevel(t.getUuid(), null, PERSON, WU);
		agentTaskGroupService.setGroup(r.board().getUuid(), groupAt("comp", null), WU);
		assertNull(agentBoardService.updateSettingsFromInput(r.board().getUuid(), removal, WU).getLadder());
		assertNull(AgentTaskService.effectiveWorkLevel(task(t.getUuid()), board(r)), "back to no levels");
	}

	@Test
	public void aLadderDeclaredOnABoardWithTasksGivesThemTheDefault() throws RelizaException {
		Rig r = rig(1, null);
		AgentTaskData t = register(r, null);
		assertNull(AgentTaskService.effectiveWorkLevel(task(t.getUuid()), board(r)));
		agentBoardService.updateSettingsFromInput(r.board().getUuid(), Map.of("ladder", threeRungs()), WU);
		assertEquals(1, AgentTaskService.effectiveWorkLevel(task(t.getUuid()), board(r)), "the board's default, now read");
	}

	@Test
	public void thePollOnALadderBoardPrefersTheLowerLevel() throws RelizaException {
		Rig r = rig(null, threeRungs());
		AgentTaskData deeper = register(r, 1);
		AgentTaskData byDefault = register(r, null);
		agentTaskService.authorize(deeper.getUuid(), board(r), "coder", 1, null, null, null, null, COORD, WU);
		agentTaskService.authorize(byDefault.getUuid(), board(r), "coder", 20, null, null, null, null, COORD, WU);
		WorkerAssignment offered = poll(r);
		assertEquals(byDefault.getUuid(), offered.task().getUuid(), "the default 0 is below 1, whatever the order");
		assertTrue(offered.rolePrompt().contains(LADDER_HEADING), offered.rolePrompt());
		assertEquals(AgentBoardService.promptVersion(board(r), "build it"), offered.promptVersion());
	}

	// ---------- 3. the served prompts ----------

	@Test
	public void theServedPromptsCarryTheLadderSectionOnALadderBoardOnly() throws RelizaException {
		Rig plain = rig(null, null);
		AgentBoardData none = board(plain);
		assertNull(AgentBoardService.ladderSection(none));
		assertFalse(agentBoardService.servedPromptFor(none, "build it").contains(LADDER_HEADING));
		assertFalse(agentBoardService.servedCoordinatorPrompt(none).contains(LADDER_HEADING));
		assertEquals(AgentBoardService.promptVersion("build it"), AgentBoardService.promptVersion(none, "build it"),
				"boards without a ladder keep their prompt versions");

		Rig r = rig(null, threeRungs());
		AgentBoardData b = board(r);
		String section = AgentBoardService.ladderSection(b);
		assertTrue(section.startsWith(LADDER_HEADING), section);
		assertTrue(section.contains("- 0 · requirements\n- 1 · solution — the decisions that meet them\n- 2 · components"), section);
		assertTrue(section.contains("Levels are 0 to 2 on this board."), section);
		assertFalse(section.contains("{{"), section);
		String served = agentBoardService.servedPromptFor(b, "build it");
		assertTrue(served.startsWith("build it\n\n"), served);
		assertTrue(served.endsWith(section), "appended after the routing rules: " + served);
		assertTrue(agentBoardService.servedCoordinatorPrompt(b).endsWith(section));
		assertTrue(agentBoardService.servedCoordinatorPrompt(b).startsWith("coordinate"));
		assertNotEquals(AgentBoardService.promptVersion("build it"), AgentBoardService.promptVersion(b, "build it"),
				"a hop records which ladder text it read");

		// The board's own text: the levels and the last rendered in; without {{levels}} they follow it.
		Map<String, Object> own = new HashMap<>(threeRungs());
		own.put("prompt", "## Our ladder\nUp to {{last}}:\n{{levels}}");
		String before = AgentBoardService.promptVersion(b, "build it");
		agentBoardService.updateSettingsFromInput(r.board().getUuid(), Map.of("ladder", own), WU);
		String overridden = AgentBoardService.ladderSection(board(r));
		assertEquals("## Our ladder\nUp to 2:\n- 0 · requirements\n- 1 · solution — the decisions that meet them\n- 2 · components",
				overridden);
		assertNotEquals(before, AgentBoardService.promptVersion(board(r), "build it"));
		own.put("prompt", "Mind the ladder.");
		agentBoardService.updateSettingsFromInput(r.board().getUuid(), Map.of("ladder", own), WU);
		assertTrue(AgentBoardService.ladderSection(board(r)).startsWith("Mind the ladder.\n\n- 0 · requirements"),
				AgentBoardService.ladderSection(board(r)));
	}

	// ---------- 4. the form ----------

	private UserData admin(Rig r) throws RelizaException {
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Ldr " + tag, "ldr-" + tag + "@tasks.io", true, List.of(r.org().getUuid()),
				"ldr-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), r.org().getUuid(), PermissionScope.ORGANIZATION, r.org().getUuid(),
				PermissionType.ADMIN, List.of(), null, WU);
		return userService.getUserData(u.getUuid()).orElseThrow();
	}

	private ExecutionResult asPerson(UserData user, String query, Map<String, Object> vars) {
		doReturn(Optional.of(user)).when(userService).getUserDataByAuth(any());
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("ladder-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
		return dgsQueryExecutor.execute(query, vars);
	}

	private static <T> T data(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (T) ((Map<String, Object>) r.getData()).get(field);
	}

	private static final String UPDATE = "mutation($b: ID!, $i: AgentBoardInput!) { agentBoardUpdate(boardUuid: $b, input: $i)"
			+ " { defaultWorkLevel ladder { levels { number name description } prompt } } }";

	private static List<Map<String, Object>> rungs(int n) {
		List<Map<String, Object>> out = new ArrayList<>();
		for (int i = 0; i < n; i++) out.add(Map.of("name", "r" + i));
		return out;
	}

	@Test
	public void theFormSavesTheLadderAndTheDefaultTogether() throws RelizaException {
		Rig r = rig(1, Map.of("levels", rungs(2)));
		UserData admin = admin(r);
		String b = r.board().getUuid().toString();

		// Grows the ladder and raises the default in one save: neither write refuses the other.
		Map<String, Object> grown = data(asPerson(admin, UPDATE, Map.of("b", b, "i", Map.of("defaultWorkLevel", 3,
				"settings", Map.of("ladder", Map.of("levels", rungs(4)))))), "agentBoardUpdate");
		assertEquals(3, grown.get("defaultWorkLevel"));
		List<Map<String, Object>> levels = (List<Map<String, Object>>) ((Map<String, Object>) grown.get("ladder")).get("levels");
		assertEquals(List.of(0, 1, 2, 3), levels.stream().map(l -> l.get("number")).toList());

		// Shrinks it and lowers the default in one save.
		Map<String, Object> shrunk = data(asPerson(admin, UPDATE, Map.of("b", b, "i", Map.of("defaultWorkLevel", 0,
				"settings", Map.of("ladder", Map.of("levels", rungs(1)))))), "agentBoardUpdate");
		assertEquals(0, shrunk.get("defaultWorkLevel"));

		// A default off the ladder the board will have is refused, and nothing is written.
		ExecutionResult off = asPerson(admin, UPDATE, Map.of("b", b, "i", Map.of("description", "not saved",
				"defaultWorkLevel", 2)));
		assertTrue(off.getErrors().toString().contains("defaultWorkLevel 2 is not on the board's ladder: 0 r0"),
				off.getErrors().toString());
		assertEquals(0, board(r).getDefaultWorkLevel());
		assertEquals("ladder", board(r).getDescription());

		// Without a ladder the default is kept as given and ignored.
		Map<String, Object> removal = new HashMap<>();
		removal.put("ladder", null);
		Map<String, Object> noLadder = data(asPerson(admin, UPDATE, Map.of("b", b, "i", Map.of("defaultWorkLevel", 5,
				"settings", removal))), "agentBoardUpdate");
		assertNull(noLadder.get("ladder"));
		assertEquals(5, noLadder.get("defaultWorkLevel"));
	}
}
