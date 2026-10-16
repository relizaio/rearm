/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import io.reliza.common.CommonVariables.AuthHeaderParse;
import io.reliza.model.ApiKey;
import io.reliza.model.ApiKey.ApiTypeEnum;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;
import io.reliza.service.ApiTokenService.TokenClaims;
import io.reliza.service.AuthorizationService;
import io.reliza.service.UserService;

/**
 * TPF-26 (architecture-5 section 4.1): the bucket key of every caller kind, and the count, on a filter
 * instance of its own with mocked collaborators. The bucket clock is paused for each case, so no burst
 * is refilled while it runs; the Basic parse is the real {@link AuthHeaderParse#parseAuthHeader}.
 */
public class RateLimitingFilterTest {

	private static final String REMOTE = "192.0.2.10";

	private RateLimitingFilter filter;
	private ApiTokenService apiTokenService;
	private ApiKeyService apiKeyService;
	private AuthorizationService authorizationService;

	@BeforeEach
	void setUp() {
		SecurityContextHolder.clearContext();
		filter = new RateLimitingFilter();
		filter.userService = mock(UserService.class);
		authorizationService = mock(AuthorizationService.class);
		apiTokenService = mock(ApiTokenService.class);
		apiKeyService = mock(ApiKeyService.class);
		filter.authorizationService = authorizationService;
		filter.apiTokenService = apiTokenService;
		filter.apiKeyService = apiKeyService;
		when(apiTokenService.verify(anyString(), anyString())).thenReturn(Optional.empty());
		filter.clock.pause();
	}

	@AfterEach
	void tearDown() {
		filter.clock.resume();
		SecurityContextHolder.clearContext();
	}

	/** Every Basic row hands the limiter the real parse of the header, as authenticateProgrammatic does in front of the authentication filter. */
	private void realParse() {
		when(authorizationService.authenticateProgrammatic(any(), any()))
				.thenAnswer(inv -> AuthHeaderParse.parseAuthHeader(inv.getArgument(0), REMOTE));
	}

	private static String basic(String id, String secret) {
		return "Basic " + Base64.getEncoder().encodeToString((id + ":" + secret).getBytes(StandardCharsets.UTF_8));
	}

	private static MockHttpServletRequest request(String method, String path) {
		return request(method, path, path);
	}

	/** A percent-encoded spelling as the container hands it on: the URI as sent, the servlet path decoded. */
	private static MockHttpServletRequest request(String method, String requestUri, String servletPath) {
		MockHttpServletRequest req = new MockHttpServletRequest(method, requestUri);
		req.setRequestURI(requestUri);
		req.setServletPath(servletPath);
		req.setRemoteAddr(REMOTE);
		return req;
	}

	private static MockHttpServletRequest graphql(String authorization) {
		MockHttpServletRequest req = request("POST", ProgrammaticGraphQlConfig.PATH);
		if (authorization != null) req.addHeader(HttpHeaders.AUTHORIZATION, authorization);
		return req;
	}

	private static MockHttpServletRequest tokenForm(String clientId) {
		MockHttpServletRequest req = request("POST", ProgrammaticTokenController.PATH);
		req.setContentType("application/x-www-form-urlencoded");
		req.setParameter("grant_type", "client_credentials");
		req.setParameter("client_secret", "x");
		if (clientId != null) req.setParameter("client_id", clientId);
		return req;
	}

	private static ApiKey key(ApiTypeEnum type, UUID object, String order) {
		ApiKey ak = new ApiKey();
		ak.setUuid(UUID.randomUUID());
		ak.setObjectType(type);
		ak.setObjectUuid(object);
		ak.setKeyOrder(order);
		return ak;
	}

	/** apiTokenService.verify(token) answers claims for the key, and the key row exists. */
	private void bearerOf(String token, ApiKey ak) {
		TokenClaims claims = new TokenClaims(ak.getUuid(), UUID.randomUUID(), ApiTokenService.AUD_PROGRAMMATIC, "fp", 1, "jti",
				Instant.now().plusSeconds(3600));
		when(apiTokenService.verify(token, ApiTokenService.AUD_PROGRAMMATIC)).thenReturn(Optional.of(claims));
		when(apiKeyService.getApiKey(ak.getUuid())).thenReturn(Optional.of(ak));
	}

	private record Run(MockHttpServletResponse response, MockFilterChain chain) {
		boolean reached() {
			return chain.getRequest() != null;
		}

