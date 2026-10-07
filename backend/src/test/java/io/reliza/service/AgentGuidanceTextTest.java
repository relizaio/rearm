/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * The rule that keeps published documents fetchable (task 2085c601): a release pins the commit it
 * was published from, so the documents repository's pushed history is never rewritten. Pinned in
 * the served orientation and in every reference role template that publishes, so a later edit
 * cannot drop it quietly. Plain JUnit: it reads text.
 */
public class AgentGuidanceTextTest {

	private static String orientation() throws IOException {
		return io.reliza.ws.AgentOrientation.full();
	}

	private static String section(String doc, String heading) {
		int start = doc.indexOf(heading);
		assertTrue(start >= 0, heading + " is in the orientation");
		int end = doc.indexOf("\n### ", start + 1);
		return doc.substring(start, end < 0 ? doc.length() : end);
	}

	@Test
	public void theOrientationSaysNeverRewriteWhatYouPublished() throws IOException {
		String publishing = section(orientation(), "### 2.5a");
		for (String s : List.of("**Never rewrite what you published.**", "never force-push",
				"`git pull --no-rebase`", "publishing the next round", "push first")) {
			assertTrue(publishing.contains(s), "§2.5a says: " + s);
		}
		String trailers = section(orientation(), "### 2.7");
		assertTrue(trailers.contains("`git commit --amend`") && trailers.contains("only if you have\nnot pushed"),
				"§2.7 keeps --amend for the unpushed case only");
	}

	@Test
	public void everyPublishingRoleTemplateCarriesTheRule() throws IOException {
		for (String role : List.of("reviewer", "tester", "coder-fix", "coordinator-tracker", "coordinator-board-truth")) {
			String text = RoleTemplates.raw(role + ".md")
					.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
			assertTrue(text.contains("push before you publish; never force-push, amend or rebase a pushed commit here;"
					+ " on a rejected push, pull with a merge and push again."), role + ".md carries the rule");
		}
	}
}
