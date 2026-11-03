/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import io.reliza.service.TeaHostAssignmentService;

/**
 * TPF-142 mutation round: the gate's refusal declares the length of its body, so a keep-alive client reads exactly it.
 */
class TeaHostGateFilterEdgesTest {

	@Test
	void theRefusalDeclaresTheLengthOfItsBody() throws Exception {
		TeaHostAssignmentService hosts = mock(TeaHostAssignmentService.class);
		when(hosts.isCustomHost(any())).thenReturn(true);
		MockHttpServletRequest req = new MockHttpServletRequest("GET", "/graphql");
		req.setServerName("tea-a.test");
		MockHttpServletResponse resp = new MockHttpServletResponse();
		new TeaHostGateFilter(hosts).doFilter(req, resp, new MockFilterChain());
		assertEquals(404, resp.getStatus());
		byte[] body = resp.getContentAsByteArray();
		assertEquals("{\"message\":\"Not Found\"}", new String(body, StandardCharsets.UTF_8));
		assertEquals(body.length, resp.getContentLength());
		assertEquals(String.valueOf(body.length), resp.getHeader("Content-Length"));
	}
}
