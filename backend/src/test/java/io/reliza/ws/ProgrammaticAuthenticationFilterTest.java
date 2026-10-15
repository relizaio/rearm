/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
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

public class ProgrammaticAuthenticationFilterTest {

	@Test
	void anonymousCallGetsABearerChallenge() throws Exception {
		// the anonymous branch touches no service, so the collaborators can be absent
		ProgrammaticAuthenticationFilter f = new ProgrammaticAuthenticationFilter(null, null, null, null, null);
		MockHttpServletRequest req = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		req.setRequestURI(ProgrammaticGraphQlConfig.PATH);
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		f.doFilter(req, resp, chain);
		assertEquals(401, resp.getStatus());
		assertTrue(resp.getHeader("WWW-Authenticate").startsWith("Bearer realm="), "the challenge names the scheme a client must use");
		assertEquals("no-store", resp.getHeader("Cache-Control"));
		assertTrue(resp.getContentAsString().contains("invalid_request"));
		assertEquals(null, chain.getRequest(), "the request must not reach the resolvers");
	}

	@Test
	void otherPathsAreNotFiltered() throws Exception {
		ProgrammaticAuthenticationFilter f = new ProgrammaticAuthenticationFilter(null, null, null, null, null);
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/healthCheck");
		req.setRequestURI("/api/healthCheck");
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		f.doFilter(req, resp, chain);
		assertEquals(200, resp.getStatus());
		assertTrue(chain.getRequest() != null, "passed through untouched");
	}

	// TPF-13: the filter's path set is the GraphQL endpoint plus the programmatic artifact downloads

	private static final String ARTIFACT = "0b9e3a5c-6c1e-4d0b-9a57-5f0a1c2d3e4f";
	private static final String DOWNLOAD = "/api/programmatic/v1/artifact/" + ARTIFACT + "/download";
	private static final String RAW_DOWNLOAD = "/api/programmatic/v1/artifact/" + ARTIFACT + "/rawdownload";
	private static final List<String> ARTIFACT_PATHS = List.of(DOWNLOAD, RAW_DOWNLOAD);

	private static MockHttpServletRequest request(String method, String path, String query) {
		return request(method, path, path, query);
	}

	/** A percent-encoded spelling as the container hands it on: the URI as sent, the servlet path decoded. */
	private static MockHttpServletRequest request(String method, String requestUri, String servletPath, String query) {
		MockHttpServletRequest req = new MockHttpServletRequest(method, requestUri);
		req.setRequestURI(requestUri);
		req.setServletPath(servletPath);
		req.setQueryString(query);
		req.setRemoteAddr("198.51.100.31");
		return req;
	}

	private static MockHttpServletRequest spelled(String method, String requestUri, String query) {
		return request(method, requestUri, URLDecoder.decode(requestUri, StandardCharsets.UTF_8), query);
	}

	private record Run(MockHttpServletRequest request, MockHttpServletResponse response, MockFilterChain chain) {
		boolean reachedChain() {
			return chain.getRequest() != null;
		}
	}

	private static Run run(MockHttpServletRequest req) throws Exception {
		// none of the branches driven here touches a service, so the collaborators can be absent
		ProgrammaticAuthenticationFilter f = new ProgrammaticAuthenticationFilter(null, null, null, null, null);
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		f.doFilter(req, resp, chain);
		return new Run(req, resp, chain);
	}

