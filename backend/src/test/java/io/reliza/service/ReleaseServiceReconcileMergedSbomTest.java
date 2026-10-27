/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.Rebom.MissingSbomComponent;
import io.reliza.model.Rebom.MissingSbomMode;
import io.reliza.model.Rebom.MissingSbomReason;
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
	/** TEA-15: the OMIT twin row a TEA publish of the product leaves on OTHER. */
	private static final UUID OTHER_FLAT_OMIT = UUID.randomUUID();
	/** TEA-15: a second product above the source that never published on TEA. */
	private static final UUID SECOND = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000d3");
	private static final MissingSbomMode OMIT = MissingSbomMode.OMIT;
	private static final String SOURCE_VERSION = "3.0.0";
	// ADR-11: the inner product and the releases it and the outer product bundle
	private static final UUID INNER = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e1");
	private static final UUID BUNDLED_A = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e2");
	private static final UUID BUNDLED_B = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-0000000000e3");
	private static final UUID A_FLAT = UUID.randomUUID();
	private static final UUID B_FLAT = UUID.randomUUID();
	private static final String INNER_VERSION = "1.1.0";

	private ReleaseData source;
	private ReleaseData other;
	private ReleaseData inner;

	/** The product holds SOURCE (just got its first SBOM) and OTHER (cached under both structures). */
	private void productOfSourceAndOther() {
		source = withBom(SOURCE, SOURCE_VERSION, null);
		other = withBom(OTHER, "1.0.0", null);
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

		svc.reconcileMergedSbomRoutine(source.getUuid(), WU);

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

		svc.reconcileMergedSbomRoutine(source.getUuid(), WU);

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

		svc.reconcileMergedSbomRoutine(source.getUuid(), WU);

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

		svc.reconcileMergedSbomRoutine(source.getUuid(), WU);

		assertEquals(2, productMerges().size(), productMerges().toString());
		UUID freshHierarchical = sourceMergeUnder(BomStructureType.HIERARCHICAL);
		UUID freshFlat = sourceMergeUnder(BomStructureType.FLAT);
		// TEA-14 decision 2: the changed release's own rows are rebuilt in the routine's first turn, then the
		// product's options force it under those; each rebuilt row replaces its stale one.
		assertEquals(List.of(new ReleaseBom(freshHierarchical, rowOptions(BomStructureType.HIERARCHICAL)),
				new ReleaseBom(freshFlat, rowOptions(BomStructureType.FLAT))), rows.get(SOURCE));
	}

	// ---- TEA-15: the missing-SBOM mode on the reconcile ----

	private static MissingSbomComponent missing(UUID release, String version, MissingSbomReason reason) {
		return new MissingSbomComponent(release.toString(), "component", version, "library", "Example Org",
				"Example Org", null, null, null, null, reason);
	}

	// TEA-15 F-5 and F-6: a source's OMIT row rebuilds only the products that already hold an OMIT row
	@Test
	void aSourcesOmitRowRebuildsOnlyTheProductsThatHoldAnOmitRow() throws Exception {
		productOfSourceAndOther();
		storeRow(OTHER, OTHER_FLAT_OMIT, rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT));
		UUID staleSourceFlat = UUID.randomUUID();
		UUID staleSourceOmit = UUID.randomUUID();
		storeRow(SOURCE, staleSourceFlat, rowOptions(BomStructureType.FLAT));
		storeRow(SOURCE, staleSourceOmit, rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT));
		UUID staleProductFlat = UUID.randomUUID();
		UUID staleProductOmit = UUID.randomUUID();
		storeRow(PRODUCT_RELEASE, staleProductFlat, rowOptions(BomStructureType.FLAT));
		storeRow(PRODUCT_RELEASE, staleProductOmit, rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT));
		ReleaseData second = innerProductRelease(SECOND, "5.0.0");
		UUID staleSecond = UUID.randomUUID();
		storeRow(SECOND, staleSecond, rowOptions(BomStructureType.FLAT));
		unwinds(second, other, source);
		productsAbove(source, product, second);

		svc.reconcileMergedSbomRoutine(source.getUuid(), WU);

		assertEquals(2, componentMerges().size(),
				"the source once per mode; OTHER is served from its rows under both modes, never merged: " + merges);
		assertEquals(5, merges.size(), merges.toString());
		UUID sourceFlat = componentMerges().get(0).merged();
		UUID sourceOmit = componentMerges().get(1).merged();
		assertEquals(List.of(new ReleaseBom(sourceFlat, rowOptions(BomStructureType.FLAT)),
				new ReleaseBom(sourceOmit, rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT))), rows.get(SOURCE));
		assertEquals(2, rowWrites.get(SOURCE));
		merges.forEach(m -> {
			assertFalse(m.bomIds().contains(staleSourceFlat), m.toString());
			assertFalse(m.bomIds().contains(staleSourceOmit), m.toString());
		});

		assertEquals(3, productMerges().size(), productMerges().toString());
		assertEquals(product.getVersion(), productMerges().get(0).options().version());
		assertEquals(product.getVersion(), productMerges().get(1).options().version());
		assertEquals("5.0.0", productMerges().get(2).options().version());
		assertEquals(List.of(OTHER_FLAT, sourceFlat), productMerges().get(0).bomIds());
		assertEquals(List.of(OTHER_FLAT_OMIT, sourceOmit), productMerges().get(1).bomIds(),
				"the product's OMIT rebuild reads OTHER's OMIT row and the source's OMIT merge");
		assertEquals(List.of(OTHER_FLAT, sourceFlat), productMerges().get(2).bomIds());
		productMerges().forEach(m -> assertNull(m.options().missingSbomComponents(), m.toString()));
		merges.forEach(m -> assertNull(m.options().missingSbomMode(), "the mode is on the rows, never in the rebom request"));

		assertEquals(List.of(new ReleaseBom(productMerges().get(0).merged(), rowOptions(BomStructureType.FLAT)),
				new ReleaseBom(productMerges().get(1).merged(), rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT))),
				rows.get(PRODUCT_RELEASE), "both rows of a TEA-published product are rebuilt, each in its mode");
		assertEquals(2, rowWrites.get(PRODUCT_RELEASE));

		assertEquals(List.of(new ReleaseBom(productMerges().get(2).merged(), rowOptions(BomStructureType.FLAT))),
				rows.get(SECOND));
		assertFalse(Utils.OM.writeValueAsString(rows.get(SECOND).get(0)).contains("missingSbomMode"));
		assertEquals(1, rowWrites.get(SECOND), "no OMIT row is written for a product that never published");

		assertEquals(List.of(new ReleaseBom(OTHER_FLAT, rowOptions(BomStructureType.FLAT)),
				new ReleaseBom(OTHER_HIERARCHICAL, rowOptions(BomStructureType.HIERARCHICAL)),
				new ReleaseBom(OTHER_FLAT_OMIT, rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT))), rows.get(OTHER));
		assertFalse(rowWrites.containsKey(OTHER), "OTHER is served, not merged, under both modes");
	}

	// TEA-15: an OMIT row is rebuilt under OMIT, a placeholder row under PLACEHOLDER
	@Test
	void anOmitRowIsRebuiltUnderOmitAndAPlaceholderRowUnderPlaceholder() {
		source = componentRelease(SOURCE, SOURCE_VERSION);
		other = withBom(OTHER, "1.0.0", null);
		storeRow(OTHER, OTHER_FLAT, rowOptions(BomStructureType.FLAT));
		storeRow(OTHER, OTHER_FLAT_OMIT, rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT));
		unwindsTo(other, source);
		productsAbove(source, product);
		UUID staleFlat = UUID.randomUUID();
		UUID staleOmit = UUID.randomUUID();
		storeRow(PRODUCT_RELEASE, staleFlat, rowOptions(BomStructureType.FLAT));
		storeRow(PRODUCT_RELEASE, staleOmit, rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT));

		svc.reconcileMergedSbomRoutine(source.getUuid(), WU);

		assertEquals(List.of(), componentMerges(), "OTHER is served under both modes and the source has nothing to merge");
		assertEquals(2, merges.size(), merges.toString());
		assertEquals(List.of(OTHER_FLAT), productMerges().get(0).bomIds());
		assertEquals(List.of(missing(SOURCE, SOURCE_VERSION, MissingSbomReason.NO_SBOM_ARTIFACT)),
				productMerges().get(0).options().missingSbomComponents());
		assertEquals(List.of(OTHER_FLAT_OMIT), productMerges().get(1).bomIds());
		assertNull(productMerges().get(1).options().missingSbomComponents(),
				"the OMIT rebuild leaves the source out and sends no placeholder");
		productMerges().forEach(m -> assertNull(m.options().missingSbomMode(), m.toString()));
		assertEquals(List.of(new ReleaseBom(productMerges().get(0).merged(), rowOptions(BomStructureType.FLAT)),
				new ReleaseBom(productMerges().get(1).merged(), rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT))),
				rows.get(PRODUCT_RELEASE), "each row is replaced in place under its own mode");
		assertEquals(2, rowWrites.get(PRODUCT_RELEASE));
		assertEquals(Set.of(PRODUCT_RELEASE), rowWrites.keySet(), "no row written for SOURCE or OTHER");
		assertEquals(List.of(new ReleaseBom(OTHER_FLAT, rowOptions(BomStructureType.FLAT)),
				new ReleaseBom(OTHER_FLAT_OMIT, rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT))), rows.get(OTHER));
		assertTrue(rows.getOrDefault(SOURCE, List.of()).isEmpty());
	}

	/**
	 * TEA-15 run-1 T-1, design 3.3: a source's OMIT options under knobs the product holds no row for still
	 * reach a product that holds an OMIT row under other knobs, so a TEA-published product gets the OMIT
	 * row the source's own rows imply. A product holding no OMIT row does not.
	 */
	@Test
	void aTeaPublishedProductTakesTheSourcesOmitOptionsUnderOtherKnobs() {
		productOfSourceAndOther();
		UUID otherFlatOmit = UUID.randomUUID();
		UUID otherHierarchicalOmit = UUID.randomUUID();
		storeRow(OTHER, otherFlatOmit, rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT));
		storeRow(OTHER, otherHierarchicalOmit, rowOptions(BomStructureType.HIERARCHICAL).withMissingSbomMode(OMIT));
		storeRow(SOURCE, UUID.randomUUID(), rowOptions(BomStructureType.FLAT));
		storeRow(SOURCE, UUID.randomUUID(), rowOptions(BomStructureType.HIERARCHICAL).withMissingSbomMode(OMIT));
		storeRow(PRODUCT_RELEASE, UUID.randomUUID(), rowOptions(BomStructureType.FLAT));
		storeRow(PRODUCT_RELEASE, UUID.randomUUID(), rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT));
		ReleaseData second = innerProductRelease(SECOND, "5.0.0");
		storeRow(SECOND, UUID.randomUUID(), rowOptions(BomStructureType.FLAT));
		unwinds(second, other, source);
		productsAbove(source, product, second);

		svc.reconcileMergedSbomRoutine(source.getUuid(), WU);

		// The source under its two rows' options, then under the product's FLAT OMIT row; OTHER is always served.
		assertEquals(3, componentMerges().size(), componentMerges().toString());
		UUID sourceFlat = componentMerges().get(0).merged();
		UUID sourceHierarchicalOmit = componentMerges().get(1).merged();
		UUID sourceFlatOmit = componentMerges().get(2).merged();
		assertEquals(BomStructureType.HIERARCHICAL, componentMerges().get(1).options().structure());
		assertEquals(BomStructureType.FLAT, componentMerges().get(2).options().structure());

		assertEquals(4, productMerges().size(), productMerges().toString());
		List<Merge> ofProduct = productMerges().stream()
				.filter(m -> product.getVersion().equals(m.options().version())).toList();
		assertEquals(3, ofProduct.size(), "the product's two rows plus the source's HIERARCHICAL OMIT options: " + ofProduct);
		assertEquals(List.of(OTHER_FLAT, sourceFlat), ofProduct.get(0).bomIds());
		assertEquals(List.of(otherFlatOmit, sourceFlatOmit), ofProduct.get(1).bomIds());
		assertEquals(BomStructureType.HIERARCHICAL, ofProduct.get(2).options().structure());
		assertEquals(List.of(otherHierarchicalOmit, sourceHierarchicalOmit), ofProduct.get(2).bomIds(),
				"the new OMIT rebuild reads OTHER's HIERARCHICAL OMIT row and the source's HIERARCHICAL OMIT merge");
		ofProduct.forEach(m -> assertNull(m.options().missingSbomComponents(), m.toString()));
		assertEquals(List.of(new ReleaseBom(ofProduct.get(0).merged(), rowOptions(BomStructureType.FLAT)),
				new ReleaseBom(ofProduct.get(1).merged(), rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT)),
				new ReleaseBom(ofProduct.get(2).merged(),
						rowOptions(BomStructureType.HIERARCHICAL).withMissingSbomMode(OMIT))),
				rows.get(PRODUCT_RELEASE), "the TEA-published product gains a HIERARCHICAL OMIT row");
		assertEquals(3, rowWrites.get(PRODUCT_RELEASE));

		Merge ofSecond = productMerges().get(3);
		assertEquals("5.0.0", ofSecond.options().version());
		assertEquals(List.of(OTHER_FLAT, sourceFlat), ofSecond.bomIds());
		assertEquals(List.of(new ReleaseBom(ofSecond.merged(), rowOptions(BomStructureType.FLAT))), rows.get(SECOND),
				"a product holding no OMIT row takes none of the source's OMIT options");
		assertEquals(1, rowWrites.get(SECOND));
		assertFalse(rowWrites.containsKey(OTHER), "OTHER is served under every options, never merged");
	}

	// ---- SCORE-71 ADR-5: a merge that finds nothing drops its request's row ----

	// SCORE-71 T-4 (a): the source lost its last SBOM; the reconcile drops both its rows and the product
	// lists it without an SBOM again (placeholder) or leaves it out (OMIT)
	@Test
	void aReleaseThatLostItsLastSbomDropsItsRowsAndTheProductListsItWithoutAnSbomAgain() {
		source = componentRelease(SOURCE, SOURCE_VERSION);
		other = withBom(OTHER, "1.0.0", null);
		storeRow(OTHER, OTHER_FLAT, rowOptions(BomStructureType.FLAT));
		storeRow(OTHER, OTHER_FLAT_OMIT, rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT));
		storeRow(SOURCE, UUID.randomUUID(), rowOptions(BomStructureType.FLAT));
		storeRow(SOURCE, UUID.randomUUID(), rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT));
		storeRow(PRODUCT_RELEASE, UUID.randomUUID(), rowOptions(BomStructureType.FLAT));
		storeRow(PRODUCT_RELEASE, UUID.randomUUID(), rowOptions(BomStructureType.FLAT).withMissingSbomMode(OMIT));
		unwindsTo(source, other);
		productsAbove(source, product);

		svc.reconcileMergedSbomRoutine(SOURCE, WU);

		assertTrue(rows.get(SOURCE).isEmpty(), "both stale rows of the source are dropped: " + rows.get(SOURCE));
		assertEquals(2, rowWrites.get(SOURCE), "one write per request");
		assertEquals(List.of(), componentMerges());
		Merge flat = productMerges().stream().filter(m -> null != m.options().missingSbomComponents()).findFirst().orElseThrow();
		assertEquals(List.of(OTHER_FLAT), flat.bomIds());
		assertEquals(List.of(missing(SOURCE, SOURCE_VERSION, MissingSbomReason.NO_SBOM_ARTIFACT)),
				flat.options().missingSbomComponents());
		Merge omit = productMerges().stream().filter(m -> m != flat).findFirst().orElseThrow();
		assertEquals(List.of(OTHER_FLAT_OMIT), omit.bomIds());
		assertNull(omit.options().missingSbomComponents());
		assertEquals(2, productMerges().size(), productMerges().toString());
		assertEquals(Set.of(flat.merged(), omit.merged()),
				Set.copyOf(rows.get(PRODUCT_RELEASE).stream().map(ReleaseBom::rebomId).toList()),
				"the product holds its two fresh rows");
	}

	// SCORE-71 T-4 (b): a release with neither rows nor an SBOM writes nothing of its own
	@Test
	void aReleaseWithoutRowsAndWithoutAnSbomWritesNothing() {
		source = componentRelease(SOURCE, SOURCE_VERSION);
		other = withBom(OTHER, "1.0.0", null);
		storeRow(OTHER, OTHER_FLAT, rowOptions(BomStructureType.FLAT));
		storeRow(PRODUCT_RELEASE, UUID.randomUUID(), rowOptions(BomStructureType.FLAT));
		unwindsTo(source, other);
		productsAbove(source, product);

		svc.reconcileMergedSbomRoutine(SOURCE, WU);

		assertEquals(Set.of(PRODUCT_RELEASE), rowWrites.keySet());
	}

	// SCORE-71 T-4 (d), the coverage gate: a coverage-filtered request that finds nothing drops no row
	@Test
	void aCoverageFilteredRequestThatFindsNothingDropsNoRow() throws Exception {
		source = withBom(SOURCE, SOURCE_VERSION, ArtifactCoverageType.DEV);
		UUID cached = UUID.randomUUID();
		storeRow(SOURCE, cached, rowOptions(BomStructureType.FLAT));

		Optional<UUID> result = svc.matchOrGenerateSingleBomForRelease(source, rowOptions(BomStructureType.FLAT), false,
				null, WU, List.of(ArtifactCoverageType.DEV), null, new ReleaseService.MergeContext());

		assertEquals(Optional.empty(), result);
		assertEquals(List.of(), merges);
		assertEquals(Map.of(), rowWrites);
		assertEquals(List.of(new ReleaseBom(cached, rowOptions(BomStructureType.FLAT))), rows.get(SOURCE));
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

		svc.reconcileMergedSbomRoutine(source.getUuid(), WU);

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

		svc.reconcileMergedSbomRoutine(source.getUuid(), WU);

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

	// T-22 (c): an unforced export does not unwind the bundled product and serves its row
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
		// SCORE-39 ADR-5: the row is matched on the release node facts, which read the component;
		// what stays pinned is that the served product is not unwound.
		verify(sharedReleaseService, never()).unwindReleaseDependencies(inner);
	}
}
