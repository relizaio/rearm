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
 * What {@code coder-fix.md} must say. A rule it shares with other templates is asserted here when the coder
 * is the role it was written for.
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
class CoderTemplateContractTest {

	/**
	 * Task RD5-9: the hand-over runs {@code task verify} (RD5-1) and signs off with {@code --pr} (RD5-5); its own
	 * paragraph, after the read-the-task-again sentence.
	 */
	@Test
	void theCoderVerifiesThenSignsOffWithPr() throws IOException {
		String doc = template("coder-fix.md");
		String verify = "Then run `rearm agent task verify <key> --session <session>`: it prints at once every check the"
				+ " sign-off and the coordinator's merge check would refuse, and changes nothing. Fix what fails, then sign"
				+ " off with `--pr <url>` for each PR you have not linked yet: the links are made before the sign-off is sent.";
		assertTrue(doc.contains(verify), "coder-fix.md should name task verify and signoff --pr");
		assertTrue(doc.indexOf(verify) > doc.indexOf("Read the task once more right before you sign off"),
				"after the read-the-task-again sentence");
	}

	/** Task RD5-9: the commit and merge go through the RD5-2 helpers; the hand-typed git merge is replaced. */
	@Test
	void theCoderCommitsAndMergesWithTheHelpers() throws IOException {
		String doc = template("coder-fix.md");
		assertTrue(doc.contains("merge the current integration base into each PR branch (`rearm agent git merge --session"
				+ " <session> origin/<base>`)"), "coder-fix.md should name agent git merge");
		assertTrue(doc.contains("Commit with `rearm agent git commit --session <session> -m <subject> -- <paths>`; both"
				+ " helpers sign and write the trailer block from the session."), "coder-fix.md should name agent git commit");
		assertFalse(doc.contains("(`git merge origin/<base>`)"), "the plain git merge is replaced");
	}

	/** Task RD5-9: a fix is pushed with task push (RD5-3). */
	@Test
	void theCoderPushesFixesWithTaskPush() throws IOException {
		String doc = template("coder-fix.md");
		assertTrue(doc.contains("Push each fix to the PR's own head branch with `rearm agent task push <key> --session"
				+ " <session>`: fast-forward only, never to the base, and it checks the remote head after the push."),
				"coder-fix.md should name task push");
		assertFalse(doc.contains("Push each fix to the PR's own head branch. "), "the bare push sentence is replaced");
	}

	@Test
	void theCoderReadsTheLatestArchitectureAdvisoryOrNot() throws IOException {
		// task e97fde56: an architect's amendment can reach a task the coder holds as an advisory round.
		assertTrue(template("coder-fix.md").contains("read the latest `ARCHITECTURE` round of the task, advisory or not"),
				"coder-fix.md should say to read the latest ARCHITECTURE round, advisory or not");
		String orientation = io.reliza.ws.AgentOrientation.full().replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(orientation.contains("publish the amendment on that task with `doc publish --advisory`"),
				"the orientation should say how a role answers on a task it does not hold");
	}

