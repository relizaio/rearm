/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.ServletWebRequest;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ReleaseData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ArtifactDownloadAccessService;
import io.reliza.service.ArtifactService;
import io.reliza.service.AuthorizationService;
import io.reliza.service.AuthorizationService.FreeformKeyVerification;
import io.reliza.service.AuthorizationService.VerifiedProgrammaticKey;
import io.reliza.service.DownloadLogService;
import io.reliza.service.SharedArtifactService;
import io.reliza.service.SharedReleaseService;
import io.reliza.service.SupportInjectionService;
import io.reliza.service.UserService;
import jakarta.servlet.http.HttpServletResponse;
import reactor.core.publisher.Mono;

/**
 * TPF-12: the four artifact download handlers authenticate before they look the artifact up, so
 * the status never tells a caller who may not download an artifact whether its uuid exists.
 * No credential or one that does not verify is a 401 with no lookup; a verified caller on a sealed
 * or unlicensed instance reads 503 for every uuid; a verified caller reads the same 403 for an
 * unknown artifact and for one they may not download; an unknown version stays a 404, answered
 * only after authorization. Every case runs against both handlers of its pair. The case numbers
 * are those of the TPF-12 design, section 4.1.
 */
class ArtifactWsAuthenticationOrderTest {

	@FunctionalInterface
	private interface Handler {
		Mono<ResponseEntity<byte[]>> call(UUID uuid, Integer version) throws Exception;
	}

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
	private final byte[] served = "served".getBytes(StandardCharsets.UTF_8);

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
		// the user rule lives in main's shared ArtifactDownloadAccessService (SBOM score query too); wired to the same mock
		ArtifactDownloadAccessService access = new ArtifactDownloadAccessService();
		ReflectionTestUtils.setField(access, "authorizationService", authorizationService);
		ReflectionTestUtils.setField(ws, "artifactDownloadAccessService", access);
		ReflectionTestUtils.setField(ws, "userService", userService);
		ReflectionTestUtils.setField(ws, "downloadLogService", downloadLogService);
		ReflectionTestUtils.setField(ws, "supportInjectionService", mock(SupportInjectionService.class));

