/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
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
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.common.OAuthErrors;
import io.reliza.common.OAuthQueryGuard;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.model.ApiKeyData;
import io.reliza.model.ApiKeyData.ApiKeySecret;
import io.reliza.model.Organization;
import io.reliza.model.WhoUpdated;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.LicenseStatus;
import io.reliza.ws.oss.TestInitializer;
import jakarta.servlet.http.HttpServlet;
import tools.jackson.databind.json.JsonMapper;

/**
 * TPF-26 (architecture-5 section 4.2): {@link RateLimitingFilter} runs on the programmatic chain in front of
 * {@link ProgrammaticAuthenticationFilter}, counts every request there once, keys a bearer by its verified key
 * and every Basic (header or token form field) by the canonical key id, and the token controller reads its
 * form only by exact name. Driven through the real chains of the application context. The limiter's bucket
 * clock is paused for each case so no burst is refilled while it runs, and every count uses fresh addresses
 * and fresh keys, because the buckets live in the one filter bean the cached context shares. Since TPF-27 the
 * authentication filter verifies Basic: cases 2 and 7 count the 401s and the isMatchingApiKey calls behind the
 * limiter, and case 6 pins that the browser chain verifies Basic on /graphql (TPF-29, {@link LegacyGraphqlBasicFilter}).
 * TPF-27's own cases are in {@link ProgrammaticBasicFilterVerificationChainTest}, TPF-29's in {@link LegacyGraphqlBasicChainTest}.
 */
public class ProgrammaticBasicVerificationChainTest extends ProgrammaticBasicVerificationChainTestBase {

	// ---- case 1: order by structure

	@Test
	public void theLimiterPrecedesTheAuthenticationFilterOnTheProgrammaticChain() {
		List<jakarta.servlet.Filter> filters = programmaticFilterChain.getFilters();
		List<jakarta.servlet.Filter> limiters = filters.stream().filter(RateLimitingFilter.class::isInstance).toList();
		List<jakarta.servlet.Filter> auth = filters.stream().filter(ProgrammaticAuthenticationFilter.class::isInstance).toList();
		assertEquals(1, limiters.size(), "exactly one limiter on the programmatic chain");
		assertEquals(1, auth.size(), "exactly one programmatic authentication filter");
		assertTrue(filters.indexOf(limiters.get(0)) < filters.indexOf(auth.get(0)), "the limiter runs first: " + filters);
		assertSame(rateLimitingFilter, limiters.get(0), "the chain carries the bean itself");

		List<jakarta.servlet.Filter> browser = filterChain.getFilters();
		List<jakarta.servlet.Filter> browserLimiters = browser.stream().filter(RateLimitingFilter.class::isInstance).toList();
		assertEquals(1, browserLimiters.size());
		assertSame(rateLimitingFilter, browserLimiters.get(0), "one instance on both chains");
		int bearer = -1;
		for (int i = 0; i < browser.size(); i++) if (browser.get(i) instanceof BearerTokenAuthenticationFilter) bearer = i;
		assertTrue(bearer >= 0 && browser.indexOf(rateLimitingFilter) > bearer, "the browser chain is unchanged: after the bearer filter");
		// TPF-29: the /graphql Basic filter runs behind the limiter on the browser chain
		List<jakarta.servlet.Filter> legacy = browser.stream().filter(LegacyGraphqlBasicFilter.class::isInstance).toList();
		assertEquals(1, legacy.size(), "exactly one /graphql Basic filter on the browser chain");
		assertTrue(browser.indexOf(legacy.get(0)) > browser.indexOf(rateLimitingFilter), "the /graphql Basic filter runs after the limiter: " + browser);
	}

	// ---- case 2: order by behaviour, a burst of unknown Basic ids (TPF-27 case 12: refused by the filter behind the limiter)
	// An unknown id takes the fast path inside isMatchingApiKey (no org resolves, no Argon2); that is not observable
	// through the spy, so the case counts the calls and keeps its wall-clock shape: it runs in seconds.

