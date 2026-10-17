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
 * SCORE-14 (design ADR-6, T-15): rebom's record that dependency references resolving to nothing
 * were dropped from a merged release BOM, {@code reliza:export:danglingRefsDropped}, survives the
 * internal-metadata strip by its exact name, next to SCORE-11's file record, while SCORE-13's
 * {@code reliza:export:bomRefsDeduplicated} and every other reliza-namespaced marker still go.
 */
class SupportBomInjectorDanglingRefsRecordTest {

	private static final String DOC = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
			+ "\"metadata\":{\"component\":{\"type\":\"application\",\"name\":\"app\"},\"properties\":["
			+ "{\"name\":\"reliza:export:bomRefsDeduplicated\",\"value\":\"1\"},"
			+ "{\"name\":\"reliza:export:danglingRefsDropped\",\"value\":\"2\"},"
			+ "{\"name\":\"reliza:export:danglingRefsDroppedSomewhere\",\"value\":\"x\"},"
			+ "{\"name\":\"reliza:export:fileComponentsExcluded\",\"value\":\"1755\"},"
			+ "{\"name\":\"reliza:containerSafeVersion\",\"value\":\"1.2.3\"},"
			+ "{\"name\":\"cdx:other\",\"value\":\"kept\"}]},"
			+ "\"components\":[{\"type\":\"library\",\"name\":\"lib\",\"properties\":["
			+ "{\"name\":\"reliza:devops:integrationType\",\"value\":\"x\"}]}],"
			+ "\"services\":[{\"bom-ref\":\"svc\",\"name\":\"svc\",\"properties\":["
			+ "{\"name\":\"reliza:containerSafeVersion\",\"value\":\"9\"},"
			+ "{\"name\":\"cdx:service:packageName\",\"value\":\"nginx\"}]}]}";

	private static List<String> names(JsonNode properties) {
		List<String> out = new ArrayList<>();
		for (JsonNode p : properties) out.add(p.get("name").asString());
		return out;
	}

	@Test
	void theRecordOfDroppedRefsSurvivesTheInternalStripNextToTheFileRecordAndTheDedupCountDoesNot() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));

		JsonNode properties = stripped.get("metadata").get("properties");
		assertEquals(List.of("reliza:export:danglingRefsDropped", "reliza:export:fileComponentsExcluded", "cdx:other"),
				names(properties));
		assertEquals("2", properties.get(0).get("value").asString());
		assertEquals("1755", properties.get(1).get("value").asString());
		assertEquals("kept", properties.get(2).get("value").asString());
		assertNull(stripped.get("components").get(0).get("properties"),
				"the component's internal marker went, and its emptied array with it");
	}

	@Test
	void aCarriedServiceStaysAndLosesOnlyItsInternalMarkers() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));

		assertEquals("svc", stripped.get("services").get(0).get("bom-ref").asString());
		assertEquals(List.of("cdx:service:packageName"),
				names(stripped.get("services").get(0).get("properties")));
	}

	@Test
	void theRecordIsNamedWhereRebomWritesIt() {
		assertEquals("reliza:export:danglingRefsDropped", SupportBomInjector.DANGLING_REFS_DROPPED_PROPERTY);
	}
}
