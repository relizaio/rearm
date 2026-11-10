/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.common.VendoredIdentifier;
import io.reliza.model.OverlayKind;
import io.reliza.model.SbomComponentOverlay;
import io.reliza.model.SbomComponentOverlayData;
import io.reliza.service.OverlayBomInjector.Overlaid;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** FDAW-15 row 4 (REQ-4): the overlay injector's key and fill rules, over two maps. No Spring, no database. */
class OverlayBomInjectorTest {

	private static final UUID ORG = UUID.fromString("00000000-0000-4000-8000-000000000002");
	private static final String KEY = VendoredIdentifier.mint(ORG, "libdosecalc", "2.3");

	private final Map<String, SbomComponentOverlay> rows = new LinkedHashMap<>();
	private final Map<String, String> rowKeys = new HashMap<>();
	private final List<Set<String>> lookups = new ArrayList<>();

	private static SbomComponentOverlay overlay(OverlayKind kind, String supplier, String licence, String description) {
		SbomComponentOverlay o = new SbomComponentOverlay();
		o.setKind(kind);
		o.setRecordData(new SbomComponentOverlayData(supplier, licence, description, "origin stays here", null, null, null,
				null, null));
		return o;
	}

	private static ObjectNode bom() {
		ObjectNode bom = Utils.OM.createObjectNode().put("bomFormat", "CycloneDX");
		bom.putObject("metadata").putObject("component").put("name", "libdosecalc").put("version", "2.3");
		bom.putArray("components");
		return bom;
	}

	private static ObjectNode add(ObjectNode bom, String name, String version) {
		ObjectNode n = ((ArrayNode) bom.get("components")).addObject().put("type", "library").put("name", name);
		if (null != version) n.put("version", version);
		return n;
	}

	private Overlaid apply(JsonNode bom) {
		Function<String, String> rowKeyOf = k -> rowKeys.getOrDefault(k, k);
		return OverlayBomInjector.apply(bom, ORG, rowKeyOf, keys -> {
			lookups.add(keys);
			Map<String, SbomComponentOverlay> found = new HashMap<>();
			keys.forEach(k -> {
				if (rows.containsKey(k)) found.put(k, rows.get(k));
			});
			return found;
		});
	}

	@Test
	void aDescribedKeylessNodeIsKeyedAndFilledAndItsBomRefLeftAlone() {
		rows.put(KEY, overlay(OverlayKind.VENDORED, "Acme", "MIT", "dose calculation"));
		ObjectNode bom = bom();
		ObjectNode n = add(bom, "libdosecalc", "2.3").put("bom-ref", "assigned-1");

		assertEquals(new Overlaid(1, 1, 3), apply(bom));

		assertEquals(VendoredIdentifier.exportPurl(KEY), n.get("purl").asText());
		assertEquals(KEY, SupportBomInjector.componentKey(n));
		assertEquals("Acme", n.get("supplier").get("name").asText());
		assertEquals("MIT", n.get("licenses").get(0).get("license").get("id").asText());
		assertEquals(1, n.get("licenses").size());
		assertEquals("dose calculation", n.get("description").asText());
		assertEquals("assigned-1", n.get("bom-ref").asText());
		assertFalse(bom.toString().contains("reliza:"));
		assertFalse(bom.toString().contains("origin stays here"));
	}

	@Test
	void aLicenceThatIsNotOneListedIdIsAnExpression() {
		String second = VendoredIdentifier.mint(ORG, "b", "1");
		rows.put(KEY, overlay(OverlayKind.VENDORED, null, "MIT OR Apache-2.0", null));
		rows.put(second, overlay(OverlayKind.VENDORED, null, "LicenseRef-Acme", null));
		ObjectNode bom = bom();
		ObjectNode a = add(bom, "libdosecalc", "2.3");
		ObjectNode b = add(bom, "b", "1");
		apply(bom);
		assertEquals("MIT OR Apache-2.0", a.get("licenses").get(0).get("expression").asText());
		assertEquals("LicenseRef-Acme", b.get("licenses").get(0).get("expression").asText());
	}

	@Test
	void aNodeIsMatchedByItsNormalisedNameAndVersion() {
		String key = VendoredIdentifier.mint(ORG, "libdosecalc", null);
		rows.put(key, overlay(OverlayKind.VENDORED, "Acme", null, null));
		ObjectNode bom = bom();
		ObjectNode n = add(bom, " libdosecalc", "");
		apply(bom);
		assertEquals(VendoredIdentifier.exportPurl(key), n.get("purl").asText());
	}

	@Test
	void aKeylessNodeWithoutAVendoredRowIsUntouched() {
		String other = VendoredIdentifier.mint(ORG, "componentrow", "1");
		rows.put(other, overlay(OverlayKind.COMPONENT, "Acme", "MIT", null));
		ObjectNode bom = bom();
		ObjectNode none = add(bom, "norow", "1");
		ObjectNode component = add(bom, "componentrow", "1");
		String before = bom.toString();
		assertEquals(new Overlaid(2, 0, 0), apply(bom));
		assertEquals(before, bom.toString());
		assertNull(none.get("purl"));
		assertNull(component.get("purl"));
	}

