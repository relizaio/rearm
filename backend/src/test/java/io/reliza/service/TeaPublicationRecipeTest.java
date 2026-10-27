/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import io.reliza.common.CommonVariables.PerspectiveType;
import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.SbomProfileData;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.SbomProfileData.SbomInternalMetadata;
import io.reliza.model.ScopedResolutionStatus;
import io.reliza.model.ScopedSettingsSource;
import io.reliza.model.TeaProfileData;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.service.ReleaseService.PublicationMergeRequest;
import io.reliza.service.SbomProfileResolver.SbomProfileResolution;
import io.reliza.service.TeaProfileResolver.TeaProfileSource;
import io.reliza.service.TeaProfileResolver.TeaResolutionStatus;
import io.reliza.service.TeaProfileResolver.TeaResolvedProfile;
import io.reliza.service.TeaPublicationService.ReleaseContext;
import io.reliza.service.TeaPublicationService.TeaNamedRecipe;
import io.reliza.service.TeaPublicationService.TeaChildOutcome;
import io.reliza.service.TeaPublicationService.TeaChildPublishResult;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishPreview;
import io.reliza.service.TeaPublicationService.TeaPublishResult;
import io.reliza.service.oss.OssPerspectiveService.TeaPerspectiveRef;

/**
 * Task TEA-27, design 4.7, rewritten for the list by task TEA-28, design 4.4: a publish reads one recipe
 * per name of the resolved TEA settings through the SBOM profile catalog, in order, at the publishing
 * component with the settings' own perspective preferred; the first name that does not resolve refuses
 * before anything is minted or written; the cascade shapes every child with the top product's names and
 * recipes, each capped by the child (F-1, F-4); the preview runs the same check in the publish's order.
 */
class TeaPublicationRecipeTest extends TeaPublicationServiceTestBase {

	private final TeaPerspectiveRef alpha = new TeaPerspectiveRef(UUID.randomUUID(), "Alpha", PerspectiveType.PERSPECTIVE);
	private final TeaPerspectiveRef beta = new TeaPerspectiveRef(UUID.randomUUID(), "Beta", PerspectiveType.PERSPECTIVE);

	private static SbomProfileResolution notFound() {
		return new SbomProfileResolution(ScopedResolutionStatus.NOT_FOUND, null, null, null, List.of());
	}

	private SbomProfileResolution conflict() {
		return new SbomProfileResolution(ScopedResolutionStatus.CONFLICT, ScopedSettingsSource.PERSPECTIVE, null, null,
				List.of(alpha, beta));
	}

	private static TeaResolvedProfile from(TeaProfileSource source, UUID object, TeaProfileData p) {
		return new TeaResolvedProfile(TeaResolutionStatus.RESOLVED, source, object, p, List.of());
	}

	@Test
	void theRecipeIsResolvedAtThePublishingComponentWithNoRecordedRow() throws Exception {
		SbomProfileData recipe = recipe(component.getUuid());
		recipe.setDependencyDepth(SbomDependencyDepth.TOP_LEVEL_ONLY);
		ArgumentCaptor<PublicationMergeRequest> mr = ArgumentCaptor.forClass(PublicationMergeRequest.class);

		publish(TeaPublishMode.PUBLISH);

		verify(sbomResolver).resolveForComponent(eq(component), eq("default"), isNull(), isNull());
		verify(releaseService).generateMergedBomForPublication(any(), mr.capture(), any());
		assertTrue(mr.getValue().tldOnly(), "the component's recipe shapes the aggregate");
		assertEquals(TeaPublicationService.mergeRequestOf(recipe), mr.getValue());
	}

