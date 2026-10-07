/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static io.reliza.ws.OrientationText.section;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

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

	@Test
	void aCoordinatorFollowsTheBoardWithTwoReads() throws IOException {
		// task 9540d3b6: the feed does not carry forward hand-overs, authorizes or assignments
		String doc = section("taking-a-task").replaceAll("\\s+", " ");
		assertTrue(doc.contains("**How a coordinator follows the board.**"), "the section");
		assertTrue(doc.contains("`task list --changed-since <last updatedAt>` for every movement"), "the second read");
		assertTrue(doc.contains("a quiet feed is not a still board"), "why");
		assertTrue(doc.contains("rearm agent task list --board <board-uuid> --changed-since <last updatedAt>"), "the command");
	}
}
