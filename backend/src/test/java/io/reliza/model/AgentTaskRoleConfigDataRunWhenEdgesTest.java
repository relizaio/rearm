/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.model.AgentTaskRoleConfigData.RunWhen;

/**
 * TPF-155 mutation round: what the routing rows leave open in the condition itself. A key compares without case and
 * without surrounding space on either side, a tag with no key or no record at all is ignored, and a condition is empty
 * only when it names no key at all.
 */
public class AgentTaskRoleConfigDataRunWhenEdgesTest {

	private static List<TagRecord> tags(String... keys) {
		return Arrays.stream(keys).map(k -> new TagRecord(k, "v")).toList();
	}

	@Test
	public void keysCompareIgnoringCaseAndSpaceOnBothSides() {
		// built directly, so the service's lower-casing and stripping of a stored key did not happen
		assertNull(new RunWhen(List.of("MUTATION"), List.of()).unmet(tags(" Mutation ")));
		assertNull(new RunWhen(List.of(" mutation "), List.of()).unmet(tags("mutation")));
		assertEquals("runWhen not met: tagged Skip-Me", new RunWhen(List.of(), List.of("Skip-Me")).unmet(tags("skip-me")));
		assertEquals("runWhen not met: tagged skip-me", new RunWhen(List.of(), List.of("skip-me")).unmet(tags(" SKIP-ME")));
	}

	@Test
	public void aTagWithNoKeyOrNoRecordIsIgnored() {
		RunWhen needsMutation = new RunWhen(List.of("mutation"), List.of());
		assertNull(needsMutation.unmet(Arrays.asList(null, new TagRecord(null, "v"), new TagRecord("mutation", null))));
		assertEquals("runWhen not met: no mutation tag", needsMutation.unmet(Arrays.asList(null, new TagRecord(null, "v"))));
		assertEquals("runWhen not met: no mutation tag", needsMutation.unmet(null));
		assertNull(new RunWhen(List.of(), List.of("skip")).unmet(null), "a taggedNone condition holds for a task with no tags");
	}

	@Test
	public void aConditionIsEmptyOnlyWhenItNamesNoKey() {
		assertTrue(new RunWhen(null, null).isEmpty());
		assertFalse(new RunWhen(List.of("a"), List.of()).isEmpty());
		assertFalse(new RunWhen(List.of(), List.of("a")).isEmpty());
		assertFalse(new RunWhen(List.of("a"), List.of("b")).isEmpty());
	}
}
