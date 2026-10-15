/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.service.ApiKeyAccessService;
import io.reliza.service.ApiKeyService;

/**
 * TPF-29 (architecture-3 section 5.1): {@link LegacyGraphqlBasicFilter} alone, against a mocked {@link ApiKeyService}.
 * It claims {@code /graphql} on the decoded path only, passes no header and every bearer-prefixed header through
 * untouched, admits a verified Basic with the key row's principal and the deprecation headers, and answers every other
 * value with the programmatic lane's one invalid-credential 401. The chain-level cases are in
 * {@link LegacyGraphqlBasicChainTest}; the programmatic filter's own cases, unchanged across the move into
 * {@link BasicKeyVerifier}, are {@link ProgrammaticAuthenticationFilterTest}.
 */
public class LegacyGraphqlBasicFilterTest {

	private static final String PATH = LegacyGraphqlBasicFilter.PATH;
	private static final String ADDRESS = "198.51.100.41";

	private static MockHttpServletRequest request(String method, String path) {
		return request(method, path, path);
	}

	/** A percent-encoded spelling as the container hands it on: the URI as sent, the servlet path decoded. */
	private static MockHttpServletRequest request(String method, String requestUri, String servletPath) {
		MockHttpServletRequest req = new MockHttpServletRequest(method, requestUri);
		req.setRequestURI(requestUri);
		req.setServletPath(servletPath);
		req.setRemoteAddr(ADDRESS);
		return req;
	}

	private static MockHttpServletRequest spelled(String method, String requestUri) {
		return request(method, requestUri, URLDecoder.decode(requestUri, StandardCharsets.UTF_8));
	}

	private record Run(MockHttpServletRequest request, MockHttpServletResponse response, MockFilterChain chain) {
		boolean reachedChain() {
			return chain.getRequest() != null;
		}

		Object principal() {
			return request.getAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
		}
	}

	private static Run run(ApiKeyService apiKeyService, MockHttpServletRequest req) throws Exception {
		LegacyGraphqlBasicFilter f = new LegacyGraphqlBasicFilter(apiKeyService, mock(ApiKeyAccessService.class));
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		f.doFilter(req, resp, chain);
		return new Run(req, resp, chain);
	}

	private static Run run(ApiKeyService apiKeyService, String method, String path, String authorization) throws Exception {
		MockHttpServletRequest req = request(method, path);
		if (authorization != null) req.addHeader(HttpHeaders.AUTHORIZATION, authorization);
		return run(apiKeyService, req);
	}

	private static String basic(String credentials) {
		return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
	}

	private static String forged() {
		return basic("FREEFORM__" + UUID.randomUUID() + "__ord__0:x");
	}

	/** The full answer of a refusal: every header with its values, the status, then the body. */
	private static Map<String, Object> answerOf(Run r) throws Exception {
		Map<String, Object> m = new TreeMap<>();
		for (String name : r.response().getHeaderNames()) m.put(name, r.response().getHeaders(name));
		m.put("status", r.response().getStatus());
		m.put("body", r.response().getContentAsString(StandardCharsets.UTF_8));
		return m;
	}

