/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.Rebom.RebomOptions;
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
	/** The rules SCORE-14 stored rows under, before SCORE-15 listed releases without a BOM. */
	private static final int PREVIOUS_MERGE_VERSION = ReleaseService.MERGE_VERSION - 1;

	private ReleaseService svc;
	private RebomService rebomService;
	private ReleaseRebomService releaseRebomService;
	private SharedReleaseService sharedReleaseService;
	private GetComponentService getComponentService;
	private ReleaseData rd;

	@BeforeEach
	void wire() {
		svc = new ReleaseService(null);
		rebomService = mock(RebomService.class);
		releaseRebomService = mock(ReleaseRebomService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		getComponentService = mock(GetComponentService.class);
		GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);
		ArtifactService artifactService = mock(ArtifactService.class);
		ReflectionTestUtils.setField(svc, "rebomService", rebomService);
		ReflectionTestUtils.setField(svc, "releaseRebomService", releaseRebomService);
		ReflectionTestUtils.setField(svc, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(svc, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(svc, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(svc, "artifactService", artifactService);
		ReflectionTestUtils.setField(svc, "supportInjectionService", mock(SupportInjectionService.class));

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

	/** A row stored with the options of a plain flat request, under rule version {@code mergeVersion}. */
	private static ReleaseBom row(UUID id, Integer mergeVersion) {
		return new ReleaseBom(id, new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null, BomStructureType.FLAT)
				.withMergeVersion(mergeVersion));
	}

	private UUID request(UUID release) throws Exception {
		return svc.getReleaseBomId(release, false, false, false, ArtifactBelongsTo.RELEASE, BomStructureType.FLAT, WU,
				null);
	}

	@Test
	void theMergeVersionIsFour() {
		assertEquals(4, ReleaseService.MERGE_VERSION,
				"4 = product merges list releases without a BOM (SCORE-15 ADR-7), after 3 = services carried and"
				+ " dangling refs dropped (SCORE-14 ADR-5) and 2 = bom-refs de-duplicated (SCORE-13 ADR-8)");
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
		verify(releaseRebomService).setReboms(eq(rd), stored.capture(), eq(WU));
		assertEquals(List.of(row(OLD_ROW, PREVIOUS_MERGE_VERSION), row(MERGED, ReleaseService.MERGE_VERSION)),
				stored.getValue(), "the previous row stays as stored, the new row is added under the current rules");
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
		verify(releaseRebomService).setReboms(eq(rd), stored.capture(), eq(WU));
		assertEquals(List.of(row(OLD_ROW, PREVIOUS_MERGE_VERSION), row(NEWER_ROW, ReleaseService.MERGE_VERSION + 1),
				row(MERGED, ReleaseService.MERGE_VERSION)), stored.getValue(),
				"both rows stay as stored, the new row is added under the current rules");
	}

	@Test
	void aReleaseWithOnlyAnOldRowIsMergedOnceUnderTheCurrentRulesAndKeepsTheOldRow() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(OLD_ROW, null)));

		assertEquals(MERGED, request(RELEASE));

		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, times(1)).mergeAndStoreBoms(anyList(), sent.capture(), eq(ORG));
		assertEquals(Integer.valueOf(ReleaseService.MERGE_VERSION), sent.getValue().mergeVersion());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).setReboms(eq(rd), stored.capture(), eq(WU));
		assertEquals(List.of(OLD_ROW, MERGED), stored.getValue().stream().map(ReleaseBom::rebomId).toList(),
				"the old row stays, the new one is added after it");
		assertEquals(row(OLD_ROW, null), stored.getValue().get(0));
		assertEquals(row(MERGED, ReleaseService.MERGE_VERSION), stored.getValue().get(1),
				"the new row is the request's options under the current rules, so the next request matches it");
	}

	@Test
	void theRowAMergeStoresServesTheNextRequest() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(OLD_ROW, null)));
		request(RELEASE);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).setReboms(eq(rd), stored.capture(), eq(WU));

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
		verify(releaseRebomService).setReboms(eq(product), stored.capture(), eq(WU));
		assertEquals(row(productMerge, ReleaseService.MERGE_VERSION), stored.getValue().get(stored.getValue().size() - 1));
	}
}