		user = mock(UserData.class);
		when(user.getUuid()).thenReturn(UUID.randomUUID());
		when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class))).thenReturn(Optional.of(user));
		SecurityContext ctx = mock(SecurityContext.class);
		when(ctx.getAuthentication()).thenReturn(mock(JwtAuthenticationToken.class));
		SecurityContextHolder.setContext(ctx);
		when(request.getRequest()).thenReturn(new MockHttpServletRequest());
		when(sharedArtifactService.downloadRawArtifact(any())).thenReturn(Mono.just(ResponseEntity.ok(served)));
		when(sharedArtifactService.downloadArtifact(any(), any(), any())).thenReturn(Mono.just(ResponseEntity.ok(served)));
		when(authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(any(), any(), any(), any(), anyCollection(), any()))
			.thenReturn(KEY);
		when(authorizationService.isFreeformKeyAuthorizedForAnyObjectGraphQL(any(), any(), any(), anySet(), anyCollection()))
			.thenReturn(KEY);
	}

	/** What an authorized key's check returns: the attribution its download is logged under (main's SCORE-17 log row). */
	private static final FreeformKeyVerification KEY = new FreeformKeyVerification(
		WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "127.0.0.1"), UUID.randomUUID(), UUID.randomUUID());

	@AfterEach
	void clearContext() {
		SecurityContextHolder.clearContext();
	}

	private Map<String, Handler> programmatic() {
		return Map.of(
			"downloadArtifactProgrammatic",
			(uuid, version) -> ws.downloadArtifactProgrammatic(headers, uuid, version, null, null, null, request, response),
			"downloadRawArtifactProgrammatic",
			(uuid, version) -> ws.downloadRawArtifactProgrammatic(headers, uuid, version, request, response));
	}

	private Map<String, Handler> manual() {
		return Map.of(
			"downloadArtifact",
			(uuid, version) -> ws.downloadArtifact(headers, uuid, version, null, null, null, request, null, response),
			"downloadRawArtifact",
			(uuid, version) -> ws.downloadRawArtifact(headers, uuid, version, request, null, response));
	}

	private ArtifactData artifact(List<ReleaseData> heldBy) {
		ArtifactData ad = new ArtifactData();
		UUID uuid = UUID.randomUUID();
		ReflectionTestUtils.setField(ad, "uuid", uuid);
		ad.setOrg(org);
		ad.setType(ArtifactType.BOM);
		when(artifactService.getArtifactData(uuid)).thenReturn(Optional.of(ad));
		when(sharedReleaseService.gatherReleasesForArtifact(uuid, org)).thenReturn(heldBy);
		return ad;
	}

	private UUID unknown() {
		UUID uuid = UUID.randomUUID();
		when(artifactService.getArtifactData(uuid)).thenReturn(Optional.empty());
		return uuid;
	}

	private ReleaseData releaseOfComponent(UUID component) {
		ReleaseData rd = new ReleaseData();
		ReflectionTestUtils.setField(rd, "component", component);
		return rd;
	}

	/** A key that verifies; the verified principal is a different object from the raw parse, as for a Basic key. */
	private AuthHeaderParse verifiedKey() {
		AuthHeaderParse ahp = mock(AuthHeaderParse.class);
		AuthHeaderParse principal = mock(AuthHeaderParse.class);
		when(authorizationService.authenticateProgrammatic(any(), any())).thenReturn(ahp);
		when(authorizationService.verifyProgrammaticKey(ahp)).thenReturn(new VerifiedProgrammaticKey(principal, org));
		return principal;
	}

	private void sealed() throws RelizaException {
		doThrow(new RelizaException("System is sealed. Please unseal the system first."))
			.when(authorizationService).validateSystemOperational(CallType.READ);
	}

	private void clearAll() {
		clearInvocations(artifactService, sharedArtifactService, sharedReleaseService, authorizationService,
			userService, downloadLogService);
	}

	private static ResponseEntity<byte[]> run(Handler h, UUID uuid, Integer version) throws Exception {
		ResponseEntity<byte[]> re = h.call(uuid, version).block();
		assertNotNull(re);
		return re;
	}

	private void assertUnauthorized(ResponseEntity<byte[]> re, String challenge, String body, String label) {
		assertEquals(HttpStatus.UNAUTHORIZED, re.getStatusCode(), label);
		assertEquals(challenge, re.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE), label);
		assertEquals("no-store", re.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL), label);
		assertArrayEquals(body.getBytes(StandardCharsets.UTF_8), re.getBody(), label);
	}

	private void assertNothingLookedUpOrServed() throws Exception {
		verify(artifactService, never()).getArtifactData(any());
		verify(authorizationService, never()).validateSystemOperational(any());
		assertNoAuthorizeCall();
		assertNothingServedOrLogged();
	}

	private void assertNoAuthorizeCall() throws Exception {
		verify(authorizationService, never()).isFreeformKeyAuthorizedForObjectGraphQL(any(), any(), any(), any(), anyCollection(), any());
		verify(authorizationService, never()).isFreeformKeyAuthorizedForAnyObjectGraphQL(any(), any(), any(), anySet(), anyList());
		verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(any(), any(), any(), any(), anyCollection(), any());
		verify(authorizationService, never()).isUserAuthorizedForAnyObjectGraphQL(any(), any(), any(), anySet(), anyCollection(), any());
	}

	private void assertNothingServedOrLogged() throws Exception {
		verify(sharedArtifactService, never()).downloadArtifact(any(), any(), any());
		verify(sharedArtifactService, never()).downloadRawArtifact(any());
		verify(downloadLogService, never()).createDownloadLog(any(), any(), any(), any(), any(), any());
	}

	// ---- programmatic pair ----

	@Test
	void case1NoCredentialAndAnUnknownUuidIsUnauthorizedBeforeAnyLookup() throws Exception {
		when(authorizationService.authenticateProgrammatic(any(), any())).thenReturn(null);
		UUID uuid = unknown();
		for (var e : programmatic().entrySet()) {
			clearAll();
			assertUnauthorized(run(e.getValue(), uuid, null), ProgrammaticAuthenticationFilter.NO_CREDENTIAL_CHALLENGE,
				ProgrammaticAuthenticationFilter.NO_CREDENTIAL_BODY, e.getKey());
			verify(authorizationService, never()).verifyProgrammaticKey(any());
			assertNothingLookedUpOrServed();
		}
	}

	@Test
	void case2NoCredentialAndAKnownUuidIsTheSameUnauthorized() throws Exception {
		when(authorizationService.authenticateProgrammatic(any(), any())).thenReturn(null);
		ArtifactData ad = artifact(List.of());
		for (var e : programmatic().entrySet()) {
			clearAll();
			assertUnauthorized(run(e.getValue(), ad.getUuid(), null), ProgrammaticAuthenticationFilter.NO_CREDENTIAL_CHALLENGE,
				ProgrammaticAuthenticationFilter.NO_CREDENTIAL_BODY, e.getKey());
			verify(authorizationService, never()).verifyProgrammaticKey(any());
			assertNothingLookedUpOrServed();
		}
	}

	@Test
	void case3ACredentialThatDoesNotVerifyIsUnauthorizedForUnknownAndKnown() throws Exception {
		AuthHeaderParse ahp = mock(AuthHeaderParse.class);
		when(authorizationService.authenticateProgrammatic(any(), any())).thenReturn(ahp);
		when(authorizationService.verifyProgrammaticKey(ahp)).thenThrow(new AccessDeniedException("Key unauthorized"));
		ArtifactData ad = artifact(List.of());
		UUID missing = unknown();
		for (var e : programmatic().entrySet()) {
			for (UUID uuid : List.of(missing, ad.getUuid())) {
				clearAll();
				assertUnauthorized(run(e.getValue(), uuid, null), ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE,
					ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, e.getKey());
				assertNothingLookedUpOrServed();
			}
		}
	}

	@Test
	void case4AVerifiedKeyAndAnUnknownUuidIsForbidden() throws Exception {
		verifiedKey();
		UUID uuid = unknown();
		for (var e : programmatic().entrySet()) {
			clearAll();
			ResponseEntity<byte[]> re = run(e.getValue(), uuid, null);
			assertEquals(HttpStatus.FORBIDDEN, re.getStatusCode(), e.getKey());
			assertNoAuthorizeCall();
			assertNothingServedOrLogged();
		}
	}

	@Test
	void case5AVerifiedKeyRefusedOnAKnownArtifactReadsExactlyLikeAnUnknownOne() throws Exception {
		verifiedKey();
		ArtifactData ad = artifact(List.of(releaseOfComponent(UUID.randomUUID())));
		UUID missing = unknown();
		when(authorizationService.isFreeformKeyAuthorizedForAnyObjectGraphQL(any(), any(), any(), anySet(), anyList()))
			.thenThrow(new AccessDeniedException("FreeForm key not authorized for this resource"));
		for (var e : programmatic().entrySet()) {
			clearAll();
			ResponseEntity<byte[]> unknownRe = run(e.getValue(), missing, null);
			ResponseEntity<byte[]> forbiddenRe = run(e.getValue(), ad.getUuid(), null);
			assertEquals(HttpStatus.FORBIDDEN, forbiddenRe.getStatusCode(), e.getKey());
			assertEquals(unknownRe.getStatusCode(), forbiddenRe.getStatusCode(), e.getKey());
			assertEquals(unknownRe.getHeaders(), forbiddenRe.getHeaders(), e.getKey());
			assertNull(forbiddenRe.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE), e.getKey());
			assertEquals("no-store", forbiddenRe.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL), e.getKey());
			assertNull(forbiddenRe.getBody(), e.getKey());
			assertNothingServedOrLogged();
		}
	}

	@Test
	void case6AnAuthorizedKeyIsServedAndAuthorizedWithTheVerifiedPrincipal() throws Exception {
		AuthHeaderParse principal = verifiedKey();
		UUID component = UUID.randomUUID();
		ArtifactData onRelease = artifact(List.of(releaseOfComponent(component)));
		ArtifactData orphan = artifact(List.of());
		for (var e : programmatic().entrySet()) {
			clearAll();
			assertEquals(HttpStatus.OK, run(e.getValue(), onRelease.getUuid(), null).getStatusCode(), e.getKey());
			verify(authorizationService).isFreeformKeyAuthorizedForAnyObjectGraphQL(principal, PermissionFunction.ARTIFACT_DOWNLOAD,
				PermissionScope.COMPONENT, Set.of(component), List.of(onRelease));
			// logged like a manual download, after authorization, under the key's attribution
			InOrder order = inOrder(authorizationService, downloadLogService);
			order.verify(authorizationService).isFreeformKeyAuthorizedForAnyObjectGraphQL(any(), any(), any(), anySet(), anyCollection());
			order.verify(downloadLogService).createDownloadLog(eq(org), any(), any(), eq(onRelease.getUuid()), eq(KEY.whoUpdated()), any());

			clearAll();
			assertEquals(HttpStatus.OK, run(e.getValue(), orphan.getUuid(), null).getStatusCode(), e.getKey());
			verify(authorizationService).isFreeformKeyAuthorizedForObjectGraphQL(principal, PermissionFunction.ARTIFACT_DOWNLOAD,
				PermissionScope.ORGANIZATION, org, List.of(orphan), CallType.READ);
			verify(authorizationService, never()).isFreeformKeyAuthorizedForAnyObjectGraphQL(any(), any(), any(), anySet(), anyList());
		}
	}

	@Test
	void case7AnAuthorizedKeyAndAnUnknownVersionIsNotFound() throws Exception {
		verifiedKey();
		ArtifactData ad = artifact(List.of());
		when(artifactService.getArtifactDataByVersion(ad.getUuid(), 99)).thenReturn(Optional.empty());
		for (var e : programmatic().entrySet()) {
			clearAll();
			assertEquals(HttpStatus.NOT_FOUND, run(e.getValue(), ad.getUuid(), 99).getStatusCode(), e.getKey());
			assertNothingServedOrLogged();
		}
	}

	@Test
	void case13AVerifiedKeyOnASealedSystemReadsTheSameUnavailableForEveryUuid() throws Exception {
		verifiedKey();
		sealed();
		ArtifactData ad = artifact(List.of());
		UUID missing = unknown();
		for (var e : programmatic().entrySet()) {
			clearAll();
			ResponseEntity<byte[]> unknownRe = run(e.getValue(), missing, null);
			ResponseEntity<byte[]> knownRe = run(e.getValue(), ad.getUuid(), null);
			assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unknownRe.getStatusCode(), e.getKey());
			assertEquals(HttpStatus.SERVICE_UNAVAILABLE, knownRe.getStatusCode(), e.getKey());
			assertEquals(unknownRe.getHeaders(), knownRe.getHeaders(), e.getKey());
			assertEquals("no-store", knownRe.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL), e.getKey());
			assertFalse(knownRe.getHeaders().containsHeader(HttpHeaders.WWW_AUTHENTICATE), e.getKey());
			verify(artifactService, never()).getArtifactData(any());
			assertNoAuthorizeCall();
			assertNothingServedOrLogged();
			InOrder order = inOrder(authorizationService);
			order.verify(authorizationService).verifyProgrammaticKey(any());
			order.verify(authorizationService).validateSystemOperational(CallType.READ);
		}
	}

	// ---- manual pair ----

	@Test
	void case8AJwtThatResolvesToNoUserIsUnauthorizedForUnknownAndKnown() throws Exception {
		when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class))).thenReturn(Optional.empty());
		ArtifactData ad = artifact(List.of());
		UUID missing = unknown();
		for (var e : manual().entrySet()) {
			for (UUID uuid : List.of(missing, ad.getUuid())) {
				clearAll();
				assertUnauthorized(run(e.getValue(), uuid, null), ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE,
					ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, e.getKey());
				assertNothingLookedUpOrServed();
			}
		}
	}

	@Test
	void case9AUserAndAnUnknownUuidIsForbidden() throws Exception {
		UUID uuid = unknown();
		for (var e : manual().entrySet()) {
			clearAll();
			assertEquals(HttpStatus.FORBIDDEN, run(e.getValue(), uuid, null).getStatusCode(), e.getKey());
			assertNoAuthorizeCall();
			assertNothingServedOrLogged();
		}
	}

	@Test
	void case10AUserRefusedOnAKnownArtifactReadsExactlyLikeAnUnknownOne() throws Exception {
		ArtifactData ad = artifact(List.of(releaseOfComponent(UUID.randomUUID())));
		UUID missing = unknown();
		when(authorizationService.isUserAuthorizedForAnyObjectGraphQL(any(), any(), any(), anySet(), anyCollection(), any()))
			.thenThrow(new AccessDeniedException("Forbidden"));
		for (var e : manual().entrySet()) {
			clearAll();
			ResponseEntity<byte[]> unknownRe = run(e.getValue(), missing, null);
			ResponseEntity<byte[]> forbiddenRe = run(e.getValue(), ad.getUuid(), null);
			assertEquals(HttpStatus.FORBIDDEN, forbiddenRe.getStatusCode(), e.getKey());
			assertEquals(unknownRe.getStatusCode(), forbiddenRe.getStatusCode(), e.getKey());
			assertEquals(unknownRe.getHeaders(), forbiddenRe.getHeaders(), e.getKey());
			assertNull(forbiddenRe.getBody(), e.getKey());
			assertNothingServedOrLogged();
		}
	}

	@Test
	void case11AnAuthorizedUserIsServedAndTheDownloadIsLoggedAfterAuthorization() throws Exception {
		UUID component = UUID.randomUUID();
		ArtifactData ad = artifact(List.of(releaseOfComponent(component)));
		for (var e : manual().entrySet()) {
			clearAll();
			assertEquals(HttpStatus.OK, run(e.getValue(), ad.getUuid(), null).getStatusCode(), e.getKey());
			InOrder order = inOrder(authorizationService, downloadLogService);
			order.verify(authorizationService).isUserAuthorizedForAnyObjectGraphQL(user, PermissionFunction.ARTIFACT_DOWNLOAD,
				PermissionScope.COMPONENT, Set.of(component), List.of(ad), CallType.READ);
			order.verify(downloadLogService).createDownloadLog(eq(org), any(), any(), eq(ad.getUuid()), any(), any());
		}
	}

	@Test
	void case12AnAuthorizedUserAndAnUnknownVersionIsNotFound() throws Exception {
		ArtifactData ad = artifact(List.of());
		when(artifactService.getArtifactDataByVersion(ad.getUuid(), 99)).thenReturn(Optional.empty());
		for (var e : manual().entrySet()) {
			clearAll();
			assertEquals(HttpStatus.NOT_FOUND, run(e.getValue(), ad.getUuid(), 99).getStatusCode(), e.getKey());
			assertNothingServedOrLogged();
		}
	}

	@Test
	void case14AUserOnASealedSystemReadsTheSameUnavailableForEveryUuid() throws Exception {
		sealed();
		ArtifactData ad = artifact(List.of());
		UUID missing = unknown();
		for (var e : manual().entrySet()) {
			clearAll();
			ResponseEntity<byte[]> unknownRe = run(e.getValue(), missing, null);
			ResponseEntity<byte[]> knownRe = run(e.getValue(), ad.getUuid(), null);
			assertEquals(HttpStatus.SERVICE_UNAVAILABLE, unknownRe.getStatusCode(), e.getKey());
			assertEquals(HttpStatus.SERVICE_UNAVAILABLE, knownRe.getStatusCode(), e.getKey());
			assertEquals(unknownRe.getHeaders(), knownRe.getHeaders(), e.getKey());
			assertEquals("no-store", knownRe.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL), e.getKey());
			verify(artifactService, never()).getArtifactData(any());
			assertNoAuthorizeCall();
			assertNothingServedOrLogged();
		}
	}

	/** The window the second catch covers: the state changes between the handler's check and the helper's own. */
	@Test
	void aStateChangeInsideTheAuthorizeHelperIsReturnedNotThrown() throws Exception {
		AuthHeaderParse principal = verifiedKey();
		ArtifactData ad = artifact(List.of());
		RelizaException licence = new RelizaException("License is invalid or expired. Please upload a valid license.");
		when(authorizationService.isFreeformKeyAuthorizedForObjectGraphQL(eq(principal), any(), any(), any(), anyCollection(), any()))
			.thenThrow(licence);
		when(authorizationService.isUserAuthorizedForObjectGraphQL(eq(user), any(), any(), any(), anyCollection(), any()))
			.thenThrow(licence);
		for (Map<String, Handler> pair : List.of(programmatic(), manual())) {
			for (var e : pair.entrySet()) {
				clearAll();
				assertEquals(HttpStatus.SERVICE_UNAVAILABLE, run(e.getValue(), ad.getUuid(), null).getStatusCode(), e.getKey());
				assertNothingServedOrLogged();
			}
		}
	}
}
