/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.RootComponentMergeMode;
import io.reliza.common.Utils.StripBom;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomStructureType;

/**
 * SCORE-13 (T-13): mergeVersion travels to rebom and onto the stored merged-BOM row only when set,
 * so every other caller's request, and every row stored before it existed, keeps its shape.
 */
class RebomOptionsMergeVersionJsonTest {

	private static RebomOptions merge() {
		return new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null, BomStructureType.FLAT);
	}

	@Test
	void aSetMergeVersionIsSerialised() throws Exception {
		String json = Utils.OM.writeValueAsString(merge().withMergeVersion(2));
		assertTrue(json.contains("\"mergeVersion\":2"), json);
	}

	@Test
	void aNullMergeVersionIsNotSerialisedAtAll() throws Exception {
		String json = Utils.OM.writeValueAsString(merge());
		assertFalse(json.contains("mergeVersion"), json);
	}

	@Test
	void withMergeVersionChangesNothingElse() {
		RebomOptions before = new RebomOptions("name", "group", "1.0.0", ArtifactBelongsTo.RELEASE, "hash", true, true,
				true, BomStructureType.HIERARCHICAL, "notes", StripBom.FALSE, "serial", "digest", "purl",
				RootComponentMergeMode.FLATTEN_UNDER_NEW_ROOT);
		RebomOptions after = before.withMergeVersion(2);
		assertEquals(2, after.mergeVersion());
		assertEquals(before, after.withMergeVersion(null));
	}

	@Test
	void aStoredRowReadsBackItsMergeVersionOrNone() throws Exception {
		ReleaseBom stored = new ReleaseBom(UUID.randomUUID(), merge().withMergeVersion(2));
		ReleaseBom read = Utils.OM.readValue(Utils.OM.writeValueAsString(stored), ReleaseBom.class);
		assertEquals(Integer.valueOf(2), read.rebomMergeOptions().mergeVersion());

		String before = "{\"rebomId\":\"" + UUID.randomUUID() + "\",\"rebomMergeOptions\":"
				+ "{\"belongsTo\":\"RELEASE\",\"tldOnly\":false,\"ignoreDev\":false,\"structure\":\"FLAT\"}}";
		assertNull(Utils.OM.readValue(before, ReleaseBom.class).rebomMergeOptions().mergeVersion(),
				"a row stored before mergeVersion existed reads back without one");
	}
}
