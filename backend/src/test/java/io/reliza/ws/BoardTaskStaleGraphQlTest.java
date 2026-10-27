/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.TaskAssignment;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.UserData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.ComponentService;
import io.reliza.service.StatementCounter;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;
import jakarta.persistence.EntityManagerFactory;

/**
 * AgentTask.stale through GraphQL itself (task TPF-69): the board tasks read and the single reads carry the
 * live hopNoProgress mark of a backdated assignment and nothing for a fresh one or on a board without a staleness
 * block; and the field's loader keeps a board read's statement count flat in the number of stalled tasks. Only
 * who is asking is mocked (the AgentBoardGraphQlTest rig); the boards, tasks and rules are real.
 */
@SpringBootTest(classes = {App.class})
public class BoardTaskStaleGraphQlTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());

	@MockitoBean private UserService userService;
	@MockitoBean private AuthorizationService authorizationService;
	@Autowired private DgsQueryExecutor dgsQueryExecutor;
	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private ComponentService componentService;
	@Autowired private EntityManagerFactory emf;

	@BeforeEach
	void signIn() {
		UserData user = mock(UserData.class);
		when(user.getUuid()).thenReturn(UUID.randomUUID());
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(user));
		try {
			when(authorizationService.userOnBoard(any(), any(), any(), any(), any())).thenReturn(WU);
		} catch (RelizaException e) {
			throw new IllegalStateException(e);
		}
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("stale-test").build();
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(new JwtAuthenticationToken(jwt, List.of()));
		SecurityContextHolder.setContext(ctx);
	}

	@AfterEach
	void signOut() {
		SecurityContextHolder.clearContext();
	}

	private record Board(Organization org, AgentBoardData board, List<AgentTaskData> stalled, AgentTaskData fresh) {}

	/** A board whose {@code stalled} tasks were assigned 70 minutes ago and one task assigned now. */
	private Board board(boolean watched, int stalled) throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("stale_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData bd = agentBoardService.createBoard(org.getUuid(), "stale-" + UUID.randomUUID(), "stale marks",
				List.of(), "coordinate", 50, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(bd, AgentBoardService.RoleConfigSpec.ofBasics("coder", "build", 10,
				null, false, true, null), true, WU);
		if (watched) {
			Map<String, Object> block = new LinkedHashMap<>();
			block.put("hopNoProgressMinutes", 60);
			Map<String, Object> settings = new LinkedHashMap<>();
			settings.put("staleness", block);
			agentBoardService.updateSettingsFromInput(bd.getUuid(), settings, WU);
		}
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(), "st-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentSessionData session = agentSessionService.initialize(org.getUuid(), agent.getUuid(), null,
				"s-" + UUID.randomUUID(), "session", null, null, WU);
		List<AgentTaskData> backdated = new ArrayList<>();
		for (int i = 0; i < stalled; i++) {
			AgentTaskData td = assigned(bd, agent, session);
			TaskAssignment a = td.getAssignment();
			td.setAssignment(new TaskAssignment(a.session(), a.agent(), a.role(), a.roleUuid(),
					ZonedDateTime.now().minusMinutes(70), a.promptVersion(), a.resolvedInputs(), a.prHeads(), a.baseHeads(),
					a.linkedHeads()));
			backdated.add(agentTaskService.saveData(td, WU));
		}
		AgentTaskData fresh = assigned(bd, agent, session);
		return new Board(org, agentBoardService.getBoardData(bd.getUuid()).orElseThrow(), backdated, fresh);
	}

	private AgentTaskData assigned(AgentBoardData bd, AgentData agent, AgentSessionData session) throws RelizaException {
		AgentBoardData current = agentBoardService.getBoardData(bd.getUuid()).orElseThrow();
		AgentTaskData t = agentTaskService.register(current, null, "stale " + UUID.randomUUID(), null, null, null,
				null, null, null, COORD, true, WU);
		agentTaskService.authorize(t.getUuid(), current, "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), current, agent.getUuid(), session.getUuid(), WU);
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	private static final String BOARD_TASKS = "query($b: ID!) { agentTasksOfBoard(boardUuid: $b) "
			+ "{ uuid status stale { rule since minutes message } } }";

	@SuppressWarnings("unchecked")
	private static <T> T data(ExecutionResult r, String field) {
		assertTrue(r.getErrors().isEmpty(), "no errors: " + r.getErrors());
		Map<String, Object> data = r.getData();
		assertNotNull(data, "data");
		return (T) data.get(field);
	}

	@SuppressWarnings("unchecked")
	private Map<String, List<Map<String, Object>>> staleByTask(AgentBoardData bd) {
		List<Map<String, Object>> tasks = data(dgsQueryExecutor.execute(BOARD_TASKS, Map.of("b", bd.getUuid().toString())),
				"agentTasksOfBoard");
		Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
		for (Map<String, Object> t : tasks) out.put((String) t.get("uuid"), (List<Map<String, Object>>) t.get("stale"));
		return out;
	}

	@Test
	public void theBoardTasksReadCarriesTheMarkOfAStalledHopOnly() throws RelizaException {
		Board b = board(true, 2);
		Map<String, List<Map<String, Object>>> stale = staleByTask(b.board());
		assertEquals(3, stale.size());
		for (AgentTaskData td : b.stalled()) {
			List<Map<String, Object>> marks = stale.get(td.getUuid().toString());
			assertEquals(1, marks.size(), marks.toString());
			Map<String, Object> m = marks.get(0);
			assertEquals("hopNoProgress", m.get("rule"));
			assertTrue(((Number) m.get("minutes")).intValue() >= 70, m.toString());
			assertNotNull(m.get("since"));
			assertTrue(((String) m.get("message")).contains("stalled: assigned to coder"), m.toString());
		}
		assertEquals(List.of(), stale.get(b.fresh().getUuid().toString()), "a fresh assignment carries no mark");

		Board quiet = board(false, 2);
		assertTrue(staleByTask(quiet.board()).values().stream().allMatch(List::isEmpty),
				"a board without a staleness block marks nothing");
	}

	@Test
	@SuppressWarnings("unchecked")
	public void theSingleReadsCarryTheMarkToo() throws RelizaException {
		Board b = board(true, 1);
		AgentTaskData td = b.stalled().get(0);
		Map<String, Object> one = data(dgsQueryExecutor.execute("query($t: ID!) { agentTask(uuid: $t) { stale { rule message } } }",
				Map.of("t", td.getUuid().toString())), "agentTask");
		List<Map<String, Object>> marks = (List<Map<String, Object>>) one.get("stale");
		assertEquals(1, marks.size(), marks.toString());
		assertEquals("hopNoProgress", marks.get(0).get("rule"));

		Map<String, Object> byKey = data(dgsQueryExecutor.execute(
				"query($o: ID!, $k: String!) { agentTaskByKey(orgUuid: $o, key: $k) { stale { rule message } } }",
				Map.of("o", b.org().getUuid().toString(), "k", td.getKey())), "agentTaskByKey");
		assertEquals(marks, byKey.get("stale"));
	}

	@Test
	public void aBoardReadsStatementsDoNotGrowWithItsStalledTasks() throws Exception {
		Board six = board(true, 6);
		Board two = board(true, 2);
		assertEquals(6, staleByTask(six.board()).values().stream().filter(m -> !m.isEmpty()).count());
		assertEquals(2, staleByTask(two.board()).values().stream().filter(m -> !m.isEmpty()).count());

		long many = StatementCounter.fewest(emf, 3, () -> staleByTask(six.board()));
		long few = StatementCounter.fewest(emf, 3, () -> staleByTask(two.board()));
		assertEquals(few, many, "the same statements for 6 stalled tasks as for 2");
		// the parent read alone: the board, the numbering check (ensureNumbered) and the board's tasks
		long parent = StatementCounter.fewest(emf, 3, () -> data(dgsQueryExecutor.execute(
				"query($b: ID!) { agentTasksOfBoard(boardUuid: $b) { uuid status } }",
				Map.of("b", six.board().getUuid().toString())), "agentTasksOfBoard"));
		assertEquals(3, parent);
		// the field's loader on top: the board, the keyed tasks, the usage reports grouped (no linked release and
		// no DELIVERING task, so no release or PR read): 6 in all, whatever the number of stalled tasks
		assertEquals(parent + 3, many);
		assertEquals(6, many);
	}
}
