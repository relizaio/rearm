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
 * SCORE-11 (design ADR-5, R-5): rebom's record that file components were left out of a merged
 * release BOM, {@code reliza:export:fileComponentsExcluded}, survives the internal-metadata strip
 * by its exact name, while every other reliza-namespaced marker, a near namesake included, still
 * goes.
 */
class SupportBomInjectorFileComponentsRecordTest {

	private static final String DOC = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
			+ "\"metadata\":{\"component\":{\"type\":\"application\",\"name\":\"app\"},\"properties\":["
			+ "{\"name\":\"reliza:export:fileComponentsExcluded\",\"value\":\"1755\"},"
			+ "{\"name\":\"reliza:export:somethingElse\",\"value\":\"x\"},"
			+ "{\"name\":\"reliza:containerSafeVersion\",\"value\":\"1.2.3\"},"
			+ "{\"name\":\"cdx:other\",\"value\":\"kept\"}]},"
			+ "\"components\":[{\"type\":\"library\",\"name\":\"lib\",\"properties\":["
			+ "{\"name\":\"reliza:devops:integrationType\",\"value\":\"x\"}]}]}";

	private static List<String> names(JsonNode properties) {
		List<String> out = new ArrayList<>();
		for (JsonNode p : properties) out.add(p.get("name").asString());
		return out;
	}

	@Test
	void theRecordOfLeftOutFilesSurvivesTheInternalStripAndNothingElseOfOursDoes() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));

		assertEquals(List.of("reliza:export:fileComponentsExcluded", "cdx:other"),
				names(stripped.get("metadata").get("properties")));
		assertEquals("1755", stripped.get("metadata").get("properties").get(0).get("value").asString());
		assertNull(stripped.get("components").get(0).get("properties"),
				"the component's internal marker went, and its emptied array with it");
	}

	@Test
	void theRecordIsNamedWhereRebomWritesIt() {
		assertEquals("reliza:export:fileComponentsExcluded", SupportBomInjector.FILE_COMPONENTS_EXCLUDED_PROPERTY);
	}
}
