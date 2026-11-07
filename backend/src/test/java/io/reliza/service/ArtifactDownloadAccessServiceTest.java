/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.CommonVariables.Removable;
import io.reliza.common.CommonVariables.TagRecord;
import io.reliza.model.ArtifactData;
import io.reliza.model.ReleaseData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.service.ArtifactDownloadAccessService.Holders;

/**
 * FDAW-28 row 2 (parent row 20): the holder resolution over mocks, and the empty holder set going to the
 * component check. No resolver is wired unless a case says so, which is the CE answer.
 */
class ArtifactDownloadAccessServiceTest {

	private static final String DRAFT_KEY = CommonVariables.SUBMISSION_DRAFT_TAG_KEY;
	private static final String RELEASE_KEY = CommonVariables.SYNTHETIC_OF_RELEASE_TAG_KEY;
	private static final Holders NOBODY = new Holders(Set.of(), false);
	private static final Holders ORGANIZATION = new Holders(Set.of(), true);

	private final UUID org = UUID.randomUUID();
	private final UUID artifact = UUID.randomUUID();
	private final UUID release = UUID.randomUUID();
	private final UUID draft = UUID.randomUUID();
	private final UUID component = UUID.randomUUID();
	private final AuthorizationService authorizationService = mock(AuthorizationService.class);
	private final SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
	private final SubmissionDraftArtifactResolver resolver = mock(SubmissionDraftArtifactResolver.class);
	private final ArtifactDownloadAccessService service = new ArtifactDownloadAccessService();

	@BeforeEach
	void wire() {
		ReflectionTestUtils.setField(service, "authorizationService", authorizationService);
		ReflectionTestUtils.setField(service, "sharedReleaseService", sharedReleaseService);
		when(sharedReleaseService.gatherReleasesForArtifact(artifact, org)).thenReturn(List.of());
	}

	private void withResolver() {
		ReflectionTestUtils.setField(service, "submissionDraftArtifactResolver", resolver);
	}

	private ArtifactData artifactWith(TagRecord... tags) {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", artifact);
		ad.setOrg(org);
		ad.setTags(new ArrayList<>(Arrays.asList(tags)));
		return ad;
	}

	private static TagRecord system(String key, Object value) {
		return new TagRecord(key, String.valueOf(value), Removable.NO);
	}

	private static TagRecord removable(String key, Object value) {
		return new TagRecord(key, String.valueOf(value), Removable.YES);
	}

	private ReleaseData releaseOf(UUID releaseOrg, UUID releaseComponent) {
		ReleaseData rd = mock(ReleaseData.class);
		when(rd.getOrg()).thenReturn(releaseOrg);
		when(rd.getComponent()).thenReturn(releaseComponent);
		return rd;
	}

	@Test
	void aBoundArtifactAnswersTheComponentsOfItsReleases() {
		UUID other = UUID.randomUUID();
		List<ReleaseData> holders = List.of(releaseOf(org, component), releaseOf(org, other));
		when(sharedReleaseService.gatherReleasesForArtifact(artifact, org)).thenReturn(holders);
		assertEquals(new Holders(Set.of(component, other), false),
				service.holdersOf(artifactWith(system(DRAFT_KEY, draft), system(RELEASE_KEY, release))));
	}

	@Test
	void theTwoArgumentFormTakesTheGivenReleasesWithoutGathering() {
		assertEquals(new Holders(Set.of(component), false),
				service.holdersOf(artifactWith(), List.of(releaseOf(org, component))));
		verify(sharedReleaseService, never()).gatherReleasesForArtifact(any(), any());
	}

	@Test
	void theTwoArgumentFormRunsTheTagStepsOnAnEmptyList() {
		assertEquals(NOBODY, service.holdersOf(artifactWith(system(DRAFT_KEY, draft), system(RELEASE_KEY, release)),
				List.of()));
		assertEquals(ORGANIZATION, service.holdersOf(artifactWith(), List.of()));
	}

	@Test
	void anUnboundArtifactWithNeitherSystemTagAnswersTheOrganization() {
		assertEquals(ORGANIZATION, service.holdersOf(artifactWith()));
	}

	@Test
	void aNullTagListReadsAsNoTags() {
		ArtifactData ad = artifactWith();
		ad.setTags(null);
		assertEquals(ORGANIZATION, service.holdersOf(ad));
	}

	@Test
	void aDraftTagAnswersNobodyWithoutAResolverBean() {
		assertEquals(NOBODY, service.holdersOf(artifactWith(system(DRAFT_KEY, draft), system(RELEASE_KEY, release))));
		verify(sharedReleaseService, never()).getReleaseData(any());
	}

	@Test
	void aDraftTagAnswersTheResolversComponent() {
		withResolver();
		when(resolver.componentOfDraftPart(draft, release, org)).thenReturn(Optional.of(component));
		assertEquals(new Holders(Set.of(component), false),
				service.holdersOf(artifactWith(system(DRAFT_KEY, draft), system(RELEASE_KEY, release))));
	}

	@Test
	void aResolverAnsweringEmptyAnswersNobodyAndNeverFallsToTheReleaseBranch() {
		withResolver();
		when(resolver.componentOfDraftPart(draft, release, org)).thenReturn(Optional.empty());
		assertEquals(NOBODY, service.holdersOf(artifactWith(system(DRAFT_KEY, draft), system(RELEASE_KEY, release))));
		verify(sharedReleaseService, never()).getReleaseData(any());
	}

