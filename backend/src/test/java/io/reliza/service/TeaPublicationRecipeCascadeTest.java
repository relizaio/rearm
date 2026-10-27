/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.SbomProfileData.SbomInternalMetadata;
import io.reliza.model.ScopedResolutionStatus;
import io.reliza.model.ScopedSettingsSource;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.model.TeaPublishedCollectionData.TeaPublishedEntry;
import io.reliza.service.ReleaseService.PublicationMergeRequest;
import io.reliza.service.SbomProfileResolver.SbomProfileResolution;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolutionStatus;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaChildPublishResult;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/**
 * Task TEA-28, design 4.4, the cascade cases (a) to (i): the product's names and recipes shape every
 * child it carries, in the product's order; per name the child's view of the name caps internal
 * metadata when it resolves at the child, else the child's own TEA settings' names do (any EXCLUDE);
 * only the child's own names can refuse it, exactly as its direct publish would (F-1, F-4). The
 * product's {@code top} is top-level only and its {@code default} full, so a merge request tells the
 * two names apart.
 */
class TeaPublicationRecipeCascadeTest extends TeaPublicationServiceTestBase {

	private final TeaPerspectiveRef alpha = new TeaPerspectiveRef(UUID.randomUUID(), "Alpha", PerspectiveType.PERSPECTIVE);
	private final TeaPerspectiveRef beta = new TeaPerspectiveRef(UUID.randomUUID(), "Beta", PerspectiveType.PERSPECTIVE);

	private SbomProfileResolution conflict() {
		return new SbomProfileResolution(ScopedResolutionStatus.CONFLICT, ScopedSettingsSource.PERSPECTIVE, null, null,
				List.of(alpha, beta));
	}

	private static SbomProfileResolution notFound() {
		return new SbomProfileResolution(ScopedResolutionStatus.NOT_FOUND, null, null, null, List.of());
	}

	private static TeaResolvedProfile fromComponent(ComponentData cd, TeaProfileData p) {
		return new TeaResolvedProfile(TeaResolutionStatus.RESOLVED, TeaProfileSource.COMPONENT, cd.getUuid(), p,
				List.of());
	}

	private TeaChildPublishResult childOf(TeaPublishResult r, UUID child) {
		return r.children().stream().filter(c -> child.equals(c.release())).findFirst().orElseThrow();
	}

	/** Every merge request generated for {@code releaseUuid}, in order. */
	private List<PublicationMergeRequest> mergesOf(UUID releaseUuid) {
		return mockingDetails(releaseService).getInvocations().stream()
				.filter(i -> "generateMergedBomForPublication".equals(i.getMethod().getName()))
				.filter(i -> releaseUuid.equals(((ReleaseData) i.getArgument(0)).getUuid()))
				.map(i -> (PublicationMergeRequest) i.getArgument(1)).toList();
	}

	/** The merge of the product's {@code top} (top-level only) for a release. */
	private PublicationMergeRequest topOf(UUID releaseUuid) {
		List<PublicationMergeRequest> tld = mergesOf(releaseUuid).stream().filter(PublicationMergeRequest::tldOnly).toList();
		assertEquals(1, tld.size(), "one top merge for " + releaseUuid);
		return tld.get(0);
	}

	/** The merge of the product's {@code default} (full depth) for a release. */
	private PublicationMergeRequest defaultOf(UUID releaseUuid) {
		List<PublicationMergeRequest> full = mergesOf(releaseUuid).stream().filter(m -> !m.tldOnly()).toList();
		assertEquals(1, full.size(), "one default merge for " + releaseUuid);
		return full.get(0);
	}

	private static boolean excluded(PublicationMergeRequest m) {
		return m.exportMetadata().internalMetadataDeclined();
	}

	/** The synthetic entry names of a release's latest collection, in order. */
	private List<String> aggregateNames(UUID releaseUuid) {
		UUID pub = service.getPublicationOfRelease(releaseUuid).orElseThrow().getUuid();
		return service.getLatestCollection(pub).orElseThrow().getEntries().stream()
				.filter(e -> TeaArtifactOrigin.SYNTHETIC == e.origin()).map(TeaPublishedEntry::name).toList();
	}

