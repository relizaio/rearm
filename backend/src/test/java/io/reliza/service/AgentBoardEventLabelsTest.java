/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentActor;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.SignOffOutcome;
import io.reliza.model.AgentTaskData.TaskReturnReason;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.model.AgentTaskInput.InputScope;
import io.reliza.model.AgentTaskRoleConfigData.ProducedOutput;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.ReleaseData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.BoardReviewItemIndex.BoardReviewItemStatus;
import io.reliza.service.AgentDocumentService.Answer;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.ws.oss.TestInitializer;

/**
 * The task key wherever the board's feed names a task (task RD2-22, board-documents.md D12). The sweep
 * saw one task named three ways -- key and title, title only ("Hold lifted on task <title>"), raw uuid
 * ("Question answered on task <uuid>"). Each path the sweep saw is driven here through the services, and
 * every event about a task reads its label: the key, then the title or its truncation, never the uuid.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {io.reliza.ws.App.class})
public class AgentBoardEventLabelsTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String DOCS = "https://github.com/acme/labels-docs";
	private static final AgentActor COORD = AgentActor.ofSession(UUID.randomUUID());
	private static final AgentActor HUMAN = AgentActor.ofUser(UUID.randomUUID(), "operator");
	/** Longer than the label keeps, so the label's truncation is what the feed must carry. */
	private static final String LONG = " -- a title long enough that the board's label cuts it short before the end";

	@Autowired private TestInitializer testInitializer;
	@Autowired private AgentBoardService agentBoardService;
	@Autowired private AgentTaskService agentTaskService;
	@Autowired private AgentDocumentService agentDocumentService;
	@Autowired private AgentService agentService;
	@Autowired private AgentSessionService agentSessionService;
	@Autowired private ComponentService componentService;

	private record Rig(AgentBoardData board, AgentData workerA, AgentSessionData sessA, AgentData workerB,
			AgentSessionData sessB) {}

	/** designer (produces ARCHITECTURE) and coder, a documents repository, and two agents with sessions. */
	private Rig rig() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component target = componentService.createComponent("node_" + UUID.randomUUID(), org.getUuid(),
				ComponentType.PRODUCT, "semver", "Branch.Micro", null, WU);
		AgentBoardData board = agentBoardService.createBoard(org.getUuid(), "labels-" + UUID.randomUUID(),
				"labels board", List.of(), "coordinate", 4, null, target.getUuid(), null, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("designer", "design it", 10,
				null, false, true, null, null, null, null, List.of(),
				List.of(new ProducedOutput(RearmSpecificationType.ARCHITECTURE, InputScope.COMPONENT, false))), true, WU);
		agentBoardService.upsertRoleConfig(board, new AgentBoardService.RoleConfigSpec("coder", "build it", 20,
				null, false, true, null, null, null, null, List.of(), List.of()), true, WU);
		board = agentBoardService.setDocumentsConfig(board.getUuid(), DOCS, null, WU);
		AgentData a = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(), "la-" + UUID.randomUUID(),
				null, null, null, WU);
		AgentData b = agentService.findOrRegisterRootAgent(org.getUuid(), UUID.randomUUID(), "lb-" + UUID.randomUUID(),
				null, null, null, WU);
		return new Rig(board, a, session(org, a), b, session(org, b));
	}

	private AgentSessionData session(Organization org, AgentData agent) throws RelizaException {
		return agentSessionService.initialize(org.getUuid(), agent.getUuid(), null, "s-" + UUID.randomUUID(),
				"labels session", null, null, WU);
	}

	private AgentBoardData board(Rig r) {
		return agentBoardService.getBoardData(r.board().getUuid()).orElseThrow();
	}

	private AgentTaskData reload(AgentTaskData td) {
		return agentTaskService.getTaskData(td.getUuid()).orElseThrow();
	}

	/** A task registered and authorized for the coder: keyed, with a title the label truncates. */
	private AgentTaskData queued(Rig r, String what) throws RelizaException {
		AgentTaskData t = agentTaskService.register(board(r), null, what + LONG, null, null, null, null, null, null, WU);
		return agentTaskService.authorize(t.getUuid(), board(r), "coder", 10, null, null, null, null, COORD, WU);
	}

	/** The board's event log, as the feed reads it. */
	private List<String> events(Rig r) {
		try {
			return agentBoardService.eventsOf(r.board().getUuid(), null, null, AgentBoardService.EVENTS_PAGE_MAX).events()
					.stream().map(AgentBoardService.LoggedEvent::message).toList();
		} catch (RelizaException e) {
			throw new IllegalStateException(e);
		}
	}

	/** The one event beginning with {@code prefix}, which must carry the task's label. */
	private void saysTheLabel(Rig r, AgentTaskData td, String prefix) {
		AgentTaskData t = reload(td);
		List<String> hits = events(r).stream().filter(m -> m.startsWith(prefix)).toList();
		assertFalse(hits.isEmpty(), "an event beginning '" + prefix + "' in " + events(r));
		for (String m : hits) {
			assertTrue(m.contains(t.label()), "'" + m + "' names the task by its label '" + t.label() + "'");
		}
	}

	private static Map<String, Object> questionsAbout(RearmSpecificationType spec) {
		Map<String, Object> item = new LinkedHashMap<>();
		item.put("id", "q1");
		item.put("priority", 1);
		item.put("status", "OPEN");
		item.put("title", "which branch?");
		Map<String, Object> about = new LinkedHashMap<>();
		about.put("specification", spec.name());
		about.put("release", null);
		Map<String, Object> idx = new LinkedHashMap<>();
		idx.put("kind", "BOARD_QUESTIONS");
		idx.put("verdict", "REJECTED");
		idx.put("reviewItems", new ArrayList<>(List.of(item)));
		idx.put("about", about);
		return idx;
	}

	@Test
	public void everyEventTheSweepSawNamesTheTaskByItsKeyAndTitle() throws RelizaException {
		Rig r = rig();

		// A question a person answers, as on the sweep: the coder asks about a test plan no role makes,
		// the coordinator escalates it, a person answers, and the board says so.
		AgentTaskData answered = queued(r, "answered");
		agentTaskService.assign(answered.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		ReleaseData q = agentDocumentService.publish(r.sessA(), new PublishRequest(answered.getUuid(),
				RearmSpecificationType.BOARD_QUESTIONS, null, null, null, null, null, null, questionsAbout(RearmSpecificationType.TEST_PLAN),
				null, null, null, null, null), WU);
		assertEquals(TaskStatus.AWAITING_COORDINATOR, agentTaskService.signOff(answered.getUuid(), r.sessA().getUuid(),
				SignOffOutcome.REJECTED, "who owns the test plan?", List.of(q.getUuid()), WU).getStatus());
		agentTaskService.escalateQuestion(answered.getUuid(), HoldLevel.OPERATOR, "nobody here makes a test plan", COORD, WU);
		assertEquals("coder", agentTaskService.answer(answered.getUuid(),
				List.of(new Answer("q1", BoardReviewItemStatus.RESOLVED, "the platform team owns it")), HUMAN, true, WU).getRole(),
				"back to the asker");
		saysTheLabel(r, answered, "Question answered on task ");

		// A hold a person places and releases; the task's budget set.
		AgentTaskData held = queued(r, "held");
		agentTaskService.hold(held.getUuid(), HoldLevel.OPERATOR, "wait for the freeze", HUMAN, WU);
		agentTaskService.liftHold(held.getUuid(), HoldLevel.OPERATOR, HUMAN, "the freeze is over", WU);
		saysTheLabel(r, held, "Hold lifted on task ");
		agentTaskService.setBudget(held.getUuid(), 5_000_000L, HUMAN, WU);
		saysTheLabel(r, held, "Budget of task ");

		// The coordinator's hold escalated to the operator.
		AgentTaskData escalated = queued(r, "escalated");
		agentTaskService.hold(escalated.getUuid(), HoldLevel.COORDINATOR, "unsure", COORD, WU);
		assertEquals(TaskStatus.ON_HOLD, agentTaskService.escalateHold(escalated.getUuid(), "a person must decide",
				COORD, WU).getStatus());
		assertTrue(events(r).stream().anyMatch(m -> m.startsWith("Task " + reload(escalated).label() + " escalated")),
				events(r).toString());

		// A hop returned.
		AgentTaskData returned = queued(r, "returned");
		agentTaskService.assign(returned.getUuid(), board(r), r.workerA().getUuid(), r.sessA().getUuid(), WU);
		agentTaskService.returnTask(returned.getUuid(), r.sessA().getUuid(), TaskReturnReason.TASK_UNCLEAR,
				"what is done?", WU);
		assertTrue(events(r).stream().anyMatch(m -> m.startsWith("Task " + reload(returned).label() + " returned by coder")),
				events(r).toString());

		// And no event anywhere on the board names a task by its uuid, or by its title without its key.
		for (AgentTaskData t : List.of(answered, held, escalated, returned)) {
			AgentTaskData now = reload(t);
			assertTrue(now.getKey().contains("-"), "keyed: " + now.getKey());
			assertTrue(now.label().endsWith("…"), "the label truncates the long title: " + now.label());
			String opening = now.getTitle().substring(0, 20);
			for (String m : events(r)) {
				assertFalse(m.contains(now.getUuid().toString()), "no raw uuid: " + m);
				if (m.contains(opening)) assertTrue(m.contains(now.getKey() + " " + opening), "the key leads the title: " + m);
			}
		}
	}
}