	@Test
	void aDraftTagWithNoReleaseTagAnswersNobodyAndAsksNoResolver() {
		withResolver();
		assertEquals(NOBODY, service.holdersOf(artifactWith(system(DRAFT_KEY, draft))));
		verifyNoInteractions(resolver);
	}

	@Test
	void aRemovableReleaseTagBesideADraftTagIsNotARelease() {
		withResolver();
		assertEquals(NOBODY, service.holdersOf(artifactWith(system(DRAFT_KEY, draft), removable(RELEASE_KEY, release))));
		verifyNoInteractions(resolver);
	}

	@Test
	void aSyntheticRowAnswersItsReleasesComponent() {
		ReleaseData rd = releaseOf(org, component);
		when(sharedReleaseService.getReleaseData(release)).thenReturn(Optional.of(rd));
		assertEquals(new Holders(Set.of(component), false), service.holdersOf(artifactWith(system(RELEASE_KEY, release))));
	}

	@Test
	void aSyntheticRowOfAReleaseInAnotherOrganizationAnswersNobody() {
		ReleaseData rd = releaseOf(UUID.randomUUID(), component);
		when(sharedReleaseService.getReleaseData(release)).thenReturn(Optional.of(rd));
		assertEquals(NOBODY, service.holdersOf(artifactWith(system(RELEASE_KEY, release))));
	}

	@Test
	void aSyntheticRowOfAMissingReleaseAnswersNobody() {
		when(sharedReleaseService.getReleaseData(release)).thenReturn(Optional.empty());
		assertEquals(NOBODY, service.holdersOf(artifactWith(system(RELEASE_KEY, release))));
	}

	@Test
	void onlyRemovableTagsAnswerTheOrganization() {
		assertEquals(ORGANIZATION, service.holdersOf(artifactWith(removable(RELEASE_KEY, release))));
		assertEquals(ORGANIZATION, service.holdersOf(artifactWith(removable(DRAFT_KEY, draft),
				removable(RELEASE_KEY, release))));
		verify(sharedReleaseService, never()).getReleaseData(any());
	}

	@Test
	void aRemovableDraftTagBesideASystemReleaseTagTakesTheReleaseBranch() {
		withResolver();
		ReleaseData rd = releaseOf(org, component);
		when(sharedReleaseService.getReleaseData(release)).thenReturn(Optional.of(rd));
		assertEquals(new Holders(Set.of(component), false),
				service.holdersOf(artifactWith(removable(DRAFT_KEY, draft), system(RELEASE_KEY, release))));
		verifyNoInteractions(resolver);
	}

	@Test
	void aSystemTagValueThatIsNotAUuidAnswersNobody() {
		withResolver();
		assertEquals(NOBODY, service.holdersOf(artifactWith(system(DRAFT_KEY, "not-a-uuid"), system(RELEASE_KEY, release))));
		assertEquals(NOBODY, service.holdersOf(artifactWith(system(DRAFT_KEY, draft), system(RELEASE_KEY, "not-a-uuid"))));
		assertEquals(NOBODY, service.holdersOf(artifactWith(system(RELEASE_KEY, "not-a-uuid"))));
		assertEquals(NOBODY, service.holdersOf(artifactWith(new TagRecord(RELEASE_KEY, null, Removable.NO))));
		verifyNoInteractions(resolver);
		verify(sharedReleaseService, never()).getReleaseData(any());
	}

	@Test
	void noHolderGoesToTheComponentCheckOverTheEmptySetAndARefusalPropagates() throws Exception {
		UserData ud = mock(UserData.class);
		ArtifactData ad = artifactWith();
		doThrow(new AccessDeniedException("Not authorized")).when(authorizationService).isUserAuthorizedForAnyObjectGraphQL(
				ud, PermissionFunction.ARTIFACT_DOWNLOAD, PermissionScope.COMPONENT, Set.of(), List.of(ad), CallType.READ);
		assertThrows(AccessDeniedException.class, () -> service.authorizeUserDownload(ud, ad, NOBODY));
		verifyNoOrganizationCheck();
	}

	@Test
	void noHolderReturnsWhenTheComponentCheckPassesAGlobalAdmin() throws Exception {
		UserData ud = mock(UserData.class);
		ArtifactData ad = artifactWith();
		service.authorizeUserDownload(ud, ad, NOBODY);
		verify(authorizationService).isUserAuthorizedForAnyObjectGraphQL(ud, PermissionFunction.ARTIFACT_DOWNLOAD,
				PermissionScope.COMPONENT, Set.of(), List.of(ad), CallType.READ);
		verifyNoOrganizationCheck();
	}

	@Test
	void organizationScopeAsksTheOrganizationCheck() throws Exception {
		UserData ud = mock(UserData.class);
		ArtifactData ad = artifactWith();
		service.authorizeUserDownload(ud, ad, ORGANIZATION);
		verify(authorizationService).isUserAuthorizedForObjectGraphQL(ud, PermissionFunction.ARTIFACT_DOWNLOAD,
				PermissionScope.ORGANIZATION, org, List.of(ad), CallType.READ);
		verify(authorizationService, never()).isUserAuthorizedForAnyObjectGraphQL(any(), any(), any(), any(), anyCollection(),
				any());
	}

	private void verifyNoOrganizationCheck() throws Exception {
		verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(any(), any(), eq(PermissionScope.ORGANIZATION),
				any(), anyCollection(), any());
	}
}
