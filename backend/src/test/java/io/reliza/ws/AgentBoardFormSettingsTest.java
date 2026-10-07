/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;

/**
 * The form inputs' presence rules (task 40f270be): what is left out stays, what is sent as null is
 * cleared, and nothing negative gets through.
 */
class AgentBoardFormSettingsTest {

	@Test
	void settingsKeepTheirNulls() {
		Map<Object, Object> in = new LinkedHashMap<>();
		in.put("budgetMicros", 5_000_000L);
		in.put("cycleCap", null);
		Map<String, Object> out = AgentTaskDataFetcher.stringKeyed(in);
		assertEquals(5_000_000L, out.get("budgetMicros"));
		assertTrue(out.containsKey("cycleCap"), "a null is a clear, so it must survive");
		assertFalse(out.containsKey("softAlertPercent"));
	}

	@Test
	void aRolesAllowanceIsLeftSetOrCleared() throws RelizaException {
		Map<String, Object> left = new HashMap<>(Map.of("name", "coder"));
		assertFalse(AgentTaskDataFetcher.clearsHopBudget(left));
		assertNull(AgentTaskDataFetcher.hopBudgetFromInput(left));

		Map<String, Object> set = new HashMap<>(Map.of("name", "coder", "hopBudgetMicros", 1_500_000));
		assertEquals(1_500_000L, AgentTaskDataFetcher.hopBudgetFromInput(set), "an Int from GraphQL is fine");
		assertFalse(AgentTaskDataFetcher.clearsHopBudget(set));

		Map<String, Object> cleared = new HashMap<>();
		cleared.put("name", "coder");
		cleared.put("hopBudgetMicros", null);
		assertTrue(AgentTaskDataFetcher.clearsHopBudget(cleared));
		assertNull(AgentTaskDataFetcher.hopBudgetFromInput(cleared));

		assertThrows(RelizaException.class,
				() -> AgentTaskDataFetcher.hopBudgetFromInput(Map.of("hopBudgetMicros", -1L)));
	}
}
