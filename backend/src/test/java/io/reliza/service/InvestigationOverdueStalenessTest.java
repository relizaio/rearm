/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentTaskData;
import io.reliza.ws.App;

/**
 * The investigationOverdue staleness rule (task RD4-12, design §3.4): an investigation neither completed nor
 * cancelled the board's minutes past its deadline ALERTs, one breach per task; before that, or once it completes,
 * nothing; a negative threshold is refused; and the rule alone makes the board one the sweep reads.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class InvestigationOverdueStalenessTest extends InvestigationTestBase {

	@Autowired private BoardStalenessService staleness;

	private void overdueAfter(Rig r, Integer minutes) throws RelizaException {
		Map<String, Object> block = new LinkedHashMap<>();
		block.put("investigationOverdueMinutes", minutes);
		Map<String, Object> settings = new LinkedHashMap<>();
		settings.put("staleness", block);
		agentBoardService.updateSettingsFromInput(r.board().getUuid(), settings, WU);
	}

	private List<BoardStalenessService.Breach> breaches(Rig r, ZonedDateTime at) {
		AgentBoardData bd = board(r);
		return staleness.breaches(bd, bd.getStaleness(), at);
	}

	@Test
	public void anInvestigationPastItsDeadlineAndGraceAlertsUntilItCompletes() throws RelizaException {
		Rig r = rig();
		overdueAfter(r, 30);
		assertTrue(agentBoardService.listWatchingStaleness().stream().anyMatch(b -> b.getUuid().equals(r.board().getUuid())),
				"the rule alone puts the board on the sweep");
		ZonedDateTime due = ZonedDateTime.now().plusHours(2);
		AgentTaskData inv = agentTaskService.commission(board(r), new AgentTaskService.CommissionRequest("researcher",
				"measure it", "how slow is it", null, List.of(), null, due, null, null, null, null, null), null, PERSON, WU);
		assertEquals(due.toInstant(), inv.getInvestigation().deadline().toInstant());

		assertTrue(breaches(r, due.minusMinutes(1)).isEmpty(), "before the deadline");
		assertTrue(breaches(r, due.plusMinutes(29)).isEmpty(), "inside the grace");
		List<BoardStalenessService.Breach> late = breaches(r, due.plusMinutes(31));
		assertEquals(1, late.size(), late.toString());
		assertEquals(BoardStalenessService.INVESTIGATION_OVERDUE + ":" + inv.getUuid(), late.get(0).key());
		assertTrue(late.get(0).message().contains(inv.getKey() + " ") && late.get(0).message().contains("overdue"),
				late.get(0).message());

		investigate(r, r.researcher(), inv);
		assertTrue(breaches(r, due.plusMinutes(31)).isEmpty(), "a completed investigation is not overdue");
	}

	@Test
	public void noDeadlineNeverAlertsAndANegativeThresholdIsRefused() throws RelizaException {
		Rig r = rig();
		overdueAfter(r, 0);
		agentTaskService.commission(board(r), ask("researcher", null), null, PERSON, WU);
		assertTrue(breaches(r, ZonedDateTime.now().plusYears(1)).isEmpty());
		assertTrue(AgentBoardService.stalenessProblems(new AgentBoardData.Staleness(null, null, null, null, null, -1))
				.stream().anyMatch(p -> p.contains("investigationOverdueMinutes cannot be negative")));
		assertTrue(AgentBoardService.stalenessProblems(new AgentBoardData.Staleness(null, null, null, null, null, 0)).isEmpty(),
				"0 alerts at the deadline");
	}
}
