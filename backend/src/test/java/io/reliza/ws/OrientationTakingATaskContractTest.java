/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static io.reliza.ws.OrientationText.section;
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
