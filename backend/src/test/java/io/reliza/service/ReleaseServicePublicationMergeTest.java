/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.EnumSet;
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
import io.reliza.common.Utils;
import io.reliza.common.Utils.ArtifactBelongsTo;
import io.reliza.exceptions.MergedSbomUnavailableException;
import io.reliza.exceptions.MergedSbomUnavailableException.Reason;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.DeliverableData;
import io.reliza.model.ExportMetadataChoice;
import io.reliza.model.LevelOfSupport;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseRebomData.ReleaseBom;
import io.reliza.model.SbomComponent;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.SupportData;
import io.reliza.model.SupportInjectionSetting;
import io.reliza.model.SupportParty;
import io.reliza.model.SupportSource;
import io.reliza.model.SupportState;
import io.reliza.model.VariantData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ExportMetadataOptions;
import io.reliza.model.Rebom.InternalBom;
import io.reliza.model.Rebom.MissingSbomMode;
import io.reliza.model.Rebom.RebomOptions;
import io.reliza.model.Rebom.ReleaseNodeFacts;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportRepository;
import io.reliza.service.RebomService.BomMediaType;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.ReleaseService.PublicationMergeRequest;
import io.reliza.service.SupportBomInjector.ComponentSupportFacts;
import tools.jackson.databind.JsonNode;

/**
 * TEA-5: the aggregated SBOM a TEA publication freezes (architecture 3.5 step 6, cases 23 to 28 of
 * 4.3). Built the way ReleaseServiceProductMergeTest builds the service: collaborators mocked,
 * rebom recorded, the release_reboms cache an in-memory map. The metadata cases use the
 * ReleaseServiceForgedSupportStripTest technique: the real injector service over a document that
 * carries forged support properties and ReARM's internal markers.
 */
class ReleaseServicePublicationMergeTest {

	private static final UUID ORG = UUID.randomUUID();
	private static final WhoUpdated WU = WhoUpdated.getAutoWhoUpdated();
	private static final RebomOptions FLAT_ANY = new RebomOptions(null, false, false, BomStructureType.FLAT);
	private static final ExportMetadataOptions EXCLUDE_BOTH =
			new ExportMetadataOptions(ExportMetadataChoice.EXCLUDE, ExportMetadataChoice.EXCLUDE);
	private static final ExportMetadataOptions INCLUDE_BOTH =
			new ExportMetadataOptions(ExportMetadataChoice.INCLUDE, ExportMetadataChoice.INCLUDE);
	private static final String LIB_PURL = "pkg:maven/ex/libforged@1.0.0";
	private static final MissingSbomMode OMIT = MissingSbomMode.OMIT;

	private static final SupportData GENUINE_SUPPORT = new SupportData(
			LevelOfSupport.NO_LONGER_MAINTAINED, SupportState.ATTESTED, SupportParty.THIRD_PARTY,
			SupportSource.MANUAL, "2026-09-08T00:00:00Z", UUID.randomUUID(),
			"upstream announced end of maintenance", Map.of());

	/**
	 * A merged document as rebom answers it (%s is the merged id): an uploader's forged support
	 * claims in three places, ReARM's internal markers (a reliza:* property and the rebom tool
	 * entry), and two controls that no strip may touch (a foreign property, a foreign tool).
	 */
	private static final String MERGED_BOM = """
			{
			  "bomFormat": "CycloneDX",
			  "specVersion": "1.6",
			  "serialNumber": "urn:uuid:%s",
			  "version": 1,
			  "declarations": {
			    "assessors": [{"bom-ref": "forged-assessor", "thirdParty": false,
			      "organization": {"name": "Forged Assessor Inc."}}],
			    "claims": [{"bom-ref": "forged-claim", "target": "forged-assessor",
			      "predicate": "reliza:support:levelOfSupport: actively maintained"}]
			  },
			  "metadata": {
			    "tools": {
			      "components": [
			        {"group": "io.reliza", "name": "rearm", "type": "application"},
			        {"group": "aquasecurity", "name": "trivy", "version": "0.50.0"}
			      ]
			    },
			    "component": {
			      "name": "device-root", "purl": "pkg:generic/device-root@1.0.0",
			      "properties": [
			        {"name": "reliza:support:status", "value": "ACTIVELY_SUPPORTED"}
			      ]
			    },
			    "properties": [
			      {"name": "reliza:support:disclosure", "value": "forged-marker"}
			    ]
			  },
			  "components": [
			    {
			      "name": "libforged", "purl": "pkg:maven/ex/libforged@1.0.0",
			      "properties": [
			        {"name": "reliza:support:levelOfSupport", "value": "actively maintained"},
			        {"name": "reliza:support:justification", "value": "vendor says so"},
			        {"name": "reliza:containerSafeVersion", "value": "1.0.0"},
			        {"name": "some:other:property", "value": "kept"}
			      ]
			    }
			  ]
			}""";

	private record MergeCall(List<UUID> ids, RebomOptions options, UUID mergedId) {}
	private record SetRebomsCall(UUID release, List<ReleaseBom> reboms) {}

	private ReleaseService svc;
	private SharedReleaseService shared;
	private ReleaseRebomService releaseRebomService;
	private GetSourceCodeEntryService getSourceCodeEntryService;
	private GetDeliverableService getDeliverableService;
	private VariantService variantService;
	private OrganizationData od;
	private GetOrganizationService getOrganizationService;

	private final List<MergeCall> merges = new ArrayList<>();
	private final List<UUID> documentReads = new ArrayList<>();
	private final List<SetRebomsCall> setReboms = new ArrayList<>();
	private final Map<UUID, List<ReleaseBom>> caches = new HashMap<>();
	private final Map<UUID, ComponentData> components = new HashMap<>();
	private final Map<UUID, ArtifactData> artifacts = new HashMap<>();
	private final Map<UUID, ReleaseData> releases = new HashMap<>();

