/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.tea.Rebom.MissingSbomComponent;
import io.reliza.model.tea.Rebom.MissingSbomReason;
import io.reliza.service.RebomService.BomStructureType;

/**
 * SCORE-15 ADR-10 (T-17, T-18): a request with a non-empty {@code excludeCoverageTypes} stores no
 * {@link ReleaseBom} row, at product nor at component level. Its row would carry the unfiltered
 * options and be served to the next plain request. The merge still runs and its id is returned.
 */
class ReleaseServiceCoverageFilteredMergeStoreTest extends ReleaseServiceMergeTestBase {

	private static final UUID FIRST = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e1");
	private static final UUID SECOND = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e2");
	private static final UUID DEV_ONLY = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e3");

	private UUID request(UUID release, List<ArtifactCoverageType> excludeCoverageTypes) throws Exception {
		return svc.getReleaseBomId(release, false, false, false, ArtifactBelongsTo.RELEASE, BomStructureType.FLAT,
				WU, excludeCoverageTypes);
	}

	// T-17, run-1 T-2
	@Test
	void aFilteredProductExportStoresNoRowAndThePlainOneAfterItIsBuiltUnfiltered() throws Exception {
		unwindsTo(withBom(FIRST, "1.0.0", null), withBom(SECOND, "2.0.0", null),
				withBom(DEV_ONLY, "5.0.0", ArtifactCoverageType.DEV));

		UUID filtered = request(PRODUCT_RELEASE, List.of(ArtifactCoverageType.DEV));

		assertEquals(1, productMerges().size());
		Merge filteredMerge = productMerges().get(0);
		assertEquals(filtered, filteredMerge.merged());
		assertEquals(List.of(new MissingSbomComponent(DEV_ONLY.toString(), "component", "5.0.0", "library",
				"Example Org", "Example Org", null, MissingSbomReason.COVERAGE_EXCLUDED)),
				filteredMerge.options().missingSbomComponents());
		assertEquals(Map.of(), rowWrites, "neither the product nor any component stores a row");

		UUID plain = request(PRODUCT_RELEASE, null);

		assertEquals(2, productMerges().size(), "the plain export is not served the filtered merge");
		Merge plainMerge = productMerges().get(1);
		assertEquals(plain, plainMerge.merged());
		assertNull(plainMerge.options().missingSbomComponents());
		assertEquals(3, plainMerge.bomIds().size(), "the DEV-only release is merged with its SBOM");
		assertEquals(List.of(new ReleaseBom(plain, rowOptions(BomStructureType.FLAT))), rows.get(PRODUCT_RELEASE));
		assertEquals(Map.of(PRODUCT_RELEASE, 1, FIRST, 1, SECOND, 1, DEV_ONLY, 1), rowWrites);
	}

	// T-18
	@Test
	void aFilteredComponentMergeIsReturnedAndNotStored() throws Exception {
		withBom(FIRST, "1.0.0", null);

		UUID merged = request(FIRST, List.of(ArtifactCoverageType.DEV));

		assertEquals(1, merges.size());
		assertEquals(merges.get(0).merged(), merged);
		assertEquals(Map.of(), rowWrites);
	}

	// T-18: a null filter stores the row
	@Test
	void aComponentMergeWithoutAFilterIsStored() throws Exception {
		withBom(FIRST, "1.0.0", null);

		UUID merged = request(FIRST, null);

		assertEquals(List.of(new ReleaseBom(merged, rowOptions(BomStructureType.FLAT))), rows.get(FIRST));
	}

	// T-18: an empty filter is no filter and stores the row
	@Test
	void aComponentMergeWithAnEmptyFilterIsStored() throws Exception {
		withBom(FIRST, "1.0.0", null);

		UUID merged = request(FIRST, List.of());

		assertEquals(List.of(new ReleaseBom(merged, rowOptions(BomStructureType.FLAT))), rows.get(FIRST));
	}
}
