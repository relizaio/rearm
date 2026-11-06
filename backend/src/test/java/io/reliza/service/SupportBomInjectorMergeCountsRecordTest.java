/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import tools.jackson.databind.JsonNode;

/**
 * FDAW-6 T17 (design 3.3): rebom's six records of what the default merge left out, removed or linked
 * survive the internal-metadata strip on {@code metadata.properties} by their exact names, while
 * {@code reliza:export:bomRefsDeduplicated} still goes; a copy of one of them anywhere else in the
 * document arrived in an upload and is removed by the egress sweep.
 */
class SupportBomInjectorMergeCountsRecordTest {

	private static final List<String> SIX = List.of("reliza:export:devComponentsExcluded",
			"reliza:export:excludedScopeDropped", "reliza:export:personalDataStripped",
			"reliza:export:localPathsStripped", "reliza:export:credentialsStripped",
			"reliza:export:orphanComponentsLinked");

	private static String records() {
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < SIX.size(); i++) {
			b.append("{\"name\":\"").append(SIX.get(i)).append("\",\"value\":\"").append(i).append("\"},");
		}
		return b.toString();
	}

	private static String doc() {
		return "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
				+ "\"metadata\":{\"component\":{\"type\":\"application\",\"name\":\"app\",\"properties\":["
				+ "{\"name\":\"reliza:export:orphanComponentsLinked\",\"value\":\"0\"}]},"
				+ "\"tools\":{\"components\":[{\"type\":\"application\",\"name\":\"gen\",\"properties\":["
				+ "{\"name\":\"reliza:export:devComponentsExcluded\",\"value\":\"0\"}]}]},"
				+ "\"properties\":[" + records()
				+ "{\"name\":\"reliza:export:bomRefsDeduplicated\",\"value\":\"3\"}]},"
				+ "\"components\":[{\"type\":\"library\",\"name\":\"lib\",\"properties\":["
				+ "{\"name\":\"reliza:export:personalDataStripped\",\"value\":\"0\"},"
				+ "{\"name\":\"reliza:export:credentialsStripped\",\"value\":\"0\"},"
				+ "{\"name\":\"cdx:other\",\"value\":\"kept\"}]}],"
				+ "\"services\":[{\"name\":\"svc\",\"properties\":["
				+ "{\"name\":\"reliza:export:localPathsStripped\",\"value\":\"0\"}]}]}";
	}

	private static List<String> names(JsonNode properties) {
		List<String> out = new ArrayList<>();
		if (null != properties) for (JsonNode p : properties) out.add(p.get("name").asString());
		return out;
	}

	@Test
	void theSixRecordsSurviveTheInternalStripAndTheDedupRecordStillGoes() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(doc()));

		assertEquals(SIX, names(stripped.get("metadata").get("properties")));
		for (int i = 0; i < SIX.size(); i++) {
			assertEquals(String.valueOf(i), stripped.get("metadata").get("properties").get(i).get("value").asString());
		}
	}

	@Test
	void aCopyAnywhereButTheDocumentRecordsIsSweptOnEgressAndTheRecordsStay() throws Exception {
		JsonNode swept = SupportBomInjector.stripSilently(Utils.OM.readTree(doc()));

		assertEquals(List.of(SIX.get(0), SIX.get(1), SIX.get(2), SIX.get(3), SIX.get(4), SIX.get(5),
				"reliza:export:bomRefsDeduplicated"), names(swept.get("metadata").get("properties")));
		assertEquals(List.of("cdx:other"), names(swept.get("components").get(0).get("properties")));
		assertNull(swept.get("services").get(0).get("properties"), "the service's forged copy went, its array with it");
		assertNull(swept.get("metadata").get("component").get("properties"));
		assertNull(swept.get("metadata").get("tools").get("components").get(0).get("properties"));
	}

	@Test
	void theRecordsAreNamedWhereRebomWritesThem() {
		assertEquals(SIX, SupportBomInjector.MERGE_COUNT_PROPERTIES);
		assertEquals(SIX, List.of(SupportBomInjector.DEV_COMPONENTS_EXCLUDED_PROPERTY,
				SupportBomInjector.EXCLUDED_SCOPE_DROPPED_PROPERTY, SupportBomInjector.PERSONAL_DATA_STRIPPED_PROPERTY,
				SupportBomInjector.LOCAL_PATHS_STRIPPED_PROPERTY, SupportBomInjector.CREDENTIALS_STRIPPED_PROPERTY,
				SupportBomInjector.ORPHAN_COMPONENTS_LINKED_PROPERTY));
	}
}
