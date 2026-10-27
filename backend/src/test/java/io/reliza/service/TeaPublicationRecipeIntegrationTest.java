/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.TeaPublishRefusedException;
import io.reliza.exceptions.TeaPublishRefusedException.TeaPublishRefusal;
import io.reliza.model.SbomProfileData.SbomDependencyDepth;
import io.reliza.model.ScopedSettingsMode;
import io.reliza.model.SettingsScope;
import io.reliza.model.TeaProfileData.TeaComponentProfileMode;
import io.reliza.model.TeaProfileData.TeaProductComponents;
import io.reliza.model.TeaProfileData.TeaProfileScope;
import io.reliza.model.TeaProfileData.TeaPublishing;
import io.reliza.model.TeaProfileData.TeaRawArtifacts;
import io.reliza.model.TeaProfileData.TeaVisibility;
import io.reliza.model.dto.TeaProfileInputDto;
import io.reliza.service.TeaProfileService.TeaCaller;
import io.reliza.service.TeaPublicationService.TeaPublishMode;
import io.reliza.service.TeaPublicationService.TeaPublishOutcome;
import io.reliza.service.TeaPublicationService.TeaPublishPreview;
import io.reliza.service.saas.PerspectiveService;

/**
 * Task TEA-27, design 4.12: a publish reads its recipe through the SBOM profile catalog, at the
 * publishing component's scope, with the TEA settings' own perspective preferred (the functional
 * reviewer's F-1 case: no profile row on the component), and refuses a name in conflict (F-5: so does the
 * preview). The recipe reaching the merge is read off the mocked rebom's merge options.
 */
class TeaPublicationRecipeIntegrationTest extends TeaPublicationIntegrationTestBase {

	@Autowired PerspectiveService perspectiveService;

	private void sbom(SettingsScope scope, UUID object, String name, SbomDependencyDepth depth) throws Exception {
		sbomProfileService.saveProfile(org.getUuid(), scope, object, name, recipeInput()
				.mode(SettingsScope.COMPONENT == scope ? ScopedSettingsMode.OVERRIDE : null).dependencyDepth(depth).build(),
				WU);
	}

	/** Whether the last merge of {@code release} asked for top-level dependencies only. */
	private boolean lastMergeTldOnly(UUID release) {
		List<MergeCall> merges = mergesOf(release);
		assertFalse(merges.isEmpty(), "no merge for " + release);
		return Boolean.TRUE.equals(merges.get(merges.size() - 1).options().tldOnly());
	}

	private TeaProfileInputDto.TeaProfileInputDtoBuilder enabled() {
		return profileInput(TeaPublishing.ENABLED, TeaVisibility.PRIVATE, TeaRawArtifacts.NONE,
				TeaProductComponents.PUBLISH_WITH_PRODUCT);
	}

	@Test
	void theRecipeIsReadAtTheComponentsScope() throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World shaped = componentRelease("pkg:generic/pump@1.0.0");
		World plain = componentRelease("pkg:generic/valve@1.0.0");
		sbom(SettingsScope.COMPONENT, shaped.component(), "default", SbomDependencyDepth.TOP_LEVEL_ONLY);

		assertEquals(TeaPublishOutcome.PUBLISHED, publish(shaped.release(), TeaPublishMode.PUBLISH).outcome());
		assertEquals(TeaPublishOutcome.PUBLISHED, publish(plain.release(), TeaPublishMode.PUBLISH).outcome());

