/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData;
import io.reliza.model.DeliverableData;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.Rebom.MissingSbomMode;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.VariantData;
import io.reliza.service.RebomService.BomStructureType;

/**
 * SCORE-57 (T-18, ADR-11), ported after TEA as SCORE-62 (T-18, T-18b; ADR-2 to ADR-4): a release
 * merge sends rebom, as {@code releaseRootIds}, the ids of the merging release's own
 * RELEASE-attached BOM artifacts, exactly the RELEASE block of the merge ids and in its order; a
 * merge whose effective sources leave RELEASE out, and a release without such a BOM, send none. A
 * product merge lists the product's own RELEASE-attached uploads only, never its SCE uploads or a
 * component row. The list is never on a stored row and is not part of the match. SCORE-63 (T-11,
 * ADR-5): the same merges send, as {@code attachedRootIds}, the BOM ids of the base variant's
 * outbound deliverables and of the SCE block, never an inbound deliverable's or the RELEASE block.
 */
class ReleaseServiceReleaseRootIdsTest extends ReleaseServiceMergeTestBase {

	private static final UUID RELEASE = UUID.fromString("0b0f4a52-6c55-4d1f-9a8e-000000005701");
	private static final UUID SCE = UUID.randomUUID();
	private static final UUID DELIVERABLE = UUID.randomUUID();
	private static final UUID PRODUCT_SCE = UUID.randomUUID();
	private static final UUID INBOUND = UUID.randomUUID();

	private VariantService variantService;
	private GetDeliverableService getDeliverableService;
	private GetSourceCodeEntryService getSourceCodeEntryService;
	private final List<UUID> deliverableArtifacts = new ArrayList<>();
	private final List<SCEArtifact> sceArtifacts = new ArrayList<>();
	private final List<SCEArtifact> productSceArtifacts = new ArrayList<>();
	private final List<UUID> productArtifacts = new ArrayList<>();
	private final List<UUID> inboundArtifacts = new ArrayList<>();
	private DeliverableData dd;

	@BeforeEach
	void wireDeliverablesAndSourceCode() {
		variantService = mock(VariantService.class);
		getDeliverableService = mock(GetDeliverableService.class);
		getSourceCodeEntryService = mock(GetSourceCodeEntryService.class);
		ReflectionTestUtils.setField(svc, "variantService", variantService);
		ReflectionTestUtils.setField(svc, "getDeliverableService", getDeliverableService);
		ReflectionTestUtils.setField(svc, "getSourceCodeEntryService", getSourceCodeEntryService);
		deliverableArtifacts.clear();
		sceArtifacts.clear();
		productSceArtifacts.clear();
		productArtifacts.clear();
		inboundArtifacts.clear();
		dd = mock(DeliverableData.class);
		// SCORE-63 T-11: the outbound filter of ADR-5 keys on the deliverable's uuid
		when(dd.getUuid()).thenReturn(DELIVERABLE);
		when(dd.getArtifacts()).thenReturn(deliverableArtifacts);
		when(getDeliverableService.getDeliverableDataList(any())).thenReturn(List.of(dd));
		SourceCodeEntryData sced = mock(SourceCodeEntryData.class);
		when(sced.getArtifacts()).thenReturn(sceArtifacts);
		when(getSourceCodeEntryService.getSourceCodeEntryData(SCE)).thenReturn(Optional.of(sced));
		// the product release's own SBOM artifacts (TEA-1): RELEASE-attached and SCE-attached
		when(product.getArtifacts()).thenReturn(productArtifacts);
		when(product.getSourceCodeEntry()).thenReturn(PRODUCT_SCE);
		SourceCodeEntryData productSced = mock(SourceCodeEntryData.class);
		when(productSced.getArtifacts()).thenReturn(productSceArtifacts);
		when(getSourceCodeEntryService.getSourceCodeEntryData(PRODUCT_SCE)).thenReturn(Optional.of(productSced));
	}

