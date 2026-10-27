/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;

/**
 * Task TEA-6, design round 2 case 38: the error dispatch of a TEA URI is answered with
 * {@code {"message":"<reason phrase>"}}, no-store, the status copied, nothing of the request
 * echoed, and the chain not continued; any other dispatch passes through untouched.
 */
class TeaErrorDispatchFilterTest {

	private static final String SECRET = "secret-text";

	private static MockHttpServletRequest errorDispatch(Integer status) {
		MockHttpServletRequest r = new MockHttpServletRequest("GET", "/error");
		r.setDispatcherType(DispatcherType.ERROR);
		if (null != status) r.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, status);
		r.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, "/tea/" + UUID.randomUUID() + "/v1.0.0/nothing");
		r.setAttribute(RequestDispatcher.ERROR_MESSAGE, SECRET);
		r.setAttribute(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException(SECRET));
		return r;
	}

	private static void assertAnswer(Integer status, int expectedStatus, String reason) throws Exception {
		MockHttpServletRequest req = errorDispatch(status);
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		new TeaErrorDispatchFilter().doFilter(req, resp, chain);
		String what = "status " + status;
		assertEquals(expectedStatus, resp.getStatus(), what);
		assertEquals("application/json", resp.getContentType(), what);
		assertEquals("no-store", resp.getHeader("Cache-Control"), what);
		String body = resp.getContentAsString();
		assertEquals("{\"message\":\"" + reason + "\"}", body, what);
		assertFalse(body.contains(SECRET), what);
		assertFalse(body.contains((String) req.getAttribute(RequestDispatcher.ERROR_REQUEST_URI)), what);
		assertFalse(body.contains("\"error\""), what);
		assertNull(resp.getHeader("WWW-Authenticate"), what);
		assertNull(chain.getRequest(), what + ": the chain is not continued");
	}

	@Test
	void theErrorDispatchIsAnsweredWithTheReasonPhraseAndNothingElse() throws Exception {
		Map<Integer, String> reasons = Map.of(404, "Not Found", 400, "Bad Request", 405, "Method Not Allowed",
				406, "Not Acceptable", 500, "Internal Server Error", 599, "Error");
		for (Map.Entry<Integer, String> e : reasons.entrySet()) assertAnswer(e.getKey(), e.getKey(), e.getValue());
	}

	@Test
	void anErrorDispatchWithoutAStatusIsA500() throws Exception {
		assertAnswer(null, 500, "Internal Server Error");
	}

	@Test
	void anyOtherDispatchPassesThroughUntouched() throws Exception {
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/tea/" + UUID.randomUUID() + "/v1.0.0/products");
		req.setDispatcherType(DispatcherType.REQUEST);
		MockHttpServletResponse resp = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		new TeaErrorDispatchFilter().doFilter(req, resp, chain);
		assertNotNull(chain.getRequest(), "the chain continues");
		assertEquals(200, resp.getStatus());
		assertTrue(resp.getContentAsString().isEmpty());
		assertNull(resp.getHeader("Cache-Control"));
	}

	@Test
	void itRunsOnTheErrorDispatch() {
		assertFalse(new TeaErrorDispatchFilter().shouldNotFilterErrorDispatch());
	}
}
