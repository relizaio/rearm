/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import io.reliza.model.AgentTaskData.HoldKind;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;
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
import io.reliza.model.dto.notifications.AgentBoardEventPayload;
import io.reliza.model.NotificationEventType;
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
 * Task RD4-5: a session holds its own hop for an operator decision, and withdraws its own registration in
 * intake. The agent's side goes through the programmatic GraphQL endpoint with the principal its filter leaves
 * on the request, so the new argument and both gates are exercised as a client meets them; the person's release
 * is the service call the operator-hold mutation makes.
 */
@SpringBootTest(classes = {App.class})
public class AgentSelfHoldAndWithdrawTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final String QUESTION = "Which secret model do we ship: per-org or per-board?";

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
				AuthHeaderParse.fromVerifiedKey(key, 1, "198.51.100.45"));
		HttpHeaders headers = new HttpHeaders();
		headers.set(HttpHeaders.AUTHORIZATION, "Bearer verified-by-the-filter");
		return dgsQueryExecutor.execute(query, vars, Map.of(), headers, null, new ServletWebRequest(request));
	}

	private record Caller(ApiKey key, AgentData agent, AgentSessionData session) {}

	private Caller caller(Organization org, PermissionFunction... fns) throws Exception {
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

	private record Board(Organization org, AgentBoardData board) {}

	private Board board() throws Exception {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("rd45_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "rd45-" + UUID.randomUUID(), "self hold",
				List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics("architect", "design", 10,
				null, false, true, null), true, WU);
		return new Board(org, agentBoardService.getBoardData(board.getUuid()).orElseThrow());
	}

	/** A task the architect role is working, held by {@code holder}'s session. */
	private AgentTaskData assignedTo(Board b, Caller holder) throws RelizaException {
		AgentTaskData t = agentTaskService.register(b.board(), null, "self hold " + UUID.randomUUID(), null, null, null,
				null, null, null, COORD, true, WU);
		agentTaskService.authorize(t.getUuid(), b.board(), "architect", 10, null, null, null, null, COORD, WU);
		agentTaskService.assign(t.getUuid(), b.board(), holder.agent().getUuid(), holder.session().getUuid(), WU);
		return agentTaskService.getTaskData(t.getUuid()).orElseThrow();
	}

	private static final String HOLD = "mutation($t: ID!, $s: ID!, $r: String!, $l: AgentTaskHoldLevel) {"
			+ " agentTaskHoldProgrammatic(taskUuid: $t, sessionUuid: $s, reason: $r, level: $l)"
			+ " { status hold { level kind reason heldBy { kind uuid } } } }";

	private ExecutionResult hold(Caller c, AgentTaskData t, String reason, String level) {
		Map<String, Object> vars = new java.util.HashMap<>(Map.of("t", t.getUuid().toString(),
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

	@Test
	@SuppressWarnings("unchecked")
	public void theHolderParksItsHopAtOperatorLevelOnlyAndThePeopleAreTold() throws Exception {
		Board b = board();
		Caller holder = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		Caller stranger = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		AgentTaskData t = assignedTo(b, holder);

		// a COORDINATOR hold from the holder -- the default level, and said outright -- is the seat's
		for (String level : new String[] {null, "COORDINATOR"}) {
			ExecutionResult coordinatorLevel = hold(holder, t, QUESTION, level);
			assertTrue(coordinatorLevel.getErrors().toString().contains("parks its hop at OPERATOR level only"),
					coordinatorLevel.getErrors().toString());
			assertEquals(TaskStatus.ASSIGNED, task(t).getStatus());
		}
		// another session, of a key that works the board too, holds nothing here
		ExecutionResult notTheHolder = hold(stranger, t, QUESTION, "OPERATOR");
		assertTrue(notTheHolder.getErrors().toString().contains("Only the session holding the task's current assignment"),
				notTheHolder.getErrors().toString());
		assertEquals(TaskStatus.ASSIGNED, task(t).getStatus());
		// the question is the reason, and it is required
		ExecutionResult noQuestion = hold(holder, t, " ", "OPERATOR");
		assertTrue(noQuestion.getErrors().toString().contains("needs the question"), noQuestion.getErrors().toString());

		ExecutionResult parked = hold(holder, t, QUESTION, "OPERATOR");
		assertTrue(parked.getErrors().isEmpty(), parked.getErrors().toString());
		Map<String, Object> out = (Map<String, Object>) ((Map<String, Object>) parked.getData()).get("agentTaskHoldProgrammatic");
		assertEquals("ON_HOLD", out.get("status"));
		Map<String, Object> h = (Map<String, Object>) out.get("hold");
		assertEquals("OPERATOR", h.get("level"));
		assertEquals("MANUAL", h.get("kind"));
		assertEquals("awaiting the operator: " + QUESTION, h.get("reason"));
		assertEquals(holder.session().getUuid().toString(), ((Map<String, Object>) h.get("heldBy")).get("uuid"));

		AgentTaskData held = task(t);
		assertTrue(held.holdParksAHop());
		assertEquals(holder.session().getUuid(), held.getAssignment().session(), "the hop stays assigned to the holder");
		assertTrue(agentTaskService.hasAssignmentsForSession(holder.session().getUuid()),
				"the holder's idle window is still a holder's");
		assertEquals(StatusTrigger.HOLD, lastRow(held).trigger());
		assertEquals(TaskStatus.ASSIGNED, lastRow(held).from());
		assertEquals("awaiting the operator: " + QUESTION, lastRow(held).note());
		// the board's writers are told, through the needs-a-person path
		List<AgentBoardEventPayload> person = outboxRepository.findRecentByOrg(b.org().getUuid(), 5000).stream()
				.filter(e -> NotificationEventType.AGENT_TASK_NEEDS_PERSON == e.getEventType())
				.map(AgentBoardRenderSupport::payload).filter(p -> held.getUuid().equals(p.task())).toList();
		assertEquals(1, person.size(), "the needs-a-person notification is written once");
		assertEquals(HoldKind.MANUAL.name(), person.get(0).holdKind());
		assertEquals("awaiting the operator: " + QUESTION, person.get(0).message());
		assertTrue(agentBoardService.recentEvents(b.board().getUuid()).stream()
				.anyMatch(ev -> ev.message().contains("parked by its architect hop, awaiting the operator: " + QUESTION)),
				"the feed says the hop is parked and on what");

		// the parked hop cannot sign off, and the refusal names what it waits on
		RelizaException signOff = assertThrows(RelizaException.class, () -> agentTaskService.signOff(t.getUuid(),
				holder.session().getUuid(), SignOffOutcome.PASSED, "done", WU));
		assertTrue(signOff.getMessage().contains("is parked, awaiting the operator: " + QUESTION), signOff.getMessage());
		// nor park it twice
		ExecutionResult again = hold(holder, t, "another question", "OPERATOR");
		assertTrue(again.getErrors().toString().contains("Only the session holding")
				|| again.getErrors().toString().contains("is parked"), again.getErrors().toString());
	}

	@Test
	public void aPersonsReleaseNeedsTheAnswerAndRecordsItAndTheHopResumes() throws Exception {
		Board b = board();
		Caller holder = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		AgentTaskData t = assignedTo(b, holder);
		assertTrue(hold(holder, t, QUESTION, "OPERATOR").getErrors().isEmpty());
		AgentActor person = AgentActor.ofUser(UUID.randomUUID(), "Pat Operator");

		// the coordinator cannot lift it
		RelizaException bySeat = assertThrows(RelizaException.class, () -> agentTaskService.liftHold(t.getUuid(),
				HoldLevel.COORDINATOR, COORD, "go on", null, WU));
		assertTrue(bySeat.getMessage().contains("carries an OPERATOR hold"), bySeat.getMessage());
		// a person's release without words is refused: the words are the answer
		RelizaException noNote = assertThrows(RelizaException.class, () -> agentTaskService.liftHoldOrAnswer(
				t.getUuid(), HoldLevel.OPERATOR, person, "  ", null, WU));
		assertTrue(noNote.getMessage().contains("with your answer as the note"), noNote.getMessage());
		// and the hop resumes with its holder, so no role is routed to
		RelizaException withRole = assertThrows(RelizaException.class, () -> agentTaskService.liftHoldOrAnswer(
				t.getUuid(), HoldLevel.OPERATOR, person, "per-org", "architect", WU));
		assertTrue(withRole.getMessage().contains("lift it without a role"), withRole.getMessage());
		assertEquals(TaskStatus.ON_HOLD, task(t).getStatus());

		AgentTaskData released = agentTaskService.liftHoldOrAnswer(t.getUuid(), HoldLevel.OPERATOR, person,
				"per-org, rotated yearly", null, WU);
		assertEquals(TaskStatus.ASSIGNED, released.getStatus());
		assertNull(released.getHold());
		assertEquals(holder.session().getUuid(), released.getAssignment().session(), "the hop is the holder's again");
		StatusChange row = lastRow(released);
		assertEquals(StatusTrigger.LIFT_HOLD, row.trigger());
		assertEquals("lifted by Pat Operator: per-org, rotated yearly", row.note());
		assertEquals(person, row.actor());
		assertTrue(agentBoardService.recentEvents(b.board().getUuid()).stream()
				.anyMatch(ev -> AgentBoardData.BoardEventKind.INFO == ev.kind()
						&& ev.message().contains(released.label() + " answered by Pat Operator: per-org, rotated yearly")
						&& ev.message().contains("(the question: " + QUESTION + ")")),
				"an INFO names the task and the answer");
		// the question stays on the record beside the answer
		StatusChange asked = released.getStatusHistory().get(released.getStatusHistory().size() - 2);
		assertEquals("awaiting the operator: " + QUESTION, asked.note());

		// the holder carries on: its sign-off is accepted again
		AgentTaskData signed = agentTaskService.signOff(t.getUuid(), holder.session().getUuid(), SignOffOutcome.PASSED,
				"designed with the operator's answer", WU);
		assertNotNull(signed);
		assertFalse(TaskStatus.ASSIGNED == signed.getStatus() && null != signed.getAssignment(), "the hop ended");
	}

	@Test
	public void aParkedHopWhoseSessionClosesStaysParkedAndTheAnswerQueuesItsRole() throws Exception {
		Board b = board();
		Caller holder = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		AgentTaskData t = assignedTo(b, holder);
		assertTrue(hold(holder, t, QUESTION, "OPERATOR").getErrors().isEmpty());

		agentSessionService.close(holder.session().getUuid(), WU);
		AgentTaskData closed = task(t);
		assertEquals(TaskStatus.ON_HOLD, closed.getStatus(), "the question still waits on a person");
		assertNull(closed.getAssignment());
		assertTrue(closed.holdParksAHop());

		AgentTaskData released = agentTaskService.liftHoldOrAnswer(t.getUuid(), HoldLevel.OPERATOR,
				AgentActor.ofUser(UUID.randomUUID(), "Pat Operator"), "per-board", null, WU);
		assertEquals(TaskStatus.QUEUED, released.getStatus());
		assertEquals("architect", released.getRole());
		assertEquals("lifted by Pat Operator: per-board", lastRow(released).note());
	}

	/** The seat's OPERATOR hold (task RD4-17) is {@code AgentSeatOperatorHoldTest}'s; this is its COORDINATOR hold. */
	@Test
	public void theSeatStillHoldsAtCoordinatorLevel() throws Exception {
		Board b = board();
		Caller seat = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		agentBoardService.claimCoordinatorSeat(b.board().getUuid(), seat.session().getUuid(), seat.agent().getUuid(), WU);
		AgentTaskData t = agentTaskService.register(b.board(), null, "seat hold " + UUID.randomUUID(), null, null, null,
				null, null, null, COORD, true, WU);

		ExecutionResult held = hold(seat, t, "waiting on the tracker", null);
		assertTrue(held.getErrors().isEmpty(), held.getErrors().toString());
		AgentTaskData after = task(t);
		assertEquals(TaskStatus.ON_HOLD, after.getStatus());
		assertEquals(HoldLevel.COORDINATOR, after.getHold().level());
		assertFalse(after.holdParksAHop());

		// a seat key without BOARD_WRITE holds nothing, as before the verb had a second caller
		Board other = board();
		Caller agentOnly = caller(other.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_AGENT);
		agentBoardService.claimCoordinatorSeat(other.board().getUuid(), agentOnly.session().getUuid(),
				agentOnly.agent().getUuid(), WU);
		AgentTaskData t2 = agentTaskService.register(other.board(), null, "seat hold " + UUID.randomUUID(), null, null,
				null, null, null, null, COORD, true, WU);
		ExecutionResult noWrite = hold(agentOnly, t2, "waiting", null);
		assertTrue(noWrite.getErrors().toString().contains("BOARD_WRITE"), noWrite.getErrors().toString());
		assertEquals(TaskStatus.PENDING_INTAKE, task(t2).getStatus());
	}

	private static final String CANCEL = "mutation($t: ID!, $s: ID!, $n: String) {"
			+ " agentTaskCancelProgrammatic(taskUuid: $t, sessionUuid: $s, note: $n) { status statusHistory { trigger note } } }";

	private ExecutionResult cancel(Caller c, AgentTaskData t, String note) {
		Map<String, Object> vars = new java.util.HashMap<>(Map.of("t", t.getUuid().toString(),
				"s", c.session().getUuid().toString()));
		if (null != note) vars.put("n", note);
		return asKey(c.key(), CANCEL, vars);
	}

	@Test
	public void theRegistrantWithdrawsInIntakeOnlyAndNobodyElseDoes() throws Exception {
		Board b = board();
		Caller registrant = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		Caller other = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		AgentTaskData t = agentTaskService.register(b.board(), null, "duplicate " + UUID.randomUUID(), null,
				registrant.session().getUuid(), null, null, null, null, AgentActor.ofSession(registrant.session().getUuid()),
				true, WU);

		ExecutionResult byOther = cancel(other, t, "not mine to withdraw");
		assertTrue(byOther.getErrors().toString().contains("only its registrant withdraws it"), byOther.getErrors().toString());
		assertEquals(TaskStatus.PENDING_INTAKE, task(t).getStatus());
		ExecutionResult noReason = cancel(registrant, t, null);
		assertTrue(noReason.getErrors().toString().contains("A withdrawal needs a reason"), noReason.getErrors().toString());
		assertEquals(TaskStatus.PENDING_INTAKE, task(t).getStatus());

		ExecutionResult withdrawn = cancel(registrant, t, "duplicate of the task registered a minute earlier");
		assertTrue(withdrawn.getErrors().isEmpty(), withdrawn.getErrors().toString());
		AgentTaskData after = task(t);
		assertEquals(TaskStatus.CANCELLED, after.getStatus());
		StatusChange row = lastRow(after);
		assertEquals(StatusTrigger.CANCEL, row.trigger());
		assertEquals("withdrawn by its registrant: duplicate of the task registered a minute earlier", row.note());
		assertEquals(AgentActor.ofSession(registrant.session().getUuid()), row.actor());
		assertTrue(agentBoardService.recentEvents(b.board().getUuid()).stream()
				.anyMatch(ev -> ev.message().contains(after.label() + " withdrawn by its registrant: duplicate")),
				"the withdrawal is on the feed");
	}

	@Test
	public void aWithdrawalIsRefusedOnceTheCoordinatorAuthorisedTheTask() throws Exception {
		Board b = board();
		Caller registrant = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		AgentTaskData t = agentTaskService.register(b.board(), null, "authorised " + UUID.randomUUID(), null,
				registrant.session().getUuid(), null, null, null, null, AgentActor.ofSession(registrant.session().getUuid()),
				true, WU);
		agentTaskService.authorize(t.getUuid(), b.board(), "architect", 10, null, null, null, null, COORD, WU);

		ExecutionResult refused = cancel(registrant, t, "changed my mind");
		assertTrue(refused.getErrors().toString().contains("the coordinator owns this task now; return it or ask the seat"),
				refused.getErrors().toString());
		assertEquals(TaskStatus.QUEUED, task(t).getStatus());

		// the seat still cancels, whoever registered the task
		Caller seat = caller(b.org(), PermissionFunction.AGENT, PermissionFunction.BOARD_WRITE);
		agentBoardService.claimCoordinatorSeat(b.board().getUuid(), seat.session().getUuid(), seat.agent().getUuid(), WU);
		ExecutionResult bySeat = cancel(seat, t, "not needed");
		assertTrue(bySeat.getErrors().isEmpty(), bySeat.getErrors().toString());
		assertEquals(TaskStatus.CANCELLED, task(t).getStatus());
		assertEquals("not needed", lastRow(task(t)).note());
	}
}
