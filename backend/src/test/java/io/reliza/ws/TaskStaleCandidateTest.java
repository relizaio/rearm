/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.TaskStatus;

/**
 * Which tasks reach the AgentTask.stale loader (task TPF-69, mutation round): only the statuses a task-keyed rule
 * can name, so every other task answers an empty list without a loader call. Spring-free.
 */
public class TaskStaleCandidateTest {

	private static AgentTaskData task(TaskStatus status) {
		AgentTaskData t = new AgentTaskData();
		ReflectionTestUtils.setField(t, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(t, "status", status);
		return t;
	}

	private static AgentTaskData investigation(TaskStatus status, ZonedDateTime deadline) {
		AgentTaskData t = task(status);
		ReflectionTestUtils.setField(t, "kind", AgentTaskData.TaskKind.INVESTIGATION);
		ReflectionTestUtils.setField(t, "investigation", new AgentTaskData.Investigation(null, null, "researcher", null,
				null, null, deadline, null, null, null));
		return t;
	}

	@Test
	public void anAssignedAndADeliveringTaskCanBeStale() {
		assertTrue(AgentTaskDataFetcher.canBeStale(task(TaskStatus.ASSIGNED)));
		assertTrue(AgentTaskDataFetcher.canBeStale(task(TaskStatus.DELIVERING)));
	}

	@Test
	public void aTaskInAnyOtherStatusCannotBe() {
		for (TaskStatus s : TaskStatus.values()) {
			if (s == TaskStatus.ASSIGNED || s == TaskStatus.DELIVERING) continue;
			assertFalse(AgentTaskDataFetcher.canBeStale(task(s)), s.name());
		}
	}

	@Test
	public void anOpenInvestigationWithADeadlineCanBeStaleAndAFinishedOneCannot() {
		ZonedDateTime due = ZonedDateTime.parse("2026-10-20T08:00:00Z");
		assertTrue(AgentTaskDataFetcher.canBeStale(investigation(TaskStatus.QUEUED, due)), "waiting past its deadline");
		assertFalse(AgentTaskDataFetcher.canBeStale(investigation(TaskStatus.QUEUED, null)), "no deadline: nothing to be late for");
		assertFalse(AgentTaskDataFetcher.canBeStale(investigation(TaskStatus.COMPLETED, due)));
		assertFalse(AgentTaskDataFetcher.canBeStale(investigation(TaskStatus.CANCELLED, due)));
	}
}
