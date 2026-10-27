/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-57 (T-11, ADR-5): rebom's record on the release node of an uploaded root folded into it,
 * {@code reliza:export:foldedRoot}, survives the internal-metadata strip by its exact name on
 * {@code metadata.component} and on a component (the release node's copy in a product above it),
 * while the count {@code reliza:export:releaseRootsFolded} and the other internal markers go. The
 * egress reserved sweep ({@code isReservedNamespace}: the support and device namespaces and the
 * signing records) does not name it, so a copy that arrived in an upload is served like
 * {@code reliza:license:dropped}: it describes only the release its writer could already write.
 */
class SupportBomInjectorFoldedRootRecordTest {

	private static final String UP = "pkg:npm/facts%20org/facts-api@1.0.0?belongsto=RELEASE";

	private static final String DOC = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
			+ "\"metadata\":{\"properties\":["
			+ "{\"name\":\"reliza:export:releaseRootsFolded\",\"value\":\"1\"},"
			+ "{\"name\":\"reliza:export:bomRefsDeduplicated\",\"value\":\"1\"}],"
			+ "\"component\":{\"type\":\"application\",\"name\":\"facts-api\",\"properties\":["
			+ "{\"name\":\"reliza:containerSafeVersion\",\"value\":\"1.0.0\"},"
			+ "{\"name\":\"reliza:export:foldedRoot\",\"value\":\"" + UP + "\"},"
			+ "{\"name\":\"reliza:export:foldedRootOf\",\"value\":\"x\"}]}},"
			+ "\"components\":["
			+ "{\"type\":\"application\",\"name\":\"facts-api\",\"purl\":\"pkg:generic/Facts%20Org/facts-api@1.0.0\","
			+ "\"properties\":["
			+ "{\"name\":\"reliza:devops:integrationType\",\"value\":\"x\"},"
			+ "{\"name\":\"reliza:export:foldedRoot\",\"value\":\"" + UP + "\"}]}]}";

	private static List<String> names(JsonNode properties) {
		List<String> out = new ArrayList<>();
		if (properties == null) return out;
		for (JsonNode p : properties) out.add(p.get("name").asString() + "=" + p.get("value").asString());
		return out;
	}

	@Test
	void theRecordSurvivesTheInternalStripOnTheRootAndItsNeighboursGo() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));

		assertEquals(List.of("reliza:export:foldedRoot=" + UP),
				names(stripped.get("metadata").get("component").get("properties")));
	}

	@Test
	void theRecordSurvivesOnAComponent() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));

		assertEquals(List.of("reliza:export:foldedRoot=" + UP),
				names(stripped.get("components").get(0).get("properties")));
	}

	@Test
	void theCountIsStripped() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));

		assertEquals(List.of(), names(stripped.get("metadata").get("properties")));
	}

	@Test
	void theEgressReservedSweepLeavesTheRecordAsItLeavesTheDroppedLicenceRecord() throws Exception {
		JsonNode swept = SupportBomInjector.stripSilently(Utils.OM.readTree(DOC));

		assertEquals(List.of("reliza:containerSafeVersion=1.0.0", "reliza:export:foldedRoot=" + UP,
				"reliza:export:foldedRootOf=x"), names(swept.get("metadata").get("component").get("properties")));
		assertEquals(List.of("reliza:devops:integrationType=x", "reliza:export:foldedRoot=" + UP),
				names(swept.get("components").get(0).get("properties")));
	}

	@Test
	void theRecordIsNamedWhereRebomWritesIt() {
		assertEquals("reliza:export:foldedRoot", SupportBomInjector.FOLDED_ROOT_PROPERTY);
	}
}
