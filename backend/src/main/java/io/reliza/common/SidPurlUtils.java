/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.common;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import com.github.packageurl.PackageURLBuilder;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;

/**
 * Utilities for sid (Software IDentification) PURL handling — input validation
 * and canonical-string construction.
 *
 * <p>Rules are derived from the upstream sid PURL spec
 * (<a href="https://github.com/package-url/purl-spec/blob/main/docs/decisions/001-PURL_type-software_without_registry.md">decision doc 001</a>)
 * and the general PURL specification's namespace-segment rules.
 */
public final class SidPurlUtils {

	/** Canonical lowercase type for sid PURLs. */
	public static final String SID_TYPE = "sid";

	private SidPurlUtils() {}

	/**
	 * @return true iff {@code purl} parses to a PURL whose type is {@code "sid"}
	 *   (case-insensitive via the library's normalization). Robust against
	 *   whitespace and case variants in user-supplied values; returns false on
	 *   any parse failure (treats malformed PURLs as non-sid). Null-safe.
	 */
	public static boolean isSidPurl(String purl) {
		if (purl == null) {
			return false;
		}
		try {
			return SID_TYPE.equalsIgnoreCase(new PackageURL(purl).getType());
		} catch (Exception e) {
			return false;
		}
	}

	/**
	 * Pick the preferred PURL identifier — sid first, then any other valid PURL
	 * (lex tie-break for stable re-emits), malformed entries last.
	 * Malformed PURLs do not throw — a single bad tenant value must not break BOM emission.
	 * Returns empty when the input has no PURL-typed entry.
	 *
	 * <p>Single-pass selection: each candidate is parsed at most once (versus
	 * comparator-driven {@code stream.min()} which can re-parse on every pairwise
	 * compare). Identifier lists are tiny in practice — this matters only for
	 * predictability, not raw throughput.
	 */
	public static Optional<RearmIdentifier> pickPreferredPurl(List<RearmIdentifier> identifiers) {
		if (identifiers == null || identifiers.isEmpty()) {
			return Optional.empty();
		}
		RearmIdentifier best = null;
		int bestTier = Integer.MAX_VALUE;
		for (RearmIdentifier ti : identifiers) {
			if (ti == null || ti.getIdType() != RearmIdentifierType.PURL || ti.getIdValue() == null) {
				continue;
			}
			int tier = tierOf(ti);
			if (tier < bestTier) {
				best = ti;
				bestTier = tier;
			} else if (tier == bestTier
					&& ti.getIdValue().compareTo(best.getIdValue()) < 0) {
				// Same tier — lex-smallest idValue wins for stable re-emits.
				best = ti;
			}
		}
		return Optional.ofNullable(best);
	}

	/** Tier helper for {@link #pickPreferredPurl}: 0 = sid, 1 = other valid PURL, 2 = malformed. */
	private static int tierOf(RearmIdentifier ti) {
		try {
			PackageURL parsed = new PackageURL(ti.getIdValue());
			return SID_TYPE.equalsIgnoreCase(parsed.getType()) ? 0 : 1;
		} catch (Exception e) {
			return 2;
		}
	}

	/** Maximum total length of a domain-form first segment per RFC 1035. */
	private static final int DOMAIN_MAX_LENGTH = 253;

	/** Per-label syntax for a domain-form first segment per RFC 1035. */
	private static final Pattern DOMAIN_LABEL = Pattern.compile("^[A-Za-z0-9](?:[A-Za-z0-9-]{0,61}[A-Za-z0-9])?$");

	/** Characters forbidden in a raw (decoded) authority segment because they collide with PURL structure. */
	private static final Pattern FORBIDDEN_RAW = Pattern.compile("[\\u0000-\\u001F\\u007F/\\\\?#%]");

	/** Result of validating authority segments. {@code valid} is true iff segments would produce a well-formed namespace. */
	public record ValidationResult(boolean valid, String error) {
		public static ValidationResult ok() {
			return new ValidationResult(true, null);
		}

		public static ValidationResult fail(String error) {
			return new ValidationResult(false, error);
		}
	}

	/**
	 * Validate decoded authority segments. First segment is the authority (domain or
	 * registered name); remaining segments are publisher/BU/product-line context.
	 * Internal whitespace is allowed (will be percent-encoded at build time);
	 * leading/trailing whitespace is rejected so list-equality stays well-defined.
	 */
	public static ValidationResult validateAuthoritySegments(List<String> segments) {
		if (segments == null || segments.isEmpty()) {
			return ValidationResult.fail("sidAuthoritySegments must not be empty");
		}
		for (int i = 0; i < segments.size(); i++) {
			String seg = segments.get(i);
			ValidationResult segmentResult = validateSegment(seg, i);
			if (!segmentResult.valid()) {
				return segmentResult;
			}
		}
		ValidationResult firstResult = validateFirstSegment(segments.get(0));
		if (!firstResult.valid()) {
			return firstResult;
		}
		return ValidationResult.ok();
	}

