/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.common.Utils.RootComponentMergeMode;
import io.reliza.model.ArtifactData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentKind;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.WhoUpdated;
import io.reliza.model.tea.Rebom.InternalBom;
import io.reliza.model.tea.Rebom.RebomOptions;
import io.reliza.service.RebomService.BomStructureType;

/**
 * Shared wiring for the {@link ReleaseService} product-merge and merged-SBOM cache tests (SCORE-15):
 * a ReleaseService on mocks, with the stored {@link ReleaseBom} rows kept per release in memory
 * so that what one call stores is what the next one reads, and every rebom merge recorded.
 */
abstract class ReleaseServiceMergeTestBase {

	protected static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();
	protected static final UUID ORG = UUID.randomUUID();
	protected static final UUID COMPONENT = UUID.randomUUID();
	protected static final UUID PRODUCT = UUID.randomUUID();
	protected static final UUID PRODUCT_RELEASE = UUID.randomUUID();
	/** A second PRODUCT component, for a product bundled inside {@link #product} (SCORE-15 ADR-11). */
	protected static final UUID INNER_PRODUCT = UUID.randomUUID();

	/** One rebom merge request: the inputs, the options and the id rebom answered. */
	protected record Merge(List<UUID> bomIds, RebomOptions options, UUID merged) {
		boolean isProductMerge() {
			return RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT == options.rootComponentMergeMode();
		}
	}

	protected ReleaseService svc;
	protected RebomService rebomService;
	protected ReleaseRebomService releaseRebomService;
	protected SharedReleaseService sharedReleaseService;
	protected ArtifactService artifactService;
	protected GetComponentService getComponentService;
	protected SbomComponentService sbomComponentService;
	protected ReleaseData product;
	/** The stored rows, by release. */
	protected final Map<UUID, List<ReleaseBom>> rows = new HashMap<>();
	/** Every merge rebom was asked for, in order. */
	protected final List<Merge> merges = new ArrayList<>();
	/** How many times the rows of a release were written. */
	protected final Map<UUID, Integer> rowWrites = new HashMap<>();

