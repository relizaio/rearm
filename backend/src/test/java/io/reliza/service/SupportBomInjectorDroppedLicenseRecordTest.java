/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.SbomComponent;
import io.reliza.model.SupportData;
import io.reliza.model.SupportMilestoneFact;
import io.reliza.model.SupportMilestoneType;
import io.reliza.model.SupportParty;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-44 (T-8, ADR-5): rebom's record on a merged node of a listed licence expression the merge
 * dropped, {@code reliza:license:dropped}, survives the internal-metadata strip by its exact name,
 * on a top-level component, a nested component and {@code metadata.component}, repeated on one
 * node, in order, while every other internal marker and every neighbour name of the namespace
 * goes. The egress reserved sweep and the support injection leave it too.
 */
class SupportBomInjectorDroppedLicenseRecordTest {

	private static final String DOC = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
			+ "\"metadata\":{\"component\":{\"type\":\"application\",\"name\":\"product\",\"properties\":["
			+ "{\"name\":\"reliza:containerSafeVersion\",\"value\":\"3.0.0\"},"
			+ "{\"name\":\"reliza:license:dropped\",\"value\":\"MIT OR Apache-2.0\"}]}},"
			+ "\"components\":["
			+ "{\"type\":\"library\",\"name\":\"facts-api\",\"purl\":\"pkg:npm/facts-api@1.0.0\","
			+ "\"licenses\":[{\"license\":{\"id\":\"AGPL-3.0-only\"}}],\"properties\":["
			+ "{\"name\":\"reliza:containerSafeVersion\",\"value\":\"1.0.0\"},"
			+ "{\"name\":\"reliza:license:dropped\",\"value\":\"BSD-3-Clause OR MIT\"},"
			+ "{\"name\":\"reliza:license:droppedFrom\",\"value\":\"x\"},"
			+ "{\"name\":\"reliza:license:other\",\"value\":\"x\"},"
			+ "{\"name\":\"reliza:license:dropped\",\"value\":\"GPL-2.0-only WITH Classpath-exception-2.0\"}]},"
			+ "{\"type\":\"application\",\"name\":\"app\",\"components\":["
			+ "{\"type\":\"library\",\"name\":\"lib\",\"properties\":["
			+ "{\"name\":\"reliza:devops:integrationType\",\"value\":\"x\"},"
			+ "{\"name\":\"reliza:license:dropped\",\"value\":\"BSD-3-Clause OR MIT\"}]}]}]}";

	private static final List<String> TOP_LEVEL_RECORDS = List.of(
			"reliza:license:dropped=BSD-3-Clause OR MIT",
			"reliza:license:dropped=GPL-2.0-only WITH Classpath-exception-2.0");

	private static List<String> names(JsonNode properties) {
		List<String> out = new ArrayList<>();
		if (properties == null) return out;
		for (JsonNode p : properties) out.add(p.get("name").asString() + "=" + p.get("value").asString());
		return out;
	}

	private static List<String> records(JsonNode properties) {
		return names(properties).stream().filter(n -> n.startsWith("reliza:license:dropped=")).toList();
	}

	@Test
	void bothRecordsSurviveTheInternalStripOnATopLevelComponentInOrderAndTheNeighboursGo() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));

		assertEquals(TOP_LEVEL_RECORDS, names(stripped.get("components").get(0).get("properties")));
	}

	@Test
	void theRecordSurvivesOnANestedComponentWhoseOtherInternalMarkerGoes() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));

		assertEquals(List.of("reliza:license:dropped=BSD-3-Clause OR MIT"),
				names(stripped.get("components").get(1).get("components").get(0).get("properties")));
	}

	@Test
	void theRecordSurvivesOnTheRootComponent() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));

		assertEquals(List.of("reliza:license:dropped=MIT OR Apache-2.0"),
				names(stripped.get("metadata").get("component").get("properties")));
	}

	@Test
	void theEgressReservedSweepLeavesEveryRecord() throws Exception {
		JsonNode swept = SupportBomInjector.stripSilently(Utils.OM.readTree(DOC));

		assertEquals(TOP_LEVEL_RECORDS, records(swept.get("components").get(0).get("properties")));
		assertEquals(List.of("reliza:license:dropped=BSD-3-Clause OR MIT"),
				records(swept.get("components").get(1).get("components").get(0).get("properties")));
		assertEquals(List.of("reliza:license:dropped=MIT OR Apache-2.0"),
				records(swept.get("metadata").get("component").get("properties")));
	}

	@Test
	void theSupportInjectionLeavesTheRecordsBesideTheSupportItAdds() throws Exception {
		JsonNode bom = Utils.OM.readTree(DOC);
		Map<SupportMilestoneType, SupportMilestoneFact> milestones = new EnumMap<>(SupportMilestoneType.class);
		String assessedAt = ZonedDateTime.of(2026, 8, 26, 12, 0, 0, 0, ZoneOffset.UTC).toInstant().toString();
		milestones.put(SupportMilestoneType.END_OF_SUPPORT,
				new SupportMilestoneFact("2027-01-01", SupportSource.MANUAL, assessedAt, null, null));
		SupportData support = new SupportData(LevelOfSupport.ACTIVELY_MAINTAINED, SupportState.ATTESTED,
				SupportParty.THIRD_PARTY, SupportSource.MANUAL, assessedAt,
				UUID.fromString("11111111-2222-3333-4444-555555555555"), "maintained", milestones);
		String key = SupportBomInjector.componentKey(bom.get("components").get(0));

		SupportBomInjector.inject(bom, Map.of(key, new ComponentSupportFacts(new SbomComponent(), support)),
				LocalDate.of(2026, 10, 15));

		List<String> after = names(bom.get("components").get(0).get("properties"));
		assertEquals(TOP_LEVEL_RECORDS, records(bom.get("components").get(0).get("properties")));
		assertTrue(after.stream().anyMatch(n -> n.startsWith("reliza:support:")), after.toString());
		assertEquals(List.of("reliza:license:dropped=MIT OR Apache-2.0"),
				records(bom.get("metadata").get("component").get("properties")));
	}

	@Test
	void theRecordIsNamedWhereRebomWritesIt() {
		assertEquals("reliza:license:dropped", SupportBomInjector.DROPPED_LICENSE_PROPERTY);
	}
}
