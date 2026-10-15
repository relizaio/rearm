/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.service.RebomService.BomStructureType;

/**
 * SCORE-15 ADR-9 (T-16): an artifact change on a release rebuilds every stored merged row of the
 * products above it, whether or not the release has rows of its own, so a placeholder leaves the
 * product export once its release gets its first SBOM. Only the changed release is forced below
 * the product; the rows the release itself holds keep being rebuilt as before.
 *
 * <p>SCORE-15 ADR-11 (T-22): the products above the release are found transitively, so a product
 * that bundles the release only through another product is rebuilt too, and a forced product
 * rebuild forces the product releases it bundles, so their stale rows are never merged in.
 */
class ReleaseServiceReconcileMergedSbomTest extends ReleaseServiceMergeTestBase {

	private static final UUID SOURCE = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000d1");
	private static final UUID OTHER = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000d2");
	private static final UUID OTHER_FLAT = UUID.randomUUID();
	private static final UUID OTHER_HIERARCHICAL = UUID.randomUUID();
	private static final String SOURCE_VERSION = "3.0.0";
	// ADR-11: the inner product and the releases it and the outer product bundle
	private static final UUID INNER = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e1");
	private static final UUID BUNDLED_A = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e2");
	private static final UUID BUNDLED_B = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e3");
	private static final UUID A_FLAT = UUID.randomUUID();
	private static final UUID B_FLAT = UUID.randomUUID();
	private static final String INNER_VERSION = "1.1.0";

	private ReleaseData source;
	private ReleaseData inner;

	/** The product holds SOURCE (just got its first SBOM) and OTHER (cached under both structures). */
	private void productOfSourceAndOther() {
		source = withBom(SOURCE, SOURCE_VERSION, null);
		ReleaseData other = withBom(OTHER, "1.0.0", null);
		storeRow(OTHER, OTHER_FLAT, rowOptions(BomStructureType.FLAT));
		storeRow(OTHER, OTHER_HIERARCHICAL, rowOptions(BomStructureType.HIERARCHICAL));
		unwindsTo(other, source);
		productsAbove(source, product);
	}

	private UUID sourceMergeUnder(BomStructureType structure) {
		List<Merge> own = componentMerges().stream().filter(m -> m.options().structure() == structure).toList();
		assertEquals(1, own.size(), componentMerges().toString());
		return own.get(0).merged();
	}

	private Merge productMergeUnder(BomStructureType structure) {
		List<Merge> under = productMerges().stream().filter(m -> m.options().structure() == structure).toList();
		assertEquals(1, under.size(), productMerges().toString());
		return under.get(0);
	}

	// T-16, run-1 T-1
	@Test
	void aReleaseWithoutRowsOfItsOwnRebuildsEachStoredRowOfItsProduct() {
		productOfSourceAndOther();
		UUID staleFlat = UUID.randomUUID();
		UUID staleHierarchical = UUID.randomUUID();
		storeRow(PRODUCT_RELEASE, staleFlat, rowOptions(BomStructureType.FLAT));
		storeRow(PRODUCT_RELEASE, staleHierarchical, rowOptions(BomStructureType.HIERARCHICAL));

		svc.reconcileMergedSbomRoutine(source, WU);

		assertEquals(2, productMerges().size(), productMerges().toString());
		Merge flat = productMergeUnder(BomStructureType.FLAT);
		Merge hierarchical = productMergeUnder(BomStructureType.HIERARCHICAL);
		// The other release is served from its rows; only the changed release is merged afresh.
		assertEquals(List.of(OTHER_FLAT, sourceMergeUnder(BomStructureType.FLAT)), flat.bomIds());
		assertEquals(List.of(OTHER_HIERARCHICAL, sourceMergeUnder(BomStructureType.HIERARCHICAL)), hierarchical.bomIds());
		assertNull(flat.options().missingSbomComponents(), "the release is no placeholder any more");
		// The rebuilt rows replace the stale ones under the same options.
		assertEquals(List.of(new ReleaseBom(flat.merged(), rowOptions(BomStructureType.FLAT)),
				new ReleaseBom(hierarchical.merged(), rowOptions(BomStructureType.HIERARCHICAL))),
				rows.get(PRODUCT_RELEASE));
	}

	// T-16
	@Test
	void aProductWithoutStoredRowsIsNotMergedForAReleaseWithoutRows() {
		productOfSourceAndOther();

		svc.reconcileMergedSbomRoutine(source, WU);

		assertEquals(List.of(), merges);
		assertEquals(Map.of(), rowWrites);
	}

