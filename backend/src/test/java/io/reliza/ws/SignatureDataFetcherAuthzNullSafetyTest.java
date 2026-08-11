/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.reliza.model.CommitterData;
import io.reliza.model.RelizaObject;
import io.reliza.model.SigningKeyData.SigningKeyOwnerType;
import io.reliza.model.UserData;
import io.reliza.service.AuthorizationService;
import io.reliza.service.CommitterService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SignatureVerificationService;
import io.reliza.service.SigningKeyService;
import io.reliza.service.UserService;

/**
 * Regression guard for the {@code List.of(ro)} / {@code List.of(od.orElse(null))}
 * NPE in {@link SignatureDataFetcher}'s read and write resolvers — the same
 * latent bug fixed in {@code AgentDataFetcher} (see
 * {@link AgentDataFetcherAuthzNullSafetyTest}).
 *
 * <p>When the scoping uuid (committer / signing-key / verification / org)
 * doesn't resolve, the resolved {@code RelizaObject} is {@code null}. The
 * pre-fix code fed that null into {@code List.of(...)}, which throws
 * {@link NullPointerException} <em>before</em> the authorization check ran —
 * GraphQL then surfaced an opaque {@code SERVICE_ERROR} for what is really an
 * unauthorized / not-found uuid. The fix swaps in the null-tolerant
 * {@code Collections.singletonList(...)} so the authz call is actually
 * reached: {@code getMatchingOrg} maps a null-only collection to a null org
 * and the request is denied cleanly (an {@link AccessDeniedException}, mapped
 * to PERMISSION_DENIED, not SERVICE_ERROR).
 *
 * <p>These tests pin both halves: (1) the authz call is reached with a
 * one-element, null-bearing collection rather than NPE-ing first, and
 * (2) a denial from the authz layer propagates as {@link AccessDeniedException}.
 */
class SignatureDataFetcherAuthzNullSafetyTest {

	private AuthorizationService authorizationService;
	private UserService userService;
	private GetOrganizationService getOrganizationService;
	private CommitterService committerService;
	private SigningKeyService signingKeyService;
	private SignatureVerificationService signatureVerificationService;
	private SignatureDataFetcher fetcher;