		assertTrue(lastMergeTldOnly(shaped.release()), "the component's default: no transitive dependencies");
		assertFalse(lastMergeTldOnly(plain.release()), "the organization's default: FULL");
	}

	@Test
	void anOrganizationRowNamingAnotherProfilePublishesItsRecipe() throws Exception {
		saveOrgRecipe();
		sbom(SettingsScope.ORGANIZATION, null, "a", SbomDependencyDepth.TOP_LEVEL_ONLY);
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.ORGANIZATION, null,
				enabled().profiles(List.of("a")).build(), TeaCaller.ORG_ADMIN, WU);
		World w = componentRelease("pkg:generic/pump@1.0.0");

		publish(w.release(), TeaPublishMode.PUBLISH);

		assertTrue(lastMergeTldOnly(w.release()), "profile a shapes the aggregate, not default");
	}

	/** A component in two perspectives P1 and P2, each with an ENABLED TEA row naming default and its own default. */
	private record TwoPerspectives(World w, UUID p1, UUID p2) {}

	private TwoPerspectives twoPerspectives(boolean secondDefinesDefault) throws Exception {
		saveOrgProfile(TeaRawArtifacts.NONE, TeaProductComponents.PUBLISH_WITH_PRODUCT);
		World w = componentRelease("pkg:generic/pump@1.0.0");
		UUID p1 = perspectiveService.createPerspective(org.getUuid(), "tea27-p1-" + UUID.randomUUID(), WU).getUuid();
		UUID p2 = perspectiveService.createPerspective(org.getUuid(), "tea27-p2-" + UUID.randomUUID(), WU).getUuid();
		sbom(SettingsScope.PERSPECTIVE, p1, "default", SbomDependencyDepth.TOP_LEVEL_ONLY);
		if (secondDefinesDefault) sbom(SettingsScope.PERSPECTIVE, p2, "default", SbomDependencyDepth.FULL);
		for (UUID p : List.of(p1, p2)) {
			teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.PERSPECTIVE, p, enabled().build(),
					TeaCaller.ORG_ADMIN, WU);
		}
		perspectiveService.setPerspectivesOnComponent(w.component(), List.of(p1, p2), TeaCaller.ORG_ADMIN, WU);
		return new TwoPerspectives(w, p1, p2);
	}

	@Test
	void aFollowOfOneOfTwoPerspectivesDefiningDefaultPublishesWithTheFollowedRecipe() throws Exception {
		// The functional reviewer's F-1 case: no profile row on the component.
		assumeFalse(LicensingConstants.isOssEdition(), "perspectives are ReARM Pro");
		TwoPerspectives t = twoPerspectives(true);
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.COMPONENT, t.w().component(),
				TeaProfileInputDto.builder().mode(TeaComponentProfileMode.FOLLOW_PERSPECTIVE).followedPerspective(t.p1())
						.build(), TeaCaller.ORG_ADMIN, WU);

		TeaPublishPreview preview = teaPublicationService.preview(t.w().release());
		assertTrue(preview.canPublish(), String.valueOf(preview.refusalMessage()));

		assertEquals(TeaPublishOutcome.PUBLISHED, publish(t.w().release(), TeaPublishMode.PUBLISH).outcome());
		assertTrue(lastMergeTldOnly(t.w().release()), "P1's default: no transitive dependencies");
	}

	@Test
	void anOverrideRowNamingADefaultInConflictIsRefusedAndThePreviewSaysSo() throws Exception {
		assumeFalse(LicensingConstants.isOssEdition(), "perspectives are ReARM Pro");
		TwoPerspectives t = twoPerspectives(false);
		// Saved while only P1 defines default: the save-time check is an early warning only.
		teaProfileService.saveProfile(org.getUuid(), TeaProfileScope.COMPONENT, t.w().component(),
				enabled().mode(TeaComponentProfileMode.OVERRIDE).build(), TeaCaller.ORG_ADMIN, WU);
		sbom(SettingsScope.PERSPECTIVE, t.p2(), "default", SbomDependencyDepth.FULL);

		TeaPublishPreview preview = teaPublicationService.preview(t.w().release());
		assertFalse(preview.canPublish());
		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, preview.refusal());
		assertTrue(preview.refusalMessage().contains("SBOM profile default resolves to a conflict between perspectives"),
				preview.refusalMessage());

		TeaPublishRefusedException e = assertThrows(TeaPublishRefusedException.class,
				() -> publish(t.w().release(), TeaPublishMode.PUBLISH));
		assertEquals(TeaPublishRefusal.PROFILE_CONFLICT, e.reason());
		assertEquals(preview.refusalMessage(), e.getMessage());
		assertTrue(mergesOf(t.w().release()).isEmpty(), "nothing generated");
		assertTrue(publicationRepository.findByRelease(t.w().release()).isEmpty(), "nothing written");

		// The remedy in the message: define it on the component.
		sbom(SettingsScope.COMPONENT, t.w().component(), "default", SbomDependencyDepth.TOP_LEVEL_ONLY);
		assertTrue(teaPublicationService.preview(t.w().release()).canPublish());
		assertNotEquals(TeaPublishOutcome.UNCHANGED, publish(t.w().release(), TeaPublishMode.PUBLISH).outcome());
		assertTrue(lastMergeTldOnly(t.w().release()));
	}
}
