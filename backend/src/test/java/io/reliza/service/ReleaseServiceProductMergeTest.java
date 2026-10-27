/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
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
import io.reliza.common.Utils.RootComponentMergeMode;
import io.reliza.exceptions.MergedSbomUnavailableException;
import io.reliza.exceptions.MergedSbomUnavailableException.ChildBomOutcome;
import io.reliza.exceptions.MergedSbomUnavailableException.ChildBomStatus;
import io.reliza.exceptions.MergedSbomUnavailableException.Reason;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData;
import io.reliza.model.OrganizationData;
import io.reliza.model.RearmIdentifier;
import io.reliza.model.RearmIdentifierType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.VariantData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.Rebom.MissingSbomComponent;
import io.reliza.model.Rebom.MissingSbomReason;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.Rebom.ReleaseNodeFacts;
import io.reliza.service.RebomService.BomStructureType;

/**
 * TEA-1: the merged SBOM of a product release (ticket t20260909-105515-31103).
 *
 * <p>The sandbox product release 9000.1.0 carries its own device-sbom artifact and no component
 * releases, and its merged export answered "No SBOMs found!": the product branch merged its
 * children only and never read the product's own artifacts. It also swallowed every child's
 * failure, so a product whose children failed served a partial document or the same opaque
 * answer. These drive getReleaseBomId with the collaborators mocked and rebom recorded.
 */
class ReleaseServiceProductMergeTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();
	private static final RebomOptions FLAT_ANY = new RebomOptions(null, false, false, BomStructureType.FLAT);

	private record MergeCall(List<UUID> ids, RebomOptions options, UUID mergedId) {}
	private record SetRebomsCall(UUID release, List<ReleaseBom> reboms) {}

	private ReleaseService svc;
	private SharedReleaseService shared;
	private ReleaseRebomService releaseRebomService;
	private GetComponentService getComponentService;
	private ArtifactService artifactService;
	private GetSourceCodeEntryService getSourceCodeEntryService;
	private GetDeliverableService getDeliverableService;
	private VariantService variantService;

	private final List<MergeCall> merges = new ArrayList<>();
	private final List<SetRebomsCall> setReboms = new ArrayList<>();
	private final Map<UUID, List<ReleaseBom>> caches = new HashMap<>();
	private final Map<UUID, ComponentData> components = new HashMap<>();
	private final Map<UUID, ArtifactData> artifacts = new HashMap<>();
	private final Map<UUID, ReleaseData> releases = new HashMap<>();
	/** A merge whose inputs contain one of these bom ids is refused the way rebom refuses it. */
	private final Set<UUID> missingInRebom = new LinkedHashSet<>();

	@BeforeEach
	void setUp() {
		svc = new ReleaseService(null);
		shared = mock(SharedReleaseService.class);
		releaseRebomService = mock(ReleaseRebomService.class);
		getComponentService = mock(GetComponentService.class);
		artifactService = mock(ArtifactService.class);
		getSourceCodeEntryService = mock(GetSourceCodeEntryService.class);
		getDeliverableService = mock(GetDeliverableService.class);
		variantService = mock(VariantService.class);
		GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);
		OrganizationData od = mock(OrganizationData.class);
		when(od.getName()).thenReturn("Acme Medical");
		when(od.getUuid()).thenReturn(ORG);
		when(getOrganizationService.getOrganizationData(ORG)).thenReturn(Optional.of(od));

		when(shared.getReleaseData(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(releases.get(inv.<UUID>getArgument(0))));
		when(shared.unwindReleaseDependencies(any(ReleaseData.class))).thenReturn(new LinkedHashSet<>());
		when(getComponentService.getComponentData(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(components.get(inv.<UUID>getArgument(0))));
		when(artifactService.getArtifactData(any(UUID.class)))
				.thenAnswer(inv -> Optional.ofNullable(artifacts.get(inv.<UUID>getArgument(0))));
		when(releaseRebomService.getReleaseBoms(any(ReleaseData.class)))
				.thenAnswer(inv -> caches.getOrDefault(inv.<ReleaseData>getArgument(0).getUuid(), List.of()));
		// The merge path's read and its only write after SCORE-71 ADR-11, over the same caches; the
		// reconcile keeps reading getReleaseBoms.
		when(releaseRebomService.readRows(any(ReleaseData.class))).thenAnswer(inv -> {
			ReleaseData rd = inv.getArgument(0);
			return new ReleaseRebomService.RowsRead(rd.getUuid(), (int) setReboms.stream()
					.filter(c -> c.release().equals(rd.getUuid())).count(), null,
					caches.getOrDefault(rd.getUuid(), List.of()));
		});
		when(releaseRebomService.storeRowsIfUnchanged(any(ReleaseData.class), anyList(), any(), any())).thenAnswer(inv -> {
			ReleaseData rd = inv.getArgument(0);
			List<ReleaseBom> reboms = inv.getArgument(1);
			ReleaseRebomService.RowsRead seen = inv.getArgument(2);
			setReboms.add(new SetRebomsCall(rd.getUuid(), List.copyOf(reboms)));
			caches.put(rd.getUuid(), List.copyOf(reboms));
			return Optional.of(new ReleaseRebomService.RowsRead(seen.record(), seen.revision() + 1, seen.created(), reboms));
		});
		when(variantService.getBaseVariantForRelease(any(ReleaseData.class))).thenReturn(new VariantData());
		when(getDeliverableService.getDeliverableDataList(any())).thenReturn(List.of());

		RebomService rebom = new RebomService("http://rebom.invalid") {
			@Override
			public UUID mergeAndStoreBoms(List<UUID> bomIds, RebomOptions rebomOptions, UUID org)
					throws RelizaException {
				for (UUID id : bomIds) {
					if (missingInRebom.contains(id)) throw new RelizaException("BOM not found: " + id);
				}
				UUID merged = UUID.randomUUID();
				merges.add(new MergeCall(List.copyOf(bomIds), rebomOptions, merged));
				return merged;
			}
		};

		ReflectionTestUtils.setField(svc, "sharedReleaseService", shared);
		ReflectionTestUtils.setField(svc, "releaseRebomService", releaseRebomService);
		ReflectionTestUtils.setField(svc, "getComponentService", getComponentService);
		ReflectionTestUtils.setField(svc, "getOrganizationService", getOrganizationService);
		ReflectionTestUtils.setField(svc, "artifactService", artifactService);
		ReflectionTestUtils.setField(svc, "getSourceCodeEntryService", getSourceCodeEntryService);
		ReflectionTestUtils.setField(svc, "getDeliverableService", getDeliverableService);
		ReflectionTestUtils.setField(svc, "variantService", variantService);
		ReflectionTestUtils.setField(svc, "rebomService", rebom);
	}

	// ---- fixtures ----

	private ComponentData component(String name, ComponentType type) {
		ComponentData cd = new ComponentData();
		ReflectionTestUtils.setField(cd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(cd, "org", ORG);
		cd.setName(name);
		cd.setType(type);
		components.put(cd.getUuid(), cd);
		return cd;
	}

	private ReleaseData release(ComponentData cd, String version) {
		return release(cd.getUuid(), version);
	}

	private ReleaseData release(UUID componentUuid, String version) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", ORG);
		ReflectionTestUtils.setField(rd, "component", componentUuid);
		rd.setVersion(version);
		rd.setArtifacts(new ArrayList<>());
		releases.put(rd.getUuid(), rd);
		return rd;
	}

	/** An SBOM artifact; answers its internal bom id. */
	private UUID sbomArtifact(List<UUID> attachTo, TagRecord... tags) {
		UUID artifactUuid = UUID.randomUUID();
		UUID bomId = UUID.randomUUID();
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", artifactUuid);
		ad.setInternalBom(new InternalBom(bomId, ArtifactBelongsTo.RELEASE));
		ad.setTags(new ArrayList<>(List.of(tags)));
		artifacts.put(artifactUuid, ad);
		attachTo.add(artifactUuid);
		return bomId;
	}

	private UUID releaseSbom(ReleaseData rd, TagRecord... tags) {
		return sbomArtifact(rd.getArtifacts(), tags);
	}

	private void children(ReleaseData product, ReleaseData... kids) {
		when(shared.unwindReleaseDependencies(product)).thenReturn(new LinkedHashSet<>(List.of(kids)));
	}

	private UUID export(ReleaseData rd) throws Exception {
		return svc.getReleaseBomId(rd.getUuid(), false, false, false, null, BomStructureType.FLAT, WU, null);
	}

	private MergedSbomUnavailableException exportFails(ReleaseData rd) {
		return assertThrows(MergedSbomUnavailableException.class, () -> export(rd));
	}

	private String label(ComponentData cd, ReleaseData rd) {
		return cd.getName() + " " + rd.getVersion() + " (" + rd.getUuid() + ")";
	}

	/**
	 * The row a merge requested with FLAT_ANY is stored as (TEA-14 runtime fix 3, main's storedRow): the request
	 * under MERGE_VERSION, keyed by the release node facts it was built with.
	 */
	private RebomOptions storedFlatAny(ReleaseData rd) {
		return FLAT_ANY.withMergeVersion(ReleaseService.MERGE_VERSION)
				.withReleaseNodeFactsKey(ReleaseNodeFacts.key(svc.releaseNodeFacts(rd)));
	}

	private List<SetRebomsCall> setRebomsFor(ReleaseData rd) {
		return setReboms.stream().filter(c -> c.release().equals(rd.getUuid())).toList();
	}

	// ---- cases ----

	@Test
	void aProductOfTwoComponentsWithSbomsIsMergedUnderTheProduct() throws Exception {
		ComponentData pc = component("Infusion Pump 9000 (device)", ComponentType.PRODUCT);
		ReleaseData product = release(pc, "9000.1.0");
		product.setIdentifiers(new ArrayList<>(List.of(
				new RearmIdentifier(RearmIdentifierType.PURL, "pkg:generic/infusion-pump@9000.1.0"))));
		ReleaseData a = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		ReleaseData b = release(component("ui", ComponentType.COMPONENT), "2.0.0");
		releaseSbom(a);
		releaseSbom(b);
		children(product, a, b);

		UUID served = export(product);

		assertEquals(3, merges.size(), "each child merged, then the product: " + merges);
		assertEquals(RootComponentMergeMode.FLATTEN_UNDER_NEW_ROOT, merges.get(0).options().rootComponentMergeMode());
		assertEquals(RootComponentMergeMode.FLATTEN_UNDER_NEW_ROOT, merges.get(1).options().rootComponentMergeMode());
		MergeCall productMerge = merges.get(2);
		assertEquals(List.of(merges.get(0).mergedId(), merges.get(1).mergedId()), productMerge.ids(),
				"the product merges exactly the two children's merged boms, in child order");
		assertEquals("Infusion Pump 9000 (device)", productMerge.options().name());
		assertEquals("9000.1.0", productMerge.options().version());
		assertEquals("pkg:generic/infusion-pump@9000.1.0", productMerge.options().purl());
		assertEquals("Acme Medical", productMerge.options().group());
		assertEquals(RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, productMerge.options().rootComponentMergeMode());
		assertEquals(productMerge.mergedId(), served);
		List<SetRebomsCall> productCache = setRebomsFor(product);
		assertEquals(1, productCache.size());
		assertEquals(List.of(new ReleaseBom(served, storedFlatAny(product))), productCache.get(0).reboms());
		// main's options on the product merge (TEA-14 decision 1)
		assertEquals(Integer.valueOf(ReleaseService.MERGE_VERSION), productMerge.options().mergeVersion());
		assertNull(productMerge.options().missingSbomComponents(), "nothing missing");
	}

	@Test
	void aProductWhoseChildrenCarryNoSbomNamesEveryChild() throws Exception {
		ComponentData pc = component("device", ComponentType.PRODUCT);
		ReleaseData product = release(pc, "9000.1.0");
		ComponentData ac = component("firmware", ComponentType.COMPONENT);
		ComponentData bc = component("ui", ComponentType.COMPONENT);
		ReleaseData a = release(ac, "1.0.0");
		ReleaseData b = release(bc, "2.0.0");
		children(product, a, b);

		MergedSbomUnavailableException e = exportFails(product);

		assertEquals(Reason.NO_SBOM_ARTIFACTS, e.reason());
		assertEquals(product.getUuid(), e.release());
		assertEquals("No SBOMs found: product release " + label(pc, product)
				+ " carries no SBOM artifact itself and none of its 2 component releases carries one"
				+ " under belongsTo=ANY, excluding coverage none: " + label(ac, a) + ", " + label(bc, b),
				e.getMessage());
		assertEquals(List.of(ChildBomStatus.NO_SBOM, ChildBomStatus.NO_SBOM),
				e.children().stream().map(ChildBomOutcome::status).toList());
		assertEquals(List.of(a.getUuid(), b.getUuid()), e.children().stream().map(ChildBomOutcome::release).toList());
		assertTrue(merges.isEmpty(), "nothing to merge, so rebom is never called: " + merges);
		assertTrue(setReboms.isEmpty(), "nothing merged, so nothing cached: " + setReboms);
	}

	/** The sandbox's 9000.1.0: its own device-sbom, no component releases. */
	@Test
	void aProductWithItsOwnSbomAndNoChildrenMergesItsOwnSbom() throws Exception {
		ComponentData pc = component("Infusion Pump 9000 (device)", ComponentType.PRODUCT);
		ReleaseData product = release(pc, "9000.1.0");
		UUID deviceSbom = releaseSbom(product);

		UUID served = export(product);

		assertEquals(1, merges.size(), merges.toString());
		assertEquals(List.of(deviceSbom), merges.get(0).ids());
		assertEquals("Infusion Pump 9000 (device)", merges.get(0).options().name());
		assertEquals("9000.1.0", merges.get(0).options().version());
		assertEquals(RootComponentMergeMode.PRESERVE_UNDER_NEW_ROOT, merges.get(0).options().rootComponentMergeMode());
		assertEquals(merges.get(0).mergedId(), served);
		assertEquals(List.of(new ReleaseBom(served, storedFlatAny(product))), setRebomsFor(product).get(0).reboms());
	}

	@Test
	void aProductMergesItsOwnSbomFirstThenTheChildren() throws Exception {
		ReleaseData product = release(component("device", ComponentType.PRODUCT), "1.0");
		ReleaseData a = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		UUID own = releaseSbom(product);
		releaseSbom(a);
		children(product, a);

		export(product);

		assertEquals(2, merges.size(), merges.toString());
		assertEquals(List.of(own, merges.get(0).mergedId()), merges.get(1).ids(),
				"own id first, then the child's merged id, no duplicates");
	}

	@Test
	void aProductWithNothingAtAllSaysItHasNoComponentReleases() {
		ComponentData pc = component("device", ComponentType.PRODUCT);
		ReleaseData product = release(pc, "1.0");

		MergedSbomUnavailableException e = exportFails(product);

		assertEquals(Reason.NO_SBOM_ARTIFACTS, e.reason());
		assertEquals("No SBOMs found: product release " + label(pc, product)
				+ " carries no SBOM artifact itself and it has no component releases"
				+ " under belongsTo=ANY, excluding coverage none", e.getMessage());
		assertTrue(e.children().isEmpty());
	}

	@Test
	void aFailingChildFailsTheProductAndNamesTheChild() {
		ComponentData pc = component("device", ComponentType.PRODUCT);
		ReleaseData product = release(pc, "1.0");
		ComponentData ac = component("firmware", ComponentType.COMPONENT);
		ReleaseData a = release(ac, "1.0.0");
		ReleaseData b = release(component("ui", ComponentType.COMPONENT), "2.0.0");
		UUID gone = releaseSbom(a);
		releaseSbom(b);
		missingInRebom.add(gone);
		children(product, a, b);

		MergedSbomUnavailableException e = exportFails(product);

		assertEquals(Reason.CHILD_MERGE_FAILED, e.reason());
		assertEquals("Merged SBOM for product release " + label(pc, product) + " could not be built: 1 of 2"
				+ " component releases failed: " + label(ac, a) + ": BOM not found: " + gone, e.getMessage());
		assertEquals(List.of(ChildBomStatus.FAILED, ChildBomStatus.MERGED),
				e.children().stream().map(ChildBomOutcome::status).toList());
		assertEquals("BOM not found: " + gone, e.children().get(0).detail());
		assertEquals(1, merges.size(), "only the healthy child merged; no product merge: " + merges);
		assertTrue(setRebomsFor(product).isEmpty(), "no partial document is cached for the product");
	}

	@Test
	void excludedCoverageIsNamedWhenItLeavesNothing() {
		ComponentData pc = component("device", ComponentType.PRODUCT);
		ReleaseData product = release(pc, "1.0");
		ReleaseData a = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		releaseSbom(a, new TagRecord(CommonVariables.ARTIFACT_COVERAGE_TYPE_TAG_KEY, ArtifactCoverageType.TEST.name()));
		children(product, a);

		MergedSbomUnavailableException e = assertThrows(MergedSbomUnavailableException.class,
				() -> svc.getReleaseBomId(product.getUuid(), false, false, false, null, BomStructureType.FLAT, WU,
						List.of(ArtifactCoverageType.TEST, ArtifactCoverageType.DEV)));

		assertEquals(Reason.NO_SBOM_ARTIFACTS, e.reason());
		assertTrue(e.getMessage().contains("under belongsTo=ANY, excluding coverage TEST,DEV: "), e.getMessage());
		assertTrue(merges.isEmpty(), merges.toString());
	}

	@Test
	void belongsToDeliverableSkipsTheProductsOwnReleaseArtifacts() throws Exception {
		ReleaseData product = release(component("device", ComponentType.PRODUCT), "1.0");
		releaseSbom(product);
		ReleaseData a = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		DeliverableData dd = new DeliverableData();
		UUID deliverableBom = sbomArtifact(dd.getArtifacts());
		UUID deliverableUuid = UUID.randomUUID();
		VariantData variant = new VariantData();
		variant.setOutboundDeliverables(new LinkedHashSet<>(List.of(deliverableUuid)));
		when(variantService.getBaseVariantForRelease(a)).thenReturn(variant);
		when(getDeliverableService.getDeliverableDataList(List.of(deliverableUuid))).thenReturn(List.of(dd));
		children(product, a);

		svc.getReleaseBomId(product.getUuid(), false, false, false, ArtifactBelongsTo.DELIVERABLE,
				BomStructureType.FLAT, WU, null);

		assertEquals(2, merges.size(), merges.toString());
		assertEquals(List.of(deliverableBom), merges.get(0).ids(), "the child's deliverable bom is gathered");
		assertEquals(List.of(merges.get(0).mergedId()), merges.get(1).ids(),
				"the product's own release-level artifact is not gathered under DELIVERABLE");
	}

	@Test
	void aCachedConfigurationIsServedWithoutUnwindingOrMerging() throws Exception {
		ReleaseData product = release(component("device", ComponentType.PRODUCT), "1.0");
		UUID cached = UUID.randomUUID();
		caches.put(product.getUuid(), List.of(new ReleaseBom(cached, storedFlatAny(product))));

		assertEquals(cached, export(product));
		verify(shared, never()).unwindReleaseDependencies(any());
		assertTrue(merges.isEmpty(), merges.toString());
	}

	@Test
	void aNestedProductsFailingGrandchildIsNamedThroughItsParent() {
		ComponentData pc = component("system", ComponentType.PRODUCT);
		ReleaseData product = release(pc, "1.0");
		ComponentData nc = component("device", ComponentType.PRODUCT);
		ReleaseData nested = release(nc, "2.0");
		ComponentData gc = component("firmware", ComponentType.COMPONENT);
		ReleaseData grandchild = release(gc, "3.0");
		missingInRebom.add(releaseSbom(grandchild));
		children(product, nested);
		children(nested, grandchild);

		MergedSbomUnavailableException e = exportFails(product);

		assertEquals(Reason.CHILD_MERGE_FAILED, e.reason());
		ChildBomOutcome failing = e.children().get(0);
		assertEquals(nested.getUuid(), failing.release());
		assertEquals(ChildBomStatus.FAILED, failing.status());
		assertTrue(failing.detail().startsWith("Merged SBOM for product release " + label(nc, nested)), failing.detail());
		assertTrue(failing.detail().contains(label(gc, grandchild)), failing.detail());
	}

	@Test
	void aChildWithAMissingComponentIsAFailedOutcomeNotACrash() {
		ReleaseData product = release(component("device", ComponentType.PRODUCT), "1.0");
		UUID danglingComponent = UUID.randomUUID();
		ReleaseData orphan = release(danglingComponent, "1.0.0");
		children(product, orphan);

		MergedSbomUnavailableException e = exportFails(product);

		assertEquals(Reason.CHILD_MERGE_FAILED, e.reason());
		ChildBomOutcome failing = e.children().get(0);
		assertEquals(ChildBomStatus.FAILED, failing.status());
		assertEquals("component " + danglingComponent + " of release " + orphan.getUuid() + " not found",
				failing.detail());
		assertEquals(danglingComponent.toString(), failing.componentName(),
				"the name falls back to the component uuid");
	}

	@Test
	void aComponentReleaseWithoutSbomNamesTheRelease() {
		ComponentData cc = component("firmware", ComponentType.COMPONENT);
		ReleaseData rd = release(cc, "1.0.0");

		MergedSbomUnavailableException e = exportFails(rd);

		assertEquals(Reason.NO_SBOM_ARTIFACTS, e.reason());
		assertEquals("No SBOMs found: release firmware 1.0.0 (" + rd.getUuid()
				+ ") carries no SBOM artifact under belongsTo=ANY, excluding coverage none", e.getMessage());
		assertTrue(e.children().isEmpty());
	}

	@Test
	void collectOwnBomIdsReadsDeliverablesForAComponentOnly() {
		ComponentData cc = component("firmware", ComponentType.COMPONENT);
		ReleaseData rd = release(cc, "1.0.0");
		// deliverable source
		DeliverableData dd = new DeliverableData();
		UUID deliverableBom = sbomArtifact(dd.getArtifacts());
		UUID deliverableUuid = UUID.randomUUID();
		rd.setInboundDeliverables(new ArrayList<>(List.of(deliverableUuid)));
		when(getDeliverableService.getDeliverableDataList(any())).thenReturn(List.of(dd));
		// SCE source: one artifact of this component, one of another component
		SourceCodeEntryData sce = mock(SourceCodeEntryData.class);
		List<UUID> sceArtifacts = new ArrayList<>();
		UUID sceBom = sbomArtifact(sceArtifacts);
		List<UUID> otherArtifacts = new ArrayList<>();
		sbomArtifact(otherArtifacts);
		when(sce.getArtifacts()).thenReturn(List.of(new SCEArtifact(sceArtifacts.get(0), cc.getUuid()),
				new SCEArtifact(otherArtifacts.get(0), UUID.randomUUID())));
		UUID sceUuid = UUID.randomUUID();
		rd.setSourceCodeEntry(sceUuid);
		when(getSourceCodeEntryService.getSourceCodeEntryData(sceUuid)).thenReturn(Optional.of(sce));
		// release source, plus the deliverable's artifact attached again: distinct ids
		UUID releaseBom = releaseSbom(rd);
		rd.getArtifacts().add(dd.getArtifacts().get(0));

		assertEquals(List.of(deliverableBom, sceBom, releaseBom),
				svc.collectOwnBomIds(rd, ComponentType.COMPONENT, FLAT_ANY, null));

		ComponentData pc = component("device", ComponentType.PRODUCT);
		ReleaseData product = release(pc, "1.0");
		UUID productBom = releaseSbom(product);
		VariantService freshVariants = mock(VariantService.class);
		GetDeliverableService freshDeliverables = mock(GetDeliverableService.class);
		ReflectionTestUtils.setField(svc, "variantService", freshVariants);
		ReflectionTestUtils.setField(svc, "getDeliverableService", freshDeliverables);

		assertEquals(List.of(productBom), svc.collectOwnBomIds(product, ComponentType.PRODUCT, FLAT_ANY, null));
		verifyNoInteractions(freshVariants, freshDeliverables);
		verify(shared, never()).unwindReleaseDependencies(product);
		assertTrue(merges.isEmpty(), "collecting never merges: " + merges);
	}

	// TEA-14 architecture row 9: main's placeholders for now (TEA-15 changes the publication path)
	@Test
	void aChildWithoutSbomIsAPlaceholderWhenAnotherChildHasOne() throws Exception {
		ReleaseData product = release(component("device", ComponentType.PRODUCT), "1.0");
		ReleaseData a = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		ReleaseData bare = release(component("ui", ComponentType.COMPONENT), "2.0.0");
		releaseSbom(a);
		children(product, a, bare);

		UUID served = export(product);

		assertEquals(2, merges.size(), merges.toString());
		MergeCall productMerge = merges.get(1);
		assertEquals(List.of(merges.get(0).mergedId()), productMerge.ids(), "one merge of the one child BOM");
		assertEquals(served, productMerge.mergedId());
		List<MissingSbomComponent> missing = productMerge.options().missingSbomComponents();
		assertEquals(1, missing.size(), String.valueOf(missing));
		assertEquals(bare.getUuid().toString(), missing.get(0).releaseUuid());
		assertEquals(MissingSbomReason.NO_SBOM_ARTIFACT, missing.get(0).reason());
	}

	// TEA-14 architecture row 10: the carve-out of decision 1
	@Test
	void aReleaseGraphCycleSkipsThatEntryAndDoesNotFailTheProduct() throws Exception {
		ComponentData pc = component("system", ComponentType.PRODUCT);
		ReleaseData product = release(pc, "1.0");
		ReleaseData nested = release(component("device", ComponentType.PRODUCT), "2.0");
		ReleaseData a = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		releaseSbom(a);
		// a legacy cycle: the nested product bundles the outer one back
		children(product, nested, a);
		children(nested, product, a);
		CapturingAppender logs = new CapturingAppender();
		logs.start();
		org.apache.logging.log4j.core.Logger rsLog = (org.apache.logging.log4j.core.Logger)
				org.apache.logging.log4j.LogManager.getLogger(ReleaseService.class);
		rsLog.addAppender(logs);
		UUID served;
		try {
			served = export(product);
		} finally {
			rsLog.removeAppender(logs);
			logs.stop();
		}

		MergeCall outer = merges.stream().filter(m -> "1.0".equals(m.options().version())).findFirst().orElseThrow();
		assertEquals(served, outer.mergedId(), "the product is merged, not failed");
		assertEquals(1, setRebomsFor(product).size(), "and its row written");
		MergeCall inner = merges.stream().filter(m -> "2.0".equals(m.options().version())).findFirst().orElseThrow();
		assertTrue(outer.ids().contains(inner.mergedId()), "the merges around the cycle complete");
		assertNull(inner.options().missingSbomComponents(), "the entry met again is left out, not a placeholder");
		assertEquals(1, logs.events.stream().filter(e -> e.getLevel() == org.apache.logging.log4j.Level.ERROR
				&& e.getMessage().getFormattedMessage().startsWith("Release graph cycle")).count(), logs.events.toString());
	}

	/** In-memory appender on ReleaseService's logger; only the level and the message matter. */
	private static class CapturingAppender extends org.apache.logging.log4j.core.appender.AbstractAppender {
		private final List<org.apache.logging.log4j.core.LogEvent> events =
				java.util.Collections.synchronizedList(new ArrayList<>());
		CapturingAppender() {
			super("capture-" + UUID.randomUUID(), null, null, true, org.apache.logging.log4j.core.config.Property.EMPTY_ARRAY);
		}
		@Override public void append(org.apache.logging.log4j.core.LogEvent event) { events.add(event.toImmutable()); }
	}
}
