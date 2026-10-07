/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.RoleTemplates.template;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Registered PRs are consulted (task RD4-2): what the coordinator and coder templates say about the sign-off head
 * check and the moved base. The rule spans the coder's template and both coordinator templates, so it has a class
 * of its own (the RD4-15 placement rule).
 */
class RegisteredPrsTemplatesContractTest {

	private static final String[] COORDINATORS = {"coordinator-board-truth.md", "coordinator-tracker.md"};

	@Test
	void theCoordinatorMergesClientGoFirstThenWhatPinsIt() throws IOException {
		for (String name : COORDINATORS) {
			String doc = template(name);
			int start = doc.indexOf("## When this board declares you cover PR_MERGE");
			assertTrue(start >= 0, name + " has no PR_MERGE section");
			String section = doc.substring(start);
			assertTrue(section.contains("merge rearm-client-go first, then everything that pins it (the CLI, the Terraform"
					+ " provider), then the rest in dependency order"), name + " should give the merge order across repositories");
			assertTrue(section.contains("Merge a sibling before you authorise the next round of a task whose base it moves"),
					name + " should merge a sibling before the next round it moves");
			assertTrue(section.contains("`base moved: N commits since your round`"), name + " should name the base-moved line");
			// Visibility only: ReARM has no git, so the line must not claim to know about conflicts.
			assertTrue(section.contains("It says the base moved, not whether the PR still merges"), name);
		}
	}

	@Test
	void theCoderSignsOffANoteOnlyRoundWithNoCode() throws IOException {
		String coder = template("coder-fix.md");
		assertTrue(coder.contains("(\"" + AgentDeliveryService.NO_PR_MOVED.substring(0, AgentDeliveryService.NO_PR_MOVED.indexOf(';'))
				+ "\")"), "coder-fix.md should quote the refusal the server gives");
		assertTrue(coder.contains("signs off with `--no-code`"), "coder-fix.md should name --no-code");
		assertTrue(coder.contains("`base moved: N commits since your round`: merge the base in before you sign off"),
				"coder-fix.md should say what to do about a moved base");
		assertTrue(AgentDeliveryService.NO_PR_MOVED.contains("sign off with --no-code for a round that changed no code"),
				"the refusal should name the way out");
	}

	@Test
	void theCoderKnowsALinkedStalePrDoesNotCount() throws IOException {
		String coder = template("coder-fix.md");
		assertTrue(coder.contains("A PR you link during the round counts once it moves, or when you opened it after your"
				+ " assignment; linking an older PR that has not moved does not (\"" + AgentDeliveryService.LINKED_PR_PREDATES
				+ "\")."), "coder-fix.md should say which linked PR counts, in the refusal's words (design round 2)");
	}
}
