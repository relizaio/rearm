/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.RearmSpecificationType;
import io.reliza.model.SessionUsageSource;
import io.reliza.service.AgentDocumentService.PublishRequest;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.ws.App;
import jakarta.persistence.EntityManagerFactory;

/**
 * The task-keyed staleness marks (task TPF-69): {@link BoardStalenessService#taskBreaches} runs the sweep's own
 * hopNoProgress rule over the given tasks, with the span's start and length on each mark; progress clears it; a
 * board without a block reads nothing; and the reads behind the rule are batched, so the statement count does not
 * grow with the number of stalled tasks.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class TaskStaleBreachesTest extends AgentBoardStalenessTestBase {

	@Autowired private EntityManagerFactory emf;

	private Map<UUID, List<BoardStalenessService.Breach>> taskBreaches(Rig r, ZonedDateTime at) {
		AgentBoardData bd = board(r);
		return staleness.taskBreaches(bd, agentTaskService.listByBoard(bd.getUuid(), null), at);
	}

	private AgentTaskData held(Rig r, AgentSessionData s) throws RelizaException {
		AgentTaskData t = queued(r, "reviewer");
		agentTaskService.assign(t.getUuid(), board(r), s.getAgent(), s.getUuid(), WU);
		return reload(t);
	}

	private void publishReviewItems(AgentSessionData s, AgentTaskData t) throws RelizaException {
		publishReviewItems(s, t, List.of());
	}

	/** A review-items round; distinct items make a distinct round, which the board does not fold into the last. */
	private void publishReviewItems(AgentSessionData s, AgentTaskData t, List<Map<String, Object>> items) throws RelizaException {
		agentDocumentService.publish(s, new PublishRequest(t.getUuid(),
				RearmSpecificationType.BOARD_REVIEW_ITEMS, null, null, null, null, null, null,
				Map.of("kind", "BOARD_REVIEW_ITEMS", "verdict", "PASSED", "reviewItems", items), null, null, null,
				null, null), WU);
	}

	@Test
	public void aStalledHopCarriesOneMarkWithItsSpanAndTheAlertText() throws RelizaException {
		Rig r = rig();
		setStaleness(r, block("hopNoProgressMinutes", 60));
		AgentTaskData t = held(r, r.sa());

		assertTrue(taskBreaches(r, in(30)).isEmpty(), "under the threshold: no entry");
		Map<UUID, List<BoardStalenessService.Breach>> stale = taskBreaches(r, in(61));
		assertEquals(1, stale.size(), stale.toString());
		List<BoardStalenessService.Breach> marks = stale.get(t.getUuid());
		assertEquals(1, marks.size(), marks.toString());
		BoardStalenessService.Breach b = marks.get(0);
		assertEquals(BoardStalenessService.HOP_NO_PROGRESS + ":" + t.getUuid() + ":" + r.sa().getUuid(), b.key());
		assertEquals(t.getAssignment().assignedAt().toInstant(), b.since().toInstant());
		assertTrue(b.minutes() >= 61 && b.minutes() <= 62, "minutes " + b.minutes());
		assertTrue(b.message().contains("stalled: assigned to reviewer"), b.message());
		assertTrue(b.message().contains("release it with task unassign if the agent is gone"), b.message());

		// the same text the sweep posts for it
		AgentBoardData bd = board(r);
		ZonedDateTime at = in(61);
		assertEquals(staleness.breaches(bd, bd.getStaleness(), at).stream().map(BoardStalenessService.Breach::message).toList(),
				staleness.taskBreaches(bd, agentTaskService.listByBoard(bd.getUuid(), null), at).get(t.getUuid()).stream()
						.map(BoardStalenessService.Breach::message).toList());
	}

	@Test
	public void aPublishOrAUsageReportFromTheHolderClearsTheMark() throws RelizaException {
		Rig r = rig();
		setStaleness(r, block("hopNoProgressMinutes", 60));
		AgentTaskData published = held(r, r.sa());
		AgentTaskData reported = held(r, r.sb());
		assertEquals(2, taskBreaches(r, in(61)).size());

		publishReviewItems(r.sa(), published);
		Map<UUID, List<BoardStalenessService.Breach>> afterPublish = taskBreaches(r, in(61));
		assertFalse(afterPublish.containsKey(published.getUuid()), "a document from the holder is progress: " + afterPublish);
		assertTrue(afterPublish.containsKey(reported.getUuid()));

		usageService.report(agentSessionService.getSessionData(r.sb().getUuid()).orElseThrow(),
				new UsageReport(r.sb().getUuid(), null, SEQ.incrementAndGet(), SessionUsageSource.TRANSCRIPT,
						ZonedDateTime.now().minusMinutes(1), ZonedDateTime.now(), 1, 1, 60, null, reported.getUuid(), Map.of(),
						List.of(new UsageLine("claude-opus-5-5", null, 0L, 1, 100, 0, 0, 0, null, 100L, 100L, null))), WU);
		assertTrue(taskBreaches(r, in(61)).isEmpty(), "a usage report from the holder is progress");
	}

	@Test
	public void aBoardWithoutTheBlockMarksNothingAndReadsNothing() throws Exception {
		Rig r = rig();
		AgentTaskData t = held(r, r.sa());
		AgentBoardData bd = board(r);
		List<AgentTaskData> tasks = List.of(reload(t));
		long statements = StatementCounter.fewest(emf, 3, () -> assertTrue(staleness.taskBreaches(bd, tasks, in(10_000)).isEmpty()));
		assertEquals(0, statements, "no read beyond the task list the caller holds");

		// a block set, then declared null: off again
		setStaleness(r, block("hopNoProgressMinutes", 60));
		assertEquals(1, taskBreaches(r, in(61)).size());
		setStaleness(r, null);
		assertTrue(taskBreaches(r, in(61)).isEmpty());
	}

	@Test
	public void theReadsDoNotGrowWithTheNumberOfStalledTasks() throws Exception {
		Rig six = rig(true, 50);
		Rig two = rig(true, 50);
		List<AgentTaskData> manyTasks = stalledWithTwoReleasesEach(six, 6);
		List<AgentTaskData> fewTasks = stalledWithTwoReleasesEach(two, 2);
		AgentBoardData manyBoard = board(six);
		AgentBoardData fewBoard = board(two);
		ZonedDateTime at = in(61);
		assertEquals(6, staleness.taskBreaches(manyBoard, manyTasks, at).size(), "all six are stalled");
		assertEquals(2, staleness.taskBreaches(fewBoard, fewTasks, at).size());

		long many = StatementCounter.fewest(emf, 3, () -> staleness.taskBreaches(manyBoard, manyTasks, at));
		long few = StatementCounter.fewest(emf, 3, () -> staleness.taskBreaches(fewBoard, fewTasks, at));
		assertEquals(few, many, "the same statements for 6 stalled tasks as for 2");
		// the linked releases in one IN read and the usage reports grouped in one; no DELIVERING task, no PR read
		assertEquals(2, many);
		assertTrue(many <= 3);
	}

	/**
	 * {@code n} tasks held by session b, each linking two review-items releases that session a published during
	 * an earlier hop: documents in the read, none of them the holder's, so each task is a stalled candidate.
	 */
	private List<AgentTaskData> stalledWithTwoReleasesEach(Rig r, int n) throws RelizaException {
		setStaleness(r, block("hopNoProgressMinutes", 60));
		List<AgentTaskData> out = new ArrayList<>();
		for (int i = 0; i < n; i++) {
			AgentTaskData t = held(r, r.sa());
			publishReviewItems(r.sa(), t);
			publishReviewItems(r.sa(), reload(t), List.of(Map.of("id", "F-1", "priority", 1, "status", "RESOLVED",
					"title", "fixed " + i)));
			agentTaskService.unassign(t.getUuid(), "handing it over", PERSON, WU);
			agentTaskService.assign(t.getUuid(), board(r), r.b().getUuid(), r.sb().getUuid(), WU);
			AgentTaskData now = reload(t);
			assertEquals(2, now.getReleases().size(), "two linked releases");
			out.add(now);
		}
		return out;
	}
}