	private static ValidationResult validateSegment(String seg, int index) {
		if (seg == null || seg.isEmpty()) {
			return ValidationResult.fail("sidAuthoritySegments[" + index + "] must not be null or empty");
		}
		if (!seg.equals(seg.strip())) {
			return ValidationResult.fail("sidAuthoritySegments[" + index + "] must not have leading or trailing whitespace");
		}
		if (FORBIDDEN_RAW.matcher(seg).find()) {
			return ValidationResult.fail("sidAuthoritySegments[" + index
					+ "] must not contain control characters or any of: / \\ ? # %");
		}
		return ValidationResult.ok();
	}

	private static ValidationResult validateFirstSegment(String first) {
		if (!first.chars().allMatch(c -> c < 0x80)) {
			return ValidationResult.fail("sidAuthoritySegments[0] must be ASCII (use punycode for IDN domains)");
		}
		if (first.startsWith(".") || first.endsWith(".")) {
			return ValidationResult.fail("sidAuthoritySegments[0] must not start or end with '.'");
		}
		boolean isDomainForm = first.contains(".");
		if (isDomainForm) {
			return validateDomainForm(first);
		}
		// Registry-based form: spec requires it to be registered in the upstream PURL registry.
		// The backend cannot verify the registry from inside the request; accept with a
		// caveat that callers (UI) should surface a warning. Reject only obviously malformed values.
		if (first.chars().allMatch(Character::isDigit)) {
			return ValidationResult.fail("sidAuthoritySegments[0] (registry form) must not be all digits");
		}
		return ValidationResult.ok();
	}

	private static ValidationResult validateDomainForm(String first) {
		if (first.length() > DOMAIN_MAX_LENGTH) {
			return ValidationResult.fail("sidAuthoritySegments[0] domain exceeds " + DOMAIN_MAX_LENGTH + " characters");
		}
		if (first.chars().allMatch(c -> Character.isDigit(c) || c == '.')) {
			return ValidationResult.fail("sidAuthoritySegments[0] looks like an IPv4 address; supply a domain name instead");
		}
		String[] labels = first.split("\\.", -1);
		for (String label : labels) {
			if (!DOMAIN_LABEL.matcher(label).matches()) {
				return ValidationResult.fail("sidAuthoritySegments[0] domain label '" + label
						+ "' violates RFC 1035 (use [A-Za-z0-9-], 1-63 chars, no leading/trailing '-')");
			}
		}
		return ValidationResult.ok();
	}

	/**
	 * Build a canonical {@code pkg:sid/<segments>/<name>@<version>?<qualifiers>#<subpath>}
	 * string from decoded inputs. Percent-encoding of namespace segments, name, version,
	 * and qualifier values is delegated to {@code packageurl-java}.
	 *
	 * <p>Inputs use case-preserved decoded forms; the spec's case-preservation rule for
	 * namespace/name/version is honored automatically by the library.
	 *
	 * @param segments    decoded authority segments (first segment = authority, rest = publisher
	 *                    / business-unit / product-line context); must be non-empty and pass
	 *                    {@link #validateAuthoritySegments(List)} (caller's responsibility — this
	 *                    method assumes pre-validated input and treats malformed segments as a
	 *                    {@link RelizaException}).
	 * @param componentName  decoded component name (e.g. {@code "Acme Robotics"} → encoded as
	 *                       {@code Acme%20Robotics} in the output); never null/blank.
	 * @param version     decoded version string; nullable (omits the {@code @<version>} segment when null).
	 * @param qualifiers  optional qualifier map (keys lowercase ASCII per spec; values decoded);
	 *                    nullable/empty means no qualifiers.
	 * @param subpath     optional decoded subpath; nullable/blank means no subpath.
	 * @return canonical {@code pkg:sid/...} string
	 * @throws RelizaException if {@code packageurl-java} rejects the inputs as malformed
	 */
	public static String buildSidPurl(List<String> segments, String componentName, String version,
			Map<String, String> qualifiers, String subpath) throws RelizaException {
		if (componentName == null || componentName.isBlank()) {
			throw new RelizaException("sid PURL componentName must be non-blank");
		}
		if (segments == null || segments.isEmpty()) {
			throw new RelizaException("sid PURL requires non-empty authority segments");
		}
		try {
			// withNamespace takes a single '/'-joined string; the library splits on '/'
			// during canonicalization and percent-encodes each segment as UTF-8.
			String namespace = String.join("/", segments);
			PackageURLBuilder builder = PackageURLBuilder.aPackageURL()
					.withType(SID_TYPE)
					.withNamespace(namespace)
					.withName(componentName);
			if (version != null && !version.isEmpty()) {
				builder.withVersion(version);
			}
			if (qualifiers != null && !qualifiers.isEmpty()) {
				qualifiers.forEach(builder::withQualifier);
			}
			if (subpath != null && !subpath.isBlank()) {
				builder.withSubpath(subpath);
			}
			return builder.build().canonicalize();
		} catch (MalformedPackageURLException e) {
			throw new RelizaException("Failed to build sid PURL: " + e.getMessage());
		}
	}
}
