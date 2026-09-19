/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

public class DeviceAuthorizationControllerTest {

	@Test
	void observedAddressPrefersForwardedThenRealIpThenPeer() {
		MockHttpServletRequest r = new MockHttpServletRequest();
		r.setRemoteAddr("10.42.0.1");
		assertEquals("10.42.0.1", DeviceAuthorizationController.clientIp(r), "no headers: the socket peer");
		r.addHeader("X-Real-IP", "203.0.113.7");
		assertEquals("203.0.113.7", DeviceAuthorizationController.clientIp(r));
		r.addHeader("X-Forwarded-For", " 198.51.100.9, 10.0.0.2");
		assertEquals("198.51.100.9", DeviceAuthorizationController.clientIp(r), "first hop of the forwarded chain wins");
	}
}