	// test 1: the filtered paths answer a request with no header; the unfiltered ones pass it on
	@Test
	void theArtifactDownloadsAndGraphQlAreFilteredAndNothingElse() throws Exception {
		// TPF-24: the five percent-encoded spellings are matched on the decoded path, as the chain matches them
		List<String[]> filtered = List.of(
				new String[] {"POST", ProgrammaticGraphQlConfig.PATH},
				new String[] {"GET", DOWNLOAD},
				new String[] {"GET", RAW_DOWNLOAD},
				new String[] {"POST", "/api/programmatic/%67raphql"},
				new String[] {"POST", "/api/programmatic/graph%71l"},
				new String[] {"POST", "/api/%70rogrammatic/graphql"},
				new String[] {"GET", "/api/programmatic/v1/%61rtifact/" + ARTIFACT + "/download"},
				new String[] {"GET", "/api/%70rogrammatic/v1/artifact/" + ARTIFACT + "/rawdownload"});
		for (String[] m : filtered) {
			for (String query : new String[] {null, "version=2"}) {
				Run r = run(spelled(m[0], m[1], query));
				assertEquals(401, r.response().getStatus(), m[1] + "?" + query + " is filtered");
				assertFalse(r.reachedChain(), m[1] + "?" + query + " must not reach the chain without a credential");
			}
		}
		// the filter's claim stays a subset of the chain's on spellings too: the token and device-code paths pass
		List<String> unfiltered = List.of(ProgrammaticTokenController.PATH, ProgrammaticSchemaController.PATH,
				DeviceAuthorizationController.DEVICE_CODE_PATH, DeviceAuthorizationController.REVOKE_PATH,
				"/api/programmatic/v1/webhook/o/s", "/graphql", "/api/manual/v1/artifact/" + ARTIFACT + "/download",
				"/api/healthCheck", "/api/programmatic/%74oken", "/api/programmatic/device/%63ode");
		for (String path : unfiltered) {
			Run r = run(spelled("GET", path, null));
			assertEquals(200, r.response().getStatus(), path + " is not filtered");
			assertTrue(r.reachedChain(), path + " passes through untouched");
		}
	}

