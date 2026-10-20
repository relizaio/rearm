/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-29 (design T-9): the support key of a release node is its purl as written, in the
 * single-encoded form rebom now writes and in the legacy double-encoded form alike; the key
 * never decodes, so a stored row matches byte for byte. Pure, no Spring / DB.
 */
class SupportBomInjectorReleaseNodeKeyTest {

	private static final String RELEASE_NODE = "pkg:generic/Reliza/ReARM%20Pro%20Backend@26.10.38";
	private static final String LEGACY_RELEASE_NODE = "pkg:generic/Reliza/ReARM%2520Pro%2520Backend@26.10.38";

	private static JsonNode node(String purl) {
		return Utils.OM.readTree("""
			{"type":"application","name":"ReARM Pro Backend","group":"Reliza","version":"26.10.38",
			 "purl":"%s?tldonly=true","bom-ref":"%s?tldonly=true"}""".formatted(purl, purl));
	}

	@Test
	void theSingleEncodedReleaseNodeKeyIsItsPurl() {
		assertEquals(RELEASE_NODE, SupportBomInjector.componentKey(node(RELEASE_NODE)));
	}

	@Test
	void theLegacyReleaseNodeKeyIsItsPurlUndecoded() {
		assertEquals(LEGACY_RELEASE_NODE, SupportBomInjector.componentKey(node(LEGACY_RELEASE_NODE)));
	}

	@Test
	void aMergedExportCollectsTheRootAndAnInputRootKeyAsWritten() {
		JsonNode bom = Utils.OM.readTree("""
			{"bomFormat":"CycloneDX","specVersion":"1.6",
			 "metadata":{"component":%s},
			 "components":[%s]}""".formatted(node(RELEASE_NODE), node(LEGACY_RELEASE_NODE.replace("26.10.38", "26.10.37"))));
		assertEquals(Set.copyOf(List.of(RELEASE_NODE, LEGACY_RELEASE_NODE.replace("26.10.38", "26.10.37"))),
				SupportBomInjector.collectKeys(bom));
	}
}