	@BeforeEach
	void setUp() {
		svc = new ReleaseService(null);
		shared = mock(SharedReleaseService.class);
		releaseRebomService = mock(ReleaseRebomService.class);
		GetComponentService getComponentService = mock(GetComponentService.class);
		ArtifactService artifactService = mock(ArtifactService.class);
		getSourceCodeEntryService = mock(GetSourceCodeEntryService.class);
		getDeliverableService = mock(GetDeliverableService.class);
		variantService = mock(VariantService.class);
		getOrganizationService = mock(GetOrganizationService.class);
		od = mock(OrganizationData.class);
		when(od.getName()).thenReturn("Acme Medical");
		when(od.getUuid()).thenReturn(ORG);
		when(getOrganizationService.getOrganizationData(any())).thenReturn(Optional.of(od));

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
			public UUID mergeAndStoreBoms(List<UUID> bomIds, RebomOptions rebomOptions, UUID org) {
				UUID merged = UUID.randomUUID();
				merges.add(new MergeCall(List.copyOf(bomIds), rebomOptions, merged));
				return merged;
			}

			@Override
			public JsonNode findBomByIdJson(UUID bomSerialNumber, UUID org) {
				documentReads.add(bomSerialNumber);
				return Utils.OM.readTree(MERGED_BOM.formatted(bomSerialNumber));
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
		// The real injector; with EXCLUDE support metadata neither repository nor the org lookup is
		// touched. The metadata cases install an org with injection enabled themselves.
		ReflectionTestUtils.setField(svc, "supportInjectionService", new SupportInjectionService(null, null, null, null));
		ReflectionTestUtils.setField(svc, "sbomExportSigningService", unsignedExports());
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
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "uuid", UUID.randomUUID());
		ReflectionTestUtils.setField(rd, "org", ORG);
		ReflectionTestUtils.setField(rd, "component", cd.getUuid());
		rd.setVersion(version);
		rd.setArtifacts(new ArrayList<>());
		releases.put(rd.getUuid(), rd);
		return rd;
	}

	/** An SBOM artifact attached to the given list; answers its internal bom id. */
	private UUID sbomArtifact(List<UUID> attachTo) {
		UUID artifactUuid = UUID.randomUUID();
		UUID bomId = UUID.randomUUID();
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", artifactUuid);
		ad.setInternalBom(new InternalBom(bomId, ArtifactBelongsTo.RELEASE));
		ad.setTags(new ArrayList<>());
		artifacts.put(artifactUuid, ad);
		attachTo.add(artifactUuid);
		return bomId;
	}

	private UUID releaseSbom(ReleaseData rd) {
		return sbomArtifact(rd.getArtifacts());
	}

	private void children(ReleaseData product, ReleaseData... kids) {
		when(shared.unwindReleaseDependencies(product)).thenReturn(new LinkedHashSet<>(List.of(kids)));
	}

	/** The three sources of a component release: one SBOM each. */
	private record ThreeSources(ReleaseData rd, UUID deliverableBom, UUID sceBom, UUID releaseBom) {}

	private ThreeSources componentWithThreeSources() {
		ComponentData cc = component("firmware", ComponentType.COMPONENT);
		ReleaseData rd = release(cc, "1.0.0");
		DeliverableData dd = new DeliverableData();
		UUID deliverableBom = sbomArtifact(dd.getArtifacts());
		rd.setInboundDeliverables(new ArrayList<>(List.of(UUID.randomUUID())));
		when(getDeliverableService.getDeliverableDataList(any())).thenReturn(List.of(dd));
		SourceCodeEntryData sce = mock(SourceCodeEntryData.class);
		List<UUID> sceArtifacts = new ArrayList<>();
		UUID sceBom = sbomArtifact(sceArtifacts);
		when(sce.getArtifacts()).thenReturn(List.of(new SCEArtifact(sceArtifacts.get(0), cc.getUuid())));
		UUID sceUuid = UUID.randomUUID();
		rd.setSourceCodeEntry(sceUuid);
		when(getSourceCodeEntryService.getSourceCodeEntryData(sceUuid)).thenReturn(Optional.of(sce));
		UUID releaseBom = releaseSbom(rd);
		return new ThreeSources(rd, deliverableBom, sceBom, releaseBom);
	}

	private static PublicationMergeRequest request(Set<ArtifactBelongsTo> sources, ExportMetadataOptions metadata) {
		return new PublicationMergeRequest(sources, false, false, BomStructureType.FLAT, null, metadata);
	}

	private static PublicationMergeRequest request(Set<ArtifactBelongsTo> sources) {
		return request(sources, EXCLUDE_BOTH);
	}

	private JsonNode publish(ReleaseData rd, PublicationMergeRequest request) throws RelizaException {
		return svc.generateMergedBomForPublication(rd, request, WU);
	}

	/**
	 * The row a merge requested with {@code options} is stored as, and the one a cached row must be to serve
	 * (TEA-14 runtime fix 3, main's storedRow and matcher): the request under MERGE_VERSION, keyed by the
	 * release node facts it was built with.
	 */
	private RebomOptions stored(ReleaseData rd, RebomOptions options) {
		return options.withMergeVersion(ReleaseService.MERGE_VERSION)
				.withReleaseNodeFactsKey(ReleaseNodeFacts.key(svc.releaseNodeFacts(rd)));
	}

	/** The export's signing step, with no key for the organization: it stamps the unsigned record (SCORE-33). */
	private static SbomExportSigningService unsignedExports() {
		return new SbomExportSigningService(mock(SbomSigningKeyService.class), mock(SbomSigner.class));
	}

	private List<SetRebomsCall> setRebomsFor(ReleaseData rd) {
		return setReboms.stream().filter(c -> c.release().equals(rd.getUuid())).toList();
	}

