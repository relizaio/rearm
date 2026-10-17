/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.HoldKind;
import io.reliza.model.AgentTaskData.HoldLevel;
import io.reliza.model.AgentTaskData.TaskHold;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.dto.notifications.AgentBoardEventPayload;

/**
 * Board notifications (task 82880ea6): which holds need a person, what every channel says, and that
 * a notification that cannot be written never fails what it reports on.
 */
class AgentBoardNotifierTest {

	private static TaskHold hold(HoldLevel level, HoldKind kind, ZonedDateTime at) {
		return new TaskHold(level, kind, null, "why", null, at);
	}

	@Test
	void theHoldsOnlyAPersonCanRelease() {
		ZonedDateTime at = ZonedDateTime.now();
		assertTrue(AgentBoardNotifier.needsAPerson(hold(HoldLevel.OPERATOR, HoldKind.HUMAN_GATE, at)));
		assertTrue(AgentBoardNotifier.needsAPerson(hold(HoldLevel.OPERATOR, HoldKind.MANUAL, at)));
		assertTrue(AgentBoardNotifier.needsAPerson(hold(HoldLevel.COORDINATOR, HoldKind.QUESTION, at)));
		assertFalse(AgentBoardNotifier.needsAPerson(hold(HoldLevel.COORDINATOR, HoldKind.MANUAL, at)),
				"the coordinator's own hold is the coordinator's to lift");
		assertFalse(AgentBoardNotifier.needsAPerson(null));
	}

	@Test
	void onlyAPlacementCounts() {
		ZonedDateTime at = ZonedDateTime.now();
		TaskHold gate = hold(HoldLevel.OPERATOR, HoldKind.HUMAN_GATE, at);
		assertTrue(AgentBoardNotifier.placedForAPerson(null, gate));
		assertFalse(AgentBoardNotifier.placedForAPerson(gate, gate), "a re-save of the same hold");
		assertTrue(AgentBoardNotifier.placedForAPerson(gate, hold(HoldLevel.OPERATOR, HoldKind.HUMAN_GATE, at.plusSeconds(1))),
				"a new hold of the same kind");
		assertTrue(AgentBoardNotifier.placedForAPerson(hold(HoldLevel.COORDINATOR, HoldKind.MANUAL, at),
				hold(HoldLevel.OPERATOR, HoldKind.MANUAL, at)), "raised to an operator");
		assertFalse(AgentBoardNotifier.placedForAPerson(gate, null), "a release");
	}

	@Test
	void aFailingWriteNeverReachesTheCaller() throws Exception {
		AgentBoardNotifier notifier = new AgentBoardNotifier();
		AgentBoardNotifier self = mock(AgentBoardNotifier.class);
		doThrow(new RuntimeException("outbox down")).when(self).write(any(), any(), any(), any(), any());
		AgentBoardService boards = mock(AgentBoardService.class);
		when(boards.getBoardData(any())).thenReturn(Optional.empty());
		inject(notifier, "self", self);
		inject(notifier, "agentBoardService", boards);
		AgentTaskData td = mock(AgentTaskData.class);
		when(td.getUuid()).thenReturn(UUID.randomUUID());
		when(td.getOrg()).thenReturn(UUID.randomUUID());
		when(td.getBoard()).thenReturn(UUID.randomUUID());
		when(td.getHold()).thenReturn(hold(HoldLevel.OPERATOR, HoldKind.HUMAN_GATE, ZonedDateTime.now()));
		assertDoesNotThrow(() -> notifier.needsPerson(td), "the sign-off that placed the gate stands");
		verify(self).write(any(), eq(NotificationEventType.AGENT_TASK_NEEDS_PERSON), any(), any(), any());
	}

	@Test
	void aSessionClosingIsNotAReturnWorthTelling() throws Exception {
		AgentBoardNotifier notifier = new AgentBoardNotifier();
		AgentBoardNotifier self = mock(AgentBoardNotifier.class);
		AgentBoardService boards = mock(AgentBoardService.class);
		when(boards.getBoardData(any())).thenReturn(Optional.empty());
		inject(notifier, "self", self);
		inject(notifier, "agentBoardService", boards);
		AgentTaskData td = mock(AgentTaskData.class);
		when(td.getUuid()).thenReturn(UUID.randomUUID());
		when(td.getOrg()).thenReturn(UUID.randomUUID());
		notifier.returned(td, new AgentTaskData.TaskReturn("coder", null, null, null,
				AgentTaskData.TaskReturnReason.SESSION_CLOSED, "closed", ZonedDateTime.now(), null, null));
		verify(self, org.mockito.Mockito.never()).write(any(), any(), any(), any(), any());
		notifier.returned(td, new AgentTaskData.TaskReturn("coder", null, null, null,
				AgentTaskData.TaskReturnReason.TASK_UNCLEAR, "scope?", ZonedDateTime.now(), null, null));
		verify(self).write(any(), eq(NotificationEventType.AGENT_TASK_RETURNED), any(), any(), any());
	}

