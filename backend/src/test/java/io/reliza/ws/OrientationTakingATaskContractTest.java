/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static io.reliza.ws.OrientationText.section;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

/**
 * The orientation's {@code taking-a-task} section.
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
class OrientationTakingATaskContractTest {

	/**
	 * Task RD5-9: the hand-over names the RD5 verbs: {@code task verify} before the sign-off (RD5-1), {@code signoff
	 * --pr} (RD5-5), and {@code task push} for a returning task (RD5-3).
	 */
	@Test
	void theHandOverNamesVerifySignoffPrAndPush() throws IOException {
		String doc = section("taking-a-task").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("**Handing over.** Before you sign off, run `rearm agent task verify <task> --session"
				+ " <session-uuid>`. It runs locally what the server would refuse at sign-off (outputs, rounds published"
				+ " since the assignment that you have not read, failing blocking checks, a linked PR that has not moved)"
				+ " and, in a repository a linked PR names, the git facts the coordinator's merge check refuses later"
				+ " (every commit's trailer block, double quotes in subjects, each PR's head is HEAD, the base merged in),"
				+ " and prints every failure at once; it changes nothing. Fix what fails, then sign off."), "task verify");
		assertTrue(doc.contains("`task signoff --pr <url>` links each PR to the task before the sign-off is sent, in order;"
				+ " the first refused link stops the command before any sign-off."), "signoff --pr");
		assertTrue(doc.contains("A returning task pushes its fixes with `rearm agent task push <task> --session"
				+ " <session-uuid>`: it pushes HEAD to the linked PR's head branch, fast-forward only and never to the"
				+ " base, then checks the remote head with `git ls-remote`."), "task push");
		assertTrue(doc.contains("Both verbs take `--base <branch>` when the PR row names no base (a PR CI never registered)."),
				"--base");
	}

	/** Task SPLIT-5 (3.9): the design checkpoint is the one forward hand-over that raises an ALERT, and what to do. */
	@Test
	void theDesignCheckpointStopsAtTheCoordinator() throws IOException {
		String doc = section("taking-a-task").replaceAll("\\s+", " ");
		assertTrue(doc.contains("but not a first forward hand-over (\"X passed, queued for Y\") (except the design"
				+ " checkpoint, which raises an ALERT)"), "the exception on the feed");
		assertTrue(doc.contains("**The design checkpoint.**"), "the paragraph");
		assertTrue(doc.contains("`task show <key>` has the rounds"), "where the rounds are");
		assertTrue(doc.contains("rearm agent task split <key> --session <seat-session-uuid> \\ --children-json"), "the split");
		assertTrue(doc.contains("each child carries the design as pinned inputs"), "the carried design");
		assertTrue(doc.contains("rearm agent task authorize <key> --session <seat-session-uuid> --role coder"), "send it on");
		assertTrue(doc.contains("Authorizing a design role instead sends the task round again"), "the design role");
		// task TPF-152 (6.2, F-4): the stop is qualified once, and the coordinator keeps its verbs on a waived task
		assertTrue(doc.contains("once the design is reviewed, the task stops at the coordinator with an ALERT instead of"
				+ " going to that role, unless the architect signed its newest design round off `--no-split` (below)"),
				"the qualified stop");
		assertTrue(doc.contains("An architect whose design fits one coder pass signs it off with `--no-split`: the task"
				+ " does not stop"), "the waiver");
		assertTrue(doc.contains("You keep `task hold`, `task reopen` and `task split` on a waived task."), "the verbs");
	}

	/**
	 * Task TPF-161 (D11, T-21): a child marked designFromParent goes to the build role on the carried rounds, and the
	 * coder sentence, verbatim (F-12); a proposed child may carry the flag and the coordinator decides.
	 */
	@Test
	void aCarriedChildGoesToTheBuildRole() throws IOException {
		String doc = section("taking-a-task").replaceAll("\\s+", " ");
		String checkpoint = OrientationText.between(doc, "**The design checkpoint.**", "**A split request.**");
		assertTrue(checkpoint.contains("A child marked `designFromParent` at the split (`\"designFromParent\":true` on the"
				+ " child in `--children-json`) carries the parent's reviewed rounds as its design, lists them under"
				+ " `carried from <parent key>`, and goes to the build role"), "the carried child");
		assertTrue(checkpoint.contains("It stops here only once it publishes its own round."), "when it stops");
		assertTrue(checkpoint.contains("On a carried child, work that the child's description asks for and the carried"
				+ " design does not cover is a review item about ARCHITECTURE, not something to build."), "the coder sentence");
		String request = OrientationText.between(doc, "**A split request.**", "A board's events are a log");
		assertTrue(request.contains("A proposed child may say `designFromParent`; the coordinator decides:"
				+ " `--from-request` keeps it, your own `--children-json` may drop it."), "the proposal");
	}

	/**
	 * Task TPF-171 (D14, T-14): the coordinator's verdict on an answer marked no change, both verdicts, and that
	 * authorize confirms nothing.
	 */
	@Test
	void anAnswerMarkedNoChangeWaitsForTheCoordinatorsVerdict() throws IOException {
		String doc = section("taking-a-task").replaceAll("\\s+", " ");
		String paragraph = OrientationText.between(doc, "**An answer marked no change.**", "A child marked `designFromParent`");
		assertTrue(paragraph.contains("`rearm agent task confirm-answer <key> --session <seat-session-uuid> --verdict CONFIRMED`"),
				"the verb");
		assertTrue(paragraph.contains("`--verdict REFUSED`"), "the refusal");
		assertTrue(paragraph.contains("Sending the task on with `authorize` instead confirms nothing: the round counts as a change."),
				"authorize");
		assertTrue(paragraph.contains("You cannot confirm an answer your own agent gave, as the seat or with a personal key of"
				+ " the user whose key that agent runs on."), "the self-confirmation rule");
	}

	/**
	 * Task SPLIT-9 (3.9): a worker asks for a split by returning the task with {@code --reason SPLIT}, and is told what
	 * to do on a CLI that has no {@code --children-json} on {@code return} yet (the orientation is served before the
	 * SPLIT-9b CLI is released).
	 */
	@Test
	void aWorkerAsksForASplitByReturningTheTask() throws IOException {
		String doc = section("taking-a-task").replaceAll("\\s+", " ");
		String paragraph = OrientationText.between(doc, "**Asking for a split.**", "**Handing over.**");
		assertTrue(paragraph.contains("**Asking for a split.** When the task is too big for your role's pass, or the design's"
				+ " last section recommends a split the coordinator has not made, return it with `--reason SPLIT` and one line"
				+ " why; propose the children if you can: `rearm agent task return <task> --session <session-uuid> --reason"
				+ " SPLIT --description '<why>' --children-json"
				+ " '[{\"title\":\"...\"},{\"title\":\"...\",\"dependsOnSiblingIndexes\":[0]}]'`."), "the request and its command");
		assertTrue(paragraph.contains("`--children-json` on `return` needs the rearm CLI release that carries task SPLIT-9b"
				+ " (`rearm agent task return --help` lists it); on an older CLI leave the proposal out, `--reason SPLIT"
				+ " --description '<why>'` records the request without one."), "the fallback on an older CLI");
		assertTrue(paragraph.contains("The coordinator splits it (each child carries the parent's newest handed-over design as"
				+ " pinned inputs) or sends it on with a note; `task brief` prints the request and the note on your next hop."),
				"what comes back");
		assertTrue(doc.indexOf("**Asking for a split.**") > doc.indexOf("return the task (`--reason OTHER` with a description"
				+ " saying so) rather than pressing on."), "after the OTHER return");
		int examples = 0;
		for (String cmd : inlineCommands(paragraph)) {
			if (cmd.endsWith("--help")) continue;
			examples++;
			assertTrue(cmd.contains("--session <session-uuid>"), "no --session: " + cmd);
		}
		assertTrue(examples >= 1, "the return example was found");
	}

	/**
	 * Task SPLIT-9 (3.9, F-8): the coordinator answers a split request by splitting or sending the task on with a note;
	 * on an older CLI it writes the children itself from the ALERT, since that CLI's {@code task show} does not print
	 * the request.
	 */
	@Test
	void aCoordinatorSplitsOrSendsOnASplitRequest() throws IOException {
		String doc = section("taking-a-task").replaceAll("\\s+", " ");
		String paragraph = OrientationText.between(doc, "**A split request.**", "A board's events are a log");
		assertTrue(paragraph.contains("**A split request.** A task in AWAITING_COORDINATOR whose `splitRequest.state` is"
				+ " `REQUESTED` was returned with `--reason SPLIT`; the ALERT names the role, its reason and the proposed"
				+ " children."), "the paragraph");
		assertTrue(paragraph.contains("Read the task, then `rearm agent task split <key> --session <seat-session-uuid>"
				+ " --from-request` (or `--children-json` with your own list) or `rearm agent task authorize <key> --session"
				+ " <seat-session-uuid> --role <role> --note '<why not>'`."), "split or send on");
		assertTrue(paragraph.contains("On a CLI older than the SPLIT-9b release `--from-request` and `--note` do not exist:"
				+ " write the children list yourself from the titles the ALERT names (on a board with a blind role it names"
				+ " only their count) and pass it with `--children-json`; an older CLI's `task show` does not print the"
				+ " request, the UI does once SPLIT-9c is deployed."), "the fallback on an older CLI");
		assertTrue(paragraph.contains("write the children list yourself from the titles the ALERT names"), "the fallback's list");
		assertTrue(paragraph.contains("an older CLI's `task show` does not print the request"), "the fallback's read");
		assertTrue(paragraph.contains("Or authorize without a note (the request is then declined with `authorized for"
				+ " <role>`). Either is recorded on the request."), "the decline without a note");
		assertTrue(paragraph.contains("Every split pins the parent's newest handed-over design on the children, with or without"
				+ " a checkpoint; a parent with no design round pins nothing and the split's INFO says so."), "the pin");
		assertTrue(doc.indexOf("**A split request.**") > doc.indexOf("**The design checkpoint.**"), "after the checkpoint");
		assertFalse(doc.contains("copied from `task show`"), "an older CLI's task show does not print the request");
		int seatCommands = 0;
		for (String cmd : inlineCommands(paragraph)) {
			seatCommands++;
			assertTrue(cmd.contains("--session <seat-session-uuid>"), "no --session: " + cmd);
		}
		assertTrue(seatCommands >= 2, "the split and authorize examples were found: " + seatCommands);
	}

	/**
	 * Task TPF-155 (7.2, T-92 b): an OPTIONAL role with runWhen is passed over by routing when the task's tags do not
	 * meet it; the paragraph names the record, the reason text, when the condition is read, and the verb per state.
	 */
	@Test
	void aConditionalRoleIsPassedOverByRouting() throws IOException {
		String doc = section("taking-a-task").replaceAll("\\s+", " ");
		String paragraph = OrientationText.between(doc, "**Conditional roles.**", "A board's events are a log");
		assertTrue(paragraph.contains("**Conditional roles.**"), "the paragraph");
		assertTrue(paragraph.contains("routing passes the role over with no hop and no event"), "no hop, no event");
		assertTrue(paragraph.contains("`rolesSkippedByCondition`"), "the record");
		assertTrue(paragraph.contains("runWhen not met: no mutation tag"), "the reason");
		assertTrue(paragraph.contains("a tag added later changes nothing until routing next reaches the role"),
				"a tag added later");
		assertTrue(paragraph.contains("while QUEUED, `rearm agent task hold <key> --session <seat-session-uuid> --reason"
				+ " '<why>'` then `rearm agent task lifthold <key> --session <seat-session-uuid> --role <role>`; once back with"
				+ " the coordinator, `rearm agent task authorize <key> --session <seat-session-uuid> --role <role>`; once"
				+ " completed, `rearm agent task reopen <key> --session <seat-session-uuid> --role <role> --reason '<why>'`."
				+ " Each runs the role whatever the tags, for that round."), "the verb per state");
		assertTrue(paragraph.contains("A REQUIRED role never carries a condition."), "never on a REQUIRED role");
		assertTrue(doc.indexOf("**Conditional roles.**") > doc.indexOf("**A split request.**"), "after the split request");
		int seatCommands = 0;
		for (String cmd : inlineCommands(paragraph)) {
			seatCommands++;
			assertTrue(cmd.contains("--session <seat-session-uuid>"), "no --session: " + cmd);
		}
		assertEquals(4, seatCommands, "the hold, lifthold, authorize and reopen examples");
	}

	@Test
	void aCoordinatorFollowsTheBoardWithTwoReads() throws IOException {
		// task 9540d3b6: the feed does not carry forward hand-overs, authorizes or assignments
		String doc = section("taking-a-task").replaceAll("\\s+", " ");
		assertTrue(doc.contains("**How a coordinator follows the board.**"), "the section");
		assertTrue(doc.contains("`task list --changed-since <last updatedAt>` for every movement"), "the second read");
		assertTrue(doc.contains("a quiet feed is not a still board"), "why");
		assertTrue(doc.contains("rearm agent task list --board <board-uuid> --changed-since <last updatedAt>"), "the command");
	}

	/** Every inline {@code `rearm agent ...`} span of a folded paragraph. */
	private static List<String> inlineCommands(String paragraph) {
		List<String> cmds = new ArrayList<>();
		Matcher m = Pattern.compile("`(rearm agent [^`]*)`").matcher(paragraph);
		while (m.find()) cmds.add(m.group(1));
		return cmds;
	}
}
