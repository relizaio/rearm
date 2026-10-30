/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.RootComponentMergeMode;
import io.reliza.common.Utils.StripBom;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.Rebom.MissingSbomComponent;
import io.reliza.model.Rebom.MissingSbomMode;
import io.reliza.model.Rebom.MissingSbomReason;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.Rebom.ReleaseNodeFacts;
import io.reliza.service.RebomService.BomStructureType;

/**
 * TEA-15 (architecture 3.1, 4.2 cases 12 to 15): the missing-SBOM mode on {@link RebomOptions}. Null is
 * PLACEHOLDER and is never serialised, so every row stored before the mode existed and every placeholder
 * request keeps its shape; OMIT is the only value ever written.
 */
class RebomOptionsMissingSbomModeJsonTest {

	private static RebomOptions merge() {
		return new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null, BomStructureType.FLAT).withMergeVersion(10);
	}

	// 12
	@Test
	void nullIsNotSerialisedAndReadsAsPlaceholder() throws Exception {
		assertFalse(Utils.OM.writeValueAsString(merge()).contains("missingSbomMode"));
		assertNull(merge().missingSbomMode());
		assertEquals(MissingSbomMode.PLACEHOLDER, merge().effectiveMissingSbomMode());
		assertEquals(merge(), merge().withMissingSbomMode(MissingSbomMode.PLACEHOLDER),
				"PLACEHOLDER written out is carried as null");
		assertEquals(merge(), merge().withMissingSbomMode(null));
		assertFalse(Utils.OM.writeValueAsString(merge().withMissingSbomMode(MissingSbomMode.PLACEHOLDER))
				.contains("missingSbomMode"));
	}

	// 13
	@Test
	void omitIsSerialisedByNameAndReadsBack() throws Exception {
		String json = Utils.OM.writeValueAsString(merge().withMissingSbomMode(MissingSbomMode.OMIT));

		assertTrue(json.contains("\"missingSbomMode\":\"OMIT\""), json);
		RebomOptions read = Utils.OM.readValue(json, RebomOptions.class);
		assertEquals(MissingSbomMode.OMIT, read.missingSbomMode());
		assertEquals(MissingSbomMode.OMIT, read.effectiveMissingSbomMode());
		assertEquals(merge().withMissingSbomMode(MissingSbomMode.OMIT), read);
	}

	// 14
	@Test
	void aRowStoredBeforeTheModeExistedReadsBackAsPlaceholder() throws Exception {
		String before = "{\"rebomId\":\"" + UUID.randomUUID() + "\",\"rebomMergeOptions\":"
				+ "{\"belongsTo\":\"RELEASE\",\"tldOnly\":false,\"ignoreDev\":false,\"structure\":\"FLAT\",\"mergeVersion\":9}}";

		RebomOptions read = Utils.OM.readValue(before, ReleaseBom.class).rebomMergeOptions();
		assertNull(read.missingSbomMode());
		assertEquals(MissingSbomMode.PLACEHOLDER, read.effectiveMissingSbomMode());
	}

	// 15: each of the 7 existing copy methods keeps the mode
	@Test
	void everyCopyKeepsTheMode() {
		RebomOptions omit = merge().withMissingSbomMode(MissingSbomMode.OMIT);
		MissingSbomComponent httpd = new MissingSbomComponent(UUID.randomUUID().toString(), "httpd", "2.4.68",
				"container", "Example Org", "Example Org", null, null, null, null, MissingSbomReason.NO_SBOM_ARTIFACT);

		assertEquals(MissingSbomMode.OMIT, omit.withBelongsTo(ArtifactBelongsTo.DELIVERABLE).missingSbomMode(), "withBelongsTo");
		assertEquals(MissingSbomMode.OMIT, omit.withMergeVersion(11).missingSbomMode(), "withMergeVersion");
		assertEquals(MissingSbomMode.OMIT, omit.withMissingSbomComponents(List.of(httpd)).missingSbomMode(),
				"withMissingSbomComponents");
		assertEquals(MissingSbomMode.OMIT, omit.withReleaseNode(new ReleaseNodeFacts("Example Org", null, null, null))
				.missingSbomMode(), "withReleaseNode");
		assertEquals(MissingSbomMode.OMIT, omit.withSupplierName("Example Org").missingSbomMode(), "withSupplierName");
		assertEquals(MissingSbomMode.OMIT, omit.withReleaseNodeFactsKey("0".repeat(64)).missingSbomMode(),
				"withReleaseNodeFactsKey");
	}

	// 15: each of the 10 explicit constructors starts without the mode (no 25-argument overload, F-1)
	@Test
	void everyConstructorStartsWithoutTheMode() {
		assertNull(new RebomOptions("n", "g", "1", ArtifactBelongsTo.RELEASE, "h", false, BomStructureType.FLAT, "notes",
				StripBom.TRUE, "s", "d", "p").missingSbomMode(), "create opts, 12 arguments");
		assertNull(new RebomOptions("n", "g", "1", ArtifactBelongsTo.RELEASE, "h", false, false, BomStructureType.FLAT,
				"notes", StripBom.TRUE, "s", "d", "p").missingSbomMode(), "13 arguments");
		assertNull(new RebomOptions("n", "g", "1", ArtifactBelongsTo.RELEASE, "h", false, false, BomStructureType.FLAT,
				"notes", StripBom.TRUE, "s", "d", "p", RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT).missingSbomMode(),
				"14 arguments");
		assertNull(new RebomOptions("n", "g", "1", ArtifactBelongsTo.RELEASE, "h", false, false, true, BomStructureType.FLAT,
				"notes", StripBom.TRUE, "s", "d", "p", RootComponentMergeMode.FLATTEN_UNDER_NEW_ROOT).missingSbomMode(),
				"15 arguments");
		assertNull(new RebomOptions(ArtifactBelongsTo.RELEASE, false, BomStructureType.FLAT,
				RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT).missingSbomMode(), "belongsTo, tldOnly, structure, mode");
		assertNull(new RebomOptions().missingSbomMode(), "no arguments");
		assertNull(new RebomOptions("n", "g", "1", ArtifactBelongsTo.RELEASE, "h", StripBom.TRUE, "p").missingSbomMode(),
				"7 arguments");
		assertNull(new RebomOptions(ArtifactBelongsTo.RELEASE, false, BomStructureType.FLAT).missingSbomMode(),
				"merge opts, 3 arguments");
		assertNull(new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, BomStructureType.FLAT).missingSbomMode(),
				"4 arguments");
		assertNull(new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null, BomStructureType.FLAT).missingSbomMode(),
				"5 arguments");
	}
}
