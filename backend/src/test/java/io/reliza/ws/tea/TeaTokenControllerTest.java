/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import io.reliza.common.OAuthErrors;
import io.reliza.common.OAuthQueryGuard;
import io.reliza.common.Utils;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.Organization;
import io.reliza.model.tea.TeaErrorResponse;
import io.reliza.model.tea.TeaUnknownErrorType;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.ApiTokenServiceTestSupport;
import io.reliza.service.ExternalApiKeyService;
import io.reliza.service.ExternalApiKeyService.ExternalKeyMatch;

/**
 * Task TEA-3, design 4.4: the TEA token endpoint, driven through the controller method. Task TEA-24: the request shape
 * goes through {@link OAuthQueryGuard}, so the grant and the form credentials are parameters of the request.
 */
class TeaTokenControllerTest {

	private final ExternalApiKeyService external = mock(ExternalApiKeyService.class);
	private final OrganizationRepository orgs = mock(OrganizationRepository.class);
	private final ApiKeyAccessService access = mock(ApiKeyAccessService.class);
	private final ApiTokenService tokens = ApiTokenServiceTestSupport.create();
	private final TeaTokenController controller = new TeaTokenController(external, orgs, tokens, access);

	private final UUID org = UUID.randomUUID();
	private final UUID teaOrg = UUID.randomUUID();
	private final ApiKey key = new ApiKey();
	private final ApiKeyData data = ApiKeyData.apiKeyDataFactory(org);
	private final ApiKeySecret secret = new ApiKeySecret(1, "argon-hash", true, ZonedDateTime.now(), null);
	private final String keyId;

	TeaTokenControllerTest() {
		Organization o = new Organization();
		o.setUuid(org);
		when(orgs.findByTeaUuid(anyString())).thenReturn(Optional.empty());
		when(orgs.findByTeaUuid(teaOrg.toString())).thenReturn(Optional.of(o));
		key.setObjectType(ApiTypeEnum.EXTERNAL);
		key.setObjectUuid(org);
		key.setOrg(org);
		key.setKeyOrder(UUID.randomUUID().toString());
		data.setTeaOrg(teaOrg);
		data.setSecrets(new LinkedList<>(List.of(secret)));
		key.setRecordData(Utils.dataToRecord(data));
		keyId = ApiKeyService.keyIdOf(key);
		when(external.authenticate(any(), any(), any(), any())).thenReturn(Optional.empty());
		when(external.authenticate(eq(keyId), eq("good-secret"), eq(org), any())).thenReturn(Optional.of(new ExternalKeyMatch(key, data, secret)));
	}

	/** A token request; each non-null value is set as a form parameter (TEA-24 design 4.1 rig). */
	private static MockHttpServletRequest request(String authorization, String grantType, String clientId, String clientSecret) {
		MockHttpServletRequest r = new MockHttpServletRequest("POST", "/tea/x/v1.0.0/token");
		r.setRemoteAddr("203.0.113.7");
		if (authorization != null) r.addHeader("Authorization", authorization);
		if (grantType != null) r.setParameter("grant_type", grantType);
		if (clientId != null) r.setParameter("client_id", clientId);
		if (clientSecret != null) r.setParameter("client_secret", clientSecret);
		return r;
	}

