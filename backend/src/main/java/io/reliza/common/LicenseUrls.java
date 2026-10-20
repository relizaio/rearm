/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Locale;

/**
 * The URL of a declared licence (SCORE-39 ADR-2), the only guard on it: rebom stores what it is
 * given (SCORE-40 ADR-7). {@code java.net.URI} is the parser on purpose: it refuses what a lenient
 * parser would rewrite (a space, a newline, a quote), so what is stored is what is served. Beyond
 * that, only printable ASCII is accepted (an international host is written in punycode, other
 * characters percent-encoded), so no invisible or direction-changing character can make the link
 * read as another, and no user information, which would name a host the reader does not see or
 * put credentials into every exported SBOM.
 */
public final class LicenseUrls {

	/** The longest URL accepted, after trimming. */
	public static final int MAX_LENGTH = 2048;

	private LicenseUrls() {}

	/**
	 * {@code input} trimmed, when it is an absolute http or https URL with a host.
	 *
	 * @throws IllegalArgumentException with the reason otherwise
	 */
	public static String canonical(String input) {
		if (null == input || input.isBlank()) throw new IllegalArgumentException("must not be empty");
		String trimmed = input.strip();
		if (trimmed.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("at most " + MAX_LENGTH + " characters");
		}
		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			if (c < 0x21 || c > 0x7E) {
				throw new IllegalArgumentException(c > 0x7E
						? "must be ASCII: write an international host in punycode and percent-encode the rest"
						: "not a valid URL");
			}
		}
		URI uri;
		try {
			uri = new URI(trimmed);
		} catch (URISyntaxException e) {
			throw new IllegalArgumentException("not a valid URL");
		}
		String scheme = null == uri.getScheme() ? null : uri.getScheme().toLowerCase(Locale.ROOT);
		if (!"http".equals(scheme) && !"https".equals(scheme)) {
			throw new IllegalArgumentException("scheme must be http or https");
		}
		if (null == uri.getHost() || uri.getHost().isEmpty()) {
			throw new IllegalArgumentException("host is missing");
		}
		if (null != uri.getRawUserInfo()) {
			throw new IllegalArgumentException("must not carry user information");
		}
		return trimmed;
	}
}
