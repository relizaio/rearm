/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static io.reliza.ws.OrientationText.orientation;
import static io.reliza.ws.OrientationText.section;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * The orientation's {@code waiting} section: the background wait and the fresh context per task.
 *
 * <p>The orientation document is the agent-facing API contract, and for document handoff it is the
 * ONLY thing an agent reads: role prompts are operator material kept in the repository, and agents
 * receive their role's prompt with the task rather than fetching it. So anything the server refuses a
 * document for has to be stated here. A rule enforced in code and undocumented in the one place agents
 * look is a rule agents will break, and the refusal arrives after the work is done.
 *
 * <p>One file per orientation section: add your assertion to the file for the section you changed, or to a new
 * {@code Orientation<Section>ContractTest} for a section that has none, never at the end of a shared class. Task
 * RD4-15 split the old {@code AgentOrientationContractTest} this way, because every task that appended to its end made
 * the next PR conflict at the closing brace; the methods kept their names.
 */
class OrientationWaitingContractTest {

	/**
	 * Task RD5-9: the watch's change shape after RD5-7: from and to from the status history, status as it is now, and
	 * new on every change. One paragraph after the --watch paragraph, before the interval.
	 */
	@Test
	void aWatchChangePrintsFromToStatusAndNew() throws IOException {
		String doc = section("waiting").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		String fromTo = "Each change's `from` and `to` are read from the task's status history: `from` is the status the"
				+ " hop end's own row left, `to` is where that transaction left the task, through the routing rows the"
				+ " system wrote with it, and `status` is the task's status now. A rejection prints from `ASSIGNED`, to"
				+ " `QUEUED`; a rejected task the coder has picked up again prints from `ASSIGNED`, to `QUEUED`, status"
				+ " `ASSIGNED`.";
		String isNew = "Every change prints `new`: `true` on the wake that first reports it, `false` on a standing change"
				+ " printed again because it still stands.";
		assertTrue(doc.contains(fromTo), "from, to and status");
		assertTrue(doc.contains(isNew), "new");
		assertTrue(doc.indexOf(fromTo) > doc.indexOf("so the next wait does not report it again.")
				&& doc.indexOf(isNew) < doc.indexOf("Never poll faster than every 30 seconds"),
				"after the --watch paragraph, before the interval");
	}

	@Test
	void waitingForWorkIsExplainedWithAnExample() throws IOException {
		// task RD2-32: the background wait, what each exit means, the limits, and one harness example
		String doc = section("waiting").replaceAll("\\s+", " ");
		assertTrue(doc.contains("### 2.5c Waiting for work (task boards)"), "the section");
		assertTrue(doc.contains("rearm agent wait --session <session-uuid> --board <board-uuid> --role <role>"), "the worker command");
		assertTrue(doc.contains("rearm agent wait --session <seat-session-uuid> --board <board-uuid> --coordinator"), "the coordinator command");
		assertTrue(doc.contains("**Exit 0** is work."), "exit 0");
		assertTrue(doc.contains("**Exit 2** is the heartbeat timeout"), "exit 2");
		assertTrue(doc.contains("a wait armed without `--after` reads on from where the last one stopped"),
				"the cursor is kept between waits (T-1)");
		assertTrue(doc.contains("**Exit 1** means two polls in a row failed"), "exit 1");
		assertTrue(doc.contains("Never poll faster than every 30 seconds"), "the interval floor");
		assertTrue(doc.contains("Never run `wait` with shell tracing on"), "no tracing");
		assertTrue(doc.contains("poll with `task next` no more than once every five minutes"), "the fallback");
		assertTrue(doc.contains("the Bash tool's `run_in_background`"), "the Claude Code example");
		assertTrue(section("inbox").replaceAll("\\s+", " ").contains("**On a task board, wait for work with `rearm agent wait` (§2.5c)**"),
				"the inbox section points to it");
	}