	/**
	 * A product over {@code children} whose TEA settings name {@code names}; its {@code default} is full
	 * depth with internal metadata INCLUDE, every other name top-level only with INCLUDE.
	 */
	private ReleaseData product(List<UUID> children, String... names) {
		ReleaseData p = productOver("platform", resolvedFromOrg(profiles(profile(TeaVisibility.PRIVATE), names)),
				children);
		recipe(p.getComponent()).setInternalMetadata(SbomInternalMetadata.INCLUDE);
		for (String n : names) {
			if (!"default".equals(n)) {
				recipe(p.getComponent(), n, SbomInternalMetadata.INCLUDE).setDependencyDepth(SbomDependencyDepth.TOP_LEVEL_ONLY);
			}
		}
		return p;
	}

	/** A second COMPONENT release, ASSEMBLED, whose TEA settings resolve to {@code own}. */
	private ReleaseData child(String name, TeaProfileData own) {
		ComponentData cd = component(name, ComponentType.COMPONENT);
		resolutions.put(cd.getUuid(), fromComponent(cd, own));
		ReleaseData rd = release(cd, ReleaseLifecycle.ASSEMBLED, List.of(artifact(ArtifactType.BOM, name + ".cdx.json",
				"1", StoredIn.REARM, true, "bb22")));
		documents.put(rd.getUuid(), bom("pkg:generic/" + name + "@1"));
		return rd;
	}

	// ---------------------------------------------------------------- (a)

