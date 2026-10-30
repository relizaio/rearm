/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.common;

/**
 * The bounded rendering of a request-derived string a log line writes (task TPF-24).
 *
 * <p>The rule on the credential paths (the token endpoint's grants and its Basic path, the device-code start and
 * revoke, the bearer verify, the Basic parse and lookup, the rate limiter on both chains, the download handlers' key
 * gate): no log line reached before a credential is verified writes a client-controlled string as sent. An
 * unverified value is written only (i) through {@link #text}, (ii) as
 * {@link CommonVariables.AuthHeaderParse#canonicalId()} (or the key service's refused identity built on it) for a
 * key-id text, (iii) as an exception's class name, never its message, (iv) as a length, a count or a hash, or (v) as
 * a fixed-vocabulary classification. In the classes on those paths every log argument that originates in a request
 * string (a header, a form value, a claim, a request target, an exception message a library built from request
 * bytes) takes one of these forms, verified at that line or not. The default layout writes the message unescaped,
 * so this helper, not the layout, is what bounds the line.
 */
public final class LogSafe {

	/** The longest rendering, in UTF-16 units, before the cut mark. */
	public static final int MAX_LENGTH = 256;

	private LogSafe() {}

	/**
	 * {@code -} for null; otherwise every UTF-16 unit outside printable ASCII (below 0x20 or above 0x7E) replaced by
	 * {@code ?}, and a result longer than {@value #MAX_LENGTH} units cut to that length with {@code ...} appended.
	 * Nothing else: no escaping, no quoting. An address, an issuer URL, a request target or a key id renders unchanged,
	 * so a line break, a tab, a control or a non-ASCII character never reaches the log.
	 */
	public static String text(String s) {
		if (s == null) return "-";
		int n = Math.min(s.length(), MAX_LENGTH);
		StringBuilder b = new StringBuilder(n + 3);
		for (int i = 0; i < n; i++) {
			char c = s.charAt(i);
			b.append(c < 0x20 || c > 0x7E ? '?' : c);
		}
		if (s.length() > MAX_LENGTH) b.append("...");
		return b.toString();
	}
}
