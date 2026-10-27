/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData;
import io.reliza.model.DeliverableData.SoftwareDeliverableMetadata;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.VariantData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.Rebom.BomHash;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.Rebom.ReleaseNodeFacts;
import io.reliza.model.DigestAlgorithm;
import io.reliza.common.Utils.RootComponentMergeMode;
import io.reliza.service.RebomService.BomStructureType;

/**
 * SCORE-13 (T-12, ADR-8): the merge rule version is part of the merged-BOM cache key. A row stored
 * under the current rules (MERGE_VERSION) is served; a row stored before (null) is not, and its
 * release is merged once more, with mergeVersion sent to rebom and stored on the new row, the old
 * row left in place. A product merge and the component merges it triggers all carry it.
 *
 * <p>SCORE-14 (T-14, ADR-5): the services carry moved the rules to 3, so a row stored under
 * SCORE-13's rules (2) is merged again. SCORE-15 (T-4, ADR-7): listing the component releases
 * without a BOM moved them to 4, so a row stored under SCORE-14's rules (3) is merged again. Every
 * expectation reads {@link ReleaseService#MERGE_VERSION}.
 *
 * <p>SCORE-30 (T-14, ADR-7, ADR-8): the release facts moved them to 6. The component merge and the
 * product merge each send the facts of the release they merge, set before the request leaves; the
 * stored row never carries them.
 *
 * <p>SCORE-39 (T-13, ADR-4, ADR-5): the declared licence and the component supplier moved them to 7.
 * A stored row carries the key of the release node facts it was built with and serves only a request
 * whose facts have that key; a row without one, or with another, is merged again. A new row replaces
 * every row of the same request whatever its version or key, so a release keeps one row per request;
 * rows of other requests stay. The facts are computed once per release per request.
 */
class ReleaseBomMergeVersionCacheTest {

	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();
	private static final UUID RELEASE = UUID.randomUUID();
	private static final UUID SECOND_RELEASE = UUID.randomUUID();
	private static final UUID PRODUCT_RELEASE = UUID.randomUUID();
	private static final UUID ORG = UUID.randomUUID();
	private static final UUID COMPONENT = UUID.randomUUID();
	private static final UUID PRODUCT = UUID.randomUUID();
	private static final UUID ARTIFACT = UUID.randomUUID();
	private static final UUID OLD_ROW = UUID.randomUUID();
	private static final UUID CURRENT_ROW = UUID.randomUUID();
	private static final UUID SECOND_OLD_ROW = UUID.randomUUID();
	private static final UUID NEWER_ROW = UUID.randomUUID();
	private static final UUID MERGED = UUID.randomUUID();
	private static final UUID DELIVERABLE = UUID.randomUUID();
	private static final String SHA_256 = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08";
	private static final String SHA_512 = "ee26b0dd4af7e749aa1a8ee3c10ae9923f618980772e473f8819a5d4940e0db2"
			+ "7ac185f8a0e1d5f84f88bc887fd67b143732c304cc5fa9ad8e6f57f50028a8ff";
	/** The rules SCORE-14 stored rows under, before SCORE-15 listed releases without a BOM. */
	private static final int PREVIOUS_MERGE_VERSION = ReleaseService.MERGE_VERSION - 1;

	private ReleaseService svc;
	private RebomService rebomService;
	private ReleaseRebomService releaseRebomService;
	private SharedReleaseService sharedReleaseService;
	private GetComponentService getComponentService;
	private GetDeliverableService getDeliverableService;
	private VariantService variantService;
	private ReleaseData rd;

