/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.RoleTemplates.template;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

/**
 * Task RD4-19: a PR linked to a task the coordinator seat parked for the operator is accepted, recorded on the decision
 * and posted as an INFO, and does not answer the question. RD4-17 round 2 wrote "every task verb a session runs on the
 * parked task is refused" into both coordinator templates, the orientation's {@code asking} section and the
 * programmatic schema's docs; each now carries the exception in place. The rule spans templates, a section and the
 * schema, so it has a class of its own (the RD4-15 placement rule).
 */
class ParkedTaskLinkContractTest {

	private static final String EXCEPTION = "except `task linkpr`, which is accepted, recorded on the decision and posted"
			+ " as an INFO; it does not answer the question.";

	private static String folded(Path p) throws IOException {
		return Files.readString(p, StandardCharsets.UTF_8).replaceAll("[ \\t]*\\n[ \\t]*", " ");
	}

	@Test
	void bothCoordinatorTemplatesExceptTheLinkFromWhatIsRefused() throws IOException {
		for (String name : new String[] {"coordinator-board-truth.md", "coordinator-tracker.md"}) {
			String doc = template(name);
			assertTrue(doc.contains("`requirereview` and the rest) is refused, " + EXCEPTION), name
					+ " should except the link in the sentence that lists what is refused");
			assertTrue(doc.contains("A person who will supersede a PR links its replacement first, so a link is"
					+ " preparation for the decision, never the decision."), name + " should say why the link is accepted");
			assertFalse(doc.contains("is refused, and the delivery sweep leaves it alone"), name + " still overstates");
		}
	}

	@Test
	void theAskingSectionExceptsTheLink() throws IOException {
		String doc = folded(Path.of("src/main/resources/static/agents/orientation/asking.md"));
		assertTrue(doc.contains("Until then every task verb a session runs on it is refused, " + EXCEPTION), doc);
	}

	@Test
	void theSchemaDocsExceptTheLinkOnBothVerbs() throws IOException {
		String schema = folded(Path.of("src/main/resources/schema/programmatic.graphqls"));
		assertTrue(schema.contains("Until then every task verb is refused for every session, except"
				+ " agentTaskLinkPrProgrammatic (`task linkpr`), which is accepted, recorded on the decision (hold.linked)"
				+ " and posted as an INFO; it does not answer the question (task RD4-19)."),
				"agentTaskHoldProgrammatic's doc should except the link");
		assertTrue(schema.contains("On a task the coordinator seat parked for the operator it is accepted too, recorded on"
				+ " the decision (hold.linked: the PR, the key's agent, the time) and posted as an INFO; it does not answer"
				+ " the question or lift the hold (task RD4-19). \"\"\" agentTaskLinkPrProgrammatic("),
				"agentTaskLinkPrProgrammatic's doc should say the same from its side");
		String types = folded(Path.of("src/main/resources/schema/schema.graphqls"));
		assertTrue(types.contains("linked: [AgentTaskHoldLink] }"), "AgentTaskHold.linked is served");
	}
}
