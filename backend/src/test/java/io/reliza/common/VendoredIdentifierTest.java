/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.VendoredIdentifier.Vendored;
import io.reliza.service.SupportBomInjector;
import tools.jackson.databind.node.ObjectNode;

/** FDAW-15 row 2 (REQ-2) and FDAW-49 row 8: the minted key, its normalisation, and the one reader of a node. */
class VendoredIdentifierTest {

	private static final UUID ORG = UUID.fromString("00000000-0000-4000-8000-000000000001");
	private static final String BASE = "pkg:generic/" + ORG + "/";

	private static ObjectNode node(String name, String version) {
		ObjectNode n = Utils.OM.createObjectNode().put("type", "library");
		if (null != name) n.put("name", name);
		if (null != version) n.put("version", version);
		return n;
	}

	private static Vendored vendored(ObjectNode n) {
		return VendoredIdentifier.ofNode(ORG, n).orElseThrow();
	}

	@Test
	void theMintedKeyIsItsOwnCanonicalFormAndTheComponentKeyOfItsExportedNode() {
		for (String[] nv : Arrays.asList(new String[] {"libdosecalc", "2.3"}, new String[] {"my lib+x@y/z:w%v", "1"},
				new String[] {"pkg:npm/left-pad@1.0.0", null}, new String[] {"x", "1.2+dfsg:3"},
				new String[] {"novalue", null})) {
			String key = VendoredIdentifier.mint(ORG, nv[0], nv[1]);
			assertEquals(key, Utils.canonicalizePurlPreservingEncoding(key));
			assertEquals(key, Utils.canonicalizePurl(VendoredIdentifier.exportPurl(key)));
			assertEquals(key, SupportBomInjector.componentKey(node(nv[0], nv[1]).put("purl", VendoredIdentifier.exportPurl(key))));
			assertTrue(VendoredIdentifier.exportPurl(key).endsWith("?vendored=true"));
			assertEquals(1, VendoredIdentifier.exportPurl(key).split("vendored=true", -1).length - 1);
		}
		assertEquals(BASE + "libdosecalc@2.3", VendoredIdentifier.mint(ORG, "libdosecalc", "2.3"));
		assertEquals(BASE + "pkg%3Anpm%2Fleft-pad%401.0.0", VendoredIdentifier.mint(ORG, "pkg:npm/left-pad@1.0.0", null));
	}

	@Test
	void twoOrganisationsMintDifferentKeysAndOnlyOwnKeysAreMinted() {
		String key = VendoredIdentifier.mint(ORG, "x", "1");
		assertNotEquals(key, VendoredIdentifier.mint(UUID.randomUUID(), "x", "1"));
		assertTrue(VendoredIdentifier.isMintedKey(ORG, key));
		assertFalse(VendoredIdentifier.isMintedKey(ORG, "pkg:generic/other-org/x@1"));
		assertFalse(VendoredIdentifier.isMintedKey(ORG, "pkg:npm/x@1"));
	}

	@Test
	void mintNormalisesAndRequiresAName() {
		for (String blank : Arrays.asList(null, "", "  ")) {
			assertEquals("name is required", assertThrows(IllegalArgumentException.class,
					() -> VendoredIdentifier.mint(ORG, blank, "1")).getMessage());
		}
		assertEquals(VendoredIdentifier.mint(ORG, "libdosecalc", "2.3"), VendoredIdentifier.mint(ORG, " libdosecalc ", "2.3"));
		assertEquals(BASE + "x", VendoredIdentifier.mint(ORG, "x", null));
		assertEquals(BASE + "x", VendoredIdentifier.mint(ORG, "x", ""));
		assertEquals(BASE + "x", VendoredIdentifier.mint(ORG, "x", "  "));
	}

	@Test
	void ofNodeAnswersNothingForANodeThatIsNotKeylessOrHasNoName() {
		for (ObjectNode n : List.of(node("libdosecalc", "1").put("purl", "libdosecalc"),
				node("libdosecalc", "1").put("purl", "pkg:"),
				node("libdosecalc", "1").put("bom-ref", "pkg:npm/libdosecalc@1"),
				node("libdosecalc", "1").put("cpe", "cpe:2.3:a:x:y:1:*:*:*:*:*:*:*"),
				node(null, "1"), node("  ", "1"))) {
			assertTrue(VendoredIdentifier.ofNode(ORG, n).isEmpty(), n.toString());
		}
	}

	@Test
	void ofNodeRefusesValuesThatCannotPassTheBoundsAndNormalisesTheRest() {
		Vendored longName = vendored(node("n".repeat(513), "1"));
		assertNull(longName.key());
		assertEquals("name is longer than 512 characters", longName.refusal());
		Vendored lf = vendored(node("x", "1\n2"));
		assertNull(lf.key());
		assertEquals("version holds a control character", lf.refusal());

		Vendored v = vendored(node(" libdosecalc", ""));
		assertEquals(BASE + "libdosecalc", v.key());
		assertEquals("libdosecalc", v.name());
		assertNull(v.version());
		assertNull(v.refusal());
	}

	@Test
	void aFormatCharacterInANodesNameOrVersionIsRefusedAndANoBreakSpaceMints() {
		Vendored name = vendored(node("lib\u200Bdose", "1"));
		assertNull(name.key());
		assertEquals("name holds a control character", name.refusal());
		Vendored version = vendored(node("lib", "1.0\u00AD"));
		assertNull(version.key());
		assertEquals("version holds a control character", version.refusal());
		Vendored nbsp = vendored(node("lib\u00A0dose", "1"));
		assertNull(nbsp.refusal());
		assertEquals(VendoredIdentifier.mint(ORG, "lib\u00A0dose", "1"), nbsp.key());
	}
}
