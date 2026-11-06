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

	private static final String REASON_REFUSED = "reason holds a control character";

	private static void refusedEverywhere(String value) {
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.bounded(value, "reason", 2048), value);
		assertEquals(REASON_REFUSED, e.getMessage());
		e = assertThrows(IllegalArgumentException.class, () -> SupportTextBounds.boundedUnknownText(value), value);
		assertEquals(SupportTextBounds.UNKNOWN_TEXT_REFUSED, e.getMessage());
		e = assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.boundedKeys(List.of("pkg:deb/" + value + "@1"), 1000, "cap"), value);
		assertEquals(SupportTextBounds.KEY_REFUSED, e.getMessage());
	}

	private static String cp(int codePoint) {
		return new String(Character.toChars(codePoint));
	}

	/** FDAW-49 row 1: DEL is refused through every entry point with the unchanged texts. */
	@Test
	void deleteIsRefusedEverywhere() {
		for (String field : List.of("reason", "justification", "supportNotes")) {
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> SupportTextBounds.bounded("a\u007Fb", field, 2048));
			assertEquals(field + " holds a control character", e.getMessage());
		}
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.boundedKeepingBlank("\u007F", "supportNotes", 2048));
		assertEquals("supportNotes holds a control character", e.getMessage());
		e = assertThrows(IllegalArgumentException.class, () -> SupportTextBounds.boundedUnknownText("a\u007Fb"));
		assertEquals("justified unknown: the text is longer than 512 characters or holds a control character", e.getMessage());
		e = assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.boundedKeys(List.of("pkg:deb/a\u007Fb@1"), 1000, "cap"));
		assertEquals("component key is longer than 2048 characters or holds a control character", e.getMessage());
		assertEquals("a~b", SupportTextBounds.bounded("a~b", "reason", 2048), "U+007E, the last code point before DEL, passes");
	}

	/** FDAW-49 row 2: the C1 edges and NEL are refused, under the multi-line flag too; U+00A0 passes. */
	@Test
	void theC1ControlsAreRefusedEverywhere() {
		refusedEverywhere("a\u0080b");
		refusedEverywhere("a\u009Fb");
		refusedEverywhere("a\u0085b");
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.boundedKeepingBlank("a\u0085b", "justification", 2048));
		assertEquals("justification holds a control character", e.getMessage());
		assertEquals("a\u00A0b", SupportTextBounds.bounded("a\u00A0b", "reason", 2048),
				"the no-break space is neither control nor whitespace to strip");
	}

	/** FDAW-49 row 3: FORMAT characters are refused, by code point, under either flag, both joiners included. */
	@Test
	void formatCharactersAreRefusedEverywhere() {
		for (String v : List.of("a\u202Eb", "a\u200Bb", "a\u200Db", "a\u200Cb", "a\u00ADb", "a\uFEFFb")) {
			refusedEverywhere(v);
			IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
					() -> SupportTextBounds.boundedKeepingBlank(v, "justification", 2048), v);
			assertEquals("justification holds a control character", e.getMessage(), "no FORMAT character under the multi-line flag");
		}
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.bounded("\uFEFFabc", "reason", 2048));
		assertEquals(REASON_REFUSED, e.getMessage(), "strip leaves a leading BOM, so it is refused, not stripped");
		String tag = "a" + cp(0xE0001) + "b";
		assertEquals(4, tag.length(), "U+E0001 is a surrogate pair");
		e = assertThrows(IllegalArgumentException.class, () -> SupportTextBounds.bounded(tag, "reason", 2048));
		assertEquals(REASON_REFUSED, e.getMessage(), "iterated by code point, so the tag character is FORMAT");
		e = assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.bounded("\uD83D\uDC68\u200D\uD83D\uDC69", "reason", 2048));
		assertEquals(REASON_REFUSED, e.getMessage(), "an emoji ZWJ sequence");
		// the script uses of the two joiners (3.4): refused as designed, so a reversal is a test change
		e = assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.bounded("\u0645\u06CC\u200C\u062E", "reason", 2048));
		assertEquals(REASON_REFUSED, e.getMessage(), "U+200C between Arabic-script letters");
		e = assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.bounded("\u0915\u094D\u200D\u0937", "reason", 2048));
		assertEquals(REASON_REFUSED, e.getMessage(), "U+200D inside a Devanagari conjunct");
	}

	/** FDAW-49 row 4: letters of any script, marks, emoji without a joiner, and line structure pass unchanged. */
	@Test
	void legitimateTextStillPasses() {
		assertEquals("Soci\u00E9t\u00E9 G\u00E9n\u00E9rale",
				SupportTextBounds.bounded("  Soci\u00E9t\u00E9 G\u00E9n\u00E9rale \n", "reason", 2048));
		List<String> same = List.of("Z\u00FCrich Stra\u00DFe", "\u682A\u5F0F\u4F1A\u793E\u65E5\u7ACB\u88FD\u4F5C\u6240",
				"\u0395\u03BB\u03BB\u03B7\u03BD\u03B9\u03BA\u03AC \u0391.\u0395.",
				"\u00DCr\u00FCn M\u00FCd\u00FCrl\u00FC\u011F\u00FC", "\u0633\u0644\u0627\u0645",
				cp(0x1F642), cp(0x1F1EB) + cp(0x1F1F7), "e\u0301", "\u2764\uFE0F");
		for (String v : same) {
			assertEquals(v, SupportTextBounds.bounded(v, "reason", 2048), v);
		}
		assertEquals("a\tb\nc\rd", SupportTextBounds.bounded("a\tb\nc\rd", "reason", 2048));
		assertEquals("a\tb\nc\rd", SupportTextBounds.boundedKeepingBlank("a\tb\nc\rd", "supportNotes", 2048));
		IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
				() -> SupportTextBounds.boundedKeys(List.of("pkg:deb/a\tb@1"), 1000, "cap"));
		assertEquals(SupportTextBounds.KEY_REFUSED, e.getMessage(), "a key is still one line");
		assertEquals(List.of("pkg:npm/%40scope/na\u00EFve@1.0"),
				SupportTextBounds.boundedKeys(List.of("pkg:npm/%40scope/na\u00EFve@1.0"), 1000, "cap"));
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
