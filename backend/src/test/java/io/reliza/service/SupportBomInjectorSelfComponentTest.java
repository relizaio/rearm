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
 * The own-entry marker {@code reliza:support:selfComponent} (SCORE-58 ADR-3 and ADR-4, built as
 * SCORE-60, test T-12): one node per rule of {@code SupportBomInjector.selfComponent}. Names are
 * the design's literal text, not the production constants. Pure, no Spring / DB.
 *
 * <p>Rule 2's info line is not asserted: the backend logs through log4j2 and no test of this
 * module captures log output, so the line is left to review (the design allows skipping it).
 */
class SupportBomInjectorSelfComponentTest {

	private static final LocalDate ASOF = LocalDate.of(2026, 6, 1);
	private static final String SELF = "reliza:support:selfComponent";
	private static final String LEVEL = "reliza:support:levelOfSupport";
	private static final String UNIDENTIFIED = "reliza:support:unidentifiedComponents";
	private static final String PURL = "pkg:generic/acme/app@1.0.0";

	private static SupportData attested() {
		return new SupportData(LevelOfSupport.ACTIVELY_MAINTAINED, SupportState.ATTESTED, SupportParty.THIRD_PARTY,
				SupportSource.MANUAL, "2026-08-26T12:00:00Z", null, null, Map.of());
	}

	private static ComponentSupportFacts facts(boolean isRoot, SupportData support) {
		SbomComponent sc = new SbomComponent();
		sc.setRecordData(Map.of("isRoot", isRoot));
		return new ComponentSupportFacts(sc, support);
	}

	private static String stamp(String value) {
		return "{\"name\":\"reliza:export:releaseNode\",\"value\":\"" + value + "\"}";
	}

