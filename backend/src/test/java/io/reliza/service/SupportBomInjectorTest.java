/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;

import org.cyclonedx.model.Bom;
import org.cyclonedx.parsers.JsonParser;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import io.reliza.common.Utils;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportSource;

/**
 * Pure behavior tests for the read-time support-property injector: matching by canonical
 * purl / cpe, recursion into nested components, append-not-overwrite of existing properties,
 * the never-emit-supportNotes invariant, never-assessed skipping, and the document-level
 * disclosure marker. No Spring / DB.
 */
class SupportBomInjectorTest {

	private static final LocalDate ASOF = LocalDate.of(2026, 6, 1);

	private SbomComponent manual(LocalDate eos, LocalDate eol, String notes) {
		SbomComponent sc = new SbomComponent();
		sc.setSupportSource(SupportSource.MANUAL);
		sc.setEndOfSupportDate(eos);
		sc.setEndOfLifeDate(eol);
		sc.setSupportNotes(notes);
		sc.setSupportLastAssessed(ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZoneOffset.UTC));
		return sc;
	}

	private JsonNode parse(String json) throws Exception {
		return Utils.OM.readTree(json);
	}

	/** Flatten a component/metadata properties array into name -> value. */
	private Map<String, String> propMap(JsonNode component) {
		Map<String, String> out = new HashMap<>();
		JsonNode props = component.get("properties");
		if (props == null || !props.isArray()) {
			return out;
		}
		for (JsonNode p : props) {
			out.put(p.get("name").asText(), p.get("value").asText());
		}
		return out;
	}

	@Test
	void appendsSupportPropertiesAndPreservesExistingAndSpecVersion() throws Exception {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.5","components":[
			  {"type":"library","name":"lib","purl":"pkg:maven/org.example/lib@1.2.3",
			   "properties":[{"name":"existing","value":"keep"}]},
			  {"type":"library","name":"other","purl":"pkg:npm/other@2.0.0"}
			]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		Map<String, SbomComponent> facts = Map.of(
				key, manual(LocalDate.of(2026, 1, 1), null, "SECRET internal note"));

		SupportBomInjector.inject(bom, facts, ASOF);

		Map<String, String> p0 = propMap(bom.get("components").get(0));
		assertEquals("keep", p0.get("existing"), "existing property preserved");
		assertEquals("MANUAL", p0.get(SupportBomInjector.PROP_SOURCE));
		assertEquals("END_OF_SUPPORT", p0.get(SupportBomInjector.PROP_STATUS)); // EOS 2026-01-01 < asOf
		assertEquals("2026-01-01", p0.get(SupportBomInjector.PROP_EOS));
		assertTrue(p0.containsKey(SupportBomInjector.PROP_LAST_ASSESSED));
		assertNull(p0.get(SupportBomInjector.PROP_EOL), "no EOL date -> no EOL property");
		// specVersion untouched (no object-model round-trip).
		assertEquals("1.5", bom.get("specVersion").asText());
		// Unmatched component gets nothing.
		assertNull(bom.get("components").get(1).get("properties"));
	}

	@Test
	void neverEmitsSupportNotes() throws Exception {
		JsonNode bom = parse("""
			{"specVersion":"1.6","components":[
			  {"name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		SupportBomInjector.inject(bom, Map.of(key, manual(null, null, "DO NOT LEAK")), ASOF);

		Map<String, String> p = propMap(bom.get("components").get(0));
		assertFalse(p.containsKey("supportNotes"));
		assertFalse(p.values().stream().anyMatch(v -> v.contains("DO NOT LEAK")),
				"internal notes must never reach the exported BOM");
		// A MANUAL row with no dates still emits status (UNKNOWN) + source.
		assertEquals("UNKNOWN", p.get(SupportBomInjector.PROP_STATUS));
		assertEquals("MANUAL", p.get(SupportBomInjector.PROP_SOURCE));
	}

	@Test
	void skipsNeverAssessedComponents() throws Exception {
		JsonNode bom = parse("""
			{"components":[{"name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		// Row present but no supportSource -> not assessed -> no properties.
		SupportBomInjector.inject(bom, Map.of(key, new SbomComponent()), ASOF);
		assertNull(bom.get("components").get(0).get("properties"));
	}

	@Test
	void recursesNestedComponents() throws Exception {
		JsonNode bom = parse("""
			{"components":[
			  {"name":"parent","purl":"pkg:maven/org.example/parent@1.0.0","components":[
			    {"name":"child","purl":"pkg:maven/org.example/child@2.0.0"}]}]}""");
		JsonNode child = bom.get("components").get(0).get("components").get(0);
		String childKey = SupportBomInjector.componentKey(child);
		SupportBomInjector.inject(bom, Map.of(childKey, manual(null, null, null)), ASOF);
		Map<String, String> pc = propMap(child);
		assertEquals("MANUAL", pc.get(SupportBomInjector.PROP_SOURCE), "nested child is injected");
	}

	@Test
	void matchesCpeOnlyComponentByCpeKey() throws Exception {
		JsonNode bom = parse("""
			{"components":[{"name":"thing","cpe":"cpe:2.3:a:vendor:thing:1.0:*:*:*:*:*:*:*"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		assertEquals("cpe:2.3:a:vendor:thing:1.0:*:*:*:*:*:*:*", key);
		SupportBomInjector.inject(bom, Map.of(key, manual(LocalDate.of(2030, 1, 1), null, null)), ASOF);
		Map<String, String> p = propMap(bom.get("components").get(0));
		assertEquals("ACTIVELY_SUPPORTED", p.get(SupportBomInjector.PROP_STATUS)); // future EOS
	}

	@Test
	void componentWithNeitherPurlNorCpeIsUnmatchable() throws Exception {
		JsonNode bom = parse("""
			{"components":[{"name":"mystery","type":"library"}]}""");
		assertNull(SupportBomInjector.componentKey(bom.get("components").get(0)));
		assertTrue(SupportBomInjector.collectKeys(bom).isEmpty());
	}

	@Test
	void stampsDocumentLevelDisclosureMarker() throws Exception {
		JsonNode bom = parse("""
			{"specVersion":"1.5","metadata":{"component":{"name":"root","purl":"pkg:oci/app@1.0.0"}},
			 "components":[]}""");
		SupportBomInjector.inject(bom, Map.of(), ASOF);
		Map<String, String> meta = propMap(bom.get("metadata"));
		assertEquals(SupportBomInjector.DISCLOSURE_CURRENT_STATE,
				meta.get(SupportBomInjector.PROP_DISCLOSURE));
	}

	private long count(ArrayNode props, String name) {
		long n = 0;
		for (JsonNode p : props) {
			if (name.equals(p.get("name").asText())) n++;
		}
		return n;
	}

	@Test
	void stripsForgedRelizaSupportOnUnmatchedComponent() throws Exception {
		JsonNode bom = parse("""
			{"components":[
			  {"name":"evil","purl":"pkg:npm/evil@1.0.0","properties":[
			    {"name":"reliza:support:source","value":"MANUAL"},
			    {"name":"reliza:support:status","value":"ACTIVELY_SUPPORTED"},
			    {"name":"keep:me","value":"ok"}]}]}""");
		// No facts -> unmatched; forged provenance must not survive.
		SupportBomInjector.inject(bom, Map.of(), ASOF);
		Map<String, String> p = propMap(bom.get("components").get(0));
		assertFalse(p.containsKey("reliza:support:source"), "forged provenance stripped");
		assertFalse(p.containsKey("reliza:support:status"), "forged status stripped");
		assertEquals("ok", p.get("keep:me"), "non-reliza properties preserved");
	}

	@Test
	void replacesForgedAndPreExistingOnMatchedComponent() throws Exception {
		JsonNode bom = parse("""
			{"components":[
			  {"name":"lib","purl":"pkg:maven/org.example/lib@1.2.3","properties":[
			    {"name":"reliza:support:source","value":"MANUAL"},
			    {"name":"cdx:lifecycle:milestone:endOfSupport","value":"2099-01-01"}]}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		SupportBomInjector.inject(bom, Map.of(key, manual(LocalDate.of(2026, 1, 1), null, null)), ASOF);
		ArrayNode props = (ArrayNode) bom.get("components").get(0).get("properties");
		assertEquals(1, count(props, SupportBomInjector.PROP_EOS), "single milestone -- forged one replaced");
		assertEquals(1, count(props, SupportBomInjector.PROP_SOURCE), "single provenance");
		Map<String, String> p = propMap(bom.get("components").get(0));
		assertEquals("2026-01-01", p.get(SupportBomInjector.PROP_EOS), "our value, not the forged 2099-01-01");
	}

	@Test
	void stampsExactlyOneDisclosureEvenIfForged() throws Exception {
		JsonNode bom = parse("""
			{"metadata":{"properties":[{"name":"reliza:support:disclosure","value":"attested-forged"}]},
			 "components":[]}""");
		SupportBomInjector.inject(bom, Map.of(), ASOF);
		ArrayNode props = (ArrayNode) bom.get("metadata").get("properties");
		assertEquals(1, count(props, SupportBomInjector.PROP_DISCLOSURE), "exactly one disclosure marker");
		Map<String, String> meta = propMap(bom.get("metadata"));
		assertEquals(SupportBomInjector.DISCLOSURE_CURRENT_STATE, meta.get(SupportBomInjector.PROP_DISCLOSURE));
	}

	@Test
	void componentKeyFallsThroughToCpeWhenPurlUnparseable() throws Exception {
		JsonNode bom = parse("""
			{"components":[{"name":"x","purl":"not-a-purl","cpe":"cpe:2.3:a:v:x:1:*:*:*:*:*:*:*"}]}""");
		assertEquals("cpe:2.3:a:v:x:1:*:*:*:*:*:*:*",
				SupportBomInjector.componentKey(bom.get("components").get(0)));
	}

	@Test
	void stripsForgedRelizaSupportEverywhereInTree() throws Exception {
		JsonNode bom = parse("""
			{"components":[
			  {"name":"c","purl":"pkg:npm/c@1.0.0","pedigree":{"variants":[
			    {"name":"variant","purl":"pkg:npm/v@1.0.0","properties":[
			      {"name":"reliza:support:source","value":"MANUAL"}]}]}}],
			 "services":[{"name":"svc","properties":[
			   {"name":"reliza:support:status","value":"ACTIVELY_SUPPORTED"}]}],
			 "metadata":{"tools":{"components":[{"name":"tool","properties":[
			   {"name":"reliza:support:source","value":"MANUAL"}]}]}}}""");
		SupportBomInjector.inject(bom, Map.of(), ASOF);
		JsonNode variant = bom.get("components").get(0).get("pedigree").get("variants").get(0);
		assertTrue(propMap(variant).keySet().stream().noneMatch(k -> k.startsWith("reliza:support:")),
				"forged prop in pedigree.variants stripped");
		JsonNode svc = bom.get("services").get(0);
		assertTrue(propMap(svc).keySet().stream().noneMatch(k -> k.startsWith("reliza:support:")),
				"forged prop in services stripped");
		JsonNode tool = bom.get("metadata").get("tools").get("components").get(0);
		assertTrue(propMap(tool).keySet().stream().noneMatch(k -> k.startsWith("reliza:support:")),
				"forged prop in metadata.tools.components stripped");
	}

	@Test
	void injectedOutputIsCycloneDxParseable() throws Exception {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.5","version":1,
			 "metadata":{"component":{"type":"application","name":"root","purl":"pkg:oci/app@1.0.0"}},
			 "components":[{"type":"library","name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		SupportBomInjector.inject(bom, Map.of(key, manual(LocalDate.of(2026, 1, 1), null, null)), ASOF);
		byte[] bytes = bom.toString().getBytes(StandardCharsets.UTF_8);
		Bom parsed = new JsonParser().parse(bytes);
		assertNotNull(parsed, "injected BOM is still CycloneDX-parseable");
		assertFalse(parsed.getComponents().isEmpty(), "components survived the injection");
	}
}
