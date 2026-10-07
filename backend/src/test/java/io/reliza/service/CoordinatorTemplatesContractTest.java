/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.RoleTemplates.template;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.reliza.ws.RateLimitingFilter;

/**
 * What both coordinator templates, {@code coordinator-board-truth.md} and {@code coordinator-tracker.md},
 * must say. They carry the same rules, so each method checks both.
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
class CoordinatorTemplatesContractTest {

	@Test
	void aTitleIsOneLineAndTheRestGoesInTheDescription() throws IOException {
		// task fceb1e57: register refuses a title over 120 characters or with a line break.
		String line = "A title is one line of at most 120 characters — what a card shows; everything else goes in `--description`";
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			assertTrue(template(name).contains(line), name + " should state the title rule");
		}
		String orientation = io.reliza.ws.AgentOrientation.full().replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(orientation.contains(line), "the orientation should state the title rule");
	}

	@Test
	void theCoordinatorTemplatesSayWhatToDoWithAStop() throws IOException {
		// task bb2b4bcb: an operator's stop stays the operator's; the seat recommends, cancels or waits.
		// task c0a2134c: the first loop stop of a kind is the seat's to lift once or escalate.
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			int stops = doc.indexOf("**The stops.**");
			assertTrue(stops >= 0, name + " has no stops section");
			int next = doc.indexOf("**Exceptions the board hands back.**", stops);
			String section = next < 0 ? doc.substring(stops) : doc.substring(stops, next);
			assertTrue(section.contains("you cannot lift it"), name + " should say the hold is not the coordinator's");
			assertTrue(section.contains("re-raised under a new id"), name + " should name the partly fixed carry");
			assertTrue(section.contains("post an ALERT"), name + " should say what the coordinator can do");
			assertTrue(section.contains("Do not register a replacement task"), name);
			assertTrue(section.contains("comes to you first when the board allows it"), name + " should give the seat the first stop");
			assertTrue(section.contains("lift it once with a note, or to a role"), name);
			assertTrue(section.contains("rearm agent task escalate <task> --reason"), name + " should name the escalate verb");
			assertTrue(section.contains("one lift per stop kind per task"), name);
			assertTrue(section.contains("never lift a"), name + " should forbid a second lift");
			assertTrue(section.contains("Budget stops are always"), name);
		}
	}

	@Test
	void theCoordinatorReadsTheBoardInBulk() throws IOException {
		// task cc14f4cb: one task show per task spent the rate limit in a poll.
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("rearm agent task list --board <board> --status AWAITING_COORDINATOR"), name);
			assertTrue(doc.contains("rearm agent task list --board <board> --status DELIVERING"), name);
			assertTrue(doc.contains("rearm agent board events <board> --after <last seq>"), name);
			assertTrue(doc.contains("Use `task show` only for a task the feed named"), name);
			assertTrue(doc.contains("at most " + RateLimitingFilter.REQUESTS_PER_WINDOW + " requests per "
					+ RateLimitingFilter.WINDOW_SECONDS + " seconds"), name + " should state the filter's limit");
			// T-2 of round 1: each rearm command is a token exchange and a call, both counted.
			assertTrue(doc.contains("each `rearm` command spends two of them"), name);
			assertTrue(doc.contains("about " + RateLimitingFilter.REQUESTS_PER_WINDOW / 2 + " commands per "
					+ RateLimitingFilter.WINDOW_SECONDS + " seconds"), name + " should count in commands");
		}
	}

	@Test
	void theCoordinatorDeclaresADeliveryThisInstanceCannotSee() throws IOException {
		// task 18c5c293: a PR registered elsewhere, or a board without PRs, settles by declaration.
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("rearm agent task declare-delivery <task> --session <uuid> --unit <pr> --commit <merge sha>"), name);
			assertTrue(doc.contains("`--abandoned`"), name);
		}
	}

	@Test
	void theCoordinatorMergesInDeliveringAndReopensWhatCannotLand() throws IOException {
		// task 1a80f338: the PR_MERGE section predated DELIVERING and reopen, and contradicted both.
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			int start = doc.indexOf("## When this board declares you cover PR_MERGE");
			assertTrue(start >= 0, name + " has no PR_MERGE section");
			int next = doc.indexOf(" ## ", start + 1);
			String section = next < 0 ? doc.substring(start) : doc.substring(start, next);
			assertTrue(section.contains("waits in **DELIVERING**"), name + " should merge while the task is DELIVERING");
			assertTrue(section.contains("from DELIVERING"), name + " should reopen what cannot land, from DELIVERING");
			assertFalse(doc.contains("Once a task completes, merge"), name + " still merges after completion");
			assertFalse(doc.contains("not a reason to reopen"), name + " still refuses the reopen");
			// T-1 of round 1: after PR_CLOSED the task is AWAITING_COORDINATOR, where reopen is refused.
			// (RD3-13 replaced "a closed PR cannot be dropped or replaced": see theReplacedPrIsDeclaredSuperseded.)
			assertTrue(section.contains("`task authorize <task> --role coder`"), name + " should name the verb after PR_CLOSED");
			assertFalse(section.contains("reopen it to the coder"), name + " still reopens after PR_CLOSED");
		}
	}

	@Test
	void aDecisionForAPersonIsAnOperatorHoldOnTheTask() throws IOException {
		// task RD4-17: the seat's decisions ended up in chat, since an ALERT reaches a person only through a
		// subscription; the seat now parks the task with the question, in DELIVERING too.
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("**Decisions for a person.** A decision for a person is an operator hold with the"
					+ " question, the options you see and your recommendation, on the task; an ALERT alone reaches nobody"
					+ " without a subscription, and chat leaves no record."), name + " should state the rule");
			assertTrue(doc.contains("rearm agent task hold <key> --session <uuid> --operator \\ --question '"),
					name + " should give the seat's hold as it runs");
			assertTrue(doc.contains("in PENDING_INTAKE, QUEUED, AWAITING_COORDINATOR or DELIVERING"), name + " the four states");
			assertTrue(doc.contains("the task returns to the state it was parked from, so a DELIVERING task goes on"
					+ " delivering"), name + " should say where the answer leaves the task");
			assertTrue(doc.contains("nothing moves it: every task verb a session runs on it (`declare-delivery`, `--abandoned`,"
					+ " `supersedepr`, `complete`, `reopen`, `cancel`, `order`, `work-level`, `requirereview` and the rest) is"
					+ " refused"), name + " should say what is refused while it waits");
			// RD4-17 architecture round 2: any person's action is the answer, recorded through one path
			assertTrue(doc.contains("A person answers by lifting the hold with a note, or by anything else they do on"
					+ " the task (answering its questions,"), name + " should say any action of a person answers it");
			assertTrue(doc.contains("reading \"<action> by <person>: <note>\""), name + " should say how the answer reads");
			int start = doc.indexOf("## When this board declares you cover PR_MERGE");
			String merge = doc.substring(start);
			assertFalse(merge.contains("post an ALERT with the PR and the error"), name + " still only alerts on a red PR");
			assertFalse(merge.contains("post an ALERT naming the task and the PR, and leave the call to the operator"),
					name + " still only alerts on a closed PR");
			assertTrue(merge.contains("park the task for the operator with the PR, the error, the options and your"
					+ " recommendation"), name + " should park a PR that fails for another reason");
		}
	}

	@Test
	void theMergeProcedureIsTheBoards() throws IOException {
		// task 71a3dd22: the templates read delivery.merge instead of assuming a coordinator's merge commit
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("The board's `delivery.merge` says who merges and how; `task mergeplan` prints the command for it."),
					name);
			assertTrue(doc.contains("If `by` is not you, you do not merge"), name);
			assertTrue(doc.contains("Merge in the order `delivery.merge.order` gives"), name);
			assertFalse(doc.contains("Merge in the order the notes give when tasks depend on each other, else the oldest pass first."),
					name + " still assumes the order");
		}
		String coder = template("coder-fix.md");
		assertTrue(coder.contains("Under SQUASH your commits collapse"), "coder-fix.md");
		assertTrue(coder.contains("the board's `atTestedHead` guards the PR head, not the result"), "coder-fix.md");
	}

	@Test
	void theCoordinatorTemplatesReadTheTasksThatMoved() throws IOException {
		// task 9540d3b6
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("task list --board <board> --changed-since <last updatedAt>"), name);
			assertTrue(doc.contains("a quiet feed is not a still board"), name);
		}
	}

	@Test
	void theCoordinatorWaitsForAttention() throws IOException {
		// task RD2-32: the coordinator waits on the board with the seat held, and reads what woke it first.
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("## Waiting for attention"), name);
			assertTrue(doc.contains("`rearm agent wait --session <seat> --board <board> --coordinator` in the background"), name);
			assertTrue(doc.contains("read the printed triggers and events first, then act"), name);
			assertTrue(doc.contains("arm the next wait with `--after <nextAfter>`"), name);
			// RD2-32 T-1: a wake with no event read prints nextAfter null; the wait resumes from its state.
			assertTrue(doc.contains("when it printed one; the wait keeps its place in its state file, so nothing posted"
					+ " while you worked is lost either way"), name);
			assertTrue(doc.contains("It touches the seat on every poll, so the seat stays yours while you wait."), name);
		}
	}

	/** Task RD3-8: the coordinator's context keeps only its wait loop; task work goes to a fresh context. */
	@Test
	void theCoordinatorKeepsOnlyTheLoop() throws IOException {
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String t = template(name);
			assertTrue(t.contains("Your context keeps only this loop."), name);
			assertTrue(t.contains("write what matters to the board (an order, a hold, a note) rather than keeping it in mind"), name);
			assertTrue(t.contains("do it in a fresh context as orientation §2.5d describes, and keep only the few lines it returns."), name);
		}
	}

	/** Task RD3-15: the coordinator's wait wakes only on a trigger it has not reported; a shrinking set stays quiet. */
	@Test
	void theCoordinatorWakesOnlyOnATriggerItHasNotReported() throws IOException {
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("It wakes when a trigger appears that it has not reported (a new task, or a task in a new"
					+ " state), or on an `ALERT`; a set that only shrinks stays quiet, and the new triggers print first,"
					+ " marked `\"new\": true`."), name);
		}
	}

	/**
	 * Task RD3-13: a replaced PR is declared superseded, never declared abandoned, so the coordinator does not
	 * ask a person to abandon it; the old "cannot be dropped or replaced" is gone.
	 */
	@Test
	void theReplacedPrIsDeclaredSuperseded() throws IOException {
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			// RD4-1 rewrote the paragraph; aReplacedPrIsSupersededInBothCoordinatorTemplates holds its wording.
			assertTrue(doc.contains("is superseded, not abandoned."), name);
			assertFalse(doc.contains("cannot be dropped or replaced"), name + " still says a closed PR cannot be replaced");
		}
	}

	/**
	 * Task RD4-1: a replaced PR is superseded, not abandoned; the holder with CODE_PUSH, the seat or a BOARD_WRITE key
	 * declares it (RD3-18), and an old PR no CI reported closed is declared abandoned first (RD3-20). The sentence for
	 * a PR that never merges and has no replacement stays, after the paragraph.
	 */
	@Test
	void aReplacedPrIsSupersededInBothCoordinatorTemplates() throws IOException {
		String paragraph = "A PR replaced by another — for example to drop commits without their ReARM trailers, since"
				+ " nothing is force-pushed — is superseded, not abandoned. The holder with `CODE_PUSH`, you as the seat, or"
				+ " any `BOARD_WRITE` key declares it: `rearm agent task supersedepr <key> --session <uuid> --old <closed pr>"
				+ " --by <replacement pr>`; delivery then counts the replacement only. If CI never reported the old PR"
				+ " closed and the tracker cannot be read, declare it abandoned first and then declare the supersede; an"
				+ " abandoned unit accepts one.";
		String noReplacement = "A PR that will never merge is `--abandoned`, with a note saying why; the task comes back to you.";
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			assertTrue(doc.contains(paragraph), name + " should carry the current replaced-PR paragraph");
			assertTrue(doc.contains(noReplacement), name + " should keep the sentence for a PR with no replacement");
			assertTrue(doc.indexOf(noReplacement) > doc.indexOf(paragraph), name + ": the --abandoned sentence follows the paragraph");
			assertTrue(doc.contains("A PR that shows unregistered means the repository's CI is not reporting"), name);
			assertFalse(doc.contains("the coder holding the task links the replacement"), name + " still names only the coder");
		}
	}

	/**
	 * Task RD4-9: duplicate registrations are the coordinator's call (the server dedupes only on externalRef), and on
	 * Dogfood 3 it kept the last copy, RD3-18, and cancelled the original RD3-17. The earliest keeps its key; each later
	 * copy is cancelled with a note naming it. The sentence sits in the Intake item of both coordinator templates.
	 */
	@Test
	void aDuplicateRegistrationKeepsTheEarliestKey() throws IOException {
		String rule = "**Duplicates keep the earliest.** When several registrations are the same task, the same title landing"
				+ " within a short window of each other, the earliest (registered first, the lower key) keeps its key: cancel"
				+ " each later copy with the note \"duplicate of <earliest key>\", `rearm agent task cancel <later key> --session"
				+ " <seat> --note 'duplicate of <earliest key>'`. Never cancel the earliest in favour of a later copy: its key"
				+ " is the one people have already seen.";
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			assertTrue(doc.contains(rule), name + " should say the earliest registration keeps its key");
			int at = doc.indexOf(rule);
			assertTrue(at > doc.indexOf("1. **Intake.**") && at < doc.indexOf("2. **Splitting and ordering.**"),
					name + ": the duplicates rule sits in the Intake item");
			assertFalse(doc.contains("keep the latest") || doc.contains("keeps the last copy"), name + " keeps a later copy");
		}
	}

	/**
	 * Task RD4-13: after a review item about a role's own document, that role's pass with a new round goes to the role
	 * that builds from it before the filer re-checks; a --no-change pass goes back to the filer. Each coordinator
	 * template states both paths, one sentence each, and coder-fix.md says what such a task carries.
	 */
	@Test
	void anAnswerThatChangesWhatIsBuiltGoesToTheBuilderFirst() throws IOException {
		String newRound = "When a review item filed about a role's own document (an architect's `ARCHITECTURE`) sends the"
				+ " task to that role and it passes with a new round, the board sends the task to the next role that"
				+ " builds from the round, the coder, before the filer re-checks, and the review item stays open until the"
				+ " filer does.";
		String noChange = "When that role signs off with `--no-change`, saying its round changes nothing to build (a"
				+ " departure accepted, an observation answered), the board returns the task to the filer at once.";
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			assertTrue(doc.contains(newRound), name + " should state the new-round path");
			assertTrue(doc.contains(noChange), name + " should state the --no-change path");
			assertTrue(doc.indexOf(newRound) > doc.indexOf("**The board routes; you do not.**")
					&& doc.indexOf(noChange) < doc.indexOf("**Following the board.**"),
					name + ": both sit with the routing paragraph");
		}
		assertTrue(template("coder-fix.md").contains("A task arriving this way carries the architect's new round as its"
				+ " input, and the open review item names why"), "coder-fix.md should say what a task after an answer carries");
		for (String name : new String[] {"tester.md", "reviewer.md"}) {
			assertFalse(template(name).contains("When the architect passes, the task comes back to you to verify"),
					name + " still says the architect's pass comes straight back to the filer");
		}
	}
}