		String remaining() {
			return response.getHeader("X-Rate-Limit-Remaining");
		}
	}

	private Run run(MockHttpServletRequest req) throws Exception {
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(req, resp, chain);
		return new Run(resp, chain);
	}

	private static void assertLimited(Run r, String what) throws Exception {
		assertEquals(HttpStatus.TOO_MANY_REQUESTS.value(), r.response().getStatus(), what);
		assertTrue(Long.parseLong(r.response().getHeader("Retry-After")) >= 1, what);
		assertEquals("application/json", r.response().getContentType(), what);
		assertEquals("{\"error\":\"Rate limit exceeded\"}", r.response().getContentAsString(StandardCharsets.UTF_8), what);
		assertFalse(r.reached(), what + ": the chain is not reached");
	}

	// case 1
	@Test
	public void aVerifiedBearerIsKeyedByItsKeyIdText() {
		UUID o = UUID.randomUUID();
		bearerOf("x", key(ApiTypeEnum.FREEFORM, o, "7"));
		assertEquals("api:FREEFORM__" + o + "__ord__7", filter.resolveKey(graphql("Bearer x")));
		assertEquals("api:FREEFORM__" + o + "__ord__7", filter.resolveKey(graphql("bearer x")), "the scheme is matched case-insensitively");
		verifyNoInteractions(authorizationService);
	}

	// case 2
	@Test
	public void aBearerThatDoesNotVerifyOrHasNoRowFallsToTheAddress() {
		assertEquals("ip:" + REMOTE, filter.resolveKey(graphql("Bearer x")));
		MockHttpServletRequest forwarded = graphql("Bearer x");
		forwarded.addHeader("X-Forwarded-For", "10.0.0.9, 10.0.0.1");
		assertEquals("ip:10.0.0.9", filter.resolveKey(forwarded));

		UUID gone = UUID.randomUUID();
		when(apiTokenService.verify("y", ApiTokenService.AUD_PROGRAMMATIC)).thenReturn(Optional.of(new TokenClaims(gone, UUID.randomUUID(),
				ApiTokenService.AUD_PROGRAMMATIC, "fp", 1, "jti", Instant.now().plusSeconds(3600))));
		when(apiKeyService.getApiKey(gone)).thenReturn(Optional.empty());
		assertEquals("ip:" + REMOTE, filter.resolveKey(graphql("Bearer y")));
	}

	// case 3
	@Test
	public void everySpellingOfOneKeyGivesItsCanonicalBucket() {
		realParse();
		UUID o = UUID.randomUUID();
		String u = o.toString().toUpperCase(Locale.ROOT);
		String canonical = "api:FREEFORM__" + o + "__ord__7";
		for (String text : List.of("FREEFORM__" + o + "__ord__7", "FREEFORM__" + u + "__ord__7", "FREEFORM__" + o + "__x__ord__7",
				"FREEFORM__" + o + "__ORGANIZATION__" + UUID.randomUUID() + "__ord__7")) {
			assertEquals(canonical, filter.resolveKey(graphql(basic(text, "secret"))), text);
		}
		assertEquals("api:FREEFORM__" + o, filter.resolveKey(graphql(basic("FREEFORM__" + o, "secret"))));
		assertEquals("api:FREEFORM__" + o, filter.resolveKey(graphql(basic("FREEFORM__" + o + "__ord__", "secret"))), "an empty order is the no-order form");
		UUID c = UUID.randomUUID();
		assertEquals("api:COMPONENT__" + c, filter.resolveKey(graphql(basic("COMPONENT__" + c, "secret"))));
		assertEquals("api:FREEFORM__" + o + "__ord__8", filter.resolveKey(graphql(basic("FREEFORM__" + o + "__ord__8", "secret"))));
		verifyNoInteractions(apiTokenService);

		assertNull(RateLimitingFilter.bucketOf(null));
		assertNull(RateLimitingFilter.bucketOf(AuthHeaderParse.builder().build()));
		assertNull(RateLimitingFilter.bucketOf(AuthHeaderParse.builder().type(ApiTypeEnum.FREEFORM).build()));
	}

