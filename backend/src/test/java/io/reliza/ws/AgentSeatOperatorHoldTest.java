/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
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
import io.reliza.model.AgentTaskData.HoldKind;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.NotificationDelivery;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.model.Organization;
import io.reliza.model.User;
import io.reliza.model.UserData;
import io.reliza.common.CommonVariables.OauthType;
import io.reliza.model.UserPermission.PermissionDto;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.notifications.AgentBoardEventPayload;
import io.reliza.repositories.NotificationDeliveryRepository;
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
import io.reliza.service.NotificationFanOutService;
import io.reliza.service.UserService;
import io.reliza.ws.oss.TestInitializer;

/**
 * Task RD4-17: the coordinator seat parks a task nobody is working for an operator decision, with the question,
 * in PENDING_INTAKE, QUEUED, AWAITING_COORDINATOR or DELIVERING; a person's release with the answer returns it to
 * the state it was parked from. The seat's side goes through the programmatic GraphQL endpoint with the principal
 * its filter leaves on the request, so the gate is exercised as a client meets it; the person's release is the
 * service call the operator-hold mutation makes. What a held DELIVERING task does meanwhile (the sweep, the
 * delivery verbs, a person answering by acting) is {@code AgentSeatOperatorHoldDeliveryIntegrationTest}'s.
 */