	@Test
	void recipesForPassesTheSettingsPerspectiveAsThePreferenceAndNothingElse() throws Exception {
		ReleaseContext ctx = new ReleaseContext(ReleaseData.dataFromRecord(releaseRows.get(release.getUuid())), component);
		UUID object = UUID.randomUUID();
		for (TeaProfileSource source : TeaProfileSource.values()) {
			TeaResolvedProfile resolved = from(source, object, profile);
			List<TeaNamedRecipe> recipes = service.recipesFor(ctx, resolved);
			assertEquals(List.of(new TeaNamedRecipe("default", recipeOf(component.getUuid()).profile())), recipes);
			UUID expected = TeaProfileSource.FOLLOWED_PERSPECTIVE == source || TeaProfileSource.PERSPECTIVE == source
					? object : null;
			assertEquals(expected, TeaPublicationService.settingsPerspectiveOf(resolved), source.name());
		}
		// COMPONENT, ORGANIZATION and DEFAULT pass no preference; FOLLOWED_PERSPECTIVE and PERSPECTIVE their object.
		verify(sbomResolver, times(3)).resolveForComponent(eq(component), eq("default"), isNull(), isNull());
		verify(sbomResolver, times(2)).resolveForComponent(eq(component), eq("default"), isNull(), eq(object));
		verify(sbomResolver, never()).resolveForComponent(any(), any(), org.mockito.ArgumentMatchers.notNull(), any());
	}

