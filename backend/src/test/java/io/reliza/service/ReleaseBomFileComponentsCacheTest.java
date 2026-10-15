/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.model.ArtifactData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.RebomService.BomStructureType;

/**
 * SCORE-11 (T-5): excludeFileComponents is part of the merged-BOM cache key. Rows stored before the
 * option existed (null) and rows stored with false serve a request with the switch off; a request
 * with it on matches only a row stored on, and otherwise merges once with the option set and stores
 * its own row. Off is sent to rebom, and stored, as null, so those requests stay what they were.
 *
 * <p>And every reader of the merged BOM picks its row by the same key: a product release's merge
 * of its components' merges, the CSV and EXCEL renderings (design ADR-1 (a): the three media types
 * of one export agree), and the VDR read, which design section 1 leaves on the unfiltered merge.
 */
class ReleaseBomFileComponentsCacheTest {

	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();
	private static final UUID RELEASE = UUID.randomUUID();
	private static final UUID ORG = UUID.randomUUID();
	private static final UUID COMPONENT = UUID.randomUUID();
	private static final UUID ARTIFACT = UUID.randomUUID();
	private static final UUID NULL_ROW = UUID.randomUUID();
	private static final UUID FALSE_ROW = UUID.randomUUID();
	private static final UUID TRUE_ROW = UUID.randomUUID();
	private static final UUID MERGED = UUID.randomUUID();
	private static final UUID PRODUCT_RELEASE = UUID.randomUUID();
	private static final UUID PRODUCT = UUID.randomUUID();
	private static final UUID SECOND_RELEASE = UUID.randomUUID();
	private static final UUID SECOND_NULL_ROW = UUID.randomUUID();
	private static final UUID SECOND_TRUE_ROW = UUID.randomUUID();

	private ReleaseService svc;
	private RebomService rebomService;
	private ReleaseRebomService releaseRebomService;
	private ReleaseData rd;
	private SharedReleaseService sharedReleaseService;
	private GetComponentService getComponentService;
	private SupportInjectionService supportInjectionService;

	@BeforeEach
	void wire() {
		svc = new ReleaseService(null);
		rebomService = mock(RebomService.class);
		releaseRebomService = mock(ReleaseRebomService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		getComponentService = mock(GetComponentService.class);
		supportInjectionService = mock(SupportInjectionService.class);
		GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);
		ArtifactService artifactService = mock(ArtifactService.class);
		ReflectionTestUtils.setField(svc, "rebomService", rebomService);
		ReflectionTestUtils.setField(svc, "releaseRebomService", releaseRebomService);
		ReflectionTestUtils.setField(svc, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(svc, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(svc, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(svc, "artifactService", artifactService);
		ReflectionTestUtils.setField(svc, "supportInjectionService", supportInjectionService);

		rd = mock(ReleaseData.class);
		when(rd.getUuid()).thenReturn(RELEASE);
		when(rd.getOrg()).thenReturn(ORG);
		when(rd.getComponent()).thenReturn(COMPONENT);
		when(rd.getVersion()).thenReturn("1.0.0");
		when(rd.getArtifacts()).thenReturn(new ArrayList<>(List.of(ARTIFACT)));
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

	private static ReleaseBom row(UUID id, Boolean excludeFileComponents) {
		// Stored under the current merge rules, so only the file switch decides (SCORE-13 adds
		// the rule version to the key; ReleaseBomMergeVersionCacheTest covers it).
		return new ReleaseBom(id, new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, excludeFileComponents,
				BomStructureType.FLAT).withMergeVersion(ReleaseService.MERGE_VERSION));
	}

	private UUID request(boolean excludeFileComponents) throws Exception {
		return svc.getReleaseBomId(RELEASE, false, false, excludeFileComponents, ArtifactBelongsTo.RELEASE,
				BomStructureType.FLAT, WU, null);
	}

	@Test
	void anOffRequestIsServedByARowStoredBeforeTheOptionExisted() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(TRUE_ROW, true), row(NULL_ROW, null)));
		assertEquals(NULL_ROW, request(false));
		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
	}

