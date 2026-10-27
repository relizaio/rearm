/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentSessionUsage;
import io.reliza.model.AgentTaskData;
import io.reliza.model.SessionUsageSource;
import io.reliza.repositories.AgentSessionUsageRepository;
import io.reliza.service.AgentSessionUsageService.UsageLine;
import io.reliza.service.AgentSessionUsageService.UsageReport;
import io.reliza.ws.App;

/**
 * lastReportsForTasks (task TPF-69, mutation round): a report stamped exactly at the start of the read window is
 * inside it, the same bound findForHop reads a hop's reports by. A task assigned at the instant of its first
 * report must still see that report as progress.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class LastReportsBoundaryTest extends AgentBoardStalenessTestBase {

	@Autowired private AgentSessionUsageRepository usageRepository;

	@Test
	public void aReportAtTheStartOfTheWindowIsInsideIt() throws RelizaException {
		Rig r = rig();
		AgentTaskData t = queued(r, "reviewer");
		agentTaskService.assign(t.getUuid(), board(r), r.sa().getAgent(), r.sa().getUuid(), WU);
		usageService.report(agentSessionService.getSessionData(r.sa().getUuid()).orElseThrow(),
				new UsageReport(r.sa().getUuid(), null, SEQ.incrementAndGet(), SessionUsageSource.TRANSCRIPT,
						ZonedDateTime.now().minusMinutes(1), ZonedDateTime.now(), 1, 1, 60, null, t.getUuid(), Map.of(),
						List.of(new UsageLine("claude-opus-5-5", null, 0L, 1, 100, 0, 0, 0, null, 100L, 100L, null))), WU);
		List<AgentSessionUsage> rows = usageRepository.findForHop(t.getUuid(), r.sa().getUuid(), ZonedDateTime.now().minusDays(1), null);
		assertEquals(1, rows.size());
		ZonedDateTime stamped = rows.get(0).getReportedAt();

		assertEquals(1, usageRepository.lastReportsForTasks(List.of(t.getUuid()), stamped).size(),
				"a report stamped at the window start is read");
		assertEquals(0, usageRepository.lastReportsForTasks(List.of(t.getUuid()), stamped.plusSeconds(1)).size(),
				"a report before the window start is not");
	}
}
