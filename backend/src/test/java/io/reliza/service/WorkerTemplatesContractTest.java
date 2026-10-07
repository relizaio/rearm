/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.RoleTemplates.template;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Rules several templates state alike, asserted before the split: the worker templates (coder, reviewer,
 * tester), and the coordinator templates too for the task key. Closed to additions: a new rule shared by several
 * templates gets a class of its own, named after the rule.
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
class WorkerTemplatesContractTest {

	@Test
	void everyTemplateNamesTasksByKey() throws IOException {
		// board-documents.md D12 (task 3d1f9dd7): the key leads every human surface, notes and PR titles too.
		for (String name : new String[] {"coder-fix.md", "reviewer.md", "tester.md"}) {
			assertTrue(template(name).contains("Name the task by its key, e.g. `RD-42`"), name + " should say to name the task by its key");
		}
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			assertTrue(template(name).contains("Name tasks by their key, e.g. `RD-42`"), name + " should say to name tasks by their key");
		}
	}

	@Test
	void everyRoleWaitsForWorkInTheBackground() throws IOException {
		// task RD2-32: an idle agent that polls spends a turn per empty poll; each worker template names
		// the background wait for its own role, and what each exit means.
		for (String[] t : new String[][] {{"coder-fix.md", "coder"}, {"reviewer.md", "reviewer"}, {"tester.md", "tester"}}) {
			String doc = template(t[0]);
			assertTrue(doc.contains("## Waiting for work"), t[0]);
			assertTrue(doc.contains("`rearm agent wait --session <session> --board <board> --role " + t[1] + "` in the background"), t[0]);
			assertTrue(doc.contains("On exit 0, assign the offered task; on exit 2, run it again; on exit 1, read the error and run it again."), t[0]);
			assertTrue(doc.contains("Never poll faster than every 30 seconds"), t[0]);
		}
	}

	/**
	 * Task RD2-34: a document can land on the task after pick-up; every worker is told the offer is not the
	 * document list, that the server refuses a sign-off that does not acknowledge a newer document, and that
	 * `task show --session <session>` is the acknowledgement (architecture-3: only a read the session made counts).
	 */
	@Test
	void everyWorkerAcknowledgesDocumentsBeforeSignOff() throws IOException {
		for (String name : new String[] {"coder-fix.md", "reviewer.md", "tester.md"}) {
			String t = template(name);
			assertTrue(t.contains("The offer printed by `task next` or `rearm agent wait` is not the document list"), name);
			assertTrue(t.contains("The server refuses a sign-off that does not acknowledge a document published on the task since your assignment"), name);
			assertTrue(t.contains("`task show --session <session>` is the acknowledgement"), name);
		}
	}

	/** Task RD3-8: every worker works an offer in a fresh context and gets back a few lines. */
	@Test
	void everyWorkerReturnsAFewLines() throws IOException {
		for (String name : new String[] {"coder-fix.md", "reviewer.md", "tester.md"}) {
			String t = template(name);
			assertTrue(t.contains("Do the assignment and everything after it in a fresh context (orientation §2.5d): hand the offered key"
					+ " to a context that starts from `rearm agent task brief <key> --session <session>` and nothing else"), name);
			assertTrue(t.contains("Your main context keeps only the wait loop and those lines."), name);
			assertTrue(t.contains("## What you return"), name);
			assertTrue(t.contains("The fresh context returns, in a few lines: the task's key, the outcome, the PRs, and one line of what it learned."), name);
			assertTrue(t.contains("`rearm agent notes append`"), name);
		}
	}
}
