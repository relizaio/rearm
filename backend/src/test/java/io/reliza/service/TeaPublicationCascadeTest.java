/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import io.reliza.common.CommonVariables.ArtifactCoverageType;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DigestRecord;
import io.reliza.model.ArtifactData.DigestScope;
import io.reliza.model.ArtifactData.StoredIn;
import io.reliza.model.DigestAlgorithm;
import io.reliza.model.Link;
import io.reliza.model.TeaPublishedCollectionData;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseUpdateAction;
import io.reliza.model.ReleaseData.ReleaseUpdateEvent;
import io.reliza.model.SbomProfileData;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.SbomProfileData.SbomInternalMetadata;
import io.reliza.model.SbomProfileData.SbomOptionalDependencies;
import io.reliza.model.SbomProfileData.SbomSourceKind;
import io.reliza.model.SbomProfileData.SbomSupportMetadata;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaTei;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.TeaPublication;
import io.reliza.model.TeaPublicationData;
import io.reliza.model.TeaPublishedCollection;
import io.reliza.model.TeaPublishedCollectionData.TeaArtifactOrigin;
import io.reliza.service.RebomService.BomStructureType;
import io.reliza.service.ReleaseService.PublicationMergeRequest;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolutionStatus;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.TeaPublicationService.ReleaseContext;
import io.reliza.service.TeaPublicationService.TeaNamedRecipe;
import io.reliza.service.SbomProfileResolver.SbomProfileResolution;
import io.reliza.model.ScopedResolutionStatus;
import io.reliza.model.ScopedSettingsSource;
import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaChildPublishResult;
import io.reliza.service.TeaPublicationService.TeaPublicationState;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;
import io.reliza.service.oss.OssReleaseService.AcollectionMode;

/**
 * TEA-5 round 2, cases 60 to 63: the product cascade holds the organization boundary (T-1), reads
 * each child's own profile as a gate and a cap (T-4), and gives a shared child one owner (T-3).
 */
class TeaPublicationCascadeTest extends TeaPublicationServiceTestBase {

	private static final UUID ORG_B = UUID.randomUUID();

	private static TeaProfileData withProductComponents(TeaProfileData p, TeaProductComponents mode) {
		p.setProductComponents(mode);
		return p;
	}

	private static TeaResolvedProfile fromComponent(ComponentData cd, TeaProfileData p) {
		return new TeaResolvedProfile(TeaResolutionStatus.RESOLVED, TeaProfileSource.COMPONENT, cd.getUuid(), p,
				List.of());
	}

	private TeaChildPublishResult childOf(TeaPublishResult r, UUID release) {
		return r.children().stream().filter(c -> release.equals(c.release())).findFirst().orElseThrow();
	}

	// ---------------------------------------------------------------- 60, the boundary (T-1)

