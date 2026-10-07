/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import io.reliza.exceptions.ActionRefusedException;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ActionGuard;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentBoardData.BoardPauseLevel;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.QuestionFrame;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputKind;
import io.reliza.model.AgentTaskInput.InputResolution;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskInput.RequiredInput;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.AgentTaskRoleConfigData.HumanGate;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.AgentTaskRoleConfigData.RoleNecessity;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.BoardReviewItemIndex;
import io.reliza.model.BoardReviewItemIndex.About;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItem;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.model.BoardReviewItemIndex.BoardReviewVerdict;
import io.reliza.model.GuardMode;
import io.reliza.model.GuardedAction;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ComponentDto;
import io.reliza.service.AgentDocumentService.BoardReviewItemDecision;
import io.reliza.service.AgentDocumentService.BoardReviewItemDecisionAction;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.oss.TestInitializer;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.TaskHold;
import io.reliza.model.AgentTaskData.HoldKind;
import io.reliza.model.dto.notifications.AgentBoardEventPayload;
import io.reliza.repositories.NotificationOutboxEventRepository;
import java.time.ZonedDateTime;


/**
 * Board events that need a person become notifications (task 82880ea6, gaps §1.1).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {io.reliza.ws.App.class})
public class AgentBoardNotificationIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String DOCS = "https://github.com/acme/notify-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AgentActor HUMAN = AgentActor.ofUser(UUID.randomUUID(), "operator");

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private ComponentService componentService;
	@Autowired private NotificationOutboxEventRepository outbox;
	@Autowired private BoardQueueAgeService boardQueueAgeService;

	private record Rig(Organization org, AgentBoardData board, AgentData a, AgentSessionData sa,
			AgentData b, AgentSessionData sb) {}

	/** designer, then a reviewer gated on every sign-off, then a coder. */
	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "notify-" + UUID.randomUUID(),
				"notifications", List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("designer", "design it", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE, InputScope.TASK, false))), true, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("reviewer", "review it", 20,
				null, false, true, null, null, RoleNecessity.OPTIONAL, HumanGate.ON_ANY_SIGNOFF, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.BOARD_REVIEW_ITEMS, InputScope.TASK, false))), true, WU);
		agentBoardService.upsertRoleConfig(board, AgentBoardService.RoleConfigSpec.ofBasics("coder", "build it", 30,
				null, false, true, null), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData a = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(), "na-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentData b = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(), "nb-" + UUID.randomUUID(),
				null, null, null, WU);
		return new Rig(org, board, a, session(org, a), b, session(org, b));
	}

	private AgentSessionData session(Organization org, AgentData agent) throws RelizaException {
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), null, "s-" + UUID.randomUUID(),
				"session", null, null, WU);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData task(Rig r, String role) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), null, "notify " + UUID.randomUUID(), null, null, null,
				null, null, null, HUMAN, true, WU);
		agentTaskService.authorize(t.getUuid(), board(r), role, 10, null, null, null, null, COORD, WU);
		return t;
	}

	/** This board's rows of a type, newest first. */
	private List<NotificationOutboxEvent> rows(Rig r, NotificationEventType type) {
		return outbox.findRecentByOrg(r.org().getUuid(), 5000).stream()
				.filter(e -> e.getEventType() == type)
				.filter(e -> String.valueOf(r.board().getUuid()).equals(String.valueOf(e.getRecordData().get("board"))))
				.toList();
	}

	private static AgentBoardEventPayload payload(NotificationOutboxEvent e) {
		return AgentBoardRenderSupport.payload(e);
	}

	@Test
	public void anAlertBecomesOneNotificationInsideTheWindow() throws RelizaException {
		Rig r = rig();
		String message = "Task x stopped by budget " + UUID.randomUUID();
		agentBoardService.postEvent(r.board().getUuid(), AgentBoardData.BoardEventKind.ALERT, message, COORD, WU);
		List<NotificationOutboxEvent> alerts = rows(r, NotificationEventType.AGENT_BOARD_ALERT);
		assertEquals(1, alerts.size());
		AgentBoardEventPayload p = payload(alerts.get(0));
		assertEquals(r.board().getUuid(), p.board());
		assertEquals(r.board().getName(), p.boardName());
		assertEquals("ALERT", p.kind());
		assertEquals(message, p.message());
		assertEquals(AgentBoardNotifier.alertKey(r.board().getUuid(), message), alerts.get(0).getDedupKey());

		agentBoardService.postEvent(r.board().getUuid(), AgentBoardData.BoardEventKind.ALERT, message, COORD, WU);
		assertEquals(1, rows(r, NotificationEventType.AGENT_BOARD_ALERT).size(), "the same text again is not a second");
		agentBoardService.postEvent(r.board().getUuid(), AgentBoardData.BoardEventKind.INFO, "just so you know", COORD, WU);
		agentBoardService.postEvent(r.board().getUuid(), AgentBoardData.BoardEventKind.ALERT, message + " (again, worse)",
				COORD, WU);
		assertEquals(2, rows(r, NotificationEventType.AGENT_BOARD_ALERT).size(), "INFO is not an alert; new text is");

		agentBoardService.setPause(r.board().getUuid(), BoardPauseLevel.OPERATOR, true, "budget spent", HUMAN, WU);
		assertTrue(rows(r, NotificationEventType.AGENT_BOARD_ALERT).stream()
				.anyMatch(e -> "PAUSED".equals(payload(e).kind())), "a pause is an alert too");
	}

	@Test
	public void aGateAndAnOperatorHoldNeedAPerson() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "reviewer");
		agentTaskService.assign(t.getUuid(), board(r), r.a().getUuid(), r.sa().getUuid(), WU);
		ReleaseData round = agentDocumentService.publish(r.sa(), new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_REVIEW_ITEMS, null, null, null, null, null, null,
				Map.of("kind", "BOARD_REVIEW_ITEMS", "verdict", "PASSED", "reviewItems", List.of()), null, null, null,
				null, null), WU);
		AgentTaskData gated = agentTaskService.signOff(t.getUuid(), r.sa().getUuid(), SignOffOutcome.PASSED, "done",
				List.of(round.getUuid()), WU);
		assertEquals(HoldKind.HUMAN_GATE, gated.getHold().kind());
		List<NotificationOutboxEvent> person = rows(r, NotificationEventType.AGENT_TASK_NEEDS_PERSON);
		assertEquals(1, person.size());
		AgentBoardEventPayload p = payload(person.get(0));
		assertEquals(t.getUuid(), p.task());
		assertEquals("HUMAN_GATE", p.holdKind());
		assertEquals(AgentBoardEventPayload.taskLink(t.getUuid()), p.link());

		// a person holding another task for an operator
		AgentTaskData other = task(r, "coder");
		agentTaskService.hold(other.getUuid(), HoldLevel.OPERATOR, "waiting on legal", HUMAN, WU);
		assertTrue(rows(r, NotificationEventType.AGENT_TASK_NEEDS_PERSON).stream()
				.anyMatch(e -> other.getUuid().equals(payload(e).task()) && "OPERATOR".equals(payload(e).holdLevel())));
		// saving the held task again for something else is not a second notification
		int before = rows(r, NotificationEventType.AGENT_TASK_NEEDS_PERSON).size();
		AgentTaskData again = agentTaskService.getTaskData(other.getUuid()).orElseThrow();
		agentTaskService.saveData(again, WU);
		assertEquals(before, rows(r, NotificationEventType.AGENT_TASK_NEEDS_PERSON).size());
	}

	@Test
	public void aCoordinatorHoldAndAnAnsweredQuestionNeedNobody() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "coder");
		agentTaskService.hold(t.getUuid(), HoldLevel.COORDINATOR, "the coordinator parks it", COORD, WU);
		// a question about the design, which the designer produces: routed to the designer, no hold
		AgentTaskData q = task(r, "coder");
		agentTaskService.assign(q.getUuid(), board(r), r.a().getUuid(), r.sa().getUuid(), WU);
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", "BOARD_QUESTIONS");
		idx.put("verdict", "REJECTED");
		idx.put("reviewItems", new ArrayList<>(List.of(Map.of("id", "q1", "priority", 1, "status", "OPEN", "title", "which?"))));
		idx.put("about", Map.of("specification", "ARCHITECTURE"));
		ReleaseData questions = agentDocumentService.publish(r.sa(), new PublishRequest(q.getUuid(),
				RearmSpecificationType.BOARD_QUESTIONS, null, null, null, null, null, null, idx, null, null, null, null,
				null), WU);
		AgentTaskData asked = agentTaskService.signOff(q.getUuid(), r.sa().getUuid(), SignOffOutcome.REJECTED,
				"asked", List.of(questions.getUuid()), WU);
		assertEquals("designer", asked.getRole(), "a question with a producer goes to it");
		assertEquals(0, rows(r, NotificationEventType.AGENT_TASK_NEEDS_PERSON).size());
	}

	@Test
	public void aReturnIsNotifiedAndASessionClosingIsNot() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "coder");
		agentTaskService.assign(t.getUuid(), board(r), r.a().getUuid(), r.sa().getUuid(), WU);
		agentTaskService.returnTask(t.getUuid(), r.sa().getUuid(), TaskReturnReason.TASK_UNCLEAR, "what is the scope?",
				WU);
		List<NotificationOutboxEvent> returned = rows(r, NotificationEventType.AGENT_TASK_RETURNED);
		assertEquals(1, returned.size());
		assertEquals("TASK_UNCLEAR", payload(returned.get(0)).kind());
		assertTrue(payload(returned.get(0)).message().contains("what is the scope?"));

		AgentTaskData other = task(r, "coder");
		agentTaskService.assign(other.getUuid(), board(r), r.b().getUuid(), r.sb().getUuid(), WU);
		agentSessionService.close(r.sb().getUuid(), WU);
		assertEquals(1, rows(r, NotificationEventType.AGENT_TASK_RETURNED).size(), "a session closing is not news");
	}

	@Test
	public void aTaskWaitingPastTheThresholdIsToldOnce() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "coder");
		agentTaskService.hold(t.getUuid(), HoldLevel.OPERATOR, "legal", HUMAN, WU);
		// held 31 minutes ago
		AgentTaskData held = agentTaskService.getTaskData(t.getUuid()).orElseThrow();
		TaskHold h = held.getHold();
		ZonedDateTime heldAt = ZonedDateTime.now().minusMinutes(31);
		held.setHold(new TaskHold(h.level(), h.kind(), h.gateRole(), h.reason(), h.heldBy(), heldAt));
		agentTaskService.saveData(held, WU);

		boardQueueAgeService.sweep(ZonedDateTime.now());
		assertEquals(0, rows(r, NotificationEventType.AGENT_TASK_QUEUE_AGE).size(), "threshold 0 is off");

		AgentBoardData bd = board(r);
		bd.setHumanQueueAgeMinutes(30);
		agentBoardService.saveData(bd, WU);
		boardQueueAgeService.sweep(ZonedDateTime.now());
		List<NotificationOutboxEvent> aged = rows(r, NotificationEventType.AGENT_TASK_QUEUE_AGE);
		assertEquals(1, aged.size());
		assertTrue(payload(aged.get(0)).ageMinutes() >= 31);
		boardQueueAgeService.sweep(ZonedDateTime.now().plusMinutes(1));
		assertEquals(1, rows(r, NotificationEventType.AGENT_TASK_QUEUE_AGE).size(), "the next tick adds nothing");
	}

	/**
	 * One inbox row per gate (task RD2-15, sweep UI-45): a human gate wrote AGENT_TASK_NEEDS_PERSON and,
	 * from its "awaits human review" ALERT, an AGENT_BOARD_ALERT too -- the same gate twice. The ALERT stays
	 * on the board's feed and goes out as no notification; any other ALERT still does.
	 */
	@Test
	public void aGateIsOneNotificationAndItsAlertStaysOnTheFeed() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = task(r, "reviewer");
		agentTaskService.assign(t.getUuid(), board(r), r.a().getUuid(), r.sa().getUuid(), WU);
		ReleaseData round = agentDocumentService.publish(r.sa(), new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_REVIEW_ITEMS, null, null, null, null, null, null,
				Map.of("kind", "BOARD_REVIEW_ITEMS", "verdict", "PASSED", "reviewItems", List.of()), null, null, null,
				null, null), WU);
		AgentTaskData gated = agentTaskService.signOff(t.getUuid(), r.sa().getUuid(), SignOffOutcome.PASSED, "done",
				List.of(round.getUuid()), WU);
		assertEquals(HoldKind.HUMAN_GATE, gated.getHold().kind());
		assertEquals(1, rows(r, NotificationEventType.AGENT_TASK_NEEDS_PERSON).size(), "the gate, once");
		assertEquals(0, rows(r, NotificationEventType.AGENT_BOARD_ALERT).size(), "and not again as a board alert");
		assertTrue(agentBoardService.eventsOf(r.board().getUuid(), null, null, AgentBoardService.EVENTS_PAGE_MAX).events()
				.stream().anyMatch(e -> AgentBoardData.BoardEventKind.ALERT == e.kind()
						&& e.message().contains(" awaits human review of its reviewer sign-off")),
				"the feed still says it");

		String plain = "coordinator needs a person " + UUID.randomUUID();
		agentBoardService.postEvent(r.board().getUuid(), AgentBoardData.BoardEventKind.ALERT, plain, COORD, WU);
		assertEquals(1, rows(r, NotificationEventType.AGENT_BOARD_ALERT).size(), "any other ALERT is still sent");
	}
}

