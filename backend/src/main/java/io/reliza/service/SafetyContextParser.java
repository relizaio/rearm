/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;

import tools.jackson.databind.JsonNode;

import io.reliza.common.Utils;
import io.reliza.model.dto.SafetyContextEntry;
import io.reliza.model.dto.SafetyContextParseResult;

/**
 * Reads the CycloneDX 2.0 development model at schema commit
 * ea8c3605829d1da13339a0790b6da73548e42a6b.
 *
 * <p>This parser extracts only blueprint assets with safety-integrity levels
 * and their explicit component references. It does not infer a safety outcome,
 * create a classification, or treat a classification as an approval. Unknown
 * fields and all source JSON remain intact for later consumers.
 */
@Service
public class SafetyContextParser {

    public SafetyContextParseResult parse(String json) {
        JsonNode root;
        try {
            root = Utils.OM.readTree(json);
        } catch (RuntimeException e) {
            return invalid(json, "Document is not valid JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            return invalid(json, "CycloneDX document root must be an object");
        }

        String specVersion = root.path("specVersion").asText(null);
        if (!"2.0".equals(specVersion) && (specVersion == null || !specVersion.startsWith("2.0-"))) {
            return new SafetyContextParseResult(specVersion, json, List.of(),
                List.of("Expected CycloneDX specVersion 2.0 or a 2.0 development version"), List.of());
        }

        Map<String, List<ComponentIdentity>> componentsByRef = new HashMap<>();
        Map<String, List<ComponentIdentity>> componentsByBomRef = new HashMap<>();
        collectComponents(root, componentsByRef, componentsByBomRef);

        List<SafetyContextEntry> entries = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        JsonNode blueprints = root.path("blueprints");
        if (blueprints.isArray()) {
            for (JsonNode blueprint : blueprints) {
                JsonNode assets = blueprint.path("assets");
                if (!assets.isArray()) continue;
                for (JsonNode asset : assets) {
                    JsonNode levels = asset.path("classification").path("safetyIntegrityLevels");
                    if (!levels.isArray() || levels.isEmpty()) continue;

                    String componentRef = asset.path("componentRef").asText(null);
                    ComponentIdentity component = unique(componentsByRef.get(componentRef));
                    if (component == null) {
                        component = unique(componentsByBomRef.get(componentRef));
                    }
                    if (componentRef != null && component == null) {
                        warnings.add("Asset " + asset.path("refId").asText("<no refId>")
                            + " references component " + componentRef
                            + " that is absent or ambiguous in this document");
                    }

                    List<String> levelJson = new ArrayList<>();
                    levels.forEach(level -> levelJson.add(level.toString()));
                    entries.add(new SafetyContextEntry(
                        asset.path("refId").asText(null),
                        componentRef,
                        component == null ? null : component.bomRef(),
                        component == null ? null : component.purl(),
                        component == null ? null : component.name(),
                        component == null ? null : component.version(),
                        List.copyOf(levelJson),
                        asset.toString()));
                }
            }
        }
        return new SafetyContextParseResult(specVersion, json, List.copyOf(entries),
            List.of(), List.copyOf(warnings));
    }

    private SafetyContextParseResult invalid(String json, String error) {
        return new SafetyContextParseResult(null, json, List.of(), List.of(error), List.of());
    }

    private void collectComponents(JsonNode node,
            Map<String, List<ComponentIdentity>> byRef,
            Map<String, List<ComponentIdentity>> byBomRef) {
        if (node == null) return;
        if (node.isObject() && node.path("name").isTextual() && node.path("type").isTextual()) {
            String refId = text(node, "refId");
            String bomRef = firstText(node, "bom-ref", "bomRef");
            String purl = readPurl(node);
            if (refId != null || bomRef != null) {
                ComponentIdentity identity = new ComponentIdentity(
                    refId, bomRef, purl, text(node, "name"), text(node, "version"));
                add(byRef, refId, identity);
                add(byBomRef, bomRef, identity);
            }
        }
        if (node.isObject()) {
            node.properties().forEach(entry -> collectComponents(entry.getValue(), byRef, byBomRef));
        } else if (node.isArray()) {
            node.forEach(child -> collectComponents(child, byRef, byBomRef));
        }
    }

    private String readPurl(JsonNode component) {
        String direct = text(component, "purl");
        if (direct != null) return direct;
        JsonNode identifiers = component.path("identifiers");
        if (!identifiers.isArray()) return null;
        for (JsonNode identifier : identifiers) {
            JsonNode identities = identifier.path("identities");
            if (!identities.isArray()) continue;
            for (JsonNode identity : identities) {
                if ("purl".equals(identity.path("scheme").asText(null))) {
                    JsonNode value = identity.path("value");
                    if (value.isTextual()) return value.asText();
                    String nested = value.path("value").asText(null);
                    if (nested != null) return nested;
                }
            }
        }
        return null;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isTextual() ? value.asText() : null;
    }

    private String firstText(JsonNode node, String first, String second) {
        String value = text(node, first);
        return value != null ? value : text(node, second);
    }

    private void add(Map<String, List<ComponentIdentity>> index, String key, ComponentIdentity value) {
        if (key == null || key.isBlank()) return;
        index.computeIfAbsent(key, ignored -> new ArrayList<>()).add(value);
    }

    private ComponentIdentity unique(List<ComponentIdentity> matches) {
        return matches != null && matches.size() == 1 ? matches.get(0) : null;
    }

    private record ComponentIdentity(String refId, String bomRef, String purl, String name, String version) {}
}
