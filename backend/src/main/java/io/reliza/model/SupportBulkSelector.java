/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;
import com.github.packageurl.PackageURLBuilder;

/**
 * A bulk selector by ecosystem or namespace (FDAW-1 F-22 item 3, FDAW-14 3.4): the purl {@code type},
 * and for {@link SupportBulkSelectorForm#NAMESPACE} the purl namespace. Resolved on the server by EXACT EQUALITY on the
 * parsed purl fields, never by a LIKE pattern or a regular expression built from the selector: the
 * type charset cannot hold {@code %}, {@code _} or whitespace, and the namespace is a bound parameter
 * compared by equality, so either matches only itself.
 *
 * <p>Built through {@link #validated}, which checks charset and length with no database access and
 * canonicalises the namespace once with the purl encoder {@code Utils.purlVersionlessKey} uses, so the
 * comparison is against the percent-encoded spelling {@code sbom_components.canonical_purl} stores
 * (a typed {@code @acme} is {@code %40acme}). The receipt stores that canonical spelling.
 */
public record SupportBulkSelector(SupportBulkSelectorForm form, String type, String namespace) {

	/**
	 * The receipt's {@code form} for an explicit id list (3.4): not a selector a caller can send, so not
	 * a {@link SupportBulkSelectorForm} value, but one of the three names a receipt's form takes.
	 */
	public static final String EXPLICIT_RECEIPT_FORM = "EXPLICIT";

	private static final Pattern TYPE_CHARSET = Pattern.compile("^[a-z0-9.+-]{1,64}$");

	static final String TYPE_REFUSED = "bulk selector: type must match [a-z0-9.+-]{1,64}";
	static final String NAMESPACE_REQUIRED = "bulk selector: namespace is required for the NAMESPACE form";
	static final String NAMESPACE_REFUSED = "bulk selector: namespace is longer than "
			+ SupportTextBounds.MAX_NAMESPACE_LENGTH + " characters or holds a control character";

	/**
	 * The selector with its type checked and its namespace stripped, bounded and canonicalised; refused
	 * with 3.11's texts. A TYPE selector carries no namespace, whatever was sent.
	 */
	public static SupportBulkSelector validated(SupportBulkSelectorForm form, String type, String namespace) {
		if (null == form) throw new IllegalArgumentException("bulk selector: form is required");
		if (null == type || !TYPE_CHARSET.matcher(type).matches()) throw new IllegalArgumentException(TYPE_REFUSED);
		if (SupportBulkSelectorForm.TYPE == form) return new SupportBulkSelector(form, type, null);
		if (null == namespace || namespace.isBlank()) throw new IllegalArgumentException(NAMESPACE_REQUIRED);
		String ns = namespace.strip();
		if (ns.length() > SupportTextBounds.MAX_NAMESPACE_LENGTH || SupportTextBounds.holdsControlCharacter(ns, false)) {
			throw new IllegalArgumentException(NAMESPACE_REFUSED);
		}
		return new SupportBulkSelector(form, type, canonicalNamespace(type, ns));
	}

	/** The namespace as the purl encoder spells it: the canonical form of {@code pkg:type/ns/x} minus {@code /x}. */
	static String canonicalNamespace(String type, String namespace) {
		try {
			String canonical = PackageURLBuilder.aPackageURL().withType(type).withNamespace(namespace)
					.withName("x").build().canonicalize();
			String prefix = "pkg:" + type + "/";
			if (!canonical.startsWith(prefix) || !canonical.endsWith("/x")) {
				throw new IllegalArgumentException("bulk selector: namespace is not a purl namespace of type " + type);
			}
			return canonical.substring(prefix.length(), canonical.length() - 2);
		} catch (MalformedPackageURLException e) {
			throw new IllegalArgumentException("bulk selector: namespace is not a purl namespace of type " + type);
		}
	}

	/** The DECODED namespace, for the comparison against parsed hash keys (3.4, hash half). */
	public String decodedNamespace() {
		if (null == namespace) return null;
		try {
			return new PackageURL("pkg:" + type + "/" + namespace + "/x").getNamespace();
		} catch (MalformedPackageURLException e) {
			return namespace;
		}
	}

	/** Whether a parsed purl's decoded type and namespace equal this selector's (3.4, hash half). */
	public boolean matches(PackageURL purl) {
		if (null == purl || !type.equals(purl.getType())) return false;
		return SupportBulkSelectorForm.TYPE == form || decodedNamespace().equals(purl.getNamespace());
	}

	/** The receipt's {@code selector} JSON object (3.4): form, type, canonical namespace, the release scope. */
	public Map<String, Object> receipt(UUID releaseUuid) {
		Map<String, Object> m = new LinkedHashMap<>();
		m.put("form", form.name());
		m.put("type", type);
		if (null != namespace) m.put("namespace", namespace);
		if (null != releaseUuid) m.put("releaseUuid", releaseUuid.toString());
		return m;
	}

	/** {@code <form> <type>[/<namespace>]}, for the over-cap refusal. */
	public String describe() {
		return form.name() + " " + type + (null == namespace ? "" : "/" + namespace);
	}
}
