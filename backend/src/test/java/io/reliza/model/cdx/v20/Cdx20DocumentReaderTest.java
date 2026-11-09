/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;

/** {@link Cdx20DocumentReader}: what the subset reads from a 2.0 document, and what it refuses. */
class Cdx20DocumentReaderTest {

	private static Cdx20Document rearmFixture() throws RelizaException {
		return Cdx20DocumentReader.read(Cdx20TestResources.read(Cdx20TestResources.REARM_FIXTURE));
	}

	@Test
	void readsHeader() throws RelizaException {
		Cdx20Document doc = rearmFixture();
		assertEquals("CycloneDX", doc.getSpecFormat());
		assertEquals("2.0", doc.getSpecVersion());
		assertEquals("urn:uuid:5d1c7a3e-2b4f-4c6a-9e8d-1f2a3b4c5d6e", doc.getSerialNumber());
		assertEquals(1, doc.getVersion());
	}

	@Test
	void readsPurlsFromIdentifiersOnly() throws RelizaException {
		Cdx20Component firmware = rearmFixture().getDefinitions().getComponents().get(0);
		assertEquals("comp-pump-firmware", firmware.getRefId());
		assertEquals(List.of("pkg:generic/acme/acme-pump-firmware@3.2.0"), firmware.purls());
		assertEquals("acme-medical", firmware.getIdentifiers().get(0).getParty());
		Cdx20Scheme custom = firmware.getIdentifiers().get(0).getIdentities().get(1).getScheme();
		assertNull(custom.getPredefined());
		assertEquals("acme-device-catalogue", custom.getCustomName());
		assertEquals("SHA-256", firmware.getHashes().get(0).getAlg());
	}

	@Test
	void readsInventoryReferencesUnresolved() throws RelizaException {
		Cdx20Document doc = rearmFixture();
		Cdx20Inventory inv = doc.getInventories().get(0);
		assertEquals("comp-pump-firmware", inv.getSubject().getRef());
		assertEquals("comp-globex-tls", inv.getComponents().get(0).getRef());
		// two definitions, then the inventory's subject and component references
		assertEquals(4, doc.allComponents().size());
	}

	@Test
	void readsSafetyIntegrityLevels() throws RelizaException {
		Cdx20Blueprint bp = rearmFixture().getBlueprints().get(0);
		assertEquals("Therapy command path", bp.getScope().getName());
		Cdx20Asset therapy = bp.getAssets().get(0);
		assertEquals("comp-pump-firmware", therapy.getComponentRef());
		Cdx20SafetyIntegrityLevel predefined = therapy.getClassification().getSafetyIntegrityLevels().get(0);
		assertEquals(Cdx20SafetyScheme.IEC_62304, predefined.predefinedScheme());
		assertEquals("class-c", predefined.getLevel());
		Cdx20SafetyIntegrityLevel custom = bp.getAssets().get(1).getClassification().getSafetyIntegrityLevels().get(0);
		assertNull(custom.predefinedScheme());
		assertEquals("acme-hazard-tier", custom.getScheme().getCustomName());
		assertEquals("tier-2", custom.getLevel());
	}

	@Test
	void readsAssumptions() throws RelizaException {
		Cdx20Assumption a = rearmFixture().getBlueprints().get(0).getAssumptions().get(0);
		assertEquals("assume-gateway-pinned", a.getRefId());
		assertEquals(Cdx20AssumptionValidity.VERIFIED, a.getValidity());
		assertEquals(List.of("asset-tls-link"), a.getRelatedAssets());
	}

	@Test
	void readsTheUpstreamSafetyFixture() throws RelizaException {
		Cdx20Document doc = Cdx20DocumentReader.read(Cdx20TestResources.read(Cdx20TestResources.UPSTREAM_VALID_FIXTURE));
		Cdx20SafetyIntegrityLevel sil = doc.getBlueprints().get(0).getAssets().get(0)
			.getClassification().getSafetyIntegrityLevels().get(0);
		assertEquals(Cdx20SafetyScheme.IEC_62304, sil.predefinedScheme());
		assertEquals("class-c", sil.getLevel());
	}