	/** The component release, with a base variant shipping {@link #DELIVERABLE} and source code entry {@link #SCE}. */
	private ReleaseData release() {
		ReleaseData rd = componentRelease(RELEASE, "1.0.0");
		when(rd.getInboundDeliverables()).thenReturn(new ArrayList<>());
		when(rd.getSourceCodeEntry()).thenReturn(SCE);
		VariantData base = mock(VariantData.class);
		when(base.getOutboundDeliverables()).thenReturn(new LinkedHashSet<>(List.of(DELIVERABLE)));
		when(variantService.getBaseVariantForRelease(rd)).thenReturn(base);
		return rd;
	}

	/** The component release, with a base variant that ships no deliverable. */
	private ReleaseData releaseWithoutOutbound() {
		ReleaseData rd = release();
		VariantData base = mock(VariantData.class);
		when(base.getOutboundDeliverables()).thenReturn(new LinkedHashSet<>());
		when(variantService.getBaseVariantForRelease(rd)).thenReturn(base);
		return rd;
	}

	/**
	 * SCORE-63 T-11: a BOM attached to an inbound deliverable {@link #INBOUND} of {@code rd} (one the
	 * release takes in); {@code getAllDeliverableDataFromRelease} lists it before the outbound one.
	 * Returns its rebom id.
	 */
	private UUID inboundBom(ReleaseData rd) {
		UUID artifact = UUID.randomUUID();
		UUID rebomId = UUID.randomUUID();
		ArtifactData ad = mock(ArtifactData.class);
		when(ad.getInternalBom()).thenReturn(new InternalBom(rebomId, ArtifactBelongsTo.DELIVERABLE));
		when(ad.getTags()).thenReturn(List.of());
		when(artifactService.getArtifactData(artifact)).thenReturn(Optional.of(ad));
		inboundArtifacts.add(artifact);
		DeliverableData inbound = mock(DeliverableData.class);
		when(inbound.getUuid()).thenReturn(INBOUND);
		when(inbound.getArtifacts()).thenReturn(inboundArtifacts);
		when(rd.getInboundDeliverables()).thenAnswer(i -> new ArrayList<>(List.of(INBOUND)));
		when(getDeliverableService.getDeliverableDataList(any())).thenReturn(List.of(inbound, dd));
		return rebomId;
	}

	/** A stored BOM artifact of {@code kind}, tagged {@code coverage} when not null; returns its rebom id. */
	private UUID bom(ReleaseData rd, ArtifactBelongsTo kind, ArtifactCoverageType coverage) {
		UUID artifact = UUID.randomUUID();
		UUID rebomId = UUID.randomUUID();
		ArtifactData ad = mock(ArtifactData.class);
		when(ad.getInternalBom()).thenReturn(new InternalBom(rebomId, kind));
		when(ad.getTags()).thenReturn(null == coverage ? List.of()
				: List.of(new TagRecord(CommonVariables.ARTIFACT_COVERAGE_TYPE_TAG_KEY, coverage.name())));
		when(artifactService.getArtifactData(artifact)).thenReturn(Optional.of(ad));
		if (rd == product) {
			switch (kind) {
				case SCE -> productSceArtifacts.add(new SCEArtifact(artifact, PRODUCT));
				case RELEASE -> productArtifacts.add(artifact);
				default -> throw new IllegalArgumentException("a product carries no " + kind + " BOM of its own here");
			}
			return rebomId;
		}
		switch (kind) {
			case DELIVERABLE -> deliverableArtifacts.add(artifact);
			case SCE -> sceArtifacts.add(new SCEArtifact(artifact, COMPONENT));
			default -> rd.getArtifacts().add(artifact);
		}
		return rebomId;
	}

	/**
	 * A merge with the knobs a TEA publication uses ({@code generateMergedBomForPublication}): forced,
	 * {@link MissingSbomMode#OMIT}, a two-element source set as the set filter with belongsTo null.
	 */
	private void publish(ReleaseData rd, Set<ArtifactBelongsTo> sources) throws Exception {
		RebomOptions options = new RebomOptions(null, false, false, null, BomStructureType.FLAT)
				.withMissingSbomMode(MissingSbomMode.OMIT);
		svc.matchOrGenerateSingleBomForRelease(rd, options, true, null, WU, null, sources);
	}

