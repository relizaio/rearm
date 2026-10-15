/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.ApiKeyData.ApiKeyStatus;
import io.reliza.model.CliSession;
import io.reliza.model.UserData;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.ApiTokenService.TokenClaims;
import io.reliza.service.CliSessionService;
import io.reliza.service.FederatedTrustRuleService;

/**
 * Every verified credential on the programmatic lane bumps the key's last access date through the
 * accumulator, whatever the resolver behind it records; a refused one does not (task t20261013-072815-2705).
 */
class ProgrammaticAuthenticationFilterLastAccessTest {

	private ApiTokenService apiTokenService;
	private ApiKeyService apiKeyService;
	private ApiKeyAccessService apiKeyAccessService;
	private CliSessionService cliSessionService;
	private ProgrammaticAuthenticationFilter filter;

	private final UUID org = UUID.randomUUID();
	private final UUID user = UUID.randomUUID();
	private final UUID sessionUuid = UUID.randomUUID();
	private ApiKey key;
	private ApiKeySecret secret;

	@BeforeEach
	void wire() {
		apiTokenService = mock(ApiTokenService.class);
		apiKeyService = mock(ApiKeyService.class);
		apiKeyAccessService = mock(ApiKeyAccessService.class);
		cliSessionService = mock(CliSessionService.class);
		filter = new ProgrammaticAuthenticationFilter(apiTokenService, apiKeyService, apiKeyAccessService, cliSessionService,
				mock(FederatedTrustRuleService.class));
		key = new ApiKey();
		key.setOrg(org);
		key.setObjectType(ApiTypeEnum.USER);
		key.setObjectUuid(user);
		key.setKeyOrder("k1");
		secret = new ApiKeySecret(1, "hash", true, ZonedDateTime.now(), null);
		withStatus(ApiKeyStatus.ACTIVE);
		when(apiKeyService.getApiKey(key.getUuid())).thenReturn(Optional.of(key));
	}

	private void withStatus(ApiKeyStatus status) {
		ApiKeyData akd = ApiKeyData.apiKeyDataFactory(org);
		akd.setStatus(status);
		akd.setSecrets(List.of(secret));
		key.setRecordData(Utils.dataToRecord(akd));
	}

	/** A token exchanged with the key's secret: bound to its fingerprint, no session. */
	private void secretToken() {
		TokenClaims claims = new TokenClaims(key.getUuid(), org, ApiTokenService.AUD_PROGRAMMATIC, ApiTokenService.fingerprint(secret), 1,
				"jti", Instant.now().plusSeconds(600));
		when(apiTokenService.verify("t", ApiTokenService.AUD_PROGRAMMATIC)).thenReturn(Optional.of(claims));
	}

	/** The filter covers the GraphQL path only when the request URI is set, or shouldNotFilter skips it. */
	private static MockHttpServletRequest request(String authorization) {
		MockHttpServletRequest req = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		req.setRequestURI(ProgrammaticGraphQlConfig.PATH);
		req.setServletPath(ProgrammaticGraphQlConfig.PATH);
		req.setRemoteAddr("198.51.100.31");
		req.addHeader(HttpHeaders.AUTHORIZATION, authorization);
		return req;
	}

	private static String basic(String credentials) {
		return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
	}

	private MockHttpServletResponse run(MockHttpServletRequest req, MockFilterChain chain) throws Exception {
		MockHttpServletResponse resp = new MockHttpServletResponse();
		filter.doFilter(req, resp, chain);
		return resp;
	}

	private void sessionToken() {
		TokenClaims claims = new TokenClaims(key.getUuid(), org, ApiTokenService.AUD_PROGRAMMATIC, "fp", null, "jti",
				Instant.now().plusSeconds(600), sessionUuid, user, null, null);
		when(apiTokenService.verify("t", ApiTokenService.AUD_PROGRAMMATIC)).thenReturn(Optional.of(claims));
	}

	/** The session's active owner, a member of the given orgs. */
	private void sessionOwnedBy(UUID owner, Set<UUID> orgs) {
		sessionOwnedBy(owner, orgs, false);
	}

	private void sessionOwnedBy(UUID owner, Set<UUID> orgs, boolean globalAdmin) {
		CliSession session = new CliSession();
		when(cliSessionService.usable(sessionUuid, "fp")).thenReturn(Optional.of(session));
		UserData ud = mock(UserData.class);
		when(ud.getUuid()).thenReturn(owner);
		when(ud.getOrganizations()).thenReturn(orgs);
		when(ud.isGlobalAdmin()).thenReturn(globalAdmin);
		when(cliSessionService.owner(session)).thenReturn(Optional.of(ud));
	}

