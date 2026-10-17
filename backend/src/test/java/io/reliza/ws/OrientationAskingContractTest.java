/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static io.reliza.ws.OrientationText.section;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * The orientation's {@code asking} section.
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
class OrientationAskingContractTest {

	/** Task RD4-13: the answering role learns both paths after a review item about its document; in the asking section. */
	@Test
	void anAnswerToAReviewItemAboutYourDocumentGoesToTheBuilderUnlessNoChange() throws IOException {
		String doc = section("asking").replaceAll("[ \\t]*\\n[ \\t]*", " ");
		assertTrue(doc.contains("The board then sends the task to the next role that builds from your round (the coder,"
				+ " after an architecture round) before the filer re-checks, and the review item stays open until the filer does."));
		assertTrue(doc.contains("sign off with `--no-change`: the task goes straight back to the filer."));
	}
}