	@Test
	void aNodeWithAnUploadedUnparseablePurlIsNeverKeyedOrFilled() {
		rows.put(KEY, overlay(OverlayKind.VENDORED, "Acme", "MIT", "d"));
		ObjectNode bom = bom();
		add(bom, "libdosecalc", "2.3").put("purl", "libdosecalc");
		add(bom, "libdosecalc", "2.3").put("purl", "pkg:");
		String before = bom.toString();
		assertEquals(new Overlaid(2, 0, 0), apply(bom));
		assertEquals(before, bom.toString());
	}

	@Test
	void aKeyedNodeReadsTheChosenRowsOverlayAndTheLookupSeesOnlyMappedKeys() {
		String plus = "pkg:deb/debian/libfoo@1.2+dfsg-1";
		String encoded = "pkg:deb/debian/libfoo@1.2%2Bdfsg-1";
		String spaced = "pkg:generic/acme/my%20lib@1";
		String legacy = "pkg:generic/acme/my%2520lib@1";
		rowKeys.put(plus, encoded);
		rowKeys.put(spaced, legacy);
		rows.put(encoded, overlay(OverlayKind.COMPONENT, "Debian", null, null));
		rows.put(legacy, overlay(OverlayKind.COMPONENT, "Legacy", null, null));
		ObjectNode bom = bom();
		ObjectNode a = add(bom, "libfoo", "1.2+dfsg-1").put("purl", plus);
		ObjectNode b = add(bom, "my lib", "1").put("purl", spaced);

		assertEquals(new Overlaid(2, 0, 2), apply(bom));

		assertEquals("Debian", a.get("supplier").get("name").asText());
		assertEquals("Legacy", b.get("supplier").get("name").asText());
		assertEquals(plus, a.get("purl").asText());
		assertEquals(1, lookups.size());
		assertEquals(Set.of(encoded, legacy), lookups.get(0));
	}

	@Test
	void aKeyedNodeHasItsEmptyFieldsFilledAndNoUploadedValueReplaced() {
		String key = "pkg:npm/x@1";
		rows.put(key, overlay(OverlayKind.COMPONENT, "Acme", "MIT", "d"));
		ObjectNode bom = bom();
		ObjectNode empty = add(bom, "x", "1").put("purl", key).put("description", " ");
		empty.putObject("supplier").put("name", " ");
		empty.putArray("licenses");
		ObjectNode named = add(bom, "x", "1").put("purl", key).put("description", "uploaded");
		named.putObject("supplier").put("name", "Uploaded");
		named.putArray("licenses").addObject().putObject("license").put("id", "Apache-2.0");
		ObjectNode urlOnly = add(bom, "x", "1").put("purl", key).put("description", "uploaded");
		urlOnly.putObject("supplier").putArray("url").add("https://uploaded.example");
		urlOnly.putArray("licenses").addObject().put("expression", "GPL-2.0-only");
		String namedBefore = named.toString();
		String urlOnlyBefore = urlOnly.toString();

		assertEquals(new Overlaid(3, 0, 3), apply(bom));

		assertEquals("Acme", empty.get("supplier").get("name").asText());
		assertEquals("MIT", empty.get("licenses").get(0).get("license").get("id").asText());
		assertEquals("d", empty.get("description").asText());
		assertEquals(key, empty.get("purl").asText());
		assertEquals(namedBefore, named.toString());
		assertEquals(urlOnlyBefore, urlOnly.toString());
	}

	@Test
	void theReleasesOwnNodeAndAMissingSbomNodeAreNeverVisitedAndNestedNodesAre() {
		rows.put(KEY, overlay(OverlayKind.VENDORED, "Acme", null, null));
		ObjectNode bom = bom();
		ObjectNode missing = add(bom, "libdosecalc", "2.3");
		missing.putArray("properties").addObject().put("name", "reliza:sbom:missing").put("value", "true");
		ObjectNode parent = add(bom, "parent", "1");
		ObjectNode nested = parent.putArray("components").addObject().put("name", "libdosecalc").put("version", "2.3");

		assertEquals(new Overlaid(2, 1, 1), apply(bom));

		assertNull(bom.get("metadata").get("component").get("purl"));
		assertNull(missing.get("purl"));
		assertEquals(VendoredIdentifier.exportPurl(KEY), nested.get("purl").asText());
	}

	@Test
	void twoNodesOfOneNameAndVersionAreBothFilledAndANamelessNodeIsSkipped() {
		rows.put(KEY, overlay(OverlayKind.VENDORED, "Acme", null, null));
		ObjectNode bom = bom();
		ObjectNode a = add(bom, "libdosecalc", "2.3");
		ObjectNode b = add(bom, "libdosecalc", "2.3");
		ObjectNode nameless = ((ArrayNode) bom.get("components")).addObject().put("type", "library");

		assertEquals(new Overlaid(3, 2, 2), apply(bom));

		assertEquals(a.get("purl"), b.get("purl"));
		assertEquals(1, nameless.size());
		assertEquals(1, lookups.size());
		assertEquals(Set.of(KEY), lookups.get(0));
	}

	@Test
	void anUploadedNodeCarryingTheExportPurlKeepsItsOwnSupplier() {
		rows.put(KEY, overlay(OverlayKind.VENDORED, "Acme", null, null));
		ObjectNode bom = bom();
		ObjectNode n = add(bom, "libdosecalc", "2.3").put("purl", VendoredIdentifier.exportPurl(KEY));
		n.putObject("supplier").put("name", "Uploaded");
		String before = n.toString();
		assertEquals(new Overlaid(1, 0, 0), apply(bom));
		assertEquals(before, n.toString());
		assertTrue(lookups.get(0).contains(KEY));
	}
}
