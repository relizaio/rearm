/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentTaskRoleConfigData.RunWhen;

/**
 * A role's runWhen from the role input (task TPF-155, 4.4, T-90): left out, unchanged; sent as null, cleared; an
 * object, the condition as sent, unchecked here because the service checks it on every path.
 */
class RunWhenInputParsingTest {

	@Test
	void absentLeavesNullClearsAnObjectSets() {
		assertNull(AgentTaskDataFetcher.runWhenFromInput(Map.of("name", "mutant")), "absent: unchanged");
		Map<String, Object> cleared = new HashMap<>();
		cleared.put("runWhen", null);
		assertEquals(Optional.empty(), AgentTaskDataFetcher.runWhenFromInput(cleared), "null: cleared");
		assertEquals(Optional.of(new RunWhen(List.of("Mutation"), List.of())),
				AgentTaskDataFetcher.runWhenFromInput(Map.of("runWhen", Map.of("taggedAny", List.of("Mutation")))),
				"an object: as sent, the missing list empty");
		assertEquals(Optional.of(new RunWhen(List.of(), List.of("hotfix"))),
				AgentTaskDataFetcher.runWhenFromInput(Map.of("runWhen", Map.of("taggedNone", List.of("hotfix")))));
	}
}