	// case 4
	@Test
	public void noKeyInTheHeaderFallsToTheAddress() {
		realParse();
		assertEquals("ip:" + REMOTE, filter.resolveKey(graphql(null)));
		assertEquals("ip:" + REMOTE, filter.resolveKey(graphql("Digest abc")));
		verifyNoInteractions(apiTokenService);
		UUID o = UUID.randomUUID();
		assertEquals("ip:" + REMOTE, filter.resolveKey(graphql("Basic !!!")));
		assertEquals("ip:" + REMOTE, filter.resolveKey(graphql(basic("FOO__" + UUID.randomUUID(), "x"))));
		assertEquals("ip:" + REMOTE, filter.resolveKey(graphql(basic("FREEFORM__" + o + "__ORGANIZATION", "x"))));
	}

	// case 5
	@Test
	public void theHundredAndFirstRequestOfOneKeyIsRefusedFromAnyAddress() throws Exception {
		realParse();
		UUID o = UUID.randomUUID();
		bearerOf("a", key(ApiTypeEnum.FREEFORM, o, "7"));
		for (int i = 0; i < 100; i++) {
			MockHttpServletRequest req = graphql("Bearer a");
			req.setRemoteAddr(i % 2 == 0 ? "192.0.2.11" : "192.0.2.12");
			Run r = run(req);
			assertTrue(r.reached(), "request " + (i + 1));
			assertEquals(String.valueOf(99 - i), r.remaining(), "request " + (i + 1));
		}
		MockHttpServletRequest last = graphql("Bearer a");
		last.setRemoteAddr("192.0.2.12");
		assertLimited(run(last), "the 101st");
		String upper = "FREEFORM__" + o.toString().toUpperCase(Locale.ROOT) + "__ord__7";
		assertLimited(run(graphql(basic(upper, "s"))), "a Basic of the same key, upper-cased");

		bearerOf("b", key(ApiTypeEnum.FREEFORM, UUID.randomUUID(), "7"));
		MockHttpServletRequest other = graphql("Bearer b");
		other.setRemoteAddr("192.0.2.12");
		Run r = run(other);
		assertTrue(r.reached());
		assertEquals("99", r.remaining(), "another key has its own bucket");
	}

	// case 6
	@Test
	public void theDeviceCodeBucketIsUnchanged() throws Exception {
		String address = "192.0.2.20";
		for (int i = 0; i < 10; i++) {
			MockHttpServletRequest req = request("POST", DeviceAuthorizationController.DEVICE_CODE_PATH);
			req.setRemoteAddr(address);
			assertTrue(run(req).reached(), "start " + (i + 1));
		}
		MockHttpServletRequest eleventh = request("POST", DeviceAuthorizationController.DEVICE_CODE_PATH);
		eleventh.setRemoteAddr(address);
		assertLimited(run(eleventh), "the 11th start");
		MockHttpServletRequest get = request("GET", ProgrammaticSchemaController.PATH);
		get.setRemoteAddr(address);
		assertEquals("89", run(get).remaining(), "ten starts charged the general bucket, the refused one did not");
	}

	// case 7
	@Test
	public void theTokenFormCarrierIsKeyedLikeTheHeader() {
		UUID o = UUID.randomUUID();
		String u = o.toString().toUpperCase(Locale.ROOT);
		String canonical = "api:FREEFORM__" + o + "__ord__7";
		for (String text : List.of("FREEFORM__" + o + "__ord__7", "FREEFORM__" + u + "__ord__7", "FREEFORM__" + o + "__x__ord__7",
				"FREEFORM__" + o + "__ORGANIZATION__" + UUID.randomUUID() + "__ord__7")) {
			assertEquals(canonical, filter.resolveKey(tokenForm(text)), text);
		}
		assertEquals("api:FREEFORM__" + o, filter.resolveKey(tokenForm("FREEFORM__" + o)));
		assertEquals("api:FREEFORM__" + o, filter.resolveKey(tokenForm("FREEFORM__" + o + "__ord__")));
		assertEquals("api:FREEFORM__" + o + "__ord__8", filter.resolveKey(tokenForm("FREEFORM__" + o + "__ord__8")));
		verifyNoInteractions(authorizationService);

		// the binder's array-suffix names are read by nobody
		MockHttpServletRequest suffixed = tokenForm(null);
		suffixed.removeParameter("client_secret");
		suffixed.setParameter("client_id[]", "FREEFORM__" + o + "__ord__7");
		suffixed.setParameter("client_secret[]", "x");
		assertEquals("ip:" + REMOTE, filter.resolveKey(suffixed));

		// colon rows: charged to the key the controller's fold names (the text before the first colon)
		assertEquals(canonical, filter.resolveKey(tokenForm("FREEFORM__" + o + "__ord__7:junk")));
		assertEquals(canonical, filter.resolveKey(tokenForm("FREEFORM__" + o + "__ord__7:")));
		assertEquals("ip:" + REMOTE, filter.resolveKey(tokenForm("junk:FREEFORM__" + o + "__ord__7")));

		// address rows
		assertEquals("ip:" + REMOTE, filter.resolveKey(tokenForm(UUID.randomUUID().toString())), "an organisation uuid");
		assertEquals("ip:" + REMOTE, filter.resolveKey(tokenForm(null)));
		assertEquals("ip:" + REMOTE, filter.resolveKey(tokenForm("")));
		MockHttpServletRequest get = tokenForm("FREEFORM__" + o + "__ord__7");
		get.setMethod("GET");
		assertEquals("ip:" + REMOTE, filter.resolveKey(get), "a GET on the token path");
		MockHttpServletRequest gql = graphql(null);
		gql.setParameter("client_id", "FREEFORM__" + o + "__ord__7");
		gql.setParameter("client_secret", "x");
		assertEquals("ip:" + REMOTE, filter.resolveKey(gql), "the form is read on the token path only");
		verifyNoInteractions(authorizationService);
	}