	/**
	 * Task RD3-8: a hop is worked in a fresh context that starts from the brief, runs under the same session and
	 * key, and returns a few lines; the main context keeps only the wait loop. Harness-neutral, with one Claude Code
	 * example that names no file path.
	 */
	@Test
	void aTaskIsWorkedInAFreshContext() throws IOException {
		String doc = section("waiting").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("### 2.5d Working a task in a fresh context (task boards)"), "the section");
		assertTrue(doc.contains("When `rearm agent wait` exits with an offer, do the hop in a fresh context: one that starts from"
				+ " `rearm agent task brief <key> --session <s>` and nothing else."), "starts from the brief");
		assertTrue(doc.contains("returns to you a few lines: the key, the outcome, the PRs, one line of what it learned."), "what it returns");
		assertTrue(doc.contains("Your main context keeps only the wait loop and those lines."), "the loop only");
		assertTrue(doc.contains("The fresh context runs under your session and your key, so its usage and its attribution are yours."), "same session");
		assertTrue(doc.contains("The hooks report a spawned context's usage under your session; install them as §2.3 says."), "usage (round 2)");
		assertTrue(doc.contains("(`rearm agent notes append`)"), "carry-over through the notes");
		assertTrue(doc.contains("For example, in Claude Code: define a custom agent whose whole instruction is"), "one Claude Code example");
		assertFalse(doc.substring(doc.indexOf("### 2.5d")).contains(".claude/"), "the example names no file path");
	}

	/** Task RD3-15: the coordinator's wait wakes on a trigger it has not reported, not on a new set; in the waiting section. */
	@Test
	void theCoordinatorWaitWakesOnlyOnAnUnreportedTrigger() throws IOException {
		String doc = section("waiting").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("It wakes when a trigger appears that it has not reported (a new task, or a task in a new"
				+ " state), or on an `ALERT`; a set that only shrinks stays quiet. The triggers print with the new ones"
				+ " first, marked `\"new\": true`."));
		assertFalse(orientation().replaceAll("[ \\t]*\\n[ \\t]*", " ").contains("The same set of triggers does not wake it twice."),
				"the whole-set rule is gone");
	}

	/** Task RD4-3: a worker's wait with --watch, one paragraph in the waiting section after the coordinator's wake rules. */
	@Test
	void aWorkerWatchWakesOnWhatHappensToItsWork() throws IOException {
		String doc = section("waiting").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		String watch = "A worker that also follows what happens to its work adds `--watch` (with `--board`): when there is no"
				+ " offer, it also wakes when a task it signed off on is rejected, returned or reopened, or passed again after"
				+ " its own rejection, when an open question is addressed to its role, and on an `ALERT`, `PAUSED` or"
				+ " `RESUMED` event, and prints each change with the document rounds published since its sign-off, so you"
				+ " can read the rejection without a `task show`.";
		assertTrue(doc.contains(watch), "the --watch paragraph");
		assertTrue(doc.indexOf(watch) > doc.indexOf("It wakes when a trigger appears that it has not reported")
				&& doc.indexOf(watch) < doc.indexOf("Never poll faster than every 30 seconds"),
				"after the coordinator's wake rules, before the interval");
	}

	/**
	 * Task RD4-16: a watch prints one shape, the offer inside it, and keeps what it reported on every exit;
	 * it closes the --watch paragraph.
	 */
	@Test
	void aWatchPrintsOneShapeAndKeepsWhatItReportedOnEveryExit() throws IOException {
		String doc = section("waiting").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		String shape = "without a `task show`. An offer still wins, and prints inside the same object as `offer` (`null` on"
				+ " every other wake and on the timeout), so you parse one shape; whatever ends the wait, what it reported"
				+ " is kept in `--state` first, so the next wait does not report it again.";
		assertTrue(doc.contains(shape), "the --watch paragraph ends with the one shape and the kept state");
		assertFalse(doc.contains("An offer still wins and prints as before."), "an offer no longer prints as without --watch");
	}
}
