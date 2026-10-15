/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.RootComponentMergeMode;
import io.reliza.common.Utils.StripBom;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.tea.Rebom.MissingSbomComponent;
import io.reliza.model.tea.Rebom.MissingSbomReason;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomStructureType;
import tools.jackson.databind.JsonNode;

/**
 * SCORE-15 (T-13, ADR-7): missingSbomComponents reaches rebom only when a product merge has
 * releases without a BOM; every other request, and every stored row, keeps its shape.
 */
class RebomOptionsMissingSbomComponentsJsonTest {

	private static final MissingSbomComponent HTTPD = new MissingSbomComponent("0b0f4a52-6c55-4d1f-9a8e-0000000000d1",
			"httpd", "2.4.68", "container", "Example Org", "Example Org", "pkg:oci/httpd@2.4.68", MissingSbomReason.NO_SBOM_ARTIFACT);
	private static final MissingSbomComponent FLYWAY = new MissingSbomComponent("0b0f4a52-6c55-4d1f-9a8e-0000000000d2",
			"flyway", "11.20.3", "library", "Example Org", "Example Org", null, MissingSbomReason.COVERAGE_EXCLUDED);

	private static RebomOptions merge() {
		return new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null, BomStructureType.FLAT).withMergeVersion(4);
	}

	@Test
	void aNullListIsNotSerialisedAtAll() throws Exception {
		assertFalse(Utils.OM.writeValueAsString(merge()).contains("missingSbomComponents"));
	}

	@Test
	void anEmptyOrNullListIsCarriedAsNull() {
		assertNull(merge().withMissingSbomComponents(List.of()).missingSbomComponents());
		assertNull(merge().withMissingSbomComponents(null).missingSbomComponents());
		assertEquals(merge(), merge().withMissingSbomComponents(List.of()));
	}

	@Test
	void aListIsSerialisedInOrderWithEveryFieldByName() throws Exception {
		JsonNode sent = Utils.OM.readTree(Utils.OM.writeValueAsString(
				merge().withMissingSbomComponents(List.of(HTTPD, FLYWAY))));

		assertEquals(Utils.OM.readTree("[{\"releaseUuid\":\"0b0f4a52-6c55-4d1f-9a8e-0000000000d1\",\"name\":\"httpd\","
				+ "\"version\":\"2.4.68\",\"type\":\"container\",\"supplierName\":\"Example Org\",\"group\":\"Example Org\","
				+ "\"purl\":\"pkg:oci/httpd@2.4.68\",\"reason\":\"NO_SBOM_ARTIFACT\"},"
				+ "{\"releaseUuid\":\"0b0f4a52-6c55-4d1f-9a8e-0000000000d2\",\"name\":\"flyway\",\"version\":\"11.20.3\","
				+ "\"type\":\"library\",\"supplierName\":\"Example Org\",\"group\":\"Example Org\",\"purl\":null,"
				+ "\"reason\":\"COVERAGE_EXCLUDED\"}]"), sent.get("missingSbomComponents"));
	}

	@Test
	void theListIsACopyTheCallerCannotChangeAfterwards() {
		List<MissingSbomComponent> list = new ArrayList<>(List.of(HTTPD));
		RebomOptions options = merge().withMissingSbomComponents(list);
		list.add(FLYWAY);

		assertEquals(List.of(HTTPD), options.missingSbomComponents());
	}

	@Test
	void withMergeVersionKeepsTheListAndWithMissingSbomComponentsChangesNothingElse() {
		RebomOptions before = new RebomOptions("name", "group", "1.0.0", ArtifactBelongsTo.RELEASE, "hash", true, true,
				true, BomStructureType.HIERARCHICAL, "notes", StripBom.FALSE, "serial", "digest", "purl",
				RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT).withMergeVersion(4);
		RebomOptions after = before.withMissingSbomComponents(List.of(HTTPD));

		assertEquals(List.of(HTTPD), after.withMergeVersion(5).missingSbomComponents());
		assertEquals(before, after.withMissingSbomComponents(null));
	}

	@Test
	void aRowStoredBeforeTheListExistedReadsBackWithoutOne() throws Exception {
		String before = "{\"rebomId\":\"" + UUID.randomUUID() + "\",\"rebomMergeOptions\":"
				+ "{\"belongsTo\":\"RELEASE\",\"tldOnly\":false,\"ignoreDev\":false,\"structure\":\"FLAT\",\"mergeVersion\":3}}";

		assertNull(Utils.OM.readValue(before, ReleaseBom.class).rebomMergeOptions().missingSbomComponents());
	}

	@Test
	void rebomsEchoOfTheOptionsReadsBackWithTheList() throws Exception {
		RebomOptions echoed = Utils.OM.readValue(Utils.OM.writeValueAsString(merge().withMissingSbomComponents(
				List.of(HTTPD, FLYWAY))), RebomOptions.class);

		assertEquals(List.of(HTTPD, FLYWAY), echoed.missingSbomComponents());
	}
}