	// test 2: no header on either download path is the shared no-credential 401, written by the filter
	@Test
	void noCredentialOnADownloadIsTheNoCredential401() throws Exception {
		for (String path : ARTIFACT_PATHS) {
			Run r = run(request("GET", path, null));
			assertEquals(401, r.response().getStatus(), path);
			assertEquals(ProgrammaticAuthenticationFilter.NO_CREDENTIAL_CHALLENGE, r.response().getHeader("WWW-Authenticate"), path);
			assertEquals("no-store", r.response().getHeader("Cache-Control"), path);
			assertEquals(ProgrammaticAuthenticationFilter.NO_CREDENTIAL_BODY, r.response().getContentAsString(StandardCharsets.UTF_8), path);
			assertFalse(r.reachedChain(), path);
			assertNull(r.request().getAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE), path);
		}
	}

	// ---- TPF-27 (architecture-3 section 4.1): the filter verifies Basic itself, against a mocked ApiKeyService

	private static final String GRAPHQL = ProgrammaticGraphQlConfig.PATH;

	/** The GraphQL path (POST) and both download paths (GET): the three requests the filter covers. */
	private static List<String[]> filteredRequests() {
		return List.of(new String[] {"POST", GRAPHQL}, new String[] {"GET", DOWNLOAD}, new String[] {"GET", RAW_DOWNLOAD});
	}

	private static String basic(String credentials) {
		return "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
	}

	private static Run run(ApiKeyService apiKeyService, MockHttpServletRequest req) throws Exception {
		// the Basic branch touches only ApiKeyService and the access accumulator; the bearer branch is not driven with a token here
		ProgrammaticAuthenticationFilter f = new ProgrammaticAuthenticationFilter(null, apiKeyService, mock(ApiKeyAccessService.class), null, null);
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		f.doFilter(req, resp, chain);
		return new Run(req, resp, chain);
	}

	private static Run run(ApiKeyService apiKeyService, String method, String path, String authorization) throws Exception {
		MockHttpServletRequest req = request(method, path, null);
		req.addHeader("Authorization", authorization);
		return run(apiKeyService, req);
	}

	/** The full answer of a refusal: every header with its values, then the body. */
	private static Map<String, Object> answerOf(Run r) throws Exception {
		Map<String, Object> m = new TreeMap<>();
		for (String name : r.response().getHeaderNames()) m.put(name, r.response().getHeaders(name));
		m.put("status", r.response().getStatus());
		m.put("body", r.response().getContentAsString(StandardCharsets.UTF_8));
		return m;
	}

	private static void assertRefused(Run r, String what) throws Exception {
		assertEquals(401, r.response().getStatus(), what);
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE, r.response().getHeader("WWW-Authenticate"), what);
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, r.response().getContentAsString(StandardCharsets.UTF_8), what);
		assertEquals("no-store", r.response().getHeader("Cache-Control"), what);
		assertTrue(r.response().getContentType().startsWith("application/json"), what + ": " + r.response().getContentType());
		assertNull(r.response().getHeader("Deprecation"), what + ": no deprecation header on a refusal");
		assertNull(r.response().getHeader("Link"), what);
		assertNull(r.response().getHeader("X-ReARM-Deprecation"), what);
		assertFalse(r.reachedChain(), what + ": the request must not reach a resolver or a handler");
		assertNull(r.request().getAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE), what + ": no principal");
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

	/** The reference refusal every other one is compared with: an unknown key on the GraphQL path. */
	private static Map<String, Object> referenceRefusal() throws Exception {
		ApiKeyService svc = mock(ApiKeyService.class);
		Run r = run(svc, "POST", GRAPHQL, basic("FREEFORM__" + UUID.randomUUID() + "__ord__" + UUID.randomUUID() + ":x"));
		assertRefused(r, "reference");
		return answerOf(r);
	}

	// case 1 (rewrites test 3): a valid Basic is verified and the bearer-shaped principal of the key row is stored
	@Test
	void aValidBasicIsVerifiedAndTheKeyRowPrincipalIsStored() throws Exception {
		UUID org = UUID.randomUUID();
		String order = UUID.randomUUID().toString();
		ApiKey orgRw = row(ApiTypeEnum.ORGANIZATION_RW, org, org, null);
		ApiKey freeform = row(ApiTypeEnum.FREEFORM, org, org, order);
		List<Object[]> keys = List.of(
				new Object[] {orgRw, "ORGANIZATION_RW__" + org, "ORGANIZATION_RW__" + org, org},
				new Object[] {freeform, "FREEFORM__" + org + "__ord__" + order, "FREEFORM__" + org + "__ord__" + order, null});
		for (Object[] k : keys) {
			ApiKey ak = (ApiKey) k[0];
			for (String[] m : filteredRequests()) {
				ApiKeyService svc = mock(ApiKeyService.class);
				doAnswer(inv -> {
					inv.getArgument(0, AuthHeaderParse.class).setMatchedSecretSlot(2);
					return ak.getUuid();
				}).when(svc).isMatchingApiKey(any());
				when(svc.getApiKey(ak.getUuid())).thenReturn(Optional.of(ak));
				String what = ak.getObjectType() + " on " + m[1];
				Run r = run(svc, m[0], m[1], basic(k[1] + ":the-secret"));
				assertTrue(r.reachedChain(), what);
				assertEquals(200, r.response().getStatus(), what);
				AuthHeaderParse p = assertInstanceOf(AuthHeaderParse.class, r.request().getAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE), what);
				assertEquals(ak.getUuid(), p.getVerifiedKeyUuid(), what);
				assertNull(p.getApiKey(), what + ": the secret does not travel past the filter");
				assertEquals(2, p.getMatchedSecretSlot(), what);
				assertEquals("198.51.100.31", p.getRemoteIp(), what);
				assertEquals(k[3], p.getOrgUuid(), what + ": the row org for ORGANIZATION_RW, none for FREEFORM");
				assertEquals(k[2], p.getApiKeyId(), what + ": the canonical id");
				assertEquals("true", r.response().getHeader("Deprecation"), what);
				String link = r.response().getHeader("Link");
				assertNotNull(link, what);
				assertTrue(link.contains(ProgrammaticTokenController.PATH), what + ": " + link);
				assertNotNull(r.response().getHeader("X-ReARM-Deprecation"), what);

				ArgumentCaptor<AuthHeaderParse> seen = ArgumentCaptor.forClass(AuthHeaderParse.class);
				verify(svc).isMatchingApiKey(seen.capture());
				assertEquals("the-secret", seen.getValue().getApiKey(), what + ": the filter parsed before it verified");
				assertEquals(k[1], seen.getValue().getApiKeyId(), what);
				assertNull(seen.getValue().getVerifiedKeyUuid(), what);
			}
		}
	}

	// case 2: a wrong secret or an unknown id, and a matched key whose row is gone: one answer
	@Test
	void aWrongSecretOrAMissingRowIsTheInvalidCredential401() throws Exception {
		Map<String, Object> reference = referenceRefusal();
		String header = basic("FREEFORM__" + UUID.randomUUID() + "__ord__" + UUID.randomUUID() + ":wrong");
		for (String[] m : filteredRequests()) {
			ApiKeyService noMatch = mock(ApiKeyService.class);
			when(noMatch.isMatchingApiKey(any())).thenReturn(null);
			Run a = run(noMatch, m[0], m[1], header);
			assertRefused(a, "no match on " + m[1]);
			verify(noMatch, never()).getApiKey(any());

			ApiKeyService noRow = mock(ApiKeyService.class);
			UUID gone = UUID.randomUUID();
			when(noRow.isMatchingApiKey(any())).thenReturn(gone);
			when(noRow.getApiKey(gone)).thenReturn(Optional.empty());
			Run b = run(noRow, m[0], m[1], header);
			assertRefused(b, "no row on " + m[1]);

			assertEquals(answerOf(a), answerOf(b), m[1] + ": the two refusals are byte-identical");
			assertEquals(reference, answerOf(a), m[1]);
		}
	}

	// case 3: a malformed Basic is refused without a service call; the logged identity is canonical
	@Test
	void aMalformedBasicIsRefusedWithoutAServiceCall() throws Exception {
		Map<String, Object> reference = referenceRefusal();
		UUID u = UUID.randomUUID();
		List<String> malformed = List.of("Basic !!!notbase64", basic("nocolon"), basic(":x"), basic("FREEFORM__" + u + "__ord__1:"),
				basic("FOO__" + u + ":x"), basic("FREEFORM__notauuid__ord__1:x"), "Basic");
		for (String authorization : malformed) {
			for (String[] m : filteredRequests()) {
				ApiKeyService svc = mock(ApiKeyService.class);
				Run r = run(svc, m[0], m[1], authorization);
				assertRefused(r, authorization + " on " + m[1]);
				assertEquals(reference, answerOf(r), authorization + " on " + m[1]);
				verifyNoInteractions(svc);
			}
		}
	}

	private static AuthHeaderParse parse(String credentials) {
		HttpHeaders h = new HttpHeaders();
		h.set(HttpHeaders.AUTHORIZATION, basic(credentials));
		return AuthHeaderParse.parseAuthHeader(h, "198.51.100.31");
	}

	// case 3, identity row: the warn line's identity never carries the presented id text
	@Test
	void theLoggedIdentityIsCanonical() throws Exception {
		UUID u = UUID.randomUUID();
		String forged = ProgrammaticAuthenticationFilter.canonicalIdOf(parse("FREEFORM__" + u + "__ord__abc\nFORGED LINE:x"));
		assertEquals("FREEFORM__" + u + "__ord__?", forged);
		assertFalse(forged.contains("FORGED LINE"));
		String order = UUID.randomUUID().toString();
		assertEquals("FREEFORM__" + u + "__ord__" + order, ProgrammaticAuthenticationFilter.canonicalIdOf(parse("FREEFORM__" + u + "__ord__" + order + ":x")));
		assertEquals("ORGANIZATION_RW__" + u, ProgrammaticAuthenticationFilter.canonicalIdOf(parse("ORGANIZATION_RW__" + u.toString().toUpperCase(Locale.ROOT) + ":x")));
		for (String m : List.of("nocolon", ":x", "FOO__" + u + ":x", "FREEFORM__notauuid__ord__1:x")) {
			assertEquals("", ProgrammaticAuthenticationFilter.canonicalIdOf(parse(m)), m);
		}
		assertEquals("", ProgrammaticAuthenticationFilter.canonicalIdOf(null));

		// TPF-24: the filter's rendering is the parse's own, by delegation, for every input above
		for (String m : List.of("FREEFORM__" + u + "__ord__abc\nFORGED LINE:x", "FREEFORM__" + u + "__ord__" + order + ":x",
				"ORGANIZATION_RW__" + u.toString().toUpperCase(Locale.ROOT) + ":x", "nocolon", ":x", "FOO__" + u + ":x", "FREEFORM__notauuid__ord__1:x")) {
			assertEquals(ProgrammaticAuthenticationFilter.canonicalIdOf(parse(m)), parse(m).canonicalId(), m);
		}
		// a real caller's rendering is unchanged: for a principal built from a key row it is the row's key id
		UUID org = UUID.randomUUID();
		AuthHeaderParse freeform = AuthHeaderParse.fromVerifiedKey(row(ApiTypeEnum.FREEFORM, org, org, order), 1, "198.51.100.31");
		assertEquals(freeform.getApiKeyId(), freeform.canonicalId());
		AuthHeaderParse orgRw = AuthHeaderParse.fromVerifiedKey(row(ApiTypeEnum.ORGANIZATION_RW, org, org, null), 1, "198.51.100.31");
		assertEquals(orgRw.getApiKeyId(), orgRw.canonicalId());
		// the one recorded difference (architecture-6 decision 8): a FEDERATED row's provider:repository order renders as ?
		AuthHeaderParse federated = AuthHeaderParse.fromVerifiedKey(row(ApiTypeEnum.FEDERATED, org, org, "github:owner/repo"), null, "198.51.100.31");
		assertEquals("FEDERATED__" + org + "__ord__?", federated.canonicalId());
		assertTrue(federated.getApiKeyId().endsWith("__ord__github:owner/repo"), federated.getApiKeyId());

		// the request with the line-break order is answered like every other refusal
		ApiKeyService svc = mock(ApiKeyService.class);
		when(svc.isMatchingApiKey(any())).thenReturn(null);
		Run r = run(svc, "POST", GRAPHQL, basic("FREEFORM__" + u + "__ord__abc\nFORGED LINE:x"));
		assertRefused(r, "line-break order");
		assertEquals(referenceRefusal(), answerOf(r));
	}

	// case 4: a foreign scheme is refused the same way, without a service call
	@Test
	void aForeignSchemeIsTheInvalidCredential401() throws Exception {
		Map<String, Object> reference = referenceRefusal();
		for (String authorization : List.of("Digest abc", "Token abc", "basicx", "Bearer")) {
			for (String[] m : filteredRequests()) {
				ApiKeyService svc = mock(ApiKeyService.class);
				Run r = run(svc, m[0], m[1], authorization);
				assertRefused(r, authorization + " on " + m[1]);
				assertEquals(reference, answerOf(r), authorization + " on " + m[1]);
				verifyNoInteractions(svc);
			}
		}
	}

	// case 5: an exception from the lookup (the ambiguous no-order id) is the same 401, not a 500
	@Test
	void aLookupExceptionIsTheInvalidCredential401() throws Exception {
		Map<String, Object> reference = referenceRefusal();
		for (RuntimeException thrown : List.of(new IncorrectResultSizeDataAccessException(1, 2), new RuntimeException("boom"))) {
			for (String[] m : filteredRequests()) {
				ApiKeyService svc = mock(ApiKeyService.class);
				when(svc.isMatchingApiKey(any())).thenThrow(thrown);
				Run r = run(svc, m[0], m[1], basic("FREEFORM__" + UUID.randomUUID() + ":x"));
				assertRefused(r, thrown.getClass().getSimpleName() + " on " + m[1]);
				assertEquals(reference, answerOf(r), m[1]);
			}
		}
	}

	// case 7, added row: a Basic header on an unfiltered path, the token exchange and /graphql included, is never verified here
	@Test
	void basicOnAnUnfilteredPathIsNotTouched() throws Exception {
		for (String path : List.of(ProgrammaticTokenController.PATH, DeviceAuthorizationController.DEVICE_CODE_PATH,
				DeviceAuthorizationController.REVOKE_PATH, ProgrammaticSchemaController.PATH, "/api/programmatic/v1/webhook/o/s", "/graphql")) {
			ApiKeyService svc = mock(ApiKeyService.class);
			Run r = run(svc, "POST", path, basic("FREEFORM__" + UUID.randomUUID() + "__ord__1:x"));
			assertTrue(r.reachedChain(), path + " passes through untouched");
			assertEquals(200, r.response().getStatus(), path);
			assertNull(r.request().getAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE), path);
			assertNull(r.response().getHeader("Deprecation"), path);
			verifyNoInteractions(svc);
		}
	}

	// ---- SCORE-17 (main, #803): the path set and Basic on the artifact downloads, rewritten to the TPF answers on merge.
	// A wrong, unknown, inactive or malformed Basic, and any other scheme, is the one invalid-credential 401 of
	// BasicKeyVerifier.refuse (TPF-27), on the downloads and on the GraphQL endpoint alike; Basic on the GraphQL
	// endpoint is verified here too since TPF-27, no longer passed through to the resolvers.

	private static MockHttpServletRequest get(String uri, String query) {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", uri);
		req.setRequestURI(uri);
		req.setQueryString(query);
		return req;
	}

	private static final String ARTIFACT_BASE = "/api/programmatic/v1/artifact/" + UUID.randomUUID();

	/** T-11: filtered are the GraphQL endpoint and both artifact downloads; every other path of the chain is not. */
	@Test
	void theFilterRunsOnTheGraphQlEndpointAndTheArtifactDownloadsOnly() {
		ProgrammaticAuthenticationFilter f = new ProgrammaticAuthenticationFilter(null, null, null, null, null);
		for (String uri : new String[] { ProgrammaticGraphQlConfig.PATH, ARTIFACT_BASE + "/download", ARTIFACT_BASE + "/rawdownload" }) {
			assertFalse(f.shouldNotFilter(get(uri, null)), uri);
		}
		assertFalse(f.shouldNotFilter(get(ARTIFACT_BASE + "/download", "version=2&releaseUuid=" + UUID.randomUUID())), "with a query string");
		assertFalse(f.shouldNotFilter(get(ARTIFACT_BASE + "/rawdownload", "version=2")), "raw, with a query string");
		for (String uri : new String[] { ProgrammaticTokenController.PATH, ProgrammaticSchemaController.PATH,
				DeviceAuthorizationController.DEVICE_CODE_PATH, DeviceAuthorizationController.REVOKE_PATH,
				"/api/programmatic/v1/webhook/x/y", "/graphql", "/api/manual/v1/artifact/" + UUID.randomUUID() + "/download" }) {
			assertTrue(f.shouldNotFilter(get(uri, null)), uri);
		}
	}

	private static String basic(String id, String secret) {
		return basic(id + ":" + secret);
	}

	private static final String KEY_ID = "FREEFORM__" + UUID.randomUUID() + "__ord__k1";

	/** Was a Basic challenge with invalid_client and a Deprecation header on main; the TPF answer is the uniform 401. */
	@Test
	void aBasicKeyThatDoesNotMatchIsRefusedOnAnArtifactDownload() throws Exception {
		ApiKeyService keys = mock(ApiKeyService.class);
		when(keys.isMatchingApiKey(any())).thenReturn(null);
		MockHttpServletRequest req = get(ARTIFACT_BASE + "/download", null);
		req.addHeader("Authorization", basic(KEY_ID, "wrong"));
		Run r = run(keys, req);
		assertRefused(r, "a wrong Basic on a download");
		assertEquals(referenceRefusal(), answerOf(r), "no key id, no reason, the same bytes as every other refusal");
	}

	@Test
	void aMalformedBasicHeaderIsRefusedWithoutAKeyLookup() throws Exception {
		ApiKeyService keys = mock(ApiKeyService.class);
		MockHttpServletRequest req = get(ARTIFACT_BASE + "/rawdownload", null);
		req.addHeader("Authorization", "Basic $(ReARM-ApiKeyId)");
		Run r = run(keys, req);
		assertRefused(r, "a malformed Basic on a raw download");
		verifyNoInteractions(keys);
	}

	@Test
	void aMatchingBasicKeyBecomesThePrincipalOnAnArtifactDownload() throws Exception {
		UUID org = UUID.randomUUID();
		String order = "k1";
		ApiKey ak = row(ApiTypeEnum.FREEFORM, org, org, order);
		String keyId = "FREEFORM__" + org + "__ord__" + order;
		ApiKeyService keys = mock(ApiKeyService.class);
		when(keys.isMatchingApiKey(any())).thenReturn(ak.getUuid());
		when(keys.getApiKey(ak.getUuid())).thenReturn(Optional.of(ak));
		MockHttpServletRequest req = get(ARTIFACT_BASE + "/download", "version=1");
		req.addHeader("Authorization", basic(keyId, "right"));
		Run r = run(keys, req);
		assertSame(req, r.chain().getRequest(), "passed on to the handler");
		Object principal = req.getAttribute(ProgrammaticAuthenticationFilter.PRINCIPAL_ATTRIBUTE);
		assertTrue(principal instanceof AuthHeaderParse ahp && keyId.equals(ahp.getApiKeyId())
				&& ak.getUuid().equals(ahp.getVerifiedKeyUuid()), "the verified key row is the principal");
		assertEquals("true", r.response().getHeader("Deprecation"));
	}

	/** T-10 (unit side) was: Basic passes through unverified on the GraphQL endpoint. Since TPF-27 it is verified there too. */
	@Test
	void aWrongBasicIsRefusedOnTheGraphQlEndpointToo() throws Exception {
		ApiKeyService keys = mock(ApiKeyService.class);
		when(keys.isMatchingApiKey(any())).thenReturn(null);
		MockHttpServletRequest req = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		req.setRequestURI(ProgrammaticGraphQlConfig.PATH);
		req.addHeader("Authorization", basic(KEY_ID, "wrong"));
		Run r = run(keys, req);
		assertRefused(r, "a wrong Basic on the GraphQL endpoint");
	}

	/** Was the no-credential challenge on the downloads and a pass-through on GraphQL; now the invalid-credential 401 on both. */
	@Test
	void anotherSchemeIsTheInvalidCredential401OnTheDownloadsAndGraphQl() throws Exception {
		Map<String, Object> reference = referenceRefusal();
		MockHttpServletRequest req = get(ARTIFACT_BASE + "/download", null);
		req.addHeader("Authorization", "Token abc");
		Run r = run(mock(ApiKeyService.class), req);
		assertRefused(r, "Token on a download");
		assertEquals(reference, answerOf(r));

		MockHttpServletRequest gql = new MockHttpServletRequest("POST", ProgrammaticGraphQlConfig.PATH);
		gql.setRequestURI(ProgrammaticGraphQlConfig.PATH);
		gql.addHeader("Authorization", "Token abc");
		Run g = run(mock(ApiKeyService.class), gql);
		assertRefused(g, "Token on the GraphQL endpoint");
		assertEquals(reference, answerOf(g));
	}
}
