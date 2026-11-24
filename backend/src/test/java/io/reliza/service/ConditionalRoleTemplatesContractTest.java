/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;

import org.junit.jupiter.api.Test;

/**
 * Task TPF-155 (7.4, T-91): the conditional-role paragraph goes to the two coordinator templates only. The role
 * templates and the coordinator's other template files say nothing about runWhen: a worker never decides a skip, and
 * the board's prompts are reseeded by the operator, not copied from these files.
 */
class ConditionalRoleTemplatesContractTest {

	@Test
	void theOtherTemplatesSayNothingAboutConditionalRoles() throws IOException {
		for (String name : new String[] {"coder-fix.md", "reviewer.md", "tester.md", "coordinator-addendum.md",
				"coordinator-board-mechanics.md"}) {
			String raw = RoleTemplates.raw(name);
			assertFalse(raw.contains("runWhen"), name + " names runWhen");
			assertFalse(raw.contains("rolesSkippedByCondition"), name + " names rolesSkippedByCondition");
		}
	}
}