	@Test
	void anOffRequestIsServedByARowStoredWithFalse() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(TRUE_ROW, true), row(FALSE_ROW, false)));
		assertEquals(FALSE_ROW, request(false));
		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
	}

	@Test
	void anOnRequestIsServedOnlyByARowStoredOn() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd))
				.thenReturn(List.of(row(NULL_ROW, null), row(FALSE_ROW, false), row(TRUE_ROW, true)));
		assertEquals(TRUE_ROW, request(true));
		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
	}

	@Test
	void anOnRequestWithoutItsRowMergesOnceWithTheOptionAndStoresItsRow() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(NULL_ROW, null), row(FALSE_ROW, false)));

		assertEquals(MERGED, request(true));

		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, times(1)).mergeAndStoreBoms(anyList(), sent.capture(), eq(ORG));
		assertEquals(Boolean.TRUE, sent.getValue().excludeFileComponents());
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).setReboms(eq(rd), stored.capture(), eq(WU));
		assertEquals(3, stored.getValue().size(), "the rows already there stay");
		ReleaseBom added = stored.getValue().get(2);
		assertEquals(MERGED, added.rebomId());
		assertEquals(Boolean.TRUE, added.rebomMergeOptions().excludeFileComponents());
	}

	@Test
	void anOffRequestWithoutARowMergesAndStoresExactlyWhatItDidBefore() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(TRUE_ROW, true)));

		assertEquals(MERGED, request(false));

		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService).mergeAndStoreBoms(anyList(), sent.capture(), eq(ORG));
		assertNull(sent.getValue().excludeFileComponents());
		assertFalse(Utils.OM.writeValueAsString(sent.getValue()).contains("excludeFileComponents"),
				"off is not sent to rebom at all, so a rebom without the option still merges it");
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).setReboms(eq(rd), stored.capture(), eq(WU));
		ReleaseBom added = stored.getValue().get(stored.getValue().size() - 1);
		assertNull(added.rebomMergeOptions().excludeFileComponents());
		assertFalse(Utils.OM.writeValueAsString(added).contains("excludeFileComponents"),
				"the stored row is the shape rows had before the option");
	}

	@Test
	void anOnRequestIsSentToRebomWithTheOption() throws Exception {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of());
		request(true);
		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService).mergeAndStoreBoms(anyList(), sent.capture(), eq(ORG));
		assertTrue(Utils.OM.writeValueAsString(sent.getValue()).contains("\"excludeFileComponents\":true"));
	}

	// ---- every reader of the merged BOM picks its row by the same key -------------------------

	/** A product release of two component releases, each already holding an off row and an on row. */
	private ReleaseData productOfTwoComponents() {
		ReleaseData second = mock(ReleaseData.class);
		when(second.getUuid()).thenReturn(SECOND_RELEASE);
		when(second.getOrg()).thenReturn(ORG);
		when(second.getComponent()).thenReturn(COMPONENT);
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(TRUE_ROW, true), row(NULL_ROW, null)));
		when(releaseRebomService.getReleaseBoms(second))
				.thenReturn(List.of(row(SECOND_NULL_ROW, null), row(SECOND_TRUE_ROW, true)));

		ReleaseData product = mock(ReleaseData.class);
		when(product.getUuid()).thenReturn(PRODUCT_RELEASE);
		when(product.getOrg()).thenReturn(ORG);
		when(product.getComponent()).thenReturn(PRODUCT);
		when(product.getVersion()).thenReturn("2.0.0");
		when(releaseRebomService.getReleaseBoms(product)).thenReturn(List.of());
		when(sharedReleaseService.getReleaseData(PRODUCT_RELEASE)).thenReturn(Optional.of(product));
		when(sharedReleaseService.unwindReleaseDependencies(product))
				.thenReturn(new LinkedHashSet<>(List.of(rd, second)));
		ComponentData pd = mock(ComponentData.class);
		when(pd.getType()).thenReturn(ComponentType.PRODUCT);
		when(pd.getName()).thenReturn("product");
		when(getComponentService.getComponentData(PRODUCT)).thenReturn(Optional.of(pd));
		return product;
	}

	private RebomOptions productMerge(boolean excludeFileComponents, List<UUID> expectedInputs) throws Exception {
		ReleaseData product = productOfTwoComponents();
		assertEquals(MERGED, svc.getReleaseBomId(PRODUCT_RELEASE, false, false, excludeFileComponents,
				ArtifactBelongsTo.RELEASE, BomStructureType.FLAT, WU, null));
		ArgumentCaptor<RebomOptions> sent = ArgumentCaptor.forClass(RebomOptions.class);
		verify(rebomService, times(1)).mergeAndStoreBoms(eq(expectedInputs), sent.capture(), eq(ORG));
		@SuppressWarnings("unchecked")
		ArgumentCaptor<List<ReleaseBom>> stored = ArgumentCaptor.forClass(List.class);
		verify(releaseRebomService).setReboms(eq(product), stored.capture(), eq(WU));
		assertEquals(sent.getValue().excludeFileComponents(),
				stored.getValue().get(stored.getValue().size() - 1).rebomMergeOptions().excludeFileComponents(),
				"the product's stored row says what its merge was asked");
		return sent.getValue();
	}

	@Test
	void aProductWithTheSwitchMergesItsComponentsFilteredMergesAndAsksForTheFilter() throws Exception {
		RebomOptions sent = productMerge(true, List.of(TRUE_ROW, SECOND_TRUE_ROW));
		assertEquals(Boolean.TRUE, sent.excludeFileComponents(),
				"the product merge filters too, so its own count adds to its components' (D-1)");
	}

	@Test
	void aProductWithoutTheSwitchMergesItsComponentsUnfilteredMergesAsBefore() throws Exception {
		RebomOptions sent = productMerge(false, List.of(NULL_ROW, SECOND_NULL_ROW));
		assertNull(sent.excludeFileComponents());
		assertFalse(Utils.OM.writeValueAsString(sent).contains("excludeFileComponents"));
	}

	private void bothRowsStored() {
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(row(TRUE_ROW, true), row(NULL_ROW, null)));
	}

	private String exportAs(BomMediaType mediaType, boolean excludeFileComponents) throws Exception {
		return svc.exportReleaseSbom(RELEASE, false, false, excludeFileComponents, ArtifactBelongsTo.RELEASE,
				BomStructureType.FLAT, mediaType, ORG, WU, null, ExportMetadataOptions.callerSilent());
	}

	@Test
	void csvRendersTheRowOfTheSwitch() throws Exception {
		bothRowsStored();
		when(rebomService.findBomByIdCsv(TRUE_ROW, ORG)).thenReturn("filtered");
		when(rebomService.findBomByIdCsv(NULL_ROW, ORG)).thenReturn("full");

		assertEquals("filtered", exportAs(BomMediaType.CSV, true));
		assertEquals("full", exportAs(BomMediaType.CSV, false));
		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
	}

	@Test
	void excelRendersTheRowOfTheSwitch() throws Exception {
		bothRowsStored();
		when(rebomService.findBomByIdExcel(TRUE_ROW, ORG)).thenReturn("filtered");
		when(rebomService.findBomByIdExcel(NULL_ROW, ORG)).thenReturn("full");

		assertEquals("filtered", exportAs(BomMediaType.EXCEL, true));
		assertEquals("full", exportAs(BomMediaType.EXCEL, false));
		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
	}

	@Test
	void theVdrReadsTheUnfilteredMergeWhateverIsStored() throws Exception {
		// The VDR read asks for every artifact (belongsTo null), flat, the whole graph.
		when(releaseRebomService.getReleaseBoms(rd)).thenReturn(List.of(
				new ReleaseBom(TRUE_ROW, new RebomOptions(null, false, false, true, BomStructureType.FLAT)
						.withMergeVersion(ReleaseService.MERGE_VERSION)),
				new ReleaseBom(NULL_ROW, new RebomOptions(null, false, false, null, BomStructureType.FLAT)
						.withMergeVersion(ReleaseService.MERGE_VERSION))));
		when(rebomService.findBomByIdJson(any(), eq(ORG))).thenAnswer(inv -> Utils.OM.readTree("{}"));

		svc.mergedBomForVdr(RELEASE, ORG, WU);

		verify(rebomService).findBomByIdJson(NULL_ROW, ORG);
		verify(rebomService, never()).findBomByIdJson(eq(TRUE_ROW), any());
		verify(rebomService, never()).mergeAndStoreBoms(anyList(), any(), any());
	}
}
