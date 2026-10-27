/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.AgentTaskData.TaskStatus;
import io.reliza.repositories.AgentSessionUsageRepository;

/**
 * The exact threshold of the three task-keyed staleness rules (task TPF-69, mutation round): a rule marks a task
 * from the whole minute its threshold names and not a minute before, the same minute the sweep's ALERT starts on.
 * {@link BoardStalenessService#taskBreaches} takes the reading time, so the boundary is tested without a clock.
 * Spring-free: the tasks are built in memory and the reads behind the rules are mocked.
 */
public class TaskStaleThresholdTest {

	private static final ZonedDateTime T = ZonedDateTime.parse("2026-10-20T10:00:00Z");

	private final AgentSessionUsageRepository usage = mock(AgentSessionUsageRepository.class);

	private BoardStalenessService service() {
		BoardStalenessService s = new BoardStalenessService();
		ReflectionTestUtils.setField(s, "usageRepository", usage);
		return s;
	}

	private static AgentBoardData board(Integer hop, Integer delivery, Integer overdue) {
		AgentBoardData b = new AgentBoardData();
		b.setStaleness(new AgentBoardData.Staleness(null, hop, delivery, null, null, overdue));
		return b;
	}

	private static AgentTaskData task(TaskStatus status) {
		AgentTaskData t = new AgentTaskData();
		ReflectionTestUtils.setField(t, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(t, "status", status);
		return t;
	}

	private Map<UUID, List<BoardStalenessService.Breach>> at(AgentBoardData board, AgentTaskData t, ZonedDateTime now) {
		return service().taskBreaches(board, List.of(t), now);
	}

	@Test
	public void aStalledHopIsMarkedFromTheWholeMinuteOfItsThreshold() {
		AgentTaskData t = task(TaskStatus.ASSIGNED);
		ReflectionTestUtils.setField(t, "assignment", new AgentTaskData.TaskAssignment(UUID.randomUUID(), UUID.randomUUID(),
				"coder", null, T, null, null, null, null, null));
		AgentBoardData board = board(60, null, null);

		assertTrue(at(board, t, T.plusMinutes(59).plusSeconds(59)).isEmpty(), "59 minutes 59 seconds: not yet");
		Map<UUID, List<BoardStalenessService.Breach>> marked = at(board, t, T.plusMinutes(60));
		assertEquals(1, marked.size(), marked.toString());
		assertEquals(60, marked.get(t.getUuid()).get(0).minutes());
	}

	@Test
	public void aStuckDeliveryIsMarkedFromTheWholeMinuteOfItsThreshold() {
		AgentTaskData t = task(TaskStatus.DELIVERING);
		ReflectionTestUtils.setField(t, "createdDate", T);
		AgentBoardData board = board(null, 120, null);
		// a board that delivers without pull requests: a delivery waiting past its threshold is marked on time alone
		ReflectionTestUtils.setField(board, "deliveryPolicy",
				new AgentBoardData.DeliveryPolicy(AgentBoardData.DeliveryMode.NONE, false, null));

		assertTrue(at(board, t, T.plusMinutes(119).plusSeconds(59)).isEmpty(), "119 minutes 59 seconds: not yet");
		Map<UUID, List<BoardStalenessService.Breach>> marked = at(board, t, T.plusMinutes(120));
		assertEquals(1, marked.size(), marked.toString());
		assertEquals(120, marked.get(t.getUuid()).get(0).minutes());
	}

	@Test
	public void anOverdueInvestigationIsMarkedFromTheWholeMinuteOfItsGrace() {
		AgentTaskData t = task(TaskStatus.QUEUED);
		ReflectionTestUtils.setField(t, "kind", AgentTaskData.TaskKind.INVESTIGATION);
		ReflectionTestUtils.setField(t, "investigation", new AgentTaskData.Investigation(null, null, "researcher", null,
				null, null, T, null, null, null));
		AgentBoardData board = board(null, null, 30);

		assertTrue(at(board, t, T.plusMinutes(29).plusSeconds(59)).isEmpty(), "29 minutes 59 seconds past the deadline: not yet");
		Map<UUID, List<BoardStalenessService.Breach>> marked = at(board, t, T.plusMinutes(30));
		assertEquals(1, marked.size(), marked.toString());
		assertEquals(30, marked.get(t.getUuid()).get(0).minutes());
	}

	@Test
	public void aBoardDeliveringWithoutPullRequestsReadsNoPullRequestRows() {
		AgentDeliveryService deliveries = mock(AgentDeliveryService.class);
		BoardStalenessService service = service();
		ReflectionTestUtils.setField(service, "agentDeliveryService", deliveries);
		AgentTaskData t = task(TaskStatus.DELIVERING);
		ReflectionTestUtils.setField(t, "createdDate", T);
		ReflectionTestUtils.setField(t, "prUrls", List.of("https://github.com/relizaio/rearm/pull/1"));
		AgentBoardData board = board(null, 120, null);
		ReflectionTestUtils.setField(board, "deliveryPolicy",
				new AgentBoardData.DeliveryPolicy(AgentBoardData.DeliveryMode.NONE, false, null));

		assertEquals(1, service.taskBreaches(board, List.of(t), T.plusMinutes(120)).size());
		verify(deliveries, never()).pullRequestsByKey(any(), any());
	}
}
