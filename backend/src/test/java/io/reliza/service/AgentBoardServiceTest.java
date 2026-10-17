/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentBoardData;

/**
 * The routing-rules block every served role prompt ends with (gaps §1.28). Exact text: an agent
 * reads these lines as its rules, so a wording drift is a behaviour change for every role.
 */
class AgentBoardServiceTest {

	private static final String PASSED_LINE = "- A PASSED with a blocking item OPEN is refused at sign-off; say in"
			+ " your note what is OPEN, if anything.\n";
	private static final String PARTLY_FIXED_LINE = "- A review item the producer fixed in part is RESOLVED"
			+ " in the round that saw the fix; what remains is re-raised under a new id whose title names"
			+ " the old one. The same id still OPEN after the producer's round counts as no progress.\n";

	private static final String COORDINATOR_FIRST_LINE = "- Before a person: a no-progress or cycle-cap stop parks"
			+ " for the coordinator first, which may lift it once per stop kind per task or escalate it; the"
			+ " next identical stop, and every budget stop, goes straight to the operator.\n";
	private static final String OPERATOR_ONLY_LINE = "- Every stop parks for the operator; the coordinator cannot"
			+ " lift it.\n";

	@Test
	void routingRulesStrictDefaults() {
		assertEquals(AgentBoardService.ROUTING_RULES_HEADING + "\n"
				+ "- Blocking: every open item blocks -- there is no priority threshold on this board; PASSED requires"
				+ " nothing OPEN.\n"
				+ "- Stops: a pair of roles going round more than 3 times, or the same open ids after 1"
				+ " consecutive round(s), or the budget. At a stop any open item needs a person.\n"
				+ COORDINATOR_FIRST_LINE + PASSED_LINE + PARTLY_FIXED_LINE,
				AgentBoardService.routingRules(new AgentBoardData()));
	}

	@Test
	void routingRulesWithThresholds() {
		AgentBoardData board = new AgentBoardData();
		board.setBlockingPriority(2);
		board.setCompletionPriority(1);
		board.setCycleCap(3);
		board.setNoProgressRepeatsToStop(2);
		board.setCoordinatorStopLift(false);
		assertEquals(AgentBoardService.ROUTING_RULES_HEADING + "\n"
				+ "- Blocking: items with priority ≤ 2 block; lower ones are recorded, never routed on.\n"
				+ "- Stops: a pair of roles going round more than 3 times, or the same open ids after 2"
				+ " consecutive round(s), or the budget. At a stop any blocking item with priority ≤ 1"
				+ " needs a person; the rest is accepted by policy.\n"
				+ OPERATOR_ONLY_LINE + PASSED_LINE + PARTLY_FIXED_LINE,
				AgentBoardService.routingRules(board));
	}

	@Test
	void routingRulesBlockingThresholdWithStrictCompletion() {
		// Only blocking items reach a stop, so "any open item" would overstate it here.
		AgentBoardData board = new AgentBoardData();
		board.setBlockingPriority(2);
		board.setCycleCap(5);
		String rules = AgentBoardService.routingRules(board);
		assertEquals("- Stops: a pair of roles going round more than 5 times, or the same open ids after 1"
				+ " consecutive round(s), or the budget. At a stop any blocking item still open needs a"
				+ " person.", rules.lines().toList().get(2));
	}

	@Test
	void theServedPromptIsTheOperatorsPromptThenTheBlock() {
		AgentBoardData board = new AgentBoardData();
		String rules = AgentBoardService.routingRules(board);
		assertEquals("you are the coder\n\n" + rules, AgentBoardService.servedPrompt(board, "you are the coder"));
		assertEquals(rules, AgentBoardService.servedPrompt(board, null), "a role with no prompt still gets its rules");
	}

	@Test
	void routingRulesStrictSaysNoThreshold() {
		// task de91c937: a tester read "priority 1" as a threshold on a strict board
		String rules = AgentBoardService.routingRules(new AgentBoardData());
		assertTrue(rules.contains("there is no priority threshold on this board"), rules);
		assertFalse(rules.contains("Say the threshold you applied"), rules);
		AgentBoardData lax = new AgentBoardData();
		lax.setBlockingPriority(2);
		assertFalse(AgentBoardService.routingRules(lax).contains("no priority threshold"),
				"a board with a blocking priority has one, and says it");
	}
}
