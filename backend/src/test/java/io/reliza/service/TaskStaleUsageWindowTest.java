/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentSessionData;
import io.reliza.model.AgentTaskData;
import io.reliza.model.SessionUsageSource;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.ws.App;

/**
 * Which usage report of a task counts as progress (task TPF-69, mutation round). The staleness rule reads the
 * reports of all its stalled candidates at once from the earliest of their assignments, and keeps the newest report
 * of each (task, session): a report from an earlier hop of the same session sits inside that window but is no
 * progress on the current assignment, and only a report made after it is.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class TaskStaleUsageWindowTest extends AgentBoardStalenessTestBase {

	private Map<UUID, List<BoardStalenessService.Breach>> taskBreaches(Rig r, ZonedDateTime at) {
		AgentBoardData bd = board(r);
		return staleness.taskBreaches(bd, agentTaskService.listByBoard(bd.getUuid(), null), at);
	}

	private AgentTaskData held(Rig r, AgentSessionData s) throws RelizaException {
		AgentTaskData t = queued(r, "reviewer");
		agentTaskService.assign(t.getUuid(), board(r), s.getAgent(), s.getUuid(), WU);
		return reload(t);
	}

	private void report(AgentSessionData s, AgentTaskData t) throws RelizaException {
		usageService.report(agentSessionService.getSessionData(s.getUuid()).orElseThrow(),
				new UsageReport(s.getUuid(), null, SEQ.incrementAndGet(), SessionUsageSource.TRANSCRIPT,
						ZonedDateTime.now().minusMinutes(1), ZonedDateTime.now(), 1, 1, 60, null, t.getUuid(), Map.of(),
						List.of(new UsageLine("claude-opus-5-5", null, 0L, 1, 100, 0, 0, 0, null, 100L, 100L, null))), WU);
	}

	@Test
	public void aReportOfAnEarlierHopIsNoProgressOnTheCurrentAssignmentButALaterOneIs() throws Exception {
		Rig r = rig();
		setStaleness(r, block("hopNoProgressMinutes", 60));
		// the first assignment on the board: it widens the window the usage reads start from
		AgentTaskData early = held(r, r.sb());
		Thread.sleep(25);
		AgentTaskData t = held(r, r.sa());
		Thread.sleep(25);
		report(r.sa(), t);
		Thread.sleep(25);
		agentTaskService.unassign(t.getUuid(), "handing it back", PERSON, WU);
		agentTaskService.assign(t.getUuid(), board(r), r.sa().getAgent(), r.sa().getUuid(), WU);
		Thread.sleep(25);

		Map<UUID, List<BoardStalenessService.Breach>> before = taskBreaches(r, in(61));
		assertTrue(before.containsKey(t.getUuid()), "a report from before this assignment is no progress on it: " + before);
		assertTrue(before.containsKey(early.getUuid()));

		report(r.sa(), t);
		Map<UUID, List<BoardStalenessService.Breach>> after = taskBreaches(r, in(61));
		assertFalse(after.containsKey(t.getUuid()), "a report made after this assignment is progress: " + after);
		assertTrue(after.containsKey(early.getUuid()), "the other task is still stalled");
	}

	@Test
	public void aReportOfAnEarlierAssignedTaskIsReadEvenWhenALaterOneStartsTheWindowLater() throws Exception {
		Rig r = rig();
		setStaleness(r, block("hopNoProgressMinutes", 60));
		AgentTaskData early = held(r, r.sb());
		Thread.sleep(25);
		report(r.sb(), early);
		Thread.sleep(25);
		// assigned after the first task's report: a read window that started here would miss that report
		AgentTaskData later = held(r, r.sa());
		Thread.sleep(25);

		Map<UUID, List<BoardStalenessService.Breach>> out = taskBreaches(r, in(61));
		assertFalse(out.containsKey(early.getUuid()), "the report of the earlier task is progress on it: " + out);
		assertTrue(out.containsKey(later.getUuid()), "the later task has reported nothing: " + out);
	}
}