	@Test
	void aVerifiedSessionBearerTouchesTheKeyOnce() throws Exception {
		sessionToken();
		sessionOwnedBy(user, Set.of(org));
		MockFilterChain chain = new MockFilterChain();

		MockHttpServletResponse resp = run(request("Bearer t"), chain);

		assertEquals(200, resp.getStatus());
		assertNotNull(chain.getRequest(), "passed on to the resolver");
		verify(apiKeyAccessService, times(1)).touchLastAccess(key.getUuid());
	}

	/** A session whose user left the org, or no longer owns or holds the key, is refused whether or not it was revoked. */
	@Test
	void aSessionItsUserNoLongerBacksIsRefused() throws Exception {
		sessionToken();
		sessionOwnedBy(user, Set.of());
		MockFilterChain left = new MockFilterChain();
		assertEquals(401, run(request("Bearer t"), left).getStatus(), "the owner left the org");
		assertNull(left.getRequest());

		sessionOwnedBy(UUID.randomUUID(), Set.of(org));
		MockFilterChain notTheirs = new MockFilterChain();
		assertEquals(401, run(request("Bearer t"), notTheirs).getStatus(), "the key belongs to someone else");
		assertNull(notTheirs.getRequest());
		verify(apiKeyAccessService, never()).touchLastAccess(any());
	}

	/** A global admin outside the org, and the holder of a Free Form key, pass. */
	@Test
	void aGlobalAdminAndAFreeFormHolderPass() throws Exception {
		sessionToken();
		sessionOwnedBy(user, Set.of(), true);
		MockFilterChain admin = new MockFilterChain();
		assertEquals(200, run(request("Bearer t"), admin).getStatus(), "a global admin outside the org");
		assertNotNull(admin.getRequest());

		UUID holder = UUID.randomUUID();
		key.setObjectType(ApiTypeEnum.FREEFORM);
		key.setObjectUuid(org);
		ApiKeyData akd = ApiKeyData.dataFromRecord(key);
		akd.setHolder(holder);
		key.setRecordData(Utils.dataToRecord(akd));
		sessionOwnedBy(holder, Set.of(org));
		MockFilterChain held = new MockFilterChain();
		assertEquals(200, run(request("Bearer t"), held).getStatus(), "the holder of a Free Form key");
		assertNotNull(held.getRequest());
	}

	@Test
	void aRefusedBearerDoesNotTouch() throws Exception {
		sessionToken();
		when(cliSessionService.usable(sessionUuid, "fp")).thenReturn(Optional.empty());
		MockFilterChain chain = new MockFilterChain();

		MockHttpServletResponse resp = run(request("Bearer t"), chain);

		assertEquals(401, resp.getStatus());
		assertNull(chain.getRequest());
		verify(apiKeyAccessService, never()).touchLastAccess(any());
	}

	@Test
	void aVerifiedSecretBearerTouchesTheKeyOnce() throws Exception {
		secretToken();
		MockFilterChain chain = new MockFilterChain();

		MockHttpServletResponse resp = run(request("Bearer t"), chain);

		assertEquals(200, resp.getStatus());
		assertNotNull(chain.getRequest());
		verify(apiKeyAccessService, times(1)).touchLastAccess(key.getUuid());
	}

	@Test
	void aBearerOfAnInactiveKeyDoesNotTouch() throws Exception {
		withStatus(ApiKeyStatus.INACTIVE);
		secretToken();

		MockHttpServletResponse resp = run(request("Bearer t"), new MockFilterChain());

		assertEquals(401, resp.getStatus());
		verify(apiKeyAccessService, never()).touchLastAccess(any());
	}

	@Test
	void aBearerOfARetiredSecretDoesNotTouch() throws Exception {
		secretToken();
		secret = new ApiKeySecret(1, "another-hash", true, ZonedDateTime.now(), null);
		withStatus(ApiKeyStatus.ACTIVE);

		MockHttpServletResponse resp = run(request("Bearer t"), new MockFilterChain());

		assertEquals(401, resp.getStatus());
		verify(apiKeyAccessService, never()).touchLastAccess(any());
	}

	@Test
	void aVerifiedBasicTouchesTheKeyOnceThroughTheVerifier() throws Exception {
		when(apiKeyService.isMatchingApiKey(any())).thenReturn(key.getUuid());
		MockFilterChain chain = new MockFilterChain();

		MockHttpServletResponse resp = run(request(basic("USER__" + user + "__ord__k1:the-secret")), chain);

		assertEquals(200, resp.getStatus());
		assertNotNull(chain.getRequest());
		verify(apiKeyAccessService, times(1)).touchLastAccess(key.getUuid());
	}

	@Test
	void aRefusedBasicDoesNotTouch() throws Exception {
		when(apiKeyService.isMatchingApiKey(any())).thenReturn(null);
		MockHttpServletResponse resp = run(request(basic("USER__" + user + "__ord__k1:wrong")), new MockFilterChain());

		assertEquals(401, resp.getStatus());
		verify(apiKeyAccessService, never()).touchLastAccess(any());
	}
}
