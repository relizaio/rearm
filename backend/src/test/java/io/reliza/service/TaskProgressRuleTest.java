/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.ReleaseData;
import io.reliza.model.RearmSpecificationType;
import io.reliza.repositories.AgentSessionUsageRepository;
import io.reliza.repositories.AgentSessionUsageRepository.TaskSessionLastReport;

/**
 * What counts as progress on a held task (task TPF-69, mutation round): {@link BoardStalenessService#progressedAmong}
 * on states the integration rig cannot make: a document of another session published after the assignment, and a
 * document or a usage report stamped at exactly the instant of the assignment. Spring-free, with the two reads mocked.
 */
public class TaskProgressRuleTest {

	private static final ZonedDateTime ASSIGNED = ZonedDateTime.parse("2026-10-20T10:00:00Z");
	private static final UUID HOLDER = UUID.randomUUID();
	private static final UUID OTHER = UUID.randomUUID();

	private final SharedReleaseService shared = mock(SharedReleaseService.class);
	private final AgentSessionUsageRepository usage = mock(AgentSessionUsageRepository.class);

	private BoardStalenessService service() {
		BoardStalenessService s = new BoardStalenessService();
		ReflectionTestUtils.setField(s, "sharedReleaseService", shared);
		ReflectionTestUtils.setField(s, "usageRepository", usage);
		return s;
	}

	/** A task held by {@link #HOLDER} since {@link #ASSIGNED}, linking the given releases. */
	private static AgentTaskData task(UUID... releases) {
		AgentTaskData t = new AgentTaskData();
		ReflectionTestUtils.setField(t, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(t, "releases", List.of(releases));
		ReflectionTestUtils.setField(t, "assignment", new AgentTaskData.TaskAssignment(HOLDER, UUID.randomUUID(), "coder",
				null, ASSIGNED, null, null, null, null, null));
		return t;
	}

	/** A review-items document release of the task, published by {@code session} at {@code created}. */
	private ReleaseData document(UUID uuid, UUID task, UUID session, ZonedDateTime created) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", uuid);
		ReflectionTestUtils.setField(rd, "createdDate", created);
		rd.setDocument(new ReleaseData.DocumentRef(RearmSpecificationType.BOARD_REVIEW_ITEMS, "p", "d", "text/markdown",
				null, null, task, session, 1, null, null, null, null, "coder"));
		return rd;
	}

	private void releasesAre(ReleaseData... rds) {
		Map<UUID, ReleaseData> byUuid = new java.util.HashMap<>();
		for (ReleaseData rd : rds) byUuid.put(rd.getUuid(), rd);
		when(shared.getReleaseDataByUuids(anyCollection())).thenReturn(byUuid);
	}

	private static TaskSessionLastReport report(UUID task, UUID session, ZonedDateTime last) {
		TaskSessionLastReport r = mock(TaskSessionLastReport.class);
		when(r.getTask()).thenReturn(task);
		when(r.getSession()).thenReturn(session);
		when(r.getLast()).thenReturn(last.toInstant());
		return r;
	}

	@Test
	public void aDocumentOfAnotherSessionPublishedAfterTheAssignmentIsNotProgress() {
		UUID release = UUID.randomUUID();
		AgentTaskData t = task(release);
		releasesAre(document(release, t.getUuid(), OTHER, ASSIGNED.plusMinutes(5)));
		when(usage.lastReportsForTasks(anyCollection(), any())).thenReturn(List.of());

		assertEquals(Set.of(), service().progressedAmong(List.of(t)));
	}

	@Test
	public void aDocumentOfTheHolderIsProgressFromTheInstantOfTheAssignment() {
		UUID before = UUID.randomUUID();
		UUID at = UUID.randomUUID();
		AgentTaskData earlier = task(before);
		AgentTaskData same = task(at);
		releasesAre(document(before, earlier.getUuid(), HOLDER, ASSIGNED.minusSeconds(1)),
				document(at, same.getUuid(), HOLDER, ASSIGNED));
		when(usage.lastReportsForTasks(anyCollection(), any())).thenReturn(List.of());

		assertEquals(Set.of(same.getUuid()), service().progressedAmong(List.of(earlier, same)),
				"a document stamped at the assignment counts, one a second earlier does not");
	}

	@Test
	public void aUsageReportOfTheHolderIsProgressFromTheInstantOfTheAssignment() {
		AgentTaskData at = task();
		AgentTaskData earlier = task();
		AgentTaskData other = task();
		List<TaskSessionLastReport> reports = List.of(
				report(at.getUuid(), HOLDER, ASSIGNED),
				report(earlier.getUuid(), HOLDER, ASSIGNED.minusSeconds(1)),
				report(other.getUuid(), OTHER, ASSIGNED.plusMinutes(5)));
		when(usage.lastReportsForTasks(anyCollection(), any())).thenReturn(reports);

		Set<UUID> progressed = service().progressedAmong(List.of(at, earlier, other));
		assertTrue(progressed.contains(at.getUuid()), "a report stamped at the assignment counts");
		assertEquals(Set.of(at.getUuid()), progressed, "a report from before it, or from another session, does not");
	}

	@Test
	public void onlyAnAssignedTaskCanBeStalledEvenWhenItStillCarriesAnAssignment() {
		AgentBoardData board = new AgentBoardData();
		board.setStaleness(new AgentBoardData.Staleness(null, 60, null, null, null, null));
		when(usage.lastReportsForTasks(anyCollection(), any())).thenReturn(List.of());
		AgentTaskData assigned = task();
		ReflectionTestUtils.setField(assigned, "status", AgentTaskData.TaskStatus.ASSIGNED);
		AgentTaskData returned = task();
		ReflectionTestUtils.setField(returned, "status", AgentTaskData.TaskStatus.AWAITING_COORDINATOR);

		Map<UUID, List<BoardStalenessService.Breach>> out = service().taskBreaches(board, List.of(assigned, returned),
				ASSIGNED.plusHours(5));
		assertEquals(Set.of(assigned.getUuid()), out.keySet(), "the returned task has an old assignment but is not held");
	}
}