	// ADR-9, the forceComponent logic kept: a stale row of the changed release is not served
	@Test
	void theChangedReleasesOwnStaleRowIsMergedAfreshAndTheOthersAreServed() {
		productOfSourceAndOther();
		UUID staleSource = UUID.randomUUID();
		storeRow(SOURCE, staleSource, rowOptions(BomStructureType.FLAT));
		storeRow(PRODUCT_RELEASE, UUID.randomUUID(), rowOptions(BomStructureType.FLAT));

		svc.reconcileMergedSbomRoutine(source, WU);

		UUID fresh = sourceMergeUnder(BomStructureType.FLAT);
		assertEquals(List.of(OTHER_FLAT, fresh), productMergeUnder(BomStructureType.FLAT).bomIds());
		assertEquals(List.of(new ReleaseBom(fresh, rowOptions(BomStructureType.FLAT))), rows.get(SOURCE));
		assertEquals(1, componentMerges().size(), "OTHER is served from its row, not merged again");
	}

	// Departure D-1: the release's own options are still rebuilt when the product has no row under them
	@Test
	void theReleasesOwnRowsAreStillRebuiltUnderOptionsTheProductHasNoRowFor() {
		productOfSourceAndOther();
		storeRow(SOURCE, UUID.randomUUID(), rowOptions(BomStructureType.HIERARCHICAL));
		storeRow(PRODUCT_RELEASE, UUID.randomUUID(), rowOptions(BomStructureType.FLAT));

		svc.reconcileMergedSbomRoutine(source, WU);

		assertEquals(2, productMerges().size(), productMerges().toString());
		UUID freshHierarchical = sourceMergeUnder(BomStructureType.HIERARCHICAL);
		UUID freshFlat = sourceMergeUnder(BomStructureType.FLAT);
		// The product's options first, then the release's own; each rebuilt row replaces its stale one.
		assertEquals(List.of(new ReleaseBom(freshFlat, rowOptions(BomStructureType.FLAT)),
				new ReleaseBom(freshHierarchical, rowOptions(BomStructureType.HIERARCHICAL))), rows.get(SOURCE));
	}

	// ---- ADR-11 (T-22): nested products ----

	/**
	 * The s15n shape: the inner product holds A and the source release; the outer {@link #product}
	 * holds B and the inner product, so its unwound set is flat: B, the inner product, A, the source.
	 * A and B are cached FLAT; each product has a stale FLAT row listing the source as a placeholder.
	 */
	private void nestedProducts() {
		source = withBom(SOURCE, SOURCE_VERSION, null);
		ReleaseData a = withBom(BUNDLED_A, "1.0.0", null);
		ReleaseData b = withBom(BUNDLED_B, "2.0.0", null);
		storeRow(BUNDLED_A, A_FLAT, rowOptions(BomStructureType.FLAT));
		storeRow(BUNDLED_B, B_FLAT, rowOptions(BomStructureType.FLAT));
		inner = innerProductRelease(INNER, INNER_VERSION);
		unwinds(inner, a, source);
		unwindsTo(b, inner, a, source);
	}

	private List<String> productMergeVersions() {
		return productMerges().stream().map(m -> m.options().version()).toList();
	}

	private List<Merge> productMergesOf(String version) {
		return productMerges().stream().filter(m -> version.equals(m.options().version())).toList();
	}

	private UUID onlyProductMergeOf(String version) {
		List<Merge> of = productMergesOf(version);
		assertEquals(1, of.size(), productMerges().toString());
		return of.get(0).merged();
	}