	@Test
	public void theHundredAndFirstBasicIsRefusedBeforeTheAuthenticationFilter() throws Exception {
		clearInvocations(apiKeyService);
		String graphqlId = "FREEFORM__" + UUID.randomUUID() + "__ord__" + UUID.randomUUID();
		String address = freshAddress();
		for (int i = 0; i < 100; i++) {
			Run r = programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, address, basic(graphqlId, "x")));
			assertInvalidToken(r, String.valueOf(99 - i), "request " + (i + 1) + ": the filter refuses an unknown Basic id");
		}
		assertLimited(programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, address, basic(graphqlId, "x"))), "the 101st on GraphQL");

		String downloadId = "FREEFORM__" + UUID.randomUUID() + "__ord__" + UUID.randomUUID();
		String download = "/api/programmatic/v1/artifact/" + UUID.randomUUID() + "/download";
		String address2 = freshAddress();
		for (int i = 0; i < 100; i++) {
			Run r = programmatic(withAuthorization("GET", download, address2, basic(downloadId, "x")));
			assertInvalidToken(r, String.valueOf(99 - i), "download " + (i + 1));
		}
		assertLimited(programmatic(withAuthorization("GET", download, address2, basic(downloadId, "x"))), "the 101st download");
		// one call per request that reached the filter, none for the two 429s
		verify(apiKeyService, times(200)).isMatchingApiKey(any());
	}

	// ---- case 3: a bearer is keyed by its key, not the address

	@Test
	public void aBearerIsCountedByItsKeyBeforeTheFilterVerifiesIt() throws Exception {
		Key a = freeformKey();
		Key b = freeformKey();
		String tokenA = mint(a.uuid());
		String first = freshAddress();
		String second = freshAddress();
		for (int i = 0; i < 100; i++) {
			Run r = programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, i % 2 == 0 ? first : second, "Bearer " + tokenA));
			assertTrue(r.reached(), "request " + (i + 1) + ": " + r.response().getStatus());
			assertEquals(String.valueOf(99 - i), r.remaining(), "request " + (i + 1));
			assertEquals(a.uuid(), assertInstanceOf(AuthHeaderParse.class, r.principal()).getVerifiedKeyUuid());
		}
		assertLimited(programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, second, "Bearer " + tokenA)), "the 101st of key A");

		String tokenB = mint(b.uuid());
		Run rb = programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, first, "Bearer " + tokenB));
		assertTrue(rb.reached());
		assertEquals("99", rb.remaining(), "key B from the same addresses has its own bucket");

		assertLimited(programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, freshAddress(), basic(a.id(), "any"))),
				"the Basic exchange text of key A shares the bearer's bucket");

		assertInvalidToken(programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, freshAddress(), "Bearer garbage")), "99",
				"a garbage bearer");
		assertInvalidToken(programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, freshAddress(), "Bearer " + idpShapedToken())), "99",
				"an IdP-shaped bearer");
	}

	// ---- case 4: nothing else moved

	@Test
	public void theUnfilteredPathsAreCountedAndPassedOn() throws Exception {
		for (String[] mp : List.of(new String[] {"POST", ProgrammaticTokenController.PATH}, new String[] {"POST", DeviceAuthorizationController.REVOKE_PATH},
				new String[] {"GET", ProgrammaticSchemaController.PATH}, new String[] {"POST", "/api/programmatic/v1/webhook/x/y"})) {
			Run r = programmatic(request(mp[0], mp[1], freshAddress()));
			assertTrue(r.reached(), mp[1]);
			assertEquals("99", r.remaining(), mp[1]);
			assertNull(r.principal(), mp[1]);
		}
		String address = freshAddress();
		for (int i = 0; i < 10; i++) {
			assertTrue(programmatic(request("POST", DeviceAuthorizationController.DEVICE_CODE_PATH, address)).reached(), "start " + (i + 1));
		}
		Run eleventh = programmatic(request("POST", DeviceAuthorizationController.DEVICE_CODE_PATH, address));
		assertEquals(429, eleventh.response().getStatus());
		assertEquals(LIMITED_BODY, eleventh.response().getContentAsString(StandardCharsets.UTF_8));
		assertFalse(eleventh.reached());
		assertEquals("89", programmatic(request("GET", ProgrammaticSchemaController.PATH, address)).remaining());
	}

	// ---- case 5: one count per request, the servlet-level pass emulated

	@Test
	public void theServletLevelPassOfTheSameBeanDoesNotCountAgain() throws Exception {
		Key k = freeformKey();
		String token = mint(k.uuid());
		String address = freshAddress();
		for (int i = 0; i < 10; i++) {
			MockFilterChain after = new MockFilterChain(new HttpServlet() {
				private static final long serialVersionUID = 1L;
			}, rateLimitingFilter);
			Run r = through(programmaticFilterChain, withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, address, "Bearer " + token), after);
			assertTrue(r.reached(), "request " + (i + 1));
			assertEquals(String.valueOf(99 - i), r.remaining(), "one count per request, not two");
		}
	}

	// ---- case 6: the browser chain verifies Basic on /graphql only (TPF-29)

	@Test
	public void theBrowserChainVerifiesBasicOnGraphqlOnly() throws Exception {
		// the browser chain has no security matcher (it matches every request), so which chain serves a path is the
		// first matching chain in @Order: the programmatic chain for the programmatic paths, the browser chain otherwise
		for (String path : List.of(ProgrammaticTokenController.PATH, DeviceAuthorizationController.REVOKE_PATH, ProgrammaticSchemaController.PATH,
				"/api/programmatic/v1/webhook/x/y", ProgrammaticGraphQlConfig.PATH)) {
			assertSame(programmaticFilterChain, servedBy(request("POST", path, freshAddress())), path);
		}
		assertSame(filterChain, servedBy(request("GET", "/api/manual/v1/artifact/" + UUID.randomUUID() + "/download", freshAddress())));
		assertSame(filterChain, servedBy(request("GET", "/graphql", freshAddress())));

		Key k = freeformKey();
		Run r = through(filterChain, withAuthorization("GET", "/graphql", freshAddress(), basic(k.id(), "any")), new MockFilterChain());
		assertRefused(r, "a wrong secret on /graphql");
		assertEquals("99", r.remaining(), "keyed by the canonical id there as before");
		// TPF-29: LegacyGraphqlBasicFilter verifies Basic on /graphql behind the limiter, so a wrong secret is the
		// invalid-credential 401 there too and no unverified parse reaches the resolvers
		assertNull(r.principal(), "no principal for a wrong secret");

		String address = freshAddress();
		assertAdmittedBasic(through(filterChain, withAuthorization("GET", "/graphql", address, basic(k.id(), k.secret())), new MockFilterChain()),
				k, address, "a valid key on /graphql");

		String forged = basic("FREEFORM__" + UUID.randomUUID() + "__ORGANIZATION__" + testInitializer.obtainOrganization().getUuid() + "__ord__0", "x");
		assertRefused(through(filterChain, spelled("GET", "/%67raphql", freshAddress(), forged), new MockFilterChain()),
				"a forged org-segment id on the spelling /%67raphql");
	}

	// ---- case 7: one key, one bucket, whatever the text (TPF-27 case 13: every spelling reaches the row, 401 each)

	@Test
	public void everySpellingOfOneKeyDrawsDownOneBucket() throws Exception {
		Key c = freeformKey();
		clearInvocations(apiKeyService);
		String o = c.object().toString();
		List<String> spellings = List.of(c.id(), "FREEFORM__" + o.toUpperCase(Locale.ROOT) + "__ord__" + c.order(),
				"FREEFORM__" + o + "__x__ord__" + c.order(), "FREEFORM__" + o + "__ORGANIZATION__" + c.key().getOrg() + "__ord__" + c.order());
		assertEquals("FREEFORM__" + o + "__ord__" + c.order(), c.id());
		for (int i = 0; i < 100; i++) {
			Run r = programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, freshAddress(), basic(spellings.get(i % 4), "s" + i)));
			assertInvalidToken(r, String.valueOf(99 - i), "request " + (i + 1) + " as " + spellings.get(i % 4));
		}
		assertLimited(programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, freshAddress(), basic(spellings.get(0), "s"))), "the 101st");
		assertLimited(programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, freshAddress(), "Bearer " + mint(c.uuid()))),
				"the key's bearer");
		Run noOrder = programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, freshAddress(), basic("FREEFORM__" + o, "s")));
		assertInvalidToken(noOrder, "99", "the no-order form has its own bucket");
		// the per-row bound: 100 Argon2 matches for the burst, none for the 429s; the no-order form is still a service call
		// but is refused before any lookup (TPF-51), so it runs no Argon2
		verify(apiKeyService, times(101)).isMatchingApiKey(any());
	}

	// ---- TPF-51 (architecture-1 4.3): an orderless id of an ordered type is the one 401, refused before any lookup

	@Test
	public void anOrderlessFreeformIdIsRefusedBeforeAnyLookup() throws Exception {
		Key first = freeformKey();
		UUID org = first.org();
		Key second = freeformKey(org);
		int firstAccesses = keyAccessCount(first.uuid());
		int secondAccesses = keyAccessCount(second.uuid());
		clearInvocations(apiKeyService);
		String orderless = basic("FREEFORM__" + org, first.secret());
		// row 1: the reference refusal on the GraphQL path, the no-order bucket
		assertInvalidToken(programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, freshAddress(), orderless)), "99",
				"the orderless id on two keys, graphql");
		// row 2: the same credential on a download, the same bucket
		assertInvalidToken(programmatic(withAuthorization("GET", "/api/programmatic/v1/artifact/" + UUID.randomUUID() + "/download",
				freshAddress(), orderless)), "98", "the orderless id on two keys, download");
		// row 3: one service call each, no access recorded, no secret touched (no Argon2 match ran)
		verify(apiKeyService, times(2)).isMatchingApiKey(any());
		assertEquals(firstAccesses, keyAccessCount(first.uuid()));
		assertEquals(secondAccesses, keyAccessCount(second.uuid()));
		assertNull(firstSecret(first.uuid()).getLastUsedDate(), "the first key's secret untouched");
		assertNull(firstSecret(second.uuid()).getLastUsedDate(), "the second key's secret untouched");
		// row 4: each key by its own id is admitted
		for (Key k : List.of(first, second)) {
			String address = freshAddress();
			assertAdmittedBasic(programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, address, basic(k.id(), k.secret()))),
					k, address, "own id of " + k.uuid());
		}
		// row 5: a fresh org with one key, the orderless id with its real secret (was admitted through the only row)
		Key only = freeformKey();
		assertInvalidToken(programmatic(withAuthorization("POST", ProgrammaticGraphQlConfig.PATH, freshAddress(),
				basic("FREEFORM__" + only.org(), only.secret()))), "99", "the orderless id on one key");
	}

	// ---- case 8: the token form carrier is charged to the key, the chain in front of the real controller

	private record Answer(int status, String raw, Map<String, Object> body, MockHttpServletResponse response) {
		String remaining() {
			return response.getHeader(REMAINING);
		}

		String cacheControl() {
			return String.join(", ", response.getHeaders(HttpHeaders.CACHE_CONTROL));
		}
	}

	private static MockHttpServletRequestBuilder tokenPost(String body) {
		String address = freshAddress();
		return post(ProgrammaticTokenController.PATH).contentType(MediaType.APPLICATION_FORM_URLENCODED).content(body)
				.header("X-Forwarded-For", freshAddress())
				.with(r -> { r.setRemoteAddr(address); return r; });
	}

	@SuppressWarnings("unchecked")
	private static Answer send(MockMvc mvc, MockHttpServletRequestBuilder b) throws Exception {
		MockHttpServletResponse r = mvc.perform(b).andReturn().getResponse();
		String raw = r.getContentAsString(StandardCharsets.UTF_8);
		Map<String, Object> body = raw.isEmpty() || !raw.startsWith("{") ? Map.of() : JSON.readValue(raw, Map.class);
		return new Answer(r.getStatus(), raw, body, r);
	}

	private void assertToken(Answer a, String what) {
		assertEquals(200, a.status(), what + ": " + a.raw());
		assertTrue(apiTokenService.verify((String) a.body().get("access_token"), ApiTokenService.AUD_PROGRAMMATIC).isPresent(), what);
	}

	private static void assertLimited(Answer a, String what) {
		assertEquals(429, a.status(), what + ": " + a.raw());
		assertEquals(LIMITED_BODY, a.raw(), what);
	}

	private static void assertRepeated(Answer a, String name, String... absent) {
		assertEquals(400, a.status(), a.raw());
		assertEquals(OAuthErrors.INVALID_REQUEST, a.body().get("error"), a.raw());
		String description = (String) a.body().get("error_description");
		assertTrue(description.contains(name), description);
		for (String s : absent) assertFalse(a.raw().contains(s), "the answer does not repeat a value");
		assertTrue(a.cacheControl().contains("no-store"), a.cacheControl());
		assertFalse(a.body().containsKey("access_token"));
		assertNotNull(a.remaining(), "admitted by the limiter");
	}

	private static void assertNoCredential(Answer a, String... absent) {
		assertEquals(401, a.status(), a.raw());
		assertEquals(OAuthErrors.INVALID_CLIENT, a.body().get("error"), a.raw());
		assertEquals("client authentication required (HTTP Basic with the API key id and secret)", a.body().get("error_description"));
		assertEquals("Basic realm=\"rearm\"", a.response().getHeader(HttpHeaders.WWW_AUTHENTICATE));
		assertTrue(a.cacheControl().contains("no-store"), a.cacheControl());
		assertFalse(a.body().containsKey("access_token"));
		assertNotNull(a.remaining(), "admitted by the limiter");
		for (String s : absent) assertFalse(a.raw().contains(s), "the answer does not repeat a value");
	}

	@Test
	public void theTokenFormCarrierIsChargedToTheKeyWhateverTheAddress() throws Exception {
		MockMvc mvc = MockMvcBuilders.standaloneSetup(tokenController).addFilters(new FilterChainProxy(programmaticFilterChain)).build();
		Key d = freeformKey();
		Key e = freeformKey();
		Key f = freeformKey();
		clearInvocations(apiKeyService);

		// the single-valued form carrier, 101 addresses and X-Forwarded-For values
		String dBody = "grant_type=client_credentials&client_id=" + enc(d.id()) + "&client_secret=" + enc(d.secret());
		for (int i = 0; i < 100; i++) {
			Answer a = send(mvc, tokenPost(dBody));
			assertToken(a, "post " + (i + 1));
			assertEquals(String.valueOf(99 - i), a.remaining(), "post " + (i + 1));
		}
		Answer refused = send(mvc, tokenPost(dBody));
		assertLimited(refused, "the 101st");
		assertFalse(refused.raw().contains("access_token"));
		verify(apiKeyService, times(100)).isMatchingApiKey(any());
		assertLimited(send(mvc, tokenPost("grant_type=client_credentials").header(HttpHeaders.AUTHORIZATION, basic(d.id(), d.secret()))),
				"the Basic exchange of the same key");
		assertLimited(send(mvc, tokenPost("grant_type=client_credentials&client_id=" + enc(d.id() + ":junk") + "&client_secret=" + enc(d.secret()))),
				"a colon form of the same key");
		verify(apiKeyService, times(100)).isMatchingApiKey(any());
		Answer unknown = send(mvc, tokenPost("grant_type=client_credentials&client_id=" + enc("FREEFORM__" + UUID.randomUUID() + "__ord__" + UUID.randomUUID())
				+ "&client_secret=x"));
		assertEquals(401, unknown.status(), unknown.raw());
		assertEquals(OAuthErrors.INVALID_CLIENT, unknown.body().get("error"));
		assertEquals("99", unknown.remaining(), "an unknown key has its own bucket");
		verify(apiKeyService, times(101)).isMatchingApiKey(any());

		// the repeated-parameter burst (F-4): the join would name E; every one is refused before any parse
		String eTail = "__" + e.key().getOrg() + "__ord__" + e.order();
		String eBody = "grant_type=client_credentials&client_id=" + enc("FREEFORM__" + e.object() + "__ORGANIZATION") + "&client_id=" + enc(eTail)
				+ "&client_secret=" + enc(e.secret());
		for (int i = 0; i < 101; i++) {
			assertRepeated(send(mvc, tokenPost(eBody)), "client_id", e.object().toString(), e.secret());
		}
		verify(apiKeyService, times(101)).isMatchingApiKey(any());
		assertRepeated(send(mvc, tokenPost("grant_type=client_credentials&client_id=" + enc(e.id()) + "&client_secret=" + enc(e.secret())
				+ "&client_secret=x")), "client_secret", e.secret());
		verify(apiKeyService, times(101)).isMatchingApiKey(any());
		assertToken(send(mvc, tokenPost("grant_type=client_credentials&client_id=" + enc(e.id()) + "&client_secret=" + enc(e.secret()))),
				"E still exchanges");
		verify(apiKeyService, times(102)).isMatchingApiKey(any());

		// the binder-spelling burst (F-5): names the binder read before decision 7 are read by nobody
		String fBody = "grant_type=client_credentials&" + enc("client_id[]") + "=" + enc(f.id()) + "&" + enc("client_secret[]") + "=" + enc(f.secret());
		for (int i = 0; i < 101; i++) {
			assertNoCredential(send(mvc, tokenPost(fBody)), f.secret(), f.id());
		}
		verify(apiKeyService, times(102)).isMatchingApiKey(any());
		assertNoCredential(send(mvc, tokenPost("grant_type=client_credentials&" + enc("client_id[0]") + "=" + enc(f.id()) + "&client_secret=" + enc(f.secret()))));
		assertNoCredential(send(mvc, tokenPost("grant_type=client_credentials&" + enc("client_id.value") + "=" + enc(f.id()) + "&client_secret=" + enc(f.secret()))));
		Answer secretSuffixed = send(mvc, tokenPost("grant_type=client_credentials&client_id=" + enc(f.id()) + "&" + enc("client_secret[]") + "=" + enc(f.secret())));
		assertNoCredential(secretSuffixed);
		assertEquals("99", secretSuffixed.remaining(), "the exact client_id is charged to F's bucket (an overcharge)");
		verify(apiKeyService, times(102)).isMatchingApiKey(any());
		assertToken(send(mvc, tokenPost("grant_type=client_credentials&client_id=" + enc(f.id()) + "&client_secret=" + enc(f.secret()))),
				"F still exchanges");
		verify(apiKeyService, times(103)).isMatchingApiKey(any());
	}

	// ---- case 9: a header beside form credentials is refused before any lookup and charged to the header's key (TPF-50)

	private static void assertOneMethod(Answer a, String what, String... absent) {
		assertEquals(400, a.status(), what + ": " + a.raw());
		assertEquals(OAuthErrors.INVALID_REQUEST, a.body().get("error"), what);
		assertEquals(OAuthQueryGuard.ONE_METHOD_DESCRIPTION, a.body().get("error_description"), what);
		assertEquals(2, a.body().size(), what);
		assertTrue(a.cacheControl().contains("no-store"), what + ": " + a.cacheControl());
		assertNull(a.response().getHeader(HttpHeaders.WWW_AUTHENTICATE), what);
		assertFalse(a.body().containsKey("access_token"), what);
		assertNotNull(a.remaining(), what + ": admitted by the limiter");
		for (String s : absent) assertFalse(a.raw().contains(s), what + ": the answer does not repeat a value");
	}

	@Test
	public void aHeaderBesideFormCredentialsIsRefusedAndChargedToTheHeadersKey() throws Exception {
		MockMvc mvc = MockMvcBuilders.standaloneSetup(tokenController).addFilters(new FilterChainProxy(programmaticFilterChain)).build();
		Key d = freeformKey();
		Key e = freeformKey();
		clearInvocations(apiKeyService);
		String dBasic = basic(d.id(), d.secret());

		Answer row1 = send(mvc, tokenPost("grant_type=client_credentials&client_secret=" + enc("not-" + d.secret())).header(HttpHeaders.AUTHORIZATION, dBasic));
		assertOneMethod(row1, "row 1", d.secret(), e.secret(), e.id());
		assertEquals("99", row1.remaining(), "row 1: D's bucket");
		Answer row2 = send(mvc, tokenPost("grant_type=client_credentials&client_id=" + enc(e.id()) + "&client_secret=" + enc(e.secret()))
				.header(HttpHeaders.AUTHORIZATION, dBasic));
		assertOneMethod(row2, "row 2", d.secret(), e.secret(), e.id());
		assertEquals("98", row2.remaining(), "row 2: D's bucket again, never E's");
		Answer row3 = send(mvc, tokenPost("grant_type=client_credentials&client_id=" + enc(d.id())).header(HttpHeaders.AUTHORIZATION, dBasic));
		assertOneMethod(row3, "row 3", d.secret(), e.secret(), e.id());
		assertEquals("97", row3.remaining(), "row 3");
		verify(apiKeyService, never()).isMatchingApiKey(any());

		Answer row5 = send(mvc, tokenPost("grant_type=client_credentials&client_id=" + enc(e.id()) + "&client_secret=" + enc(e.secret())));
		assertToken(row5, "row 5: the form alone");
		assertEquals("99", row5.remaining(), "row 5: E's bucket untouched by row 2");
		verify(apiKeyService, times(1)).isMatchingApiKey(any());
		Answer row6 = send(mvc, tokenPost("grant_type=client_credentials").header(HttpHeaders.AUTHORIZATION, dBasic));
		assertToken(row6, "row 6: the header alone");
		assertEquals("96", row6.remaining(), "row 6");
		verify(apiKeyService, times(2)).isMatchingApiKey(any());
	}
}
