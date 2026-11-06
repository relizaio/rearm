/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.JustifiedUnknown;
import io.reliza.model.JustifiedUnknownField;
import io.reliza.model.JustifiedUnknownReason;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportData;
import io.reliza.model.SupportMilestoneFact;
import io.reliza.model.SupportMilestoneType;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;
import tools.jackson.databind.JsonNode;

/**
 * FDAW-14 row 3: the injector writes the two D5 properties from ReARM's rows only, and only where the
 * fact is absent; the code alone, never the text or the bulk act.
 */
class SupportBomInjectorJustifiedUnknownEmitTest {

	private static final LocalDate ASOF = LocalDate.of(2026, 6, 1);
	private static final String ASSESSED_AT = "2026-08-26T12:00:00Z";
	private static final String EOS = SupportBomInjector.PROP_JUSTIFIED_END_OF_SUPPORT;
	private static final String HASH = SupportBomInjector.PROP_JUSTIFIED_HASH;
	private static final UUID ACT = UUID.fromString("00000000-0000-0000-0000-00000000a0a0");
	private static final String SECRET = "SECRET-unknown-text";

	private static ComponentSupportFacts facts(LocalDate eos, JustifiedUnknownReason unknown, SupportState state) {
		Map<SupportMilestoneType, SupportMilestoneFact> m = new EnumMap<>(SupportMilestoneType.class);
		if (null != eos) m.put(SupportMilestoneType.END_OF_SUPPORT,
				new SupportMilestoneFact(eos.toString(), SupportSource.MANUAL, ASSESSED_AT, null, null));
		Map<JustifiedUnknownField, JustifiedUnknown> u = new EnumMap<>(JustifiedUnknownField.class);
		if (null != unknown) u.put(JustifiedUnknownField.END_OF_SUPPORT,
				new JustifiedUnknown(unknown, SECRET, ACT, UUID.randomUUID(), ASSESSED_AT));
		return new ComponentSupportFacts(new SbomComponent(), new SupportData(null, state, null, SupportSource.MANUAL,
				ASSESSED_AT, null, null, m, u, ACT));
	}

