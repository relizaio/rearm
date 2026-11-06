/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/** FDAW-14 row 12: the accepted codes per field, and the per-component model refuses a hash justification. */
class JustifiedUnknownFieldTest {

	@Test
	void theAcceptedCodesArePinnedPerField() {
		assertEquals(EnumSet.of(JustifiedUnknownReason.NO_COMMITTED_END_OF_SUPPORT, JustifiedUnknownReason.COMMUNITY_PROJECT,
				JustifiedUnknownReason.SUPPLIER_PUBLISHES_NO_DATE, JustifiedUnknownReason.OTHER),
				JustifiedUnknownField.END_OF_SUPPORT.accepted());
		assertEquals(EnumSet.of(JustifiedUnknownReason.DISTRIBUTION_PUBLISHES_NO_HASH,
				JustifiedUnknownReason.VENDORED_SOURCE_NO_HASH, JustifiedUnknownReason.OTHER),
				JustifiedUnknownField.HASH.accepted());
	}

	@Test
	void validateRefusesWithTheExactTexts() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> JustifiedUnknownField.HASH.validate(JustifiedUnknownReason.COMMUNITY_PROJECT, null));
		assertEquals("justified unknown: the code COMMUNITY_PROJECT is not accepted for HASH (accepted: "
				+ "DISTRIBUTION_PUBLISHES_NO_HASH, VENDORED_SOURCE_NO_HASH, OTHER)", e.getMessage());
		e = assertThrows(IllegalArgumentException.class,
				() -> JustifiedUnknownField.END_OF_SUPPORT.validate(JustifiedUnknownReason.OTHER, " "));
		assertEquals("justified unknown: the code OTHER needs a text", e.getMessage());
		assertDoesNotThrow(() -> JustifiedUnknownField.END_OF_SUPPORT.validate(JustifiedUnknownReason.OTHER, "why"));
	}

	@Test
	void supportDataRefusesAHashJustification() {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> new SupportData(null, null, null,
				null, null, null, null, Map.of(), Map.of(JustifiedUnknownField.HASH, new JustifiedUnknown(
						JustifiedUnknownReason.OTHER, "x", null, UUID.randomUUID(), null)), null));
		assertEquals("a hash justification is per release SBOM, not per component", e.getMessage());
	}
}
