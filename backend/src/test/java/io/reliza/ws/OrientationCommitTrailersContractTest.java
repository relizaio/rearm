/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static io.reliza.ws.OrientationText.section;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * The orientation's {@code commit-trailers} section.
 *
 * <p>One file per orientation section: add your assertion to the file for the section you changed, never at the end
 * of a shared class (RD4-15).
 */
class OrientationCommitTrailersContractTest {

	private static String doc() throws IOException {
		return section("commit-trailers").replaceAll("[ \\t]*\\n[ \\t]*", " ");
	}

	/**
	 * Task RD5-9: the block's definition stays, and the instruction to sign and compose it is replaced by the RD5-2
	 * helpers, which write the trailers from the session.
	 */
	@Test
	void commitsAndMergesGoThroughTheHelpers() throws IOException {
		String doc = doc();
		assertTrue(doc.contains("Commit with `rearm agent git commit --session <session-uuid> -m <subject> [-m <body>]... --"
				+ " <path>...` and merge with `rearm agent git merge --session <session-uuid> <ref>`. `git commit` stages"
				+ " the paths you name; both sign with the key you enrolled in §2.4 and write the trailer block."),
				"the helpers");
		assertTrue(doc.contains("The trailers come from the session; a block written by hand is still accepted."),
				"where the trailers come from");
		assertTrue(doc.contains("Give `--co-author '<name> <email>'`, `--signing-key` and `--signing-format` once: they are"
				+ " kept per agent. `--no-co-author` writes the two ReARM trailers alone for one commit."), "the kept flags");
		assertFalse(doc.contains("Sign the commit with the key you enrolled in §2.4."), "the sign-it-yourself sentence is replaced");
		assertTrue(doc.contains("Every commit you author MUST carry two trailers"), "the block's definition stays");
	}

	@Test
	void theHelpersRunTheSelfCheck() throws IOException {
		String doc = doc();
		String lead = "`git commit` and `git merge` run this check on the commit they write; run it yourself on a commit you"
				+ " wrote by hand:";
		assertTrue(doc.contains(lead), "the self-check names the helpers");
		assertTrue(doc.indexOf(lead) > doc.indexOf("#### Self-check before every push")
				&& doc.indexOf(lead) < doc.indexOf("git log -1 --format=%B | git interpret-trailers --parse"),
				"it leads the self-check, before the command");
	}
}
