/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentTaskRoleConfigData.AgentCapability;

/**
 * AgentBoardInput.coordinatorCapabilities is a partial patch: null leaves the board's list
 * unchanged, [] clears it. The integration tests call the service directly, so this pins the
 * input-to-patch step the create and update mutations share. Plain JUnit: a map conversion
 * needs no Spring context.
 */
public class CoordinatorCapabilitiesPatchTest {

	@Test
	public void anAbsentKeyLeavesItUnchanged() {
		assertNull(AgentTaskDataFetcher.coordinatorCapabilitiesPatch(Map.of("description", "d")));
	}

	@Test
	public void anExplicitNullLeavesItUnchanged() {
		Map<String, Object> input = new HashMap<>();
		input.put("coordinatorCapabilities", null);
		assertNull(AgentTaskDataFetcher.coordinatorCapabilitiesPatch(input));
	}

	@Test
	public void anEmptyListClearsIt() {
		assertEquals(List.of(), AgentTaskDataFetcher.coordinatorCapabilitiesPatch(
				Map.of("coordinatorCapabilities", List.of())));
	}

	@Test
	public void aListReplacesIt() {
		assertEquals(List.of(AgentCapability.PR_MERGE, AgentCapability.CODE_PUSH),
				AgentTaskDataFetcher.coordinatorCapabilitiesPatch(
						Map.of("coordinatorCapabilities", List.of("PR_MERGE", "CODE_PUSH"))));
	}
}
