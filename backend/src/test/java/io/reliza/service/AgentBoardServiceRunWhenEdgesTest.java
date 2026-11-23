/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AgentTaskRoleConfigData;
import io.reliza.model.AgentTaskRoleConfigData.RunWhen;

/**
 * TPF-155 mutation round: the limit of keys in a role's condition (twenty, as for a task's tags), and the routing-rules
 * line that names conditional roles, which names an active role only.
 */
public class AgentBoardServiceRunWhenEdgesTest {

	private static List<String> keys(int n) {
		return IntStream.range(0, n).mapToObj(i -> "key" + i).toList();
	}

	@Test
	public void twentyKeysAreAcceptedAndTwentyOneAreNot() throws RelizaException {
		RunWhen twenty = AgentBoardService.checkedRunWhen(new RunWhen(keys(AgentTaskService.MAX_TAGS), List.of()));
		assertEquals(AgentTaskService.MAX_TAGS, twenty.taggedAny().size());
		RelizaException e = assertThrows(RelizaException.class,
				() -> AgentBoardService.checkedRunWhen(new RunWhen(List.of(), keys(AgentTaskService.MAX_TAGS + 1))));
		assertTrue(e.getMessage().contains("at most " + AgentTaskService.MAX_TAGS), e.getMessage());
	}

	@Test
	public void aKeyWithAGarbageSuffixIsRefused() {
		assertThrows(RelizaException.class,
				() -> AgentBoardService.checkedRunWhen(new RunWhen(List.of("mutation!"), List.of())));
		assertThrows(RelizaException.class,
				() -> AgentBoardService.checkedRunWhen(new RunWhen(List.of("a b"), List.of())));
	}

	private static AgentTaskRoleConfigData role(String name, boolean active, RunWhen runWhen) {
		AgentTaskRoleConfigData rc = new AgentTaskRoleConfigData();
		rc.setName(name);
		rc.setActive(active);
		rc.setRunWhen(runWhen);
		return rc;
	}

	@Test
	public void theRoutingRulesLineNamesActiveConditionalRolesOnly() {
		List<AgentTaskRoleConfigData> roles = new ArrayList<>();
		roles.add(role("plain", true, null));
		roles.add(role("retired", false, new RunWhen(List.of("old"), List.of())));
		assertNull(AgentBoardService.conditionLine(roles), "an inactive role with a condition is not named");

		roles.add(role("mutant", true, new RunWhen(List.of("mutation"), List.of())));
		String line = AgentBoardService.conditionLine(roles);
		assertNotNull(line);
		assertTrue(line.contains("mutant runs only on a task tagged mutation"), line);
		assertFalse(line.contains("retired"), line);
		assertFalse(line.contains("plain"), line);
	}

	@Test
	public void aConditionIsDescribedInWords() {
		assertEquals("tagged a or b", AgentBoardService.describe(new RunWhen(List.of("a", "b"), List.of())));
		assertEquals("not tagged c or d", AgentBoardService.describe(new RunWhen(List.of(), List.of("c", "d"))));
		assertEquals("tagged a and not tagged c", AgentBoardService.describe(new RunWhen(List.of("a"), List.of("c"))));
	}
}
