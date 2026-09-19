/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

public class ProgrammaticAuthenticationFilterTest {

	@Test
	void anonymousCallGetsABearerChallenge() throws Exception {
		// the anonymous branch touches no service, so the collaborators can be absent
		ProgrammaticAuthenticationFilter f = new ProgrammaticAuthenticationFilter(null, null, null, null);
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
		ProgrammaticAuthenticationFilter f = new ProgrammaticAuthenticationFilter(null, null, null, null);
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/healthCheck");
		req.setRequestURI("/api/healthCheck");
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		f.doFilter(req, resp, chain);
		assertEquals(200, resp.getStatus());
		assertTrue(chain.getRequest() != null, "passed through untouched");
	}
}
