/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import io.reliza.common.Utils;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportStatus;

/**
 * Pure, DB-free injector that weaves per-component support disclosure into a CycloneDX BOM
 * JSON tree at read time (FDA-Readiness-1 PR2a). Two entry points:
 *
 * <ul>
 *   <li>{@link #collectKeys} -- the identity key of every component node, so the caller
 *       (a service with repository access) can resolve them to {@code sbom_components} rows;</li>
 *   <li>{@link #inject} -- append the support properties onto each matched component node.</li>
 * </ul>
 *
 * <p>The split keeps this class pure: the caller does the encoding-aware two-pass match and
 * hands back a {@code Map} keyed by {@link #componentKey}, so PR6's frozen snapshot can reuse
 * the exact same injection with {@code asOf} pinned to a cutoff instead of now.
 *
 * <p>Mutates the tree in place via Jackson (append onto any existing {@code properties} array,
 * never overwrite; never re-serialize through the CycloneDX object model, which would rewrite
 * specVersion and drop unknown fields). NEVER emits the internal {@code supportNotes} field.
 */
public final class SupportBomInjector {

	private SupportBomInjector() {}

	static final String PROP_STATUS = "reliza:support:status";
	static final String PROP_SOURCE = "reliza:support:source";
	static final String PROP_LAST_ASSESSED = "reliza:support:lastAssessed";
	static final String PROP_EOS = "cdx:lifecycle:milestone:endOfSupport";
	static final String PROP_EOL = "cdx:lifecycle:milestone:endOfLife";
	static final String PROP_DISCLOSURE = "reliza:support:disclosure";
	static final String DISCLOSURE_CURRENT_STATE = "derived-non-attested-current-state";

	/** Server-owned namespace: any reliza:support:* an uploader baked into the BOM is stripped. */
	static final String RELIZA_SUPPORT_PREFIX = "reliza:support:";
	/** Standard milestone keys we replace (never duplicate) on a component we attest. */
	private static final Set<String> MILESTONE_KEYS = Set.of(PROP_EOS, PROP_EOL);

	/**
	 * Identity key of a component node: the preserving-canonical purl (byte-compatible with
	 * how rebom persists {@code sbom_components.canonical_purl}), else the raw cpe, else null
	 * (a component with neither cannot match a stored row).
	 */
	public static String componentKey(JsonNode component) {
		if (component == null || !component.isObject()) {
			return null;
		}
		JsonNode purl = component.get("purl");
		if (purl != null && purl.isTextual() && !purl.asText().isBlank()) {
			try {
				String canonical = Utils.canonicalizePurlPreservingEncoding(purl.asText());
				if (canonical != null) {
					return canonical;
				}
				// A non-pkg / unparseable purl yields null -- fall through to cpe.
			} catch (RuntimeException malformed) {
				// fall through to cpe
			}
		}
		JsonNode cpe = component.get("cpe");
		if (cpe != null && cpe.isTextual() && !cpe.asText().isBlank()) {
			return cpe.asText();
		}
		return null;
	}

	/**
	 * Every resolvable component identity key in the BOM. Recurses nested
	 * {@code components[].components[]} and includes {@code metadata.component} (the root
	 * self-component) -- the flat extraction loop used elsewhere would miss nested children.
	 */
	public static Set<String> collectKeys(JsonNode bom) {
		Set<String> keys = new LinkedHashSet<>();
		if (bom == null || !bom.isObject()) {
			return keys;
		}
		collectFrom(bom.get("components"), keys);
		JsonNode metadata = bom.get("metadata");
		if (metadata != null && metadata.isObject()) {
			JsonNode root = metadata.get("component");
			addKey(root, keys);
			if (root != null) {
				collectFrom(root.get("components"), keys);
			}
		}
		return keys;
	}

	private static void collectFrom(JsonNode components, Set<String> keys) {
		if (components == null || !components.isArray()) {
			return;
		}
		for (JsonNode c : components) {
			addKey(c, keys);
			collectFrom(c.get("components"), keys);
		}
	}

	private static void addKey(JsonNode component, Set<String> keys) {
		String key = componentKey(component);
		if (key != null) {
			keys.add(key);
		}
	}

