/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import io.reliza.common.Utils;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportData;
import io.reliza.model.SupportParty;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;

/**
 * The document-level counts a disclosed document carries (SCORE-21, SCORE-12 ADR-7):
 * {@code reliza:support:unidentifiedComponents} and {@code reliza:support:unassessedComponents},
 * and since SCORE-58 ADR-8 (built as SCORE-60, T-13) {@code reliza:support:selfComponents}.
 * Test T-10. Names are the design's literal text, not the production constants. Pure, no Spring
 * / DB.
 */
class SupportBomInjectorCountsTest {

	private static final LocalDate ASOF = LocalDate.of(2026, 6, 1);
	private static final String UNIDENTIFIED = "reliza:support:unidentifiedComponents";
	private static final String UNASSESSED = "reliza:support:unassessedComponents";
	private static final String SELF_COUNT = "reliza:support:selfComponents";
	private static final String SELF = "reliza:support:selfComponent";
	private static final String STAMP = "reliza:export:releaseNode";

	private static SupportData attested(SupportState state) {
		return new SupportData(LevelOfSupport.ACTIVELY_MAINTAINED, state, SupportParty.THIRD_PARTY,
				SupportSource.MANUAL, "2026-08-26T12:00:00Z", null, null, Map.of());
	}

	private static ComponentSupportFacts facts(SupportData support) {
		return new ComponentSupportFacts(new SbomComponent(), support);
	}

	private static ComponentSupportFacts rootFacts(SupportData support) {
		SbomComponent sc = new SbomComponent();
		sc.setRecordData(Map.of("isRoot", true));
		return new ComponentSupportFacts(sc, support);
	}

	/** Every value of a property name on metadata.properties, in document order. */
	private static List<String> metaValues(JsonNode bom, String name) {
		List<String> out = new ArrayList<>();
		JsonNode props = bom.get("metadata").get("properties");
		if (props != null) {
			props.forEach(p -> {
				if (name.equals(p.get("name").asText())) out.add(p.get("value").asText());
			});
		}
		return out;
	}

	/** Every property name on metadata.properties, in document order. */
	private static List<String> metaNames(JsonNode bom) {
		List<String> out = new ArrayList<>();
		bom.get("metadata").get("properties").forEach(p -> out.add(p.get("name").asText()));
		return out;
	}

	/** Every property name anywhere in the tree that starts with the given prefix. */
	private static List<String> namesEverywhere(JsonNode node, String prefix) {
		List<String> out = new ArrayList<>();
		if (node.isObject()) {
			JsonNode name = node.get("name");
			if (name != null && name.isTextual() && name.asText().startsWith(prefix) && node.has("value")) {
				out.add(name.asText());
			}
		}
		node.forEach(child -> out.addAll(namesEverywhere(child, prefix)));
		return out;
	}

