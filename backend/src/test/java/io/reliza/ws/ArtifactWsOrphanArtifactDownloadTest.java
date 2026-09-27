/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.ServletWebRequest;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ReleaseData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.DownloadLogService;
import io.reliza.service.SharedArtifactService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SupportInjectionService;
import io.reliza.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * The artifact byte endpoints authorized per component of the releases holding the artifact. An
 * artifact on no release -- an AI agent session's reports and BOMs, which sessions hold directly --
 * has no component, and the per-component check then refused everyone short of a global admin,
 * org admins included: the session page listed its artifacts and could not open one. Such an
 * artifact is now authorized at organization scope on its own org, for users and API keys alike,
 * while an artifact on a release keeps the per-component check.
 */
class ArtifactWsOrphanArtifactDownloadTest {

	private ArtifactService artifactService;
	private SharedArtifactService sharedArtifactService;
	private SharedReleaseService sharedReleaseService;
	private AuthorizationService authorizationService;
	private UserService userService;
	private DownloadLogService downloadLogService;
	private ArtifactWs ws;
	private UserData user;
	private final UUID org = UUID.randomUUID();
	private final HttpHeaders headers = new HttpHeaders();
	private final ServletWebRequest request = mock(ServletWebRequest.class);
	private final HttpServletResponse response = mock(HttpServletResponse.class);

