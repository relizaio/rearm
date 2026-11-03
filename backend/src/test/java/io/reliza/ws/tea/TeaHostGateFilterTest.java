/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.reliza.service.TeaHostAssignmentService;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;

/**
 * TPF-142, architecture section 4 row 16: on a custom host the gate answers every non-TEA path with 404
 * {"message":"Not Found"}, no-store, and never continues the chain; a TEA path, read by the gate's own normalised
 * test (a dot segment is not one, F-11), continues untouched; on any other host the gate is inert.
 */
class TeaHostGateFilterTest {

	private TeaHostGateFilter gate;

	@BeforeEach
	void wire() {
		TeaHostAssignmentService hosts = mock(TeaHostAssignmentService.class);
		when(hosts.isCustomHost(any())).thenAnswer(inv -> TeaHostAssignmentService.normalizeHost(inv.getArgument(0))
				.map("tea-a.test"::equals).orElse(false));
		gate = new TeaHostGateFilter(hosts);
	}

	private static MockHttpServletRequest request(String method, String host, String uri) {
		MockHttpServletRequest r = new MockHttpServletRequest(method, uri);
		r.setServerName(host);
		return r;
	}

	private static MockHttpServletRequest errorDispatch(String host, String original) {
		MockHttpServletRequest r = request("GET", host, "/error");
		r.setDispatcherType(DispatcherType.ERROR);
		r.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, original);
		r.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, 404);
		return r;
	}

	private void assertRefused(MockHttpServletRequest req, String what) throws Exception {
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		gate.doFilter(req, resp, chain);
		assertEquals(404, resp.getStatus(), what);
		assertEquals("application/json", resp.getContentType(), what);
		assertEquals("no-store", resp.getHeader("Cache-Control"), what);
		assertEquals("{\"message\":\"Not Found\"}", resp.getContentAsString(), what);
		assertNull(resp.getHeader("WWW-Authenticate"), what);
		assertNull(resp.getHeader("Set-Cookie"), what);
		assertNull(chain.getRequest(), what + ": the chain is not continued");
	}

	private void assertContinues(MockHttpServletRequest req, String what) throws Exception {
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		gate.doFilter(req, resp, chain);
		assertNotNull(chain.getRequest(), what + ": the chain continues");
		assertEquals(200, resp.getStatus(), what + ": untouched");
		assertEquals("", resp.getContentAsString(), what);
	}

	@Test
	void onACustomHostEveryNonTeaPathIsRefused() throws Exception {
		for (String uri : List.of("/graphql", "/api/programmatic/graphql", "/api/programmatic/token", "/v1/x", "/", "/error",
				"/.well-known/other", "/tea2")) {
			assertRefused(request("POST", "tea-a.test", uri), "POST " + uri);
			assertRefused(request("GET", "tea-a.test", uri), "GET " + uri);
		}
		assertRefused(request("GET", "TEA-A.test", "/graphql"), "the host compares case-insensitively");
		MockHttpServletRequest port = request("GET", "tea-a.test", "/graphql");
		port.setServerPort(8443);
		assertRefused(port, "the port takes no part");
		assertRefused(errorDispatch("tea-a.test", "/graphql"), "an error dispatch of a non-TEA path");
	}

	@Test
	void onACustomHostTheTeaPathsContinue() throws Exception {
		for (String uri : List.of("/tea", "/tea/v1.0.0/products", "/tea/" + UUID.randomUUID() + "/v1.0.0/products",
				"/.well-known/tea", "/%74ea/x")) {
			assertContinues(request("GET", "tea-a.test", uri), uri);
		}
		assertContinues(errorDispatch("tea-a.test", "/tea/v1.0.0/nothing"), "the error dispatch of a TEA path");
	}

	// r5, F-11: a dot-segment spelling under /tea is not a TEA path to the gate
	@Test
	void onACustomHostADotSegmentUnderTeaIsRefused() throws Exception {
		assertRefused(request("GET", "tea-a.test", "/tea/../graphql"), "/tea/../graphql");
		assertRefused(request("POST", "tea-a.test", "/tea/%2e%2e/graphql"), "/tea/%2e%2e/graphql");
		assertRefused(request("GET", "tea-a.test", "/tea/..;/api/programmatic/token"), "/tea/..;/api/programmatic/token");
		assertRefused(request("GET", "tea-a.test", "/tea/v1.0.0/./products"), "/tea/v1.0.0/./products");
		assertRefused(errorDispatch("tea-a.test", "/tea/../graphql"), "the error dispatch of /tea/../graphql");
	}

	@Test
	void onEveryOtherHostTheGateIsInert() throws Exception {
		for (String host : List.of("rearm.localhost", "tea-x.test", "")) {
			for (String uri : List.of("/graphql", "/api/programmatic/token", "/", "/tea/v1.0.0/products", "/tea/../graphql",
					"/tea/%2e%2e/graphql", "/tea/..;/api/programmatic/token", "/tea/v1.0.0/./products")) {
				assertContinues(request("GET", host, uri), host + " " + uri);
			}
		}
	}
}
