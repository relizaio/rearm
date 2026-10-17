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
 * SCORE-15 (T-5, ADR-3): rebom's marker on a placeholder component, {@code reliza:sbom:missing},
 * and the document's count of them, {@code reliza:export:componentsWithoutSbom}, survive the
 * internal-metadata strip by their exact names, on a top-level and on a nested component, while
 * SCORE-13's {@code reliza:export:bomRefsDeduplicated} and every other internal marker still go.
 */
class SupportBomInjectorMissingSbomRecordTest {

	private static final String DOC = "{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\","
			+ "\"metadata\":{\"component\":{\"type\":\"application\",\"name\":\"product\"},\"properties\":["
			+ "{\"name\":\"reliza:export:bomRefsDeduplicated\",\"value\":\"1\"},"
			+ "{\"name\":\"reliza:export:componentsWithoutSbom\",\"value\":\"2\"},"
			+ "{\"name\":\"reliza:export:componentsWithoutSbomSomewhere\",\"value\":\"x\"},"
			+ "{\"name\":\"cdx:other\",\"value\":\"kept\"}]},"
			+ "\"components\":["
			+ "{\"type\":\"container\",\"name\":\"httpd\",\"bom-ref\":\"urn:rearm:release:1\",\"properties\":["
			+ "{\"name\":\"reliza:containerSafeVersion\",\"value\":\"1.2.3\"},"
			+ "{\"name\":\"reliza:sbom:missing\",\"value\":\"NO_SBOM_ARTIFACT\"}]},"
			+ "{\"type\":\"application\",\"name\":\"app\",\"components\":["
			+ "{\"type\":\"library\",\"name\":\"flyway\",\"bom-ref\":\"urn:rearm:release:2\",\"properties\":["
			+ "{\"name\":\"reliza:sbom:missing\",\"value\":\"COVERAGE_EXCLUDED\"},"
			+ "{\"name\":\"reliza:sbom:missingSince\",\"value\":\"x\"},"
			+ "{\"name\":\"reliza:devops:integrationType\",\"value\":\"x\"}]}]}],"
			+ "\"compositions\":[{\"aggregate\":\"unknown\",\"dependencies\":[\"urn:rearm:release:1\",\"urn:rearm:release:2\"]}]}";

	private static List<String> names(JsonNode properties) {
		List<String> out = new ArrayList<>();
		for (JsonNode p : properties) out.add(p.get("name").asString() + "=" + p.get("value").asString());
		return out;
	}

	@Test
	void theCountSurvivesTheInternalStripAndTheDedupCountDoesNot() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));

		assertEquals(List.of("reliza:export:componentsWithoutSbom=2", "cdx:other=kept"),
				names(stripped.get("metadata").get("properties")));
	}

	@Test
	void theMarkerSurvivesOnATopLevelComponentWhoseOtherInternalMarkerGoes() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));

		assertEquals(List.of("reliza:sbom:missing=NO_SBOM_ARTIFACT"),
				names(stripped.get("components").get(0).get("properties")));
	}

	@Test
	void theMarkerSurvivesOnANestedComponentByItsExactName() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));

		assertEquals(List.of("reliza:sbom:missing=COVERAGE_EXCLUDED"),
				names(stripped.get("components").get(1).get("components").get(0).get("properties")));
	}

	@Test
	void theCompositionIsNotAPropertyAndIsLeftAsIs() throws Exception {
		JsonNode stripped = SupportBomInjector.stripInternalMarkers(Utils.OM.readTree(DOC));

		assertEquals(Utils.OM.readTree(DOC).get("compositions"), stripped.get("compositions"));
		assertNull(stripped.get("components").get(1).get("properties"));
	}

	@Test
	void theRecordsAreNamedWhereRebomWritesThem() {
		assertEquals("reliza:sbom:missing", SupportBomInjector.SBOM_MISSING_PROPERTY);
		assertEquals("reliza:export:componentsWithoutSbom", SupportBomInjector.COMPONENTS_WITHOUT_SBOM_PROPERTY);
	}
}
