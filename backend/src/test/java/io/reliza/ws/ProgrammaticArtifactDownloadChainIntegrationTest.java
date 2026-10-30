/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import io.reliza.model.UserPermission.PermissionFunction;
import io.reliza.model.UserPermission.PermissionType;

/**
 * SCORE-17: which chain answers what. The programmatic artifact downloads moved to the stateless
 * programmatic chain; nothing else did. The IdP user token stays on the browser's download path
 * (ADR-2), a ReARM access token still opens no browser-chain endpoint, and the rate limit still counts the
 * downloads. On merging main into the TPF integration branch, Basic on the programmatic GraphQL endpoint is
 * verified by the filter (TPF-27) rather than passed through to the resolvers, and any other scheme is the
 * one invalid-credential 401 on the downloads and on GraphQL alike.
 */
class ProgrammaticArtifactDownloadChainIntegrationTest extends ProgrammaticArtifactDownloadTestBase {

	@Autowired private RateLimitingFilter rateLimitingFilter;

	private static String manual(String programmaticPath) {
		return programmaticPath.replace("/api/programmatic/", "/api/manual/");
	}

	/** T-7: no Authorization header is a 401 with the programmatic challenge, on both paths. */
	@Test
	void noCredentialIs401WithTheBearerChallenge() throws Exception {
		for (String path : List.of(download(w.releaseArtifact()), rawDownload(w.releaseArtifact()))) {
			HttpResponse<String> r = get(path, null);
			assertStatus(401, r);
			assertEquals("Bearer realm=\"rearm\"", header(r, "WWW-Authenticate"));
			assertEquals("no-store", header(r, "Cache-Control"));
			assertTrue(r.body().contains("invalid_request"), r.body());
		}
	}

	/** T-7 / ADR-2: the IdP user token is an invalid bearer on the programmatic path, and still downloads on the browser's path. */
	@Test
	void theIdpUserTokenDownloadsOnTheManualPathOnly() throws Exception {
		for (String path : List.of(download(w.releaseArtifact()), rawDownload(w.releaseArtifact()))) {
			HttpResponse<String> programmatic = get(path, bearer(idpToken));
			assertStatus(401, programmatic);
			assertTrue(header(programmatic, "WWW-Authenticate").startsWith(INVALID_TOKEN));
			assertStatus(200, get(manual(path), bearer(idpToken)), "regression: the browser path is unchanged");
		}
	}

	/** T-10: a ReARM access token opens no endpoint of the browser chain: /graphql and the manual downloads stay 401. */
	@Test
	void aProgrammaticTokenStillOpensNoBrowserChainEndpoint() throws Exception {
		String token = exchanged(w.orgKey());
		assertStatus(200, get(download(w.releaseArtifact()), bearer(token)), "the same token on the programmatic path");
		for (String path : List.of(manual(download(w.releaseArtifact())), manual(rawDownload(w.releaseArtifact())))) {
			HttpResponse<String> r = get(path, bearer(token));
			assertStatus(401, r);
			assertFalse(header(r, "WWW-Authenticate").contains("realm=\"rearm\""), "answered by the browser chain's resource server");
		}
		HttpResponse<String> graphql = post("/graphql", "{\"query\":\"{ __typename }\"}", bearer(token));
		assertStatus(401, graphql);
		assertFalse(header(graphql, "WWW-Authenticate").contains("realm=\"rearm\""));
	}