	private Merge onlyProductMerge() {
		assertEquals(1, productMerges().size(), merges.toString());
		return productMerges().get(0);
	}

	/** The one component merge built from {@code bomIds}. */
	private Merge componentMergeOf(List<UUID> bomIds) {
		return componentMerges().stream().filter(m -> m.bomIds().equals(bomIds)).findFirst()
				.orElseThrow(() -> new AssertionError("no component merge of " + bomIds + " in " + merges));
	}

	private UUID request(UUID release, ArtifactBelongsTo belongsTo, List<ArtifactCoverageType> excludeCoverageTypes) throws Exception {
		return svc.getReleaseBomId(release, false, false, false, belongsTo, BomStructureType.FLAT, WU, excludeCoverageTypes);
	}

	private Merge onlyComponentMerge() {
		assertEquals(1, componentMerges().size(), merges.toString());
		return componentMerges().get(0);
	}

	@Test
	void theUnfilteredMergeListsTheReleaseAttachedIdsInTheirOrderAsASubsetOfTheIds() throws Exception {
		ReleaseData rd = release();
		UUID deliverable = bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		UUID sce = bom(rd, ArtifactBelongsTo.SCE, null);
		UUID release1 = bom(rd, ArtifactBelongsTo.RELEASE, null);
		UUID release2 = bom(rd, ArtifactBelongsTo.RELEASE, null);

		request(RELEASE, null, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(deliverable, sce, release1, release2), merge.bomIds());
		assertEquals(List.of(release1, release2), merge.options().releaseRootIds());
	}

	@Test
	void theReleaseFilteredMergeSendsTheSameIdsAsBoth() throws Exception {
		ReleaseData rd = release();
		bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		bom(rd, ArtifactBelongsTo.SCE, null);
		UUID release1 = bom(rd, ArtifactBelongsTo.RELEASE, null);
		UUID release2 = bom(rd, ArtifactBelongsTo.RELEASE, null);

		request(RELEASE, ArtifactBelongsTo.RELEASE, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(release1, release2), merge.bomIds());
		assertEquals(List.of(release1, release2), merge.options().releaseRootIds());
	}

	@Test
	void theDeliverableFilteredMergeSendsNone() throws Exception {
		ReleaseData rd = release();
		UUID deliverable = bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		bom(rd, ArtifactBelongsTo.RELEASE, null);

		request(RELEASE, ArtifactBelongsTo.DELIVERABLE, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(deliverable), merge.bomIds());
		assertNull(merge.options().releaseRootIds());
	}

	@Test
	void theSceFilteredMergeSendsNone() throws Exception {
		ReleaseData rd = release();
		UUID sce = bom(rd, ArtifactBelongsTo.SCE, null);
		bom(rd, ArtifactBelongsTo.RELEASE, null);

		request(RELEASE, ArtifactBelongsTo.SCE, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(sce), merge.bomIds());
		assertNull(merge.options().releaseRootIds());
	}