	@BeforeEach
	void wireReleaseService() {
		svc = new ReleaseService(null);
		rebomService = mock(RebomService.class);
		releaseRebomService = mock(ReleaseRebomService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		artifactService = mock(ArtifactService.class);
		getComponentService = mock(GetComponentService.class);
		GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);
		ReflectionTestUtils.setField(svc, "rebomService", rebomService);
		ReflectionTestUtils.setField(svc, "releaseRebomService", releaseRebomService);
		ReflectionTestUtils.setField(svc, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(svc, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(svc, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(svc, "artifactService", artifactService);
		sbomComponentService = mock(SbomComponentService.class);
		ReflectionTestUtils.setField(svc, "sbomComponentService", sbomComponentService);

		ComponentData cd = mock(ComponentData.class);
		when(cd.getType()).thenReturn(ComponentType.COMPONENT);
		when(cd.getKind()).thenReturn(ComponentKind.GENERIC);
		when(cd.getName()).thenReturn("component");
		when(getComponentService.getComponentData(COMPONENT)).thenReturn(Optional.of(cd));
		ComponentData pd = mock(ComponentData.class);
		when(pd.getType()).thenReturn(ComponentType.PRODUCT);
		when(pd.getName()).thenReturn("product");
		when(getComponentService.getComponentData(PRODUCT)).thenReturn(Optional.of(pd));
		ComponentData ipd = mock(ComponentData.class);
		when(ipd.getType()).thenReturn(ComponentType.PRODUCT);
		when(ipd.getName()).thenReturn("inner product");
		when(getComponentService.getComponentData(INNER_PRODUCT)).thenReturn(Optional.of(ipd));
		OrganizationData od = mock(OrganizationData.class);
		when(od.getName()).thenReturn("Example Org");
		when(od.getUuid()).thenReturn(ORG);
		when(getOrganizationService.getOrganizationData(ORG)).thenReturn(Optional.of(od));

		product = mock(ReleaseData.class);
		when(product.getUuid()).thenReturn(PRODUCT_RELEASE);
		when(product.getOrg()).thenReturn(ORG);
		when(product.getComponent()).thenReturn(PRODUCT);
		when(product.getVersion()).thenReturn("26.10.35");
		when(product.getIdentifiers()).thenReturn(List.of());
		when(sharedReleaseService.getReleaseData(PRODUCT_RELEASE)).thenReturn(Optional.of(product));

		rows.clear();
		merges.clear();
		rowWrites.clear();
		when(releaseRebomService.getReleaseBoms(any())).thenAnswer(inv -> {
			ReleaseData rd = inv.getArgument(0);
			return new ArrayList<>(rows.getOrDefault(rd.getUuid(), List.of()));
		});
		doAnswer(inv -> {
			ReleaseData rd = inv.getArgument(0);
			List<ReleaseBom> stored = inv.getArgument(1);
			rows.put(rd.getUuid(), new ArrayList<>(stored));
			rowWrites.merge(rd.getUuid(), 1, Integer::sum);
			return null;
		}).when(releaseRebomService).setReboms(any(ReleaseData.class), anyList(), any());
		when(rebomService.mergeAndStoreBoms(anyList(), any(), any())).thenAnswer(inv -> {
			UUID merged = UUID.randomUUID();
			merges.add(new Merge(List.copyOf(inv.<List<UUID>>getArgument(0)), inv.getArgument(1), merged));
			return merged;
		});
	}

	/** The row options a plain request of {@code structure} is stored under. */
	protected static RebomOptions rowOptions(BomStructureType structure) {
		return new RebomOptions(ArtifactBelongsTo.RELEASE, false, false, null, structure)
				.withMergeVersion(ReleaseService.MERGE_VERSION);
	}

	protected ReleaseData componentRelease(UUID uuid, String version) {
		ReleaseData release = mock(ReleaseData.class);
		when(release.getUuid()).thenReturn(uuid);
		when(release.getOrg()).thenReturn(ORG);
		when(release.getComponent()).thenReturn(COMPONENT);
		when(release.getVersion()).thenReturn(version);
		when(release.getArtifacts()).thenReturn(new ArrayList<>());
		when(release.getIdentifiers()).thenReturn(List.of());
		when(sharedReleaseService.getReleaseData(uuid)).thenReturn(Optional.of(release));
		return release;
	}

	/** A component release with one stored BOM artifact, tagged with {@code coverage} when not null. */
	protected ReleaseData withBom(UUID uuid, String version, ArtifactCoverageType coverage) {
		ReleaseData release = componentRelease(uuid, version);
		UUID artifact = UUID.randomUUID();
		ArtifactData ad = mock(ArtifactData.class);
		when(ad.getInternalBom()).thenReturn(new InternalBom(UUID.randomUUID(), ArtifactBelongsTo.RELEASE));
		when(ad.getTags()).thenReturn(null == coverage ? List.of()
				: List.of(new TagRecord(CommonVariables.ARTIFACT_COVERAGE_TYPE_TAG_KEY, coverage.name())));
		when(artifactService.getArtifactData(artifact)).thenReturn(Optional.of(ad));
		release.getArtifacts().add(artifact);
		return release;
	}

	protected void unwindsTo(ReleaseData... releases) {
		unwinds(product, releases);
	}

	protected void unwinds(ReleaseData productRelease, ReleaseData... releases) {
		when(sharedReleaseService.unwindReleaseDependencies(productRelease)).thenReturn(new LinkedHashSet<>(List.of(releases)));
	}

	/** A release of the {@link #INNER_PRODUCT} component, to be bundled in {@link #product}. */
	protected ReleaseData innerProductRelease(UUID uuid, String version) {
		ReleaseData release = mock(ReleaseData.class);
		when(release.getUuid()).thenReturn(uuid);
		when(release.getOrg()).thenReturn(ORG);
		when(release.getComponent()).thenReturn(INNER_PRODUCT);
		when(release.getVersion()).thenReturn(version);
		when(release.getIdentifiers()).thenReturn(List.of());
		when(sharedReleaseService.getReleaseData(uuid)).thenReturn(Optional.of(release));
		return release;
	}

	/** What {@code locateAllProductsOfRelease} finds above {@code release}, in that order. */
	protected void productsAbove(ReleaseData release, ReleaseData... products) {
		when(sharedReleaseService.locateAllProductsOfRelease(eq(release), anySet()))
				.thenReturn(new LinkedHashSet<>(List.of(products)));
	}

	protected void storeRow(UUID release, UUID rebomId, RebomOptions options) {
		rows.computeIfAbsent(release, k -> new ArrayList<>()).add(new ReleaseBom(rebomId, options));
	}

	protected List<Merge> productMerges() {
		return merges.stream().filter(Merge::isProductMerge).toList();
	}

	protected List<Merge> componentMerges() {
		return merges.stream().filter(m -> !m.isProductMerge()).toList();
	}
}
