/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.reliza.service.AuthorizationService;
import io.reliza.service.TeaAuthorizationService.TeaCaller;
import io.reliza.service.TeaAuthorizationService.TeaPrincipal;
import io.reliza.ws.tea.TeaAuthenticationFilter;

/**
 * Task TEA-3, design 3.8: on /tea/** the limiter keys an authenticated TEA caller by its key and everything else by
 * address; a presented key id never keys a bucket there. Task TEA-6 (round 2, case 39): its refusal there carries
 * no error key.
 */
class RateLimitingFilterTeaTest {

	private RateLimitingFilter filter(AuthorizationService auth) {
		RateLimitingFilter f = new RateLimitingFilter();
		f.authorizationService = auth;
		return f;
	}

	private static MockHttpServletRequest req(String uri, String ip) {
		MockHttpServletRequest r = new MockHttpServletRequest("POST", uri);
		r.setRequestURI(uri);
		r.setRemoteAddr(ip);
		return r;
	}

	private static String remaining(RateLimitingFilter f, MockHttpServletRequest r) throws Exception {
		MockHttpServletResponse resp = new MockHttpServletResponse();
		f.doFilter(r, resp, new MockFilterChain());
		return resp.getHeader("X-Rate-Limit-Remaining");
	}

	@Test
	void aPresentedBasicKeyIdNeverKeysATeaBucket() throws Exception {
		AuthorizationService auth = mock(AuthorizationService.class);
		RateLimitingFilter f = filter(auth);
		MockHttpServletRequest r = req("/tea/" + UUID.randomUUID() + "/v1.0.0/token", "198.51.100.10");
		r.addHeader("Authorization", "Basic RlJFRUZPUk1fX3g6eQ==");
		assertEquals(String.valueOf(RateLimitingFilter.REQUESTS_PER_WINDOW - 1), remaining(f, r));
		verify(auth, never()).authenticateProgrammatic(any(), any());
		// the same address on another TEA path shares the bucket
		assertEquals(String.valueOf(RateLimitingFilter.REQUESTS_PER_WINDOW - 2), remaining(f, req("/tea/x/v1.0.0/product/y", "198.51.100.10")));
	}

	@Test
	void anAuthenticatedTeaCallerHasABucketOfItsOwn() throws Exception {
		RateLimitingFilter f = filter(mock(AuthorizationService.class));
		String ip = "198.51.100.11";
		assertEquals(String.valueOf(RateLimitingFilter.REQUESTS_PER_WINDOW - 1), remaining(f, req("/tea/x/v1.0.0/product/y", ip)));
		MockHttpServletRequest authed = req("/tea/x/v1.0.0/product/y", ip);
		authed.setAttribute(TeaAuthenticationFilter.CALLER_ATTRIBUTE,
				TeaCaller.authenticated(new TeaPrincipal(UUID.randomUUID(), UUID.randomUUID(), "EXTERNAL__t__ord__" + UUID.randomUUID(), 1, ip)));
		assertEquals(String.valueOf(RateLimitingFilter.REQUESTS_PER_WINDOW - 1), remaining(f, authed), "keyed by the key, not the address");
		MockHttpServletRequest invalid = req("/tea/x/v1.0.0/product/y", ip);
		invalid.setAttribute(TeaAuthenticationFilter.CALLER_ATTRIBUTE, TeaCaller.invalidToken("invalid or expired"));
		assertEquals(String.valueOf(RateLimitingFilter.REQUESTS_PER_WINDOW - 2), remaining(f, invalid), "an invalid token shares the address bucket");
	}

	/** Calls from one address until the general bucket refuses, answering the refusal. */
	private static MockHttpServletResponse untilRefused(RateLimitingFilter f, String uri, String ip) throws Exception {
		for (int i = 0; i < 4 * RateLimitingFilter.REQUESTS_PER_WINDOW; i++) {
			MockHttpServletResponse resp = new MockHttpServletResponse();
			f.doFilter(req(uri, ip), resp, new MockFilterChain());
			if (resp.getStatus() == 429) return resp;
		}
		throw new AssertionError("the bucket never refused");
	}

	// task TEA-6, design round 2 case 39: the refusal on the TEA chain is not a TEA error-response
	@Test
	void theTeaRefusalCarriesNoErrorKey() throws Exception {
		MockHttpServletResponse r = untilRefused(filter(mock(AuthorizationService.class)),
				"/tea/" + UUID.randomUUID() + "/v1.0.0/product/" + UUID.randomUUID(), "198.51.100.13");
		assertEquals("{\"message\":\"Rate limit exceeded\"}", r.getContentAsString());
		assertEquals("application/json", r.getContentType());
		assertEquals("no-store", r.getHeader("Cache-Control"));
		assertNotNull(r.getHeader("Retry-After"));
	}

	// task TEA-6 round 3 (T-3): the chain also serves bare /tea and the discovery document, so its refusal there is
	// the same; each path gets an address of its own so its bucket starts full
	@Test
	void theTeaRefusalOutsideTeaSlashCarriesNoErrorKey() throws Exception {
		RateLimitingFilter f = filter(mock(AuthorizationService.class));
		String[][] cases = { { "/.well-known/tea", "198.51.100.15" }, { "/tea", "198.51.100.16" } };
		for (String[] c : cases) {
			MockHttpServletResponse r = untilRefused(f, c[0], c[1]);
			assertEquals("{\"message\":\"Rate limit exceeded\"}", r.getContentAsString(), c[0]);
			assertEquals("application/json", r.getContentType(), c[0]);
			assertEquals("no-store", r.getHeader("Cache-Control"), c[0]);
			assertNotNull(r.getHeader("Retry-After"), c[0]);
		}
	}