	private static String basic(String raw) {
		return "Basic " + Base64.getEncoder().encodeToString(raw.getBytes(StandardCharsets.UTF_8));
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> body(ResponseEntity<?> r) {
		return (Map<String, Object>) r.getBody();
	}

	private static void assertOAuthError(ResponseEntity<?> r, int status, String code) {
		assertEquals(status, r.getStatusCode().value());
		assertEquals(code, body(r).get("error"));
		assertEquals("no-store", r.getHeaders().getFirst("Cache-Control"));
	}

	private static void assertInvalidClient(ResponseEntity<?> r) {
		assertOAuthError(r, 401, OAuthErrors.INVALID_CLIENT);
		assertEquals("Basic realm=\"tea\"", r.getHeaders().getFirst("WWW-Authenticate"));
	}

	// 15
	@Test
	void anUnknownOrMalformedOrgIsObjectUnknown() {
		for (String path : List.of(UUID.randomUUID().toString(), "not-a-uuid", org.toString())) {
			ResponseEntity<?> r = controller.token(request(basic(keyId + ":good-secret"), "client_credentials", null, null), path);
			assertEquals(404, r.getStatusCode().value(), path);
			assertEquals(TeaUnknownErrorType.OBJECT_UNKNOWN, ((TeaErrorResponse) r.getBody()).getError());
			assertEquals("no-store", r.getHeaders().getFirst("Cache-Control"));
		}
		verify(external, never()).authenticate(any(), any(), any(), any());
	}

	// 16
	@Test
	void theGrantMustBeClientCredentials() {
		assertOAuthError(controller.token(request(basic(keyId + ":good-secret"), null, null, null), teaOrg.toString()), 400, OAuthErrors.INVALID_REQUEST);
		for (String g : List.of(OAuthErrors.GRANT_REFRESH_TOKEN, OAuthErrors.GRANT_DEVICE_CODE, OAuthErrors.GRANT_JWT_BEARER, "password")) {
			ResponseEntity<?> r = controller.token(request(basic(keyId + ":good-secret"), g, null, null), teaOrg.toString());
			assertOAuthError(r, 400, OAuthErrors.UNSUPPORTED_GRANT_TYPE);
			assertTrue(String.valueOf(body(r).get("error_description")).contains("client_credentials"), g);
		}
		verify(external, never()).authenticate(any(), any(), any(), any());
	}

	// 17
	@ParameterizedTest
	@ValueSource(strings = { "", "Bearer abc.def.ghi", "Basic !!!not-base64!!!", "Basic bm9jb2xvbg==", "Basic OnNlY3JldA==", "Basic aWQ6" })
	void missingOrBadCredentialsAreInvalidClientWithABasicChallenge(String authorization) {
		// "bm9jb2xvbg==" is "nocolon"; "OnNlY3JldA==" is ":secret"; "aWQ6" is "id:"
		ResponseEntity<?> r = controller.token(request(authorization.isEmpty() ? null : authorization, "client_credentials", null, null), teaOrg.toString());
		assertInvalidClient(r);
		verify(external, never()).authenticate(any(), any(), any(), any());
	}

	// 18
	@Test
	void headerAndBodyCredentialsTogetherAreInvalidRequest() {
		ResponseEntity<?> r = controller.token(request(basic(keyId + ":good-secret"), "client_credentials", keyId, "good-secret"), teaOrg.toString());
		assertOAuthError(r, 400, OAuthErrors.INVALID_REQUEST);
		// TEA-24 design 4.1 row 4: the guard's text, and the body is exactly the two members
		assertEquals(OAuthQueryGuard.ONE_METHOD_DESCRIPTION, body(r).get("error_description"));
		assertEquals(Map.of("error", "invalid_request", "error_description", OAuthQueryGuard.ONE_METHOD_DESCRIPTION), body(r));
		verify(external, never()).authenticate(any(), any(), any(), any());
	}

	// TEA-24 design 3.1 step 6: the one-method check stays after the grant check, so an unsupported grant sent with both
	// methods keeps answering unsupported_grant_type (test report run 1, T-3)
	@Test
	void anUnsupportedGrantWithBothMethodsStaysUnsupportedGrantType() {
		ResponseEntity<?> r = controller.token(request(basic(keyId + ":good-secret"), "password", keyId, "good-secret"), teaOrg.toString());
		assertOAuthError(r, 400, OAuthErrors.UNSUPPORTED_GRANT_TYPE);
		assertEquals("supported: " + OAuthErrors.GRANT_CLIENT_CREDENTIALS, body(r).get("error_description"));
		verify(external, never()).authenticate(any(), any(), any(), any());
	}

	// 19
	@Test
	void basicPartsAreFormUrlDecoded() {
		var c = TeaTokenController.BasicCredentials.parse(basic("a%3Ab:s%2Bx")).orElseThrow();
		assertEquals("a:b", c.id());
		assertEquals("s+x", c.secret());
		var plain = TeaTokenController.BasicCredentials.parse(basic(keyId + ":0a1b2c3d")).orElseThrow();
		assertEquals(keyId, plain.id());
		assertEquals("0a1b2c3d", plain.secret());
		assertTrue(TeaTokenController.BasicCredentials.parse("basic " + Base64.getEncoder().encodeToString("i:s".getBytes())).isPresent(), "scheme is case-insensitive");
		assertTrue(TeaTokenController.BasicCredentials.parse(basic("i:%zz")).isEmpty(), "a broken escape is malformed");
	}

	// 20
	@Test
	void bodyCredentialsReachTheCheckAsTheHeaderOnesDo() {
		ResponseEntity<?> r = controller.token(request(null, "client_credentials", keyId, "good-secret"), teaOrg.toString());
		assertEquals(200, r.getStatusCode().value());
		verify(external).authenticate(eq(keyId), eq("good-secret"), eq(org), eq("203.0.113.7"));
		assertInvalidClient(controller.token(request(null, "client_credentials", keyId, null), teaOrg.toString()));
		assertInvalidClient(controller.token(request(null, "client_credentials", "", "good-secret"), teaOrg.toString()));
	}

	// 21
	@Test
	void aRefusedKeyIsInvalidClientAndNothingIsIssued() {
		ApiTokenService spyTokens = spy(tokens);
		TeaTokenController c = new TeaTokenController(external, orgs, spyTokens, access);
		ResponseEntity<?> r = c.token(request(basic(keyId + ":wrong"), "client_credentials", null, null), teaOrg.toString());
		assertInvalidClient(r);
		assertEquals("unknown API key or wrong secret", body(r).get("error_description"));
		verify(spyTokens, never()).issue(any(), any(), any());
		verify(access, never()).recordApiKeyAccess(any(), any(), any(), any());
	}

	// 22
	@Test
	void aMatchIssuesATeaTokenForAnHour() {
		MockHttpServletRequest req = request(basic(keyId + ":good-secret"), "client_credentials", null, null);
		req.addParameter("scope", "tea:read"); // ignored: the controller never reads it
		ResponseEntity<?> r = controller.token(req, teaOrg.toString());
		assertEquals(200, r.getStatusCode().value());
		Map<String, Object> b = body(r);
		String token = (String) b.get("access_token");
		assertTrue(tokens.verify(token, ApiTokenService.AUD_TEA).isPresent());
		assertFalse(tokens.verify(token, ApiTokenService.AUD_PROGRAMMATIC).isPresent(), "a TEA token is not a programmatic token");
		assertEquals("Bearer", b.get("token_type"));
		assertEquals(3600L, ((Number) b.get("expires_in")).longValue());
		assertFalse(b.containsKey("refresh_token"));
		assertEquals(3, b.size());
		assertEquals("no-store", r.getHeaders().getFirst("Cache-Control"));
		assertEquals("no-cache", r.getHeaders().getFirst("Pragma"));
		verify(access, times(1)).recordApiKeyAccess(key.getUuid(), "203.0.113.7", org, keyId);
		assertTrue(keyId.startsWith("EXTERNAL__" + teaOrg + "__ord__"));
	}

	// 59 (design round 2, T-1): token-request parameters come from the form body only; since TEA-24 the matching is
	// OAuthQueryGuard.namesInQuery, whose rows are OAuthQueryGuardTest, and the text is the guard's (design 4.1 row 9)
	@Test
	void aTokenParameterInTheQueryIsInvalidRequestBeforeAnythingElse() {
		ApiTokenService spyTokens = spy(tokens);
		TeaTokenController c = new TeaTokenController(external, orgs, spyTokens, access);
		MockHttpServletRequest req = request(basic(keyId + ":good-secret"), "client_credentials", null, null);
		req.setQueryString("client_secret=s");
		ResponseEntity<?> r = c.token(req, teaOrg.toString());
		assertOAuthError(r, 400, OAuthErrors.INVALID_REQUEST);
		assertEquals(OAuthQueryGuard.BODY_ONLY_DESCRIPTION + "; in the query string: client_secret", body(r).get("error_description"));
		assertFalse(body(r).containsKey("access_token"));
		verify(external, never()).authenticate(any(), any(), any(), any());
		verify(spyTokens, never()).issue(any(), any(), any());
		// step 1 stays first: an unknown org is 404 whatever the query carries
		MockHttpServletRequest unknown = request(basic(keyId + ":good-secret"), "client_credentials", null, null);
		unknown.setQueryString("client_secret=s");
		assertEquals(404, c.token(unknown, UUID.randomUUID().toString()).getStatusCode().value());
		// any other name in the query is ignored, as scope is in the body
		MockHttpServletRequest scoped = request(basic(keyId + ":good-secret"), "client_credentials", null, null);
		scoped.setQueryString("scope=x");
		assertEquals(200, c.token(scoped, teaOrg.toString()).getStatusCode().value());
	}

	// TEA-24 design 4.1 row 10: the names in query order, each once
	@Test
	void theQueryRefusalNamesTheParametersInQueryOrderOnce() {
		MockHttpServletRequest two = request(basic(keyId + ":good-secret"), "client_credentials", null, null);
		two.setQueryString("client_secret=s&grant_type=x");
		ResponseEntity<?> r = controller.token(two, teaOrg.toString());
		assertOAuthError(r, 400, OAuthErrors.INVALID_REQUEST);
		assertEquals(OAuthQueryGuard.BODY_ONLY_DESCRIPTION + "; in the query string: client_secret, grant_type", body(r).get("error_description"));
		MockHttpServletRequest twice = request(basic(keyId + ":good-secret"), "client_credentials", null, null);
		twice.setQueryString("client_id=a&client_id=b");
		assertEquals(OAuthQueryGuard.BODY_ONLY_DESCRIPTION + "; in the query string: client_id",
				body(controller.token(twice, teaOrg.toString())).get("error_description"));
		verify(external, never()).authenticate(any(), any(), any(), any());
	}

	// TEA-24 design 4.1 row 11: a repeated client_id is refused before anything is read
	@Test
	void aRepeatedClientIdIsInvalidRequestBeforeAnySecretCheck() {
		ApiTokenService spyTokens = spy(tokens);
		TeaTokenController c = new TeaTokenController(external, orgs, spyTokens, access);
		MockHttpServletRequest req = request(null, "client_credentials", null, "good-secret");
		req.setParameter("client_id", keyId, "other");
		ResponseEntity<?> r = c.token(req, teaOrg.toString());
		assertOAuthError(r, 400, OAuthErrors.INVALID_REQUEST);
		String description = OAuthQueryGuard.ONCE_ONLY_DESCRIPTION + "; sent more than once: client_id";
		assertEquals(description, body(r).get("error_description"));
		assertEquals(Map.of("error", "invalid_request", "error_description", description), body(r));
		String raw = String.valueOf(body(r));
		assertFalse(raw.contains(keyId), raw);
		assertFalse(raw.contains("good-secret"), raw);
		verify(external, never()).authenticate(any(), any(), any(), any());
		verify(spyTokens, never()).issue(any(), any(), any());
	}

	// TEA-24 design 4.1 row 12: repeated names sorted, and the order of the steps (organization, query, repeat, grant)
	@Test
	void repeatedNamesAreSortedAndTheGuardsRunInOrder() {
		MockHttpServletRequest both = request(null, "client_credentials", null, null);
		both.setParameter("client_secret", "s1", "s2");
		both.setParameter("client_id", keyId, "other");
		assertEquals(OAuthQueryGuard.ONCE_ONLY_DESCRIPTION + "; sent more than once: client_id, client_secret",
				body(controller.token(both, teaOrg.toString())).get("error_description"));
		// grant_type twice with a valid Basic: the repeat refusal, not unsupported_grant_type; the first value is an
		// unsupported grant, so a grant check running before the repeat check (step 5 before step 3) answers otherwise
		MockHttpServletRequest grants = request(basic(keyId + ":good-secret"), null, null, null);
		grants.setParameter("grant_type", "password", "client_credentials");
		ResponseEntity<?> g = controller.token(grants, teaOrg.toString());
		assertOAuthError(g, 400, OAuthErrors.INVALID_REQUEST);
		assertEquals(OAuthQueryGuard.ONCE_ONLY_DESCRIPTION + "; sent more than once: grant_type", body(g).get("error_description"));
		// a query offender and a repeat together: the query check (step 2) runs before the repeat check (step 3)
		MockHttpServletRequest queryAndRepeat = request(null, "client_credentials", null, null);
		queryAndRepeat.setQueryString("client_id=a");
		queryAndRepeat.setParameter("client_id", keyId, "other");
		assertEquals(OAuthQueryGuard.BODY_ONLY_DESCRIPTION + "; in the query string: client_id",
				body(controller.token(queryAndRepeat, teaOrg.toString())).get("error_description"));
		// a repeat on an unknown organization: the organization (step 1) runs first
		MockHttpServletRequest unknown = request(null, "client_credentials", null, null);
		unknown.setParameter("client_id", keyId, "other");
		ResponseEntity<?> u = controller.token(unknown, UUID.randomUUID().toString());
		assertEquals(404, u.getStatusCode().value());
		assertEquals(TeaUnknownErrorType.OBJECT_UNKNOWN, ((TeaErrorResponse) u.getBody()).getError());
		verify(external, never()).authenticate(any(), any(), any(), any());
	}

	// TEA-24 design 3.1 step 3 before step 6: a repeated client_id or client_secret beside a valid Basic header is the
	// repeat refusal, not the one-method refusal (test report run 1, T-2)
	@Test
	void aRepeatBesideAValidBasicIsTheRepeatRefusalNotTheOneMethodOne() {
		for (String name : List.of("client_id", "client_secret")) {
			MockHttpServletRequest r = request(basic(keyId + ":good-secret"), "client_credentials", null, null);
			r.setParameter(name, "a", "b");
			ResponseEntity<?> a = controller.token(r, teaOrg.toString());
			assertOAuthError(a, 400, OAuthErrors.INVALID_REQUEST);
			assertEquals(OAuthQueryGuard.ONCE_ONLY_DESCRIPTION + "; sent more than once: " + name, body(a).get("error_description"), name);
		}
		verify(external, never()).authenticate(any(), any(), any(), any());
	}

	// TEA-24 design 4.1 row 13: decorated names are read by nobody
	@Test
	void decoratedNamesAreUnread() {
		MockHttpServletRequest decorated = request(null, "client_credentials", null, null);
		decorated.setParameter("client_id[]", keyId);
		decorated.setParameter("client_secret[]", "good-secret");
		ResponseEntity<?> r = controller.token(decorated, teaOrg.toString());
		assertInvalidClient(r);
		assertEquals("client authentication required: HTTP Basic with the external key id and secret", body(r).get("error_description"));
		verify(external, never()).authenticate(any(), any(), any(), any());
		// a valid Basic beside client_id[]: the header is used, no pair refusal
		MockHttpServletRequest beside = request(basic(keyId + ":good-secret"), "client_credentials", null, null);
		beside.setParameter("client_id[]", "x");
		assertEquals(200, controller.token(beside, teaOrg.toString()).getStatusCode().value());
		verify(external, times(1)).authenticate(eq(keyId), eq("good-secret"), eq(org), eq("203.0.113.7"));
	}

	// TEA-24 design 4.1 row 14: a whitespace-only header is a method when form credentials come with it
	@Test
	void aWhitespaceOnlyHeaderBesideBodyCredentialsIsInvalidRequest() {
		ResponseEntity<?> r = controller.token(request(" ", "client_credentials", keyId, "good-secret"), teaOrg.toString());
		assertOAuthError(r, 400, OAuthErrors.INVALID_REQUEST);
		assertEquals(OAuthQueryGuard.ONE_METHOD_DESCRIPTION, body(r).get("error_description"));
		ResponseEntity<?> alone = controller.token(request(" ", "client_credentials", null, null), teaOrg.toString());
		assertInvalidClient(alone);
		assertEquals("client authentication required: HTTP Basic with the external key id and secret", body(alone).get("error_description"));
		verify(external, never()).authenticate(any(), any(), any(), any());
	}

	// TEA-24 design 4.1 row 15: the guard's set holds three more names than TEA's own did
	@ParameterizedTest
	@ValueSource(strings = { "refresh_token", "device_code", "assertion" })
	void theOtherTokenRequestParametersAreRefusedInTheQuery(String name) {
		MockHttpServletRequest req = request(basic(keyId + ":good-secret"), "client_credentials", null, null);
		req.setQueryString(name + "=x");
		ResponseEntity<?> r = controller.token(req, teaOrg.toString());
		assertOAuthError(r, 400, OAuthErrors.INVALID_REQUEST);
		assertEquals(OAuthQueryGuard.BODY_ONLY_DESCRIPTION + "; in the query string: " + name, body(r).get("error_description"));
		verify(external, never()).authenticate(any(), any(), any(), any());
	}

	// TEA-24 design 4.1 row 16: the three WARN lines carry the guard's names and the address only
	@Test
	void theRefusalLogLinesCarryNamesAndTheAddressOnly() {
		try (LogEventCapture logs = LogEventCapture.of(TeaTokenController.class, Level.DEBUG)) {
			// (a) the query shape, with a query value that cannot occur in a message by accident
			MockHttpServletRequest query = request(basic(keyId + ":good-secret"), "client_credentials", null, null);
			query.setQueryString("client_secret=query-secret-7f3a");
			controller.token(query, teaOrg.toString());
			// (b) the repeat shape
			MockHttpServletRequest repeat = request(null, "client_credentials", null, "good-secret");
			repeat.setParameter("client_id", keyId, "other");
			controller.token(repeat, teaOrg.toString());
			// (c) the two-methods shape
			controller.token(request(basic(keyId + ":good-secret"), "client_credentials", keyId, "good-secret"), teaOrg.toString());
			List<LogEvent> events = logs.events();
			assertEquals(List.of(Level.WARN, Level.WARN, Level.WARN), events.stream().map(LogEvent::getLevel).toList(), String.valueOf(events));
			assertEquals(List.of("SECURITY: TEA token request refused from 203.0.113.7: [client_secret] in the URL query string",
					"SECURITY: TEA token request refused from 203.0.113.7: [client_id] sent more than once",
					"SECURITY: TEA token request refused from 203.0.113.7: an Authorization header and a client_id or client_secret form field"),
					events.stream().map(e -> e.getMessage().getFormattedMessage()).toList());
			for (LogEvent e : events) {
				String all = e.getMessage().getFormattedMessage() + " " + e.getMessage().getFormat() + " " + e.getThrown();
				for (String secretText : List.of("query-secret-7f3a", "good-secret", keyId)) {
					assertFalse(all.contains(secretText), secretText + " in " + all);
				}
			}
		}
		verify(external, never()).authenticate(any(), any(), any(), any());
	}

	// TEA-21, design section 5 row 9: form or query parameters Tomcat could not decode are the RFC 6749
	// invalid_request with a fixed description and one DEBUG line; the form body cannot be re-read, so no name
	@Test
	void parametersThatCouldNotBeDecodedAreInvalidRequestWithOneDebugLine() {
		String path = "/tea/" + teaOrg + "/v1.0.0/token";
		MockHttpServletRequest req = new MockHttpServletRequest("POST", path);
		try (LogEventCapture logs = LogEventCapture.of(TeaTokenController.class, Level.DEBUG)) {
			ResponseEntity<?> r = controller.invalidParameter(new InvalidParameterException("msg", new MalformedInputException(1)), req);
			assertOAuthError(r, 400, OAuthErrors.INVALID_REQUEST);
			assertEquals("the request parameters could not be decoded", body(r).get("error_description"));
			assertEquals("application/json", String.valueOf(r.getHeaders().getContentType()));
			List<LogEvent> events = logs.events();
			assertEquals(1, events.size(), String.valueOf(events));
			assertEquals(Level.DEBUG, events.get(0).getLevel());
			assertNull(events.get(0).getThrown());
			assertEquals("TEA request refused, parameters could not be decoded (MalformedInputException): path " + path
					+ ", parameter -", events.get(0).getMessage().getFormattedMessage());
		}
		verify(external, never()).authenticate(any(), any(), any(), any());
	}

	@Test
	void aSecretExpiringSoonShortensExpiresIn() {
		secret.setExpiresDate(ZonedDateTime.now().plusMinutes(10));
		ResponseEntity<?> r = controller.token(request(basic(keyId + ":good-secret"), "client_credentials", null, null), teaOrg.toString());
		long expiresIn = ((Number) body(r).get("expires_in")).longValue();
		assertTrue(expiresIn <= 600 && expiresIn > 590, "expires_in follows the secret: " + expiresIn);
	}

	@Test
	void theTokenPathIsRecognized() {
		assertTrue(isTokenPath("/tea/" + teaOrg + "/v1.0.0/token"));
		// TEA-6 round 4 (T-5): read as the chain and the handler mapping read it
		assertTrue(isTokenPath("/tea/" + teaOrg + "/v1.0.0/t%6Fken"));
		assertTrue(isTokenPath("/%74ea/" + teaOrg + "/v1.0.0/token;x=1"));
		assertFalse(isTokenPath("/tea/" + teaOrg + "/v1.0.0/product/x"));
		assertFalse(isTokenPath("/tea/" + teaOrg + "/v1.0.0/token/"), "not the path the controller serves");
		assertFalse(isTokenPath("/api/programmatic/token"));
		assertFalse(TeaTokenController.isTokenPath(null));
	}

	private static boolean isTokenPath(String uri) {
		return TeaTokenController.isTokenPath(io.reliza.ws.App.chainPathOf(uri, "").orElseThrow());
	}
}
