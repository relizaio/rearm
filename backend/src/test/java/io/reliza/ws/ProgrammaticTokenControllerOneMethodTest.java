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
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.OAuthErrors;
import io.reliza.common.OAuthQueryGuard;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.CliSessionService;
import io.reliza.service.FederatedAssertionService;
import io.reliza.service.FederatedTrustRuleService;

/**
 * TPF-50: the token endpoint refuses a request that carries an Authorization header together with a client_id or
 * client_secret form field (one client authentication method per request, RFC 6749 section 2.3), on every grant,
 * after the query-string and repeat guards and before any parse, key lookup or grant work. Spring-free: the
 * controller is constructed with mocks and called directly, as the container would call it (the method reads only
 * the query string, the parameter map, the Authorization header and the remote address).
 */
public class ProgrammaticTokenControllerOneMethodTest {

	private static final String ID = "FREEFORM__" + UUID.randomUUID() + "__ord__" + UUID.randomUUID();
	private static final String OTHER = "FREEFORM__" + UUID.randomUUID() + "__ord__" + UUID.randomUUID();
	private static final String BASIC = "Basic " + Base64.getEncoder().encodeToString((ID + ":header-secret").getBytes(StandardCharsets.UTF_8));
	private static final String ORG = UUID.randomUUID().toString();

	private ApiKeyService apiKeyService;
	private ApiKeyAccessService apiKeyAccessService;
	private ApiTokenService apiTokenService;
	private CliSessionService cliSessionService;
	private FederatedTrustRuleService federatedTrustRuleService;
	private ProgrammaticTokenController controller;

	@BeforeEach
	void setUp() {
		apiKeyService = mock(ApiKeyService.class);
		apiTokenService = mock(ApiTokenService.class);
		apiKeyAccessService = mock(ApiKeyAccessService.class);
		cliSessionService = mock(CliSessionService.class);
		federatedTrustRuleService = mock(FederatedTrustRuleService.class);
		controller = new ProgrammaticTokenController(apiKeyService, apiTokenService, apiKeyAccessService);
		ReflectionTestUtils.setField(controller, "cliSessionService", cliSessionService);
		ReflectionTestUtils.setField(controller, "federatedTrustRuleService", federatedTrustRuleService);
	}

	/** A form POST; {@code params} are name, value pairs (a name given twice becomes a two-element array). */
	private static MockHttpServletRequest request(String authorization, String... params) {
		MockHttpServletRequest req = new MockHttpServletRequest("POST", ProgrammaticTokenController.PATH);
		req.setRemoteAddr("198.51.100.50");
		req.setContentType("application/x-www-form-urlencoded");
		for (int i = 0; i < params.length; i += 2) req.addParameter(params[i], params[i + 1]);
		if (authorization != null) req.addHeader(HttpHeaders.AUTHORIZATION, authorization);
		return req;
	}

	private ResponseEntity<Map<String, Object>> send(MockHttpServletRequest req) {
		return controller.token(req);
	}

	private static void assertOneMethod(ResponseEntity<Map<String, Object>> r) {
		assertEquals(400, r.getStatusCode().value());
		assertEquals(Map.of("error", "invalid_request", "error_description", OAuthQueryGuard.ONE_METHOD_DESCRIPTION), r.getBody());
		assertEquals("no-store", r.getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
		assertNull(r.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE));
	}

	private void assertNothingLookedUp() {
		verifyNoInteractions(apiKeyService, apiTokenService, cliSessionService, federatedTrustRuleService);
	}

	private AuthHeaderParse checkedCredential() {
		ArgumentCaptor<AuthHeaderParse> c = ArgumentCaptor.forClass(AuthHeaderParse.class);
		verify(apiKeyService, times(1)).isMatchingApiKey(c.capture());
		return c.getValue();
	}

	// case 1
	@Test
	public void headerPlusFormSecretIsRefusedBeforeAnyLookup() {
		assertOneMethod(send(request(BASIC, "grant_type", OAuthErrors.GRANT_CLIENT_CREDENTIALS, "client_secret", "form-secret")));
		verify(apiKeyService, never()).isMatchingApiKey(any());
		assertNothingLookedUp();
	}

	// case 2
	@Test
	public void headerPlusFormClientIdIsRefusedBeforeAnyLookup() {
		String cc = OAuthErrors.GRANT_CLIENT_CREDENTIALS;
		assertOneMethod(send(request(BASIC, "grant_type", cc, "client_id", OTHER)));
		assertOneMethod(send(request(BASIC, "grant_type", cc, "client_id", OTHER, "client_secret", "s")));
		assertOneMethod(send(request(BASIC, "grant_type", cc, "client_id", "")));
		assertOneMethod(send(request(" ", "grant_type", cc, "client_id", OTHER, "client_secret", "s")));
		assertOneMethod(send(request("Bearer abc", "grant_type", cc, "client_secret", "s")));
		verify(apiKeyService, never()).isMatchingApiKey(any());
		assertNothingLookedUp();
	}