	private static String serialOf(JsonNode doc) {
		return doc.get("serialNumber").asText();
	}

	/** Installs the real injector over an org with injection ENABLED and one genuine fact for libforged. */
	private SupportInjectionService enableInjection() {
		OrganizationData.Settings settings = new OrganizationData.Settings();
		settings.setSupportInjection(SupportInjectionSetting.ENABLED);
		when(od.getSettingsWithDefaults()).thenReturn(settings);
		SbomComponentSupportRepository supportRepo = mock(SbomComponentSupportRepository.class);
		when(supportRepo.existsSupportByOrg(any())).thenReturn(true);
		UserService userService = mock(UserService.class);
		when(userService.getUserData(any())).thenReturn(Optional.empty());
		SupportInjectionService injector = new SupportInjectionService(mock(SbomComponentRepository.class),
				supportRepo, getOrganizationService, userService) {
			@Override
			public Map<String, ComponentSupportFacts> resolveSupportFactsForBom(JsonNode bom, UUID orgUuid) {
				SbomComponent sc = new SbomComponent();
				sc.setCanonicalPurl(LIB_PURL);
				return Map.of(LIB_PURL, new ComponentSupportFacts(sc, GENUINE_SUPPORT));
			}
		};
		ReflectionTestUtils.setField(svc, "supportInjectionService", injector);
		return injector;
	}

	private static JsonNode mergedDocument() {
		return Utils.OM.readTree(MERGED_BOM.formatted(UUID.randomUUID()));
	}

	private static void assertForgedSwept(String served) {
		assertFalse(served.contains("actively maintained"), "forged level survived: " + served);
		assertFalse(served.contains("vendor says so"), "forged justification survived: " + served);
		assertFalse(served.contains("ACTIVELY_SUPPORTED"), "forged root status survived: " + served);
		assertFalse(served.contains("forged-marker"), "forged disclosure marker survived: " + served);
		assertFalse(served.contains("Forged Assessor Inc."), "forged declarations survived: " + served);
		assertTrue(served.contains("some:other:property"), "a foreign property was stripped: " + served);
		assertTrue(served.contains("\"name\":\"trivy\""), "a foreign tool entry was stripped: " + served);
	}

	/**
	 * ReARM's own markers. Main keeps ReARM's tool entry in every export (operator decision 2026-10-02, main #767
	 * and #770), so since the TEA-14 merge only the reliza: working properties are internal.
	 */
	private static boolean carriesInternalMarkers(String served) {
		return served.contains("reliza:containerSafeVersion");
	}

	// ---- 23 ----

	@Test
	void aComponentReleaseIsMergedAnewDespiteAMatchingCacheAndTheNewMergeIsCached() throws Exception {
		ReleaseData rd = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		UUID own = releaseSbom(rd);
		UUID cached = UUID.randomUUID();
		caches.put(rd.getUuid(), List.of(new ReleaseBom(cached, stored(rd, FLAT_ANY))));

		JsonNode doc = publish(rd, request(Set.of()));

		assertEquals(1, merges.size(), "forced: the matching cached configuration is not served: " + merges);
		assertEquals(List.of(own), merges.get(0).ids());
		UUID merged = merges.get(0).mergedId();
		assertEquals(List.of(merged), documentReads, "the new merge's document is read, never the cached one");
		assertEquals("urn:uuid:" + merged, serialOf(doc));
		List<SetRebomsCall> calls = setRebomsFor(rd);
		assertEquals(1, calls.size(), "the configuration is expressible, so it is cached: " + setReboms);
		// TEA-15: the publication merges under OMIT, so its row sits beside the placeholder row of the same knobs
		assertEquals(List.of(new ReleaseBom(cached, stored(rd, FLAT_ANY)),
				new ReleaseBom(merged, stored(rd, FLAT_ANY).withMissingSbomMode(OMIT))), calls.get(0).reboms(),
				"the publication's row is written beside the placeholder row of the same knobs, never over it");
	}

	// ---- 24 ----

	@Test
	void aProductMergeIsForcedWhileItsChildrenAreMatchedFromTheirCaches() throws Exception {
		ReleaseData product = release(component("device", ComponentType.PRODUCT), "9000.1.0");
		UUID own = releaseSbom(product);
		// TEA-15: a publication reads OMIT rows
		caches.put(product.getUuid(), List.of(new ReleaseBom(UUID.randomUUID(),
				stored(product, FLAT_ANY).withMissingSbomMode(OMIT))));
		ReleaseData cachedChild = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		releaseSbom(cachedChild);
		UUID childCached = UUID.randomUUID();
		caches.put(cachedChild.getUuid(), List.of(new ReleaseBom(childCached,
				stored(cachedChild, FLAT_ANY).withMissingSbomMode(OMIT))));
		ReleaseData uncachedChild = release(component("ui", ComponentType.COMPONENT), "2.0.0");
		UUID uncachedBom = releaseSbom(uncachedChild);
		children(product, cachedChild, uncachedChild);

		JsonNode doc = publish(product, request(Set.of()));

		assertEquals(2, merges.size(), "the uncached child, then the product; no merge for the cached child: " + merges);
		assertEquals(List.of(uncachedBom), merges.get(0).ids());
		MergeCall productMerge = merges.get(1);
		assertEquals(List.of(own, childCached, merges.get(0).mergedId()), productMerge.ids(),
				"the product merges its own SBOM, the cached child's merged bom and the fresh one");
		assertEquals("device", productMerge.options().name());
		assertEquals("urn:uuid:" + productMerge.mergedId(), serialOf(doc));
		assertTrue(setRebomsFor(cachedChild).isEmpty(), "a matched child is not written again");
		assertEquals(List.of(new ReleaseBom(productMerge.mergedId(), stored(product, FLAT_ANY).withMissingSbomMode(OMIT))),
				setRebomsFor(product).get(0).reboms());
	}

