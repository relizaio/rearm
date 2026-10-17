/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsDataFetchingEnvironment;
import com.netflix.graphql.dgs.context.DgsContext;
import com.netflix.graphql.dgs.internal.DgsWebMvcRequestData;

import graphql.GraphQLContext;
import graphql.schema.DataFetchingEnvironmentImpl;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AgentSessionUsageService;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.service.AgentTaskService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.BoardAgentsService;
import io.reliza.service.BoardAgentsService.AgentRow;
import io.reliza.service.BoardAgentsService.StateKind;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.model.SessionUsageSource;
import io.reliza.ws.oss.TestInitializer;

/**
 * The Agents tab's read (task RD3-5): every session that worked or polled a board, in the four states, ordered
 * open first by last activity, with its roles, completed tasks, spend and cache share in the window, and the
 * staleness marks that name it; read only with the board.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class BoardAgentsIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor PERSON = AgentActor.ofUser(UUID.randomUUID(), "ops@acme.example");
	private static final AtomicInteger SEQ = new AtomicInteger(1);

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private AgentSessionUsageService usageService;
	@Autowired private ComponentService componentService;
	@Autowired private BoardAgentsService boardAgentsService;
	@Autowired private AgentTaskDataFetcher fetcher;
	@Autowired private ApiKeyService apiKeyService;
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

	private record Rig(Organization org, AgentBoardData board) {}

	/** A board with one REQUIRED coder, so a coder's pass completes a task with no PRs. */
	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("agents_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "agents-" + UUID.randomUUID(), "agents",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build it", 10, null, false,
				true, null, null, RoleNecessity.REQUIRED, null, List.of(), List.of(), null, null), true, WU);
		return new Rig(org, board);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentSessionData session(Rig r, String name) throws RelizaException {
		AgentData a = agentService.findOrRegisterRootAgent(r.org().getUuid(), UUID.randomUUID(), name + "-" + UUID.randomUUID(),
				null, null, null, WU);
		return agentSessionService.initialize(r.org().getUuid(), a.getUuid(), null, "s-" + UUID.randomUUID(), name, null, null, WU);
	}

	private AgentTaskData queued(Rig r) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), null, "agents " + UUID.randomUUID(), null, null, null, null, null,
				null, PERSON, true, WU);
		return agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, PERSON, WU);
	}

	private static AgentRow rowOf(List<AgentRow> rows, AgentSessionData s) {
		return rows.stream().filter(x -> x.session().equals(s.getUuid())).findFirst().orElseThrow();
	}

	private void pause() throws InterruptedException {
		Thread.sleep(15);
	}

	@Test
	public void theFourStatesInOrderWithRolesCompletedSpendAndCacheShare() throws Exception {
		Rig r = rig();
		ZonedDateTime now = ZonedDateTime.now();
		// Closed: passed a task, which completed, then closed.
		AgentSessionData closed = session(r, "closed");
		AgentTaskData done = queued(r);
		agentTaskService.assign(done.getUuid(), board(r), closed.getAgent(), closed.getUuid(), WU);
		assertEquals(TaskStatus.COMPLETED,
				agentTaskService.signOff(done.getUuid(), closed.getUuid(), SignOffOutcome.PASSED, "done", WU).getStatus());
		pause();
		// Idle: polled for reviewer half an hour ago.
		AgentSessionData idle = session(r, "idle");
		agentSessionService.recordBoardPolls(idle.getUuid(), List.of(r.board().getUuid()), List.of("reviewer"), null,
				now.minusMinutes(30));
		pause();
		// Waiting: polled for coder a minute ago.
		AgentSessionData waiting = session(r, "waiting");
		agentSessionService.recordBoardPolls(waiting.getUuid(), List.of(r.board().getUuid()), List.of("coder"), null,
				now.minusMinutes(1));
		// ...and signed off a task that did not complete: a rejection is not a completed task.
		AgentTaskData rejected = queued(r);
		agentTaskService.assign(rejected.getUuid(), board(r), waiting.getAgent(), waiting.getUuid(), WU);
		assertTrue(agentTaskService.signOff(rejected.getUuid(), waiting.getUuid(), SignOffOutcome.REJECTED, "not yet", WU)
				.getStatus() != TaskStatus.COMPLETED);
		pause();
		// Working: holds a task and reported usage with cache on it.
		AgentSessionData working = session(r, "working");
		AgentTaskData held = queued(r);
		agentTaskService.assign(held.getUuid(), board(r), working.getAgent(), working.getUuid(), WU);
		usageService.report(agentSessionService.getSessionData(working.getUuid()).orElseThrow(),
				new UsageReport(working.getUuid(), null, SEQ.incrementAndGet(), SessionUsageSource.TRANSCRIPT,
						ZonedDateTime.now().minusMinutes(1), ZonedDateTime.now(), 1, 1, 60, null, held.getUuid(), Map.of(),
						List.of(new UsageLine("claude-opus-5-5", null, 0L, 1, 100, 10, 300, 100, null, 100L, 100L, null))), WU);
		pause();
		// Closed last, so its activity is the newest: it still sorts after every open session.
		agentSessionService.close(closed.getUuid(), WU);

		List<AgentRow> rows = boardAgentsService.agents(board(r), null, null, ZonedDateTime.now());
		assertEquals(List.of(working.getUuid(), waiting.getUuid(), idle.getUuid(), closed.getUuid()),
				rows.stream().map(AgentRow::session).toList(), "open by last activity, newest first, then closed");

		AgentRow w = rowOf(rows, working);
		AgentTaskData heldNow = agentTaskService.getTaskData(held.getUuid()).orElseThrow();
		assertEquals(StateKind.WORKING, w.state().kind());
		assertEquals(heldNow.getKey(), w.state().taskKey(), "the key alone, which a consumer passes on as a key (T-1)");
		assertNotNull(heldNow.getKey());
		assertNotEquals(heldNow.label(), w.state().taskKey(), "not the label, key and title");
		assertEquals(held.getUuid(), w.state().taskUuid());
		assertEquals(heldNow.getAssignment().assignedAt().toInstant(), w.state().since().toInstant());
		assertEquals(List.of("coder"), w.roles());
		assertEquals(0, w.tasksCompleted());
		assertNotNull(w.tokens());
		assertEquals(300, w.tokens().cacheReadTokens());
		assertEquals(0.6, w.cacheShare(), 1e-9, "300 cache read over 100 + 300 + 100");
		assertTrue(w.agentName().startsWith("working-"), w.agentName());

		AgentRow p = rowOf(rows, waiting);
		assertEquals(StateKind.WAITING, p.state().kind());
		assertEquals(0, p.tasksCompleted(), "its rejected task did not complete");
		assertEquals(List.of("coder"), p.roles(), "a session that only polled shows the roles it asked for");
		assertNotNull(p.lastPollAt());
		assertEquals(p.lastPollAt().toEpochSecond(), p.state().since().toEpochSecond());
		assertNull(p.tokens());
		assertNull(p.cacheShare());

		AgentRow i = rowOf(rows, idle);
		assertEquals(StateKind.IDLE, i.state().kind(), "its last poll is older than the waiting window");
		assertEquals(List.of("reviewer"), i.roles());

		AgentRow c = rowOf(rows, closed);
		assertEquals(StateKind.CLOSED, c.state().kind());
		assertNotNull(c.state().since());
		assertNotNull(c.state().closedBy());
		assertEquals(1, c.tasksCompleted());
		assertEquals(List.of("coder"), c.roles());

		// The window: nothing spent in an hour that has not come yet.
		List<AgentRow> later = boardAgentsService.agents(board(r), ZonedDateTime.now().plusHours(1),
				ZonedDateTime.now().plusHours(2), ZonedDateTime.now());
		assertNull(rowOf(later, working).tokens());
		assertEquals(0L, rowOf(later, working).costMicros());
	}

	@Test
	public void aStalledHopMarksItsSessionWithTheRule() throws Exception {
		Rig r = rig();
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("staleness", Map.of("hopNoProgressMinutes", 60));
		agentBoardService.updateSettingsFromInput(r.board().getUuid(), settings, WU);
		AgentSessionData working = session(r, "stalled");
		AgentSessionData other = session(r, "other");
		agentSessionService.recordBoardPolls(other.getUuid(), List.of(r.board().getUuid()), List.of(), null, ZonedDateTime.now());
		AgentTaskData held = queued(r);
		agentTaskService.assign(held.getUuid(), board(r), working.getAgent(), working.getUuid(), WU);

		List<AgentRow> now = boardAgentsService.agents(board(r), null, null, ZonedDateTime.now());
		assertTrue(rowOf(now, working).stale().isEmpty(), "not stale yet");
		List<AgentRow> later = boardAgentsService.agents(board(r), null, null, ZonedDateTime.now().plusMinutes(61));
		AgentRow w = rowOf(later, working);
		assertEquals(1, w.stale().size(), w.stale().toString());
		assertEquals("hopNoProgress", w.stale().get(0).rule());
		assertTrue(w.stale().get(0).message().contains("stalled"), w.stale().get(0).message());
		assertTrue(rowOf(later, other).stale().isEmpty(), "the rule names the stalled session only");
	}

	// ---------- read only with the board ----------

	private ApiKey key(Organization org, Set<PermissionFunction> functions) throws RelizaException {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "agents reader", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_ONLY, functions, null)), WU);
		return key;
	}

	private DgsDataFetchingEnvironment as(ApiKey key, Object source) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.14"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		GraphQLContext.Builder context = GraphQLContext.newContext();
		new DgsContext(null, new DgsWebMvcRequestData(Map.of(), headers, new ServletWebRequest(request))).accept(context);
		return new DgsDataFetchingEnvironment(DataFetchingEnvironmentImpl.newDataFetchingEnvironment()
				.graphQLContext(context.build()).arguments(Map.of()).source(source).build(), applicationContext);
	}

	@Test
	public void theAgentsReadWithTheBoardAndAKeyWithoutBoardReadGetsNeither() throws Exception {
		Rig r = rig();
		AgentSessionData waiting = session(r, "reader-case");
		agentSessionService.recordBoardPolls(waiting.getUuid(), List.of(r.board().getUuid()), List.of("coder"), null,
				ZonedDateTime.now());
		ApiKey none = key(r.org(), Set.of(PermissionFunction.AGENT));
		RelizaException refused = assertThrows(RelizaException.class,
				() -> fetcher.agentBoardProgrammatic(r.board().getUuid(), as(none, null)));
		assertTrue(refused.getMessage().contains("BOARD_READ"), refused.getMessage());

		ApiKey reader = key(r.org(), Set.of(PermissionFunction.BOARD_READ));
		AgentBoardData read = fetcher.agentBoardProgrammatic(r.board().getUuid(), as(reader, null));
		List<AgentRow> rows = fetcher.boardAgents(as(reader, read), null, null);
		assertEquals(List.of(waiting.getUuid()), rows.stream().map(AgentRow::session).toList());
	}
}
