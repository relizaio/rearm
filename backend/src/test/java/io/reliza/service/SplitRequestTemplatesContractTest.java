/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.RoleTemplates.template;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * The split request in the worker templates (task SPLIT-9, design section 3.9), one rule across three templates and so its
 * own class: a task too big for the role's pass, or whose design recommends a split not yet made, goes back to the
 * coordinator with {@code --reason SPLIT}, and the sentence names what a CLI without {@code --children-json} on
 * {@code return} does. The same words in each, so a board's roles are told one thing. The coordinator's side is in
 * {@link CoordinatorTemplatesContractTest}.
 */
class SplitRequestTemplatesContractTest {

	private static final String[] WORKERS = {"coder-fix.md", "reviewer.md", "tester.md"};

	@Test
	void everyWorkerTemplateSaysHowToAskForASplit() throws IOException {
		String sentence = "A task too big for your pass, or whose design recommends a split not yet made, is returned with"
				+ " `--reason SPLIT`, one line why and, when you can, `--children-json` with the children you propose (a CLI"
				+ " without that flag on `return` sends the request without a proposal); the coordinator splits it or sends"
				+ " it back with a note you read in `task brief`.";
		for (String name : WORKERS) {
			String doc = template(name);
			assertTrue(doc.contains("or return the hop. " + sentence), name + ": the sentence after \"or return the hop\"");
		}
	}
}