	/** A document with one component node: the given purl (or none) and the given properties JSON. */
	private static JsonNode oneNode(String purlOrNull, String propertiesJson) {
		String purl = purlOrNull == null ? "" : ",\"purl\":\"" + purlOrNull + "\"";
		String props = propertiesJson == null ? "" : ",\"properties\":" + propertiesJson;
		return Utils.OM.readTree("{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":["
				+ "{\"type\":\"application\",\"name\":\"n\"" + purl + props + "}]}");
	}

	private static JsonNode node(JsonNode bom) {
		return bom.get("components").get(0);
	}

	/** Every value of a property name on a node, in order. */
	private static List<String> values(JsonNode node, String name) {
		List<String> out = new ArrayList<>();
		JsonNode props = node.get("properties");
		if (props != null) {
			props.forEach(p -> {
				if (name.equals(p.get("name").asText())) out.add(p.get("value").asText());
			});
		}
		return out;
	}

	/** Every occurrence of a property name anywhere in the tree. */
	private static int countEverywhere(JsonNode node, String name) {
		int n = 0;
		if (node.isObject() && node.has("value") && node.get("name") != null
				&& name.equals(node.get("name").asText())) {
			n++;
		}
		for (JsonNode child : node) {
			n += countEverywhere(child, name);
		}
		return n;
	}

	// rule 1
	@Test
	void aRootRowIsSelfWithoutAStatement() {
		JsonNode bom = oneNode(PURL, null);
		SupportBomInjector.inject(bom, Map.of(PURL, facts(true, null)), ASOF);
		assertEquals(List.of("true"), values(node(bom), SELF), "root row, no support: " + bom);
		assertEquals(List.of(), values(node(bom), LEVEL), "no statement written");
	}

	// rule 1'
	@Test
	void aRootRowWithAnAttestationKeepsBothTheMarkerAndTheStatement() {
		JsonNode bom = oneNode(PURL, null);
		SupportBomInjector.inject(bom, Map.of(PURL, facts(true, attested())), ASOF);
		assertEquals(List.of("true"), values(node(bom), SELF));
		assertEquals(1, values(node(bom), LEVEL).size(), "the operator's statement on their own entry is kept: " + bom);
	}

	// rule 2
	@Test
	void aNonRootRowIsNotSelfEvenWithTheStamp() {
		JsonNode bom = oneNode(PURL, "[" + stamp("true") + "]");
		SupportBomInjector.inject(bom, Map.of(PURL, facts(false, attested())), ASOF);
		assertEquals(List.of(), values(node(bom), SELF), "a row the organization lists as a dependency wins: " + bom);
		assertEquals(1, values(node(bom), LEVEL).size(), "judged as today: the statement is written");
	}

	// rule 3
	@Test
	void noRowAndTheStampIsSelf() {
		JsonNode bom = oneNode(PURL, "[" + stamp("true") + "]");
		SupportBomInjector.inject(bom, Map.of(), ASOF);
		assertEquals(List.of("true"), values(node(bom), SELF), bom.toString());
	}

	// rule 3'
	@Test
	void noRowAndAStampValueOtherThanTrueIsNotSelf() {
		for (String value : List.of("TRUE", " true", "false")) {
			JsonNode bom = oneNode(PURL, "[" + stamp(value) + "]");
			SupportBomInjector.inject(bom, Map.of(), ASOF);
			assertEquals(List.of(), values(node(bom), SELF), "stamp value '" + value + "': " + bom);
		}
	}

	// rule 4
	@Test
	void noRowAndNoStampIsNotSelf() {
		JsonNode bom = oneNode(PURL, null);
		SupportBomInjector.inject(bom, Map.of(), ASOF);
		assertEquals(List.of(), values(node(bom), SELF));
	}

	@Test
	void aKeylessNodeWithTheStampIsNotSelfAndIsCountedUnidentified() {
		JsonNode bom = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","components":[
			  {"type":"application","name":"keyless","bom-ref":"not-a-purl",
			   "properties":[{"name":"reliza:export:releaseNode","value":"true"}]}]}""");
		SupportBomInjector.inject(bom, Map.of(), ASOF);
		assertEquals(List.of(), values(node(bom), SELF), bom.toString());
		JsonNode metaProps = bom.get("metadata").get("properties");
		assertEquals("1", metaProps.get(indexOf(metaProps, UNIDENTIFIED)).get("value").asText());
	}

	@Test
	void aNestedNodeUnderRuleThreeIsSelfAtDepth() {
		JsonNode bom = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6","components":[
			  {"type":"library","name":"outer","purl":"pkg:npm/outer@1.0.0","components":[
			    {"type":"application","name":"inner","purl":"pkg:generic/acme/inner@2.0.0",
			     "properties":[{"name":"reliza:export:releaseNode","value":"true"}]}]}]}""");
		SupportBomInjector.inject(bom, Map.of(), ASOF);
		assertEquals(List.of("true"), values(node(bom).get("components").get(0), SELF), bom.toString());
		assertEquals(List.of(), values(node(bom), SELF));
	}

	@Test
	void theRootNodeIsSelfByItsRowOrByTheStamp() {
		JsonNode byRow = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "metadata":{"component":{"type":"application","name":"root","purl":"pkg:generic/acme/root@1.0.0"}},
			 "components":[]}""");
		SupportBomInjector.inject(byRow, Map.of("pkg:generic/acme/root@1.0.0", facts(true, null)), ASOF);
		assertEquals(List.of("true"), values(byRow.get("metadata").get("component"), SELF), byRow.toString());

		JsonNode byStamp = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "metadata":{"component":{"type":"application","name":"root","purl":"pkg:generic/acme/root@1.0.0",
			   "properties":[{"name":"reliza:export:releaseNode","value":"true"}]}},
			 "components":[]}""");
		SupportBomInjector.inject(byStamp, Map.of(), ASOF);
		assertEquals(List.of("true"), values(byStamp.get("metadata").get("component"), SELF), byStamp.toString());
	}

	@Test
	void exactlyOneMarkerPerSelfNode() {
		JsonNode bom = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "metadata":{"component":{"type":"application","name":"root","purl":"pkg:generic/acme/root@1.0.0",
			   "properties":[{"name":"reliza:export:releaseNode","value":"true"},
			                 {"name":"reliza:support:selfComponent","value":"true"}]}},
			 "components":[
			  {"type":"application","name":"a","purl":"pkg:generic/acme/a@1.0.0",
			   "properties":[{"name":"reliza:support:selfComponent","value":"true"},
			                 {"name":"reliza:support:selfComponent","value":"true"}]},
			  {"type":"application","name":"b","purl":"pkg:generic/acme/b@1.0.0",
			   "properties":[{"name":"reliza:export:releaseNode","value":"true"}]}]}""");
		Map<String, ComponentSupportFacts> byKey = new HashMap<>();
		byKey.put("pkg:generic/acme/a@1.0.0", facts(true, attested()));
		SupportBomInjector.inject(bom, byKey, ASOF);
		assertEquals(3, countEverywhere(bom, SELF), "root, a and b: one marker each: " + bom);
		assertEquals(1, values(bom.get("metadata").get("component"), SELF).size());
		assertEquals(1, values(node(bom), SELF).size());
		assertEquals(1, values(bom.get("components").get(1), SELF).size());
		assertEquals(1, values(node(bom), LEVEL).size(), "a's statement written beside its marker");
	}

	private static int indexOf(JsonNode props, String name) {
		for (int i = 0; i < props.size(); i++) {
			if (name.equals(props.get(i).get("name").asText())) {
				return i;
			}
		}
		throw new AssertionError(name + " not in " + props);
	}
}
