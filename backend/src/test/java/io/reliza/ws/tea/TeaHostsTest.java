/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws.tea;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * TPF-142 mutation round: the request host of a TEA request is the normalised server name, or the empty string; no
 * request and a server name that is no hostname are both no host.
 */
class TeaHostsTest {

	private static MockHttpServletRequest on(String serverName) {
		MockHttpServletRequest r = new MockHttpServletRequest("GET", "/tea/v1.0.0/products");
		r.setServerName(serverName);
		return r;
	}

	@Test
	void noRequestHasNoHost() {
		assertEquals("", TeaHosts.hostOf(null));
	}

	@Test
	void theServerNameIsNormalised() {
		assertEquals("tea-a.test", TeaHosts.hostOf(on("TEA-A.Test")));
		assertEquals("tea-a.test", TeaHosts.hostOf(on("tea-a.test.")));
	}

	@Test
	void aServerNameThatIsNoHostnameIsNoHost() {
		assertEquals("", TeaHosts.hostOf(on("127.0.0.1")));
		assertEquals("", TeaHosts.hostOf(on("bad_host.test")));
	}

	@Test
	void aForwardedHostHeaderIsNotReadByTheHelper() {
		// the container rewrites the server name from X-Forwarded-Host for an internal proxy only; the helper reads no header
		MockHttpServletRequest r = on("tea-b.test");
		r.addHeader("X-Forwarded-Host", "tea-a.test");
		assertEquals("tea-b.test", TeaHosts.hostOf(r));
	}
}