@SpringBootTest(classes = {App.class})
public class AgentSeatOperatorHoldTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final String QUESTION = "CI is red on #401. Options: re-run, reopen to the coder. Recommend: re-run.";
	private static final String AWAITING = "awaiting the operator: " + QUESTION;

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
	@Autowired private UserService userService;
	@Autowired private NotificationOutboxEventRepository outboxRepository;
	@Autowired private NotificationDeliveryRepository deliveries;
	@Autowired private NotificationFanOutService fanOutService;

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
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.46"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return dgsQueryExecutor.execute(query, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	private record Caller(ApiKey key, AgentData agent, AgentSessionData session) {}

	private Caller caller(Organization org, PermissionFunction... fns) throws Exception {
		ApiKey key = apiKeyService.createObjectApiKey(org.getUuid(), ApiTypeEnum.FREEFORM, org.getUuid(),
				UUID.randomUUID().toString(), "rd4-17", WU);
		apiKeyService.setPermissionsOnApiKey(key.getUuid(), null, List.of(new PermissionDto(org.getUuid(),
				PermissionScope.ORGANIZATION, org.getUuid(), PermissionType.READ_WRITE, Set.of(fns), null)), WU);
		UUID identity = agentIdentityService.findOrRegisterByCredential(org.getUuid(), IdentityType.REARM_API_KEY,
				key.getUuid().toString(), WU).getUuid();
		AgentData agent = agentService.findOrRegisterRootAgent(org.getUuid(), identity, "rd4-17-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentSessionData s = agentSessionService.initialize(org.getUuid(), agent.getUuid(), key.getUuid(),
				"rd4-17-" + UUID.randomUUID(), "rd4-17", null, null, WU);
		return new Caller(key, agent, s);
	}

	private record Board(Organization org, AgentBoardData board, Caller seat, Caller worker) {}

	/** A board with one REQUIRED coder role, its seat claimed, and a worker session. */
	private Board board() throws Exception {
		return board(true);
	}

	/** As above; the seat claimed only when asked, so a test can claim it with a key of its own. */
	private Board board(boolean claimSeat) throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("rd417_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "rd417-" + UUID.randomUUID(), "seat hold",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build", 10, null, false,
				true, null, null, RoleNecessity.REQUIRED, null, List.of(), List.of(), null, null), true, WU);
		// The seat's key as a coordinator's is: it writes the board and makes the agent verbs (delivered's gate).
		Caller seat = caller(org, PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE, PermissionFunction.BOARD_AGENT);
		if (claimSeat) {
			agentBoardService.claimCoordinatorSeat(board.getUuid(), seat.session().getUuid(), seat.agent().getUuid(), WU);
		}
		Caller worker = caller(org, PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		return new Board(org, agentBoardService.getBoardData(board.getUuid()).orElseThrow(), seat, worker);
	}

	private AgentBoardData fresh(Board b) {
		return agentBoardService.getBoardData(b.board().getUuid()).orElseThrow();
	}

	private AgentTaskData pending(Board b) throws RelizaException {
		return agentTaskService.register(fresh(b), null, "seat hold " + UUID.randomUUID(), null, null, null, null, null,
				null, COORD, true, WU);
	}

	private AgentTaskData queued(Board b) throws RelizaException {
		AgentTaskData t = pending(b);
		return agentTaskService.authorize(t.getUuid(), fresh(b), "coder", 10, null, null, null, null, COORD, WU);
	}

	private AgentTaskData assigned(Board b) throws RelizaException {
		AgentTaskData t = queued(b);
		agentTaskService.assign(t.getUuid(), fresh(b), b.worker().agent().getUuid(), b.worker().session().getUuid(), WU);
		return task(t);
	}

	private AgentTaskData awaitingCoordinator(Board b) throws RelizaException {
		AgentTaskData t = assigned(b);
		return agentTaskService.returnTask(t.getUuid(), b.worker().session().getUuid(), TaskReturnReason.OTHER,
				"the tracker issue is gone", WU);
	}

	/** Passed with a PR no CI reports here: it waits in DELIVERING. */
	private AgentTaskData delivering(Board b) throws RelizaException {
		AgentTaskData t = assigned(b);
		agentTaskService.linkPr(t.getUuid(), "https://github.com/acme/rd417/pull/" + UUID.randomUUID().toString()
				.substring(0, 6).replaceAll("[a-f]", "1"), WU);
		AgentTaskData d = agentTaskService.signOff(t.getUuid(), b.worker().session().getUuid(), SignOffOutcome.PASSED,
				"done", WU);
		assertEquals(TaskStatus.DELIVERING, d.getStatus());
		return d;
	}

	private static final String HOLD = "mutation($t: ID!, $s: ID!, $r: String!, $l: AgentTaskHoldLevel) {"
			+ " agentTaskHoldProgrammatic(taskUuid: $t, sessionUuid: $s, reason: $r, level: $l)"
			+ " { status hold { level kind reason returnTo heldBy { kind uuid } } } }";

	private ExecutionResult hold(Caller c, AgentTaskData t, String reason, String level) {
		Map<String, Object> vars = new HashMap<>(Map.of("t", t.getUuid().toString(),
				"s", c.session().getUuid().toString(), "r", reason));
		if (null != level) vars.put("l", level);
		return asKey(c.key(), HOLD, vars);
	}

	private AgentTaskData task(AgentTaskData t) {
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	private static StatusChange lastRow(AgentTaskData t) {
		return t.getStatusHistory().get(t.getStatusHistory().size() - 1);
	}

	private List<AgentBoardEventPayload> needsPerson(Board b, AgentTaskData t) {
		return outboxRepository.findRecentByOrg(b.org().getUuid(), 5000).stream()
				.filter(e -> NotificationEventType.AGENT_TASK_NEEDS_PERSON == e.getEventType())
				.map(AgentBoardRenderSupport::payload).filter(p -> t.getUuid().equals(p.task())).toList();
	}

	@Test
	@SuppressWarnings("unchecked")
	public void theSeatParksATaskInEachOfTheFourStatesAndTheAnswerReturnsItThere() throws Exception {
		Board b = board();
		AgentActor person = AgentActor.ofUser(UUID.randomUUID(), "Pat Operator");
		Map<TaskStatus, AgentTaskData> tasks = new java.util.LinkedHashMap<>();
		tasks.put(TaskStatus.PENDING_INTAKE, pending(b));
		tasks.put(TaskStatus.QUEUED, queued(b));
		tasks.put(TaskStatus.AWAITING_COORDINATOR, awaitingCoordinator(b));
		tasks.put(TaskStatus.DELIVERING, delivering(b));
		for (Map.Entry<TaskStatus, AgentTaskData> e : tasks.entrySet()) {
			TaskStatus from = e.getKey();
			AgentTaskData t = e.getValue();
			assertEquals(from, task(t).getStatus());

			ExecutionResult parked = hold(b.seat(), t, QUESTION, "OPERATOR");
			assertTrue(parked.getErrors().isEmpty(), from + ": " + parked.getErrors());
			Map<String, Object> out = (Map<String, Object>) ((Map<String, Object>) parked.getData())
					.get("agentTaskHoldProgrammatic");
			assertEquals("ON_HOLD", out.get("status"), from.name());
			Map<String, Object> h = (Map<String, Object>) out.get("hold");
			assertEquals("OPERATOR", h.get("level"));
			assertEquals("MANUAL", h.get("kind"));
			assertEquals(AWAITING, h.get("reason"));
			assertEquals(from.name(), h.get("returnTo"), "the hold records where the answer returns the task");
			assertEquals(b.seat().session().getUuid().toString(), ((Map<String, Object>) h.get("heldBy")).get("uuid"));

			AgentTaskData held = task(t);
			assertTrue(held.seatParkedForOperator(), from.name());
			assertTrue(held.awaitsTheOperator(), from.name());
			assertEquals(StatusTrigger.HOLD, lastRow(held).trigger());
			assertEquals(from, lastRow(held).from());
			assertEquals(AWAITING, lastRow(held).note());
			// the board's writers are told through the needs-a-person path, as for a hop's own question
			List<AgentBoardEventPayload> told = needsPerson(b, held);
			assertEquals(1, told.size(), from + ": the needs-a-person notification is written once");
			assertEquals(HoldKind.MANUAL.name(), told.get(0).holdKind());
			assertEquals(HoldLevel.OPERATOR.name(), told.get(0).holdLevel());
			assertEquals(AWAITING, told.get(0).message());
			assertTrue(agentBoardService.recentEvents(b.board().getUuid()).stream().anyMatch(ev -> ev.message()
					.contains(held.label() + " parked by the coordinator, " + AWAITING)), "the feed says so");

			// the seat cannot lift it: the answer is a person's
			RelizaException bySeat = assertThrows(RelizaException.class, () -> agentTaskService.liftHold(t.getUuid(),
					HoldLevel.COORDINATOR, AgentActor.ofSession(b.seat().session().getUuid()), "go on", null, WU));
			assertTrue(bySeat.getMessage().contains(AWAITING + "; a person lifts the hold with the answer"), bySeat.getMessage());
			// a person's release needs the answer, and routes nowhere
			RelizaException bare = assertThrows(RelizaException.class, () -> agentTaskService.liftHoldOrAnswer(
					t.getUuid(), HoldLevel.OPERATOR, person, " ", null, WU));
			assertTrue(bare.getMessage().contains("with your answer as the note"), bare.getMessage());
			RelizaException withRole = assertThrows(RelizaException.class, () -> agentTaskService.liftHoldOrAnswer(
					t.getUuid(), HoldLevel.OPERATOR, person, "re-run it", "coder", WU));
			assertTrue(withRole.getMessage().contains("returns it to " + from + " rather than routing it"),
					withRole.getMessage());
			assertEquals(TaskStatus.ON_HOLD, task(t).getStatus());

			AgentTaskData released = agentTaskService.liftHoldOrAnswer(t.getUuid(), HoldLevel.OPERATOR, person,
					"re-run it once", null, WU);
			assertEquals(from, released.getStatus(), "the answer returns the task to where it was parked");
			assertNull(released.getHold());
			StatusChange row = lastRow(released);
			assertEquals(StatusTrigger.LIFT_HOLD, row.trigger());
			assertEquals("lifted by Pat Operator: re-run it once", row.note());
			assertEquals(person, row.actor());
			assertTrue(agentBoardService.recentEvents(b.board().getUuid()).stream()
					.anyMatch(ev -> AgentBoardData.BoardEventKind.INFO == ev.kind()
							&& ev.message().contains(released.label() + " answered by Pat Operator: re-run it once")
							&& ev.message().contains("(the question: " + QUESTION + "); it returned to " + from)),
					from + ": an INFO names the task, the answer and the question");
		}
	}

	@Test
	public void theSeatsHoldIsRefusedToOtherSessionsAndOutsideTheFourStates() throws Exception {
		Board b = board();
		// a session of a key that writes the board but does not hold the seat
		Caller writer = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		AgentTaskData d = delivering(b);
		ExecutionResult notTheSeat = hold(writer, d, QUESTION, "OPERATOR");
		assertTrue(notTheSeat.getErrors().toString().contains("Only the session holding the task's current assignment,"
				+ " or the coordinator seat, parks it for the operator"), notTheSeat.getErrors().toString());
		assertEquals(TaskStatus.DELIVERING, task(d).getStatus());
		// the worker that passed it holds nothing now
		ExecutionResult byWorker = hold(b.worker(), d, QUESTION, "OPERATOR");
		assertTrue(byWorker.getErrors().toString().contains("parks it for the operator"), byWorker.getErrors().toString());
		// the seat's COORDINATOR hold on a delivery is refused and names the operator hold
		ExecutionResult coordinatorLevel = hold(b.seat(), d, QUESTION, null);
		assertTrue(coordinatorLevel.getErrors().toString().contains("is DELIVERING: the seat parks it only for the"
				+ " operator, with the question (task hold --operator --question)"), coordinatorLevel.getErrors().toString());
		assertEquals(TaskStatus.DELIVERING, task(d).getStatus());
		// no question, no hold
		ExecutionResult blank = hold(b.seat(), d, "  ", "OPERATOR");
		assertTrue(blank.getErrors().toString().contains("needs the question"), blank.getErrors().toString());
		// a task someone is working is that hop's to park
		AgentTaskData working = assigned(b);
		ExecutionResult onAssigned = hold(b.seat(), working, QUESTION, "OPERATOR");
		assertTrue(onAssigned.getErrors().toString().contains("the session working it parks its own hop"),
				onAssigned.getErrors().toString());
		assertEquals(TaskStatus.ASSIGNED, task(working).getStatus());
		// nor a task already held
		assertTrue(hold(b.seat(), d, QUESTION, "OPERATOR").getErrors().isEmpty());
		ExecutionResult twice = hold(b.seat(), d, "another question", "OPERATOR");
		assertTrue(twice.getErrors().toString().contains("is ON_HOLD"), twice.getErrors().toString());
		assertEquals(AWAITING, task(d).getHold().reason());

		// a seat key without BOARD_WRITE parks nothing
		Board other = board(false);
		Caller agentOnly = caller(other.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		agentBoardService.claimCoordinatorSeat(other.board().getUuid(), agentOnly.session().getUuid(),
				agentOnly.agent().getUuid(), WU);
		AgentTaskData t2 = pending(other);
		ExecutionResult noWrite = hold(agentOnly, t2, QUESTION, "OPERATOR");
		assertTrue(noWrite.getErrors().toString().contains("BOARD_WRITE"), noWrite.getErrors().toString());
		assertEquals(TaskStatus.PENDING_INTAKE, task(t2).getStatus());
	}

	private static final String DELIVERED = "mutation($t: ID!, $s: ID!, $u: String!, $c: String) {"
			+ " agentTaskDeclareDeliveryProgrammatic(taskUuid: $t, sessionUuid: $s, unit: $u, commit: $c) { status } }";
	private static final String COMPLETE = "mutation($t: ID!, $s: ID!) {"
			+ " agentTaskCompleteProgrammatic(taskUuid: $t, sessionUuid: $s, note: \"merged by hand\") { status } }";
	private static final String REOPEN = "mutation($t: ID!, $s: ID!) {"
			+ " agentTaskReopenProgrammatic(taskUuid: $t, sessionUuid: $s, role: \"coder\", reason: \"conflicts\") { status } }";

	@Test
	public void theSeatsDeliveryVerbsAreRefusedWhileAPersonDecides() throws Exception {
		Board b = board();
		AgentTaskData d = delivering(b);
		assertTrue(hold(b.seat(), d, QUESTION, "OPERATOR").getErrors().isEmpty());
		String refusal = AWAITING + "; the person lifts the hold or acts";
		Map<String, Object> vars = Map.of("t", d.getUuid().toString(), "s", b.seat().session().getUuid().toString(),
				"u", d.getPrUrls().get(0), "c", "0123456789abcdef0123456789abcdef01234567");
		for (String op : List.of(DELIVERED, COMPLETE, REOPEN)) {
			ExecutionResult r = asKey(b.seat().key(), op, vars);
			assertTrue(r.getErrors().toString().contains(refusal), op + " -> " + r.getErrors());
			assertEquals(TaskStatus.ON_HOLD, task(d).getStatus());
		}
	}

	@Test
	public void theWritersInboxHasTheQuestionWithoutASubscription() throws Exception {
		Board b = board();
		String tag = UUID.randomUUID().toString().substring(0, 8);
		User u = userService.createUser("Rd417 " + tag, "rd417-" + tag + "@boards.io", true, List.of(b.org().getUuid()),
				"rd417-" + UUID.randomUUID(), OauthType.RELIZA_KEYCLOAK_OWN, WU);
		userService.setUserPermission(u.getUuid(), b.org().getUuid(), PermissionScope.ORGANIZATION, b.org().getUuid(),
				PermissionType.READ_ONLY, List.of(), null, WU);
		userService.setUserPermission(u.getUuid(), b.org().getUuid(), PermissionScope.BOARD, b.board().getUuid(),
				PermissionType.READ_WRITE, List.of(PermissionFunction.BOARD_WRITE), null, WU);
		UserData writer = userService.getUserData(u.getUuid()).orElseThrow();

		AgentTaskData d = delivering(b);
		assertTrue(hold(b.seat(), d, QUESTION, "OPERATOR").getErrors().isEmpty());
		NotificationOutboxEvent e = outboxRepository.findRecentByOrg(b.org().getUuid(), 5000).stream()
				.filter(x -> NotificationEventType.AGENT_TASK_NEEDS_PERSON == x.getEventType())
				.filter(x -> d.getUuid().equals(AgentBoardRenderSupport.payload(x).task())).findFirst().orElseThrow();
		assertTrue(AgentBoardRenderSupport.payload(e).targetUsers().contains(writer.getUuid()),
				"the board's writer is in the event's snapshot");
		List<NotificationDelivery> rows = delivered(e);
		assertTrue(rows.stream().anyMatch(r -> writer.getUuid().equals(r.getTargetUser()) && null == r.getSubscriptionUuid()),
				"an inbox row for the writer, from no subscription: " + rows);
		assertTrue(deliveries.findInboxPage(b.org().getUuid(), writer.getUuid(), false, "{}", "{}", false, null, null, 500, 0)
				.stream().anyMatch(r -> r.getOutboxEventUuid().equals(e.getUuid())), "the writer's inbox holds the question");
	}

	/** Fan the event out (the drain takes the advisory lock, so the live scheduler cannot double it). */
	private List<NotificationDelivery> delivered(NotificationOutboxEvent e) throws InterruptedException {
		for (int i = 0; i < 60; i++) {
			NotificationOutboxEvent now = outboxRepository.findById(e.getUuid()).orElseThrow();
			if (NotificationOutboxStatus.PENDING != now.getStatus()) return deliveries.findByOutboxEventUuid(e.getUuid());
			fanOutService.drainBatch(500);
			Thread.sleep(100);
		}
		throw new AssertionError("event " + e.getUuid() + " was never fanned out");
	}
}
