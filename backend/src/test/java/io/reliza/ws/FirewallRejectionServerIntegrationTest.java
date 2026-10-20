/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

/**
 * TPF-45 architecture 4.3: firewall rejections through a real Tomcat, the only rig with a real container
 * error dispatch. Before TPF-45 each refused spelling was re-dispatched to /error and answered there by the
 * browser chain with a 401, a Bearer challenge and a new session cookie, whatever the method (Tomcat 11 makes
 * every error dispatch a GET, so the request cache saves it); now it is the fixed 400 with neither. An ordinary
 * anonymous request keeps today's 401.
 */
class FirewallRejectionServerIntegrationTest extends ProgrammaticArtifactDownloadTestBase {

	private static final String BODY = "{\"message\":\"Bad Request\"}";
	private static final String JSON_BODY = "{\"query\":\"{ __typename }\"}";
	/** Fresh addresses from 203.0.113.0/24 (documentation range), so no case shares a limiter bucket. */
	private static final AtomicInteger ADDRESSES = new AtomicInteger(1);

	private HttpResponse<String> send(String method, String rawPath, String authorization, String jsonBody) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + rawPath))
				.header("X-Forwarded-For", "203.0.113." + ADDRESSES.getAndIncrement())
				.method(method, null == jsonBody ? HttpRequest.BodyPublishers.noBody()
						: HttpRequest.BodyPublishers.ofString(jsonBody));
		if (null != jsonBody) b.header(HttpHeaders.CONTENT_TYPE, "application/json");
		if (null != authorization) b.header(HttpHeaders.AUTHORIZATION, authorization);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static void assertCheap400(HttpResponse<String> r, String what) {
		assertStatus(400, r, what);
		assertEquals(BODY, r.body(), what);
		assertEquals("application/json", header(r, HttpHeaders.CONTENT_TYPE), what);
		assertEquals("no-store", header(r, HttpHeaders.CACHE_CONTROL), what);
		assertNull(header(r, HttpHeaders.WWW_AUTHENTICATE), what + ": no challenge");
		assertNull(header(r, HttpHeaders.SET_COOKIE), what + ": no session cookie");
	}

	@Test
	void anonymousGetWithAPathParameterIsTheCheap400() throws Exception {
		assertCheap400(send("GET", "/teapot;x=1", null, null), "GET /teapot;x=1");
	}

	@Test
	void anonymousGraphqlGetWithALineBreakIsTheCheap400() throws Exception {
		assertCheap400(send("GET", "/graphql%0a", null, null), "GET /graphql%0a");
	}

	@Test
	void programmaticPostWithAPathParameterIsTheCheap400() throws Exception {
		assertCheap400(send("POST", "/api/programmatic/graphql;x=1", "Bearer garbage", JSON_BODY),
				"POST /api/programmatic/graphql;x=1");
	}

	@Test
	void anOrdinaryAnonymousGetKeepsTodays401() throws Exception {
		HttpResponse<String> r = send("GET", "/teapot", null, null);
		assertStatus(401, r, "GET /teapot");
		String challenge = header(r, HttpHeaders.WWW_AUTHENTICATE);
		assertNotNull(challenge, "GET /teapot: a challenge");
		assertTrue(challenge.startsWith("Bearer"), "GET /teapot: " + challenge);
	}

	/**
	 * TPF-54 architecture 4.2: an anonymous GET of an unmatched path under /api/programmatic/v1/ is the browser
	 * chain's 401 on the wire, neither a 404 nor the programmatic filter's challenge. It passes before and after the
	 * permit's removal (before, the 401 came through the /error dispatch of the 404); no assertion on Set-Cookie.
	 */
	@Test
	void anAnonymousGetOfAnUnmatchedProgrammaticPathIsTheBrowserChains401() throws Exception {
		HttpResponse<String> r = send("GET", "/api/programmatic/v1/nope", null, null);
		assertStatus(401, r, "GET /api/programmatic/v1/nope");
		String challenge = header(r, HttpHeaders.WWW_AUTHENTICATE);
		assertNotNull(challenge, "GET /api/programmatic/v1/nope: a challenge");
		assertTrue(challenge.startsWith("Bearer"), "GET /api/programmatic/v1/nope: " + challenge);
		assertNotEquals(ProgrammaticAuthenticationFilter.NO_CREDENTIAL_CHALLENGE, challenge,
				"GET /api/programmatic/v1/nope: not the programmatic filter's");
	}
}