	/** TEA-15: a child's placeholder row is the export's document, never the publication's. */
	@Test
	void aChildsPlaceholderRowDoesNotServeThePublication() throws Exception {
		ReleaseData product = release(component("device", ComponentType.PRODUCT), "9000.1.0");
		UUID own = releaseSbom(product);
		UUID productCached = UUID.randomUUID();
		caches.put(product.getUuid(), List.of(new ReleaseBom(productCached, stored(product, FLAT_ANY))));
		ReleaseData cachedChild = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		UUID cachedChildBom = releaseSbom(cachedChild);
		UUID childCached = UUID.randomUUID();
		caches.put(cachedChild.getUuid(), List.of(new ReleaseBom(childCached, stored(cachedChild, FLAT_ANY))));
		ReleaseData uncachedChild = release(component("ui", ComponentType.COMPONENT), "2.0.0");
		UUID uncachedBom = releaseSbom(uncachedChild);
		children(product, cachedChild, uncachedChild);

		publish(product, request(Set.of()));

		assertEquals(3, merges.size(), "both children and the product: a placeholder row serves no publication: " + merges);
		assertEquals(List.of(cachedChildBom), merges.get(0).ids());
		assertEquals(List.of(uncachedBom), merges.get(1).ids());
		MergeCall productMerge = merges.get(2);
		assertEquals(List.of(own, merges.get(0).mergedId(), merges.get(1).mergedId()), productMerge.ids(),
				"the product never consumes the child's placeholder row");
		assertFalse(productMerge.ids().contains(childCached), productMerge.toString());
		assertEquals(List.of(new ReleaseBom(childCached, stored(cachedChild, FLAT_ANY)),
				new ReleaseBom(merges.get(0).mergedId(), stored(cachedChild, FLAT_ANY).withMissingSbomMode(OMIT))),
				setRebomsFor(cachedChild).get(0).reboms(), "the child's OMIT row is written beside its placeholder row");
		assertEquals(List.of(new ReleaseBom(productCached, stored(product, FLAT_ANY)),
				new ReleaseBom(productMerge.mergedId(), stored(product, FLAT_ANY).withMissingSbomMode(OMIT))),
				setRebomsFor(product).get(0).reboms(), "the product's OMIT row is written beside its placeholder row");
	}

	/**
	 * SCORE-69 ADR-10: the publication passes its release's uuid as componentFilter, so a nested product is
	 * regenerated with it (its row then names the rows this publication merges) while a component child is
	 * still matched from its OMIT row; the outer merge consumes the fresh nested row, never the stale one.
	 */
	@Test
	void aPublicationRegeneratesItsNestedProductsAndStillMatchesComponentChildrenFromTheirCaches() throws Exception {
		ReleaseData outer = release(component("device", ComponentType.PRODUCT), "9000.1.0");
		UUID own = releaseSbom(outer);
		ReleaseData inner = release(component("board", ComponentType.PRODUCT), "1.0.0");
		UUID innerOwn = releaseSbom(inner);
		UUID innerCached = UUID.randomUUID();
		caches.put(inner.getUuid(), List.of(new ReleaseBom(innerCached, stored(inner, FLAT_ANY).withMissingSbomMode(OMIT))));
		ReleaseData firmware = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		releaseSbom(firmware);
		UUID childCached = UUID.randomUUID();
		caches.put(firmware.getUuid(), List.of(new ReleaseBom(childCached, stored(firmware, FLAT_ANY).withMissingSbomMode(OMIT))));
		children(outer, inner, firmware);
		children(inner, firmware);

		publish(outer, request(Set.of()));

		assertEquals(2, merges.size(), "the nested product, then the outer; no merge for the component: " + merges);
		assertEquals(List.of(innerOwn, childCached), merges.get(0).ids(), "the nested product regenerated, its component child matched");
		assertEquals("board", merges.get(0).options().name());
		MergeCall outerMerge = merges.get(1);
		assertEquals(List.of(own, merges.get(0).mergedId(), childCached), outerMerge.ids(),
				"the outer merges its own SBOM, the fresh nested row and the cached component row");
		assertFalse(outerMerge.ids().contains(innerCached), "the stale nested row is never consumed: " + outerMerge);
		List<SetRebomsCall> innerWrites = setRebomsFor(inner);
		assertEquals(1, innerWrites.size(), innerWrites.toString());
		assertEquals(List.of(new ReleaseBom(merges.get(0).mergedId(), stored(inner, FLAT_ANY).withMissingSbomMode(OMIT))),
				innerWrites.get(0).reboms(), "the nested product's stale OMIT row is replaced");
		assertTrue(setRebomsFor(firmware).isEmpty(), "a matched component is not written again");
		assertEquals(List.of(new ReleaseBom(outerMerge.mergedId(), stored(outer, FLAT_ANY).withMissingSbomMode(OMIT))),
				setRebomsFor(outer).get(0).reboms());
	}

	// ---- SCORE-71 ADR-5: a merge that finds nothing drops its request's row ----

	/**
	 * SCORE-71 T-3 (a): a release that lost its SBOM after its OMIT row was stored. The publication forces it,
	 * finds nothing and drops the OMIT row only: the placeholder row of the other request stays.
	 */
	@Test
	void aReleaseThatLostItsSbomDropsItsOmitRowAndKeepsItsPlaceholderRow() {
		ReleaseData rd = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		UUID p = UUID.randomUUID();
		UUID o = UUID.randomUUID();
		caches.put(rd.getUuid(), List.of(new ReleaseBom(p, stored(rd, FLAT_ANY)),
				new ReleaseBom(o, stored(rd, FLAT_ANY).withMissingSbomMode(OMIT))));

		MergedSbomUnavailableException e = assertThrows(MergedSbomUnavailableException.class,
				() -> publish(rd, request(Set.of())));

		assertEquals(Reason.NO_SBOM_ARTIFACTS, e.reason());
		assertTrue(merges.isEmpty(), merges.toString());
		List<SetRebomsCall> writes = setRebomsFor(rd);
		assertEquals(1, writes.size(), writes.toString());
		assertEquals(List.of(new ReleaseBom(p, stored(rd, FLAT_ANY))), writes.get(0).reboms(),
				"the OMIT row is dropped, the placeholder row of the other request kept");
	}

