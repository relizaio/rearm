/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

import io.reliza.ws.AgentOrientation;

/**
 * Task RD4-5: a decision reached outside the board is written into the task by whoever received it, and the
 * two verbs that let a worker do so are documented where agents read them. On Dogfood 3 an operator's decision
 * given in a coder's chat lived in a note until an architect round wrote it up. A class of its own rather than
 * more methods at the end of {@link AgentRoleTemplatesContractTest}, where siblings keep meeting at the brace.
 */
class AgentDecisionOnTheBoardContractTest {

	/** The template with line wraps folded to spaces, so a phrase reads the same wherever it breaks. */
	private static String template(String name) throws IOException {
		return RoleTemplates.template(name);
	}

	private static String fold(String s) {
		return s.replaceAll("[ \\t]*\\n[ \\t]*", " ");
	}

	private static String section(String key) throws IOException {
		return fold(AgentOrientation.section(key).orElseThrow(() -> new AssertionError("no orientation section " + key)));
	}

	@Test
	void everyWorkerTemplateSaysADecisionReachedOutsideTheBoardIsWrittenIntoTheTask() throws IOException {
		for (String name : new String[] {"coder-fix.md", "tester.md", "reviewer.md"}) {
			String t = template(name);
			assertTrue(t.contains("## A decision reached outside the board"), name + " should carry the section");
			assertTrue(t.contains("is written into the task by you: a worker files a question round, an architect"
					+ " parks the task for the operator"), name + " should say who writes the decision in, and how");
			assertTrue(t.contains("`rearm agent task hold <key> --session <session> --operator --question '...'`"),
					name + " should give the architect's verb as it runs");
			assertTrue(t.contains("The board carries the record; a note does not."), name + " should say why");
		}
	}

	@Test
	void theOrientationDocumentsParkingAHopAndTheDecisionRule() throws IOException {
		String asking = section("asking");
		assertTrue(asking.contains("rearm agent task hold <task> --session <session-uuid> --operator \\ --question"),
				"asking should give the holder's hold as it runs");
		assertTrue(asking.contains("The task shows \"awaiting the operator: <question>\""), "what the task shows");
		assertTrue(asking.contains("the hop stays yours"), "the hop stays assigned to the holder");
		assertTrue(asking.contains("A person lifts the hold with the answer as its note"), "the lift carries the answer");
		assertTrue(asking.contains("A decision you receive outside the board is written into the task by you."),
				"the generic rule is in the section agents read before asking");
		assertTrue(asking.contains("The board carries the record; a note does not."), "and why");
	}

	@Test
	void theOrientationDocumentsWithdrawingARegistration() throws IOException {
		String taking = section("taking-a-task");
		assertTrue(taking.contains("rearm agent task withdraw <task> --session <session-uuid> --reason"),
				"taking-a-task should give the withdrawal as it runs");
		assertTrue(taking.contains("\"withdrawn by its registrant: <reason>\""), "what the row reads");
		assertTrue(taking.contains("Once the coordinator has authorised it the task is the coordinator's"),
				"when the withdrawal is refused");
	}
}
