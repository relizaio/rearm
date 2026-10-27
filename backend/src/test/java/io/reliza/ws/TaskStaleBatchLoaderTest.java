/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.service.AgentBoardService;
import io.reliza.service.AgentTaskService;
import io.reliza.service.BoardAgentsService.StaleMark;
import io.reliza.service.BoardStalenessService;
import io.reliza.ws.AgentTaskDataFetcher.TaskStaleBatchLoader;
import io.reliza.ws.AgentTaskDataFetcher.TaskStaleKey;

/**
 * The AgentTask.stale loader (task TPF-69), Spring-free: keys of a board without a staleness block answer empty
 * lists without a task read or a rule run; keys of a board with one answer, in key order, the marks the rules
 * returned for their tasks, the rule being the breach key's first segment.
 */
public class TaskStaleBatchLoaderTest {

	private final AgentTaskService agentTaskService = mock(AgentTaskService.class);
	private final AgentBoardService agentBoardService = mock(AgentBoardService.class);
	private final BoardStalenessService boardStalenessService = mock(BoardStalenessService.class);

	private TaskStaleBatchLoader loader() {
		TaskStaleBatchLoader l = new TaskStaleBatchLoader();
		ReflectionTestUtils.setField(l, "agentTaskService", agentTaskService);
		ReflectionTestUtils.setField(l, "agentBoardService", agentBoardService);
		ReflectionTestUtils.setField(l, "boardStalenessService", boardStalenessService);
		return l;
	}

	private static AgentBoardData board(UUID uuid, AgentBoardData.Staleness st) {
		AgentBoardData b = new AgentBoardData();
		ReflectionTestUtils.setField(b, "uuid", uuid);
		b.setStaleness(st);
		return b;
	}

	private static AgentTaskData task(UUID uuid) {
		AgentTaskData t = new AgentTaskData();
		ReflectionTestUtils.setField(t, "uuid", uuid);
		return t;
	}

	@Test
	public void keysAnswerInOrderAndABoardWithoutABlockReadsNoTasks() throws Exception {
		UUID quiet = UUID.randomUUID();
		UUID watched = UUID.randomUUID();
		UUID q1 = UUID.randomUUID();
		UUID q2 = UUID.randomUUID();
		UUID hop = UUID.randomUUID();
		UUID delivering = UUID.randomUUID();
		UUID healthy = UUID.randomUUID();
		when(agentBoardService.getBoardData(quiet)).thenReturn(Optional.of(board(quiet, null)));
		AgentBoardData watchedBoard = board(watched, new AgentBoardData.Staleness(null, 60, 120, null, null));
		when(agentBoardService.getBoardData(watched)).thenReturn(Optional.of(watchedBoard));
		List<AgentTaskData> tasks = List.of(task(hop), task(delivering), task(healthy));
		when(agentTaskService.listByUuids(Set.of(hop, delivering, healthy))).thenReturn(tasks);
		ZonedDateTime assignedAt = ZonedDateTime.now().minusMinutes(70);
		ZonedDateTime enteredDelivering = ZonedDateTime.now().minusMinutes(180);
		UUID session = UUID.randomUUID();
		when(boardStalenessService.taskBreaches(eq(watchedBoard), eq(tasks), any())).thenReturn(Map.of(
				hop, List.of(new BoardStalenessService.Breach(BoardStalenessService.HOP_NO_PROGRESS + ":" + hop + ":" + session,
						"RD3-5 stalled: assigned to coder", assignedAt, 70)),
				delivering, List.of(new BoardStalenessService.Breach(BoardStalenessService.DELIVERY_STUCK + ":" + delivering,
						"RD3-6 delivery stuck: nothing delivered for 3 h", enteredDelivering, 180))));

		List<TaskStaleKey> keys = List.of(new TaskStaleKey(quiet, q1), new TaskStaleKey(watched, healthy),
				new TaskStaleKey(watched, delivering), new TaskStaleKey(quiet, q2), new TaskStaleKey(watched, hop));
		List<List<StaleMark>> out = loader().load(keys).toCompletableFuture().get();

		assertEquals(5, out.size());
		assertTrue(out.get(0).isEmpty(), "a board without a block answers empty");
		assertTrue(out.get(1).isEmpty(), "a task with no breach answers empty");
		assertEquals(List.of(new StaleMark(BoardStalenessService.DELIVERY_STUCK, enteredDelivering, 180,
				"RD3-6 delivery stuck: nothing delivered for 3 h")), out.get(2));
		assertTrue(out.get(3).isEmpty());
		assertEquals(List.of(new StaleMark(BoardStalenessService.HOP_NO_PROGRESS, assignedAt, 70,
				"RD3-5 stalled: assigned to coder")), out.get(4));

		// the board without a block: no task read, no rule run
		verify(agentTaskService, never()).listByUuids(Set.of(q1, q2));
		verify(boardStalenessService, never()).taskBreaches(eq(board(quiet, null)), any(), any());
	}

	@Test
	public void aBoardNotFoundOrWithAnEmptyBlockAnswersEmptyWithoutReadingTasks() throws Exception {
		UUID gone = UUID.randomUUID();
		UUID empty = UUID.randomUUID();
		when(agentBoardService.getBoardData(gone)).thenReturn(Optional.empty());
		when(agentBoardService.getBoardData(empty)).thenReturn(Optional.of(board(empty,
				new AgentBoardData.Staleness(null, null, null, null, 300))));
		List<List<StaleMark>> out = loader().load(List.of(new TaskStaleKey(gone, UUID.randomUUID()),
				new TaskStaleKey(empty, UUID.randomUUID()))).toCompletableFuture().get();
		assertEquals(List.of(List.of(), List.of()), out);
		verify(agentTaskService, never()).listByUuids(anyCollection());
		verify(boardStalenessService, never()).taskBreaches(any(), any(), any());
	}
}
