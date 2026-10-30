/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.tea;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A Transparency Exchange Identifier as the TEA 1.0.0 discovery readme writes it (task TEA-6):
 * {@code tei://<domain>/<type>/<identifier>}. The domain is one or more DNS labels separated by dots
 * (a single label, {@code localhost}, is accepted); the type is lowercase letters and digits starting
 * with a letter; the identifier is one non-empty path segment. Nothing else is accepted: no port, no
 * user info, no query, no fragment, no trailing slash, no whitespace. TEIs compare character for
 * character; a domain written in uppercase parses, and is a different TEI.
 */
public record TeaTei(String tei, String domain, String type, String identifier) {

	static final String SCHEME = "tei://";
	private static final Pattern LABEL = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");
	private static final Pattern TYPE = Pattern.compile("^[a-z][a-z0-9]*$");

	/** The TEI, or empty when it breaks the syntax. */
	public static Optional<TeaTei> parse(String value) {
		return null == problem(value) ? Optional.of(split(value)) : Optional.empty();
	}

	/** The rule {@code value} breaks, or null when it is a TEI. */
	public static String problem(String value) {
		if (null == value || value.isEmpty()) return "the TEI is empty";
		if (!value.startsWith(SCHEME)) return "a TEI starts with tei://";
		for (int i = 0; i < value.length(); i++) {
			char c = value.charAt(i);
			if (Character.isWhitespace(c) || Character.isISOControl(c)) return "a TEI contains no whitespace";
		}
		if (value.indexOf('?') >= 0) return "a TEI carries no query";
		if (value.indexOf('#') >= 0) return "a TEI carries no fragment";
		String rest = value.substring(SCHEME.length());
		String[] parts = rest.split("/", -1);
		if (parts.length != 3) return "a TEI is tei://<domain>/<type>/<identifier>, with no slash in the identifier and none at the end";
		String domain = parts[0];
		if (domain.isEmpty()) return "the TEI domain is empty";
		if (domain.indexOf('@') >= 0) return "the TEI domain carries no user info";
		if (domain.indexOf(':') >= 0) return "the TEI domain carries no port";
		for (String label : domain.split("\\.", -1)) {
			if (!LABEL.matcher(label).matches()) return "the TEI domain is DNS labels separated by dots";
		}
		if (!TYPE.matcher(parts[1]).matches()) return "the TEI type is lowercase letters and digits starting with a letter";
		String id = parts[2];
		if (id.isEmpty()) return "the TEI identifier is empty";
		if (id.toLowerCase(Locale.ROOT).contains("%2f")) return "the TEI identifier contains no encoded slash";
		return null;
	}

	private static TeaTei split(String value) {
		String[] parts = value.substring(SCHEME.length()).split("/", -1);
		return new TeaTei(value, parts[0], parts[1], parts[2]);
	}

	/** The rule the identifier breaks for its type ({@code uuid}, {@code purl}), or null. */
	public String identifierProblem() {
		if ("uuid".equals(type)) return uuidIdentifier().isPresent() ? null : "a uuid TEI carries a UUID";
		if ("purl".equals(type)) {
			return purlIdentifier().isPresent() ? null : "a purl TEI carries a Base64URL encoded purl, without padding";
		}
		return null;
	}

	/** The UUID of a {@code uuid} TEI. */
	public Optional<UUID> uuidIdentifier() {
		if (!"uuid".equals(type)) return Optional.empty();
		try {
			UUID u = UUID.fromString(identifier);
			return u.toString().equalsIgnoreCase(identifier) ? Optional.of(u) : Optional.empty();
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}

	/** The purl of a {@code purl} TEI: the identifier Base64URL decoded without padding, starting with pkg:. */
	public Optional<String> purlIdentifier() {
		if (!"purl".equals(type) || identifier.indexOf('=') >= 0) return Optional.empty();
		try {
			String purl = new String(Base64.getUrlDecoder().decode(identifier), StandardCharsets.UTF_8);
			return purl.startsWith("pkg:") ? Optional.of(purl) : Optional.empty();
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
	}
}