	@BeforeEach
	void wireMocks() throws Exception {
		artifactService = mock(ArtifactService.class);
		sharedArtifactService = mock(SharedArtifactService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		authorizationService = mock(AuthorizationService.class);
		userService = mock(UserService.class);
		downloadLogService = mock(DownloadLogService.class);
		ws = new ArtifactWs();
		ReflectionTestUtils.setField(ws, "artifactService", artifactService);
		ReflectionTestUtils.setField(ws, "sharedArtifactService", sharedArtifactService);
		ReflectionTestUtils.setField(ws, "sharedReleaseService", sharedReleaseService);
		ReflectionTestUtils.setField(ws, "authorizationService", authorizationService);
		ReflectionTestUtils.setField(ws, "userService", userService);
		ReflectionTestUtils.setField(ws, "downloadLogService", downloadLogService);
		ReflectionTestUtils.setField(ws, "supportInjectionService", mock(SupportInjectionService.class));

		user = mock(UserData.class);
		when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class))).thenReturn(Optional.of(user));
		SecurityContext ctx = mock(SecurityContext.class);
		when(ctx.getAuthentication()).thenReturn(mock(JwtAuthenticationToken.class));
		SecurityContextHolder.setContext(ctx);
		when(sharedArtifactService.downloadRawArtifact(any())).thenReturn(Mono.empty());
		when(sharedArtifactService.downloadArtifact(any(), any(), any())).thenReturn(Mono.empty());
	}

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private ArtifactData artifact(List<ReleaseData> heldBy) {
		ArtifactData ad = new ArtifactData();
		UUID uuid = UUID.randomUUID();
		ReflectionTestUtils.setField(ad, "uuid", uuid);
		ad.setOrg(org);
		ad.setType(ArtifactType.AGENTIC_REPORT);
		when(artifactService.getArtifactData(uuid)).thenReturn(Optional.of(ad));
		when(sharedReleaseService.gatherReleasesForArtifact(uuid, org)).thenReturn(heldBy);
		return ad;
	}

	private ReleaseData releaseOfComponent(UUID component) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "component", component);
		return rd;
	}

	@Test
	void aSessionArtifactIsAuthorizedAtOrganizationScopeOnItsOwnOrg() throws Exception {
		ArtifactData ad = artifact(List.of());

		ws.downloadRawArtifact(headers, ad.getUuid(), null, request, null, response);

		verify(authorizationService).isUserAuthorizedForObjectGraphQL(user, PermissionFunction.ARTIFACT_DOWNLOAD,
			PermissionScope.ORGANIZATION, org, List.of(ad), CallType.READ);
		verify(authorizationService, never()).isUserAuthorizedForAnyObjectGraphQL(any(), any(), any(), anySet(), anyCollection(), any());
		verify(sharedArtifactService).downloadRawArtifact(ad);
	}

	@Test
	void theAugmentedDownloadOfASessionArtifactUsesTheSameRule() throws Exception {
		ArtifactData ad = artifact(List.of());

		ws.downloadArtifact(headers, ad.getUuid(), null, null, null, null, request, null, response);

		verify(authorizationService).isUserAuthorizedForObjectGraphQL(user, PermissionFunction.ARTIFACT_DOWNLOAD,
			PermissionScope.ORGANIZATION, org, List.of(ad), CallType.READ);
		verify(downloadLogService).createDownloadLog(any(), any(), any(), any(), any(), any());
		verify(sharedArtifactService).downloadArtifact(eq(ad), any(), any());
	}

	@Test
	void anArtifactOnAReleaseKeepsThePerComponentCheck() throws Exception {
		UUID component = UUID.randomUUID();
		ArtifactData ad = artifact(List.of(releaseOfComponent(component)));

		ws.downloadRawArtifact(headers, ad.getUuid(), null, request, null, response);

		verify(authorizationService).isUserAuthorizedForAnyObjectGraphQL(user, PermissionFunction.ARTIFACT_DOWNLOAD,
			PermissionScope.COMPONENT, Set.of(component), List.of(ad), CallType.READ);
		verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(any(), any(), any(), any(), anyCollection(), any());
	}

	/** A user without org-scope ARTIFACT_DOWNLOAD is still refused, before anything is served or logged. */
	@Test
	void aRefusalAtOrganizationScopeServesAndLogsNothing() throws Exception {
		ArtifactData ad = artifact(List.of());
		when(authorizationService.isUserAuthorizedForObjectGraphQL(any(), any(), any(), any(), anyCollection(), any()))
			.thenThrow(new AccessDeniedException("Forbidden"));

		assertThrows(AccessDeniedException.class,
			() -> ws.downloadRawArtifact(headers, ad.getUuid(), null, request, null, response));

		verify(sharedArtifactService, never()).downloadRawArtifact(any());
		verify(downloadLogService, never()).createDownloadLog(any(), any(), any(), any(), any(), any());
	}

	@Test
	void anApiKeyGetsTheSameOrganizationScopeRuleForASessionArtifact() throws Exception {
		ArtifactData ad = artifact(List.of());
		AuthHeaderParse ahp = mock(AuthHeaderParse.class);
		when(authorizationService.authenticateProgrammatic(any(), any())).thenReturn(ahp);

		ws.downloadRawArtifactProgrammatic(headers, ad.getUuid(), null, request, response);

		verify(authorizationService).isFreeformKeyAuthorizedForObjectGraphQL(ahp, PermissionFunction.ARTIFACT_DOWNLOAD,
			PermissionScope.ORGANIZATION, org, List.of(ad), CallType.READ);
		verify(authorizationService, never()).isFreeformKeyAuthorizedForAnyObjectGraphQL(any(), any(), any(), anySet(), anyList());
	}

	@Test
	void anApiKeyKeepsThePerComponentCheckForAnArtifactOnARelease() throws Exception {
		UUID component = UUID.randomUUID();
		ArtifactData ad = artifact(List.of(releaseOfComponent(component)));
		AuthHeaderParse ahp = mock(AuthHeaderParse.class);
		when(authorizationService.authenticateProgrammatic(any(), any())).thenReturn(ahp);

		ws.downloadArtifactProgrammatic(headers, ad.getUuid(), null, null, null, null, request, response);

		verify(authorizationService).isFreeformKeyAuthorizedForAnyObjectGraphQL(ahp, PermissionFunction.ARTIFACT_DOWNLOAD,
			PermissionScope.COMPONENT, Set.of(component), List.of(ad));
	}

	/** An API key without org-scope ARTIFACT_DOWNLOAD is refused too, before anything is served. */
	@Test
	void anApiKeyWithoutOrganizationScopeDownloadIsRefused() throws Exception {
		ArtifactData ad = artifact(List.of());
		when(authorizationService.authenticateProgrammatic(any(), any())).thenReturn(mock(AuthHeaderParse.class));
		when(authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(any(), any(), any(), any(), anyCollection(), any()))
			.thenThrow(new AccessDeniedException("FreeForm key not authorized for this resource"));

		assertThrows(AccessDeniedException.class,
			() -> ws.downloadRawArtifactProgrammatic(headers, ad.getUuid(), null, request, response));

		verify(sharedArtifactService, never()).downloadRawArtifact(any());
	}

	/**
	 * An unknown uuid is a 404 on every endpoint and consults no authorization at all. It was a 500
	 * on all four; returned rather than thrown, so an API-key caller gets the 404 too.
	 */
	@Test
	void anUnknownArtifactIsNotFound() throws Exception {
		UUID unknown = UUID.randomUUID();
		when(artifactService.getArtifactData(unknown)).thenReturn(Optional.empty());

		for (var result : List.of(
				ws.downloadRawArtifact(headers, unknown, null, request, null, response),
				ws.downloadArtifact(headers, unknown, null, null, null, null, request, null, response),
				ws.downloadRawArtifactProgrammatic(headers, unknown, null, request, response),
				ws.downloadArtifactProgrammatic(headers, unknown, null, null, null, null, request, response))) {
			assertEquals(HttpStatus.NOT_FOUND, result.block().getStatusCode());
		}
		verifyNoInteractions(authorizationService);
	}

	/** So is an unknown version of an artifact the caller may download. */
	@Test
	void anUnknownVersionIsNotFound() throws Exception {
		ArtifactData ad = artifact(List.of());
		when(artifactService.getArtifactDataByVersion(ad.getUuid(), 99)).thenReturn(Optional.empty());

		when(authorizationService.authenticateProgrammatic(any(), any())).thenReturn(mock(AuthHeaderParse.class));

		assertEquals(HttpStatus.NOT_FOUND,
			ws.downloadRawArtifact(headers, ad.getUuid(), 99, request, null, response).block().getStatusCode());
		assertEquals(HttpStatus.NOT_FOUND,
			ws.downloadRawArtifactProgrammatic(headers, ad.getUuid(), 99, request, response).block().getStatusCode());
		verify(sharedArtifactService, never()).downloadRawArtifact(any());
	}
}