	// case 8
	@Test
	public void aPresentHeaderWinsOverTheForm() {
		realParse();
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		MockHttpServletRequest both = tokenForm("FREEFORM__" + a + "__ord__1");
		both.addHeader(HttpHeaders.AUTHORIZATION, basic("FREEFORM__" + b + "__ord__2", "s"));
		assertEquals("api:FREEFORM__" + b + "__ord__2", filter.resolveKey(both));

		MockHttpServletRequest digest = tokenForm("FREEFORM__" + a + "__ord__1");
		digest.addHeader(HttpHeaders.AUTHORIZATION, "Digest abc");
		assertEquals("ip:" + REMOTE, filter.resolveKey(digest));

		MockHttpServletRequest space = tokenForm("FREEFORM__" + a + "__ord__1");
		space.addHeader(HttpHeaders.AUTHORIZATION, " ");
		assertEquals("ip:" + REMOTE, filter.resolveKey(space), "a whitespace-only header is present for the controller too");

		assertEquals("api:FREEFORM__" + a + "__ord__1", filter.resolveKey(tokenForm("FREEFORM__" + a + "__ord__1")));
	}

	// case 9
	@Test
	public void aRepeatedClientIdIsReadByItsFirstValue() {
		UUID o = UUID.randomUUID();
		String tail = "__" + UUID.randomUUID() + "__ord__7";
		MockHttpServletRequest join = tokenForm(null);
		join.setParameter("client_id", new String[] {"FREEFORM__" + o + "__ORGANIZATION", tail});
		assertEquals("ip:" + REMOTE, filter.resolveKey(join));
		MockHttpServletRequest reversed = tokenForm(null);
		reversed.setParameter("client_id", new String[] {tail, "FREEFORM__" + o + "__ORGANIZATION"});
		assertEquals("ip:" + REMOTE, filter.resolveKey(reversed));
		MockHttpServletRequest two = tokenForm(null);
		two.setParameter("client_id", new String[] {"FREEFORM__" + o + "__ord__7", "FREEFORM__" + o + "__ord__8"});
		assertEquals("api:FREEFORM__" + o + "__ord__7", filter.resolveKey(two));
		verify(authorizationService, never()).authenticateProgrammatic(any(), any());
	}

	// case 11 (TPF-24, architecture-6 6.1 item 3): a percent-encoded spelling of the CLI login start is counted in its bucket
	@Test
	public void theDeviceCodeBucketCountsEverySpellingOfItsPath() throws Exception {
		String address = "192.0.2.21";
		String spelled = "/api/programmatic/device/%63ode";
		for (int i = 0; i < 10; i++) {
			boolean plain = i < 5;
			MockHttpServletRequest req = plain ? request("POST", DeviceAuthorizationController.DEVICE_CODE_PATH)
					: request("POST", spelled, DeviceAuthorizationController.DEVICE_CODE_PATH);
			req.setRemoteAddr(address);
			assertTrue(run(req).reached(), "start " + (i + 1) + (plain ? " (plain)" : " (" + spelled + ")"));
		}
		MockHttpServletRequest eleventh = request("POST", DeviceAuthorizationController.DEVICE_CODE_PATH);
		eleventh.setRemoteAddr(address);
		assertLimited(run(eleventh), "the 11th start: the five spelled starts were counted in the device-code bucket");
		MockHttpServletRequest get = request("GET", ProgrammaticSchemaController.PATH);
		get.setRemoteAddr(address);
		assertEquals("89", run(get).remaining(), "ten starts charged the general bucket, the refused one did not");
	}

