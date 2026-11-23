/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;

/**
 * T-43 (task TPF-152): the board's architectCheckpointWaiver switch in JSONB. A board written before it reads null,
 * which resolves to on; false round-trips and resolves to off.
 */
public class AgentBoardDataWaiverSwitchTest {

	@Test
	public void aBoardWrittenBeforeTheSwitchReadsNullAndOn() {
		AgentBoardData bd = Utils.OM.convertValue(Map.of("name", "old"), AgentBoardData.class);
		assertNull(bd.getArchitectCheckpointWaiver());
		assertTrue(bd.getEffectiveArchitectCheckpointWaiver());
	}

	@Test
	public void falseRoundTripsAndResolvesOff() {
		AgentBoardData bd = new AgentBoardData();
		bd.setArchitectCheckpointWaiver(false);
		assertFalse(bd.getEffectiveArchitectCheckpointWaiver());
		Map<?, ?> stored = Utils.OM.convertValue(bd, Map.class);
		assertEquals(Boolean.FALSE, stored.get("architectCheckpointWaiver"));
		assertFalse(stored.containsKey("effectiveArchitectCheckpointWaiver"), "the resolved value is not stored");
		AgentBoardData back = Utils.OM.convertValue(stored, AgentBoardData.class);
		assertEquals(Boolean.FALSE, back.getArchitectCheckpointWaiver());
		assertFalse(back.getEffectiveArchitectCheckpointWaiver());
		back.setArchitectCheckpointWaiver(true);
		assertTrue(back.getEffectiveArchitectCheckpointWaiver());
	}
}
