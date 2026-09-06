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
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

import org.cyclonedx.model.Bom;
import org.cyclonedx.parsers.JsonParser;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;

import io.reliza.common.Utils;
import io.reliza.model.DeviceLifecycle;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportData;
import io.reliza.model.SupportMilestoneFact;
import io.reliza.model.SupportMilestoneType;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;
import io.reliza.model.SupportStatus;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;

/**
 * Pure behavior tests for the read-time support-property injector: matching by canonical
 * purl / cpe, recursion into nested components, append-not-overwrite of existing properties,
 * the never-emit-notes invariant, never-assessed skipping, and the document-level disclosure
 * marker. No Spring / DB.
 */
class SupportBomInjectorTest {

	private static final LocalDate ASOF = LocalDate.of(2026, 6, 1);
	private static final ZonedDateTime LAST_ASSESSED = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZoneOffset.UTC);

	private static final String ASSESSED_AT = LAST_ASSESSED.toInstant().toString();

	private SupportMilestoneFact milestone(LocalDate date, SupportSource source, String notes) {
		return new SupportMilestoneFact(date.toString(), source, ASSESSED_AT, null, notes);
	}

	/** MANUAL facts with the given EOS/EOL dates (null omits that milestone entirely). */
	private ComponentSupportFacts manual(LocalDate eos, LocalDate eol, String notes) {
		return manual(eos, eol, notes, null, SupportState.ATTESTED);
	}

	private ComponentSupportFacts manual(LocalDate eos, LocalDate eol, String notes,
			LevelOfSupport attestedLevel, SupportState state) {
		Map<SupportMilestoneType, SupportMilestoneFact> milestones = new EnumMap<>(SupportMilestoneType.class);
		if (eos != null) milestones.put(SupportMilestoneType.END_OF_SUPPORT,
				milestone(eos, SupportSource.MANUAL, notes));
		if (eol != null) milestones.put(SupportMilestoneType.END_OF_LIFE,
				milestone(eol, SupportSource.MANUAL, notes));
		SupportData data = new SupportData(attestedLevel, state, null, SupportSource.MANUAL,
				ASSESSED_AT, null, null, milestones);
		return new ComponentSupportFacts(new SbomComponent(), data);
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
		Map<String, ComponentSupportFacts> facts = Map.of(
				key, manual(LocalDate.of(2026, 1, 1), null, "SECRET internal note"));

		SupportBomInjector.inject(bom, facts, ASOF);

		Map<String, String> p0 = propMap(bom.get("components").get(0));
		assertEquals("keep", p0.get("existing"), "existing property preserved");
		assertEquals("MANUAL", p0.get(SupportBomInjector.PROP_SOURCE_PREFIX + "endOfSupport"));
		assertEquals("END_OF_SUPPORT", p0.get(SupportBomInjector.PROP_STATUS)); // EOS 2026-01-01 < asOf
		assertEquals("2026-01-01", p0.get(SupportBomInjector.PROP_EOS));
		assertTrue(p0.containsKey(SupportBomInjector.PROP_LAST_ASSESSED_PREFIX + "endOfSupport"));
		assertNull(p0.get(SupportBomInjector.PROP_EOL), "no EOL date -> no EOL property");
		// specVersion untouched (no object-model round-trip).
		assertEquals("1.5", bom.get("specVersion").asText());
		// Unmatched component gets nothing.
		assertNull(bom.get("components").get(1).get("properties"));
	}

	@Test
	void neverEmitsNotes() throws Exception {
		JsonNode bom = parse("""
			{"specVersion":"1.6","components":[
			  {"name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		SupportBomInjector.inject(bom,
				Map.of(key, manual(LocalDate.of(2030, 1, 1), null, "DO NOT LEAK")), ASOF);

		Map<String, String> p = propMap(bom.get("components").get(0));
		assertFalse(p.containsKey("notes"));
		assertFalse(p.values().stream().anyMatch(v -> v.contains("DO NOT LEAK")),
				"internal notes must never reach the exported BOM");
		// A future EOS derives UNKNOWN: a declared horizon is not evidence of current
		// maintenance. ACTIVELY_SUPPORTED is now attested, never derived.
		assertEquals("UNKNOWN", p.get(SupportBomInjector.PROP_STATUS));
		assertEquals("MANUAL", p.get(SupportBomInjector.PROP_SOURCE_PREFIX + "endOfSupport"));
	}

	@Test
	void skipsNeverAssessedComponents() throws Exception {
		JsonNode bom = parse("""
			{"components":[{"name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		// Component row resolved but carrying NO attestation -> not assessed -> no properties.
		SupportBomInjector.inject(bom,
				Map.of(key, new ComponentSupportFacts(new SbomComponent(), null)), ASOF);
		assertNull(bom.get("components").get(0).get("properties"),
				"absence of properties must keep meaning 'never assessed' and nothing else");
	}

	/**
	 * The V83 capability: an attestation with NO dates but an attested level DOES export.
	 * "I looked, nothing is published, and I judge it actively supported" is a disclosure --
	 * the old shape could not even store it, let alone emit it.
	 */
	@Test
	void emitsAttestedLevelForAnAttestationCarryingNoDates() throws Exception {
		JsonNode bom = parse("""
			{"components":[{"name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		SupportBomInjector.inject(bom, Map.of(key,
				manual(null, null, null, LevelOfSupport.ACTIVELY_MAINTAINED, SupportState.ATTESTED)), ASOF);

		Map<String, String> props = propMap(bom.get("components").get(0));
		// FDA's phrase verbatim, not the constant name -- see LevelOfSupport.getWireValue.
		assertEquals("actively maintained", props.get(SupportBomInjector.PROP_LEVEL));
		// Never bare: the claim carries its own date so a consumer can weigh its age.
		assertEquals(ASSESSED_AT, props.get(SupportBomInjector.PROP_ASSESSED_AT));
		// The DERIVED status still reports UNKNOWN, and the two are deliberately not
		// reconciled -- a reader sees the human's claim and what the dates support.
		assertEquals("UNKNOWN", props.get(SupportBomInjector.PROP_STATUS));
	}

	/**
	 * REGRESSION (#5): "assessed, nothing published" -- no dates, no level -- MUST emit its
	 * assessment instant. The coverage query counts such a row as attested, so emitting
	 * nothing made the readiness gauge report coverage the served BOM did not contain. The
	 * export and countAttestedNonRootByOrg have to agree on what counts as a disclosure.
	 */
	@Test
	void emitsAssessedAtForAnAttestationWithNoDatesAndNoLevel() throws Exception {
		JsonNode bom = parse("""
			{"components":[{"name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		SupportData assessedOnly = new SupportData(null, SupportState.ATTESTED, null,
				SupportSource.MANUAL, ASSESSED_AT, null,
				"checked upstream; no EOS is published", Map.of());
		SupportBomInjector.inject(bom,
				Map.of(key, new ComponentSupportFacts(new SbomComponent(), assessedOnly)), ASOF);

		Map<String, String> props = propMap(bom.get("components").get(0));
		assertEquals(ASSESSED_AT, props.get(SupportBomInjector.PROP_ASSESSED_AT),
				"the instant alone is the disclosure: a human looked, and when");
		assertFalse(props.containsKey(SupportBomInjector.PROP_LEVEL), "no level was attested");
		// The basis IS the disclosure for this case: with no dates and no level, "I checked
		// and upstream publishes nothing" is the entire content of the attestation. It was
		// internal until the justification export landed, which made this row exportable at
		// all rather than countable-but-invisible.
		assertEquals("checked upstream; no EOS is published",
				props.get(SupportBomInjector.PROP_JUSTIFICATION));
	}

	/**
	 * The basis ships with the claim. A negative level cannot be recorded without a
	 * justification, so exporting the claim while keeping the basis internal would publish
	 * the accusation and withhold the evidence.
	 */
	@Test
	void emitsTheJustificationAlongsideTheClaim() throws Exception {
		JsonNode bom = parse("""
			{"components":[{"name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		SupportData attested = new SupportData(LevelOfSupport.ABANDONED, SupportState.ATTESTED,
				null, SupportSource.MANUAL, ASSESSED_AT, null,
				"upstream archived the repository on 2026-04-02", Map.of());
		SupportBomInjector.inject(bom,
				Map.of(key, new ComponentSupportFacts(new SbomComponent(), attested)), ASOF);

		Map<String, String> props = propMap(bom.get("components").get(0));
		assertEquals("abandoned", props.get(SupportBomInjector.PROP_LEVEL));
		assertEquals("upstream archived the repository on 2026-04-02",
				props.get(SupportBomInjector.PROP_JUSTIFICATION));
	}

	/** A retracted claim takes its basis with it -- neither is republished. */
	@Test
	void aWithdrawnAttestationExportsNoJustificationEither() throws Exception {
		JsonNode bom = parse("""
			{"components":[{"name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		SupportData withdrawn = new SupportData(LevelOfSupport.ABANDONED, SupportState.WITHDRAWN,
				null, SupportSource.MANUAL, ASSESSED_AT, null, "asserted in error", Map.of());
		SupportBomInjector.inject(bom,
				Map.of(key, new ComponentSupportFacts(new SbomComponent(), withdrawn)), ASOF);
		assertNull(bom.get("components").get(0).get("properties"));
	}

	/** A WITHDRAWN attestation is retracted: it must not be re-published as a live claim. */
	@Test
	void withdrawnAttestationEmitsNothing() throws Exception {
		JsonNode bom = parse("""
			{"components":[{"name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		SupportBomInjector.inject(bom, Map.of(key,
				manual(LocalDate.of(2026, 1, 1), null, null,
						LevelOfSupport.NO_LONGER_MAINTAINED, SupportState.WITHDRAWN)), ASOF);
		assertNull(bom.get("components").get(0).get("properties"),
				"a retracted attestation must not reappear in an export");
	}

	@Test
	void recursesNestedComponents() throws Exception {
		JsonNode bom = parse("""
			{"components":[
			  {"name":"parent","purl":"pkg:maven/org.example/parent@1.0.0","components":[
			    {"name":"child","purl":"pkg:maven/org.example/child@2.0.0"}]}]}""");
		JsonNode child = bom.get("components").get(0).get("components").get(0);
		String childKey = SupportBomInjector.componentKey(child);
		SupportBomInjector.inject(bom,
				Map.of(childKey, manual(LocalDate.of(2026, 6, 1), null, null)), ASOF);
		Map<String, String> pc = propMap(child);
		assertEquals("MANUAL", pc.get(SupportBomInjector.PROP_SOURCE_PREFIX + "endOfSupport"), "nested child is injected");
	}

	@Test
	void matchesCpeOnlyComponentByCpeKey() throws Exception {
		JsonNode bom = parse("""
			{"components":[{"name":"thing","cpe":"cpe:2.3:a:vendor:thing:1.0:*:*:*:*:*:*:*"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		assertEquals("cpe:2.3:a:vendor:thing:1.0:*:*:*:*:*:*:*", key);
		SupportBomInjector.inject(bom, Map.of(key, manual(LocalDate.of(2030, 1, 1), null, null)), ASOF);
		Map<String, String> p = propMap(bom.get("components").get(0));
		assertEquals("UNKNOWN", p.get(SupportBomInjector.PROP_STATUS)); // future EOS derives nothing
	}

	@Test
	void stampsDiscriminatedDeviceSupportRiskAndDeviceAnchorForProductDownload() throws Exception {
		JsonNode bom = parse("""
			{"bomFormat":"CycloneDX","specVersion":"1.5",
			 "metadata":{"component":{"type":"application","name":"device","purl":"pkg:generic/device@1.0"}},
			 "components":[
			   {"name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"},
			   {"name":"ok","purl":"pkg:npm/ok@2.0.0"}]}""");
		String atRiskKey = SupportBomInjector.componentKey(bom.get("components").get(0));
		String okKey = SupportBomInjector.componentKey(bom.get("components").get(1));
		// Device support horizon = EOS 2030-01-01. lib EOS 2026 -> before -> EOS_BEFORE_DEVICE;
		// ok EOS 2031 -> after -> OK (both are ASSESSED, so both get the enum).
		SupportBomInjector.inject(bom, Map.of(
				atRiskKey, manual(LocalDate.of(2026, 1, 1), null, null),
				okKey, manual(LocalDate.of(2031, 1, 1), null, null)),
				ASOF, new DeviceLifecycle(LocalDate.of(2030, 1, 1), null));

		assertEquals("EOS_BEFORE_DEVICE",
				propMap(bom.get("components").get(0)).get(SupportBomInjector.PROP_DEVICE_SUPPORT_RISK));
		assertEquals("OK",
				propMap(bom.get("components").get(1)).get(SupportBomInjector.PROP_DEVICE_SUPPORT_RISK),
				"assessed-but-OK component still carries the verdict (absence must mean only not-assessed)");
		// Device's own support window stamped on metadata.component so the verdict is re-checkable.
		Map<String, String> dev = propMap(bom.get("metadata").get("component"));
		assertEquals("2030-01-01", dev.get(SupportBomInjector.PROP_DEVICE_EOS));
	}

	@Test
	void omitsDeviceSupportRiskWhenNoDeviceContext() throws Exception {
		JsonNode bom = parse("""
			{"components":[{"name":"lib","purl":"pkg:maven/org.example/lib@1.2.3"}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		// Null device (non-PRODUCT release / ambiguous) -> no verdict, but dates still injected.
		SupportBomInjector.inject(bom, Map.of(key, manual(LocalDate.of(2026, 1, 1), null, null)), ASOF, null);
		Map<String, String> p = propMap(bom.get("components").get(0));
		assertFalse(p.containsKey(SupportBomInjector.PROP_DEVICE_SUPPORT_RISK), "null device -> no verdict");
		assertEquals("2026-01-01", p.get(SupportBomInjector.PROP_EOS), "component dates still injected");
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
			    {"name":"reliza:support:source:endOfSupport","value":"MANUAL"},
			    {"name":"reliza:support:status","value":"ACTIVELY_SUPPORTED"},
			    {"name":"keep:me","value":"ok"}]}]}""");
		// No facts -> unmatched; forged provenance must not survive.
		SupportBomInjector.inject(bom, Map.of(), ASOF);
		Map<String, String> p = propMap(bom.get("components").get(0));
		assertFalse(p.containsKey("reliza:support:source:endOfSupport"), "forged provenance stripped");
		assertFalse(p.containsKey("reliza:support:status"), "forged status stripped");
		assertEquals("ok", p.get("keep:me"), "non-reliza properties preserved");
	}

	@Test
	void replacesForgedAndPreExistingOnMatchedComponent() throws Exception {
		JsonNode bom = parse("""
			{"components":[
			  {"name":"lib","purl":"pkg:maven/org.example/lib@1.2.3","properties":[
			    {"name":"reliza:support:source:endOfSupport","value":"MANUAL"},
			    {"name":"cdx:lifecycle:milestone:endOfSupport","value":"2099-01-01"}]}]}""");
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));
		SupportBomInjector.inject(bom, Map.of(key, manual(LocalDate.of(2026, 1, 1), null, null)), ASOF);
		ArrayNode props = (ArrayNode) bom.get("components").get(0).get("properties");
		assertEquals(1, count(props, SupportBomInjector.PROP_EOS), "single milestone -- forged one replaced");
		assertEquals(1, count(props, SupportBomInjector.PROP_SOURCE_PREFIX + "endOfSupport"), "single provenance");
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
			      {"name":"reliza:support:source:endOfSupport","value":"MANUAL"}]}]}}],
			 "services":[{"name":"svc","properties":[
			   {"name":"reliza:support:status","value":"ACTIVELY_SUPPORTED"}]}],
			 "metadata":{"tools":{"components":[{"name":"tool","properties":[
			   {"name":"reliza:support:source:endOfSupport","value":"MANUAL"}]}]}}}""");
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

	/**
	 * The two markers must not be interchangeable. An injected document says "these support
	 * properties were derived at serve time", so a component with none has no recorded date.
	 * A swept-only document asserts nothing at all -- and the components it says nothing about
	 * may well have an end-of-support date on file. Reading the second as the first is how a
	 * reviewer concludes a device has no out-of-support parts when it does.
	 */
	@Test
	void stripOnlyRemovesForgedPropertiesAndMarksItselfAsNotADisclosure() throws Exception {
		JsonNode bom = parse("""
				{"bomFormat":"CycloneDX","specVersion":"1.6",
				 "metadata":{"properties":[
				   {"name":"reliza:support:disclosure","value":"derived-non-attested-current-state"}]},
				 "components":[{"name":"c","purl":"pkg:maven/ex/c@1.0.0","properties":[
				   {"name":"reliza:support:levelOfSupport","value":"actively maintained"},
				   {"name":"keep:me","value":"yes"}]}]}""");

		SupportBomInjector.stripOnly(bom);
		String served = bom.toString();

		assertFalse(served.contains("actively maintained"), "forged level survived stripOnly");
		assertTrue(served.contains("keep:me"), "stripOnly is not a general property purge");
		Map<String, String> meta = propMap(bom.get("metadata"));
		assertEquals(SupportBomInjector.DISCLOSURE_STRIPPED_ONLY,
				meta.get(SupportBomInjector.PROP_DISCLOSURE),
				"stripOnly must not claim a current-state disclosure it did not perform --"
						+ " an uploader's forged current-state marker was also replaced, not kept");
	}

	/** A document with no metadata at all still gets marked, rather than silently unswept. */
	@Test
	void stripOnlyCreatesMetadataWhenTheBomHasNone() throws Exception {
		JsonNode bom = parse(
				"{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":[]}");
		SupportBomInjector.stripOnly(bom);
		assertEquals(SupportBomInjector.DISCLOSURE_STRIPPED_ONLY,
				propMap(bom.get("metadata")).get(SupportBomInjector.PROP_DISCLOSURE));
	}
}
