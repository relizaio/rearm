/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.ArtifactData;
import io.reliza.model.ReleaseData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.service.ArtifactDownloadAccessService;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.UserService;

/**
 * FDAW-28 3.3/3.10: the {@code artifact} GraphQL read (getArtifact), one of the four callers of
 * {@link ArtifactDownloadAccessService#holdersOf}. On a release-held artifact it asks the COMPONENT check
 * over the release's components; on an orphan artifact (no release, no system tags -- a session-attached
 * artifact or a signature sub-artifact) it falls back to the ORGANIZATION check. Neither branch had a unit
 * test calling {@code getArtifact} directly before this round, so a mutant dropping or inverting either
 * check survived. F-23/F-31 shape: a caller with only a COMPONENT-scope grant must be refused the orphan
 * artifact (403), an org admin (ORGANIZATION-scope grant) must be let through (200).
 */
@ExtendWith(MockitoExtension.class)
class ArtifactDataFetcherGetArtifactTest {

	@Mock private UserService userService;
	@Mock private AuthorizationService authorizationService;
	@Mock private ArtifactService artifactService;
	@Mock private SharedReleaseService sharedReleaseService;
	@InjectMocks private ArtifactDataFetcher fetcher;

	private final UUID org = UUID.randomUUID();
	private final UUID artifactUuid = UUID.randomUUID();
	private final UUID component = UUID.randomUUID();
	private final UserData ud = mock(UserData.class);

	@BeforeEach
	void wireHolderResolution() {
		ArtifactDownloadAccessService access = new ArtifactDownloadAccessService();
		ReflectionTestUtils.setField(access, "authorizationService", authorizationService);
		ReflectionTestUtils.setField(access, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(fetcher, "artifactDownloadAccessService", access);
	}

	@BeforeEach
	void ownSecurityContext() {
		SecurityContext ctx = SecurityContextHolder.createEmptyContext();
		ctx.setAuthentication(mock(JwtAuthenticationToken.class));
		SecurityContextHolder.setContext(ctx);
	}

	private ArtifactData artifact() {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", artifactUuid);
		ad.setOrg(org);
		return ad;
	}

	private void stubCommon(ArtifactData ad) {
		when(userService.getUserDataByAuth(any())).thenReturn(Optional.of(ud));
		when(artifactService.getArtifactData(artifactUuid)).thenReturn(Optional.of(ad));
	}

	/** The artifact sits in a release of {@code component}: the COMPONENT check, not the ORGANIZATION one. */
	@Test
	void aComponentScopeGrantIsAskedOverTheReleasesComponentAndAdmitted() throws Exception {
		ArtifactData ad = artifact();
		stubCommon(ad);
		ReleaseData rd = mock(ReleaseData.class);
		when(rd.getComponent()).thenReturn(component);
		when(sharedReleaseService.gatherReleasesForArtifact(artifactUuid, org)).thenReturn(List.of(rd));

		assertSame(ad, fetcher.getArtifact(artifactUuid.toString()));

		verify(authorizationService).isUserAuthorizedForAnyObjectGraphQL(eq(ud), eq(PermissionFunction.RESOURCE),
				eq(PermissionScope.COMPONENT), eq(Set.of(component)), any(), any());
		verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(any(), any(), any(), any(), any(),
				any());
	}

	/** Held by a release: a caller refused on the component check never falls back to the organisation. */
	@Test
	void aComponentScopeRefusalIsNotRetriedAtOrganizationScope() throws Exception {
		ArtifactData ad = artifact();
		stubCommon(ad);
		ReleaseData rd = mock(ReleaseData.class);
		when(rd.getComponent()).thenReturn(component);
		when(sharedReleaseService.gatherReleasesForArtifact(artifactUuid, org)).thenReturn(List.of(rd));
		doThrow(new AccessDeniedException("Not authorized")).when(authorizationService)
				.isUserAuthorizedForAnyObjectGraphQL(eq(ud), eq(PermissionFunction.RESOURCE),
						eq(PermissionScope.COMPONENT), eq(Set.of(component)), any(), any());

		assertThrows(AccessDeniedException.class, () -> fetcher.getArtifact(artifactUuid.toString()));

		verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(any(), any(), any(), any(), any(),
				any());
	}

	/**
	 * F-23/F-31 shape, orphan artifact (no release, no system tags: a session-attached artifact or a
	 * signature sub-artifact): {@code holdersOf} answers {@code Holders(empty, true)}, so getArtifact asks
	 * the ORGANIZATION check, never the component one over the empty set. A COMPONENT-scope-only grant
	 * elsewhere does not satisfy that check and is refused (403); an org admin is let through (200).
	 */
	@Test
	void anOrphanArtifactAsksOrganizationScopeAComponentOnlyGrantRefusedAnOrgAdminAdmitted() throws Exception {
		ArtifactData ad = artifact();
		stubCommon(ad);
		when(sharedReleaseService.gatherReleasesForArtifact(artifactUuid, org)).thenReturn(List.of());
		doThrow(new AccessDeniedException("Not authorized")).when(authorizationService)
				.isUserAuthorizedForObjectGraphQL(eq(ud), eq(PermissionFunction.RESOURCE),
						eq(PermissionScope.ORGANIZATION), eq(org), any(), any());

		// a caller whose only grant is COMPONENT-scope, elsewhere: the orphan artifact has no component to
		// ask about, so the organisation check runs and refuses it.
		assertThrows(AccessDeniedException.class, () -> fetcher.getArtifact(artifactUuid.toString()));
		verify(authorizationService, never()).isUserAuthorizedForAnyObjectGraphQL(any(), any(), any(), any(), any(),
				any());

		// an org admin: the same organisation check, this time let through.
		reset(authorizationService);
		assertSame(ad, fetcher.getArtifact(artifactUuid.toString()));
		verify(authorizationService).isUserAuthorizedForObjectGraphQL(eq(ud), eq(PermissionFunction.RESOURCE),
				eq(PermissionScope.ORGANIZATION), eq(org), any(), any());
		verify(authorizationService, never()).isUserAuthorizedForAnyObjectGraphQL(any(), any(), any(), any(), any(),
				any());
	}
}
