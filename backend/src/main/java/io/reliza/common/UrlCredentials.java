/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.reliza.service.BomLinkIndex;

/**
 * FDAW-6 rule S10 in Java (FDAW-34 3.2): credentials never leave in a URL. A userinfo, everything before
 * the authority's last {@code @}, is a credential when it holds a {@code :} (a password) or a raw
 * {@code @} (read as hiding a secret) in any scheme, and any userinfo is one in {@code http},
 * {@code https}, {@code git+http} and {@code git+https}; a key-authenticated
 * {@code git+ssh://git@host/...} login stays. The output equals rebom's for the same input, byte for byte,
 * so the purl of a TEA VEX is the purl S10 wrote into the pinned SBOM beside it.
 *
 * <p>MUST stay in sync with {@code CREDENTIAL_URL_PATTERN}, {@code credentialUserinfo},
 * {@code stripUrlCredentials} and {@code stripPurlQualifierCredentials} in rebom's
 * {@code bomProcessingService.ts}.
 */
public final class UrlCredentials {

	private UrlCredentials() {}

	/**
	 * JavaScript's {@code \s}, spelled out (FDAW-34 F-4): Java's matches ASCII whitespace only, and
	 * {@code UNICODE_CHARACTER_CLASS} is a different set (it adds U+0085 and drops U+FEFF).
	 */
	private static final String JS_WHITESPACE = "\\x{9}\\x{A}\\x{B}\\x{C}\\x{D}\\x{20}\\x{A0}\\x{1680}\\x{2000}-\\x{200A}"
			+ "\\x{2028}\\x{2029}\\x{202F}\\x{205F}\\x{3000}\\x{FEFF}";

	/**
	 * A URL token: a scheme, {@code ://} and the authority up to the first {@code /}, {@code ?}, {@code #}
	 * or whitespace, bounded on the left by any character that cannot be part of a scheme. Group 1 is the
	 * scheme, group 2 the authority. Single character classes only: linear on any input.
	 */
	private static final Pattern URL_TOKEN = Pattern.compile(
			"(?<![A-Za-z0-9+.\\-])([A-Za-z][A-Za-z0-9+.\\-]*)://([^/?#" + JS_WHITESPACE + "]*)");

	private static final Pattern ENCODED_SEPARATOR = Pattern.compile("://|%3A%2F%2F", Pattern.CASE_INSENSITIVE);
	private static final Pattern ENCODED_AUTHORITY_END = Pattern.compile("/|%2F|\\?|%3F|#|%23", Pattern.CASE_INSENSITIVE);
	private static final Pattern ENCODED_AT = Pattern.compile("@|%40", Pattern.CASE_INSENSITIVE);

	/** Schemes in which any userinfo is a credential: HTTP has no key-based login. */
	private static final Set<String> HTTP_SCHEMES = Set.of("http", "https", "git+http", "git+https");

	/** The three URL-valued purl qualifiers the purl specification defines. */
	private static final Set<String> URL_PURL_QUALIFIERS = Set.of("repository_url", "download_url", "vcs_url");

	/** The credential userinfo of an authority, or null. */
	private static String credentialUserinfo(String scheme, String authority) {
		int at = authority.lastIndexOf('@');
		if (at <= 0) return null;
		String userinfo = authority.substring(0, at);
		boolean credential = userinfo.indexOf(':') >= 0 || userinfo.indexOf('@') >= 0
				|| HTTP_SCHEMES.contains(scheme.toLowerCase(Locale.ROOT));
		return credential ? userinfo : null;
	}

	/**
	 * Every URL token of {@code value} with its credential userinfo removed, the rest of the string
	 * kept: a URL inside free text or after {@code =} is edited in place. The same instance when no
	 * token carries a credential.
	 */
	public static String stripUserinfo(String value) {
		if (null == value || !value.contains("://")) return value;
		Matcher m = URL_TOKEN.matcher(value);
		StringBuilder out = null;
		int copied = 0;
		while (m.find()) {
			String authority = m.group(2);
			String userinfo = credentialUserinfo(m.group(1), authority);
			if (null == userinfo) continue;
			if (null == out) out = new StringBuilder(value.length());
			out.append(value, copied, m.start(2)).append(authority, userinfo.length() + 1, authority.length());
			copied = m.end(2);
		}
		if (null == out) return value;
		return out.append(value, copied, value.length()).toString();
	}

