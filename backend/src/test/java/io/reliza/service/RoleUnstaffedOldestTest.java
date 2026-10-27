/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import java.time.ZonedDateTime;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskData;
import io.reliza.ws.App;

/**
 * The unstaffed-role mark and its span (task TPF-69, mutation round): the span starts when the OLDEST queued task
 * of the role entered the queue, not the newest, so the mark and its ALERT name how long the role has gone unserved.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class RoleUnstaffedOldestTest extends AgentBoardStalenessTestBase {

	@Test
	public void theUnstaffedSpanStartsAtTheOldestQueuedTask() throws RelizaException, InterruptedException {
		Rig r = rig();
		setStaleness(r, block("roleUnstaffedMinutes", 60));
		AgentTaskData older = queued(r, "coder");
		Thread.sleep(25);
		AgentTaskData newer = queued(r, "coder");
		ZonedDateTime olderSince = BoardStalenessService.inStatusSince(reload(older));
		ZonedDateTime newerSince = BoardStalenessService.inStatusSince(reload(newer));
		assertNotEquals(olderSince.toInstant(), newerSince.toInstant(), "the two tasks entered the queue at different instants");

		List<BoardStalenessService.Breach> b = breaches(r, in(61));
		assertEquals(1, b.size(), b.toString());
		assertEquals(olderSince.toInstant(), b.get(0).since().toInstant(), "the span starts at the oldest queued task");
	}
}
