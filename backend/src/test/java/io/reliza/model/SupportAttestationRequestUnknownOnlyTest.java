/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** FDAW-14 row 2 (r3, F-7; r5, F-11): isUnknownOnly over each field in turn. */
class SupportAttestationRequestUnknownOnlyTest {

	private static final JustifiedUnknownInput U = new JustifiedUnknownInput(JustifiedUnknownReason.COMMUNITY_PROJECT, null);
	private static final LocalDate D = LocalDate.of(2030, 1, 1);

	private static SupportAttestationRequest r(LevelOfSupport l, SupportState s, SupportParty p, String j,
			LocalDate eogs, LocalDate eos, LocalDate eol, String notes, Set<SupportMilestoneType> clear,
			JustifiedUnknownInput u, boolean clearU) {
		return new SupportAttestationRequest(l, s, p, j, ZonedDateTime.now(), eogs, eos, eol, notes, clear, "reason", u,
				clearU);
	}

	@Test
	void onlyTheUnknownOrItsClearWithNoOtherFactIsUnknownOnly() {
		assertTrue(r(null, null, null, null, null, null, null, null, Set.of(), U, false).isUnknownOnly(),
				"assessedAt and reason do not count as facts");
		assertTrue(r(null, null, null, null, null, null, null, null, Set.of(), null, true).isUnknownOnly());
		assertFalse(r(null, null, null, null, null, null, null, null, Set.of(), null, false).isUnknownOnly());
		assertFalse(r(LevelOfSupport.ABANDONED, null, null, null, null, null, null, null, Set.of(), U, false).isUnknownOnly());
		assertFalse(r(null, SupportState.ATTESTED, null, null, null, null, null, null, Set.of(), U, false).isUnknownOnly());
		assertFalse(r(null, null, SupportParty.THIRD_PARTY, null, null, null, null, null, Set.of(), U, false).isUnknownOnly());
		assertFalse(r(null, null, null, "j", null, null, null, null, Set.of(), U, false).isUnknownOnly());
		assertFalse(r(null, null, null, "", null, null, null, null, Set.of(), U, false).isUnknownOnly(),
				"an explicit clear of the basis is a fact");
		assertFalse(r(null, null, null, null, D, null, null, null, Set.of(), U, false).isUnknownOnly());
		assertFalse(r(null, null, null, null, null, D, null, null, Set.of(), U, false).isUnknownOnly());
		assertFalse(r(null, null, null, null, null, null, D, null, Set.of(), U, false).isUnknownOnly());
		assertFalse(r(null, null, null, null, null, null, null, "n", Set.of(), U, false).isUnknownOnly());
		assertFalse(r(null, null, null, null, null, null, null, "", Set.of(), U, false).isUnknownOnly());
		assertFalse(r(null, null, null, null, null, null, null, null, Set.of(SupportMilestoneType.END_OF_SUPPORT), U,
				false).isUnknownOnly());
	}
}