	private static AuthHeaderParse parse(String credentials) {
		HttpHeaders h = new HttpHeaders();
		h.set(HttpHeaders.AUTHORIZATION, "Basic " + Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8)));
		return AuthHeaderParse.parseAuthHeader(h, REMOTE);
	}

	// case 12 (TPF-24, F-5): the bucket of a presented order is the parse's canonical identity, never client free text
	@Test
	public void theBucketOfAPresentedOrderIsCanonical() {
		realParse();
		UUID o = UUID.randomUUID();
		String forged = filter.resolveKey(graphql(basic("FREEFORM__" + o + "__ord__abc\nFORGED LINE", "x")));
		assertEquals("api:FREEFORM__" + o + "__ord__?", forged);
		assertFalse(forged.contains("\n") || forged.contains("\r"), forged);
		assertFalse(forged.contains("FORGED LINE"), forged);
		String order = UUID.randomUUID().toString();
		assertEquals("api:FREEFORM__" + o + "__ord__" + order, filter.resolveKey(graphql(basic("FREEFORM__" + o + "__ord__" + order, "x"))),
				"a plain order keeps its bucket");

		assertEquals("api:FREEFORM__" + o + "__ord__?", RateLimitingFilter.bucketOf(parse("FREEFORM__" + o + "__ord__abc\nFORGED LINE:x")));
		assertEquals("api:FREEFORM__" + o + "__ord__" + order, RateLimitingFilter.bucketOf(parse("FREEFORM__" + o + "__ord__" + order + ":x")));
		assertEquals("api:ORGANIZATION_RW__" + o, RateLimitingFilter.bucketOf(parse("ORGANIZATION_RW__" + o.toString().toUpperCase(Locale.ROOT) + ":x")));
		assertEquals("api:FREEFORM__" + o, RateLimitingFilter.bucketOf(parse("FREEFORM__" + o + ":x")), "no order");
		assertEquals("api:FREEFORM__" + o, RateLimitingFilter.bucketOf(parse("FREEFORM__" + o + "__ord__:x")), "an empty order");
		assertNull(RateLimitingFilter.bucketOf(null));
		assertNull(RateLimitingFilter.bucketOf(AuthHeaderParse.builder().build()));
		assertNull(RateLimitingFilter.bucketOf(AuthHeaderParse.builder().type(ApiTypeEnum.FREEFORM).build()));
	}

	// case 13 (TPF-24, architecture-6 5.7 row 32): the address bucket is printable, and a real address keeps its bucket
	@Test
	public void theAddressBucketIsPrintable() {
		// the control character sits inside the entry: one at its end is removed by the trim before the rendering
		MockHttpServletRequest control = graphql(null);
		control.addHeader("X-Forwarded-For", "203.0.113.7\u0001x, 10.0.0.1");
		assertEquals("ip:203.0.113.7?x", filter.resolveKey(control));
		MockHttpServletRequest lineBreak = graphql(null);
		lineBreak.addHeader("X-Forwarded-For", "203.0.113.7\nFORGED LINE, 10.0.0.1");
		assertEquals("ip:203.0.113.7?FORGED LINE", filter.resolveKey(lineBreak));
		MockHttpServletRequest v4 = graphql(null);
		v4.addHeader("X-Forwarded-For", "203.0.113.7, 10.0.0.1");
		assertEquals("ip:203.0.113.7", filter.resolveKey(v4));
		MockHttpServletRequest v6 = graphql(null);
		v6.addHeader("X-Forwarded-For", "2001:db8::1");
		assertEquals("ip:2001:db8::1", filter.resolveKey(v6));
		assertEquals("ip:" + REMOTE, filter.resolveKey(graphql(null)));
	}

	// the clock: paused, a burst is not refilled; resumed, time only moves forward
	@Test
	public void thePausedClockHoldsAndResumesForward() {
		long paused = filter.clock.currentTimeNanos();
		assertEquals(paused, filter.clock.currentTimeNanos());
		filter.clock.resume();
		assertTrue(filter.clock.currentTimeNanos() >= paused);
		assertNotNull(filter.clock);
		filter.clock.pause();
	}
}
