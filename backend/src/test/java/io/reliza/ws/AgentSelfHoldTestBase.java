/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.ServletWebRequest;

import com.netflix.graphql.dgs.DgsQueryExecutor;

import graphql.ExecutionResult;
import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentIdentityCredential.IdentityType;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.StatusChange;
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
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.BoardAgentsService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.ws.oss.TestInitializer;

/**
 * The rig for a session's own hold (task RD4-5) and what its park does to the hop (task TPF-66): the agent's side
 * goes through the programmatic GraphQL endpoint with the principal its filter leaves on the request, so the
 * arguments and the gates are exercised as a client meets them; the person's release is the service call the
 * operator-hold mutation makes.
 *
 * <p>One file per topic: {@link AgentSelfHoldAndWithdrawTest} for the park, the release and the withdrawal,
 * {@link AgentParkedHopEndsTest} for the hop the park ends; a new topic goes into a new class extending this base.
 */
@SpringBootTest(classes = {App.class})
abstract class AgentSelfHoldTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	protected static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	protected static final String QUESTION = "Which secret model do we ship: per-org or per-board?";

	@Autowired protected DgsQueryExecutor dgsQueryExecutor;
	@Autowired protected TestInitializer testInitializer;
	@Autowired protected AgentBoardService agentBoardService;
	@Autowired protected AgentTaskService agentTaskService;
	@Autowired protected AgentService agentService;
	@Autowired protected AgentSessionService agentSessionService;
	@Autowired protected ComponentService componentService;
	@Autowired protected LicenseStatus licenseStatus;
	@Autowired protected ApiKeyService apiKeyService;
	@Autowired protected AgentIdentityService agentIdentityService;
	@Autowired protected NotificationOutboxEventRepository outboxRepository;
	@Autowired protected BoardAgentsService boardAgentsService;

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

	protected ExecutionResult asKey(ApiKey key, String query, Map<String, Object> vars) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.45"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return dgsQueryExecutor.execute(query, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	protected record Caller(ApiKey key, AgentData agent, AgentSessionData session) {}

	protected Caller caller(Organization org, PermissionFunction... fns) throws Exception {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "rd4-5", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE, Set.of(fns), null)), WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(org.getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), identity, "rd4-5-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(org.getUuid(), agent.getUuid(), key.getUuid(),
				"rd4-5-" + UUID.randomUUID(), "rd4-5", null, null, WU);
		return new Caller(key, agent, s);
	}

	protected record Board(Organization org, AgentBoardData board) {}

	protected Board board() throws Exception {
		return board(4);
	}

	/** A board with one architect role and the given per-agent WIP limit. */
	protected Board board(int perAgentWipLimit) throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("rd45_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "rd45-" + UUID.randomUUID(), "self hold",
				List.of(), "coordinate", perAgentWipLimit, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics("architect", "design", 10,
				null, false, true, null), true, WU);
		return new Board(org, agentBoardService.getBoardData(board.getUuid()).orElseThrow());
	}

	/** An authorised architect task, QUEUED. */
	protected AgentTaskData queued(Board b) throws RelizaException {
		AgentTaskData t = agentTaskService.register(b.board(), null, "self hold " + UUID.randomUUID(), null, null, null,
				null, null, null, COORD, true, WU);
		agentTaskService.authorize(t.getUuid(), b.board(), "architect", 10, null, null, null, null, COORD, WU);
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	/** A task the architect role is working, held by {@code holder}'s session. */
	protected AgentTaskData assignedTo(Board b, Caller holder) throws RelizaException {
		AgentTaskData t = queued(b);
		agentTaskService.assign(t.getUuid(), b.board(), holder.agent().getUuid(), holder.session().getUuid(), WU);
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	protected static final String HOLD = "mutation($t: ID!, $s: ID!, $r: String!, $l: AgentTaskHoldLevel) {"
			+ " agentTaskHoldProgrammatic(taskUuid: $t, sessionUuid: $s, reason: $r, level: $l)"
			+ " { status hold { level kind reason heldBy { kind uuid } } } }";

	protected ExecutionResult hold(Caller c, AgentTaskData t, String reason, String level) {
		Map<String, Object> vars = new java.util.HashMap<>(Map.of("t", t.getUuid().toString(),
				"s", c.session().getUuid().toString(), "r", reason));
		if (null != level) vars.put("l", level);
		return asKey(c.key(), HOLD, vars);
	}

	protected AgentTaskData task(AgentTaskData t) {
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	protected static StatusChange lastRow(AgentTaskData t) {
		return t.getStatusHistory().get(t.getStatusHistory().size() - 1);
	}
}
