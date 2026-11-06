/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

import io.reliza.common.Utils;

/**
 * FDAW-13 T-11: an uploaded copy of the justified-unknown properties
 * ({@code reliza:support:justifiedUnknown:endOfSupport} and {@code :hash}, FDAW-1 D5) is
 * removed on every path that sweeps the server-owned namespace, on a component and in
 * {@code metadata.properties}, with no strip code of its own. Names are the design's literal
 * text, not the production constants. Pure, no Spring / DB.
 */
class SupportBomInjectorJustifiedUnknownStripTest {

	private static final LocalDate ASOF = LocalDate.of(2026, 6, 1);
	private static final String JUSTIFIED_PREFIX = "reliza:support:justifiedUnknown:";
	private static final String KEPT = "some:other:property";

	private static JsonNode forgedBom() {
		return Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "metadata":{"properties":[
			   {"name":"reliza:support:justifiedUnknown:hash","value":"DISTRIBUTION_PUBLISHES_NO_HASH"}]},
			 "components":[{"type":"library","name":"lib","purl":"pkg:npm/lib@1.0.0","properties":[
			   {"name":"reliza:support:justifiedUnknown:endOfSupport","value":"NO_COMMITTED_END_OF_SUPPORT"},
			   {"name":"reliza:support:justifiedUnknown:hash","value":"VENDORED_SOURCE_NO_HASH"},
			   {"name":"some:other:property","value":"kept"}]}]}""");
	}

	/** Every property name anywhere in the tree. */
	private static List<String> namesEverywhere(JsonNode node) {
		List<String> out = new ArrayList<>();
		if (node.isObject() && node.has("value") && node.get("name") != null && node.get("name").isTextual()) {
			out.add(node.get("name").asText());
		}
		node.forEach(child -> out.addAll(namesEverywhere(child)));
		return out;
	}

	private static void assertSwept(String path, JsonNode bom) {
		List<String> names = namesEverywhere(bom);
		assertTrue(names.stream().noneMatch(n -> n.startsWith(JUSTIFIED_PREFIX)),
				path + ": an uploaded justified-unknown property survived: " + bom);
		assertEquals(1, names.stream().filter(KEPT::equals).count(),
				path + ": the sweep removed a property outside the reserved namespace: " + bom);
	}

	@Test
	void stripOnlyRemovesBothPropertiesOnTheComponentAndInMetadata() {
		assertSwept("stripOnly", SupportBomInjector.stripOnly(forgedBom()));
	}

	@Test
	void stripSilentlyRemovesBothProperties() {
		assertSwept("stripSilently", SupportBomInjector.stripSilently(forgedBom()));
	}

	@Test
	void injectRemovesBothPropertiesAndWritesNone() {
		JsonNode bom = forgedBom();
		SupportBomInjector.inject(bom, Map.of(), ASOF);
		assertSwept("inject", bom);
	}

	@Test
	void theConstantsAreTheDocumentedNamesUnderTheSweptPrefix() {
		assertEquals("reliza:support:justifiedUnknown:endOfSupport", SupportBomInjector.PROP_JUSTIFIED_END_OF_SUPPORT);
		assertEquals("reliza:support:justifiedUnknown:hash", SupportBomInjector.PROP_JUSTIFIED_HASH);
		assertTrue(SupportBomInjector.PROP_JUSTIFIED_HASH.startsWith(SupportBomInjector.RELIZA_SUPPORT_PREFIX));
		assertTrue(SupportBomInjector.PROP_JUSTIFIED_END_OF_SUPPORT.startsWith(SupportBomInjector.RELIZA_SUPPORT_PREFIX));
	}
}
