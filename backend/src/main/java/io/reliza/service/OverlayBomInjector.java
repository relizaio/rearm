/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import io.reliza.common.CdxLicenseUtil;
import io.reliza.common.VendoredIdentifier;
import io.reliza.model.OverlayKind;
import io.reliza.model.SbomComponentOverlay;
import io.reliza.model.SbomComponentOverlayData;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Applies the overlays to a merged document (FDAW-15 3.4), before the support injector. A pure tree edit
 * over the two functions it is given. It keys a described keyless node and fills a standard field only where
 * the node leaves it empty: it never replaces an uploaded value, adds a node, reads or writes a
 * {@code bom-ref}, or writes a {@code reliza:} property. It runs after rebom's one strip pass, so nothing
 * sweeps what it writes: the supplier content rule of {@code SupportTextBounds} keeps the document's
 * {@code reliza:export:personalDataStripped} record true, and this class edits no value.
 */
public final class OverlayBomInjector {

	private static final String SBOM_MISSING_PROPERTY = "reliza:sbom:missing";

	/** What one pass did. */
	public record Overlaid(int nodesVisited, int vendoredKeyed, int fieldsFilled) {}

	private record Keyed(ObjectNode node, String rowKey) {}

	private OverlayBomInjector() {}

	/**
	 * The overlay key of a component key (F-1): the {@code canonical_purl} of the row the support resolver
	 * chose for it, or the key itself when it chose none. The fingerprint readers use the same mapping.
	 */
	public static Function<String, String> rowKeyOf(Map<String, ComponentSupportFacts> facts) {
		return key -> {
			ComponentSupportFacts f = facts.get(key);
			return null == f || null == f.component() ? key : f.component().getCanonicalPurl();
		};
	}

	/**
	 * Every node the overlay considers, in document order: under {@code components[]} at any depth, without
	 * {@code metadata.component} (the release's own node) and {@code reliza:sbom:missing} nodes.
	 */
	public static List<ObjectNode> overlayNodes(JsonNode bom) {
		if (null == bom || !bom.isObject()) return List.of();
		List<ObjectNode> all = new ArrayList<>();
		SupportBomInjector.collectComponentNodes(bom.get("components"), all);
		return all.stream().filter(n -> !carriesSbomMissing(n)).toList();
	}

	/** Edits {@code bom} in place. {@code lookup} is called once, with the mapped keys, and answers rows by key. */
	public static Overlaid apply(JsonNode bom, UUID org, Function<String, String> rowKeyOf,
			Function<Set<String>, Map<String, SbomComponentOverlay>> lookup) {
		List<ObjectNode> nodes = overlayNodes(bom);
		// A list, not a map: two nodes of one name and version are equal JsonNodes, and each is filled.
		List<Keyed> keyed = new ArrayList<>();
		for (ObjectNode node : nodes) {
			String key = SupportBomInjector.componentKey(node);
			String rowKey = null != key ? rowKeyOf.apply(key)
					: VendoredIdentifier.ofNode(org, node).map(VendoredIdentifier.Vendored::key).orElse(null);
			if (null != rowKey) keyed.add(new Keyed(node, rowKey));
		}
		if (keyed.isEmpty()) return new Overlaid(nodes.size(), 0, 0);
		Set<String> rowKeys = new LinkedHashSet<>();
		keyed.forEach(k -> rowKeys.add(k.rowKey()));
		Map<String, SbomComponentOverlay> rows = lookup.apply(rowKeys);

		int vendoredKeyed = 0;
		int fieldsFilled = 0;
		for (Keyed k : keyed) {
			ObjectNode node = k.node();
			SbomComponentOverlay row = rows.get(k.rowKey());
			if (null == row) continue;
			if (null == SupportBomInjector.componentKey(node)) {
				if (OverlayKind.VENDORED != row.getKind()) continue;
				node.put("purl", VendoredIdentifier.exportPurl(k.rowKey()));
				vendoredKeyed++;
			}
			fieldsFilled += fill(node, row.getRecordData());
		}
		return new Overlaid(nodes.size(), vendoredKeyed, fieldsFilled);
	}

	private static int fill(ObjectNode node, SbomComponentOverlayData data) {
		int filled = 0;
		if (null != data.supplier() && supplierEmpty(node.get("supplier"))) {
			node.putObject("supplier").put("name", data.supplier());
			filled++;
		}
		if (null != data.licence() && licensesEmpty(node.get("licenses"))) {
			ArrayNode licenses = node.putArray("licenses");
			Optional<String> id = CdxLicenseUtil.canonicalSpdxId(data.licence());
			if (id.isPresent()) licenses.addObject().putObject("license").put("id", id.get());
			else licenses.addObject().put("expression", data.licence());
			filled++;
		}
		if (null != data.description() && descriptionEmpty(node.get("description"))) {
			node.put("description", data.description());
			filled++;
		}
		return filled;
	}

	private static boolean absent(JsonNode field) {
		return null == field || field.isNull();
	}

	private static boolean supplierEmpty(JsonNode supplier) {
		if (absent(supplier)) return true;
		if (!supplier.isObject()) return false;
		JsonNode name = supplier.get("name");
		boolean blankName = absent(name) || (name.isTextual() && name.asText().isBlank());
		return blankName && emptyOrAbsent(supplier.get("url")) && emptyOrAbsent(supplier.get("contact"));
	}

	private static boolean emptyOrAbsent(JsonNode field) {
		return absent(field) || (field.isArray() && field.isEmpty());
	}

	private static boolean licensesEmpty(JsonNode licenses) {
		return emptyOrAbsent(licenses);
	}

	private static boolean descriptionEmpty(JsonNode description) {
		return absent(description) || (description.isTextual() && description.asText().isBlank());
	}

	private static boolean carriesSbomMissing(JsonNode node) {
		JsonNode properties = node.get("properties");
		if (null == properties || !properties.isArray()) return false;
		for (JsonNode p : properties) {
			JsonNode name = p.get("name");
			if (null != name && SBOM_MISSING_PROPERTY.equals(name.asText())) return true;
		}
		return false;
	}
}
