/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;

/**
 * Key notes are normalised and capped on every write (task t20261013-072815-12524): control, format,
 * line and paragraph separator characters become spaces (the zero-width joiners stay), whitespace runs
 * collapse, the ends are stripped, and the text is cut to 512 code points without splitting a pair.
 */
class ApiKeyNotesTest {

	private static final String GRINNING_FACE = "\uD83D\uDE00";

	private static int codePoints(String s) {
		return s.codePointCount(0, s.length());
	}

	private static boolean hasLoneSurrogate(String s) {
		return s.codePoints().anyMatch(cp -> Character.getType(cp) == Character.SURROGATE);
	}

	@Test
	void nullAndBlankGiveNull() {
		assertNull(ApiKeyService.normalizeNotes(null));
		assertNull(ApiKeyService.normalizeNotes(""));
		assertNull(ApiKeyService.normalizeNotes("   \t\n"));
		assertNull(ApiKeyService.normalizeNotes("\u200B\uFEFF"));
	}

	@Test
	void controlCharactersBecomeSpaces() {
		assertEquals("a b c d", ApiKeyService.normalizeNotes("a\u0000b\tc\nd"));
	}

	@Test
	void bidiAndZeroWidthCharactersBecomeSpaces() {
		assertEquals("deploy abc x y", ApiKeyService.normalizeNotes("deploy\u202Eabc\u200Bx\uFEFFy"));
	}

	@Test
	void zeroWidthJoinersStay() {
		String persian = "\u0645\u06CC\u200C\u062E";
		assertEquals(persian, ApiKeyService.normalizeNotes(persian));
		String family = "\uD83D\uDC69\u200D\uD83D\uDC67";
		assertEquals(family, ApiKeyService.normalizeNotes(family));
	}

	@Test
	void noBreakSpaceAndLineSeparatorCollapseLikeSpaces() {
		assertEquals("a b c", ApiKeyService.normalizeNotes("a\u00A0\u00A0b\u2028c"));
		assertEquals("a b", ApiKeyService.normalizeNotes("a  \u2029 b"));
	}

	@Test
	void runsCollapseAndEndsAreStripped() {
		assertEquals("ci key", ApiKeyService.normalizeNotes("  ci    key  "));
	}

	@Test
	void lettersOutsideAsciiStay() {
		String munich = "m\u00FCnchen";
		assertEquals(munich, ApiKeyService.normalizeNotes(munich));
	}

	@Test
	void longNoteCutsTo512() {
		String cut = ApiKeyService.normalizeNotes("a".repeat(600));
		assertEquals(ApiKeyService.MAX_NOTES_LENGTH, cut.length());
	}

	@Test
	void emojiAtTheCapIsKeptWhole() {
		String note = "a".repeat(511) + GRINNING_FACE;
		String kept = ApiKeyService.normalizeNotes(note);
		assertEquals(note, kept);
		assertEquals(512, codePoints(kept));
		assertFalse(hasLoneSurrogate(kept));
	}

	@Test
	void emojiPastTheCapIsDroppedWhole() {
		String cut = ApiKeyService.normalizeNotes("a".repeat(512) + GRINNING_FACE);
		assertEquals("a".repeat(512), cut);
		assertFalse(hasLoneSurrogate(cut));
	}

	@Test
	void loneSurrogateBecomesSpace() {
		assertEquals("a b", ApiKeyService.normalizeNotes("a\uD83Db"));
	}

	@Test
	void notesProblemAtTheCap() {
		assertNull(ApiKeyService.notesProblem(null));
		assertNull(ApiKeyService.notesProblem("a".repeat(512)));
		assertNull(ApiKeyService.notesProblem("a".repeat(511) + GRINNING_FACE));
		assertEquals("notes are at most 512 characters", ApiKeyService.notesProblem("a".repeat(513)));
		// counted after cleaning: trailing whitespace does not push a note over
		assertNull(ApiKeyService.notesProblem("a".repeat(512) + "     "));
	}

	@Test
	void checkedNotesRefusesAndNormalises() throws RelizaException {
		assertThrows(RelizaException.class, () -> ApiKeyService.checkedNotes("a".repeat(513)));
		assertEquals("ci key", ApiKeyService.checkedNotes("ci\u0007 key\n"));
		assertNull(ApiKeyService.checkedNotes(" "));
	}

	@Test
	void joinersNeverDangleAtTheEnds() {
		assertNull(ApiKeyService.normalizeNotes("\u200D\u200C \u200D"));
		assertEquals("ci", ApiKeyService.normalizeNotes("\u200Dci\u200C"));
		// a cut through a ZWJ sequence drops the joiner it would leave last
		String cut = ApiKeyService.normalizeNotes("a".repeat(511) + "\uD83D\uDC69\u200D\uD83D\uDC67");
		assertEquals("a".repeat(511) + "\uD83D\uDC69", cut);
	}

	@Test
	void denialReasonHasItsOwnLimit() throws RelizaException {
		assertEquals("too broad", ApiKeyService.checkedDenialReason(" too\nbroad "));
		assertNull(ApiKeyService.checkedDenialReason(null));
		assertEquals(256, ApiKeyService.checkedDenialReason("x".repeat(256)).length());
		RelizaException e = assertThrows(RelizaException.class, () -> ApiKeyService.checkedDenialReason("x".repeat(257)));
		assertEquals("a denial reason is at most 256 characters", e.getMessage());
	}
}