	/**
	 * T-10: on the programmatic GraphQL endpoint a wrong Basic secret is refused by the filter with the
	 * uniform invalid-credential 401 (TPF-27; it reached the resolvers on main before the merge); a valid
	 * bearer still works and a garbage bearer is the same refusal.
	 */
	@Test
	void basicOnTheProgrammaticGraphQlEndpointIsVerifiedByTheFilter() throws Exception {
		String query = "{\"query\":\"{ exportCatalogProgrammatic { components { name } } }\"}";
		HttpResponse<String> wrong = post(ProgrammaticGraphQlConfig.PATH, query, basic(w.orgKey().id(), "wrong"));
		assertStatus(401, wrong);
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE, header(wrong, "WWW-Authenticate"));
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, wrong.body());
		assertNull(header(wrong, "Deprecation"), "no deprecation header on a refusal");
		assertStatus(200, post(ProgrammaticGraphQlConfig.PATH, query, bearer(exchanged(w.orgKey()))));
		HttpResponse<String> garbage = post(ProgrammaticGraphQlConfig.PATH, query, bearer("not-a-token"));
		assertStatus(401, garbage);
		assertTrue(header(garbage, "WWW-Authenticate").startsWith(INVALID_TOKEN));
	}

	/**
	 * A scheme the filter does not take is the uniform invalid-credential 401 (TPF-27), on the downloads
	 * and on the GraphQL endpoint alike (main answered it like no credential on the downloads and passed
	 * it on to the GraphQL resolvers; the TPF answer was kept on the merge).
	 */
	@Test
	void anotherSchemeIsTheInvalidCredential401() throws Exception {
		HttpResponse<String> r = get(download(w.releaseArtifact()), "Token " + w.orgKey().secret());
		assertStatus(401, r);
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE, header(r, "WWW-Authenticate"));
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_BODY, r.body());
		HttpResponse<String> gql = post(ProgrammaticGraphQlConfig.PATH, "{\"query\":\"{ __typename }\"}", "Token x");
		assertStatus(401, gql);
		assertEquals(ProgrammaticAuthenticationFilter.INVALID_CREDENTIAL_CHALLENGE, header(gql, "WWW-Authenticate"));
	}

	/**
	 * The rate limit counts a download once per request, per key, before any credential is checked
	 * (TPF-26: the limiter runs in front of the programmatic filter on every request of the chain), so a
	 * wrong Basic secret is counted too.
	 */
	@Test
	void theRateLimitCountsDownloadsAndBasicGuessesOncePerRequest() throws Exception {
		// the bucket refills one request every 300 ms (100 per 30 s): paused, so a slow request does not read as an uncounted one
		rateLimitingFilter.clock.pause();
		try {
			countsDownloadsAndBasicGuessesOncePerRequest();
		} finally {
			rateLimitingFilter.clock.resume();
		}
	}

	private void countsDownloadsAndBasicGuessesOncePerRequest() throws Exception {
		Key k = freeform(w.org(), orgWide(w.org(), PermissionType.READ_ONLY, PermissionFunction.ARTIFACT_DOWNLOAD));
		HttpResponse<String> first = get(rawDownload(w.releaseArtifact()), basic(k));
		HttpResponse<String> guess = get(rawDownload(w.releaseArtifact()), basic(k.id(), "wrong"));
		HttpResponse<String> second = get(rawDownload(w.releaseArtifact()), basic(k));
		assertStatus(200, first);
		assertStatus(401, guess);
		assertStatus(200, second);
		int a = Integer.parseInt(header(first, "X-Rate-Limit-Remaining"));
		assertEquals(a - 1, Integer.parseInt(header(guess, "X-Rate-Limit-Remaining")), "the guess is counted, once");
		assertEquals(a - 2, Integer.parseInt(header(second, "X-Rate-Limit-Remaining")), "one bucket per key id, one count per request");

		String token = exchanged(k);
		HttpResponse<String> t1 = get(download(w.releaseArtifact()), bearer(token));
		HttpResponse<String> t2 = get(download(w.releaseArtifact()), bearer(token));
		assertNotNull(header(t1, "X-Rate-Limit-Remaining"), "a bearer download is counted");
		assertEquals(Integer.parseInt(header(t1, "X-Rate-Limit-Remaining")) - 1, Integer.parseInt(header(t2, "X-Rate-Limit-Remaining")));
	}
}