	// T-22 (a): a product at the second level is rebuilt, after the product between it and the release
	@Test
	void aProductThatBundlesTheReleaseThroughAnotherProductIsRebuiltAfterIt() {
		nestedProducts();
		UUID staleInner = UUID.randomUUID();
		UUID staleOuter = UUID.randomUUID();
		storeRow(INNER, staleInner, rowOptions(BomStructureType.FLAT));
		storeRow(PRODUCT_RELEASE, staleOuter, rowOptions(BomStructureType.FLAT));
		productsAbove(source, inner, product);
		// The first-level locate finds the inner product only; the routine must not rely on it.
		when(sharedReleaseService.greedylocateProductsOfRelease(source)).thenReturn(new LinkedHashSet<>(List.of(inner)));

		svc.reconcileMergedSbomRoutine(source, WU);

		// The inner product in the routine's own loop, then the outer, which takes the inner product and
		// the changed release as this reconcile merged them, not merged again (ADR-12).
		assertEquals(List.of(INNER_VERSION, product.getVersion()), productMergeVersions());
		Merge outer = productMerges().get(1);
		UUID innerMerge = productMerges().get(0).merged();
		assertEquals(1, componentMerges().size(), componentMerges().toString());
		assertEquals(List.of(B_FLAT, innerMerge, A_FLAT, componentMerges().get(0).merged()), outer.bomIds());
		assertFalse(outer.bomIds().contains(staleInner));
		productMerges().forEach(m -> assertNull(m.options().missingSbomComponents(), "the release is no placeholder any more"));
		assertEquals(List.of(new ReleaseBom(outer.merged(), rowOptions(BomStructureType.FLAT))), rows.get(PRODUCT_RELEASE));
		assertEquals(List.of(new ReleaseBom(innerMerge, rowOptions(BomStructureType.FLAT))), rows.get(INNER));
		// The per-component tables of both products are reconciled too, from the same walk.
		verify(sbomComponentService).requestReconcile(INNER);
		verify(sbomComponentService).requestReconcile(PRODUCT_RELEASE);
	}

	// T-22 (b): the forced outer merge forces the bundled product and, below it, the changed release,
	// whatever the order the products come in; other component releases are served from their rows
	@Test
	void aForcedProductMergeForcesTheProductsItBundlesWithTheSameComponentFilter() {
		nestedProducts();
		UUID staleSource = UUID.randomUUID();
		UUID staleInner = UUID.randomUUID();
		storeRow(SOURCE, staleSource, rowOptions(BomStructureType.FLAT));
		storeRow(INNER, staleInner, rowOptions(BomStructureType.FLAT));
		storeRow(PRODUCT_RELEASE, UUID.randomUUID(), rowOptions(BomStructureType.FLAT));
		// The outer product first: the inner product's stale row has not been rebuilt when the outer merges.
		productsAbove(source, product, inner);

		svc.reconcileMergedSbomRoutine(source, WU);

		Merge innerWithinOuter = productMerges().get(0);
		assertEquals(INNER_VERSION, innerWithinOuter.options().version());
		assertEquals(List.of(A_FLAT, componentMerges().get(0).merged()), innerWithinOuter.bomIds(),
				"below the forced inner product the changed release is merged afresh, A is served");
		UUID outerMerge = onlyProductMergeOf(product.getVersion());
		Merge outer = productMerges().stream().filter(m -> m.merged().equals(outerMerge)).findFirst().orElseThrow();
		assertEquals(List.of(B_FLAT, innerWithinOuter.merged(), A_FLAT, componentMerges().get(0).merged()), outer.bomIds());
		assertFalse(outer.bomIds().contains(staleInner), "the inner product's stale row is not served");
		assertFalse(innerWithinOuter.bomIds().contains(staleSource), "the changed release's stale row is not served");
		// Only the changed release is merged at component level, once: A and B come from their rows.
		assertEquals(1, componentMerges().size(), componentMerges().toString());
		assertEquals(SOURCE_VERSION, componentMerges().get(0).options().version());
		// The routine's own turn at the inner product reuses the merge made inside the outer one (ADR-12).
		assertEquals(2, productMerges().size(), productMerges().toString());
		assertEquals(List.of(new ReleaseBom(innerWithinOuter.merged(), rowOptions(BomStructureType.FLAT))), rows.get(INNER));
	}

	// T-22 (c): an unforced export does not look the bundled releases up and serves the bundled product's row
	@Test
	void anUnforcedProductExportServesTheBundledProductsRowWithoutLookingItUp() throws Exception {
		nestedProducts();
		UUID innerRow = UUID.randomUUID();
		UUID sourceRow = UUID.randomUUID();
		storeRow(INNER, innerRow, rowOptions(BomStructureType.FLAT));
		storeRow(SOURCE, sourceRow, rowOptions(BomStructureType.FLAT));

		UUID exported = svc.getReleaseBomId(PRODUCT_RELEASE, false, false, false, ArtifactBelongsTo.RELEASE,
				BomStructureType.FLAT, WU, null);

		assertEquals(List.of(product.getVersion()), productMergeVersions());
		assertEquals(onlyProductMergeOf(product.getVersion()), exported);
		assertEquals(List.of(B_FLAT, innerRow, A_FLAT, sourceRow), productMerges().get(0).bomIds());
		assertEquals(List.of(), componentMerges());
		verify(getComponentService, never()).getComponentData(INNER_PRODUCT);
	}
}