	private static JsonNode bom() throws Exception {
		return Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "metadata":{"component":{"type":"application","name":"app","purl":"pkg:generic/acme/app@1.0.0"}},
			 "components":[
			  {"type":"library","name":"a","purl":"pkg:deb/debian/a@1"},
			  {"type":"library","name":"b","purl":"pkg:deb/debian/b@1",
			   "hashes":[{"alg":"SHA-256","content":"00"}]},
			  {"type":"library","name":"c","purl":"pkg:deb/debian/c@1",
			   "components":[{"type":"library","name":"n","purl":"pkg:deb/debian/n@1"}]},
			  {"type":"library","name":"d","purl":"pkg:deb/debian/d@1",
			   "properties":[{"name":"reliza:support:justifiedUnknown:hash","value":"OTHER"},
			                 {"name":"reliza:support:justifiedUnknown:endOfSupport","value":"OTHER"}]}
			 ]}""");
	}

	private static String key(JsonNode node) {
		return SupportBomInjector.componentKey(node);
	}

	private static List<String> values(JsonNode node, String name) {
		List<String> out = new ArrayList<>();
		JsonNode props = node.get("properties");
		if (null == props) return out;
		for (JsonNode p : props) if (name.equals(p.path("name").asText())) out.add(p.path("value").asText());
		return out;
	}

	@Test
	void anUnknownWithoutADateEmitsExactlyOnePropertyOfTheCodeName() throws Exception {
		JsonNode bom = bom();
		JsonNode a = bom.get("components").get(0);
		SupportBomInjector.inject(bom, Map.of(key(a), facts(null, JustifiedUnknownReason.NO_COMMITTED_END_OF_SUPPORT,
				SupportState.ATTESTED)), ASOF);
		assertEquals(List.of("NO_COMMITTED_END_OF_SUPPORT"), values(bom.get("components").get(0), EOS));
		String served = Utils.OM.writeValueAsString(bom);
		assertFalse(served.contains(SECRET), "the text stays in ReARM");
		assertFalse(served.contains(ACT.toString()), "the bulk act stays in ReARM");
	}

	@Test
	void aDateWinsAndAWithdrawnRowEmitsNothing() throws Exception {
		JsonNode bom = bom();
		JsonNode a = bom.get("components").get(0);
		JsonNode c = bom.get("components").get(2);
		// the model refuses nothing here: a row holding both is constructed directly to pin the fact-wins rule
		SupportBomInjector.inject(bom, Map.of(
				key(a), facts(LocalDate.of(2030, 1, 1), JustifiedUnknownReason.COMMUNITY_PROJECT, SupportState.ATTESTED),
				key(c), facts(null, JustifiedUnknownReason.COMMUNITY_PROJECT, SupportState.WITHDRAWN)), ASOF);
		assertTrue(values(bom.get("components").get(0), EOS).isEmpty(), "a date: no justified unknown");
		assertTrue(values(bom.get("components").get(2), EOS).isEmpty(), "withdrawn: nothing");
	}

	@Test
	void aHashJustificationIsWrittenOnHashlessKeyedNodesOnlyIncludingNestedAndMetadata() throws Exception {
		JsonNode bom = bom();
		JsonNode comps = bom.get("components");
		JsonNode nested = comps.get(2).get("components").get(0);
		Map<String, JustifiedUnknownReason> codes = Map.of(
				key(comps.get(0)), JustifiedUnknownReason.DISTRIBUTION_PUBLISHES_NO_HASH,
				key(comps.get(1)), JustifiedUnknownReason.DISTRIBUTION_PUBLISHES_NO_HASH,
				key(nested), JustifiedUnknownReason.VENDORED_SOURCE_NO_HASH,
				key(bom.get("metadata").get("component")), JustifiedUnknownReason.OTHER);
		SupportBomInjector.inject(bom, Map.of(), ASOF, null, null, codes);
		assertEquals(List.of("DISTRIBUTION_PUBLISHES_NO_HASH"), values(comps.get(0), HASH));
		assertTrue(values(comps.get(1), HASH).isEmpty(), "a node with hashes is never also justified");
		assertEquals(List.of("VENDORED_SOURCE_NO_HASH"), values(comps.get(2).get("components").get(0), HASH));
		assertTrue(values(comps.get(2), HASH).isEmpty(), "not keyed: nothing");
		assertEquals(List.of("OTHER"), values(bom.get("metadata").get("component"), HASH));
	}

	@Test
	void uploadedCopiesAreStrippedWithAndWithoutJustifications() throws Exception {
		JsonNode bom = bom();
		SupportBomInjector.inject(bom, Map.of(), ASOF);
		assertTrue(values(bom.get("components").get(3), HASH).isEmpty());
		assertTrue(values(bom.get("components").get(3), EOS).isEmpty());

		JsonNode again = bom();
		JsonNode d = again.get("components").get(3);
		SupportBomInjector.inject(again, Map.of(key(d), facts(null, JustifiedUnknownReason.SUPPLIER_PUBLISHES_NO_DATE,
				SupportState.ATTESTED)), ASOF, null, null, Map.of(key(d), JustifiedUnknownReason.VENDORED_SOURCE_NO_HASH));
		assertEquals(List.of("VENDORED_SOURCE_NO_HASH"), values(again.get("components").get(3), HASH),
				"the served value is ReARM's, not the upload's");
		assertEquals(List.of("SUPPLIER_PUBLISHES_NO_DATE"), values(again.get("components").get(3), EOS));

		JsonNode stripped = bom();
		SupportBomInjector.stripOnly(stripped);
		assertTrue(values(stripped.get("components").get(3), HASH).isEmpty());
		JsonNode silent = bom();
		SupportBomInjector.stripSilently(silent);
		assertTrue(values(silent.get("components").get(3), EOS).isEmpty());
	}

	@Test
	void theOlderOverloadsWriteNoHashJustification() throws Exception {
		JsonNode bom = bom();
		SupportBomInjector.inject(bom, Map.of(), ASOF, null);
		SupportBomInjector.inject(bom, Map.of(), ASOF, null, null);
		for (JsonNode c : bom.get("components")) assertTrue(values(c, HASH).isEmpty());
	}

	@Test
	void anUnknownWithoutALevelIsNotCountedAsUnassessed() throws Exception {
		JsonNode bom = bom();
		JsonNode a = bom.get("components").get(0);
		SupportBomInjector.inject(bom, Map.of(key(a), facts(null, JustifiedUnknownReason.NO_COMMITTED_END_OF_SUPPORT,
				SupportState.ATTESTED)), ASOF);
		JsonNode baseline = bom();
		SupportBomInjector.inject(baseline, Map.of(), ASOF);
		int with = Integer.parseInt(values(bom.get("metadata"), SupportBomInjector.PROP_UNASSESSED).get(0));
		int without = Integer.parseInt(values(baseline.get("metadata"), SupportBomInjector.PROP_UNASSESSED).get(0));
		assertEquals(without - 1, with, "the justified component has a statement");
	}
}
