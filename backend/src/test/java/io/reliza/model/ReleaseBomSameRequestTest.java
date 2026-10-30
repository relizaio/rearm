/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.Rebom.MissingSbomMode;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomStructureType;

/**
 * TEA-15 (4.3 case 16): {@link ReleaseBom#sameRequest} compares the missing-SBOM mode, so a product's
 * TEA publication row sits beside its placeholder row; the five comparisons it made before are unchanged.
 */
class ReleaseBomSameRequestTest {

	private static final RebomOptions A = new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null,
			BomStructureType.FLAT).withMergeVersion(10).withReleaseNodeFactsKey("0".repeat(64));

	@Test
	void placeholderWrittenOutIsTheSameRequestAsNoMode() {
		assertTrue(ReleaseBom.sameRequest(A, A.withMissingSbomMode(MissingSbomMode.PLACEHOLDER)));
	}

	@Test
	void omitIsAnotherRequest() {
		assertFalse(ReleaseBom.sameRequest(A, A.withMissingSbomMode(MissingSbomMode.OMIT)));
		assertTrue(ReleaseBom.sameRequest(A.withMissingSbomMode(MissingSbomMode.OMIT),
				A.withMissingSbomMode(MissingSbomMode.OMIT).withMergeVersion(9)));
	}

	@Test
	void belongsToIsCompared() {
		assertFalse(ReleaseBom.sameRequest(A, A.withBelongsTo(ArtifactBelongsTo.DELIVERABLE)));
	}

	@Test
	void tldOnlyIsCompared() {
		assertFalse(ReleaseBom.sameRequest(A, new RebomOptions(ArtifactBelongsTo.RELEASE, true, false, null,
				BomStructureType.FLAT)));
	}

	@Test
	void ignoreDevIsCompared() {
		assertFalse(ReleaseBom.sameRequest(A, new RebomOptions(ArtifactBelongsTo.RELEASE, false, true, null,
				BomStructureType.FLAT)));
	}

	@Test
	void structureIsCompared() {
		assertFalse(ReleaseBom.sameRequest(A, new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null,
				BomStructureType.HIERARCHICAL)));
	}

	@Test
	void theFileComponentSwitchIsComparedWithNullReadAsOff() {
		assertTrue(ReleaseBom.sameRequest(A, new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, false,
				BomStructureType.FLAT)));
		assertFalse(ReleaseBom.sameRequest(A, new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, true,
				BomStructureType.FLAT)));
	}

	@Test
	void theMergeVersionAndTheFactsKeyAreNot() {
		assertTrue(ReleaseBom.sameRequest(A, A.withMergeVersion(9).withReleaseNodeFactsKey(null)));
	}
}