	/**
	 * SCORE-71 T-3 (b): a nested product with nothing left to merge is omitted from the outer merge, and the
	 * publication's forcing of it drops its stale OMIT row.
	 */
	@Test
	void aNestedProductWithNothingLeftToMergeIsOmittedAndItsStaleOmitRowDropped() throws Exception {
		ReleaseData outer = release(component("device", ComponentType.PRODUCT), "9000.1.0");
		UUID own = releaseSbom(outer);
		ReleaseData inner = release(component("board", ComponentType.PRODUCT), "1.0.0");
		UUID innerCached = UUID.randomUUID();
		caches.put(inner.getUuid(), List.of(new ReleaseBom(innerCached, stored(inner, FLAT_ANY).withMissingSbomMode(OMIT))));
		ReleaseData firmware = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		children(outer, inner, firmware);
		children(inner, firmware);

		publish(outer, request(Set.of()));

		assertEquals(1, merges.size(), merges.toString());
		assertEquals(List.of(own), merges.get(0).ids(), "neither the nested product nor the bare component is merged");
		List<SetRebomsCall> innerWrites = setRebomsFor(inner);
		assertEquals(1, innerWrites.size(), innerWrites.toString());
		assertEquals(List.of(), innerWrites.get(0).reboms(), "the nested product's stale OMIT row is dropped");
		assertTrue(setRebomsFor(firmware).isEmpty(), "a release without rows is not written");
		assertEquals(List.of(new ReleaseBom(merges.get(0).mergedId(), stored(outer, FLAT_ANY).withMissingSbomMode(OMIT))),
				setRebomsFor(outer).get(0).reboms());
	}

	/**
	 * SCORE-71 T-3 (c), the boundary: the publication forces no component child (SCORE-69 ADR-10), so a
	 * component child's stale OMIT row with unchanged facts is still matched here. Removing it is the
	 * invalidation's job, on the artifact arm of updateRelease (SCORE-71 ADR-2 (a)), which is why that arm exists.
	 */
	@Test
	void aComponentChildWithAStaleOmitRowIsStillMatchedByThePublication() throws Exception {
		ReleaseData product = release(component("device", ComponentType.PRODUCT), "9000.1.0");
		UUID own = releaseSbom(product);
		ReleaseData firmware = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		UUID childCached = UUID.randomUUID();
		caches.put(firmware.getUuid(), List.of(new ReleaseBom(childCached, stored(firmware, FLAT_ANY).withMissingSbomMode(OMIT))));
		children(product, firmware);

		publish(product, request(Set.of()));

		assertEquals(List.of(own, childCached), merges.get(0).ids());
		assertTrue(setRebomsFor(firmware).isEmpty(), setReboms.toString());
	}

	/**
	 * SCORE-71 T-3 (d), the cacheable gate: a set-filtered request is neither matched nor stored, so when it
	 * finds nothing it reads and drops no row of the release.
	 */
	@Test
	void aTwoSourceRequestThatFindsNothingDropsNoRow() {
		ReleaseData rd = release(component("bare", ComponentType.COMPONENT), "1.0.0");
		UUID p = UUID.randomUUID();
		UUID o = UUID.randomUUID();
		List<ReleaseBom> both = List.of(new ReleaseBom(p, stored(rd, FLAT_ANY)),
				new ReleaseBom(o, stored(rd, FLAT_ANY).withMissingSbomMode(OMIT)));
		caches.put(rd.getUuid(), both);

		MergedSbomUnavailableException e = assertThrows(MergedSbomUnavailableException.class,
				() -> publish(rd, request(EnumSet.of(ArtifactBelongsTo.RELEASE, ArtifactBelongsTo.SCE))));

		assertEquals(Reason.NO_SBOM_ARTIFACTS, e.reason());
		verify(releaseRebomService, never()).readRows(any());
		assertTrue(setRebomsFor(rd).isEmpty(), setReboms.toString());
		assertEquals(both, caches.get(rd.getUuid()));
	}

	// ---- 25 ----

	@Test
	void aTwoSourceSetGathersExactlyThoseSourcesAndNeverTouchesTheCache() throws Exception {
		ThreeSources s = componentWithThreeSources();
		caches.put(s.rd().getUuid(), List.of(new ReleaseBom(UUID.randomUUID(), FLAT_ANY)));

		JsonNode doc = publish(s.rd(), request(EnumSet.of(ArtifactBelongsTo.RELEASE, ArtifactBelongsTo.SCE)));

		assertEquals(1, merges.size(), merges.toString());
		assertEquals(List.of(s.sceBom(), s.releaseBom()), merges.get(0).ids(),
				"the SCE and release-level SBOMs, not the deliverable one");
		assertEquals("urn:uuid:" + merges.get(0).mergedId(), serialOf(doc));
		verify(releaseRebomService, never()).getReleaseBoms(any());
		verify(releaseRebomService, never()).setReboms(any(), any(), any());
		assertTrue(setReboms.isEmpty(), setReboms.toString());

		// TEA-14 row 11: in one MergeContext the single-source merge of the same release is another MergeKey
		ReleaseService.MergeContext ctx = new ReleaseService.MergeContext();
		svc.matchOrGenerateSingleBomForRelease(s.rd(), FLAT_ANY, true, null, WU, null,
				EnumSet.of(ArtifactBelongsTo.RELEASE, ArtifactBelongsTo.SCE), ctx);
		svc.matchOrGenerateSingleBomForRelease(s.rd(), FLAT_ANY, true, null, WU, null, null, ctx);
		assertEquals(3, merges.size(), "two merges in the one context, not one: " + merges);
		assertEquals(List.of(s.deliverableBom(), s.sceBom(), s.releaseBom()), merges.get(2).ids());
	}