	/**
	 * 8 nodes with no key -- in components[], nested components[].components[], metadata.component
	 * and its children, one of them with a bom-ref that is not a purl -- one node keyed only by its
	 * bom-ref purl and not assessed, one assessed. Forged counts on input, at document level and on
	 * a component.
	 */
	private static JsonNode countingBom() {
		return Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "metadata":{
			   "component":{"type":"application","name":"root",
			     "components":[{"type":"file","name":"root-child"}]},
			   "properties":[
			     {"name":"reliza:support:unidentifiedComponents","value":"999"},
			     {"name":"reliza:support:unassessedComponents","value":"999"}]},
			 "components":[
			   {"type":"cryptographic-asset","name":"key-1"},
			   {"type":"cryptographic-asset","name":"key-2","bom-ref":"comp-12"},
			   {"type":"library","name":"assessed","purl":"pkg:npm/assessed@1.0.0",
			    "components":[
			      {"type":"file","name":"nested-1"},
			      {"type":"file","name":"nested-2",
			       "components":[{"type":"data","name":"nested-deep"}]}]},
			   {"type":"library","name":"stdlib","bom-ref":"pkg:golang/stdlib@v1.26.6",
			    "properties":[{"name":"reliza:support:unassessedComponents","value":"7"}]},
			   {"type":"file","name":"last"}]}""");
	}

	// T-10
	@Test
	void countsUnidentifiedAndUnassessed() {
		JsonNode bom = countingBom();
		SupportBomInjector.inject(bom, Map.of("pkg:npm/assessed@1.0.0", facts(attested(SupportState.ATTESTED))), ASOF);

		assertEquals(List.of("8"), metaValues(bom, UNIDENTIFIED),
				"8 keyless nodes, the bom-ref purl node is identified, forged value replaced: " + bom);
		assertEquals(List.of("1"), metaValues(bom, UNASSESSED),
				"the bom-ref purl node with no facts is the one unassessed: " + bom);
		assertEquals(List.of("0"), metaValues(bom, SELF_COUNT), "no self node: " + bom);
		assertEquals(List.of(UNIDENTIFIED, UNASSESSED),
				namesEverywhere(bom, "reliza:support:u"),
				"exactly one of each, on metadata.properties only (the forged component one is gone)");
		assertEquals(List.of(UNIDENTIFIED, UNASSESSED, SELF_COUNT),
				metaNames(bom).stream().filter(n -> n.endsWith("Components")).toList(),
				"the self count is written once, after the other two");
	}

	// T-13
	@Test
	void countsSelfNodesInNeitherOtherCount() {
		JsonNode bom = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "metadata":{"properties":[{"name":"reliza:support:selfComponents","value":"99"}]},
			 "components":[
			  {"type":"application","name":"own-root","purl":"pkg:generic/acme/own-root@1.0.0"},
			  {"type":"application","name":"stamped","purl":"pkg:generic/acme/stamped@2.0.0",
			   "properties":[{"name":"reliza:export:releaseNode","value":"true"}]},
			  {"type":"application","name":"own-attested","purl":"pkg:generic/acme/own-attested@3.0.0"},
			  {"type":"library","name":"dep","purl":"pkg:npm/dep@1.0.0"}]}""");
		Map<String, ComponentSupportFacts> byKey = new HashMap<>();
		byKey.put("pkg:generic/acme/own-root@1.0.0", rootFacts(null));
		byKey.put("pkg:generic/acme/own-attested@3.0.0", rootFacts(attested(SupportState.ATTESTED)));
		SupportBomInjector.inject(bom, byKey, ASOF);

		assertEquals(List.of("3"), metaValues(bom, SELF_COUNT),
				"own-root (rule 1), stamped (rule 3) and own-attested (rule 1, with a statement); forged value replaced: "
				+ bom);
		assertEquals(List.of("1"), metaValues(bom, UNASSESSED),
				"only the third-party dep: a self node is not unassessed, with or without a statement: " + bom);
		assertEquals(List.of("0"), metaValues(bom, UNIDENTIFIED));
	}

	// T-13
	@Test
	void countsTwoSelfNodesOneByRowOneByStamp() {
		JsonNode bom = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","components":[
			  {"type":"application","name":"a","purl":"pkg:generic/acme/a@1.0.0"},
			  {"type":"application","name":"b","purl":"pkg:generic/acme/b@1.0.0",
			   "properties":[{"name":"reliza:export:releaseNode","value":"true"}]}]}""");
		SupportBomInjector.inject(bom, Map.of("pkg:generic/acme/a@1.0.0", rootFacts(null)), ASOF);

		assertEquals(List.of("2"), metaValues(bom, SELF_COUNT));
		assertEquals(List.of("0"), metaValues(bom, UNASSESSED));
	}

	// T-10
	@Test
	void writesZeroCountsOnceWhenEveryNodeIsAssessed() {
		JsonNode bom = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.5","components":[
			  {"type":"library","name":"a","purl":"pkg:npm/a@1.0.0"},
			  {"type":"library","name":"b","bom-ref":"pkg:npm/b@2.0.0"}]}""");
		SupportBomInjector.inject(bom, Map.of(
				"pkg:npm/a@1.0.0", facts(attested(SupportState.ATTESTED)),
				"pkg:npm/b@2.0.0", facts(attested(SupportState.ATTESTED))), ASOF);

		assertEquals(List.of("0"), metaValues(bom, UNIDENTIFIED));
		assertEquals(List.of("0"), metaValues(bom, UNASSESSED));
		assertEquals(List.of("0"), metaValues(bom, SELF_COUNT));
	}

	/**
	 * Unassessed is "identified, and no support statement on the node": a matched row with no
	 * attestation and a withdrawn attestation write nothing on the node, so they count. Nodes are
	 * counted, not keys: two nodes with one purl count twice.
	 */
	@Test
	void countsNodesWithoutASupportStatementAsUnassessed() {
		JsonNode bom = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.5","components":[
			  {"type":"library","name":"row-only","purl":"pkg:npm/row-only@1.0.0"},
			  {"type":"library","name":"withdrawn","purl":"pkg:npm/withdrawn@1.0.0"},
			  {"type":"library","name":"twice","purl":"pkg:npm/twice@1.0.0"},
			  {"type":"library","name":"twice","purl":"pkg:npm/twice@1.0.0"},
			  {"type":"library","name":"kept","purl":"pkg:npm/kept@1.0.0"}]}""");
		Map<String, ComponentSupportFacts> byKey = new HashMap<>();
		byKey.put("pkg:npm/row-only@1.0.0", facts(null));
		byKey.put("pkg:npm/withdrawn@1.0.0", facts(attested(SupportState.WITHDRAWN)));
		byKey.put("pkg:npm/kept@1.0.0", facts(attested(SupportState.ATTESTED)));
		SupportBomInjector.inject(bom, byKey, ASOF);

		assertEquals(List.of("0"), metaValues(bom, UNIDENTIFIED));
		assertEquals(List.of("4"), metaValues(bom, UNASSESSED),
				"row-only, withdrawn and the two twice nodes carry no support statement: " + bom);
		assertEquals(List.of("0"), metaValues(bom, SELF_COUNT));
	}

	// T-10
	@Test
	void theStripPathsCarryNeitherCount() {
		JsonNode marked = SupportBomInjector.stripOnly(countingBom());
		assertEquals(List.of(), namesEverywhere(marked, "reliza:support:u"),
				"stripOnly sweeps the forged counts and writes none: " + marked);
		assertEquals(List.of(), namesEverywhere(marked, SELF_COUNT), "stripOnly writes no self count: " + marked);
		assertEquals(List.of("provenance-stripped-no-disclosure"),
				metaValues(marked, "reliza:support:disclosure"));

		JsonNode silent = SupportBomInjector.stripSilently(countingBom());
		assertEquals(List.of(), namesEverywhere(silent, "reliza:support:"),
				"stripSilently sweeps the forged counts and writes nothing: " + silent);
	}

	// T-10
	@Test
	void theInternalStripKeepsAllThreeCounts() {
		JsonNode bom = countingBom();
		SupportBomInjector.inject(bom, Map.of(), ASOF);
		SupportBomInjector.stripInternalMarkers(bom);

		assertEquals(List.of("8"), metaValues(bom, UNIDENTIFIED));
		assertEquals(List.of("2"), metaValues(bom, UNASSESSED),
				"with no facts, both identified nodes (purl and bom-ref purl) are unassessed");
		assertEquals(List.of("0"), metaValues(bom, SELF_COUNT));
		assertEquals(List.of("derived-non-attested-current-state"),
				metaValues(bom, "reliza:support:disclosure"));
	}

	// T-13: after inject then the internal strip, the self nodes keep the marker and the stamp is gone
	@Test
	void theInternalStripKeepsTheMarkerAndRemovesTheStamp() {
		JsonNode bom = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "metadata":{"component":{"type":"application","name":"root","purl":"pkg:generic/acme/root@1.0.0",
			   "properties":[{"name":"reliza:export:releaseNode","value":"true"}]}},
			 "components":[
			  {"type":"application","name":"b","purl":"pkg:generic/acme/b@1.0.0",
			   "properties":[{"name":"reliza:export:releaseNode","value":"true"}]},
			  {"type":"library","name":"dep","purl":"pkg:npm/dep@1.0.0"}]}""");
		SupportBomInjector.inject(bom, Map.of(), ASOF);
		SupportBomInjector.stripInternalMarkers(bom);

		assertEquals(List.of(SELF, SELF), namesEverywhere(bom, SELF).stream().filter(SELF::equals).toList(),
				"the root and b keep the marker: " + bom);
		assertEquals(List.of(), namesEverywhere(bom, STAMP), "no node carries the stamp: " + bom);
		assertEquals(List.of("2"), metaValues(bom, SELF_COUNT));
	}
}
