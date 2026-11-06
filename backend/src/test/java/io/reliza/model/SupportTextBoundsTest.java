/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/** FDAW-14 row 23 (r4, F-9; r5, F-11): the bounds on every free text of a support write. */
class SupportTextBoundsTest {

	@Test
	void boundedStripsMapsBlankToNullAndRefusesByLengthAndControlCharacter() {
		for (String field : List.of("reason", "justification", "supportNotes")) {
			assertEquals("a", SupportTextBounds.bounded("  a \n", field, 2048));
			assertNull(SupportTextBounds.bounded("   ", field, 2048));
			assertNull(SupportTextBounds.bounded(null, field, 2048));
			assertEquals(2048, SupportTextBounds.bounded("x".repeat(2048), field, 2048).length());
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> SupportTextBounds.bounded("x".repeat(2049), field, 2048));
			assertEquals(field + " is longer than 2048 characters", e.getMessage());
			e = assertThrows(IllegalArgumentException.class, () -> SupportTextBounds.bounded("a\u0007b", field, 2048));
			assertEquals(field + " holds a control character", e.getMessage());
			assertEquals("a\tb\nc\rd", SupportTextBounds.bounded("a\tb\nc\rd", field, 2048));
		}
	}

	@Test
	void boundedKeepingBlankKeepsTheExplicitClear() {
		assertEquals("", SupportTextBounds.boundedKeepingBlank("", "justification", 2048));
		assertEquals("", SupportTextBounds.boundedKeepingBlank("  ", "justification", 2048));
		assertNull(SupportTextBounds.boundedKeepingBlank(null, "justification", 2048));
		assertEquals("a", SupportTextBounds.boundedKeepingBlank(" a ", "supportNotes", 2048));
		assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.boundedKeepingBlank("x".repeat(2049), "supportNotes", 2048));
		assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.boundedKeepingBlank("\u0001", "supportNotes", 2048));
	}

	@Test
	void theUnknownTextAndKeysHaveTheirOwnTexts() {
		assertEquals(512, SupportTextBounds.boundedUnknownText("x".repeat(512)).length());
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.boundedUnknownText("x".repeat(513)));
		assertEquals("justified unknown: the text is longer than 512 characters or holds a control character", e.getMessage());
		assertNull(SupportTextBounds.boundedUnknownText("  "));
		e = assertThrows(IllegalArgumentException.class, () -> SupportTextBounds.boundedKeys(List.of(), 1000, "cap"));
		assertEquals("keys: at least one component key is required", e.getMessage());
		e = assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.boundedKeys(List.of("k".repeat(2049)), 1000, "cap"));
		assertEquals("component key is longer than 2048 characters or holds a control character", e.getMessage());
		e = assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.boundedKeys(List.of("pkg:deb/a\tb@1"), 1000, "cap"));
		assertEquals("component key is longer than 2048 characters or holds a control character", e.getMessage(),
				"a key is one line: no TAB either");
		assertEquals(List.of("k".repeat(2048)), SupportTextBounds.boundedKeys(List.of(" " + "k".repeat(2048)), 1000, "cap"));
		e = assertThrows(IllegalArgumentException.class, () -> SupportTextBounds.boundedKeys(List.of("a", "b"), 1, "over"));
		assertEquals("over", e.getMessage());
	}

	@Test
	void boundedRequestTouchesExactlyTheFourTexts() {
		ZonedDateTime at = ZonedDateTime.now();
		LocalDate d = LocalDate.of(2030, 1, 1);
		SupportAttestationRequest r = new SupportAttestationRequest(LevelOfSupport.ABANDONED, SupportState.ATTESTED,
				SupportParty.THIRD_PARTY, " j ", at, d, null, d, " n ", Set.of(SupportMilestoneType.END_OF_LIFE), " r ",
				new JustifiedUnknownInput(JustifiedUnknownReason.OTHER, " t "), false);
		SupportAttestationRequest b = SupportTextBounds.bounded(r);
		assertEquals("j", b.justification());
		assertEquals("n", b.supportNotes());
		assertEquals("r", b.reason());
		assertEquals("t", b.endOfSupportUnknown().text());
		assertSame(r.levelOfSupport(), b.levelOfSupport());
		assertSame(r.state(), b.state());
		assertSame(r.party(), b.party());
		assertSame(r.assessedAt(), b.assessedAt());
		assertSame(r.endOfGuaranteedSupportDate(), b.endOfGuaranteedSupportDate());
		assertSame(r.endOfLifeDate(), b.endOfLifeDate());
		assertEquals(r.clearMilestones(), b.clearMilestones());
		assertEquals(JustifiedUnknownReason.OTHER, b.endOfSupportUnknown().code());

		SupportAttestationRequest blank = SupportTextBounds.bounded(new SupportAttestationRequest(null, null, null, "  ",
				null, null, null, null, " ", Set.of(), " ", new JustifiedUnknownInput(JustifiedUnknownReason.OTHER, " "), false));
		assertEquals("", blank.justification());
		assertEquals("", blank.supportNotes());
		assertNull(blank.reason());
		assertNull(blank.endOfSupportUnknown().text());

		SupportAttestationRequest absent = SupportTextBounds.bounded(new SupportAttestationRequest(null, null, null, null,
				null, null, null, null, null, Set.of(), null));
		assertNull(absent.justification());
		assertNull(absent.supportNotes());
		assertNull(absent.reason());
		assertNull(absent.endOfSupportUnknown());
	}
}