	/**
	 * S10 on one purl-shaped string, by surgery on the encoded value of each {@code repository_url},
	 * {@code download_url} and {@code vcs_url} qualifier: the value is decoded for the test only; when it
	 * carries a credential, the bytes from after {@code ://} (raw or {@code %3A%2F%2F}) up to and
	 * including the last {@code @} (raw or {@code %40}) before the authority ends are cut. Nothing is
	 * re-encoded, and never through {@code PackageURL}, whose round trip is not byte-identical to
	 * packageurl-js. The same instance when nothing changed.
	 */
	public static String stripPurl(String purl) {
		if (null == purl) return null;
		int hashAt = purl.indexOf('#');
		String head = hashAt < 0 ? purl : purl.substring(0, hashAt);
		String subpath = hashAt < 0 ? "" : purl.substring(hashAt);
		int queryAt = head.indexOf('?');
		if (queryAt < 0) return purl;
		String[] pairs = head.substring(queryAt + 1).split("&", -1);
		boolean changed = false;
		for (int i = 0; i < pairs.length; i++) {
			String edited = stripQualifier(pairs[i]);
			if (edited != pairs[i]) {
				pairs[i] = edited;
				changed = true;
			}
		}
		if (!changed) return purl;
		return head.substring(0, queryAt) + "?" + String.join("&", pairs) + subpath;
	}

	/** One {@code key=value} pair of a purl with its credential cut; the same instance otherwise. */
	private static String stripQualifier(String pair) {
		int eq = pair.indexOf('=');
		if (eq < 0) return pair;
		String key = pair.substring(0, eq);
		if (!URL_PURL_QUALIFIERS.contains(key.toLowerCase(Locale.ROOT))) return pair;
		String encoded = pair.substring(eq + 1);
		String decoded = decodeUriComponent(encoded);
		Matcher token = URL_TOKEN.matcher(null == decoded ? encoded : decoded);
		if (!token.lookingAt() || null == credentialUserinfo(token.group(1), token.group(2))) return pair;
		Matcher sep = ENCODED_SEPARATOR.matcher(encoded);
		if (!sep.find()) return pair;
		int authStart = sep.end();
		Matcher end = ENCODED_AUTHORITY_END.matcher(encoded);
		int authEnd = end.find(authStart) ? end.start() : encoded.length();
		Matcher at = ENCODED_AT.matcher(encoded).region(authStart, authEnd);
		int cut = -1;
		while (at.find()) cut = at.end();
		if (cut < 0) return pair;
		return key + "=" + encoded.substring(0, authStart) + encoded.substring(cut);
	}

	/**
	 * JavaScript's {@code decodeURIComponent}: each {@code %XX} run read as UTF-8, a {@code +} stays a
	 * {@code +}. Null where it throws, on a {@code %} without two hex digits or a run that is not valid
	 * UTF-8; the caller then tests the encoded string, as rebom does. Not {@code java.net.URLDecoder},
	 * which reads {@code +} as a space, and not {@code new String(bytes, UTF_8)}, which would substitute
	 * U+FFFD and test a value rebom does not.
	 */
	private static String decodeUriComponent(String s) {
		StringBuilder out = new StringBuilder(s.length());
		ByteArrayOutputStream octets = new ByteArrayOutputStream();
		try {
			for (int i = 0; i < s.length(); i++) {
				char c = s.charAt(i);
				if ('%' != c) {
					flush(octets, out);
					out.append(c);
					continue;
				}
				int hi = i + 2 < s.length() ? Character.digit(s.charAt(i + 1), 16) : -1;
				int lo = i + 2 < s.length() ? Character.digit(s.charAt(i + 2), 16) : -1;
				if (hi < 0 || lo < 0) return null;
				octets.write(hi << 4 | lo);
				i += 2;
			}
			flush(octets, out);
		} catch (CharacterCodingException e) {
			return null;
		}
		return out.toString();
	}

	private static void flush(ByteArrayOutputStream octets, StringBuilder out) throws CharacterCodingException {
		if (0 == octets.size()) return;
		out.append(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
				.onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(octets.toByteArray())));
		octets.reset();
	}

	/**
	 * Whether {@code value} carries a credential by the S10 definition: split on {@code #}, a segment
	 * that starts with {@code pkg:} carries one when {@link #stripPurl} would change it, any other segment
	 * when {@link #stripUserinfo} would change a URL token in it, or, holding a {@code %}, would change one
	 * in the segment decoded once more (FDAW-25 F-33).
	 */
	public static boolean carriesCredential(String value) {
		if (null == value || value.isEmpty()) return false;
		for (String segment : value.split("#", -1)) {
			if (segment.startsWith("pkg:")) {
				if (stripPurl(segment) != segment) return true;
				continue;
			}
			if (stripUserinfo(segment) != segment) return true;
			if (segment.indexOf('%') >= 0) {
				String decoded = BomLinkIndex.decodeFragment(segment);
				if (stripUserinfo(decoded) != decoded) return true;
			}
		}
		return false;
	}
}