	@Test
	void aChildsOwnTopNarrowsTheProductsTopAndLeavesItsDefaultAlone() throws Exception {
		recipe(component.getUuid()).setInternalMetadata(SbomInternalMetadata.INCLUDE);
		recipe(component.getUuid(), "top", SbomInternalMetadata.EXCLUDE).setDependencyDepth(SbomDependencyDepth.FULL);
		ReleaseData product = product(List.of(release.getUuid()), "default", "top");

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, release.getUuid()).outcome());
		assertEquals(List.of("default", "top"), aggregateNames(release.getUuid()));
		PublicationMergeRequest top = topOf(release.getUuid());
		assertTrue(top.tldOnly(), "the product's top shape wins over the child's FULL");
		assertTrue(excluded(top), "the child's own top narrows internal metadata");
		assertFalse(excluded(defaultOf(release.getUuid())), "the product's default, unnarrowed");
		assertFalse(excluded(topOf(product.getUuid())), "the product's own top keeps INCLUDE");
	}

	// ---------------------------------------------------------------- (b)

	@Test
	void aChildsOwnListNeverAddsANameItOnlyCaps() throws Exception {
		TeaProfileData own = profiles(profile(TeaVisibility.PRIVATE), "other");
		resolutions.put(component.getUuid(), fromComponent(component, own));
		recipe(component.getUuid(), "other", SbomInternalMetadata.INCLUDE);
		ReleaseData product = product(List.of(release.getUuid()), "default", "top");

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, release.getUuid()).outcome());
		assertEquals(List.of("default", "top"), aggregateNames(release.getUuid()), "the product's names only");
		assertEquals(2, mergesOf(release.getUuid()).size(), "nothing generated for other");
		verify(sbomResolver, times(1)).resolveForComponent(eq(component), eq("other"), isNull(), any());
		// top does not resolve at the child: the child's own cap (other INCLUDE) applies, so the product's INCLUDE stands
		assertFalse(excluded(topOf(release.getUuid())));
	}

	// ---------------------------------------------------------------- (c)

	@Test
	void aProductLocalNameShapesEveryChildCappedByEachChildsOwnNames() throws Exception {
		recipe(component.getUuid()).setInternalMetadata(SbomInternalMetadata.EXCLUDE);
		ReleaseData valve = child("valve", profile(TeaVisibility.PRIVATE));
		recipe(valve.getComponent()).setInternalMetadata(SbomInternalMetadata.INCLUDE);
		ReleaseData product = product(List.of(release.getUuid(), valve.getUuid()), "default", "local");

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		for (ReleaseData c : List.of(release, valve)) {
			assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, c.getUuid()).outcome());
			assertEquals(List.of("default", "local"), aggregateNames(c.getUuid()));
			assertTrue(topOf(c.getUuid()).tldOnly(), "local is the product's recipe");
		}
		assertTrue(excluded(topOf(release.getUuid())), "pump's own default EXCLUDE caps local");
		assertFalse(excluded(topOf(valve.getUuid())), "valve's own INCLUDE leaves the product's value");
		assertTrue(r.children().stream().noneMatch(c -> TeaChildOutcome.REFUSED == c.outcome()));
	}

	// ---------------------------------------------------------------- (d)

	@Test
	void aProductNameInConflictAtTheChildIsCappedByTheChildsOwnNames() throws Exception {
		recipe(component.getUuid()).setInternalMetadata(SbomInternalMetadata.EXCLUDE);
		recipeResolutions.put(key(component.getUuid(), "top"), conflict());
		ReleaseData product = product(List.of(release.getUuid()), "default", "top");

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, release.getUuid()).outcome(),
				"the child's own list is [default]: the conflict on top refuses nothing");
		assertEquals(List.of("default", "top"), aggregateNames(release.getUuid()));
		assertTrue(excluded(topOf(release.getUuid())), "capped by the child's own default EXCLUDE");
	}

	// ---------------------------------------------------------------- (e)

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void aChildsOwnNameThatDoesNotResolveRefusesThatChildOnly(boolean productNameResolvesAtChild) throws Exception {
		TeaProfileData own = profiles(profile(TeaVisibility.PRIVATE), "default", "gone");
		resolutions.put(component.getUuid(), fromComponent(component, own));
		if (productNameResolvesAtChild) recipe(component.getUuid(), "top", SbomInternalMetadata.INCLUDE);
		ReleaseData valve = child("valve", profile(TeaVisibility.PRIVATE));
		ReleaseData product = product(List.of(release.getUuid(), valve.getUuid()), "default", "top");

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		TeaChildPublishResult c = childOf(r, release.getUuid());
		assertEquals(TeaChildOutcome.REFUSED, c.outcome());
		assertEquals(TeaPublishRefusal.PROFILE_NOT_FOUND, c.refusal());
		assertTrue(c.detail().contains("SBOM profile gone, named by the component " + component.getUuid()
				+ " TEA publishing settings, does not resolve for component pump"), c.detail());
		assertTrue(mergesOf(release.getUuid()).isEmpty(), "nothing of the refused child is generated");
		assertTrue(service.getPublicationOfRelease(release.getUuid()).isEmpty());
		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, valve.getUuid()).outcome(), "the other child stands");
		verify(synthetic, never()).upload(eq(release.getUuid()), any(), any(), any(), any());
	}

	// ---------------------------------------------------------------- (f)

	@Test
	void underOneSharedRowTheChildGetsTheProductsResultPerName() throws Exception {
		TeaProfileData shared = profiles(profile(TeaVisibility.PRIVATE), "default", "top");
		resolutions.put(component.getUuid(), resolvedFromOrg(shared));
		ReleaseData product = productOver("platform", resolvedFromOrg(shared), List.of(release.getUuid()));
		// the organization's default INCLUDE and top EXCLUDE, resolving alike at the product and the child
		for (UUID c : List.of(product.getComponent(), component.getUuid())) {
			recipe(c).setInternalMetadata(SbomInternalMetadata.INCLUDE);
			recipe(c, "top", SbomInternalMetadata.EXCLUDE).setDependencyDepth(SbomDependencyDepth.TOP_LEVEL_ONLY);
		}

		publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertFalse(excluded(defaultOf(release.getUuid())));
		assertTrue(excluded(topOf(release.getUuid())));
		assertEquals(excluded(defaultOf(product.getUuid())), excluded(defaultOf(release.getUuid())));
		assertEquals(excluded(topOf(product.getUuid())), excluded(topOf(release.getUuid())));
	}

	// ---------------------------------------------------------------- (g)

	@Test
	void aNestedProductsChildrenAreShapedByTheTopProductsList() throws Exception {
		ReleaseData sub = productOver("module", resolvedFromOrg(profiles(profile(TeaVisibility.PRIVATE), "default")),
				List.of(release.getUuid()));
		ReleaseData top = product(List.of(sub.getUuid()), "default", "top");

		TeaPublishResult r = publishRelease(top.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, sub.getUuid()).outcome());
		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, release.getUuid()).outcome());
		assertEquals(List.of("default", "top"), aggregateNames(sub.getUuid()), "the nested product: the top product's list");
		assertEquals(List.of("default", "top"), aggregateNames(release.getUuid()), "and its children too");
	}

	/**
	 * (g), the recipes: a nested product passes on the top product's recipes, never its own capped ones.
	 * The nested product's own names say EXCLUDE, so its own aggregates exclude internal metadata; the
	 * top product's recipes and the grandchild's own names say INCLUDE, so the grandchild's keep it.
	 */
	@Test
	void aNestedProductPassesTheTopProductsRecipesNotItsOwnCappedOnes() throws Exception {
		ReleaseData sub = productOver("module", resolvedFromOrg(profiles(profile(TeaVisibility.PRIVATE), "default")),
				List.of(release.getUuid()));
		recipe(sub.getComponent()).setInternalMetadata(SbomInternalMetadata.EXCLUDE);
		recipe(sub.getComponent(), "top", SbomInternalMetadata.EXCLUDE);
		recipe(component.getUuid()).setInternalMetadata(SbomInternalMetadata.INCLUDE);
		recipe(component.getUuid(), "top", SbomInternalMetadata.INCLUDE);
		ReleaseData top = product(List.of(sub.getUuid()), "default", "top");

		TeaPublishResult r = publishRelease(top.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, sub.getUuid()).outcome());
		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, release.getUuid()).outcome());
		assertTrue(excluded(defaultOf(sub.getUuid())) && excluded(topOf(sub.getUuid())),
				"the nested product's own names cap its own aggregates");
		assertFalse(excluded(defaultOf(release.getUuid())), "the grandchild's default keeps internal metadata");
		assertFalse(excluded(topOf(release.getUuid())), "the grandchild's top keeps internal metadata");
	}

	// ---------------------------------------------------------------- (h)

	@Test
	void aChildsOwnDefaultExcludeDoesNotCapAProductNameResolvingAtTheChildWithInclude() throws Exception {
		recipe(component.getUuid()).setInternalMetadata(SbomInternalMetadata.EXCLUDE);
		// top resolves at the child (from the organization in the real walk) with INCLUDE
		recipe(component.getUuid(), "top", SbomInternalMetadata.INCLUDE);
		ReleaseData product = product(List.of(release.getUuid()), "default", "top");

		publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertFalse(excluded(topOf(release.getUuid())), "the stated trade-off: top keeps INCLUDE");
		assertTrue(excluded(defaultOf(release.getUuid())), "the child's own default narrows default");
	}

	// ---------------------------------------------------------------- (i), F-4

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void aChildsOwnNameInConflictRefusesTheChildWhateverTheProductNames(boolean productNamesTop) throws Exception {
		TeaProfileData own = profiles(profile(TeaVisibility.PRIVATE), "default", "top");
		resolutions.put(component.getUuid(), fromComponent(component, own));
		recipeResolutions.put(key(component.getUuid(), "top"), conflict());
		ReleaseData valve = child("valve", profile(TeaVisibility.PRIVATE));
		ReleaseData product = productNamesTop ? product(List.of(release.getUuid(), valve.getUuid()), "default", "top")
				: product(List.of(release.getUuid(), valve.getUuid()), "default");

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		TeaChildPublishResult c = childOf(r, release.getUuid());
		assertEquals(TeaChildOutcome.REFUSED, c.outcome());
		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, c.refusal());
		assertTrue(c.detail().contains("SBOM profile top resolves to a conflict between perspectives Alpha, Beta for "
				+ "component pump"), c.detail());
		assertTrue(mergesOf(release.getUuid()).isEmpty());
		assertNull(service.getPublicationOfRelease(release.getUuid()).orElse(null));
		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, valve.getUuid()).outcome());
		// the own pass stopped at top: the product's names were never asked at that child
		verify(sbomResolver, times(1)).resolveForComponent(eq(component), eq("default"), isNull(), any());
		verify(sbomResolver, times(1)).resolveForComponent(eq(component), eq("top"), isNull(), any());
	}
}