	@BeforeEach
	void wireMocks() throws Exception {
		authorizationService = mock(AuthorizationService.class);
		userService = mock(UserService.class);
		getOrganizationService = mock(GetOrganizationService.class);
		committerService = mock(CommitterService.class);
		signingKeyService = mock(SigningKeyService.class);
		signatureVerificationService = mock(SignatureVerificationService.class);

		when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class)))
				.thenReturn(Optional.of(mock(UserData.class)));

		fetcher = new SignatureDataFetcher();
		inject("authorizationService", authorizationService);
		inject("userService", userService);
		inject("getOrganizationService", getOrganizationService);
		inject("committerService", committerService);
		inject("signingKeyService", signingKeyService);
		inject("signatureVerificationService", signatureVerificationService);

		SecurityContext ctx = mock(SecurityContext.class);
		when(ctx.getAuthentication()).thenReturn(mock(JwtAuthenticationToken.class));
		SecurityContextHolder.setContext(ctx);
	}

	private void inject(String field, Object value) throws Exception {
		Field f = SignatureDataFetcher.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(fetcher, value);
	}

	/**
	 * Asserts the authz helper was invoked exactly once with a one-element
	 * collection whose single element is null — the null-safe shape the fix
	 * introduced. Under the pre-fix {@code List.of(...)} this point was never
	 * reached because construction NPE'd first.
	 */
	private void assertReachedAuthzWithNullScope() throws Exception {
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<RelizaObject>> rosCaptor =
				ArgumentCaptor.forClass(Collection.class);
		verify(authorizationService).isUserAuthorizedForObjectGraphQL(
				any(), any(), any(), any(), rosCaptor.capture(), any());
		Collection<RelizaObject> ros = rosCaptor.getValue();
		assertEquals(1, ros.size(),
				"authz should receive a single-element scope collection");
		assertNull(ros.iterator().next(),
				"unresolved uuid must surface as a null scope element, not NPE on List.of(null)");
	}

	private void denyAuthz() throws Exception {
		doThrow(new AccessDeniedException("Forbidden"))
				.when(authorizationService).isUserAuthorizedForObjectGraphQL(
						any(), any(), any(), any(), any(), any());
	}

	// ---------- read resolvers (uuid → entity → entity.getOrg()) ----------

	@Test
	void committerUnresolvedUuidReachesAuthzWithoutNpe() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(committerService.getCommitterData(uuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.committer(uuid));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void committerUnauthorizedUuidPropagatesAccessDenied() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(committerService.getCommitterData(uuid)).thenReturn(Optional.empty());
		denyAuthz();

		assertThrows(AccessDeniedException.class, () -> fetcher.committer(uuid));
	}

	@Test
	void signingKeyUnresolvedUuidReachesAuthzWithoutNpe() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(signingKeyService.getSigningKeyData(uuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.signingKey(uuid));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void signatureVerificationUnresolvedUuidReachesAuthzWithoutNpe() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(signatureVerificationService.getData(uuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.signatureVerification(uuid));
		assertReachedAuthzWithNullScope();
	}

	// ---------- read resolvers (orgUuid → org row) ----------

	@Test
	void committersOfOrgUnresolvedOrgReachesAuthzWithoutNpe() throws Exception {
		UUID orgUuid = UUID.randomUUID();
		when(getOrganizationService.getOrganizationData(orgUuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.committersOfOrg(orgUuid));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void signingKeysOfOwnerUnresolvedOrgReachesAuthzWithoutNpe() throws Exception {
		UUID orgUuid = UUID.randomUUID();
		when(getOrganizationService.getOrganizationData(orgUuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.signingKeysOfOwner(orgUuid, SigningKeyOwnerType.AGENT, UUID.randomUUID()));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void signingKeysOfOrgUnresolvedOrgReachesAuthzWithoutNpe() throws Exception {
		UUID orgUuid = UUID.randomUUID();
		when(getOrganizationService.getOrganizationData(orgUuid)).thenReturn(Optional.empty());

		assertDoesNotThrow(() -> fetcher.signingKeysOfOrg(orgUuid));
		assertReachedAuthzWithNullScope();
	}

	// ---------- write / admin resolvers ----------
	// These build a WhoUpdated audit stamp *after* the authz call, so we
	// drive them through the deny path: it both proves AccessDeniedException
	// propagation and captures the null-bearing scope (Mockito records the
	// invocation before the stubbed throw fires).

	@Test
	void upsertCommitterUnresolvedOrgDeniesWithNullScope() throws Exception {
		UUID orgUuid = UUID.randomUUID();
		when(getOrganizationService.getOrganizationData(orgUuid)).thenReturn(Optional.empty());
		denyAuthz();

		Map<String, Object> input = Map.of("org", orgUuid.toString());
		assertThrows(AccessDeniedException.class, () -> fetcher.upsertCommitter(input));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void enrollSigningKeyUnresolvedOrgDeniesWithNullScope() throws Exception {
		UUID orgUuid = UUID.randomUUID();
		when(getOrganizationService.getOrganizationData(orgUuid)).thenReturn(Optional.empty());
		denyAuthz();

		Map<String, Object> input = Map.of("org", orgUuid.toString());
		assertThrows(AccessDeniedException.class, () -> fetcher.enrollSigningKey(input));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void archiveCommitterUnresolvedUuidDeniesWithNullScope() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(committerService.getCommitterData(uuid)).thenReturn(Optional.empty());
		denyAuthz();

		assertThrows(AccessDeniedException.class, () -> fetcher.archiveCommitter(uuid));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void revokeSigningKeyUnresolvedUuidDeniesWithNullScope() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(signingKeyService.getSigningKeyData(uuid)).thenReturn(Optional.empty());
		denyAuthz();

		assertThrows(AccessDeniedException.class, () -> fetcher.revokeSigningKey(uuid));
		assertReachedAuthzWithNullScope();
	}

	@Test
	void updateSigningKeyIdentityUnresolvedUuidDeniesWithNullScope() throws Exception {
		UUID uuid = UUID.randomUUID();
		when(signingKeyService.getSigningKeyData(uuid)).thenReturn(Optional.empty());
		denyAuthz();

		assertThrows(AccessDeniedException.class, () -> fetcher.updateSigningKeyIdentity(uuid, "user@example.com"));
		assertReachedAuthzWithNullScope();
	}

	// ---------- happy-path sanity: a resolved entity still binds its real org ----------

	@Test
	void committerResolvedUuidBindsEntityOrg() throws Exception {
		UUID uuid = UUID.randomUUID();
		UUID orgUuid = UUID.randomUUID();
		CommitterData cd = mock(CommitterData.class);
		when(cd.getOrg()).thenReturn(orgUuid);
		when(committerService.getCommitterData(uuid)).thenReturn(Optional.of(cd));

		CommitterData result = fetcher.committer(uuid);
		assertEquals(cd, result);
		@SuppressWarnings("unchecked")
		ArgumentCaptor<Collection<RelizaObject>> rosCaptor =
				ArgumentCaptor.forClass(Collection.class);
		verify(authorizationService).isUserAuthorizedForObjectGraphQL(
				any(), any(), any(), eq(orgUuid), rosCaptor.capture(), any());
		assertEquals(cd, rosCaptor.getValue().iterator().next());
	}
}
