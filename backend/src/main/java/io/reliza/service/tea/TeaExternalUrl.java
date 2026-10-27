/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import io.reliza.common.LogSafe;

/**
 * The rule for a url the TEA surface hands a reader (task TEA-19): the frozen format url of an externally
 * stored ride-along ({@code TeaPublicationService.rideAlong}) and the live distribution url of the TEA 1.0.0
 * component release ({@code TeaTransformerService.distributionOf}). A url is admitted only when it is printable
 * US-ASCII, an RFC 3986 URI, absolute with scheme http or https (any case), without user information, with a
 * host, and at most {@value #MAX_OCTETS} octets. The admitted string is used exactly as stored: no trim, no
 * normalisation.
 */
public final class TeaExternalUrl {

	/** The longest url admitted, in octets; equal to the longest Location TEA-10 sends. */
	public static final int MAX_OCTETS = 4096;

	/** The first check a url fails, in the order {@link #problemOf} runs them. */
	public enum Problem { EMPTY, NOT_PRINTABLE_ASCII, NOT_A_URI, NO_SCHEME, SCHEME_NOT_HTTP, USERINFO, NO_HOST, TOO_LONG }

	private TeaExternalUrl() {}

	/** Empty when the url may be published; else the first problem, in the order of the checks. */
	public static Optional<Problem> problemOf(String url) {
		if (null == url || url.isEmpty()) return Optional.of(Problem.EMPTY);
		if (firstNotPrintableAscii(url) >= 0) return Optional.of(Problem.NOT_PRINTABLE_ASCII);
		URI uri;
		try {
			uri = new URI(url);
		} catch (URISyntaxException e) {
			// the parser's reason is library text built from the string: never copied anywhere
			return Optional.of(Problem.NOT_A_URI);
		}
		if (!uri.isAbsolute()) return Optional.of(Problem.NO_SCHEME);
		String scheme = uri.getScheme();
		if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
			return Optional.of(Problem.SCHEME_NOT_HTTP);
		}
		if (null != uri.getRawUserInfo()) return Optional.of(Problem.USERINFO);
		if (null == uri.getHost() || uri.getHost().isEmpty()) return Optional.of(Problem.NO_HOST);
		// printable US-ASCII from here on, so the UTF-16 length is the UTF-8 octet length
		if (url.length() > MAX_OCTETS) return Optional.of(Problem.TOO_LONG);
		return Optional.empty();
	}

	/** {@code "download link " + LogSafe.text(url) + ": "} and the problem's fixed text, for an outcome's detail. */
	public static String describe(String url, Problem p) {
		return "download link " + LogSafe.text(url) + ": " + textOf(url, p);
	}

	private static String textOf(String url, Problem p) {
		return switch (p) {
			case EMPTY -> "is empty";
			case NOT_PRINTABLE_ASCII -> {
				int i = firstNotPrintableAscii(url);
				yield "has a character outside printable US-ASCII at index " + i + " ("
						+ String.format("U+%04X", url.codePointAt(i)) + "); percent-encode it";
			}
			case NOT_A_URI -> "is not an RFC 3986 URI";
			case NO_SCHEME -> "is relative; an absolute http or https url is required";
			case SCHEME_NOT_HTTP -> "has a scheme other than http or https";
			case USERINFO -> "carries user information before the host, which is never published";
			case NO_HOST -> "has no host";
			case TOO_LONG -> "is " + url.length() + " octets, over the " + MAX_OCTETS + " this server publishes";
		};
	}

	/**
	 * The UTF-16 index of the first code point outside U+0021 to U+007E, or -1. Walks code points, so a
	 * supplementary character is one code point reported at the index of its first unit.
	 */
	private static int firstNotPrintableAscii(String url) {
		for (int i = 0; i < url.length();) {
			int cp = url.codePointAt(i);
			if (cp < 0x21 || cp > 0x7E) return i;
			i += Character.charCount(cp);
		}
		return -1;
	}
}