	/** Not forced, so only the set filter keeps the matching cached row from being served. */
	@Test
	void aTwoSourceSetIgnoresAMatchingCachedRowEvenUnforced() throws Exception {
		ThreeSources s = componentWithThreeSources();
		UUID cached = UUID.randomUUID();
		caches.put(s.rd().getUuid(), List.of(new ReleaseBom(cached, stored(s.rd(), FLAT_ANY))));

		Optional<UUID> served = svc.matchOrGenerateSingleBomForRelease(s.rd(), FLAT_ANY, false, null, WU, null,
				EnumSet.of(ArtifactBelongsTo.RELEASE, ArtifactBelongsTo.SCE));

		assertEquals(1, merges.size(), merges.toString());
		assertEquals(Optional.of(merges.get(0).mergedId()), served);
		assertFalse(served.get().equals(cached));
		assertTrue(setReboms.isEmpty(), setReboms.toString());

		// The same call without the set filter is today's behaviour: the cached row is served.
		assertEquals(Optional.of(cached),
				svc.matchOrGenerateSingleBomForRelease(s.rd(), FLAT_ANY, false, null, WU, null, null));
		assertEquals(1, merges.size(), merges.toString());
	}

	@Test
	void aTwoSourceSetOnAProductReachesTheChildrenUncached() throws Exception {
		ReleaseData product = release(component("device", ComponentType.PRODUCT), "1.0");
		ReleaseData child = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		UUID childBom = releaseSbom(child);
		caches.put(child.getUuid(), List.of(new ReleaseBom(UUID.randomUUID(), FLAT_ANY)));
		children(product, child);

		publish(product, request(EnumSet.of(ArtifactBelongsTo.RELEASE, ArtifactBelongsTo.SCE)));

		assertEquals(2, merges.size(), "the child's cache cannot name the set filter, so it merges: " + merges);
		assertEquals(List.of(childBom), merges.get(0).ids());
		assertEquals(List.of(merges.get(0).mergedId()), merges.get(1).ids());
		verify(releaseRebomService, never()).getReleaseBoms(any());
		assertTrue(setReboms.isEmpty(), setReboms.toString());
	}

	@Test
	void aSingletonSetBehavesAsTodaysBelongsTo() throws Exception {
		ThreeSources s = componentWithThreeSources();
		RebomOptions deliverableOnly = new RebomOptions(ArtifactBelongsTo.DELIVERABLE, false, false, BomStructureType.FLAT);

		publish(s.rd(), request(EnumSet.of(ArtifactBelongsTo.DELIVERABLE)));

		assertEquals(1, merges.size(), merges.toString());
		assertEquals(List.of(s.deliverableBom()), merges.get(0).ids());
		assertEquals(ArtifactBelongsTo.DELIVERABLE, merges.get(0).options().belongsTo());
		ReleaseBom omitRow = new ReleaseBom(merges.get(0).mergedId(), stored(s.rd(), deliverableOnly).withMissingSbomMode(OMIT));
		assertEquals(List.of(omitRow), setRebomsFor(s.rd()).get(0).reboms(), "cached under the belongsTo DELIVERABLE key");

		// The export with belongsTo DELIVERABLE is a placeholder request, which the publication's OMIT row does not
		// serve (TEA-15): it merges once more and stores its own row beside the OMIT row.
		UUID exported = svc.getReleaseBomId(s.rd().getUuid(), false, false, false,
				ArtifactBelongsTo.DELIVERABLE, BomStructureType.FLAT, WU, null);
		assertEquals(2, merges.size(), "the publication's row serves no export: the export merges once more");
		assertEquals(merges.get(1).mergedId(), exported);
		assertEquals(List.of(s.deliverableBom()), merges.get(1).ids());
		assertEquals(ArtifactBelongsTo.DELIVERABLE, merges.get(1).options().belongsTo());
		assertNull(merges.get(1).options().missingSbomMode());
		assertEquals(List.of(omitRow, new ReleaseBom(merges.get(1).mergedId(), stored(s.rd(), deliverableOnly))),
				setRebomsFor(s.rd()).get(1).reboms());
	}

	@Test
	void theEmptySetIsNoFilter() throws Exception {
		ThreeSources s = componentWithThreeSources();

		publish(s.rd(), request(Set.of()));

		assertEquals(1, merges.size(), merges.toString());
		assertEquals(List.of(s.deliverableBom(), s.sceBom(), s.releaseBom()), merges.get(0).ids());
		assertEquals(null, merges.get(0).options().belongsTo());
		assertEquals(List.of(new ReleaseBom(merges.get(0).mergedId(), stored(s.rd(), FLAT_ANY).withMissingSbomMode(OMIT))),
				setRebomsFor(s.rd()).get(0).reboms());
	}

	// ---- 26 ----

