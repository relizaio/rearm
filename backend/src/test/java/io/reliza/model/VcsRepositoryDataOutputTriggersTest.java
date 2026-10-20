/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.ComponentData.EventType;
import io.reliza.model.ComponentData.ReleaseOutputEvent;

/**
 * The VCS repository's one-trigger invariant answers as a refused input, not a server error, and never
 * echoes the type sent (task TPF-63, architecture-1 3.6.2 and section 4 row 5).
 */
class VcsRepositoryDataOutputTriggersTest {

	private static final String AT_MOST_ONE = "triggers: a VCS repository takes at most one trigger";
	private static final String EXTERNAL_ONLY = "triggers[0].type: must be EXTERNAL_VALIDATION on a VCS repository";

	private static ReleaseOutputEvent of(EventType type) {
		return ReleaseOutputEvent.builder().type(type).build();
	}

	@Test
	void noTriggersPass() {
		assertDoesNotThrow(() -> VcsRepositoryData.validateOutputTriggers(null));
		assertDoesNotThrow(() -> VcsRepositoryData.validateOutputTriggers(List.of()));
	}

	@Test
	void oneExternalValidationTriggerPasses() {
		assertDoesNotThrow(() -> VcsRepositoryData.validateOutputTriggers(List.of(of(EventType.EXTERNAL_VALIDATION))));
	}

	@Test
	void twoTriggersAreRefused() {
		RelizaException e = assertThrows(RelizaException.class, () -> VcsRepositoryData.validateOutputTriggers(
				List.of(of(EventType.EXTERNAL_VALIDATION), of(EventType.EXTERNAL_VALIDATION))));
		assertEquals(AT_MOST_ONE, e.getMessage());
	}

	@Test
	void anyOtherTypeIsRefused() {
		RelizaException lock = assertThrows(RelizaException.class,
				() -> VcsRepositoryData.validateOutputTriggers(List.of(of(EventType.LOCK))));
		assertEquals(EXTERNAL_ONLY, lock.getMessage());
		RelizaException none = assertThrows(RelizaException.class,
				() -> VcsRepositoryData.validateOutputTriggers(List.of(of(null))));
		assertEquals(EXTERNAL_ONLY, none.getMessage());
	}
}
