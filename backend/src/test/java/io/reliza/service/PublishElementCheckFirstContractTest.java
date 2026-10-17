/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.RoleTemplates.template;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Task RD4-6: every worker template that publishes a document says to run {@code doc publish --check} first, which
 * prints the element checks the board would run and publishes nothing. A rule several templates share, so a class of
 * its own (the RD4-15 placement rule).
 */
class PublishElementCheckFirstContractTest {

	@Test
	void everyWorkerRunsTheElementCheckBeforePublishing() throws IOException {
		for (String name : new String[] {"coder-fix.md", "reviewer.md", "tester.md"}) {
			String t = template(name);
			assertTrue(t.contains("Run the same `rearm agent doc publish` with `--check` first: it prints the element"
					+ " checks the board would run on the document, in the task's current scope, and publishes nothing."),
					name + " should say to run doc publish --check first");
			assertTrue(t.contains("Fix a failure it reports before you publish."), name + " should say to fix what it reports");
		}
	}
}