	// round 4 (T-5): the chain serves an encoded spelling of its paths, so the refusal there is the chain's
	@Test
	void theTeaRefusalOnAnEncodedSpellingCarriesNoErrorKey() throws Exception {
		RateLimitingFilter f = filter(mock(AuthorizationService.class));
		String[][] cases = { { "/%74ea/x/v1.0.0/products", "198.51.100.20" }, { "/.well-known/%74ea", "198.51.100.21" } };
		for (String[] c : cases) {
			MockHttpServletResponse r = untilRefused(f, c[0], c[1]);
			assertEquals("{\"message\":\"Rate limit exceeded\"}", r.getContentAsString(), c[0]);
			assertEquals("application/json", r.getContentType(), c[0]);
			assertEquals("no-store", r.getHeader("Cache-Control"), c[0]);
			assertNotNull(r.getHeader("Retry-After"), c[0]);
		}
	}

	// round 4 (T-5): the same test keys the bucket, so an encoded spelling shares the address bucket of the plain one
	@Test
	void anEncodedSpellingSharesTheTeaBucket() throws Exception {
		AuthorizationService auth = mock(AuthorizationService.class);
		RateLimitingFilter f = filter(auth);
		MockHttpServletRequest r = req("/%74ea/x/v1.0.0/products", "198.51.100.22");
		r.addHeader("Authorization", "Basic RlJFRUZPUk1fX3g6eQ==");
		assertEquals(String.valueOf(RateLimitingFilter.REQUESTS_PER_WINDOW - 1), remaining(f, r));
		assertEquals(String.valueOf(RateLimitingFilter.REQUESTS_PER_WINDOW - 2), remaining(f, req("/tea/x/v1.0.0/products", "198.51.100.22")));
		verify(auth, never()).authenticateProgrammatic(any(), any());
	}

	@Test
	void theRefusalElsewhereIsUnchanged() throws Exception {
		RateLimitingFilter f = filter(mock(AuthorizationService.class));
		// the browser chain's paths, including the ones that only look like TEA paths
		String[][] cases = { { "/api/programmatic/graphql", "198.51.100.14" }, { "/.well-known/tea/x", "198.51.100.17" },
				{ "/teapot", "198.51.100.18" }, { "/%74eapot", "198.51.100.23" }, { "/TEA", "198.51.100.24" } };
		for (String[] c : cases) {
			MockHttpServletResponse r = untilRefused(f, c[0], c[1]);
			assertEquals("{\"error\":\"Rate limit exceeded\"}", r.getContentAsString(), c[0]);
			assertEquals("application/json", r.getContentType(), c[0]);
			assertNotNull(r.getHeader("Retry-After"), c[0]);
		}
	}

	// the same TEA-chain test keys the bucket: a presented key id on the discovery document keys nothing
	@Test
	void aPresentedBasicKeyIdNeverKeysTheDiscoveryDocumentsBucket() throws Exception {
		AuthorizationService auth = mock(AuthorizationService.class);
		RateLimitingFilter f = filter(auth);
		MockHttpServletRequest r = req("/.well-known/tea", "198.51.100.19");
		r.addHeader("Authorization", "Basic RlJFRUZPUk1fX3g6eQ==");
		assertEquals(String.valueOf(RateLimitingFilter.REQUESTS_PER_WINDOW - 1), remaining(f, r));
		assertEquals(String.valueOf(RateLimitingFilter.REQUESTS_PER_WINDOW - 2), remaining(f, req("/tea", "198.51.100.19")),
				"bare /tea shares the address bucket");
		verify(auth, never()).authenticateProgrammatic(any(), any());
	}

	@Test
	void otherPathsStillAskForTheProgrammaticPrincipal() throws Exception {
		AuthorizationService auth = mock(AuthorizationService.class);
		RateLimitingFilter f = filter(auth);
		MockHttpServletRequest r = req("/api/programmatic/graphql", "198.51.100.12");
		r.addHeader("Authorization", "Basic RlJFRUZPUk1fX3g6eQ==");
		remaining(f, r);
		verify(auth, times(1)).authenticateProgrammatic(any(), any());
	}

	// task TEA-14, decision 5 (architecture row 17): the TEA branch answers before main's ReARM-bearer step, so a
	// programmatic bearer sent to a TEA path keys the address bucket and is never handed to the verifier
	@Test
	void aProgrammaticBearerOnATeaPathKeysTheAddressBucket() throws Exception {
		AuthorizationService auth = mock(AuthorizationService.class);
		RateLimitingFilter f = filter(auth);
		io.reliza.service.ApiTokenService tokens = mock(io.reliza.service.ApiTokenService.class);
		io.reliza.service.ApiKeyService keys = mock(io.reliza.service.ApiKeyService.class);
		f.apiTokenService = tokens;
		f.apiKeyService = keys;
		String ip = "198.51.100.25";
		MockHttpServletRequest r = req("/tea/" + UUID.randomUUID() + "/v1.0.0/product", ip);
		r.addHeader("Authorization", "Bearer eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJyZWFybS1wcm9ncmFtbWF0aWMifQ.c2ln");
		assertEquals(ip, f.resolveKey(r).substring("ip:".length()), "the address, never api:");
		assertEquals(String.valueOf(RateLimitingFilter.REQUESTS_PER_WINDOW - 1), remaining(f, r));
		assertEquals(String.valueOf(RateLimitingFilter.REQUESTS_PER_WINDOW - 2), remaining(f, req("/tea/x/v1.0.0/products", ip)),
				"the same address on another TEA path shares the bucket");
		org.mockito.Mockito.verifyNoInteractions(tokens, keys);
		verify(auth, never()).authenticateProgrammatic(any(), any());
	}
}
