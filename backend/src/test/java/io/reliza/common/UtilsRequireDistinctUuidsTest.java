/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;

/**
 * TEA-7, round 3, T-5: the uuid rule of the component's and the policy's lists. A repeated uuid
 * is refused naming it; nulls are not counted, because the callers mint them first.
 */
class UtilsRequireDistinctUuidsTest {

	@Test
	void aRepeatedUuidIsRefusedNamingIt() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		RelizaException e = assertThrows(RelizaException.class,
				() -> Utils.requireDistinctUuids(List.of(a, b, a), "Output event", "component"));
		assertEquals("Output event uuid " + a + " appears more than once on this component", e.getMessage());
	}

	@Test
	void distinctUuidsNullsAndNoListPass() {
		assertDoesNotThrow(() -> Utils.requireDistinctUuids(Arrays.asList(UUID.randomUUID(), null, null,
				UUID.randomUUID()), "Rule", "component"));
		assertDoesNotThrow(() -> Utils.requireDistinctUuids(null, "Rule", "component"));
		assertDoesNotThrow(() -> Utils.requireDistinctUuids(List.of(), "Global output event", "policy"));
	}
}
