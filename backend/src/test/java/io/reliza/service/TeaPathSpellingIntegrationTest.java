/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Task TEA-6 round 4 (T-5): the TEA chain and Spring MVC read a request path decoded, without path
 * parameters, so every TEA path test reads it the same way (App.isTeaUri). A percent-encoded
 * spelling of a TEA path is that path for the bearer and for the error answer, and a path-parameter
 * spelling the firewall refuses is answered by the TEA chain, never with the browser chain's 401.
 * Requests go through the real container, which passes the encoded path to the filters as sent.
 */
public class TeaPathSpellingIntegrationTest extends TeaSurfaceIntegrationTestBase {

	@Test
	void anEncodedSpellingIsServedAsThePathItSpells() throws Exception {
		TeaWorld w = world();
		String plain = w.base() + "/componentRelease/" + w.priv().head();
		String encoded = root() + "/%74ea/" + w.teaOrg() + "/v1.0.0/componentRelease/" + w.priv().head();
		// the covering key's bearer is classified on the encoded spelling as on the plain one
		HttpResponse<String> keyed = get(encoded, w.cover());
		assertEquals(200, keyed.statusCode(), keyed.body());
		assertEquals(get(plain, w.cover()).body(), keyed.body(), "the same release");
		for (String bearer : Arrays.asList(null, BAD, w.other())) {
			HttpResponse<String> p = get(plain, bearer);
			HttpResponse<String> e = get(encoded, bearer);
			assertEquals(p.statusCode(), e.statusCode(), bearer + ": " + e.body());
			assertEquals(p.body(), e.body(), String.valueOf(bearer));
			assertEquals(p.headers().firstValue("WWW-Authenticate"), e.headers().firstValue("WWW-Authenticate"), String.valueOf(bearer));
		}
	}

	@Test
	void everySpellingOfATeaPathGetsTheTeaChainsErrorAnswer() throws Exception {
		TeaWorld w = world();
		for (String bearer : Arrays.asList(null, BAD, w.cover())) {
			for (String url : List.of(root() + "/%74ea/" + w.teaOrg() + "/v1.0.0/nothing", root() + "/%74ea",
					root() + "/t%65a/" + w.teaOrg() + "/v1.0.0/nothing")) {
				assertNotTeaError(get(url, bearer), 404, "Not Found", url + " " + bearer);
			}
			// the firewall refuses a path parameter; its error dispatch is still a TEA path
			for (String url : List.of(root() + "/tea;x=1/" + w.teaOrg() + "/v1.0.0/products", root() + "/.well-known/tea;x=1",
					root() + "/tea;x=1")) {
				assertNotTeaError(get(url, bearer), 400, "Bad Request", url + " " + bearer);
			}
		}
	}

	@Test
	void spellingsOfOtherPathsKeepTheBrowserChain() throws Exception {
		for (String path : List.of("/%74eapot", "/TEA", "/Tea/x/v1.0.0/products", "/.well-known/TEA")) {
			HttpResponse<String> r = get(root() + path, null);
			assertEquals(401, r.statusCode(), path + ": " + r.body());
			assertTrue(r.headers().firstValue("WWW-Authenticate").isPresent(), path + ": the browser chain's challenge");
		}
	}
}
