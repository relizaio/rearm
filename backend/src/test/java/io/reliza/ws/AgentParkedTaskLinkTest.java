/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.NotificationEventType;
import io.reliza.model.Organization;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.service.AgentBoardRenderSupport;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentIdentityService;
import io.reliza.service.AgentService;
import io.reliza.service.AgentSessionService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ComponentService;
import io.reliza.service.LicenseStatus;
import io.reliza.ws.oss.TestInitializer;

/**
 * Task RD4-19: {@code agentTaskLinkPrProgrammatic} on a task the coordinator seat parked for the operator, as a
 * client meets it through the programmatic endpoint. The link takes a key, not a session: it is accepted for every
 * key that may link, recorded on the hold under the key's agent, posted as an INFO, and answers nothing. A link on an
 * unparked task or under a hop's own hold (RD4-5) records nothing new. The supersede path that follows a link on a
 * parked delivery is {@code AgentParkedTaskLinkDeliveryIntegrationTest}'s.
 */
@SpringBootTest(classes = {App.class})
public class AgentParkedTaskLinkTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final String QUESTION = "CI is red on #401. Options: re-run, replace the PR. Recommend: replace.";

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
	@Autowired private NotificationOutboxEventRepository outboxRepository;

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

	private ExecutionResult asKey(ApiKey key, String query, Map<String, Object> vars) {
		SecurityContextHolder.setContext(SecurityContextHolder.createEmptyContext());
		MockHttpServletRequest request = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		request.setAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE,
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.47"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return dgsQueryExecutor.execute(query, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	private record Caller(ApiKey key, AgentData agent, AgentSessionData session) {
		/** How the hold names this key's agent: its name and the first eight of its uuid. */
		String linker() {
			return agent.getName() + " (agent " + agent.getUuid().toString().substring(0, 8) + ")";
		}
	}

	private Caller caller(Organization org, PermissionFunction... fns) throws Exception {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "rd4-19", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE, Set.of(fns), null)), WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(org.getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), identity, "rd4-19-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(org.getUuid(), agent.getUuid(), key.getUuid(),
				"rd4-19-" + UUID.randomUUID(), "rd4-19", null, null, WU);
		return new Caller(key, agent, s);
	}

	private record Board(Organization org, AgentBoardData board, Caller seat, Caller worker) {}

	/** A board with one REQUIRED coder role, its seat claimed, and a worker session. */
	private Board board() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("rd419_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "rd419-" + UUID.randomUUID(), "parked link",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build", 10, null, false,
				true, null, null, RoleNecessity.REQUIRED, null, List.of(), List.of(), null, null), true, WU);
		Caller seat = caller(org, PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE, PermissionFunction.BOARD_AGENT);
		agentBoardService.claimCoordinatorSeat(board.getUuid(), seat.session().getUuid(), seat.agent().getUuid(), WU);
		Caller worker = caller(org, PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		return new Board(org, agentBoardService.getBoardData(board.getUuid()).orElseThrow(), seat, worker);
	}

	private AgentBoardData fresh(Board b) {
		return agentBoardService.getBoardData(b.board().getUuid()).orElseThrow();
	}

	private static String prUrl() {
		return "https://github.com/acme/rd419/pull/" + (1000 + new java.util.Random().nextInt(899000));
	}

	private AgentTaskData assigned(Board b) throws RelizaException {
		AgentTaskData t = agentTaskService.register(fresh(b), null, "parked link " + UUID.randomUUID(), null, null, null,
				null, null, null, COORD, true, WU);
		agentTaskService.authorize(t.getUuid(), fresh(b), "coder", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), fresh(b), b.worker().agent().getUuid(), b.worker().session().getUuid(), WU);
		return task(t);
	}

	/** Passed with a PR no CI reports here: it waits in DELIVERING. */
	private AgentTaskData delivering(Board b) throws RelizaException {
		AgentTaskData t = assigned(b);
		agentTaskService.linkPr(t.getUuid(), prUrl(), WU);
		AgentTaskData d = agentTaskService.signOff(t.getUuid(), b.worker().session().getUuid(), SignOffOutcome.PASSED,
				"done", WU);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		return d;
	}

	private AgentTaskData parkedDelivery(Board b) throws RelizaException {
		AgentTaskData d = delivering(b);
		AgentTaskData held = agentTaskService.parkForOperator(d.getUuid(), b.seat().session().getUuid(), QUESTION, WU);
		assertTrue(held.seatParkedForOperator());
		return held;
	}

	private static final String LINK = "mutation($t: ID!, $u: String!) { agentTaskLinkPrProgrammatic(taskUuid: $t, prUrl: $u)"
			+ " { status prUrls hold { returnTo linked { prUrl by at } } } }";

	@SuppressWarnings("unchecked")
	private Map<String, Object> link(Caller c, AgentTaskData t, String url) {
		ExecutionResult r = asKey(c.key(), LINK, Map.of("t", t.getUuid().toString(), "u", url));
		assertTrue(r.getErrors().isEmpty(), r.getErrors().toString());
		return (Map<String, Object>) ((Map<String, Object>) r.getData()).get("agentTaskLinkPrProgrammatic");
	}

	private AgentTaskData task(AgentTaskData t) {
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	private List<String> linkInfos(Board b, AgentTaskData t) {
		return agentBoardService.recentEvents(b.board().getUuid()).stream()
				.filter(ev -> AgentBoardData.BoardEventKind.INFO == ev.kind())
				.map(AgentBoardData.BoardEvent::message)
				.filter(m -> m.startsWith("Task " + t.label() + " is parked, awaiting the operator: PR ")).toList();
	}

	private long needsPerson(Board b, AgentTaskData t) {
		return outboxRepository.findRecentByOrg(b.org().getUuid(), 5000).stream()
				.filter(e -> NotificationEventType.AGENT_TASK_NEEDS_PERSON == e.getEventType())
				.map(AgentBoardRenderSupport::payload).filter(p -> t.getUuid().equals(p.task())).count();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void aKeysLinkOnAParkedDeliveryIsRecordedOnTheDecisionAndAnswersNothing() throws Exception {
		Board b = board();
		AgentTaskData d = parkedDelivery(b);
		int rows = d.getStatusHistory().size();
		assertEquals(1, needsPerson(b, d));
		String url = prUrl();

		Map<String, Object> out = link(b.worker(), d, url);
		assertEquals("ON_HOLD", out.get("status"), "a link never lifts the hold");
		assertTrue(((List<String>) out.get("prUrls")).contains(url), "the task lists the PR");
		Map<String, Object> hold = (Map<String, Object>) out.get("hold");
		assertEquals("DELIVERING", hold.get("returnTo"));
		List<Map<String, Object>> linked = (List<Map<String, Object>>) hold.get("linked");
		assertEquals(1, linked.size(), linked.toString());
		assertEquals(url, linked.get(0).get("prUrl"));
		assertEquals(b.worker().linker(), linked.get(0).get("by"), "named by the linking key's agent");
		assertNotNull(linked.get(0).get("at"));

		AgentTaskData held = task(d);
		assertTrue(held.seatParkedForOperator());
		assertEquals(rows, held.getStatusHistory().size(), "no status row: nothing moved and nothing was answered");
		assertEquals(List.of("Task " + held.label() + " is parked, awaiting the operator: PR " + url + " linked by "
				+ b.worker().linker() + "; the delivery set the person is deciding on has changed"), linkInfos(b, held));
		assertEquals(1, needsPerson(b, held), "the needs-a-person row is not sent again");

		// a person's release answers it; the release row keeps what was linked under the hold
		AgentTaskData released = agentTaskService.liftHoldOrAnswer(d.getUuid(), HoldLevel.OPERATOR,
				AgentActor.ofUser(UUID.randomUUID(), "Pat Operator"), "replace it", null, WU);
		assertEquals(TaskStatus.DELIVERING, released.getStatus());
		assertNull(released.getHold());
		StatusChange row = released.getStatusHistory().get(released.getStatusHistory().size() - 1);
		assertEquals(StatusTrigger.LIFT_HOLD, row.trigger());
		assertEquals("lifted by Pat Operator: replace it", row.note(), "the link is not the answer");
		assertEquals(1, row.linked().size());
		assertEquals(url, row.linked().get(0).prUrl());
		assertEquals(b.worker().linker(), row.linked().get(0).by());
	}

	@Test
	@SuppressWarnings("unchecked")
	public void theSeatsOwnLinkIsRecordedTheSame() throws Exception {
		Board b = board();
		AgentTaskData d = parkedDelivery(b);
		String first = prUrl();
		String second = prUrl();
		link(b.seat(), d, first);
		Map<String, Object> out = link(b.worker(), d, second);
		assertEquals("ON_HOLD", out.get("status"));
		List<Map<String, Object>> linked = (List<Map<String, Object>>) ((Map<String, Object>) out.get("hold")).get("linked");
		assertEquals(List.of(first, second), linked.stream().map(l -> l.get("prUrl")).toList(), "oldest first");
		assertEquals(List.of(b.seat().linker(), b.worker().linker()), linked.stream().map(l -> l.get("by")).toList());
		assertEquals(2, linkInfos(b, task(d)).size());
		assertTrue(linkInfos(b, task(d)).get(0).contains(" linked by " + b.seat().linker() + ";"));
	}

	@Test
	@SuppressWarnings("unchecked")
	public void aLinkOnAnUnparkedTaskOrUnderAHopsOwnHoldRecordsNothingNew() throws Exception {
		Board b = board();
		// an unparked delivery: linked as before, no hold, no INFO
		AgentTaskData d = delivering(b);
		Map<String, Object> out = link(b.worker(), d, prUrl());
		assertEquals("DELIVERING", out.get("status"));
		assertNull(out.get("hold"));
		assertTrue(linkInfos(b, task(d)).isEmpty());

		// a hop's own hold (RD4-5): the holder links its own PRs as its work, and the hold records nothing
		AgentTaskData working = assigned(b);
		agentTaskService.parkHopForOperator(working.getUuid(), b.worker().session().getUuid(), QUESTION, WU);
		assertTrue(task(working).holdParksAHop());
		String url = prUrl();
		Map<String, Object> onHop = link(b.worker(), working, url);
		assertEquals("ON_HOLD", onHop.get("status"));
		assertTrue(((List<String>) onHop.get("prUrls")).contains(url));
		assertNull(((Map<String, Object>) onHop.get("hold")).get("linked"));
		assertTrue(task(working).holdParksAHop());
		assertTrue(linkInfos(b, task(working)).isEmpty());
	}
}