	@Test
	void everyChannelSaysTheSameHeadline() {
		UUID task = UUID.randomUUID();
		AgentBoardEventPayload gate = new AgentBoardEventPayload(UUID.randomUUID(), "Dogfood", task, "DOG-7 Task page",
				"HUMAN_GATE", "review the pass", "HUMAN_GATE", "OPERATOR", AgentBoardEventPayload.taskLink(task), null);
		// The task leads, key first (RD2-22), then its board.
		assertEquals("Awaiting human review — DOG-7 Task page · Dogfood",
				AgentBoardRenderSupport.headline(NotificationEventType.AGENT_TASK_NEEDS_PERSON, gate));
		AgentBoardEventPayload locked = new AgentBoardEventPayload(UUID.randomUUID(), "Dogfood", null, null, "PAUSED",
				"[OPERATOR] budget", null, null, "/x", null);
		assertEquals("Board paused — Dogfood", AgentBoardRenderSupport.headline(NotificationEventType.AGENT_BOARD_ALERT, locked));
		AgentBoardEventPayload aged = new AgentBoardEventPayload(UUID.randomUUID(), "Dogfood", task, "DOG-7 Task page",
				"QUEUE_AGE", "Waiting 45 min", "MANUAL", "OPERATOR", "/x", 45L);
		assertEquals("Waiting 45 min for a person — DOG-7 Task page · Dogfood",
				AgentBoardRenderSupport.headline(NotificationEventType.AGENT_TASK_QUEUE_AGE, aged));
		assertEquals("https://rearm.example/aiAgentTask/" + task, AgentBoardRenderSupport.url("https://rearm.example", gate));
		assertEquals(null, AgentBoardRenderSupport.url("", gate), "no base URI, no link");
		assertEquals(NotificationSeverity.HIGH, AgentBoardRenderSupport.severity(NotificationEventType.AGENT_TASK_NEEDS_PERSON));
		assertEquals(NotificationSeverity.MEDIUM, AgentBoardRenderSupport.severity(NotificationEventType.AGENT_BOARD_ALERT));

		// the CEL activation can narrow by board and kind
		Map<String, Object> m = new LinkedHashMap<>();
		AgentBoardRenderSupport.populate(m, gate);
		assertEquals(gate.board().toString(), m.get("board"));
		assertEquals("HUMAN_GATE", m.get("kind"));

		// and the payload survives the outbox's JSON round trip
		@SuppressWarnings("unchecked")
		Map<String, Object> record = Utils.OM.convertValue(gate, Map.class);
		NotificationOutboxEvent ev = new NotificationOutboxEvent();
		ev.setEventType(NotificationEventType.AGENT_TASK_NEEDS_PERSON);
		ev.setRecordData(record);
		assertEquals(gate, AgentBoardRenderSupport.payload(ev));
		assertFalse(record.containsKey("title"), "the headline helper is not a field");
	}

	private static void inject(Object target, String field, Object value) throws Exception {
		Field f = target.getClass().getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	// ---------- the key leads every channel (task RD2-22) ----------

	@Test
	void theTaskIsNamedByItsLabelOnEveryTaskNotification() throws Exception {
		AgentBoardNotifier notifier = new AgentBoardNotifier();
		AgentBoardNotifier self = mock(AgentBoardNotifier.class);
		AgentBoardService boards = mock(AgentBoardService.class);
		AgentBoardData board = new AgentBoardData();
		org.springframework.test.util.ReflectionTestUtils.setField(board, "uuid", UUID.randomUUID());
		board.setName("Dogfood");
		board.setOrg(UUID.randomUUID());
		when(boards.getBoardData(any())).thenReturn(Optional.of(board));
		inject(notifier, "self", self);
		inject(notifier, "agentBoardService", boards);
		AgentTaskData td = new AgentTaskData();
		org.springframework.test.util.ReflectionTestUtils.setField(td, "uuid", UUID.randomUUID());
		td.setOrg(UUID.randomUUID());
		td.setBoard(board.getUuid());
		td.setKey("DOG-7");
		td.setTitle("Task page per task, with a title long enough that the label has to cut it short");
		td.setHold(hold(HoldLevel.OPERATOR, HoldKind.HUMAN_GATE, ZonedDateTime.now()));

		notifier.needsPerson(td);
		notifier.returned(td, new AgentTaskData.TaskReturn("coder", null, null, null,
				AgentTaskData.TaskReturnReason.TASK_UNCLEAR, "scope?", ZonedDateTime.now(), null, null));
		notifier.queueAge(board, td, 45L);
		org.mockito.ArgumentCaptor<AgentBoardEventPayload> sent = org.mockito.ArgumentCaptor.forClass(AgentBoardEventPayload.class);
		org.mockito.ArgumentCaptor<NotificationEventType> types = org.mockito.ArgumentCaptor.forClass(NotificationEventType.class);
		verify(self, org.mockito.Mockito.times(3)).write(any(), types.capture(), any(), sent.capture(), any());
		for (int i = 0; i < 3; i++) {
			AgentBoardEventPayload p = sent.getAllValues().get(i);
			assertEquals(td.label(), p.taskTitle(), "the payload names the task by its label");
			assertTrue(AgentBoardRenderSupport.headline(types.getAllValues().get(i), p).contains(" — DOG-7 Task page per task"),
					"the key leads the headline: " + AgentBoardRenderSupport.headline(types.getAllValues().get(i), p));
		}
	}
}

