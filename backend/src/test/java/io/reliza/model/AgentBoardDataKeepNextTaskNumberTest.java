/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The board save keeps the stored task counter when that is the higher one (TPF-42): a record from a
 * copy older than a draw gets the stored counter, and a record that is current goes through as it is.
 */
class AgentBoardDataKeepNextTaskNumberTest {

	private Map<String, Object> in;

	@BeforeEach
	void record() {
		in = new LinkedHashMap<>(Map.of("nextTaskNumber", 5, "name", "x"));
	}

	@Test
	void aHigherStoredCounterReplacesTheRecordsOnACopy() {
		Map<String, Object> out = AgentBoardData.keepNextTaskNumber(in, Optional.of(7));
		assertEquals(7, out.get("nextTaskNumber"));
		assertEquals("x", out.get("name"));
		assertEquals(5, in.get("nextTaskNumber"), "the caller's record is not changed");
	}

	@Test
	void anEqualStoredCounterKeepsTheRecord() {
		assertSame(in, AgentBoardData.keepNextTaskNumber(in, Optional.of(5)));
	}

	@Test
	void aLowerStoredCounterKeepsTheRecord() {
		assertSame(in, AgentBoardData.keepNextTaskNumber(in, Optional.of(3)));
	}

	@Test
	void aRecordWithoutACounterTakesTheStoredOne() {
		Map<String, Object> out = AgentBoardData.keepNextTaskNumber(new LinkedHashMap<>(Map.of("name", "x")),
				Optional.of(3));
		assertEquals(3, out.get("nextTaskNumber"));
	}

	@Test
	void noStoredCounterKeepsTheRecord() {
		assertSame(in, AgentBoardData.keepNextTaskNumber(in, Optional.empty()));
	}
}
