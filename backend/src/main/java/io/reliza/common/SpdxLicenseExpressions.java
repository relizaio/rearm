/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The SPDX licence expressions ReARM accepts as a declared licence (SCORE-39 ADR-2), the SPDX 2.3
 * Annex D subset:
 *
 * <pre>
 * expression := term (('AND' | 'OR') term)*
 * term       := simple | simple 'WITH' exception | '(' expression ')'
 * simple     := spdxId '+'? | 'LicenseRef-' idstring
 * exception  := idstring
 * idstring   := [A-Za-z0-9.-]+
 * </pre>
 *
 * An spdxId is matched case-insensitively against the SPDX list {@link CdxLicenseUtil} loads and
 * written in the list's casing; {@code LicenseRef-} is case-sensitive, as rebom's form rule is;
 * operators are the upper-case words; an exception id is checked for syntax only; parentheses are
 * kept as written and precedence is never rewritten. NOASSERTION, NONE and DocumentRef are refused.
 * The canonical form is trimmed, with one space between tokens and none inside parentheses.
 */
public final class SpdxLicenseExpressions {

	/** The longest expression accepted, after trimming. */
	public static final int MAX_LENGTH = 512;

	private static final String LICENSE_REF = "LicenseRef-";
	private static final Pattern ID_STRING = Pattern.compile("[A-Za-z0-9.-]+");
	private static final Pattern SINGLE_LICENSE_REF = Pattern.compile("LicenseRef-[A-Za-z0-9.-]+");
	/** How much of a refused token a reason repeats. */
	private static final int TOKEN_ECHO_MAX = 64;

	private SpdxLicenseExpressions() {}

	/**
	 * {@code input} in canonical form.
	 *
	 * @throws IllegalArgumentException with the reason, naming the token, when the input is not an
	 *         accepted expression
	 */
	public static String canonical(String input) {
		if (null == input || input.isBlank()) throw new IllegalArgumentException("must not be empty");
		String trimmed = input.strip();
		if (trimmed.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("at most " + MAX_LENGTH + " characters");
		}
		Parser p = new Parser(tokenize(trimmed));
		p.expression();
		if (p.hasNext()) throw unexpected(p.peek());
		StringBuilder sb = new StringBuilder();
		String previous = null;
		for (String t : p.out) {
			if (null != previous && !"(".equals(previous) && !")".equals(t)) sb.append(' ');
			sb.append(t);
			previous = t;
		}
		// The canonical form can be longer than the input (spaces around operators written next to
		// parentheses); the bound holds for what is stored.
		if (sb.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("at most " + MAX_LENGTH + " characters");
		}
		return sb.toString();
	}

	/**
	 * Whether {@code canonical} is one {@code LicenseRef-} and nothing else: the form rebom writes as
	 * a named licence with its URL (SCORE-40).
	 */
	public static boolean isSingleLicenseRef(String canonical) {
		return null != canonical && SINGLE_LICENSE_REF.matcher(canonical).matches();
	}

	private static List<String> tokenize(String s) {
		List<String> tokens = new ArrayList<>();
		StringBuilder word = new StringBuilder();
		for (int i = 0; i < s.length(); i++) {
			char c = s.charAt(i);
			if (Character.isWhitespace(c) || '(' == c || ')' == c) {
				if (word.length() > 0) {
					tokens.add(word.toString());
					word.setLength(0);
				}
				if ('(' == c || ')' == c) tokens.add(String.valueOf(c));
			} else {
				word.append(c);
			}
		}
		if (word.length() > 0) tokens.add(word.toString());
		return tokens;
	}

	private static boolean isOperator(String t) {
		return "AND".equals(t) || "OR".equals(t) || "WITH".equals(t);
	}

	private static IllegalArgumentException unexpected(String token) {
		return new IllegalArgumentException("unexpected token " + quote(token));
	}

	private static String quote(String token) {
		String shown = token.length() > TOKEN_ECHO_MAX ? token.substring(0, TOKEN_ECHO_MAX) + "..." : token;
		return '"' + shown + '"';
	}

	/** Recursive descent over the tokens, writing the canonical tokens to {@code out}. */
	private static final class Parser {
		private final List<String> tokens;
		private final List<String> out = new ArrayList<>();
		private int pos;

		Parser(List<String> tokens) {
			this.tokens = tokens;
		}

		boolean hasNext() {
			return pos < tokens.size();
		}

		String peek() {
			return tokens.get(pos);
		}

		void expression() {
			term();
			while (hasNext() && ("AND".equals(peek()) || "OR".equals(peek()))) {
				String op = tokens.get(pos++);
				out.add(op);
				if (!hasNext()) throw new IllegalArgumentException("expected a licence after " + quote(op));
				term();
			}
		}

		void term() {
			if (!hasNext()) throw new IllegalArgumentException("expected a licence");
			String t = tokens.get(pos);
			if ("(".equals(t)) {
				pos++;
				out.add("(");
				if (hasNext() && ")".equals(peek())) throw unexpected(")");
				expression();
				if (!hasNext()) throw new IllegalArgumentException("unbalanced parenthesis: missing ')'");
				if (!")".equals(peek())) throw unexpected(peek());
				pos++;
				out.add(")");
				return;
			}
			if (")".equals(t) || isOperator(t)) throw unexpected(t);
			pos++;
			out.add(simple(t));
			if (hasNext() && "WITH".equals(peek())) {
				pos++;
				out.add("WITH");
				if (!hasNext() || isOperator(peek()) || !ID_STRING.matcher(peek()).matches()) {
					throw new IllegalArgumentException("missing exception after WITH");
				}
				out.add(tokens.get(pos++));
			}
		}

		private static String simple(String t) {
			String upper = t.toUpperCase(Locale.ROOT);
			if ("NOASSERTION".equals(upper) || "NONE".equals(upper)) {
				throw new IllegalArgumentException("NOASSERTION and NONE are not a declared licence; leave the field empty instead");
			}
			if (t.startsWith("DocumentRef-")) {
				throw new IllegalArgumentException("DocumentRef is not supported: " + quote(t));
			}
			if (t.startsWith(LICENSE_REF)) {
				if (!SINGLE_LICENSE_REF.matcher(t).matches()) {
					throw new IllegalArgumentException("invalid LicenseRef " + quote(t)
							+ ": LicenseRef- must be followed by letters, digits, dots or hyphens");
				}
				return t;
			}
			boolean orLater = t.length() > 1 && t.endsWith("+");
			String id = orLater ? t.substring(0, t.length() - 1) : t;
			Optional<String> canonicalId = CdxLicenseUtil.canonicalSpdxId(id);
			// a single '+': the list holds deprecated ids that already end in one (GPL-2.0+), so
			// GPL-2.0++ would otherwise match after the strip
			if (canonicalId.isEmpty() || orLater && canonicalId.get().endsWith("+")) {
				throw new IllegalArgumentException("unknown licence id " + quote(t));
			}
			return orLater ? canonicalId.get() + "+" : canonicalId.get();
		}
	}
}
