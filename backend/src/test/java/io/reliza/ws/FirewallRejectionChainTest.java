/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.Filter;

/**
 * TPF-45 architecture 4.2: firewall rejections through the springSecurityFilterChain bean the container
 * invokes, so the handler under test is the one WebSecurity installed (a hand-built FilterChainProxy carries
 * Spring's stock handler). Every eager rule family, on both chains, answers the fixed 400 with no challenge,
 * no session, no body read and no sendError; an ordinary anonymous request keeps today's 401.
 */
class FirewallRejectionChainTest extends ProgrammaticBasicVerificationChainTestBase {

	private static final String BODY = "{\"message\":\"Bad Request\"}";
	private static final byte[] GRAPHQL_BODY = "{\"query\":\"{ __typename }\"}".getBytes(StandardCharsets.UTF_8);
	private static final byte[] TOKEN_BODY = "grant_type=client_credentials".getBytes(StandardCharsets.UTF_8);

	@Autowired @Qualifier("springSecurityFilterChain") Filter springSecurityFilterChain;

	private Run proxied(MockHttpServletRequest req) throws Exception {
		MockHttpServletResponse resp = spy(new MockHttpServletResponse());
		MockFilterChain after = new MockFilterChain();
		springSecurityFilterChain.doFilter(req, resp, after);
		return new Run(req, resp, after);
	}

	private static void assertRejected(Run run, String what) throws Exception {
		MockHttpServletResponse r = run.response();
		assertEquals(400, r.getStatus(), what);
		assertEquals(BODY, r.getContentAsString(StandardCharsets.UTF_8), what);
		assertEquals("application/json", r.getContentType(), what);
		assertEquals("no-store", r.getHeader(HttpHeaders.CACHE_CONTROL), what);
		assertNull(r.getHeader(HttpHeaders.WWW_AUTHENTICATE), what + ": no challenge");
		assertNull(r.getHeader(HttpHeaders.SET_COOKIE), what + ": no cookie");
		assertNull(run.request().getSession(false), what + ": no session");
		assertFalse(run.reached(), what + ": the chain after the proxy is not reached");
		verify(r, never()).sendError(anyInt());
		verify(r, never()).sendError(anyInt(), any());
	}

	private static void assertBodyUnread(Run run, byte[] content, String what) throws Exception {
		assertArrayEquals(content, run.request().getInputStream().readAllBytes(), what + ": the body was not read");
	}

	@Test
	void everyEagerRuleOnBrowserChainPathsAnswersTheFixed400() throws Exception {
		String[][] spellings = {
				{"GET", "/teapot;x=1"},
				{"GET", "/graphql%0a"},
				{"GET", "/teapot%25"},
				{"GET", "//teapot"},
				{"GET", "/api/../teapot"},
				{"GET", "/teapot/%2e/x"},
				{"FOO", "/teapot"},
		};
		for (String[] s : spellings) {
			for (String authorization : new String[] {null, "Bearer garbage"}) {
				String what = s[0] + " " + s[1] + (authorization == null ? " anonymous" : " with a bearer");
				assertRejected(proxied(spelled(s[0], s[1], freshAddress(), authorization)), what);
			}
		}
	}

	@Test
	void aRefusedPostIsNotRead() throws Exception {
		MockHttpServletRequest req = spelled("POST", "/graphql%0a", freshAddress(), null);
		req.setContentType("application/json");
		req.setContent(GRAPHQL_BODY);
		Run run = proxied(req);
		assertRejected(run, "POST /graphql%0a");
		assertBodyUnread(run, GRAPHQL_BODY, "POST /graphql%0a");
	}

	@Test
	void theProgrammaticChainsRejectionsGetTheSameAnswer() throws Exception {
		MockHttpServletRequest graphql = spelled("POST", "/api/programmatic/graphql;x=1", freshAddress(), "Bearer garbage");
		graphql.setContentType("application/json");
		graphql.setContent(GRAPHQL_BODY);
		clearInvocations(apiKeyService);
		Run g = proxied(graphql);
		assertRejected(g, "POST /api/programmatic/graphql;x=1");
		assertBodyUnread(g, GRAPHQL_BODY, "POST /api/programmatic/graphql;x=1");
		verify(apiKeyService, never()).isMatchingApiKey(any());

		MockHttpServletRequest token = spelled("POST", "/api/programmatic/token;x=1", freshAddress(),
				basic("FREEFORM__" + UUID.randomUUID() + "__ord__0", "x"));
		token.setContentType("application/x-www-form-urlencoded");
		token.setContent(TOKEN_BODY);
		clearInvocations(apiKeyService);
		Run t = proxied(token);
		assertRejected(t, "POST /api/programmatic/token;x=1");
		assertBodyUnread(t, TOKEN_BODY, "POST /api/programmatic/token;x=1");
		verify(apiKeyService, never()).isMatchingApiKey(any());
	}

	@Test
	void anOrdinaryAnonymousRequestKeepsTodays401() throws Exception {
		Run run = proxied(spelled("GET", "/teapot", freshAddress(), null));
		assertEquals(401, run.response().getStatus(), "GET /teapot");
		String challenge = run.response().getHeader(HttpHeaders.WWW_AUTHENTICATE);
		assertNotNull(challenge, "GET /teapot: a challenge");
		assertTrue(challenge.startsWith("Bearer"), "GET /teapot: " + challenge);
		assertEquals("", run.response().getContentAsString(StandardCharsets.UTF_8), "GET /teapot: the entry point writes no body");
		assertFalse(run.reached(), "GET /teapot: not reached");
	}

	/**
	 * TPF-54 architecture 4.1: the browser chain no longer permits /api/programmatic/v1/**. A path under the prefix
	 * that the programmatic chain's matcher does not name is served by the browser chain and refused there by
	 * authorization: the entry point's Bearer 401 (not the programmatic filter's challenge), counted by the limiter
	 * first, never reaching a servlet, and a Basic off /graphql is not verified.
	 */
	@Test
	void anUnmatchedPathUnderTheProgrammaticPrefixIsTheBrowserChains401() throws Exception {
		for (String path : new String[] {"/api/programmatic/v1/nope", "/api/programmatic/v1/nope/deeper", "/api/programmatic/v1"}) {
			for (String authorization : new String[] {null, basic("FREEFORM__" + UUID.randomUUID() + "__ord__0", "x")}) {
				String what = "GET " + path + (authorization == null ? " anonymous" : " with a Basic");
				MockHttpServletRequest req = spelled("GET", path, freshAddress(), authorization);
				assertSame(filterChain, servedBy(req), what + ": served by the browser chain");
				clearInvocations(apiKeyService);
				Run run = proxied(req);
				assertEquals(401, run.response().getStatus(), what);
				String challenge = run.response().getHeader(HttpHeaders.WWW_AUTHENTICATE);
				assertNotNull(challenge, what + ": a challenge");
				assertTrue(challenge.startsWith("Bearer"), what + ": " + challenge);
				assertNotEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE, challenge, what + ": not the programmatic filter's");
				assertNotEquals(ProgrammaticAuthenticationFilter.NO_CREDENTIAL_CHALLENGE, challenge, what + ": not the programmatic filter's");
				assertEquals("", run.response().getContentAsString(StandardCharsets.UTF_8), what + ": the entry point writes no body");
				assertFalse(run.reached(), what + ": not reached");
				assertNotNull(run.remaining(), what + ": counted by the limiter before authorization");
				if (authorization != null) verify(apiKeyService, never()).isMatchingApiKey(any());
			}
		}
	}
}
