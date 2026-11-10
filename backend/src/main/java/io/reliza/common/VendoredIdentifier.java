/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import java.util.Optional;
import java.util.UUID;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURLBuilder;

import io.reliza.model.SupportTextBounds;
import io.reliza.service.SupportBomInjector;
import tools.jackson.databind.JsonNode;

/**
 * The key of a vendored component (FDAW-15 3.2): the canonical purl {@code pkg:generic/<org uuid>/<name>@<version>}
 * minted by the server from a keyless node's name and version, no qualifier. {@code ?vendored=true} is written
 * only into the exported node's {@code purl}. The one normalisation lives here and every minting site uses it,
 * so the verbs, the injector and the panel's listing reach one key for one node. {@link #ofNode} is the one
 * reader of a node, and {@link #keyless} the one definition of keyless.
 */
public final class VendoredIdentifier {

	private static final String VENDORED_QUALIFIER = "?vendored=true";

	/**
	 * One keyless node as a vendored component: name and version normalised; {@code key} null and
	 * {@code refusal} the {@code SupportTextBounds} text when the values cannot pass the bounds.
	 */
	public record Vendored(String key, String name, String version, String refusal) {}

	private VendoredIdentifier() {}

	/** The key; {@code name} and {@code version} are stripped, and a blank {@code version} reads as null. */
	public static String mint(UUID org, String name, String version) {
		String n = null == name ? "" : name.strip();
		if (n.isEmpty()) throw new IllegalArgumentException("name is required");
		String v = null == version || version.isBlank() ? null : version.strip();
		try {
			PackageURLBuilder builder = PackageURLBuilder.aPackageURL().withType("generic")
					.withNamespace(org.toString()).withName(n);
			if (null != v) builder.withVersion(v);
			return builder.build().canonicalize();
		} catch (MalformedPackageURLException e) {
			throw new IllegalArgumentException("name or version cannot form an identifier", e);
		}
	}

	/** The {@code purl} an exported node of this key carries. */
	public static String exportPurl(String key) {
		return key + VENDORED_QUALIFIER;
	}

	public static boolean isMintedKey(UUID org, String key) {
		return key.startsWith("pkg:generic/" + org + "/");
	}

	/**
	 * A null component key and no non-blank {@code purl}: a non-pkg or unparseable {@code purl} falls through
	 * {@code componentKey}, but it is an uploaded value, so such a node is never keyed (F-5).
	 */
	public static boolean keyless(JsonNode node) {
		if (null != SupportBomInjector.componentKey(node)) return false;
		JsonNode purl = node.get("purl");
		return null == purl || !purl.isTextual() || purl.asText().isBlank();
	}

	/** Empty for a node that is not keyless or has a blank name; else the key or the refusal. */
	public static Optional<Vendored> ofNode(UUID org, JsonNode node) {
		if (null == node || !node.isObject() || !keyless(node)) return Optional.empty();
		String rawName = text(node, "name");
		if (null == rawName || rawName.isBlank()) return Optional.empty();
		String rawVersion = text(node, "version");
		String name = rawName.strip();
		String version = null == rawVersion || rawVersion.isBlank() ? null : rawVersion.strip();
		try {
			SupportTextBounds.boundedRequiredName(name);
			SupportTextBounds.boundedNodeVersion(version);
		} catch (IllegalArgumentException e) {
			return Optional.of(new Vendored(null, name, version, e.getMessage()));
		}
		return Optional.of(new Vendored(mint(org, name, version), name, version, null));
	}

	private static String text(JsonNode node, String field) {
		JsonNode v = node.get(field);
		return null == v || !v.isTextual() ? null : v.asText();
	}
}
