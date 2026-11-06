/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.List;

/**
 * The bounds on every free text, key and selector of a support or justification write (FDAW-14 3.2
 * item 1, 3.8 step 1, F-9, F-11). The rule is {@code SubmissionService.normalizedReason}'s: strip, at
 * most the field's maximum measured by {@code String.length()} on the stripped value, and no character
 * below U+0020 other than TAB, LF and CR. Checked twice on purpose: in the fetcher as the first step of
 * every verb, before authorisation and before any database access, and again in the service as the
 * invariant of what is stored, so a caller that is not the resolver is bounded too.
 *
 * <p><b>The bound does not normalise presence (F-11).</b> On a support write a null text is "leave it
 * alone" and a supplied blank is the explicit clear: the editor's Clear justification sends
 * {@code justification: ""} and an erased milestone note is sent as {@code supportNotes: ""}. So
 * {@link #boundedKeepingBlank} keeps a blank as {@code ""} for those two fields, and
 * {@code applySupport} then applies its own clear semantics unchanged; the two integration tests
 * {@code changingToAPositiveLevelDoesNotInheritTheOldBasis} and
 * {@code aPartialWriteCannotBlankTheJustificationOffANegativeClaim} pin that. Blank-to-null
 * ({@link #bounded}) applies only to {@code reason} and to a justified unknown's text, which have no
 * clear meaning when blank.
 */
public final class SupportTextBounds {

	/** The value of {@code SubmissionService.MAX_REASON_LENGTH}, which a shared file cannot import; a test pins them equal. */
	public static final int MAX_REASON_LENGTH = 2048;
	public static final int MAX_JUSTIFICATION_LENGTH = 2048;
	public static final int MAX_NOTES_LENGTH = 2048;
	/** A justified unknown's free text. */
	public static final int MAX_UNKNOWN_TEXT_LENGTH = 512;
	/** A component key of a release SBOM (a canonical purl or a cpe). */
	public static final int MAX_COMPONENT_KEY_LENGTH = 2048;
	/** A bulk selector's namespace. */
	public static final int MAX_NAMESPACE_LENGTH = 256;

	static final String UNKNOWN_TEXT_REFUSED = "justified unknown: the text is longer than "
			+ MAX_UNKNOWN_TEXT_LENGTH + " characters or holds a control character";
	static final String KEY_REFUSED = "component key is longer than " + MAX_COMPONENT_KEY_LENGTH
			+ " characters or holds a control character";
	static final String KEYS_REQUIRED = "keys: at least one component key is required";

	private SupportTextBounds() {}

	/** Null stays null; stripped; a blank becomes null; refused over {@code max} or holding a control character. */
	public static String bounded(String value, String field, int max) {
		String s = boundedKeepingBlank(value, field, max);
		return null == s || s.isEmpty() ? null : s;
	}

	/** Null stays null; stripped; a blank stays {@code ""}; refused over {@code max} or holding a control character. */
	public static String boundedKeepingBlank(String value, String field, int max) {
		if (null == value) return null;
		String s = value.strip();
		if (s.length() > max) {
			throw new IllegalArgumentException(field + " is longer than " + max + " characters");
		}
		if (holdsControlCharacter(s, true)) {
			throw new IllegalArgumentException(field + " holds a control character");
		}
		return s;
	}

	/** A justified unknown's text: through {@link #bounded} with 3.11's one combined refusal text. */
	public static String boundedUnknownText(String value) {
		if (null == value) return null;
		String s = value.strip();
		if (s.length() > MAX_UNKNOWN_TEXT_LENGTH || holdsControlCharacter(s, true)) {
			throw new IllegalArgumentException(UNKNOWN_TEXT_REFUSED);
		}
		return s.isEmpty() ? null : s;
	}

	/**
	 * The keys of a hash justification write: non-empty, at most {@code maxKeys}, each stripped, at most
	 * {@link #MAX_COMPONENT_KEY_LENGTH} characters and with no character below U+0020 (no TAB, LF or CR
	 * either: a key is one line). The offending key is not echoed: it may be 2048 characters long.
	 */
	public static List<String> boundedKeys(List<String> keys, int maxKeys, String overCapText) {
		if (null == keys || keys.isEmpty()) throw new IllegalArgumentException(KEYS_REQUIRED);
		if (keys.size() > maxKeys) throw new IllegalArgumentException(overCapText);
		return keys.stream().map(k -> {
			String s = null == k ? "" : k.strip();
			if (s.isEmpty() || s.length() > MAX_COMPONENT_KEY_LENGTH || holdsControlCharacter(s, false)) {
				throw new IllegalArgumentException(KEY_REFUSED);
			}
			return s;
		}).toList();
	}

	/**
	 * The request with {@code reason} and the unknown's {@code text} through {@link #bounded} and
	 * {@code justification} and {@code supportNotes} through {@link #boundedKeepingBlank}; every other
	 * component is the same object.
	 */
	public static SupportAttestationRequest bounded(SupportAttestationRequest r) {
		JustifiedUnknownInput unknown = r.endOfSupportUnknown();
		JustifiedUnknownInput boundedUnknown = null == unknown ? null
				: new JustifiedUnknownInput(unknown.code(), boundedUnknownText(unknown.text()));
		return new SupportAttestationRequest(r.levelOfSupport(), r.state(), r.party(),
				boundedKeepingBlank(r.justification(), "justification", MAX_JUSTIFICATION_LENGTH),
				r.assessedAt(), r.endOfGuaranteedSupportDate(), r.endOfSupportDate(), r.endOfLifeDate(),
				boundedKeepingBlank(r.supportNotes(), "supportNotes", MAX_NOTES_LENGTH),
				r.clearMilestones(), bounded(r.reason(), "reason", MAX_REASON_LENGTH),
				boundedUnknown, r.clearEndOfSupportUnknown());
	}

	private static boolean holdsControlCharacter(String s, boolean allowWhitespaceControls) {
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (c < 0x20 && !(allowWhitespaceControls && (c == '\t' || c == '\n' || c == '\r'))) return true;
		}
		return false;
	}
}