	@BeforeEach
	void wire() throws Exception {
		svc = new ReleaseService(null);
		rebomService = mock(RebomService.class);
		releaseRebomService = mock(ReleaseRebomService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		getComponentService = mock(GetComponentService.class);
		GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);
		ArtifactService artifactService = mock(ArtifactService.class);
		ReflectionTestUtils.setField(svc, "rebomService", rebomService);
		ReflectionTestUtils.setField(svc, "releaseRebomService", releaseRebomService);
		// The merge path reads through readRows after SCORE-71 ADR-11: it answers whatever each case
		// stubs on getReleaseBoms for the same release, so every stub keeps its place.
		when(releaseRebomService.readRows(any(ReleaseData.class))).thenAnswer(inv -> {
			ReleaseData rd = inv.getArgument(0);
			return new ReleaseRebomService.RowsRead(rd.getUuid(), 0, null, releaseRebomService.getReleaseBoms(rd));
		});
		ReflectionTestUtils.setField(svc, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(svc, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(svc, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(svc, "artifactService", artifactService);
		ReflectionTestUtils.setField(svc, "supportInjectionService", mock(SupportInjectionService.class));
		getDeliverableService = mock(GetDeliverableService.class);
		ReflectionTestUtils.setField(svc, "getDeliverableService", getDeliverableService);
		variantService = mock(VariantService.class);
		ReflectionTestUtils.setField(svc, "variantService", variantService);

		rd = componentRelease(RELEASE);
		when(sharedReleaseService.getReleaseData(RELEASE)).thenReturn(Optional.of(rd));

		ComponentData cd = mock(ComponentData.class);
		when(cd.getType()).thenReturn(ComponentType.COMPONENT);
		when(cd.getName()).thenReturn("component");
		when(getComponentService.getComponentData(COMPONENT)).thenReturn(Optional.of(cd));
		OrganizationData od = mock(OrganizationData.class);
		when(od.getName()).thenReturn("org");
		when(od.getUuid()).thenReturn(ORG);
		when(getOrganizationService.getOrganizationData(ORG)).thenReturn(Optional.of(od));
		ArtifactData ad = mock(ArtifactData.class);
		when(ad.getInternalBom()).thenReturn(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.RELEASE));
		when(artifactService.getArtifactData(ARTIFACT)).thenReturn(Optional.of(ad));

		when(rebomService.mergeAndStoreBoms(anyList(), any(), any())).thenReturn(MERGED);
	}

	private static ReleaseData componentRelease(UUID uuid) {
		ReleaseData release = mock(ReleaseData.class);
		when(release.getUuid()).thenReturn(uuid);
		when(release.getOrg()).thenReturn(ORG);
		when(release.getComponent()).thenReturn(COMPONENT);
		when(release.getVersion()).thenReturn("1.0.0");
		when(release.getArtifacts()).thenReturn(new ArrayList<>(List.of(ARTIFACT)));
		return release;
	}

	/** The key of the facts the releases here have: the organization as supplier, nothing else. */
	private static final String FACTS_KEY = ReleaseNodeFacts.key(new ReleaseNodeFacts("org", null, null, null));

	/**
	 * A row stored with the options of a plain flat request, under rule version {@code mergeVersion}
	 * and the facts the releases here have.
	 */
	private static ReleaseBom row(UUID id, Integer mergeVersion) {
		return row(id, mergeVersion, FACTS_KEY);
	}

	private static ReleaseBom row(UUID id, Integer mergeVersion, String factsKey) {
		return new ReleaseBom(id, new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null, BomStructureType.FLAT)
				.withMergeVersion(mergeVersion).withReleaseNodeFactsKey(factsKey));
	}

	private UUID request(UUID release) throws Exception {
		return svc.getReleaseBomId(release, false, false, false, ArtifactBelongsTo.RELEASE, BomStructureType.FLAT, WU,
				null);
	}

	@Test
	void theMergeVersionIsEighteen() {
		assertEquals(18, ReleaseService.MERGE_VERSION,
				"18 = a release's stored merged rows are dropped when its merge finds nothing to merge, and the stored rows"
				+ " of a release and of every product above it are emptied, in the writer's transaction, when its preferred"
				+ " PURL identifier, its inventory (artifacts, source code entry, deliverables, a rebuild) or, for a product,"
				+ " its parent releases change, or when the supplier name of its product component changes, and rebuilt by"
				+ " the next export or merge (SCORE-71); rows stored under 17 may carry a release under a former identifier"
				+ " or supplier, or an SBOM the release no longer holds, and are rebuilt or dropped once,"
				+ " after 17 = in a product merge a node or a service of any input that carries the root key of a nested product's"
				+ " row, or a key of that product's own listed release- or SCE-attached roots, is re-keyed before the"
				+ " merge instead of folding into that product's node, except the copies a product row carries of the nested"
				+ " product rows it merged (SCORE-69); rows stored under 16 may carry another release's listing folded into a"
				+ " nested product's node, and are rebuilt once,"
				+ " after 16 = under a component release merge every input root that stays a node of the export (the roots of the"
				+ " release's SCE- and deliverable-attached uploads, an inbound deliverable's) is a dependency of the merged"
				+ " root, so structure.no-orphans passes on it (SCORE-64); rows stored under 15 carry those roots as orphans"
				+ " and are rebuilt once,"
				+ " after 15 = merge-boms (rearm-cli 26.10.15) folds two input roots of one key into the first instead of keeping"
				+ " the later one, keeps the survivor's licences whole beside an expression on either side, reads an empty"
				+ " licences list as absent and keys licence entries by id or name with acknowledgement and bom-ref"
				+ " (SCORE-65, delivered as SCORE-67 and SCORE-68); rows stored under 14 may carry a replaced root, or an"
				+ " empty list where a listing was dropped, and are rebuilt once,"
				+ " after 14 = the roots of the release's own SCE- and deliverable-attached uploads carry the declared licence"
				+ " where the upload wrote none (SCORE-63); rows stored under 13 carry those roots unlicensed and are"
				+ " rebuilt once,"
				+ " after 13 = in a product merge a node of a component row or placeholder that carries the product root's key,"
				+ " a key of the product's own listed upload, or the key of another input's root, is re-keyed before the"
				+ " merge instead of folding into the product root or into that node (SCORE-66); rows stored under 12 may"
				+ " carry a same-named component's facts on the product root, or on a nested product's node, and are"
				+ " rebuilt once,"
				+ " after 12 = the merged root carries reliza:export:releaseNode, from which the export marks the organization's"
				+ " own entries (SCORE-58, built as SCORE-60); rows stored under 11 carry no stamp and are rebuilt once,"
				+ " after 11 = an uploaded root that describes the release itself (a RELEASE-attached upload, a product's own"
				+ " included) is folded into the merged root (SCORE-57, ported after TEA as SCORE-62); rows stored under 10"
				+ " carry the upload as a second node and are rebuilt once,"
				+ " after 10 = the missing-SBOM mode is part of the stored row and of the matcher (TEA-15); rows stored under 9"
				+ " carry no mode and are rebuilt once, so a build without the mode never serves a row of this build,"
				+ " after 9 = a product merge always carries the product root and the product's own SBOM artifacts (TEA-1);"
				+ " rows stored under 8 (a single child served as-is, own SBOMs missed) are rebuilt once,"
				+ " after 8 = a merged node records the listed licence expression a fold dropped as reliza:license:dropped (SCORE-44 ADR-6),"
				+ " after 7 = ReARM-made nodes carry the declared licence and the component supplier (SCORE-39 ADR-4),"
				+ " after 6 = ReARM's own nodes carry the organization as supplier and the deliverable digests (SCORE-30"
				+ " ADR-8), 5 = the root purl encodes group and name once (SCORE-29 ADR-3), 4 = product"
				+ " merges list releases without a BOM (SCORE-15 ADR-7), 3 = services carried and dangling refs"
				+ " dropped (SCORE-14 ADR-5) and 2 = bom-refs de-duplicated (SCORE-13 ADR-8)");
	}

	@Test
	void aRowStoredUnderThePreviousRulesIsNotServedAndTheRegeneratedRowCarriesTheCurrentRules() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(OLD_ROW, PREVIOUS_MERGE_VERSION)));

		assertEquals(MERGED, request(RELEASE));

		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, times(1)).mergeAndStoreBoms(anyList(), sent.capture(), eq(ORG));
		assertEquals(Integer.valueOf(ReleaseService.MERGE_VERSION), sent.getValue().mergeVersion());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).storeRowsIfUnchanged(eq(rd), stored.capture(), any(), eq(WU));
		assertEquals(List.of(row(MERGED, ReleaseService.MERGE_VERSION)), stored.getValue(),
				"the new row under the current rules replaces the previous row of the same request");
	}

	@Test
	void aRequestIsServedOnlyByTheRowStoredUnderTheCurrentRules() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(OLD_ROW, null),
				row(CURRENT_ROW, ReleaseService.MERGE_VERSION)));

		assertEquals(CURRENT_ROW, request(RELEASE));

		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
	}

	@Test
	void rowsStoredUnderAnyOtherMergeVersionAreNotServed() throws Exception {
		// A bump of MERGE_VERSION must rebuild every row stored under the previous rules, and a
		// row from a newer backend must not be served by an older one: only equality matches.
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(OLD_ROW, PREVIOUS_MERGE_VERSION),
				row(NEWER_ROW, ReleaseService.MERGE_VERSION + 1)));

		assertEquals(MERGED, request(RELEASE));

		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, times(1)).mergeAndStoreBoms(anyList(), sent.capture(), eq(ORG));
		assertEquals(Integer.valueOf(ReleaseService.MERGE_VERSION), sent.getValue().mergeVersion());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).storeRowsIfUnchanged(eq(rd), stored.capture(), any(), eq(WU));
		assertEquals(List.of(row(MERGED, ReleaseService.MERGE_VERSION)), stored.getValue(),
				"the new row under the current rules replaces both rows of the same request");
	}

	@Test
	void aReleaseWithOnlyAnOldRowIsMergedOnceUnderTheCurrentRulesAndReplacesTheOldRow() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(OLD_ROW, null)));

		assertEquals(MERGED, request(RELEASE));

		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, times(1)).mergeAndStoreBoms(anyList(), sent.capture(), eq(ORG));
		assertEquals(Integer.valueOf(ReleaseService.MERGE_VERSION), sent.getValue().mergeVersion());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).storeRowsIfUnchanged(eq(rd), stored.capture(), any(), eq(WU));
		assertEquals(List.of(row(MERGED, ReleaseService.MERGE_VERSION)), stored.getValue(),
				"one row for the request: the request's options under the current rules and facts, so the next"
				+ " request matches it");
	}

	@Test
	void theRowAMergeStoresServesTheNextRequest() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(OLD_ROW, null)));
		request(RELEASE);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).storeRowsIfUnchanged(eq(rd), stored.capture(), any(), eq(WU));

		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(stored.getValue());
		assertEquals(MERGED, request(RELEASE));

		verify(rebomService, times(1)).mergeAndStoreBoms(anyList(), any(), any());
	}

	@Test
	void aProductWithOldRowsMergesItsComponentsAndItselfUnderTheCurrentRules() throws Exception {
		ReleaseData second = componentRelease(SECOND_RELEASE);
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(OLD_ROW, null)));
		when(releaseRebomService.getReleaseBoms(second)).thenReturn(List.of(row(SECOND_OLD_ROW, null)));
		UUID firstMerge = UUID.randomUUID();
		UUID secondMerge = UUID.randomUUID();
		UUID productMerge = UUID.randomUUID();
		when(rebomService.mergeAndStoreBoms(anyList(), any(), any())).thenReturn(firstMerge, secondMerge, productMerge);

		ReleaseData product = mock(ReleaseData.class);
		when(product.getUuid()).thenReturn(PRODUCT_RELEASE);
		when(product.getOrg()).thenReturn(ORG);
		when(product.getComponent()).thenReturn(PRODUCT);
		when(product.getVersion()).thenReturn("2.0.0");
		when(releaseRebomService.getReleaseBoms(product)).thenReturn(List.of(row(UUID.randomUUID(), null)));
		when(sharedReleaseService.getReleaseData(PRODUCT_RELEASE)).thenReturn(Optional.of(product));
		when(sharedReleaseService.unwindReleaseDependencies(product)).thenReturn(new LinkedHashSet<>(List.of(rd, second)));
		ComponentData pd = mock(ComponentData.class);
		when(pd.getType()).thenReturn(ComponentType.PRODUCT);
		when(pd.getName()).thenReturn("product");
		when(getComponentService.getComponentData(PRODUCT)).thenReturn(Optional.of(pd));

		assertEquals(productMerge, request(PRODUCT_RELEASE));

		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, times(3)).mergeAndStoreBoms(anyList(), sent.capture(), eq(ORG));
		int current = ReleaseService.MERGE_VERSION;
		assertEquals(List.of(current, current, current), sent.getAllValues().stream().map(RebomOptions::mergeVersion).toList(),
				"both component merges and the product merge are built under the current rules");
		verify(rebomService).mergeAndStoreBoms(eq(List.of(firstMerge, secondMerge)), any(), eq(ORG));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).storeRowsIfUnchanged(eq(product), stored.capture(), any(), eq(WU));
		assertEquals(List.of(row(productMerge, ReleaseService.MERGE_VERSION)), stored.getValue());
	}

	@Test
	void aComponentMergeSendsTheFactsOfItsReleaseAndStoresARowWithoutThem() throws Exception {
		DeliverableData dd = new DeliverableData();
		dd.setSoftwareMetadata(SoftwareDeliverableMetadata.builder().digestRecords(new LinkedHashSet<>(List.of(
				new DigestRecord(DigestAlgorithm.SHA_256, SHA_256),
				new DigestRecord(DigestAlgorithm.SHA_512, SHA_512)))).build());
		when(getDeliverableService.getDeliverableData(DELIVERABLE)).thenReturn(Optional.of(dd));
		ships(RELEASE, DELIVERABLE);
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(OLD_ROW, PREVIOUS_MERGE_VERSION)));

		request(RELEASE);

		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService).mergeAndStoreBoms(anyList(), sent.capture(), eq(ORG));
		assertEquals(new ReleaseNodeFacts("org", List.of(new BomHash("SHA-256", SHA_256), new BomHash("SHA-512", SHA_512)), null, null),
				sent.getValue().releaseNode());
		assertEquals(Integer.valueOf(ReleaseService.MERGE_VERSION), sent.getValue().mergeVersion());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).storeRowsIfUnchanged(eq(rd), stored.capture(), any(), eq(WU));
		assertEquals(1, stored.getValue().size());
		ReleaseBom newRow = stored.getValue().get(0);
		assertEquals(row(MERGED, ReleaseService.MERGE_VERSION, ReleaseNodeFacts.key(sent.getValue().releaseNode())), newRow,
				"the row carries the key of the facts sent, not the facts");
		assertNull(newRow.rebomMergeOptions().releaseNode(), "the facts are not part of the cache key");
	}

	// SCORE-39 T-13: the key of the facts decides, under the current rules
	@Test
	void aRowUnderTheCurrentRulesWithAnotherFactsKeyIsNotServedAndIsReplaced() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(OLD_ROW, ReleaseService.MERGE_VERSION,
				ReleaseNodeFacts.key(new ReleaseNodeFacts("org", null, "MIT", null)))));

		assertEquals(MERGED, request(RELEASE));

		verify(rebomService, times(1)).mergeAndStoreBoms(anyList(), any(), eq(ORG));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).storeRowsIfUnchanged(eq(rd), stored.capture(), any(), eq(WU));
		assertEquals(List.of(row(MERGED, ReleaseService.MERGE_VERSION)), stored.getValue());
	}

	@Test
	void aRowUnderTheCurrentRulesWithoutAFactsKeyIsNotServed() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(OLD_ROW, ReleaseService.MERGE_VERSION, null)));

		assertEquals(MERGED, request(RELEASE));

		verify(rebomService, times(1)).mergeAndStoreBoms(anyList(), any(), eq(ORG));
	}

	@Test
	void theRowOfTheCurrentFactsIsServedWithoutAMergeAndTheFactsAreReadOnce() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(
				row(OLD_ROW, ReleaseService.MERGE_VERSION, ReleaseNodeFacts.key(new ReleaseNodeFacts("org", null, "MIT", null))),
				row(CURRENT_ROW, ReleaseService.MERGE_VERSION)));

		assertEquals(CURRENT_ROW, request(RELEASE));

		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
		verify(variantService, times(1)).findBaseVariantForRelease(RELEASE);
	}

	@Test
	void aRegeneratedComponentMergeReadsTheFactsOnce() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(OLD_ROW, null)));

		request(RELEASE);

		verify(rebomService, times(1)).mergeAndStoreBoms(anyList(), any(), eq(ORG));
		verify(variantService, times(1)).findBaseVariantForRelease(RELEASE);
	}

	@Test
	void aRowBuiltBeforeTheComponentDeclaredALicenceIsNotServedAndTheMergeSendsIt() throws Exception {
		ComponentData cd = getComponentService.getComponentData(COMPONENT).orElseThrow();
		when(cd.getLicenseExpression()).thenReturn("LicenseRef-Acme-Commercial");
		when(cd.getLicenseUrl()).thenReturn("https://acme.example/licence");
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(CURRENT_ROW, ReleaseService.MERGE_VERSION)));

		assertEquals(MERGED, request(RELEASE));

		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService).mergeAndStoreBoms(anyList(), sent.capture(), eq(ORG));
		ReleaseNodeFacts facts = new ReleaseNodeFacts("org", null, "LicenseRef-Acme-Commercial", "https://acme.example/licence");
		assertEquals(facts, sent.getValue().releaseNode());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).storeRowsIfUnchanged(eq(rd), stored.capture(), any(), eq(WU));
		assertEquals(List.of(row(MERGED, ReleaseService.MERGE_VERSION, ReleaseNodeFacts.key(facts))), stored.getValue());
	}

	@Test
	void aNewRowKeepsTheRowsOfOtherRequests() throws Exception {
		RebomOptions flat = row(OLD_ROW, null).rebomMergeOptions();
		List<ReleaseBom> others = List.of(
				new ReleaseBom(UUID.randomUUID(), new RebomOptions(ArtifactBelongsTo.DELIVERABLE, false, false, null,
						BomStructureType.FLAT).withMergeVersion(ReleaseService.MERGE_VERSION).withReleaseNodeFactsKey(FACTS_KEY)),
				new ReleaseBom(UUID.randomUUID(), new RebomOptions(ArtifactBelongsTo.RELEASE, true, false, null,
						BomStructureType.FLAT).withMergeVersion(ReleaseService.MERGE_VERSION).withReleaseNodeFactsKey(FACTS_KEY)),
				new ReleaseBom(UUID.randomUUID(), new RebomOptions(ArtifactBelongsTo.RELEASE, false, true, null,
						BomStructureType.FLAT).withMergeVersion(ReleaseService.MERGE_VERSION).withReleaseNodeFactsKey(FACTS_KEY)),
				new ReleaseBom(UUID.randomUUID(), new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null,
						BomStructureType.HIERARCHICAL).withMergeVersion(ReleaseService.MERGE_VERSION).withReleaseNodeFactsKey(FACTS_KEY)),
				new ReleaseBom(UUID.randomUUID(), new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, true,
						BomStructureType.FLAT).withMergeVersion(ReleaseService.MERGE_VERSION).withReleaseNodeFactsKey(FACTS_KEY)));
		List<ReleaseBom> before = new ArrayList<>(others);
		before.add(new ReleaseBom(OLD_ROW, flat));
		// an unticked file switch stored as false is the same request as one stored before the option (null)
		before.add(new ReleaseBom(SECOND_OLD_ROW, new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, false,
				BomStructureType.FLAT).withMergeVersion(PREVIOUS_MERGE_VERSION)));
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(before);

		assertEquals(MERGED, request(RELEASE));

		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).storeRowsIfUnchanged(eq(rd), stored.capture(), any(), eq(WU));
		List<ReleaseBom> after = new ArrayList<>(others);
		after.add(row(MERGED, ReleaseService.MERGE_VERSION));
		assertEquals(after, stored.getValue());
	}

	@Test
	void theProductMergeAndTheComponentMergesItTriggersEachSendTheirOwnReleasesFacts() throws Exception {
		ReleaseData second = componentRelease(SECOND_RELEASE);
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(OLD_ROW, null)));
		when(releaseRebomService.getReleaseBoms(second)).thenReturn(List.of(row(SECOND_OLD_ROW, null)));
		DeliverableData dd = new DeliverableData();
		dd.setSoftwareMetadata(SoftwareDeliverableMetadata.builder().digestRecords(new LinkedHashSet<>(List.of(
				new DigestRecord(DigestAlgorithm.SHA_256, SHA_256)))).build());
		when(getDeliverableService.getDeliverableData(DELIVERABLE)).thenReturn(Optional.of(dd));
		ships(SECOND_RELEASE, DELIVERABLE);
		ReleaseData product = productRelease();
		when(sharedReleaseService.unwindReleaseDependencies(product)).thenReturn(new LinkedHashSet<>(List.of(rd, second)));

		request(PRODUCT_RELEASE);

		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, times(3)).mergeAndStoreBoms(anyList(), sent.capture(), eq(ORG));
		assertEquals(List.of(new ReleaseNodeFacts("org", null, null, null),
				new ReleaseNodeFacts("org", List.of(new BomHash("SHA-256", SHA_256)), null, null),
				new ReleaseNodeFacts("org", null, null, null)),
				sent.getAllValues().stream().map(RebomOptions::releaseNode).toList(),
				"each component merge carries its release's facts, the product merge the product release's");
		assertEquals(List.of(RootComponentMergeMode.FLATTEN_UNDER_NEW_ROOT, RootComponentMergeMode.FLATTEN_UNDER_NEW_ROOT,
				RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT),
				sent.getAllValues().stream().map(RebomOptions::rootComponentMergeMode).toList());
	}

	/** {@code release} ships {@code deliverable}: the outbound deliverable of its base variant. */
	private void ships(UUID release, UUID deliverable) {
		VariantData base = new VariantData();
		base.setOutboundDeliverables(new LinkedHashSet<>(List.of(deliverable)));
		when(variantService.findBaseVariantForRelease(release)).thenReturn(Optional.of(base));
	}

	/** A product release over this test's component releases, with an old row and no deliverables. */
	private ReleaseData productRelease() {
		ReleaseData product = mock(ReleaseData.class);
		when(product.getUuid()).thenReturn(PRODUCT_RELEASE);
		when(product.getOrg()).thenReturn(ORG);
		when(product.getComponent()).thenReturn(PRODUCT);
		when(product.getVersion()).thenReturn("2.0.0");
		when(releaseRebomService.getReleaseBoms(product)).thenReturn(List.of(row(UUID.randomUUID(), null)));
		when(sharedReleaseService.getReleaseData(PRODUCT_RELEASE)).thenReturn(Optional.of(product));
		ComponentData pd = mock(ComponentData.class);
		when(pd.getType()).thenReturn(ComponentType.PRODUCT);
		when(pd.getName()).thenReturn("product");
		when(getComponentService.getComponentData(PRODUCT)).thenReturn(Optional.of(pd));
		return product;
	}
}
