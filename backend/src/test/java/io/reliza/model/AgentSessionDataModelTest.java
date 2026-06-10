/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;

/**
 * Guards the additive v1a session-model fields against JSONB
 * forward/back-compat regressions: legacy rows written before the fields
 * existed must read with a null model and an honest DECLARED assertion,
 * and a populated row must round-trip cleanly.
 */
class AgentSessionDataModelTest {

	@Test
	void legacyRowReadsNullModelAndDeclaredAssertion() {
		// A row persisted before the model / modelAssertion fields existed.
		Map<String, Object> legacy = new HashMap<>();
		legacy.put("org", UUID.randomUUID().toString());
		legacy.put("clientSessionId", "chat-1");
		legacy.put("status", "OPEN");

		AgentSessionData asd = Utils.OM.convertValue(legacy, AgentSessionData.class);

		assertNull(asd.getModel(), "legacy row must read a null model");
		assertEquals(ModelAssertionState.DECLARED, asd.getModelAssertion(),
				"legacy row must default to the honest DECLARED assertion, not null");
	}

	@Test
	void roundTripsModelThroughJsonb() {
		UUID model = UUID.randomUUID();
		AgentSessionData seed = new AgentSessionData();
		seed.setOrg(UUID.randomUUID());
		seed.setClientSessionId("chat-2");
		seed.setModel(model);

		Map<String, Object> record = Utils.dataToRecord(seed);
		AgentSessionData restored = Utils.OM.convertValue(record, AgentSessionData.class);

		assertEquals(model, restored.getModel());
		assertEquals(ModelAssertionState.DECLARED, restored.getModelAssertion());
	}

	@Test
	void modelAssertionStateValues() {
		assertEquals(2, ModelAssertionState.values().length);
		assertEquals(ModelAssertionState.DECLARED, ModelAssertionState.valueOf("DECLARED"));
		assertEquals(ModelAssertionState.RUNTIME_OBSERVED, ModelAssertionState.valueOf("RUNTIME_OBSERVED"));
	}
}