	@Test
	void theOptionsOverloadOfCollectOwnBomIdsDelegatesWithTheDerivedSet() {
		ThreeSources s = componentWithThreeSources();
		ReleaseService spied = spy(svc);
		List<UUID> all = List.of(s.deliverableBom(), s.sceBom(), s.releaseBom());

		assertEquals(EnumSet.of(ArtifactBelongsTo.DELIVERABLE, ArtifactBelongsTo.SCE, ArtifactBelongsTo.RELEASE),
				ReleaseService.sourcesOf(null), "null is all three sources");
		assertEquals(all, spied.collectOwnBomIds(s.rd(), ComponentType.COMPONENT, FLAT_ANY, null));
		verify(spied).collectOwnBomIds(eq(s.rd()), eq(ComponentType.COMPONENT),
				eq(EnumSet.of(ArtifactBelongsTo.DELIVERABLE, ArtifactBelongsTo.SCE, ArtifactBelongsTo.RELEASE)),
				isNull());

		Map<ArtifactBelongsTo, List<UUID>> expected = Map.of(
				ArtifactBelongsTo.DELIVERABLE, List.of(s.deliverableBom()),
				ArtifactBelongsTo.SCE, List.of(s.sceBom()),
				ArtifactBelongsTo.RELEASE, List.of(s.releaseBom()));
		for (var e : expected.entrySet()) {
			RebomOptions options = new RebomOptions(e.getKey(), false, false, BomStructureType.FLAT);
			assertEquals(EnumSet.of(e.getKey()), ReleaseService.sourcesOf(e.getKey()));
			assertEquals(e.getValue(), spied.collectOwnBomIds(s.rd(), ComponentType.COMPONENT, options, null),
					"belongsTo " + e.getKey());
			assertEquals(e.getValue(), svc.collectOwnBomIds(s.rd(), ComponentType.COMPONENT, EnumSet.of(e.getKey()), null),
					"the set overload with the singleton " + e.getKey());
			verify(spied).collectOwnBomIds(eq(s.rd()), eq(ComponentType.COMPONENT), eq(EnumSet.of(e.getKey())), isNull());
		}
		assertEquals(List.of(s.sceBom(), s.releaseBom()), svc.collectOwnBomIds(s.rd(), ComponentType.COMPONENT,
				EnumSet.of(ArtifactBelongsTo.RELEASE, ArtifactBelongsTo.SCE), null),
				"a two-element set, in source order");
		assertTrue(merges.isEmpty(), "collecting never merges: " + merges);
	}

	// ---- 27 ----

	@Test
	void includeIncludeInjectsAndKeepsTheInternalMarkersAndStillSweepsTheForgery() {
		enableInjection();

		String served = svc.applyExportMetadata(mergedDocument(), ORG, UUID.randomUUID(), INCLUDE_BOTH).toString();

		assertTrue(served.contains(LevelOfSupport.NO_LONGER_MAINTAINED.getWireValue()),
				"the genuine attested level was not injected: " + served);
		assertTrue(served.contains("upstream announced end of maintenance"), served);
		assertTrue(served.contains("reliza:containerSafeVersion"), "internal property stripped under INCLUDE: " + served);
		assertTrue(served.contains("\"name\":\"rearm\""), "internal tool entry stripped under INCLUDE: " + served);
		assertTrue(served.contains("reliza:support:disclosure"), "an injected document is marked: " + served);
		assertForgedSwept(served);
	}

	@Test
	void excludeExcludeStripsBothAndStillSweepsTheForgery() {
		enableInjection();

		String served = svc.applyExportMetadata(mergedDocument(), ORG, UUID.randomUUID(), EXCLUDE_BOTH).toString();

		assertFalse(served.contains(LevelOfSupport.NO_LONGER_MAINTAINED.getWireValue()),
				"support facts injected although declined: " + served);
		assertFalse(served.contains("upstream announced end of maintenance"), served);
		assertFalse(carriesInternalMarkers(served), "internal markers survived EXCLUDE: " + served);
		assertTrue(served.contains("\"name\":\"rearm\""), "ReARM's tool entry stays in every export: " + served);
		assertFalse(served.contains("reliza:support:disclosure"),
				"a declined disclosure is not marked: " + served);
		assertForgedSwept(served);
	}

	/** The catch extracted with the seam: a failed injector still sweeps, unmarked, and honours EXCLUDE. */
	@Test
	void aFailingInjectorStillSweepsSilentlyAndHonoursTheInternalChoice() {
		ReflectionTestUtils.setField(svc, "supportInjectionService", new SupportInjectionService(null, null, null, null) {
			@Override
			public JsonNode injectIfEnabledElseStrip(JsonNode bom, UUID orgUuid, ExportMetadataOptions options) {
				throw new IllegalStateException("injector outage");
			}
		});
		ExportMetadataOptions includeSupportExcludeInternal =
				new ExportMetadataOptions(ExportMetadataChoice.INCLUDE, ExportMetadataChoice.EXCLUDE);

		String served = svc.applyExportMetadata(mergedDocument(), ORG, UUID.randomUUID(),
				includeSupportExcludeInternal).toString();

		assertForgedSwept(served);
		assertFalse(served.contains("reliza:support:disclosure"), "a failed seam serves the document unmarked: " + served);
		assertFalse(carriesInternalMarkers(served), "the internal choice was lost on the failure path: " + served);
	}

	@Test
	void theJsonExportReachesTheSameSeam() throws Exception {
		List<ExportMetadataOptions> seen = new ArrayList<>();
		List<UUID> seenRelease = new ArrayList<>();
		UUID bomId = UUID.randomUUID();
		ReleaseService recording = new ReleaseService(null) {
			@Override
			UUID getReleaseBomId(UUID releaseUuid, Boolean tldOnly, Boolean ignoreDev, boolean excludeFileComponents, ArtifactBelongsTo belongsTo,
					BomStructureType structure, WhoUpdated wu, List<CommonVariables.ArtifactCoverageType> exclude) {
				return bomId;
			}

			@Override
			JsonNode applyExportMetadata(JsonNode mergedBom, UUID org, UUID releaseUuid, ExportMetadataOptions options) {
				seen.add(options);
				seenRelease.add(releaseUuid);
				return super.applyExportMetadata(mergedBom, org, releaseUuid, options);
			}
		};
		ReflectionTestUtils.setField(recording, "rebomService", ReflectionTestUtils.getField(svc, "rebomService"));
		ReflectionTestUtils.setField(recording, "supportInjectionService", new SupportInjectionService(null, null, null, null));
		ReflectionTestUtils.setField(recording, "sbomExportSigningService", unsignedExports());
		UUID release = UUID.randomUUID();

		String served = recording.exportReleaseSbom(release, false, false, false, null, BomStructureType.FLAT,
				BomMediaType.JSON, ORG, WU, null, EXCLUDE_BOTH);

		assertEquals(1, seen.size());
		assertSame(EXCLUDE_BOTH, seen.get(0));
		assertEquals(release, seenRelease.get(0));
		assertForgedSwept(served);
		assertFalse(carriesInternalMarkers(served), served);
	}