	@Test
	void aNameThatDoesNotResolveRefusesProfileNotFoundBeforeAnythingIsMintedOrWritten() throws Exception {
		recipeResolutions.put(key(component.getUuid()), notFound());
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.PROFILE_NOT_FOUND, e.reason());
		assertTrue(e.getMessage().startsWith("Release pump "), e.getMessage());
		assertTrue(e.getMessage().endsWith("cannot be published on TEA: SBOM profile default, named by the organization "
				+ "TEA publishing settings, does not resolve for component pump"), e.getMessage());
		assertNothingWritten();
		verify(organizationService, never()).ensureTeaUuid(any(), any());
		verify(componentService, never()).ensureTeaUuid(any(), any());
		verify(releaseService, never()).generateMergedBomForPublication(any(), any(), any());
	}

	@Test
	void aConflictingNameRefusesProfileConflictNamingThePerspectives() throws Exception {
		recipeResolutions.put(key(component.getUuid()), conflict());
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, e.reason());
		assertTrue(e.getMessage().endsWith("cannot be published on TEA: SBOM profile default resolves to a conflict "
				+ "between perspectives Alpha, Beta for component pump; define it on the component or follow one "
				+ "perspective for it"), e.getMessage());
		assertNothingWritten();
		verify(organizationService, never()).ensureTeaUuid(any(), any());
	}

	@Test
	void recipesForResolvesEveryNameAtThePublishingComponentInOrder() throws Exception {
		profiles(profile, "default", "top", "third", "top");
		SbomProfileData top = recipe(component.getUuid(), "top");
		SbomProfileData third = recipe(component.getUuid(), "third");
		UUID perspective = UUID.randomUUID();
		ReleaseContext ctx = new ReleaseContext(ReleaseData.dataFromRecord(releaseRows.get(release.getUuid())), component);

		List<TeaNamedRecipe> recipes = service.recipesFor(ctx, from(TeaProfileSource.PERSPECTIVE, perspective, profile));

		assertEquals(List.of("default", "top", "third"), recipes.stream().map(TeaNamedRecipe::name).toList(),
				"in list order, a duplicate once");
		assertEquals(top, recipes.get(1).recipe());
		assertEquals(third, recipes.get(2).recipe());
		InOrder order = inOrder(sbomResolver);
		for (String name : List.of("default", "top", "third")) {
			order.verify(sbomResolver).resolveForComponent(eq(component), eq(name), isNull(), eq(perspective));
		}
		verify(sbomResolver, times(3)).resolveForComponent(any(), any(), any(), any());
	}

	@Test
	void aSecondNameThatDoesNotResolveRefusesProfileNotFoundNamingItBeforeAnythingIsMintedOrWritten() throws Exception {
		profiles(profile, "default", "nope");
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.PROFILE_NOT_FOUND, e.reason());
		assertTrue(e.getMessage().endsWith("cannot be published on TEA: SBOM profile nope, named by the organization "
				+ "TEA publishing settings, does not resolve for component pump"), e.getMessage());
		assertNothingWritten();
		verify(organizationService, never()).ensureTeaUuid(any(), any());
		verify(componentService, never()).ensureTeaUuid(any(), any());
		verify(releaseService, never()).generateMergedBomForPublication(any(), any(), any());
	}

	@Test
	void aSecondNameInConflictRefusesProfileConflictNamingItAndThePerspectives() throws Exception {
		profiles(profile, "default", "top");
		recipeResolutions.put(key(component.getUuid(), "top"), conflict());
		TeaPublishRefusedException e = refused(TeaPublishMode.PUBLISH);
		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, e.reason());
		assertTrue(e.getMessage().endsWith("cannot be published on TEA: SBOM profile top resolves to a conflict "
				+ "between perspectives Alpha, Beta for component pump; define it on the component or follow one "
				+ "perspective for it"), e.getMessage());
		assertNothingWritten();
		verify(releaseService, never()).generateMergedBomForPublication(any(), any(), any());
	}

	@Test
	void theHeadStillRecordsTheTeaRowsUuidAndRevision() throws Exception {
		recipe(component.getUuid()).setRevision(17);
		publish(TeaPublishMode.PUBLISH);
		assertEquals(profile.getUuid(), head().getProfile());
		assertEquals(3, head().getProfileRevision(), "the TEA settings row's revision, not the recipe's");
		// A recipe change that changes the document republishes; the recorded row is still the TEA row.
		documents.put(release.getUuid(), bom("pkg:generic/a@1", "pkg:generic/b@1"));
		recipe(component.getUuid()).setDependencyDepth(SbomDependencyDepth.TOP_LEVEL_ONLY);
		assertEquals(TeaPublishOutcome.REPUBLISHED, publish(TeaPublishMode.REPUBLISH).outcome());
		assertEquals(profile.getUuid(), head().getProfile());
		assertEquals(3, head().getProfileRevision());
	}

	// ---------------------------------------------------------------- the cascade (F-3)

	private TeaChildPublishResult childOf(TeaPublishResult r, UUID child) {
		return r.children().stream().filter(c -> child.equals(c.release())).findFirst().orElseThrow();
	}

	/** The merge request a release's aggregate was generated with. */
	private PublicationMergeRequest requestOf(UUID releaseUuid, ArgumentCaptor<ReleaseData> rd,
			ArgumentCaptor<PublicationMergeRequest> mr) {
		for (int i = 0; i < rd.getAllValues().size(); i++) {
			if (releaseUuid.equals(rd.getAllValues().get(i).getUuid())) return mr.getAllValues().get(i);
		}
		throw new AssertionError("no aggregate generated for " + releaseUuid);
	}

	@Test
	void aChildWithItsOwnRecipeIsShapedByTheProductsWithInternalMetadataNarrowed() throws Exception {
		SbomProfileData own = recipe(component.getUuid());
		own.setDependencyDepth(SbomDependencyDepth.FULL);
		own.setInternalMetadata(SbomInternalMetadata.EXCLUDE);
		ReleaseData product = productOver("platform", resolvedFromOrg(profile(TeaVisibility.PRIVATE)),
				List.of(release.getUuid()));
		SbomProfileData productRecipe = recipe(product.getComponent());
		productRecipe.setDependencyDepth(SbomDependencyDepth.TOP_LEVEL_ONLY);
		productRecipe.setInternalMetadata(SbomInternalMetadata.INCLUDE);
		ArgumentCaptor<ReleaseData> rd = ArgumentCaptor.forClass(ReleaseData.class);
		ArgumentCaptor<PublicationMergeRequest> mr = ArgumentCaptor.forClass(PublicationMergeRequest.class);

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaChildOutcome.PUBLISHED, childOf(r, release.getUuid()).outcome());
		verify(releaseService, times(2)).generateMergedBomForPublication(rd.capture(), mr.capture(), any());
		PublicationMergeRequest child = requestOf(release.getUuid(), rd, mr);
		assertTrue(child.tldOnly(), "the product's dependencyDepth, not the child's own");
		assertTrue(child.exportMetadata().internalMetadataDeclined(), "the child's EXCLUDE narrows the product's INCLUDE");
		PublicationMergeRequest top = requestOf(product.getUuid(), rd, mr);
		assertTrue(top.tldOnly());
		assertFalse(top.exportMetadata().internalMetadataDeclined());
		// The child's own name was resolved at the child before its upload, then its view of the product's name.
		verify(sbomResolver, times(2)).resolveForComponent(eq(component), eq("default"), isNull(), isNull());
	}

	@Test
	void aNestedProductsChildrenAreShapedByTheTopProductsRecipe() throws Exception {
		ReleaseData sub = productOver("module", resolvedFromOrg(profile(TeaVisibility.PRIVATE)),
				List.of(release.getUuid()));
		recipe(sub.getComponent()).setDependencyDepth(SbomDependencyDepth.FULL);
		ReleaseData top = productOver("platform", resolvedFromOrg(profile(TeaVisibility.PRIVATE)),
				List.of(sub.getUuid()));
		recipe(top.getComponent()).setDependencyDepth(SbomDependencyDepth.TOP_LEVEL_ONLY);
		recipe(component.getUuid()).setDependencyDepth(SbomDependencyDepth.FULL);
		ArgumentCaptor<ReleaseData> rd = ArgumentCaptor.forClass(ReleaseData.class);
		ArgumentCaptor<PublicationMergeRequest> mr = ArgumentCaptor.forClass(PublicationMergeRequest.class);

		publishRelease(top.getUuid(), TeaPublishMode.PUBLISH);

		verify(releaseService, times(3)).generateMergedBomForPublication(rd.capture(), mr.capture(), any());
		assertTrue(requestOf(sub.getUuid(), rd, mr).tldOnly(), "the nested product is shaped by the top product");
		assertTrue(requestOf(release.getUuid(), rd, mr).tldOnly(), "so are its children");
	}

	@Test
	void aChildWhoseNameDoesNotResolveIsRefusedBeforeItsUploadAndTheProductStands() throws Exception {
		recipeResolutions.put(key(component.getUuid()), notFound());
		ReleaseData product = productOver("platform", resolvedFromOrg(profile(TeaVisibility.PRIVATE)),
				List.of(release.getUuid()));

		TeaPublishResult r = publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);

		assertEquals(TeaPublishOutcome.PUBLISHED, r.outcome());
		TeaChildPublishResult c = childOf(r, release.getUuid());
		assertEquals(TeaChildOutcome.REFUSED, c.outcome());
		assertEquals(TeaPublishRefusal.PROFILE_NOT_FOUND, c.refusal());
		assertTrue(c.detail().contains("SBOM profile default"), c.detail());
		verify(synthetic, times(1)).upload(any(), any(), any(), any(), any());
		assertTrue(service.getPublicationOfRelease(release.getUuid()).isEmpty(), "the child has no publication");

		recipeResolutions.put(key(component.getUuid()), conflict());
		TeaChildPublishResult again = childOf(publishRelease(product.getUuid(), TeaPublishMode.REPUBLISH),
				release.getUuid());
		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, again.refusal());
	}

	// ---------------------------------------------------------------- the preview (F-5)

	@Test
	void thePreviewReportsAProfileNotFoundAndAConflictAndPassesAResolvingName() {
		recipeResolutions.put(key(component.getUuid()), notFound());
		TeaPublishPreview nf = service.preview(release.getUuid());
		assertFalse(nf.canPublish());
		assertEquals(TeaPublishRefusal.PROFILE_NOT_FOUND, nf.refusal());
		assertTrue(nf.refusalMessage().contains("SBOM profile default, named by the organization TEA publishing "
				+ "settings, does not resolve for component pump"), nf.refusalMessage());

		recipeResolutions.put(key(component.getUuid()), conflict());
		TeaPublishPreview c = service.preview(release.getUuid());
		assertFalse(c.canPublish());
		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, c.refusal());
		assertTrue(c.refusalMessage().contains("between perspectives Alpha, Beta"), c.refusalMessage());
		assertEquals(3, c.profileRevision(), "the TEA row's revision, shape unchanged");

		recipeResolutions.remove(key(component.getUuid()));
		TeaPublishPreview ok = service.preview(release.getUuid());
		assertTrue(ok.canPublish());
		assertNull(ok.refusal());
	}

	@Test
	void thePreviewAnswersTheProfilesInOrderAndTheSecondNamesRefusal() {
		profiles(profile, "default", "top", "default");
		recipe(component.getUuid(), "top");
		TeaPublishPreview ok = service.preview(release.getUuid());
		assertTrue(ok.canPublish());
		assertEquals(List.of("default", "top"), ok.profiles());

		profiles(profile, "default", "nope", "top");
		TeaPublishPreview nf = service.preview(release.getUuid());
		assertFalse(nf.canPublish());
		assertEquals(TeaPublishRefusal.PROFILE_NOT_FOUND, nf.refusal());
		assertTrue(nf.refusalMessage().contains("SBOM profile nope, named by the organization"), nf.refusalMessage());
		assertEquals(List.of("default", "nope", "top"), nf.profiles(), "every name, the refused one included");

		// after the mode, profile and lifecycle checks, as the publish
		setLifecycle(release, ReleaseLifecycle.DRAFT);
		assertEquals(TeaPublishRefusal.LIFECYCLE_TOO_LOW, service.preview(release.getUuid()).refusal());
		setLifecycle(release, ReleaseLifecycle.ASSEMBLED);
		resolutions.put(component.getUuid(), new TeaResolvedProfile(TeaResolutionStatus.CONFLICT,
				TeaProfileSource.PERSPECTIVE, null, null, List.of(alpha, beta)));
		TeaPublishPreview none = service.preview(release.getUuid());
		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, none.refusal());
		assertEquals(List.of(), none.profiles(), "no resolution, no names");
	}

	@Test
	void thePreviewRunsTheRecipeCheckAfterTheModeProfileAndLifecycleChecks() throws Exception {
		recipeResolutions.put(key(component.getUuid()), notFound());
		setLifecycle(release, ReleaseLifecycle.DRAFT);
		assertEquals(TeaPublishRefusal.LIFECYCLE_TOO_LOW, service.preview(release.getUuid()).refusal());
		setLifecycle(release, ReleaseLifecycle.ASSEMBLED);

		profile.setPublishing(TeaPublishing.DISABLED);
		assertEquals(TeaPublishRefusal.PUBLISHING_DISABLED, service.preview(release.getUuid()).refusal());
		profile.setPublishing(TeaPublishing.ENABLED);

		resolutions.put(component.getUuid(), new TeaResolvedProfile(TeaResolutionStatus.CONFLICT,
				TeaProfileSource.PERSPECTIVE, null, null, List.of(alpha, beta)));
		TeaPublishPreview settingsConflict = service.preview(release.getUuid());
		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, settingsConflict.refusal());
		assertTrue(settingsConflict.refusalMessage().contains("TEA profiles of several perspectives"),
				"the TEA settings' own conflict comes first: " + settingsConflict.refusalMessage());
		resolutions.put(component.getUuid(), resolvedFromOrg(profile));

		recipeResolutions.remove(key(component.getUuid()));
		publish(TeaPublishMode.PUBLISH);
		recipeResolutions.put(key(component.getUuid()), notFound());
		assertEquals(TeaPublishRefusal.ALREADY_PUBLISHED, service.preview(release.getUuid()).refusal());
		assertThrows(TeaPublishRefusedException.class, () -> publish(TeaPublishMode.REPUBLISH));
	}

	@Test
	void theComponentArgumentIsTheCascadeChildNotTheProduct() throws Exception {
		ComponentData other = component("valve", io.reliza.model.ComponentData.ComponentType.COMPONENT);
		resolutions.put(other.getUuid(), resolvedFromOrg(profile));
		ReleaseData otherRelease = release(other, ReleaseLifecycle.ASSEMBLED, List.of(inventoryBom));
		documents.put(otherRelease.getUuid(), bom("pkg:generic/valve@1"));
		ReleaseData product = productOver("platform", resolvedFromOrg(profile(TeaVisibility.PRIVATE)),
				List.of(release.getUuid(), otherRelease.getUuid()));
		publishRelease(product.getUuid(), TeaPublishMode.PUBLISH);
		// each child: its own name, then its view of the product's name (task TEA-28, 3.4)
		verify(sbomResolver, times(2)).resolveForComponent(eq(component), eq("default"), isNull(), isNull());
		verify(sbomResolver, times(2)).resolveForComponent(eq(other), eq("default"), isNull(), isNull());
	}
}
