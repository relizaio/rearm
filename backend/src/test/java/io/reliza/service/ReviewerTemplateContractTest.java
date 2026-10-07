/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.RoleTemplates.template;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * What {@code reviewer.md} must say: the verdict, the partly fixed rule and the reviewer's wait.
 *
 * <p>The role templates operators copy into board prompts must say what the board routes on
 * (gaps §1.28). They were written for a board that passes with lower-priority items open, and
 * boards ship strict: a PASSED with a P3 open went back upstream, and a narrowed review item carried
 * under its id parked the task as no progress (seen on d0ee3624).
 *
 * <p>One file per template: add your assertion to the file for the template you changed, never at the end of a
 * shared class. A rule that spans several templates gets a class of its own, named after the rule. Task RD4-15 split
 * the old {@code AgentRoleTemplatesContractTest} this way, because every task that appended to its end made the next
 * PR conflict at the closing brace; the methods kept their names.
 */
class ReviewerTemplateContractTest {

	/** Task RD5-9: the hand-over runs task verify (RD5-1) between the publish and the sign-off. */
	@Test
	void theReviewerVerifiesBeforeTheSignOff() throws IOException {
		String raw = RoleTemplates.raw("reviewer.md");
		assertTrue(raw.contains("--type BOARD_REVIEW_ITEMS --task <uuid>\nrearm agent task verify <task-uuid> --session <uuid>\n"
				+ "rearm agent task signoff <task-uuid>"), "reviewer.md: verify sits between the publish and the sign-off");
		assertTrue(template("reviewer.md").contains("`doc publish` remembers the release, so `signoff` sends it for you. `task"
				+ " verify` runs locally what the server would refuse at sign-off and prints every failure at once; fix what"
				+ " fails, then sign off."), "reviewer.md should say what verify does");
	}

	@Test
	void theVerdictIsNothingBlockingOpenNotNoPriorityOne() throws IOException {
		for (String name : new String[] {"reviewer.md", "coder-fix.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("nothing that blocks on this board"),
					name + " should state the board's blocking threshold as the bar");
			assertFalse(doc.contains("no priority-1 review item is OPEN"), name + " still says the priority-1 bar");
			assertFalse(doc.contains("no priority-1 review item is open"), name + " still says the priority-1 bar");
		}
	}

	@Test
	void aPartlyFixedReviewItemIsResolvedAndReRaised() throws IOException {
		for (String name : new String[] {"reviewer.md", "tester.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("partly"), name + " should state the partly fixed rule");
			assertTrue(doc.contains("new id"), name + " should say what remains gets a new id");
		}
		assertTrue(template("coder-fix.md").contains("partly fixed"),
				"the coder's note should say which ids were partly fixed");
	}

	@Test
	void theVerdictPointsAtTheServedRoutingRules() throws IOException {
		String reviewer = template("reviewer.md");
		int verdict = reviewer.indexOf("## The verdict");
		assertTrue(verdict >= 0, "reviewer.md has no verdict section");
		int next = reviewer.indexOf(" ## ", verdict + 1);
		String section = next < 0 ? reviewer.substring(verdict) : reviewer.substring(verdict, next);
		assertTrue(section.contains("routing-rules block"),
				"the verdict section should point at the block the served prompt ends with");
		for (String name : new String[] {"tester.md", "coder-fix.md"}) {
			assertTrue(template(name).contains("routing-rules"), name + " should point at the served block");
		}
	}

	@Test
	void theVerdictRuleStatesBothSides() throws IOException {
		// task 5ec48b02: nothing blocking OPEN is a pass; a rejection over nothing blocking is unexplained
		String reviewer = template("reviewer.md");
		assertTrue(reviewer.contains("when nothing blocking is OPEN, your verdict is `PASSED`"), "reviewer.md");
		assertTrue(reviewer.contains("A `REJECTED` that leaves nothing blocking is an unexplained rejection and goes to the"
				+ " coordinator"), "reviewer.md");
	}

	/** Task RD4-3: a reviewer that rejected waits with --watch for the producer's next round; in its waiting section. */
	@Test
	void aRejectingReviewerWaitsWithWatch() throws IOException {
		String doc = template("reviewer.md");
		String line = "After a `REJECTED` round, wait with `--watch` to learn when the producer's next round lands: the"
				+ " wait then also wakes when the producer passes a task you rejected, or when it is returned or reopened,"
				+ " and prints the rounds published since your sign-off.";
		assertTrue(doc.contains(line), "reviewer.md should say to wait with --watch after a REJECTED round");
		assertTrue(doc.indexOf(line) > doc.indexOf("## Waiting for work") && doc.indexOf(line) < doc.indexOf("## What you return"),
				"reviewer.md: the line sits in the waiting section");
	}
}
