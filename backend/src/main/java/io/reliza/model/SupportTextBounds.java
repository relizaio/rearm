/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.List;
import java.util.regex.Pattern;

import io.reliza.common.UrlCredentials;

/**
 * The bounds on every free text, key and selector of a support or justification write (FDAW-14 3.2
 * item 1, 3.8 step 1, F-9, F-11): strip, at most the field's maximum measured by
 * {@code String.length()} on the stripped value, and no code point that {@code Character.isISOControl}
 * (C0, DEL, C1) or whose type is {@code Character.FORMAT} (bidirectional controls, zero-width characters,
 * the soft hyphen, the byte order mark, the tag characters), checked by code point; TAB, LF and CR are
 * allowed only where the text is multi-line. The rule began as {@code SubmissionService.normalizedReason}'s
 * (length and nothing below U+0020) and is wider since FDAW-49 (F-19 of FDAW-15), while
 * {@code normalizedReason} keeps its own. The two zero-width joiners U+200C and U+200D are FORMAT and are
 * refused like the rest, so a word whose spelling needs one (Persian, Urdu, Kurdish, Indic conjuncts) or
 * an emoji joiner sequence is refused too. Checked twice on purpose: in the fetcher as the first step of
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
	/** A third-party supplier of an overlay (FDAW-15 3.2). */
	public static final int MAX_SUPPLIER_LENGTH = 512;
	/** An overlay's licence; equal to {@code SpdxLicenseExpressions.MAX_LENGTH}, which a test pins. */
	public static final int MAX_LICENCE_LENGTH = 512;
	/** Where vendored code came from. */
	public static final int MAX_ORIGIN_LENGTH = 512;
	public static final int MAX_OVERLAY_DESCRIPTION_LENGTH = 2048;
	/** The name of a keyless component node. */
	public static final int MAX_NODE_NAME_LENGTH = 512;
	/** The version of a keyless component node. */
	public static final int MAX_NODE_VERSION_LENGTH = 512;

	static final String UNKNOWN_TEXT_REFUSED = "justified unknown: the text is longer than "
			+ MAX_UNKNOWN_TEXT_LENGTH + " characters or holds a control character";
	static final String KEY_REFUSED = "component key is longer than " + MAX_COMPONENT_KEY_LENGTH
			+ " characters or holds a control character";
	static final String KEYS_REQUIRED = "keys: at least one component key is required";
	static final String SUPPLIER_CONTENT_REFUSED = "supplier holds an e-mail address or a URL credential";
	static final String SUPPLIER_REQUIRED = "supplier is required";
	static final String NAME_REQUIRED = "name is required";
	static final String VERSION_BLANK = "version must not be blank";

	/**
	 * JavaScript's {@code \s}, which rebom's two patterns use: in {@code java.util.regex} a bare
	 * {@code \s} is ASCII-only, so {@code Jane} U+00A0 {@code jane@example.org} would pass the port
	 * although rebom strips it (FDAW-15 F-22).
	 */
	private static final String JS_WHITESPACE =
			"\\t\\n\\u000B\\f\\r\\u0020\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF";
	/** rebom's {@code ADDRESS_PATTERN} (bomProcessingService.ts): a {@code name@version} token, a scoped path and the @ of a URL are not addresses. */
	private static final Pattern ADDRESS_PATTERN = Pattern.compile("(?:^|(?<=[" + JS_WHITESPACE + "<(\\[\"',;:]))"
			+ "[A-Za-z0-9._%+-]+@(?:[A-Za-z0-9-]+\\.)+[A-Za-z]{2,}(?=\\z|[" + JS_WHITESPACE + ">)\\]\"',;.])");

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

	/**
	 * A one-line value (FDAW-15 3.2, FDAW-50 F-2): null stays null; stripped, a blank stays {@code ""};
	 * refused over {@code max} or holding a control character, TAB, LF and CR included.
	 */
	public static String boundedLine(String value, String field, int max) {
		if (null == value) return null;
		String s = value.strip();
		if (s.length() > max) {
			throw new IllegalArgumentException(field + " is longer than " + max + " characters");
		}
		if (holdsControlCharacter(s, false)) {
			throw new IllegalArgumentException(field + " holds a control character");
		}
		return s;
	}

	/**
	 * The overlay texts of one write (FDAW-15 3.2): every field is clearable, so null stays null and a
	 * blank stays {@code ""}; {@code supplier}, {@code licence} and {@code origin} are one line and
	 * {@code description} keeps the text rule; a non-blank {@code supplier} also passes the content rule.
	 */
	public static SbomComponentOverlayPatch boundedOverlay(SbomComponentOverlayPatch patch) {
		return new SbomComponentOverlayPatch(boundedSupplier(patch.supplier()),
				boundedLine(patch.licence(), "licence", MAX_LICENCE_LENGTH),
				boundedKeepingBlank(patch.description(), "description", MAX_OVERLAY_DESCRIPTION_LENGTH),
				boundedLine(patch.origin(), "origin", MAX_ORIGIN_LENGTH));
	}

	/** The supplier of a sweep, which has nothing to clear: {@link #boundedOverlay}'s rule, and required. */
	public static String boundedRequiredSupplier(String supplier) {
		String s = boundedSupplier(supplier);
		if (null == s || s.isEmpty()) throw new IllegalArgumentException(SUPPLIER_REQUIRED);
		return s;
	}

	/**
	 * The one-line bound, then the content rule (F-16): rebom's strip runs once at merge time and never
	 * over what the overlay injector writes later, so a supplier it would have edited is refused here,
	 * and the stored, audited and exported strings stay one. The value is not echoed.
	 */
	private static String boundedSupplier(String supplier) {
		String s = boundedLine(supplier, "supplier", MAX_SUPPLIER_LENGTH);
		if (null != s && !s.isEmpty() && (holdsAddressToken(s) || holdsUrlCredential(s))) {
			throw new IllegalArgumentException(SUPPLIER_CONTENT_REFUSED);
		}
		return s;
	}

	/** The name of a vendored verb: one line, required, non-blank after strip. */
	public static String boundedRequiredName(String name) {
		String s = boundedLine(name, "name", MAX_NODE_NAME_LENGTH);
		if (null == s || s.isEmpty()) throw new IllegalArgumentException(NAME_REQUIRED);
		return s;
	}

	/** The version of a vendored verb: one line; null is a node without one; never blank. */
	public static String boundedNodeVersion(String version) {
		String s = boundedLine(version, "version", MAX_NODE_VERSION_LENGTH);
		if (null != s && s.isEmpty()) throw new IllegalArgumentException(VERSION_BLANK);
		return s;
	}

	/** True when {@code s} holds what rebom's {@code ADDRESS_PATTERN} calls an e-mail address token. */
	public static boolean holdsAddressToken(String s) {
		return ADDRESS_PATTERN.matcher(s).find();
	}

	/**
	 * True when a URL token of {@code s} carries what rebom's {@code credentialUserinfo} calls a
	 * credential: the userinfo before the authority's last {@code @} holds {@code :} or a raw {@code @},
	 * or the scheme is an HTTP one. The one Java port of the rule is {@link UrlCredentials} (FDAW-34 3.2):
	 * a token carries one exactly when {@link UrlCredentials#stripUserinfo} would edit it.
	 */
	public static boolean holdsUrlCredential(String s) {
		return UrlCredentials.stripUserinfo(s) != s;
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
	 * {@link #MAX_COMPONENT_KEY_LENGTH} characters and with no control or FORMAT code point (no TAB, LF
	 * or CR either: a key is one line). The offending key is not echoed: it may be 2048 characters long.
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

	/**
	 * True when {@code s} holds a code point that {@code Character.isISOControl} (U+0000 to U+001F, U+007F
	 * to U+009F) or whose type is {@code Character.FORMAT}; TAB, LF and CR pass only when
	 * {@code allowWhitespaceControls}. Iterated by code point, so a FORMAT character outside the BMP (the
	 * tag characters) is classified rather than seen as two surrogates.
	 */
	static boolean holdsControlCharacter(String s, boolean allowWhitespaceControls) {
		return s.codePoints().anyMatch(cp -> Character.isISOControl(cp)
				? !(allowWhitespaceControls && (cp == '\t' || cp == '\n' || cp == '\r'))
				: Character.FORMAT == Character.getType(cp));
	}
}