	@Test
	void aReleaseAttachedBomTheCoverageFilterLeavesOutIsInNeitherList() throws Exception {
		ReleaseData rd = release();
		UUID deliverable = bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		UUID kept = bom(rd, ArtifactBelongsTo.RELEASE, null);
		UUID excluded = bom(rd, ArtifactBelongsTo.RELEASE, ArtifactCoverageType.DEV);

		request(RELEASE, null, List.of(ArtifactCoverageType.DEV));

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(deliverable, kept), merge.bomIds());
		assertEquals(List.of(kept), merge.options().releaseRootIds());
		assertTrue(!merge.bomIds().contains(excluded));
	}

	@Test
	void aReleaseWithoutAReleaseAttachedBomSendsNone() throws Exception {
		ReleaseData rd = release();
		UUID deliverable = bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		UUID sce = bom(rd, ArtifactBelongsTo.SCE, null);

		request(RELEASE, null, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(deliverable, sce), merge.bomIds());
		assertNull(merge.options().releaseRootIds());
	}

	// SCORE-63 T-11 (ADR-5): the outbound deliverable's BOM and the SCE block, in gather order, never the RELEASE block
	@Test
	void theUnfilteredMergeListsTheDeliverableAndSceAttachedIdsAsAttachedRoots() throws Exception {
		ReleaseData rd = release();
		UUID deliverable = bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		UUID sce = bom(rd, ArtifactBelongsTo.SCE, null);
		UUID release1 = bom(rd, ArtifactBelongsTo.RELEASE, null);
		UUID release2 = bom(rd, ArtifactBelongsTo.RELEASE, null);

		request(RELEASE, null, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(deliverable, sce, release1, release2), merge.bomIds());
		assertEquals(List.of(release1, release2), merge.options().releaseRootIds());
		assertEquals(List.of(deliverable, sce), merge.options().attachedRootIds());
	}

	// SCORE-63 T-11
	@Test
	void theDeliverableFilteredMergeListsTheDeliverableBomAsAnAttachedRoot() throws Exception {
		ReleaseData rd = release();
		UUID deliverable = bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		bom(rd, ArtifactBelongsTo.SCE, null);
		bom(rd, ArtifactBelongsTo.RELEASE, null);

		request(RELEASE, ArtifactBelongsTo.DELIVERABLE, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(deliverable), merge.options().attachedRootIds());
		assertNull(merge.options().releaseRootIds());
	}

	// SCORE-63 T-11
	@Test
	void theSceFilteredMergeListsTheSceBomAsAnAttachedRoot() throws Exception {
		ReleaseData rd = release();
		bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		UUID sce = bom(rd, ArtifactBelongsTo.SCE, null);
		bom(rd, ArtifactBelongsTo.RELEASE, null);

		request(RELEASE, ArtifactBelongsTo.SCE, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(sce), merge.options().attachedRootIds());
		assertNull(merge.options().releaseRootIds());
	}

	// SCORE-63 T-11
	@Test
	void theReleaseFilteredMergeListsNoAttachedRoot() throws Exception {
		ReleaseData rd = release();
		bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		bom(rd, ArtifactBelongsTo.SCE, null);
		bom(rd, ArtifactBelongsTo.RELEASE, null);

		request(RELEASE, ArtifactBelongsTo.RELEASE, null);

		assertNull(onlyComponentMerge().options().attachedRootIds());
	}

	// SCORE-63 T-11 (F-1, ADR-4 (vi)): an inbound deliverable's BOM is merged as today and never listed
	@Test
	void anInboundDeliverablesBomIsMergedButNotListedInTheUnfilteredMerge() throws Exception {
		ReleaseData rd = release();
		UUID inbound = inboundBom(rd);
		UUID deliverable = bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		UUID sce = bom(rd, ArtifactBelongsTo.SCE, null);
		UUID release1 = bom(rd, ArtifactBelongsTo.RELEASE, null);

		request(RELEASE, null, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(inbound, deliverable, sce, release1), merge.bomIds());
		assertEquals(List.of(deliverable, sce), merge.options().attachedRootIds());
	}

	// SCORE-63 T-11 (F-1)
	@Test
	void anInboundDeliverablesBomIsMergedButNotListedInTheDeliverableFilteredMerge() throws Exception {
		ReleaseData rd = release();
		UUID inbound = inboundBom(rd);
		UUID deliverable = bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		bom(rd, ArtifactBelongsTo.SCE, null);

		request(RELEASE, ArtifactBelongsTo.DELIVERABLE, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(inbound, deliverable), merge.bomIds());
		assertEquals(List.of(deliverable), merge.options().attachedRootIds());
	}

	// SCORE-63 T-11 (F-1)
	@Test
	void aReleaseWhoseOnlyDeliverableBomIsInboundListsItsSceBlockOnly() throws Exception {
		ReleaseData rd = release();
		UUID inbound = inboundBom(rd);
		UUID sce = bom(rd, ArtifactBelongsTo.SCE, null);

		request(RELEASE, null, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(inbound, sce), merge.bomIds());
		assertEquals(List.of(sce), merge.options().attachedRootIds());
	}

	// SCORE-63 T-11 (F-1)
	@Test
	void aReleaseWhoseOnlyAttachedBomIsInboundListsNone() throws Exception {
		ReleaseData rd = release();
		UUID inbound = inboundBom(rd);
		UUID release1 = bom(rd, ArtifactBelongsTo.RELEASE, null);

		request(RELEASE, null, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(inbound, release1), merge.bomIds());
		assertNull(merge.options().attachedRootIds());
	}

	// SCORE-63 T-11
	@Test
	void aReleaseWhoseBaseVariantShipsNoDeliverableListsItsSceBlockOnly() throws Exception {
		ReleaseData rd = releaseWithoutOutbound();
		UUID deliverable = bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		UUID sce = bom(rd, ArtifactBelongsTo.SCE, null);

		request(RELEASE, null, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(deliverable, sce), merge.bomIds());
		assertEquals(List.of(sce), merge.options().attachedRootIds());
	}

	// SCORE-63 T-11
	@Test
	void anAttachedBomTheCoverageFilterLeavesOutIsInNeitherList() throws Exception {
		ReleaseData rd = release();
		UUID deliverable = bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		UUID excluded = bom(rd, ArtifactBelongsTo.SCE, ArtifactCoverageType.DEV);

		request(RELEASE, null, List.of(ArtifactCoverageType.DEV));

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(deliverable), merge.bomIds());
		assertEquals(List.of(deliverable), merge.options().attachedRootIds());
		assertTrue(!merge.bomIds().contains(excluded));
	}

	// SCORE-63 T-11
	@Test
	void aReleaseWithoutAnAttachedBomListsNoAttachedRoot() throws Exception {
		ReleaseData rd = release();
		UUID release1 = bom(rd, ArtifactBelongsTo.RELEASE, null);

		request(RELEASE, null, null);

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(release1), merge.bomIds());
		assertNull(merge.options().attachedRootIds());
	}

	// SCORE-63 T-11 (ADR-6): the product lists its own SCE upload, never a component row
	@Test
	void theProductMergeListsItsOwnSceUploadAsAnAttachedRootAndNeverAComponentRow() throws Exception {
		ReleaseData child = release();
		UUID childSce = bom(child, ArtifactBelongsTo.SCE, null);
		UUID childRel = bom(child, ArtifactBelongsTo.RELEASE, null);
		UUID productSce = bom(product, ArtifactBelongsTo.SCE, null);
		bom(product, ArtifactBelongsTo.RELEASE, null);
		unwindsTo(child);

		request(PRODUCT_RELEASE, null, null);

		Merge childMerge = componentMergeOf(List.of(childSce, childRel));
		assertEquals(List.of(childSce), childMerge.options().attachedRootIds());
		Merge productMerge = onlyProductMerge();
		assertEquals(List.of(productSce), productMerge.options().attachedRootIds());
		assertTrue(!productMerge.options().attachedRootIds().contains(childMerge.merged()));
	}

	// SCORE-63 T-11
	@Test
	void aProductWithoutAnOwnSceUploadListsNoAttachedRoot() throws Exception {
		ReleaseData child = release();
		bom(child, ArtifactBelongsTo.SCE, null);
		bom(product, ArtifactBelongsTo.RELEASE, null);
		unwindsTo(child);

		request(PRODUCT_RELEASE, null, null);

		assertNull(onlyProductMerge().options().attachedRootIds());
	}

	// SCORE-63 T-11: a publication's set filters the list like the merge ids
	@Test
	void aComponentPublishedWithTheSceAndDeliverableSourcesListsBothBlocks() throws Exception {
		ReleaseData rd = release();
		UUID deliverable = bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		UUID sce = bom(rd, ArtifactBelongsTo.SCE, null);
		bom(rd, ArtifactBelongsTo.RELEASE, null);

		publish(rd, EnumSet.of(ArtifactBelongsTo.DELIVERABLE, ArtifactBelongsTo.SCE));

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(deliverable, sce), merge.options().attachedRootIds());
		assertNull(merge.options().releaseRootIds());
	}

	// SCORE-63 T-11
	@Test
	void aComponentPublishedWithTheReleaseSourceAloneListsNoAttachedRoot() throws Exception {
		ReleaseData rd = release();
		bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		bom(rd, ArtifactBelongsTo.SCE, null);
		UUID rel = bom(rd, ArtifactBelongsTo.RELEASE, null);

		publish(rd, EnumSet.of(ArtifactBelongsTo.RELEASE));

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(rel), merge.bomIds());
		assertNull(merge.options().attachedRootIds());
	}

	// SCORE-62 T-18 (ADR-3): the product lists its own RELEASE-attached upload and nothing else
	@Test
	void theUnfilteredProductMergeListsItsOwnReleaseAttachedUploadOnly() throws Exception {
		ReleaseData child = release();
		UUID childSce = bom(child, ArtifactBelongsTo.SCE, null);
		UUID childRel = bom(child, ArtifactBelongsTo.RELEASE, null);
		UUID productSce = bom(product, ArtifactBelongsTo.SCE, null);
		UUID productRel = bom(product, ArtifactBelongsTo.RELEASE, null);
		unwindsTo(child);

		request(PRODUCT_RELEASE, null, null);

		Merge childMerge = componentMergeOf(List.of(childSce, childRel));
		assertEquals(List.of(childRel), childMerge.options().releaseRootIds());
		Merge productMerge = onlyProductMerge();
		assertEquals(List.of(productSce, productRel, childMerge.merged()), productMerge.bomIds(),
				"own ids first, then the component rows");
		assertEquals(List.of(productRel), productMerge.options().releaseRootIds());
	}

	// SCORE-62 T-18
	@Test
	void theSceFilteredProductMergeListsNone() throws Exception {
		ReleaseData child = release();
		UUID childSce = bom(child, ArtifactBelongsTo.SCE, null);
		bom(child, ArtifactBelongsTo.RELEASE, null);
		UUID productSce = bom(product, ArtifactBelongsTo.SCE, null);
		bom(product, ArtifactBelongsTo.RELEASE, null);
		unwindsTo(child);

		request(PRODUCT_RELEASE, ArtifactBelongsTo.SCE, null);

		Merge childMerge = componentMergeOf(List.of(childSce));
		assertNull(childMerge.options().releaseRootIds());
		Merge productMerge = onlyProductMerge();
		assertEquals(List.of(productSce, childMerge.merged()), productMerge.bomIds());
		assertNull(productMerge.options().releaseRootIds());
	}

	// SCORE-62 T-18
	@Test
	void theReleaseFilteredProductMergeListsItsOwnReleaseAttachedUpload() throws Exception {
		ReleaseData child = release();
		bom(child, ArtifactBelongsTo.SCE, null);
		UUID childRel = bom(child, ArtifactBelongsTo.RELEASE, null);
		bom(product, ArtifactBelongsTo.SCE, null);
		UUID productRel = bom(product, ArtifactBelongsTo.RELEASE, null);
		unwindsTo(child);

		request(PRODUCT_RELEASE, ArtifactBelongsTo.RELEASE, null);

		Merge childMerge = componentMergeOf(List.of(childRel));
		assertEquals(List.of(childRel), childMerge.options().releaseRootIds());
		Merge productMerge = onlyProductMerge();
		assertEquals(List.of(productRel, childMerge.merged()), productMerge.bomIds());
		assertEquals(List.of(productRel), productMerge.options().releaseRootIds());
	}

	// SCORE-57 T-18, kept: a product without an own upload lists none
	@Test
	void aProductWithoutAnOwnReleaseAttachedUploadSendsNoneAndItsIdsAreTheComponentRows() throws Exception {
		ReleaseData rd = release();
		UUID release1 = bom(rd, ArtifactBelongsTo.RELEASE, null);
		ReleaseData other = withBom(UUID.randomUUID(), "2.0.0", null);
		unwindsTo(rd, other);

		request(PRODUCT_RELEASE, ArtifactBelongsTo.RELEASE, null);

		Merge productMerge = onlyProductMerge();
		assertNull(productMerge.options().releaseRootIds());
		assertEquals(componentMerges().stream().map(Merge::merged).toList(), productMerge.bomIds());
		// the component release's own merge, under the product's request, lists its upload
		assertEquals(List.of(release1), componentMergeOf(List.of(release1)).options().releaseRootIds());
	}

	// SCORE-62 T-18b (ADR-4): a publication's two-element set without RELEASE has belongsTo null and lists none
	@Test
	void aComponentPublishedWithoutTheReleaseSourceListsNone() throws Exception {
		ReleaseData rd = release();
		UUID deliverable = bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		UUID sce = bom(rd, ArtifactBelongsTo.SCE, null);
		bom(rd, ArtifactBelongsTo.RELEASE, null);

		publish(rd, EnumSet.of(ArtifactBelongsTo.DELIVERABLE, ArtifactBelongsTo.SCE));

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(deliverable, sce), merge.bomIds());
		assertNull(merge.options().releaseRootIds());
	}

	// SCORE-62 T-18b
	@Test
	void aComponentPublishedWithTheReleaseSourceListsItsReleaseAttachedUpload() throws Exception {
		ReleaseData rd = release();
		bom(rd, ArtifactBelongsTo.DELIVERABLE, null);
		UUID sce = bom(rd, ArtifactBelongsTo.SCE, null);
		UUID rel = bom(rd, ArtifactBelongsTo.RELEASE, null);

		publish(rd, EnumSet.of(ArtifactBelongsTo.SCE, ArtifactBelongsTo.RELEASE));

		Merge merge = onlyComponentMerge();
		assertEquals(List.of(sce, rel), merge.bomIds());
		assertEquals(List.of(rel), merge.options().releaseRootIds());
	}

	// SCORE-62 T-18b
	@Test
	void aProductPublishedWithoutTheReleaseSourceListsNone() throws Exception {
		ReleaseData child = release();
		bom(child, ArtifactBelongsTo.SCE, null);
		bom(product, ArtifactBelongsTo.RELEASE, null);
		unwindsTo(child);

		publish(product, EnumSet.of(ArtifactBelongsTo.DELIVERABLE, ArtifactBelongsTo.SCE));

		assertNull(onlyProductMerge().options().releaseRootIds());
	}

	// SCORE-62 T-18b
	@Test
	void aProductPublishedWithTheReleaseSourceListsItsOwnReleaseAttachedUpload() throws Exception {
		ReleaseData child = release();
		bom(child, ArtifactBelongsTo.SCE, null);
		UUID productRel = bom(product, ArtifactBelongsTo.RELEASE, null);
		unwindsTo(child);

		publish(product, EnumSet.of(ArtifactBelongsTo.SCE, ArtifactBelongsTo.RELEASE));

		assertEquals(List.of(productRel), onlyProductMerge().options().releaseRootIds());
	}

	@Test
	void everyStoredRowCarriesNoListAndARowStoredWithoutItIsServedUnderTheCurrentRules() throws Exception {
		ReleaseData rd = release();
		bom(rd, ArtifactBelongsTo.SCE, null);
		bom(rd, ArtifactBelongsTo.RELEASE, null);

		UUID first = request(RELEASE, null, null);
		assertTrue(null != onlyComponentMerge().options().attachedRootIds(), merges.toString());

		List<ReleaseBom> stored = rows.get(RELEASE);
		assertEquals(1, stored.size());
		assertNull(stored.get(0).rebomMergeOptions().releaseRootIds());
		// SCORE-63 T-11: nor the attached list
		assertNull(stored.get(0).rebomMergeOptions().attachedRootIds());
		assertEquals(first, stored.get(0).rebomId());

		// a row stored under the current rules before the field existed is served by the match
		UUID older = UUID.randomUUID();
		rows.put(RELEASE, new ArrayList<>(List.of(new ReleaseBom(older, rowOptions(BomStructureType.FLAT)))));
		merges.clear();

		assertEquals(older, request(RELEASE, ArtifactBelongsTo.RELEASE, null));
		assertTrue(merges.isEmpty(), merges.toString());
	}
}