	@Test
	void toleratesUnknownFieldsAndValuesAndBomRef() throws RelizaException {
		Cdx20Document doc = Cdx20DocumentReader.read("""
			{"specFormat": "CycloneDX", "specVersion": "2.1", "futureField": {"x": 1},
			 "definitions": {"components": [{"bom-ref": "c1", "name": "n", "newThing": true}]},
			 "blueprints": [{"name": "b", "assumptions": [{"description": "d", "validity": "disputed"}],
			   "assets": [{"classification": {"safetyIntegrityLevels": [{"scheme": "ISO-99999", "level": "x"}]}}]}]}
			""");
		assertEquals("c1", doc.getDefinitions().getComponents().get(0).getRefId());
		assertNull(doc.getBlueprints().get(0).getAssumptions().get(0).getValidity());
		Cdx20SafetyIntegrityLevel sil = doc.getBlueprints().get(0).getAssets().get(0)
			.getClassification().getSafetyIntegrityLevels().get(0);
		assertNull(sil.predefinedScheme());
		assertEquals("ISO-99999", sil.getScheme().getPredefined());
		assertTrue(doc.getDefinitions().getComponents().get(0).purls().isEmpty());
	}

	@Test
	void skipsNullEntriesAndUnusableSchemes() throws RelizaException {
		Cdx20Document doc = Cdx20DocumentReader.read("""
			{"specFormat": "CycloneDX", "specVersion": "2.0",
			 "definitions": {"components": [null, {"refId": "c1",
			   "identifiers": [{"identities": [{"scheme": 7, "value": "x"}, {"scheme": ["purl"], "value": "y"}]}]}]},
			 "inventories": [null, {"components": [null]}]}
			""");
		assertEquals(1, doc.allComponents().size());
		Cdx20Component c1 = doc.allComponents().get(0);
		assertTrue(c1.purls().isEmpty());
		Cdx20Scheme numeric = c1.getIdentifiers().get(0).getIdentities().get(0).getScheme();
		assertNull(numeric.getPredefined());
		assertNull(numeric.getCustomName());
	}

	@Test
	void refusesTooDeeplyNestedDocuments() {
		StringBuilder json = new StringBuilder("{\"specFormat\": \"CycloneDX\", \"specVersion\": \"2.0\", \"definitions\": {\"components\": [");
		int depth = 600;
		for (int i = 0; i < depth; i++) json.append("{\"components\": [");
		for (int i = 0; i < depth; i++) json.append("]}");
		json.append("]}}");
		assertThrows(RelizaException.class, () -> Cdx20DocumentReader.read(json.toString()));
	}

	@Test
	void schemeWritesBackInItsOwnShape() {
		assertEquals("purl", Cdx20Scheme.of("purl").toJson());
		assertEquals(Map.of("name", "n", "description", "d"),
			Cdx20Scheme.of(Map.of("name", "n", "description", "d")).toJson());
		assertTrue(Cdx20Scheme.of("purl").isPredefined(Cdx20Identity.PURL_SCHEME));
		assertFalse(Cdx20Scheme.of(Map.of("name", "purl")).isPredefined(Cdx20Identity.PURL_SCHEME));
	}

	@Test
	void refusesWhatIsNotACycloneDx2Document() {
		assertThrows(RelizaException.class, () -> Cdx20DocumentReader.read(""));
		assertThrows(RelizaException.class, () -> Cdx20DocumentReader.read("not json"));
		assertThrows(RelizaException.class, () -> Cdx20DocumentReader.read("[]"));
		assertThrows(RelizaException.class, () -> Cdx20DocumentReader.read("null"));
		assertThrows(RelizaException.class, () -> Cdx20DocumentReader.read("{}"));
		assertThrows(RelizaException.class, () -> Cdx20DocumentReader.read("{\"specVersion\": \"2.0\"}"));
		assertThrows(RelizaException.class,
			() -> Cdx20DocumentReader.read("{\"bomFormat\": \"CycloneDX\", \"specVersion\": \"1.6\"}"));
		assertThrows(RelizaException.class,
			() -> Cdx20DocumentReader.read("{\"specFormat\": \"CycloneDX\", \"specVersion\": \"1.6\"}"));
	}
}
