/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;

import jakarta.servlet.http.HttpServletRequest;

/**
 * TPF-45 architecture 4.1: the mechanism of {@link FirewallRejectionHandler}, with no Spring. The answer is
 * written with setStatus and a body, never sendError (which starts the container's error dispatch), and
 * nothing is read from the request (so no session can be created).
 */
class FirewallRejectionHandlerTest {

	private static final String BODY = "{\"message\":\"Bad Request\"}";
	private static final Set<String> HEADERS = Set.of("Content-Type", "Cache-Control", "Content-Length");

	private final FirewallRejectionHandler handler = new FirewallRejectionHandler();

	private static RequestRejectedException rejected() {
		return new RequestRejectedException("rejected \";\"");
	}

	private static void assertAnswer(MockHttpServletResponse response) throws Exception {
		assertEquals(400, response.getStatus());
		assertEquals("application/json", response.getContentType());
		assertEquals("no-store", response.getHeader("Cache-Control"));
		assertEquals(25, response.getContentLength());
		assertEquals(BODY, response.getContentAsString(StandardCharsets.UTF_8));
		assertEquals(HEADERS, new HashSet<>(response.getHeaderNames()));
		assertTrue(response.isCommitted(), "the answer is flushed");
		verify(response, never()).sendError(anyInt());
		verify(response, never()).sendError(anyInt(), any());
	}

	@Test
	void answersTheFixed400AndReadsNothingFromTheRequest() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		MockHttpServletResponse response = spy(new MockHttpServletResponse());
		handler.handle(request, response, rejected());
		assertAnswer(response);
		verifyNoInteractions(request);
	}

	@Test
	void replacesWhateverTheResponseAlreadyCarried() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		MockHttpServletResponse response = spy(new MockHttpServletResponse());
		response.setHeader("WWW-Authenticate", "Bearer");
		response.setHeader("X-Rate-Limit-Remaining", "7");
		response.getOutputStream().write("xyz".getBytes(StandardCharsets.US_ASCII));
		handler.handle(request, response, rejected());
		assertAnswer(response);
		verifyNoInteractions(request);
	}

	@Test
	void leavesACommittedResponseAlone() throws Exception {
		HttpServletRequest request = mock(HttpServletRequest.class);
		MockHttpServletResponse response = spy(new MockHttpServletResponse());
		response.setStatus(200);
		response.getOutputStream().write("partial".getBytes(StandardCharsets.US_ASCII));
		response.setCommitted(true);
		assertDoesNotThrow(() -> handler.handle(request, response, rejected()));
		assertEquals(200, response.getStatus());
		assertEquals("partial", response.getContentAsString(StandardCharsets.UTF_8));
		verify(response, never()).sendError(anyInt());
		verify(response, never()).sendError(anyInt(), any());
	}
}
