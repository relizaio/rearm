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
 * What {@code tester.md} must say, with the rules it shares word for word with {@code reviewer.md} when the
 * tester is the role they were written for.
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
class TesterTemplateContractTest {

	/** Task RD5-9: the hand-over runs task verify (RD5-1) between the publish and the sign-off. */
	@Test
	void theTesterVerifiesBeforeTheSignOff() throws IOException {
		String raw = RoleTemplates.raw("tester.md");
		assertTrue(raw.contains("--type BOARD_TEST_REPORT --task <uuid>\nrearm agent task verify <task-uuid> --session <uuid>\n"
				+ "rearm agent task signoff <task-uuid>"), "tester.md: verify sits between the publish and the sign-off");
		assertTrue(template("tester.md").contains("`task verify` runs locally what the server would refuse at sign-off and"
				+ " prints every failure at once; fix what fails, then sign off."), "tester.md should say what verify does");
	}

	@Test
	void theReviewersSayWhichHeadTheyPassedAndTheCoordinatorMergesThere() throws IOException {
		// task 3b97ccfd: the index names the tested head per PR; the coordinator merges at it.
		for (String name : new String[] {"tester.md", "reviewer.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("\"tested\": [{ \"pr\": "), name + "'s index example should carry tested");
		}
		// task 5ec48b02: required on the tester's pass only; a reviewer may name what it looked at.
		assertTrue(template("tester.md").contains("Name the head you tested"), "tester.md");
		assertTrue(template("tester.md").contains("the board refuses a PASS without it"), "tester.md");
		String reviewer = template("reviewer.md");
		assertTrue(reviewer.contains("You may name the PR heads you looked at in `tested`; the tester's pass is what"
				+ " the board holds them to."), "reviewer.md");
		assertFalse(reviewer.contains("name the head you reviewed for every PR"), "reviewer.md");
		assertFalse(reviewer.contains("Name the head you reviewed for every PR"), "reviewer.md");
		assertFalse(reviewer.contains("the board refuses a PASS without it"), "reviewer.md");
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("Merge at the tested head"), name);
			assertTrue(doc.contains("rearm agent task mergeplan <task>"), name + " should name the verb");
			assertTrue(doc.contains("--match-head-commit <head>"), name);
		}
	}

	@Test
	void theVerdictNamesNoThresholdTheBoardDidNotGive() throws IOException {
		// task de91c937: "priority-1 review item" and "say the threshold you applied" read as a P1 rule
		for (String name : new String[] {"reviewer.md", "tester.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("There is no priority threshold unless your served routing-rules block names one"), name);
			assertFalse(doc.contains("Say the threshold you applied"), name);
			assertTrue(doc.contains("refuses a `PASSED` sign-off"), name);
		}
		assertTrue(template("tester.md").contains("not whether you may pass"), "tester.md");
	}

	/**
	 * Task RD4-4: a review item whose remedy is a design change is filed about ARCHITECTURE, not left as an observation
	 * for the architect, which routes nowhere. The tester and the reviewer carry the same section. The architect's
	 * pass returns the task to the asker to verify (popToAsker), and a partly answered item is resolved and re-raised
	 * under a new id, since the same id open after the architect's round is a no-progress stop.
	 */
	@Test
	void designBoundReviewItemsGoToTheArchitect() throws IOException {
		String heading = "## A review item the design must fix";
		String[] sentences = {
				"A review item whose remedy is a change to the design, not to the code, is filed `about: ARCHITECTURE`"
						+ " (`\"about\": {\"specification\": \"ARCHITECTURE\"}` in the index); the task then goes to the"
						+ " architect, who answers with a round, before any code is changed for it.",
				"Say in the review item what the design lacks.",
				"Do not leave such an item as an observation for the architect: an observation routes nowhere.",
				"For example, a live check the design calls for cannot run where the design says it runs: file it about"
						+ " the architecture, naming what the design must say instead.",
				"`about` names the whole round, so every item in it goes to the architect first.",
				// RD4-13: a new round goes to the role that builds from it before the filer verifies.
				"When the architect passes with a new round, the task goes to the next role that reads the round"
						+ " (usually the coder, to build it) and comes back to you to verify once that role has passed;"
						+ " when the architect signs off with `--no-change`, saying the round changes nothing to build, it"
						+ " comes back to you at once.",
				"Then close what the new round settled, and file what the code must still do as new items without"
						+ " `about`, which go to the coder, not the architect.",
				"When a round only partly answers, what remains is still about the architecture: mark the item `RESOLVED`"
						+ " and re-raise the rest under a new id whose title names the old one, `about: ARCHITECTURE` again.",
				"The same id open after the architect's round is no progress and parks the task."};
		String[] sections = new String[2];
		String[] names = {"tester.md", "reviewer.md"};
		for (int i = 0; i < names.length; i++) {
			String doc = template(names[i]);
			int start = doc.indexOf(heading);
			assertTrue(start >= 0, names[i] + " should carry the design-bound review item section");
			int end = doc.indexOf("## ", start + heading.length());
			assertTrue(end > start, names[i] + ": the section ends at the next heading");
			sections[i] = doc.substring(start, end);
			for (String sentence : sentences) {
				assertTrue(sections[i].contains(sentence), names[i] + " should say: " + sentence);
			}
		}
		assertTrue(sections[0].equals(sections[1]), "tester.md and reviewer.md should carry the same section");
	}
}