	@ParameterizedTest
	@EnumSource(TeaProductComponents.class)
	void aForeignOrMissingChildIsRefusedAsNotFoundAndNothingIsTouched(TeaProductComponents mode) throws Exception {
		ComponentData foreign = component("secret", ComponentType.COMPONENT);
		foreign.setOrg(ORG_B);
		resolutions.put(foreign.getUuid(), resolvedFromOrg(profile));
		ReleaseData foreignRelease = releaseIn(ORG_B, foreign, ReleaseLifecycle.ASSEMBLED, List.of(inventoryBom));
		documents.put(foreignRelease.getUuid(), bom("pkg:generic/secret@1"));
		ComponentData borrowed = component("borrowed", ComponentType.COMPONENT);
		borrowed.setOrg(ORG_B);
		resolutions.put(borrowed.getUuid(), resolvedFromOrg(profile));
		ReleaseData foreignComponent = release(borrowed, ReleaseLifecycle.ASSEMBLED, List.of(inventoryBom));
		documents.put(foreignComponent.getUuid(), bom("pkg:generic/borrowed@1"));
		UUID missing = UUID.randomUUID();
		ReleaseData product = productOver("platform",
				resolvedFromOrg(withProductComponents(profile(TeaVisibility.PRIVATE), mode)),
				List.of(release.getUuid(), foreignRelease.getUuid(), foreignComponent.getUuid(), missing));

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		Set<UUID> outside = Set.of(foreignRelease.getUuid(), foreignComponent.getUuid(), missing);
		for (UUID child : outside) {
			TeaChildPublishResult c = childOf(r, child);
			assertEquals(TeaChildOutcome.REFUSED, c.outcome());
			assertEquals(TeaPublishRefusal.RELEASE_NOT_FOUND, c.refusal());
			assertEquals("not found", c.detail());
			assertNull(c.componentName());
			assertNull(c.version());
			assertNull(c.publication());
		}
		if (TeaProductComponents.PUBLISH_WITH_PRODUCT == mode) {
			assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, release.getUuid()).outcome());
			assertEquals(4, r.children().size());
		} else {
			assertEquals(3, r.children().size(), "PRODUCT_ONLY reports only the refused children");
			verify(componentService).ensureTeaUuid(component.getUuid(), WU);
		}
		verify(componentService, never()).ensureTeaUuid(eq(foreign.getUuid()), any());
		verify(componentService, never()).ensureTeaUuid(eq(borrowed.getUuid()), any());
		for (UUID child : outside) {
			verify(synthetic, never()).upload(eq(child), any(), any(), any(), any());
			verify(releaseService, never()).generateMergedBomForPublication(argThat(rd -> child.equals(rd.getUuid())),
					any(), any());
			assertTrue(heads.values().stream().noneMatch(h -> child.equals(h.getRelease())));
			assertTrue(collectionRows.values().stream().noneMatch(c -> child.equals(c.getRelease())));
		}
		for (ReleaseData foreignRow : List.of(foreignRelease, foreignComponent)) {
			assertTrue(ReleaseData.dataFromRecord(releaseRows.get(foreignRow.getUuid())).getUpdateEvents().isEmpty());
			assertTrue(ReleaseData.dataFromRecord(releaseRows.get(foreignRow.getUuid())).getSyntheticArtifacts().isEmpty());
		}
		verify(oss, never()).saveRelease(same(releaseRows.get(foreignRelease.getUuid())), any(ReleaseData.class), any(),
				anyBoolean(), any(AcollectionMode.class));
		verify(oss, never()).saveRelease(same(releaseRows.get(foreignComponent.getUuid())), any(ReleaseData.class), any(),
				anyBoolean(), any(AcollectionMode.class));
		verify(resolver, never()).resolveForComponent(foreign);
		verify(resolver, never()).resolveForComponent(borrowed);
	}

	// ---------------------------------------------------------------- 61, the gate (T-4)

	static Stream<Arguments> ownResolutions() {
		TeaProfileData disabled = profile(TeaVisibility.PUBLIC);
		disabled.setPublishing(TeaPublishing.DISABLED);
		UUID perspective = UUID.randomUUID();
		return Stream.of(
				Arguments.of("CONFLICT", new TeaResolvedProfile(TeaResolutionStatus.CONFLICT, TeaProfileSource.PERSPECTIVE,
						null, null, List.of(new TeaPerspectiveRef(UUID.randomUUID(), "Alpha", null),
								new TeaPerspectiveRef(UUID.randomUUID(), "Beta", null))),
						TeaPublishRefusal.PROFILE_CONFLICT, List.of("Alpha", "Beta")),
				Arguments.of("DISABLED by the component", new TeaResolvedProfile(TeaResolutionStatus.RESOLVED,
						TeaProfileSource.COMPONENT, UUID.randomUUID(), disabled, List.of()),
						TeaPublishRefusal.PUBLISHING_DISABLED, List.of("DISABLED", "component")),
				Arguments.of("DISABLED by a perspective", new TeaResolvedProfile(TeaResolutionStatus.RESOLVED,
						TeaProfileSource.PERSPECTIVE, perspective, disabled, List.of()),
						TeaPublishRefusal.PUBLISHING_DISABLED, List.of("DISABLED", "perspective " + perspective)),
				Arguments.of("DISABLED by the organization", new TeaResolvedProfile(TeaResolutionStatus.RESOLVED,
						TeaProfileSource.ORGANIZATION, null, disabled, List.of()),
						TeaPublishRefusal.PUBLISHING_DISABLED, List.of("DISABLED", "organization")),
				Arguments.of("DISABLED by the built-in default", new TeaResolvedProfile(TeaResolutionStatus.RESOLVED,
						TeaProfileSource.DEFAULT, null, TeaProfileData.builtInDefault(ORG), List.of()),
						TeaPublishRefusal.PUBLISHING_DISABLED, List.of("DISABLED", "built-in default")));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("ownResolutions")
	void aChildWhoseOwnProfileForbidsPublishingIsRefused(String label, TeaResolvedProfile own,
			TeaPublishRefusal expected, List<String> named) throws Exception {
		resolutions.put(component.getUuid(), own);
		ReleaseData product = productOver("platform", resolvedFromOrg(profile(TeaVisibility.PUBLIC)),
				List.of(release.getUuid()));

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		TeaChildPublishResult c = childOf(r, release.getUuid());
		assertEquals(TeaChildOutcome.REFUSED, c.outcome());
		assertEquals(expected, c.refusal());
		for (String n : named) assertTrue(c.detail().contains(n), c.detail());
		assertEquals("pump", c.componentName());
		assertTrue(service.getPublicationOfRelease(release.getUuid()).isEmpty(), "the refused child gets no head");
		verify(synthetic, never()).upload(eq(release.getUuid()), any(), any(), any(), any());
		verify(resolver, times(1)).resolveForComponent(component);
		verify(resolver, never()).resolveForComponent(eq(component), any());
	}

	@Test
	void anEnabledPrivateChildIsPublishedUnderThePublicProductRecordingTheProductRow() throws Exception {
		ComponentData productCd = component("platform", ComponentType.PRODUCT);
		TeaProfileData productProfile = profile(TeaVisibility.PUBLIC);
		productProfile.setRevision(7);
		resolutions.put(productCd.getUuid(), fromComponent(productCd, productProfile));
		ReleaseData product = release(productCd, ReleaseLifecycle.ASSEMBLED, List.of());
		setParents(product, List.of(release.getUuid()));
		documents.put(product.getUuid(), bom("pkg:generic/platform@1"));
		resolutions.put(component.getUuid(), fromComponent(component, profile(TeaVisibility.PRIVATE)));

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, release.getUuid()).outcome());
		TeaPublicationData child = head();
		assertEquals(productProfile.getUuid(), child.getProfile());
		assertEquals(7, child.getProfileRevision());
		assertEquals(TeaProfileSource.COMPONENT, child.getProfileSource());
		assertEquals(productCd.getUuid(), child.getProfileSourceObject());
		assertEquals(r.publication().getUuid(), child.getCascadeOf());
		verify(resolver, times(1)).resolveForComponent(productCd);
		verify(resolver, times(1)).resolveForComponent(component);
		verify(resolver, never()).resolveForComponent(eq(component), any());
	}

	// ---------------------------------------------------------------- 62, the knobs (T-4)

	@Test
	void cascadeSettingsTakeTheNarrowerRawArtifactsForEveryPair() {
		for (TeaRawArtifacts p : TeaRawArtifacts.values()) {
			for (TeaRawArtifacts o : TeaRawArtifacts.values()) {
				TeaProfileData product = profile(TeaVisibility.PUBLIC);
				product.setRawArtifacts(p);
				TeaProfileData own = profile(TeaVisibility.PRIVATE);
				own.setRawArtifacts(o);
				TeaRawArtifacts expected = p.ordinal() <= o.ordinal() ? p : o;
				assertEquals(expected, TeaPublicationService.cascadeSettings(product, own).getRawArtifacts(),
						p + " and " + o);
			}
		}
		assertEquals(TeaRawArtifacts.NONE, TeaPublicationService.narrowerRawArtifacts(TeaRawArtifacts.NON_BOM,
				TeaRawArtifacts.NONE));
		assertEquals(TeaRawArtifacts.NON_BOM, TeaPublicationService.narrowerRawArtifacts(TeaRawArtifacts.ALL,
				TeaRawArtifacts.NON_BOM));
	}

	@Test
	void cascadeRecipeExcludesInternalMetadataWhenEitherDoes() {
		// TEA-27 F-3: the shape half of TEA-5's cascadeKnobs, now on the recipe; TEA-28: the child's say is a cap.
		for (SbomInternalMetadata p : SbomInternalMetadata.values()) {
			for (SbomInternalMetadata cap : SbomInternalMetadata.values()) {
				SbomProfileData product = SbomProfileData.builtInDefault(ORG);
				product.setInternalMetadata(p);
				SbomInternalMetadata expected = SbomInternalMetadata.INCLUDE == p && SbomInternalMetadata.INCLUDE == cap
						? SbomInternalMetadata.INCLUDE : SbomInternalMetadata.EXCLUDE;
				assertEquals(expected, TeaPublicationService.cascadeRecipe(product, cap).getInternalMetadata(),
						p + " and " + cap);
			}
		}
	}

	@Test
	void cascadeRecipeKeepsTheProductsShapeWhateverTheChildSays() {
		SbomProfileData product = SbomProfileData.builtInDefault(ORG);
		product.setUuid(UUID.randomUUID());
		product.setRevision(9);
		product.setSources(EnumSet.of(SbomSourceKind.RELEASE));
		product.setDependencyDepth(SbomDependencyDepth.TOP_LEVEL_ONLY);
		product.setOptionalDependencies(SbomOptionalDependencies.EXCLUDE);
		product.setStructure(BomStructureType.HIERARCHICAL);
		product.setExcludedCoverage(EnumSet.of(ArtifactCoverageType.TEST));
		product.setSupportMetadata(SbomSupportMetadata.INCLUDE);

		SbomProfileData k = TeaPublicationService.cascadeRecipe(product, SbomInternalMetadata.INCLUDE);

		assertEquals(product.getUuid(), k.getUuid());
		assertEquals(9, k.getRevision());
		assertEquals(product.getSources(), k.getSources());
		assertEquals(product.getDependencyDepth(), k.getDependencyDepth());
		assertEquals(product.getOptionalDependencies(), k.getOptionalDependencies());
		assertEquals(product.getStructure(), k.getStructure());
		assertEquals(product.getExcludedCoverage(), k.getExcludedCoverage());
		assertEquals(product.getSupportMetadata(), k.getSupportMetadata());
		assertEquals(product.getVulnerabilityDocuments(), k.getVulnerabilityDocuments());
		assertEquals(SbomInternalMetadata.EXCLUDE, product.getInternalMetadata(), "the product recipe is never touched");
	}

	// ---------------------------------------------------------------- task TEA-28, design 4.5: cascadeRecipes

	private ReleaseContext childContext() {
		return new ReleaseContext(ReleaseData.dataFromRecord(releaseRows.get(release.getUuid())), component);
	}

	private static TeaNamedRecipe named(String name, SbomInternalMetadata im) {
		SbomProfileData d = SbomProfileData.builtInDefault(ORG);
		d.setName(name);
		d.setInternalMetadata(im);
		return new TeaNamedRecipe(name, d);
	}

	private static SbomProfileResolution conflictOf(String... perspectives) {
		return new SbomProfileResolution(ScopedResolutionStatus.CONFLICT, ScopedSettingsSource.PERSPECTIVE, null, null,
				java.util.Arrays.stream(perspectives).map(n -> new TeaPerspectiveRef(UUID.randomUUID(), n,
						io.reliza.common.CommonVariables.PerspectiveType.PERSPECTIVE)).toList());
	}

	@Test
	void cascadeRecipesResolvesTheOwnNamesFirstAndThrowsTheirRefusal() {
		TeaProfileData own = profiles(profile(TeaVisibility.PRIVATE), "default", "gone");
		List<TeaNamedRecipe> product = List.of(named("default", SbomInternalMetadata.INCLUDE),
				named("top", SbomInternalMetadata.INCLUDE));
		TeaPublishRefusedException e = assertThrows(TeaPublishRefusedException.class,
				() -> service.cascadeRecipes(product, childContext(), fromComponent(component, own)));
		assertEquals(TeaPublishRefusal.PROFILE_NOT_FOUND, e.reason());
		assertTrue(e.getMessage().contains("SBOM profile gone"), e.getMessage());
		verify(sbomResolver, never()).resolveForComponent(any(), eq("top"), any(), any());
	}

	@Test
	void cascadeRecipesFoldsTheOwnCapAsAnyExclude() throws Exception {
		TeaProfileData own = profiles(profile(TeaVisibility.PRIVATE), "default", "second");
		recipe(component.getUuid()).setInternalMetadata(SbomInternalMetadata.INCLUDE);
		recipe(component.getUuid(), "second", SbomInternalMetadata.EXCLUDE);
		List<TeaNamedRecipe> product = List.of(named("local", SbomInternalMetadata.INCLUDE));
		List<TeaNamedRecipe> out = service.cascadeRecipes(product, childContext(), fromComponent(component, own));
		assertEquals(SbomInternalMetadata.EXCLUDE, out.get(0).recipe().getInternalMetadata(),
				"local does not resolve at the child; one own EXCLUDE caps it, whatever the order");

		profiles(own, "second", "default");
		assertEquals(SbomInternalMetadata.EXCLUDE, service.cascadeRecipes(product, childContext(),
				fromComponent(component, own)).get(0).recipe().getInternalMetadata());

		recipe(component.getUuid(), "second", SbomInternalMetadata.INCLUDE);
		assertEquals(SbomInternalMetadata.INCLUDE, service.cascadeRecipes(product, childContext(),
				fromComponent(component, own)).get(0).recipe().getInternalMetadata(), "every own name INCLUDE");
	}

	@Test
	void cascadeRecipesTakesTheChildsViewWhenItResolvesAndTheOwnCapOtherwiseInTheProductsOrder() throws Exception {
		TeaProfileData own = profiles(profile(TeaVisibility.PRIVATE), "default");
		recipe(component.getUuid()).setInternalMetadata(SbomInternalMetadata.EXCLUDE);
		recipe(component.getUuid(), "viewInclude", SbomInternalMetadata.INCLUDE);
		recipe(component.getUuid(), "viewExclude", SbomInternalMetadata.EXCLUDE);
		recipeResolutions.put(key(component.getUuid(), "conflicting"), conflictOf("Alpha", "Beta"));
		List<TeaNamedRecipe> product = List.of(named("viewInclude", SbomInternalMetadata.INCLUDE),
				named("missing", SbomInternalMetadata.INCLUDE), named("viewExclude", SbomInternalMetadata.INCLUDE),
				named("conflicting", SbomInternalMetadata.INCLUDE), named("productExcludes", SbomInternalMetadata.EXCLUDE));
		recipe(component.getUuid(), "productExcludes", SbomInternalMetadata.INCLUDE);

		List<TeaNamedRecipe> out = service.cascadeRecipes(product, childContext(), fromComponent(component, own));

		assertEquals(List.of("viewInclude", "missing", "viewExclude", "conflicting", "productExcludes"),
				out.stream().map(TeaNamedRecipe::name).toList(), "the product's order");
		assertEquals(List.of(SbomInternalMetadata.INCLUDE, SbomInternalMetadata.EXCLUDE, SbomInternalMetadata.EXCLUDE,
				SbomInternalMetadata.EXCLUDE, SbomInternalMetadata.EXCLUDE),
				out.stream().map(r -> r.recipe().getInternalMetadata()).toList(),
				"view INCLUDE; NOT_FOUND and CONFLICT take the own EXCLUDE; view EXCLUDE; the product's EXCLUDE never widens");
		for (int i = 0; i < product.size(); i++) {
			assertEquals(product.get(i).recipe().getDependencyDepth(), out.get(i).recipe().getDependencyDepth());
		}
	}

	/**
	 * Design 3.4 step 3: the child's view of a product name is asked with the child's own TEA settings'
	 * perspective preferred, which settles a CONFLICT on that perspective's row. Here the name is defined
	 * on P1 (INCLUDE) and P2; without the preference it is a conflict and the own cap (EXCLUDE) applies.
	 */
	@ParameterizedTest
	@EnumSource(value = TeaProfileSource.class, names = { "PERSPECTIVE", "FOLLOWED_PERSPECTIVE" })
	void cascadeRecipesAsksTheChildsViewWithItsOwnSettingsPerspectivePreferred(TeaProfileSource source)
			throws Exception {
		UUID p1 = UUID.randomUUID();
		TeaProfileData ownProfile = profiles(profile(TeaVisibility.PRIVATE), "default");
		recipe(component.getUuid()).setInternalMetadata(SbomInternalMetadata.EXCLUDE);
		recipeResolutions.put(key(component.getUuid(), "shared"), conflictOf("P1", "P2"));
		SbomProfileData p1Row = SbomProfileData.builtInDefault(ORG);
		p1Row.setName("shared");
		p1Row.setInternalMetadata(SbomInternalMetadata.INCLUDE);
		org.mockito.Mockito.doReturn(new SbomProfileResolution(ScopedResolutionStatus.RESOLVED,
				ScopedSettingsSource.PERSPECTIVE, p1, p1Row, List.of())).when(sbomResolver)
				.resolveForComponent(eq(component), eq("shared"), org.mockito.ArgumentMatchers.isNull(), eq(p1));
		List<TeaNamedRecipe> product = List.of(named("shared", SbomInternalMetadata.INCLUDE));

		TeaResolvedProfile own = new TeaResolvedProfile(TeaResolutionStatus.RESOLVED, source, p1, ownProfile, List.of());
		List<TeaNamedRecipe> out = service.cascadeRecipes(product, childContext(), own);

		assertEquals(SbomInternalMetadata.INCLUDE, out.get(0).recipe().getInternalMetadata(),
				"P1's row of the name, the child's own settings' perspective, caps it");
		verify(sbomResolver, times(1)).resolveForComponent(eq(component), eq("shared"),
				org.mockito.ArgumentMatchers.isNull(), eq(p1));

		// the same child with component-level own settings states no preference: the conflict, so the own cap
		assertEquals(SbomInternalMetadata.EXCLUDE, service.cascadeRecipes(product, childContext(),
				fromComponent(component, ownProfile)).get(0).recipe().getInternalMetadata());
	}

	@ParameterizedTest
	@ValueSource(booleans = { true, false })
	void cascadeRecipesThrowsTheOwnConflictWhetherOrNotTheProductNamesIt(boolean productNamesIt) {
		TeaProfileData own = profiles(profile(TeaVisibility.PRIVATE), "default", "top");
		recipeResolutions.put(key(component.getUuid(), "top"), conflictOf("Alpha", "Beta"));
		List<TeaNamedRecipe> product = productNamesIt
				? List.of(named("default", SbomInternalMetadata.INCLUDE), named("top", SbomInternalMetadata.INCLUDE))
				: List.of(named("default", SbomInternalMetadata.INCLUDE));
		TeaPublishRefusedException e = assertThrows(TeaPublishRefusedException.class,
				() -> service.cascadeRecipes(product, childContext(), fromComponent(component, own)));
		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, e.reason());
		assertTrue(e.getMessage().contains("SBOM profile top resolves to a conflict between perspectives Alpha, Beta"),
				e.getMessage());
	}

	@Test
	void cascadeRecipesNeverThrowsOverAProductName() throws Exception {
		TeaProfileData own = profiles(profile(TeaVisibility.PRIVATE), "default");
		recipeResolutions.put(key(component.getUuid(), "conflicting"), conflictOf("Alpha", "Beta"));
		List<TeaNamedRecipe> product = List.of(named("missing", SbomInternalMetadata.INCLUDE),
				named("conflicting", SbomInternalMetadata.INCLUDE));
		assertEquals(2, service.cascadeRecipes(product, childContext(), fromComponent(component, own)).size());
	}

	@Test
	void cascadeSettingsKeepTheProductsChannelFieldsWhateverTheChildSays() {
		TeaProfileData product = profile(TeaVisibility.PUBLIC);
		product.setRevision(9);
		product.setTei(TeaTei.UUID);
		product.setTeiDomain("products.example.com");
		product.setProfiles(List.of("a", "b"));
		TeaProfileData own = profile(TeaVisibility.PRIVATE);
		own.setTei(TeaTei.DISABLED);
		own.setTeiDomain(null);
		own.setProfiles(List.of("c"));

		TeaProfileData k = TeaPublicationService.cascadeSettings(product, own);

		assertEquals(product.getUuid(), k.getUuid());
		assertEquals(9, k.getRevision());
		assertEquals(product.getTei(), k.getTei());
		assertEquals(product.getTeiDomain(), k.getTeiDomain());
		assertEquals(product.getProductComponents(), k.getProductComponents());
		assertEquals(product.getProfiles(), k.getProfiles());
		assertEquals(product.getVisibility(), k.getVisibility());
		assertEquals(TeaRawArtifacts.NONE, product.getRawArtifacts(), "the product row itself is never touched");
	}

	@Test
	void aChildUnderAllAndIncludeWithItsOwnNoneAndExcludeShipsNoneAndExcludes() throws Exception {
		TeaProfileData productProfile = profile(TeaVisibility.PUBLIC);
		productProfile.setRawArtifacts(TeaRawArtifacts.ALL);
		TeaProfileData own = profile(TeaVisibility.PRIVATE);
		own.setRawArtifacts(TeaRawArtifacts.NONE);
		recipe(component.getUuid()).setInternalMetadata(SbomInternalMetadata.EXCLUDE);
		resolutions.put(component.getUuid(), fromComponent(component, own));
		ReleaseData product = productOver("platform", resolvedFromOrg(productProfile), List.of(release.getUuid()));
		recipe(product.getComponent()).setInternalMetadata(SbomInternalMetadata.INCLUDE);
		ArgumentCaptor<ReleaseData> rd = ArgumentCaptor.forClass(ReleaseData.class);
		ArgumentCaptor<PublicationMergeRequest> mr = ArgumentCaptor.forClass(PublicationMergeRequest.class);

		publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		verify(releaseService, times(2)).generateMergedBomForPublication(rd.capture(), mr.capture(), any());
		int childAt = rd.getAllValues().get(0).getUuid().equals(release.getUuid()) ? 0 : 1;
		assertTrue(mr.getAllValues().get(childAt).exportMetadata().internalMetadataDeclined());
		assertFalse(mr.getAllValues().get(1 - childAt).exportMetadata().internalMetadataDeclined(),
				"the product's own aggregate keeps the product's INCLUDE");
		TeaPublicationData child = head();
		List<TeaArtifactOrigin> origins = service.getLatestCollection(child.getUuid()).orElseThrow().getEntries().stream()
				.map(e -> e.origin()).toList();
		assertEquals(List.of(TeaArtifactOrigin.SYNTHETIC), origins, "the child's own NONE drops the ride-along");

		// the same child with its own row at ALL lists its SBOM on the product's next re-publish
		own.setRawArtifacts(TeaRawArtifacts.ALL);
		publishRelease(product.getUuid(), TeaPublishMode.REPUBLISH);
		assertEquals(List.of(TeaArtifactOrigin.SYNTHETIC, TeaArtifactOrigin.REAL), service.getLatestCollection(
				child.getUuid()).orElseThrow().getEntries().stream().map(e -> e.origin()).toList());
	}

	/** Task TEA-19, row 31: a child's ride-along with a relative link is skipped; the child is published. */
	@Test
	void aCascadeChildWithABadLinkIsSkippedNotRefused() throws Exception {
		captureServiceWarnings();
		UUID ext = artifact(ArtifactType.RELEASE_NOTES, "child-notes.html", "1", StoredIn.EXTERNALLY, false, null);
		Link link = new Link();
		link.setUri("/relative/notes.html");
		artifacts.get(ext).setDownloadLinks(new ArrayList<>(List.of(link)));
		artifacts.get(ext).getDigestRecords().add(new DigestRecord(DigestAlgorithm.SHA_256, "ab".repeat(32),
				DigestScope.ORIGINAL_FILE));
		setInventory(release, List.of(inventoryBom, ext));
		// cascadeSettings takes the narrower rawArtifacts of the two profiles: both ALL, so the ride-alongs run
		profile.setRawArtifacts(TeaRawArtifacts.ALL);
		TeaProfileData productProfile = profile(TeaVisibility.PUBLIC);
		productProfile.setRawArtifacts(TeaRawArtifacts.ALL);
		ReleaseData product = productOver("platform", resolvedFromOrg(productProfile), List.of(release.getUuid()));

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		TeaChildPublishResult c = childOf(r, release.getUuid());
		assertEquals(TeaChildOutcome.PUBLISHED, c.outcome());
		assertNotNull(c.publication());
		TeaPublishedCollection row = collectionRows.get(c.publication() + ":1");
		assertNotNull(row);
		TeaPublishedCollectionData stored = service.getLatestCollection(head().getUuid()).orElseThrow();
		assertFalse(stored.getEntries().stream().anyMatch(e -> ext.equals(e.artifact())));
		assertTrue(stored.getEntries().stream()
				.anyMatch(e -> inventoryBom.equals(e.artifact()) && TeaArtifactOrigin.REAL == e.origin()),
				"the ride-alongs ran; the absence is the skip");
		assertFalse(row.getDocument().contains("/relative/notes.html"));
		List<String> skips = capturedWarnings().stream().filter(w -> w.contains("SKIPPED_LINK_NOT_HTTP_URL")).toList();
		assertEquals(1, skips.size(), skips.toString());
		assertTrue(skips.get(0).contains(ext.toString()), skips.get(0));
		assertFalse(r.artifacts().stream().anyMatch(a -> ext.equals(a.artifact())),
				"the product's own result lists the top release's ride-alongs only");
	}

	static Stream<Arguments> minimumPairs() {
		return Stream.of(Arguments.of(ReleaseLifecycle.ASSEMBLED, ReleaseLifecycle.READY_TO_SHIP),
				Arguments.of(ReleaseLifecycle.READY_TO_SHIP, ReleaseLifecycle.ASSEMBLED));
	}

	@ParameterizedTest
	@MethodSource("minimumPairs")
	void aChildIsHeldToTheStricterMinimumLifecycle(ReleaseLifecycle productMinimum, ReleaseLifecycle ownMinimum)
			throws Exception {
		TeaProfileData productProfile = profile(TeaVisibility.PUBLIC);
		productProfile.setMinimumLifecycle(productMinimum);
		TeaProfileData own = profile(TeaVisibility.PRIVATE);
		own.setMinimumLifecycle(ownMinimum);
		resolutions.put(component.getUuid(), fromComponent(component, own));
		ReleaseData product = productOver("platform", resolvedFromOrg(productProfile), List.of(release.getUuid()));
		if (ReleaseLifecycle.READY_TO_SHIP == productMinimum) setLifecycle(product, ReleaseLifecycle.READY_TO_SHIP);

		TeaChildPublishResult c = childOf(publishRelease(product.getUuid(), TeaPublishMode.PUBLISH), release.getUuid());

		assertEquals(TeaChildOutcome.REFUSED, c.outcome());
		assertEquals(TeaPublishRefusal.LIFECYCLE_TOO_LOW, c.refusal());
		assertTrue(c.detail().contains("READY_TO_SHIP"), c.detail());
		assertEquals(ReleaseLifecycle.READY_TO_SHIP, TeaPublicationService.stricterMinimum(productMinimum, ownMinimum));
	}

	// ---------------------------------------------------------------- 63, one owner (T-3)

	private ReleaseData p1;
	private ReleaseData p2;

	/** Two products listing the baseline release {@code ca}; P1 published first owns it. */
	private TeaPublishResult twoProductsWithP1Owning() throws Exception {
		p1 = productOver("left", resolvedFromOrg(profile(TeaVisibility.PRIVATE)), List.of(release.getUuid()));
		p2 = productOver("right", resolvedFromOrg(profile(TeaVisibility.PUBLIC)), List.of(release.getUuid()));
		TeaPublishResult r = publishRelease(p1.getUuid(), TeaPublishMode.PUBLISH);
		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, release.getUuid()).outcome());
		assertEquals(r.publication().getUuid(), head().getCascadeOf());
		return r;
	}

	private void assertTakenOverByP2WithOneEvent(UUID p1Publication, int versionBefore) throws Exception {
		List<ReleaseUpdateEvent> before = teaEvents(release.getUuid());
		TeaPublishResult r = publishRelease(p2.getUuid(), TeaPublishMode.PUBLISH_OR_REPUBLISH);
		TeaChildPublishResult c = childOf(r, release.getUuid());
		assertEquals(TeaChildOutcome.UNCHANGED, c.outcome());
		TeaPublicationData child = head();
		assertEquals(r.publication().getUuid(), child.getCascadeOf());
		assertEquals(versionBefore, child.getLatestVersion(), "unchanged content writes no collection version");
		List<ReleaseUpdateEvent> after = teaEvents(release.getUuid());
		assertEquals(before.size() + 1, after.size());
		ReleaseUpdateEvent handOver = after.get(after.size() - 1);
		assertEquals(ReleaseUpdateAction.CHANGED, handOver.rua());
		assertEquals("with left 1.2.3", handOver.oldValue());
		assertEquals("with right 1.2.3", handOver.newValue());
	}

	@Test
	void aSecondProductSkipsAChildItsPublishedOwnerStillLists() throws Exception {
		twoProductsWithP1Owning();
		int writes = writtenDtos.size();
		int events = teaEvents(release.getUuid()).size();
		clearInvocations(synthetic, publications, oss);

		TeaPublishResult r = publishRelease(p2.getUuid(), TeaPublishMode.PUBLISH);

		TeaChildPublishResult c = childOf(r, release.getUuid());
		assertEquals(TeaChildOutcome.SKIPPED_OTHER_PRODUCT, c.outcome());
		assertEquals("published with product left 1.2.3", c.detail());
		assertEquals(head().getUuid(), c.publication());
		assertEquals(1, head().getLatestVersion());
		assertEquals(events, teaEvents(release.getUuid()).size());
		verify(synthetic, never()).upload(eq(release.getUuid()), any(), any(), any(), any());
		verify(oss, never()).saveRelease(same(releaseRows.get(release.getUuid())), any(ReleaseData.class), any(),
				anyBoolean(), any(AcollectionMode.class));
		verify(publications, never()).save(argThat((TeaPublication h) -> release.getUuid().equals(h.getRelease())));
		assertEquals(writes + 1, writtenDtos.size(), "only P2's own aggregate was written");
	}

	@Test
	void aHiddenOwnerLetsTheSecondProductTakeTheChildOverAndTheFirstThenSkipsIt() throws Exception {
		UUID p1Pub = twoProductsWithP1Owning().publication().getUuid();
		service.hide(p1.getUuid(), null, WU);

		assertTakenOverByP2WithOneEvent(p1Pub, 1);

		// P1 back: P2 owns the child and lists it, so P1 leaves it alone
		TeaPublishResult again = publishRelease(p1.getUuid(), TeaPublishMode.REPUBLISH);
		assertEquals(TeaPublicationState.PUBLISHED, again.publication().getState());
		TeaChildPublishResult c = childOf(again, release.getUuid());
		assertEquals(TeaChildOutcome.SKIPPED_OTHER_PRODUCT, c.outcome());
		assertEquals("published with product right 1.2.3", c.detail());
	}

	@Test
	void aMissingOwnerLetsTheSecondProductTakeTheChildOver() throws Exception {
		UUID p1Pub = twoProductsWithP1Owning().publication().getUuid();
		heads.remove(p1Pub);
		// the label of a missing owner falls back to its publication uuid
		List<ReleaseUpdateEvent> before = teaEvents(release.getUuid());
		TeaPublishResult r = publishRelease(p2.getUuid(), TeaPublishMode.PUBLISH);
		assertEquals(TeaChildOutcome.UNCHANGED, childOf(r, release.getUuid()).outcome());
		assertEquals(r.publication().getUuid(), head().getCascadeOf());
		ReleaseUpdateEvent handOver = teaEvents(release.getUuid()).get(before.size());
		assertEquals("with publication " + p1Pub, handOver.oldValue());
		assertEquals("with right 1.2.3", handOver.newValue());
	}

	@Test
	void anOwnerThatNoLongerListsTheChildLetsTheSecondProductTakeItOver() throws Exception {
		UUID p1Pub = twoProductsWithP1Owning().publication().getUuid();
		ComponentData other = component("valve", ComponentType.COMPONENT);
		ReleaseData unrelated = release(other, ReleaseLifecycle.ASSEMBLED, List.of());
		setParents(p1, List.of(unrelated.getUuid()));

		assertTakenOverByP2WithOneEvent(p1Pub, 1);
	}

	@Test
	void aTakeoverWithChangedContentWritesAVersionAndNoHandOverEvent() throws Exception {
		twoProductsWithP1Owning();
		service.hide(p1.getUuid(), null, WU);
		documents.put(release.getUuid(), bom("pkg:generic/a@2"));

		TeaPublishResult r = publishRelease(p2.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaChildOutcome.REPUBLISHED, childOf(r, release.getUuid()).outcome());
		assertEquals(2, head().getLatestVersion());
		ReleaseUpdateEvent last = teaEvents(release.getUuid()).get(teaEvents(release.getUuid()).size() - 1);
		assertEquals("v1", last.oldValue());
		assertEquals("v2", last.newValue());
		TeaPublishedCollection v2 = collectionRows.get(head().getUuid() + ":2");
		assertEquals(release.getUuid(), v2.getRelease());
		assertEquals(r.publication().getUuid(), head().getCascadeOf());
	}
}
