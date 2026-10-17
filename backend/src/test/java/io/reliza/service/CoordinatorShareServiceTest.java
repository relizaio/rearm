/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentActor;
import io.reliza.model.AgentTask;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.StatusChange;
import io.reliza.model.AgentTaskData.StatusTrigger;
import io.reliza.model.AgentTaskData.TaskStatus;

/**
 * Who gets a coordinator delta and how it splits (D16), without a database: the two rules are
 * pure functions of the board's tasks, so they are pinned here and the integration test covers the
 * writes around them.
 */
public class CoordinatorShareServiceTest {

	private static final UUID SEAT = UUID.randomUUID();
	private static final ZonedDateTime T0 = ZonedDateTime.parse("2026-09-25T10:00:00Z");

	private static AgentTaskData task(TaskStatus status, StatusChange... changes) {
		AgentTask t = new AgentTask();
		t.setRecordData(new LinkedHashMap<>());
		AgentTaskData td = AgentTaskData.dataFromRecord(t);
		td.setStatus(status);
		td.setStatusHistory(new ArrayList<>(List.of(changes)));
		return td;
	}

	private static StatusChange by(AgentActor actor, int minute) {
		return new StatusChange(TaskStatus.AWAITING_COORDINATOR, TaskStatus.QUEUED, T0.plusMinutes(minute),
				StatusTrigger.AUTHORIZE, actor);
	}

	private static StatusChange bySeat(int minute) {
		return by(AgentActor.ofSession(SEAT), minute);
	}

	@Test
	public void theRecipientsAreTheTasksTheSeatMovedInTheWindow() {
		AgentTaskData twice = task(TaskStatus.QUEUED, bySeat(5), bySeat(6));
		AgentTaskData once = task(TaskStatus.QUEUED, bySeat(7));
		AgentTaskData before = task(TaskStatus.QUEUED, bySeat(1));
		AgentTaskData after = task(TaskStatus.QUEUED, bySeat(30));
		AgentTaskData byAnother = task(TaskStatus.QUEUED, by(AgentActor.ofSession(UUID.randomUUID()), 5),
				by(AgentActor.system("routing"), 6), by(AgentActor.ofUser(SEAT, "a@b.c"), 7));

		CoordinatorShareService.Recipients r = CoordinatorShareService.recipients(
				List.of(twice, once, before, after, byAnother), SEAT, T0.plusMinutes(2), T0.plusMinutes(10));

		assertEquals(sorted(twice.getUuid(), once.getUuid()), r.recipients());
		assertEquals(3, r.transitions(), "transitions, not tasks: one task moved twice");
	}

	@Test
	public void theWindowIsAfterTheCursorAndUpToTheReport() {
		AgentTaskData atCursor = task(TaskStatus.QUEUED, bySeat(2));
		AgentTaskData atReport = task(TaskStatus.QUEUED, bySeat(10));
		CoordinatorShareService.Recipients r = CoordinatorShareService.recipients(
				List.of(atCursor, atReport), SEAT, T0.plusMinutes(2), T0.plusMinutes(10));
		assertEquals(List.of(atReport.getUuid()), r.recipients(), "after the cursor, at or before the report");
	}

	@Test
	public void noCursorMeansEverythingSoFar() {
		AgentTaskData old = task(TaskStatus.QUEUED, bySeat(-600));
		assertEquals(List.of(old.getUuid()), CoordinatorShareService.recipients(List.of(old), SEAT, null,
				T0).recipients());
	}

	@Test
	public void withNoTransitionTheOpenTasksShareAndNoTransitionIsCounted() {
		AgentTaskData open = task(TaskStatus.PENDING_INTAKE);
		AgentTaskData held = task(TaskStatus.ON_HOLD);
		AgentTaskData done = task(TaskStatus.COMPLETED);
		AgentTaskData cancelled = task(TaskStatus.CANCELLED);
		CoordinatorShareService.Recipients r = CoordinatorShareService.recipients(
				List.of(open, held, done, cancelled), SEAT, null, T0);
		assertEquals(sorted(open.getUuid(), held.getUuid()), r.recipients());
		assertEquals(0, r.transitions(), "a fallback says nothing about cost per transition");

		CoordinatorShareService.Recipients none = CoordinatorShareService.recipients(List.of(done), SEAT, null, T0);
		assertTrue(none.recipients().isEmpty());
	}

	@Test
	public void theRemainderIsDeterministic() {
		List<UUID> three = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		List<UUID> ordered = sorted(three.toArray(UUID[]::new));
		Map<UUID, Long> shares = CoordinatorShareService.split(100, three);
		assertEquals(34L, shares.get(ordered.get(0)), "the earliest by uuid order takes the remainder");
		assertEquals(33L, shares.get(ordered.get(1)));
		assertEquals(33L, shares.get(ordered.get(2)));
		assertEquals(shares, CoordinatorShareService.split(100, List.of(three.get(2), three.get(0), three.get(1))),
				"the same shares whatever order the tasks come in");

		Map<UUID, Long> more = CoordinatorShareService.split(101, three);
		assertEquals(35L, more.get(ordered.get(0)), "the whole remainder, not a micro each");
		assertEquals(101L, more.values().stream().mapToLong(Long::longValue).sum());
	}

	@Test
	public void lessThanOneMicroEachGoesToWhoeverGetsAny() {
		List<UUID> three = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
		Map<UUID, Long> shares = CoordinatorShareService.split(2, three);
		assertEquals(Map.of(sorted(three.toArray(UUID[]::new)).get(0), 2L), shares, "no zero shares written");
		assertTrue(CoordinatorShareService.split(0, three).isEmpty());
		assertTrue(CoordinatorShareService.split(50, List.of()).isEmpty());
	}

	private static List<UUID> sorted(UUID... uuids) {
		List<UUID> l = new ArrayList<>(List.of(uuids));
		l.sort(Comparator.comparing(UUID::toString));
		return l;
	}
}