	/**
	 * Byte for byte: the published document of a component release is what the export with the same knobs
	 * serves, apart from what each egress stamps (TEA-15: two merges of the same inputs, one per mode).
	 */
	@Test
	void thePublishedDocumentIsTheExportWithTheSameKnobs() throws Exception {
		ReleaseData rd = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		releaseSbom(rd);
		enableInjection();

		String published = publish(rd, request(Set.of(), INCLUDE_BOTH)).toString();
		String exported = svc.exportReleaseSbom(rd.getUuid(), false, false, false, null, BomStructureType.FLAT,
				BomMediaType.JSON, ORG, WU, null, INCLUDE_BOTH);

		assertEquals(2, merges.size(), "one merge per mode: the export is a placeholder request and does not serve"
				+ " the publication's OMIT row: " + merges);
		assertEquals(List.of(merges.get(0).mergedId(), merges.get(1).mergedId()), documentReads);
		// TEA-14: the export now ends with main's signing step (SCORE-33), which stamps its signing record (and a
		// signature when the organization has a key); the publication does not go through it, so the documents
		// are equal apart from that record
		tools.jackson.databind.node.ObjectNode publishedDoc = (tools.jackson.databind.node.ObjectNode) Utils.OM.readTree(published);
		tools.jackson.databind.node.ObjectNode exportedDoc = (tools.jackson.databind.node.ObjectNode) Utils.OM.readTree(exported);
		assertEquals("urn:uuid:" + merges.get(0).mergedId(), serialOf(publishedDoc));
		assertEquals("urn:uuid:" + merges.get(1).mergedId(), serialOf(exportedDoc));
		publishedDoc.remove("serialNumber");
		exportedDoc.remove("serialNumber");
		SupportBomInjector.stripPropertiesNamedEverywhere(exportedDoc, SupportBomInjector.SIGNING_RECORD_PROPERTIES);
		exportedDoc.remove("signature");
		assertEquals(publishedDoc, exportedDoc,
				"equal apart from the signing record and the serial number, which the rig makes the merge id");
		assertTrue(published.contains(LevelOfSupport.NO_LONGER_MAINTAINED.getWireValue()), published);
	}

	@Test
	void anUnrequestableSupportChoiceIsRefusedBeforeAnyMerge() {
		ReleaseData rd = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		releaseSbom(rd);
		OrganizationData.Settings settings = new OrganizationData.Settings();
		settings.setSupportInjection(SupportInjectionSetting.DISABLED);
		when(od.getSettingsWithDefaults()).thenReturn(settings);
		ReflectionTestUtils.setField(svc, "supportInjectionService",
				new SupportInjectionService(null, null, getOrganizationService, null));

		RelizaException e = assertThrows(RelizaException.class, () -> publish(rd, request(Set.of(), INCLUDE_BOTH)));

		assertFalse(e instanceof MergedSbomUnavailableException, e.toString());
		assertTrue(merges.isEmpty(), merges.toString());
	}

	// ---- 28 ----

	@Test
	void anEmptyMergeIsRefusedWithTheExportsNoSbomMessage() {
		ComponentData cc = component("firmware", ComponentType.COMPONENT);
		ReleaseData rd = release(cc, "1.0.0");

		MergedSbomUnavailableException published = assertThrows(MergedSbomUnavailableException.class,
				() -> publish(rd, request(Set.of())));
		MergedSbomUnavailableException exported = assertThrows(MergedSbomUnavailableException.class,
				() -> svc.getReleaseBomId(rd.getUuid(), false, false, false, null, BomStructureType.FLAT, WU, null));

		assertEquals(Reason.NO_SBOM_ARTIFACTS, published.reason());
		assertEquals(rd.getUuid(), published.release());
		assertEquals("No SBOMs found: release firmware 1.0.0 (" + rd.getUuid()
				+ ") carries no SBOM artifact under belongsTo=ANY, excluding coverage none", published.getMessage());
		assertEquals(exported.getMessage(), published.getMessage());
		assertTrue(merges.isEmpty(), merges.toString());
		assertTrue(documentReads.isEmpty(), "nothing merged, so nothing read: " + documentReads);
		assertTrue(setReboms.isEmpty(), setReboms.toString());
	}

	@Test
	void anEmptyProductMergeNamesItsChildrenAsTheExportDoes() {
		ComponentData pc = component("device", ComponentType.PRODUCT);
		ReleaseData product = release(pc, "1.0");
		ReleaseData child = release(component("firmware", ComponentType.COMPONENT), "1.0.0");
		children(product, child);

		MergedSbomUnavailableException published = assertThrows(MergedSbomUnavailableException.class,
				() -> publish(product, request(Set.of())));
		MergedSbomUnavailableException exported = assertThrows(MergedSbomUnavailableException.class,
				() -> svc.getReleaseBomId(product.getUuid(), false, false, false, null, BomStructureType.FLAT, WU, null));

		assertEquals(Reason.NO_SBOM_ARTIFACTS, published.reason());
		assertEquals(exported.getMessage(), published.getMessage());
		assertEquals(List.of(child.getUuid()), published.children().stream().map(c -> c.release()).toList());
	}
}
