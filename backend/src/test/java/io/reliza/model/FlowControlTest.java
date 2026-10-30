/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.FlowControl.TeaActKind;
import tools.jackson.databind.JsonNode;

/**
 * The typed view of {@code releases.flow_control} with the TEA act queue keys (task TEA-7,
 * section 4.2 case 18): every key round-trips in the shape the SQL writes, an unused flow
 * contributes no key, and a key this version does not model is tolerated.
 */
class FlowControlTest {

	private static FlowControl teaAct(UUID trigger) {
		return new FlowControl(null, null, null, null, null, null, null, null, null, null,
				"2026-10-08T04:00:00.123456+00:00", TeaActKind.HIDE, trigger, "hide-on-reject",
				"Action 'hide-on-reject' fired by rule 'rejected'", "2026-10-08T04:00:01+00:00",
				"2026-10-08T04:01:01+00:00", 2);
	}

	@Test
	void everyTeaActKeyRoundTrips() throws Exception {
		UUID trigger = UUID.randomUUID();
		FlowControl fc = teaAct(trigger);
		String json = Utils.OM.writeValueAsString(fc);
		JsonNode node = Utils.OM.readTree(json);
		assertEquals("HIDE", node.get("teaActKind").asText(), "the kind as its name, which the SQL compares");
		assertEquals(trigger.toString(), node.get("teaActTrigger").asText(), "the trigger as a uuid string");
		assertEquals(fc, Utils.OM.readValue(json, FlowControl.class));
	}

	@Test
	void theRowTheSqlWritesReads() throws Exception {
		UUID trigger = UUID.randomUUID();
		String row = "{\"teaActRequestedAt\": \"2026-10-08T04:00:00.123456+00:00\", \"teaActKind\": \"PUBLISH\", "
				+ "\"teaActTrigger\": \"" + trigger + "\", \"teaActTriggerName\": \"publish\", \"teaActComment\": null, "
				+ "\"teaActFailureCount\": 3, \"autoIntegrateRequestedAt\": \"2026-10-08T04:00:00+00:00\"}";
		FlowControl fc = Utils.OM.readValue(row, FlowControl.class);
		assertEquals(TeaActKind.PUBLISH, fc.teaActKind());
		assertEquals(trigger, fc.teaActTrigger());
		assertEquals(3, fc.teaActFailureCount());
		assertNull(fc.teaActComment());
		assertEquals("2026-10-08T04:00:00+00:00", fc.autoIntegrateRequestedAt(), "the other flows' keys are untouched");
	}

	@Test
	void anUnusedFlowWritesNoKey() throws Exception {
		FlowControl fc = new FlowControl("2026-10-08T04:00:00+00:00", null, null, null, null, null, null, null, null,
				null, null, null, null, null, null, null, null, null);
		String json = Utils.OM.writeValueAsString(fc);
		assertFalse(json.contains("teaAct"), json);
	}

	@Test
	void anUnknownKeyIsTolerated() throws Exception {
		FlowControl fc = Utils.OM.readValue("{\"teaActKind\": \"HIDE\", \"teaActFuture\": {\"x\": 1}}", FlowControl.class);
		assertEquals(TeaActKind.HIDE, fc.teaActKind());
	}
}