	private static void assertRefused(Run r, String what) throws Exception {
		assertEquals(401, r.response().getStatus(), what);
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE, r.response().getHeader(HttpHeaders.WWW_AUTHENTICATE), what);
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, r.response().getContentAsString(StandardCharsets.UTF_8), what);
		assertEquals("no-store", r.response().getHeader(HttpHeaders.CACHE_CONTROL), what);
		assertTrue(r.response().getContentType().startsWith("application/json"), what + ": " + r.response().getContentType());
		assertNull(r.response().getHeader("Deprecation"), what + ": no deprecation header on a refusal");
		assertNull(r.response().getHeader("Link"), what);
		assertNull(r.response().getHeader("X-ReARM-Deprecation"), what);
		assertFalse(r.reachedChain(), what + ": no resolver runs");
		assertNull(r.principal(), what + ": no principal");
	}

	private static void assertPassedUntouched(Run r, String what) {
		assertTrue(r.reachedChain(), what + ": passed on");
		assertEquals(200, r.response().getStatus(), what);
		assertTrue(r.response().getHeaderNames().isEmpty(), what + ": no header set by the filter, " + r.response().getHeaderNames());
		assertNull(r.principal(), what + ": no principal");
	}

	private static ApiKey row(ApiTypeEnum type, UUID object, UUID org, String order) {
		ApiKey ak = new ApiKey();
		ak.setUuid(UUID.randomUUID());
		ak.setObjectType(type);
		ak.setObjectUuid(object);
		ak.setOrg(org);
		ak.setKeyOrder(order);
		return ak;
	}

	// case 1: the path set, on the decoded path; nothing else is touched

	@Test
	void theFilterClaimsGraphqlOnlyOnTheDecodedPath() throws Exception {
		List<MockHttpServletRequest> claimed = new ArrayList<>();
		claimed.add(request("POST", PATH));
		claimed.add(request("GET", PATH));
		MockHttpServletRequest query = request("GET", PATH);
		query.setQueryString("x=1");
		claimed.add(query);
		claimed.add(spelled("POST", "/%67raphql"));
		for (MockHttpServletRequest req : claimed) {
			ApiKeyService svc = mock(ApiKeyService.class);
			req.addHeader(HttpHeaders.AUTHORIZATION, forged());
			String what = req.getMethod() + " " + req.getRequestURI() + (req.getQueryString() == null ? "" : "?" + req.getQueryString());
			assertRefused(run(svc, req), what + " is claimed");
		}
		String artifact = UUID.randomUUID().toString();
		for (String path : List.of(ProgrammaticGraphQlConfig.PATH, PATH + "/", PATH + "x", "/api/manual/v1/fetchCsrf", "/api/healthCheck",
				"/api/manual/v1/artifact/" + artifact + "/download")) {
			for (String method : List.of("GET", "POST")) {
				ApiKeyService svc = mock(ApiKeyService.class);
				Run r = run(svc, method, path, forged());
				assertPassedUntouched(r, method + " " + path + " is not claimed");
				verifyNoInteractions(svc);
			}
		}
	}

	// case 2: no header, and every bearer-prefixed value, pass through untouched (the resource server's)

	@Test
	void noHeaderAndBearerPrefixedValuesPassThroughUntouched() throws Exception {
		List<String> values = new ArrayList<>();
		values.add(null);
		values.addAll(List.of("Bearer abc", "Bearer ", "bearer x", "Bearer", "Bearerx"));
		for (String authorization : values) {
			ApiKeyService svc = mock(ApiKeyService.class);
			Run r = run(svc, "POST", PATH, authorization);
			assertPassedUntouched(r, "POST " + PATH + " with " + authorization);
			verifyNoInteractions(svc);
		}
	}

	// case 3: a valid Basic is verified and the key row's principal stored

	@Test
	void aValidBasicIsVerifiedAndTheKeyRowPrincipalIsStored() throws Exception {
		UUID org = UUID.randomUUID();
		String order = UUID.randomUUID().toString();
		ApiKey orgRw = row(ApiTypeEnum.ORGANIZATION_RW, org, org, null);
		ApiKey freeform = row(ApiTypeEnum.FREEFORM, org, org, order);
		List<Object[]> keys = List.of(
				new Object[] {orgRw, "ORGANIZATION_RW__" + org, org},
				new Object[] {freeform, "FREEFORM__" + org + "__ord__" + order, null});
		for (Object[] k : keys) {
			ApiKey ak = (ApiKey) k[0];
			for (String method : List.of("POST", "GET")) {
				ApiKeyService svc = mock(ApiKeyService.class);
				doAnswer(inv -> {
					inv.getArgument(0, AuthHeaderParse.class).setMatchedSecretSlot(2);
					return ak.getUuid();
				}).when(svc).isMatchingApiKey(any());
				when(svc.getApiKey(ak.getUuid())).thenReturn(Optional.of(ak));
				String what = ak.getObjectType() + " on " + method;
				Run r = run(svc, method, PATH, basic(k[1] + ":the-secret"));
				assertTrue(r.reachedChain(), what);
				assertEquals(200, r.response().getStatus(), what);
				AuthHeaderParse p = assertInstanceOf(AuthHeaderParse.class, r.principal(), what);
				assertEquals(ak.getUuid(), p.getVerifiedKeyUuid(), what);
				assertNull(p.getApiKey(), what + ": the secret does not travel past the filter");
				assertEquals(2, p.getMatchedSecretSlot(), what);
				assertEquals(ADDRESS, p.getRemoteIp(), what);
				assertEquals(k[2], p.getOrgUuid(), what + ": the row org for ORGANIZATION_RW, none for FREEFORM");
				assertEquals(k[1], p.getApiKeyId(), what + ": the canonical id");
				assertEquals("true", r.response().getHeader("Deprecation"), what);
				String link = r.response().getHeader("Link");
				assertNotNull(link, what);
				assertTrue(link.contains(ProgrammaticTokenController.PATH), what + ": " + link);
				assertNotNull(r.response().getHeader("X-ReARM-Deprecation"), what);

				ArgumentCaptor<AuthHeaderParse> seen = ArgumentCaptor.forClass(AuthHeaderParse.class);
				verify(svc).isMatchingApiKey(seen.capture());
				assertEquals("the-secret", seen.getValue().getApiKey(), what + ": parsed before verified");
				assertEquals(k[1], seen.getValue().getApiKeyId(), what);
				assertNull(seen.getValue().getVerifiedKeyUuid(), what);
			}
		}
	}

	/** The verifier bumps the key's last access once for a verified Basic, never for a refused one (task t20261013-072815-2705). */
	@Test
	void aVerifiedBasicTouchesTheKeyAndARefusedOneDoesNot() throws Exception {
		UUID org = UUID.randomUUID();
		ApiKey ak = row(ApiTypeEnum.FREEFORM, org, org, "k1");
		ApiKeyService svc = mock(ApiKeyService.class);
		when(svc.isMatchingApiKey(any())).thenReturn(ak.getUuid());
		when(svc.getApiKey(ak.getUuid())).thenReturn(Optional.of(ak));
		ApiKeyAccessService access = mock(ApiKeyAccessService.class);
		MockHttpServletRequest req = request("POST", PATH);
		req.addHeader(HttpHeaders.AUTHORIZATION, basic("FREEFORM__" + org + "__ord__k1:the-secret"));
		new LegacyGraphqlBasicFilter(svc, access).doFilter(req, new MockHttpServletResponse(), new MockFilterChain());
		verify(access, times(1)).touchLastAccess(ak.getUuid());

		ApiKeyService refusing = mock(ApiKeyService.class);
		when(refusing.isMatchingApiKey(any())).thenReturn(null);
		ApiKeyAccessService untouched = mock(ApiKeyAccessService.class);
		MockHttpServletRequest wrong = request("POST", PATH);
		wrong.addHeader(HttpHeaders.AUTHORIZATION, forged());
		new LegacyGraphqlBasicFilter(refusing, untouched).doFilter(wrong, new MockHttpServletResponse(), new MockFilterChain());
		verifyNoInteractions(untouched);
	}

	// case 4: every refusal on POST /graphql, one answer

	@Test
	void everyRefusalIsTheOneInvalidCredential401() throws Exception {
		Map<String, Run> all = new LinkedHashMap<>();
		String header = basic("FREEFORM__" + UUID.randomUUID() + "__ord__" + UUID.randomUUID() + ":wrong");

		ApiKeyService noMatch = mock(ApiKeyService.class);
		when(noMatch.isMatchingApiKey(any())).thenReturn(null);
		all.put("wrong secret", run(noMatch, "POST", PATH, header));
		verify(noMatch, never()).getApiKey(any());

		ApiKeyService noRow = mock(ApiKeyService.class);
		UUID gone = UUID.randomUUID();
		when(noRow.isMatchingApiKey(any())).thenReturn(gone);
		when(noRow.getApiKey(gone)).thenReturn(Optional.empty());
		all.put("no row", run(noRow, "POST", PATH, header));

		UUID u = UUID.randomUUID();
		List<String> noServiceCall = List.of("Basic !!!notbase64", basic("nocolon"), basic(":x"), basic("FREEFORM__" + u + "__ord__1:"),
				basic("FOO__" + u + ":x"), basic("FREEFORM__notauuid__ord__1:x"), "Basic", "Digest abc", "Token abc", "basicx", "");
		for (String authorization : noServiceCall) {
			ApiKeyService svc = mock(ApiKeyService.class);
			all.put("'" + authorization + "'", run(svc, "POST", PATH, authorization));
			verifyNoInteractions(svc);
		}

		for (RuntimeException thrown : List.of(new IncorrectResultSizeDataAccessException(1, 2), new RuntimeException("boom"))) {
			ApiKeyService svc = mock(ApiKeyService.class);
			when(svc.isMatchingApiKey(any())).thenThrow(thrown);
			all.put(thrown.getClass().getSimpleName(), run(svc, "POST", PATH, basic("FREEFORM__" + UUID.randomUUID() + ":x")));
		}

		Map<String, Object> reference = null;
		List<String> differing = new ArrayList<>();
		for (Map.Entry<String, Run> e : all.entrySet()) {
			assertRefused(e.getValue(), e.getKey());
			if (reference == null) reference = answerOf(e.getValue());
			else if (!reference.equals(answerOf(e.getValue()))) differing.add(e.getKey() + ": " + answerOf(e.getValue()));
		}
		assertTrue(differing.isEmpty(), "reference " + reference + "; differing " + differing);
	}
}
