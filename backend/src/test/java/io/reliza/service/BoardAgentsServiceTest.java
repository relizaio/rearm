/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.AgentSessionData.UsageTotals;

/** The Agents tab's cache share (task RD3-5): cache read over input plus cache read plus cache write. */
public class BoardAgentsServiceTest {

	private static UsageTotals tokens(long input, long output, long cacheRead, long cacheWrite) {
		return new UsageTotals(input, output, cacheRead, cacheWrite, 1, 1, 0, 0, 1, List.of(), null, List.of(), true);
	}

	@Test
	public void theCacheShare() {
		assertEquals(0.6, BoardAgentsService.cacheShare(tokens(100, 999, 300, 100)), 1e-9, "output does not count");
		assertEquals(0.0, BoardAgentsService.cacheShare(tokens(100, 0, 0, 50)), 1e-9);
		assertEquals(1.0, BoardAgentsService.cacheShare(tokens(0, 0, 40, 0)), 1e-9);
		assertNull(BoardAgentsService.cacheShare(tokens(0, 500, 0, 0)), "nothing in, nothing to share");
		assertNull(BoardAgentsService.cacheShare(null));
	}
}
