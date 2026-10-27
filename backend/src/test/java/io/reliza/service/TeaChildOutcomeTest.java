/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.service.TeaPublicationService.TeaChildOutcome;

/** The cascade's skipped children are an explicit set, not a name prefix: every outcome is decided. */
class TeaChildOutcomeTest {

	private static final Map<TeaChildOutcome, Boolean> SKIPPED = new EnumMap<>(Map.of(
			TeaChildOutcome.PUBLISHED, false,
			TeaChildOutcome.REPUBLISHED, false,
			TeaChildOutcome.UNCHANGED, false,
			TeaChildOutcome.SKIPPED_DIRECT, true,
			TeaChildOutcome.SKIPPED_HIDDEN, true,
			TeaChildOutcome.SKIPPED_OTHER_PRODUCT, true,
			TeaChildOutcome.REFUSED, false));

	@Test
	void everyOutcomeIsClassified() {
		assertEquals(EnumSet.allOf(TeaChildOutcome.class), SKIPPED.keySet(),
				"a new TeaChildOutcome needs a decision here and in isSkipped()");
		for (TeaChildOutcome o : TeaChildOutcome.values()) {
			assertEquals(SKIPPED.get(o), o.isSkipped(), o.name());
		}
	}
}
