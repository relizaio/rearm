/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;

import org.erdtman.jcs.JsonCanonicalizer;

import io.reliza.exceptions.SbomSigningException;
import io.reliza.exceptions.SbomSigningException.SbomSigningErrorReason;
import tools.jackson.databind.JsonNode;

/**
 * The RFC 8785 (JCS) canonical form of a JSON document, the signing input of the JSF signature on
 * merged SBOM exports (SCORE-31 ADR-2, SCORE-33 section 3.2).
 *
 * <p>The canonicalisation is the {@code java-json-canonicalization} library's; this class closes its
 * two gaps against the I-JSON rules rearm-cli's verifier enforces (SCORE-34 ADR-10), so that both
 * sides refuse the same documents: the library accepts only an object at the root, and it encodes a
 * lone surrogate as a replacement character where the Go side refuses it. A document the verifier
 * would refuse is therefore never signed. Duplicate member names and non-finite numbers the library
 * refuses itself.
 *
 * <p>Every refusal is a {@link SbomSigningException} with reason
 * {@link SbomSigningErrorReason#NOT_CANONICALIZABLE} and a message that names what was refused and
 * never quotes the document.
 */
public final class JsfCanonicalizer {

	/** The largest integer magnitude every IEEE 754 double reader holds exactly. */
	private static final BigInteger MAX_EXACT_INTEGER = BigInteger.TWO.pow(53);

	private JsfCanonicalizer() {}

	/** The canonical UTF-8 bytes of {@code document}, which must be an object. */
	public static byte[] canonicalize(JsonNode document) {
		if (null == document || !document.isObject()) {
			throw refused("the root is not a JSON object");
		}
		return canonicalizeText(document.toString());
	}

	/** The canonical UTF-8 bytes of the JSON text {@code json}, whose root must be an object. */
	public static byte[] canonicalize(String json) {
		if (null == json) {
			throw refused("there is no document");
		}
		return canonicalizeText(json);
	}

	/**
	 * Refuses a tree holding a number that the canonical form would change: an integer beyond
	 * 2^53 in magnitude, or a decimal that is not exactly an IEEE 754 double (I-JSON, RFC 7493
	 * section 2.2). JCS reads every number as a double on both sides, so such a number is signed as
	 * its rounded value and an edit of its last digits would still verify; ReARM does not sign it.
	 * Doubles are exact by construction, and a non-finite one is written by Jackson as a string.
	 */
	public static void requireExactNumbers(JsonNode node) {
		if (null == node) return;
		if (node.isNumber()) {
			if (node.isIntegralNumber()) {
				if (node.bigIntegerValue().abs().compareTo(MAX_EXACT_INTEGER) > 0) {
					throw refused("an integer is beyond 2^53 and is not exact as a JSON number");
				}
			} else if (node.isBigDecimal()) {
				BigDecimal d = node.decimalValue();
				double asDouble = d.doubleValue();
				if (Double.isInfinite(asDouble) || 0 != new BigDecimal(Double.toString(asDouble)).compareTo(d)) {
					throw refused("a decimal is not exactly an IEEE 754 double");
				}
			}
			return;
		}
		for (JsonNode child : node) {
			requireExactNumbers(child);
		}
	}

	private static byte[] canonicalizeText(String json) {
		requireObjectRoot(json);
		requirePairedSurrogates(json);
		try {
			return new JsonCanonicalizer(json).getEncodedUTF8();
		} catch (IOException | RuntimeException e) {
			throw refused(libraryRefusal(e));
		}
	}

	private static void requireObjectRoot(String json) {
		int i = 0;
		while (i < json.length() && isJsonWhitespace(json.charAt(i))) i++;
		if (i == json.length() || '{' != json.charAt(i)) {
			throw refused("the root is not a JSON object");
		}
	}

	private static boolean isJsonWhitespace(char c) {
		return ' ' == c || '\t' == c || '\n' == c || '\r' == c;
	}

	/**
	 * Refuses a UTF-16 surrogate without its partner, written raw or as a backslash-u escape. The
	 * text is read as the sequence of UTF-16 code units its strings denote: an escape stands for the
	 * code unit it encodes, any other escape for a non-surrogate, every other character for itself.
	 * A high surrogate must be followed by a low one, and a low one preceded by a high one.
	 */
	private static void requirePairedSurrogates(String json) {
		boolean pendingHigh = false;
		int n = json.length();
		int i = 0;
		while (i < n) {
			char unit = json.charAt(i);
			if ('\\' == unit && i + 1 < n) {
				char escaped = json.charAt(i + 1);
				if ('u' == escaped && isHex4(json, i + 2)) {
					unit = (char) Integer.parseInt(json.substring(i + 2, i + 6), 16);
					i += 6;
				} else {
					// any other escape denotes a non-surrogate; a malformed one is the parser's to refuse
					unit = 'x';
					i += 2;
				}
			} else {
				i++;
			}
			if (pendingHigh) {
				if (!Character.isLowSurrogate(unit)) {
					throw refused("a string holds an unpaired UTF-16 surrogate");
				}
				pendingHigh = false;
			} else if (Character.isHighSurrogate(unit)) {
				pendingHigh = true;
			} else if (Character.isLowSurrogate(unit)) {
				throw refused("a string holds an unpaired UTF-16 surrogate");
			}
		}
		if (pendingHigh) {
			throw refused("a string holds an unpaired UTF-16 surrogate");
		}
	}

	private static boolean isHex4(String s, int from) {
		if (from + 4 > s.length()) return false;
		for (int k = from; k < from + 4; k++) {
			char c = s.charAt(k);
			boolean hex = ('0' <= c && c <= '9') || ('a' <= c && c <= 'f') || ('A' <= c && c <= 'F');
			if (!hex) return false;
		}
		return true;
	}

	/**
	 * What the library refused, by the class of its message; the message itself is not passed on,
	 * because it can quote a member name or a value of the document.
	 */
	private static String libraryRefusal(Exception e) {
		String m = null == e.getMessage() ? "" : e.getMessage();
		if (m.startsWith("Duplicate property")) return "an object has a duplicate member name";
		if (m.contains("NaN/Infinity")) return "a number is not finite";
		return "the document is not valid JSON";
	}

	private static SbomSigningException refused(String what) {
		return new SbomSigningException(SbomSigningErrorReason.NOT_CANONICALIZABLE,
				"The document has no RFC 8785 canonical form: " + what);
	}
}