	/**
	 * Append per-component support properties onto every matched component node, and a
	 * document-level disclosure marker on {@code metadata.properties} labelling this as the
	 * derived, non-attested, current-state view (not the frozen attested record). Mutates and
	 * returns the same tree. {@code factsByKey} is keyed by {@link #componentKey}; a component
	 * with no entry, or whose row carries no {@code supportSource}, gets nothing (absence is
	 * not a benign "supported"). {@code asOf} is the derivation clock (now for the live view).
	 */
	public static JsonNode inject(JsonNode bom, Map<String, SbomComponent> factsByKey, LocalDate asOf) {
		if (bom == null || !bom.isObject() || factsByKey == null) {
			return bom;
		}
		// reliza:support:* is a server-owned namespace. Strip EVERY occurrence anywhere in the
		// tree BEFORE we write our own -- not just the component array but pedigree, services,
		// metadata.tools.components, vulnerabilities, etc. -- so any reliza:support:* in the
		// served BOM was provably written HERE and an uploader cannot spoof our provenance.
		stripReservedEverywhere(bom);
		injectInto(bom.get("components"), factsByKey, asOf);
		// metadata.component is the root self-component (injected too if matched). The
		// document-level disclosure marker is ALWAYS stamped so the download is labelled
		// non-attested current-state -- creating metadata if the BOM somehow lacks it.
		JsonNode metadata = bom.get("metadata");
		ObjectNode metaObj;
		if (metadata != null && metadata.isObject()) {
			metaObj = (ObjectNode) metadata;
			JsonNode root = metaObj.get("component");
			if (root != null && root.isObject()) {
				applyToComponent((ObjectNode) root, factsByKey, asOf);
				injectInto(root.get("components"), factsByKey, asOf);
			}
		} else {
			metaObj = ((ObjectNode) bom).putObject("metadata");
		}
		// Exactly one disclosure marker (the global sweep already removed any forged one).
		addProperty(properties(metaObj), PROP_DISCLOSURE, DISCLOSURE_CURRENT_STATE);
		return bom;
	}

	/**
	 * Recursively remove every reliza:support:* property from every node's {@code properties}
	 * array, everywhere in the tree -- the server-owned-namespace guarantee must hold document
	 * wide, not just on the primary component surface.
	 */
	private static void stripReservedEverywhere(JsonNode node) {
		if (node == null) {
			return;
		}
		if (node.isObject()) {
			stripByPrefix((ObjectNode) node, RELIZA_SUPPORT_PREFIX);
		}
		// Iterating a value node yields nothing, so this safely recurses only containers.
		for (JsonNode child : node) {
			stripReservedEverywhere(child);
		}
	}

	private static void injectInto(JsonNode components, Map<String, SbomComponent> factsByKey, LocalDate asOf) {
		if (components == null || !components.isArray()) {
			return;
		}
		for (JsonNode c : components) {
			if (c.isObject()) {
				applyToComponent((ObjectNode) c, factsByKey, asOf);
			}
			injectInto(c.get("components"), factsByKey, asOf);
		}
	}

	private static void applyToComponent(ObjectNode component, Map<String, SbomComponent> factsByKey, LocalDate asOf) {
		// reliza:support:* was already stripped tree-wide by stripReservedEverywhere; here we
		// only (re)write on a component we actually attest.
		String key = componentKey(component);
		SbomComponent sc = key == null ? null : factsByKey.get(key);
		if (sc == null || sc.getSupportSource() == null) {
			return;
		}
		LocalDate eos = sc.getEndOfSupportDate();
		LocalDate eol = sc.getEndOfLifeDate();
		SupportSource source = sc.getSupportSource();
		SupportStatus status = SupportStatus.derive(eos, eol, source, asOf);
		// We own the standard milestone keys for a component we attest: replace any existing
		// (incl. an upstream-supplied one) so there is never a duplicate/conflict. Provenance is
		// ALWAYS co-emitted as reliza:support:source. (PR4 must reconsider emitting the bare
		// cdx: milestone for non-MANUAL sources -- today only MANUAL exists.)
		stripByNames(component, MILESTONE_KEYS);
		ArrayNode props = properties(component);
		if (eos != null) {
			addProperty(props, PROP_EOS, eos.toString());
		}
		if (eol != null) {
			addProperty(props, PROP_EOL, eol.toString());
		}
		addProperty(props, PROP_STATUS, status.name());
		addProperty(props, PROP_SOURCE, source.name());
		if (sc.getSupportLastAssessed() != null) {
			addProperty(props, PROP_LAST_ASSESSED, sc.getSupportLastAssessed().toInstant().toString());
		}
		// supportNotes is internal-only and is DELIBERATELY never emitted.
	}

	/** Remove every property whose name starts with {@code prefix} from a node's array. */
	private static void stripByPrefix(ObjectNode node, String prefix) {
		JsonNode props = node.get("properties");
		if (props == null || !props.isArray()) {
			return;
		}
		ArrayNode arr = (ArrayNode) props;
		for (int i = arr.size() - 1; i >= 0; i--) {
			JsonNode name = arr.get(i).get("name");
			if (name != null && name.isTextual() && name.asText().startsWith(prefix)) {
				arr.remove(i);
			}
		}
	}

	/** Remove every property whose name is in {@code names} from a node's array. */
	private static void stripByNames(ObjectNode node, Set<String> names) {
		JsonNode props = node.get("properties");
		if (props == null || !props.isArray()) {
			return;
		}
		ArrayNode arr = (ArrayNode) props;
		for (int i = arr.size() - 1; i >= 0; i--) {
			JsonNode name = arr.get(i).get("name");
			if (name != null && name.isTextual() && names.contains(name.asText())) {
				arr.remove(i);
			}
		}
	}

	private static ArrayNode properties(ObjectNode node) {
		JsonNode existing = node.get("properties");
		if (existing != null && existing.isArray()) {
			return (ArrayNode) existing;
		}
		return node.putArray("properties");
	}

	private static void addProperty(ArrayNode props, String name, String value) {
		ObjectNode prop = Utils.OM.createObjectNode();
		prop.put("name", name);
		prop.put("value", value);
		props.add(prop);
	}
}
