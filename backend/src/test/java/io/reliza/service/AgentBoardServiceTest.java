/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentBoardData;
import io.reliza.model.AgentBoardData.MergeMethod;
import io.reliza.model.AgentBoardData.MergeProcedure;
import io.reliza.model.AgentBoardData.ServedPromptStatus;
import io.reliza.service.AgentBoardService.ServedPrompt;

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

	// ---------- TPF-97 / TPF-109: the served text by version ----------

	@Test
	void servedTextVersionIsTheFullSha256Hex() {
		String v = AgentBoardService.servedTextVersion("x");
		assertEquals("2d711642b726b04401627ca9fbac32f5c8530fb1903cc4db02258717921a4881", v);
		assertTrue(v.matches("[0-9a-f]{64}"), v);
		assertEquals(v, AgentBoardService.servedTextVersion(new String("x")), "equal strings, one version");
		assertNull(AgentBoardService.servedTextVersion(null));
		String p = "you are the coder";
		assertEquals(AgentBoardService.promptVersion(p), AgentBoardService.servedTextVersion(p).substring(0, 12),
				"the pin is the prefix of the bare prompt's version");
		String served = AgentBoardService.servedPrompt(new AgentBoardData(), p, null);
		assertFalse(AgentBoardService.servedTextVersion(served).startsWith(AgentBoardService.promptVersion(p)),
				"the served version covers the rules, the pin does not");
	}

	private static MergeProcedure merge(String by, MergeMethod method, Boolean atTestedHead) {
		return new MergeProcedure(by, method, atTestedHead, null, null);
	}

	private static String workerVersion(String prompt, Consumer<AgentBoardData> change, MergeProcedure m) {
		AgentBoardData board = new AgentBoardData();
		change.accept(board);
		return AgentBoardService.servedTextVersion(AgentBoardService.servedPrompt(board, prompt, m));
	}

	@Test
	void servedTextVersionCoversEveryInput() {
		String p = "you are the coder";
		MergeProcedure m = merge("PERSON", MergeMethod.MERGE, true);
		Consumer<AgentBoardData> none = b -> { };
		String base = workerVersion(p, none, m);
		assertEquals(base, workerVersion(p, none, m), "byte-stable across calls");
		List<String> moved = new ArrayList<>();
		moved.add(workerVersion("you are the coder, v2", none, m));
		moved.add(workerVersion(p, b -> b.setBlockingPriority(2), m));
		moved.add(workerVersion(p, b -> { b.setBlockingPriority(2); b.setCompletionPriority(1); }, m));
		moved.add(workerVersion(p, b -> b.setCycleCap(7), m));
		moved.add(workerVersion(p, b -> b.setNoProgressRepeatsToStop(3), m));
		moved.add(workerVersion(p, b -> b.setCoordinatorStopLift(false), m));
		moved.add(workerVersion(p, none, merge("COORDINATOR", MergeMethod.MERGE, true)));
		moved.add(workerVersion(p, none, merge("PERSON", MergeMethod.SQUASH, true)));
		moved.add(workerVersion(p, none, merge("PERSON", MergeMethod.MERGE, false)));
		moved.add(workerVersion(p, b -> b.setLadder(new AgentBoardData.Ladder(
				List.of(new AgentBoardData.LadderLevel(0, "task", "one PR")), null)), m));
		for (int i = 0; i < moved.size(); i++) {
			assertNotEquals(base, moved.get(i), "change " + i + " moves the version");
		}
		// the blocking change against the blocking-and-completion change: the completion priority alone moves it too
		assertNotEquals(moved.get(1), moved.get(2), "the completion priority moves the version");

		AgentBoardService service = new AgentBoardService();
		AgentBoardData coord = new AgentBoardData();
		coord.setCoordinatorPrompt("coordinate");
		String c0 = AgentBoardService.servedTextVersion(service.servedCoordinatorPrompt(coord));
		assertEquals(c0, AgentBoardService.servedTextVersion(service.servedCoordinatorPrompt(coord)), "stable");
		coord.setCoordinatorPrompt("coordinate, v2");
		String c1 = AgentBoardService.servedTextVersion(service.servedCoordinatorPrompt(coord));
		assertNotEquals(c0, c1, "a coordinator prompt edit moves the version");
		coord.setDeliveryPolicy(new AgentBoardData.DeliveryPolicy(null, null, merge("PERSON", MergeMethod.SQUASH, null)));
		String c2 = AgentBoardService.servedTextVersion(service.servedCoordinatorPrompt(coord));
		assertNotEquals(c1, c2, "a merge-procedure change moves the version");
	}

	@Test
	void servedPromptOfAnswersAgainstHeldVersions() throws RelizaException {
		String t = "you are the coder";
		String v = AgentBoardService.servedTextVersion(t);
		for (List<String> nothing : java.util.Arrays.asList(null, List.<String>of())) {
			ServedPrompt sp = ServedPrompt.of(t, nothing);
			assertEquals(new ServedPrompt(v, ServedPromptStatus.SERVED, t), sp, String.valueOf(nothing));
		}
		assertEquals(new ServedPrompt(v, ServedPromptStatus.UNCHANGED, null), ServedPrompt.of(t, List.of(v)));
		assertEquals(new ServedPrompt(v, ServedPromptStatus.UNCHANGED, null), ServedPrompt.of(t, List.of("other", v)));
		assertEquals(new ServedPrompt(v, ServedPromptStatus.SERVED, t),
				ServedPrompt.of(t, List.of(AgentBoardService.promptVersion(t))), "the 12-character pin is never held");
		assertEquals(new ServedPrompt(v, ServedPromptStatus.SERVED, t),
				ServedPrompt.of(t, List.of(v.toUpperCase(java.util.Locale.ROOT))), "no normalisation");
		assertNull(ServedPrompt.of(null, List.of(v)));

		assertNull(AgentBoardService.checkHeldVersions(null));
		List<String> fifty = Collections.nCopies(AgentBoardService.HELD_VERSIONS_MAX_ENTRIES, "a".repeat(128));
		assertSame(fifty, AgentBoardService.checkHeldVersions(fifty), "50 x 128 is accepted");
		RelizaException many = assertThrows(RelizaException.class,
				() -> AgentBoardService.checkHeldVersions(Collections.nCopies(51, "a")));
		assertEquals(AgentBoardService.HELD_VERSIONS_REFUSAL, many.getMessage());
		RelizaException longOne = assertThrows(RelizaException.class,
				() -> AgentBoardService.checkHeldVersions(List.of("a".repeat(129))));
		assertEquals(AgentBoardService.HELD_VERSIONS_REFUSAL, longOne.getMessage());
		assertEquals("heldVersions: at most 50 entries of at most 128 characters", AgentBoardService.HELD_VERSIONS_REFUSAL);
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