	// case 3
	@Test
	public void everyOtherGrantIsRefusedToo() {
		assertOneMethod(send(request(BASIC, "grant_type", OAuthErrors.GRANT_DEVICE_CODE, "device_code", "x", "client_id", OTHER)));
		assertOneMethod(send(request(BASIC, "grant_type", OAuthErrors.GRANT_REFRESH_TOKEN, "refresh_token", "x", "client_secret", "s")));
		assertOneMethod(send(request(BASIC, "grant_type", OAuthErrors.GRANT_JWT_BEARER, "assertion", "x", "client_id", ORG)));
		assertOneMethod(send(request(BASIC, "client_id", OTHER)));
		assertNothingLookedUp();
	}

	// case 4
	@Test
	public void theEarlierGuardsAnswerFirst() {
		ResponseEntity<Map<String, Object>> repeated = send(request(BASIC, "grant_type", OAuthErrors.GRANT_CLIENT_CREDENTIALS,
				"client_id", OTHER, "client_id", OTHER, "client_secret", "s"));
		assertEquals(400, repeated.getStatusCode().value());
		assertEquals(OAuthQueryGuard.ONCE_ONLY_DESCRIPTION + "; sent more than once: client_id", repeated.getBody().get("error_description"));

		MockHttpServletRequest inQuery = request(BASIC, "grant_type", OAuthErrors.GRANT_CLIENT_CREDENTIALS, "client_id", OTHER);
		inQuery.setQueryString("client_id=" + OTHER);
		ResponseEntity<Map<String, Object>> query = send(inQuery);
		assertEquals(400, query.getStatusCode().value());
		assertEquals(OAuthQueryGuard.BODY_ONLY_DESCRIPTION + "; in the query string: client_id", query.getBody().get("error_description"));
		assertNothingLookedUp();
	}

	// case 5
	@Test
	public void theHeaderAloneIsUnchangedAndUnreadNamesAreNoSecondMethod() {
		ResponseEntity<Map<String, Object>> r = send(request(BASIC, "grant_type", OAuthErrors.GRANT_CLIENT_CREDENTIALS,
				"client_id[]", OTHER, "client_secret[]", "s", "scope", "x"));
		assertEquals(401, r.getStatusCode().value());
		assertEquals("invalid_client", r.getBody().get("error"));
		assertEquals("unknown API key or wrong secret", r.getBody().get("error_description"));
		AuthHeaderParse checked = checkedCredential();
		assertEquals(ID, checked.getApiKeyId());
		assertEquals("header-secret", checked.getApiKey());

		when(cliSessionService.poll("x")).thenReturn(new CliSessionService.PollResult(CliSessionService.PollOutcome.INVALID, null));
		ResponseEntity<Map<String, Object>> poll = send(request(BASIC, "grant_type", OAuthErrors.GRANT_DEVICE_CODE, "device_code", "x"));
		assertEquals(200, poll.getStatusCode().value());
		assertEquals("invalid_grant", poll.getBody().get("error"));
		assertEquals("unknown or already used device code", poll.getBody().get("error_description"));
		verify(cliSessionService).poll("x");
	}

	// case 6
	@Test
	public void theFormAloneIsUnchanged() {
		ResponseEntity<Map<String, Object>> r = send(request(null, "grant_type", OAuthErrors.GRANT_CLIENT_CREDENTIALS,
				"client_id", OTHER, "client_secret", "form-secret"));
		assertEquals(401, r.getStatusCode().value());
		assertEquals("invalid_client", r.getBody().get("error"));
		assertEquals("unknown API key or wrong secret", r.getBody().get("error_description"));
		AuthHeaderParse checked = checkedCredential();
		assertEquals(OTHER, checked.getApiKeyId());
		assertEquals("form-secret", checked.getApiKey());
	}

	// case 7
	@Test
	public void jwtBearerWithAFormClientIdAndNoHeaderIsUnchanged() throws Exception {
		when(federatedTrustRuleService.exchange("x", ORG)).thenThrow(new FederatedTrustRuleService.ExchangeException(OAuthErrors.INVALID_GRANT,
				FederatedAssertionService.Refusal.ORG_NOT_NAMED, FederatedTrustRuleService.NOT_ACCEPTED, null, null));
		ResponseEntity<Map<String, Object>> r = send(request(null, "grant_type", OAuthErrors.GRANT_JWT_BEARER, "assertion", "x", "client_id", ORG));
		assertEquals(200, r.getStatusCode().value());
		assertNotNull(r.getBody());
		assertEquals("invalid_grant", r.getBody().get("error"));
		assertEquals(FederatedTrustRuleService.NOT_ACCEPTED, r.getBody().get("error_description"));
		verify(federatedTrustRuleService).exchange("x", ORG);
		verifyNoInteractions(apiKeyService);
	}
}