	@Test
	void parallelTasksStayMergeable() throws IOException {
		// task f3e6f756: nine reopens in 38 tasks, nearly all a sibling merging first.
		String coder = template("coder-fix.md");
		assertTrue(coder.contains("merge the current integration base into each PR branch"), "coder-fix.md");
		assertTrue(coder.contains("never rebased or force-pushed"), "coder-fix.md");
		assertTrue(coder.contains("moves only forward"), "coder-fix.md");
		assertTrue(template("tester.md").contains("merged with the current base tip"), "tester.md");
		assertTrue(template("reviewer.md").contains("with the current base tip merged in"), "reviewer.md");
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("re-check every other DELIVERING task's PRs"), name);
			assertTrue(doc.contains("Do not resolve conflicts yourself"), name);
		}
	}

	/**
	 * Task RD4-8: every consumer pins rearm-client-go by a branch-head pseudo-version, so sibling PRs conflict on the
	 * pin and each resolution re-pinned by hand (RD3-5 took four signed merge commits, RD3-11 five). Each consumer now
	 * has `make pin-client-go REF=...`, and the coder template says to land client-go first, bump each consumer with
	 * the target in its own commit, and re-run it on a moved base instead of resolving the lines by hand. The rule
	 * sits in the mergeability section, after the pin-moves-forward sentence.
	 */
	@Test
	void theClientGoPinMovesOnlyThroughItsTarget() throws IOException {
		String rule = "When your task touches client-go, land the client-go PR first; then bump each consumer with `make"
				+ " pin-client-go REF=<merged commit>` in its own commit; if the base moved before you sign off, take the"
				+ " base's module and checksum files and re-run the target rather than resolving the lines by hand.";
		String doc = template("coder-fix.md");
		assertTrue(doc.contains(rule), "coder-fix.md should say how the client-go pin moves");
		int at = doc.indexOf(rule);
		assertTrue(at > doc.indexOf("## Keep every PR mergeable") && at > doc.indexOf("(pin the merge commit)")
				&& at < doc.indexOf("## What you do not do"), "coder-fix.md: the pin rule follows the pin-moves-forward sentence");
	}

	@Test
	void aCorrectionIsAddressedAndNotUnflagged() throws IOException {
		// task cac71351
		String coder = template("coder-fix.md");
		assertTrue(coder.contains("## Corrections"), "coder-fix.md");
		assertTrue(coder.contains("Address it in this round like any review item"), "coder-fix.md");
		String reviewer = template("reviewer.md");
		assertTrue(reviewer.contains("The flag is not yours to remove"), "reviewer.md");
		assertTrue(reviewer.contains("re-raise it as a new review item under a new id"), "reviewer.md");
	}

	/** Task RD4-1: the coder runs every suite the design names, end-to-end suites included, fixtures in the same PR. */
	@Test
	void theCoderRunsTheSuitesTheDesignNames() throws IOException {
		String doc = template("coder-fix.md");
		String rule = "Run every suite the design names before you sign off, end-to-end suites included, and update their"
				+ " fixtures in the same PR; a suite the design forgot but your change breaks is yours too.";
		assertTrue(doc.contains(rule), "coder-fix.md should say to run every suite the design names");
		assertTrue(doc.indexOf(rule) > doc.indexOf("Before every sign-off, merge the current integration base"),
				"the suites sentence sits with the merge-before-sign-off rule");
	}

	/**
	 * Task RD4-4, ARCHITECTURE round 2: a round can land on a task after the coder picked it up, so the coder reads
	 * the task once more right before signing off. The sentence belongs in the paragraph that says to read the task
	 * after assign, which is where a fix hop looks for when to read.
	 */
	@Test
	void theCoderReadsTheTaskAgainBeforeSignOff() throws IOException {
		String sentence = "Read the task once more right before you sign off (`task show --session` or `task brief`):"
				+ " a round may have been published since your pick-up, and reading it is the acknowledgement the server"
				+ " asks for.";
		String raw = RoleTemplates.raw("coder-fix.md");
		String paragraph = null;
		for (String p : raw.split("\\n[ \\t]*\\n")) {
			if (p.contains("after assign, read the task")) {
				paragraph = p.replaceAll("[ \\t]*\\n[ \\t]*", " ");
			}
		}
		assertTrue(paragraph != null, "coder-fix.md should have the paragraph that says to read the task after assign");
		assertTrue(paragraph.contains(sentence), "that paragraph should say: " + sentence);
	}

	/**
	 * Task RD4-15: sibling tasks that appended their tests to the end of one shared class conflicted at its closing
	 * brace whenever one landed first. The generic rule sits in the mergeability section, which is where a coder
	 * looks for what keeps a PR mergeable.
	 */
	@Test
	void aNewTestGoesIntoTheClassForItsTopic() throws IOException {
		String rule = "A new test goes into the class for its topic, or a new class; never at the end of a class other"
				+ " tasks are appending to.";
		String doc = template("coder-fix.md");
		assertTrue(doc.contains(rule), "coder-fix.md should say where a new test goes");
		int at = doc.indexOf(rule);
		assertTrue(at > doc.indexOf("## Keep every PR mergeable") && at < doc.indexOf("## What you do not do"),
				"coder-fix.md: the rule sits in the mergeability section");
	}
}
