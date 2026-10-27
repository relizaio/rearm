/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.RequestDispatcher;

/**
 * Tasks TEA-3 and TEA-6 (round 2): which error dispatches belong to the TEA chain. They are the
 * paths the chain matches on the request dispatch (/tea/** and the discovery document), the bare
 * /tea included, and nothing that only starts with the same letters.
 */
class AppTeaErrorDispatchTest {

	private static MockHttpServletRequest dispatch(DispatcherType type, String original) {
		MockHttpServletRequest r = new MockHttpServletRequest("GET", "/error");
		r.setDispatcherType(type);
		if (null != original) r.setAttribute(RequestDispatcher.ERROR_REQUEST_URI, original);
		return r;
	}

	@Test
	void theTeaPathsAreTeaErrorDispatches() {
		for (String uri : new String[] { "/tea", "/tea/", "/tea/v0.4.0/products", "/tea/x/v1.0.0/nothing", "/.well-known/tea" }) {
			assertTrue(App.isTeaErrorDispatch(dispatch(DispatcherType.ERROR, uri)), uri);
		}
	}

	// round 4 (T-5): the chain reads the path decoded and without path parameters, and so does the test
	@Test
	void everySpellingTheChainReadsAsATeaPathIs() {
		for (String uri : new String[] { "/tea;x=1/x/v1.0.0/products", "/.well-known/tea;x=1", "/%74ea/x/v1.0.0/nothing",
				"/.well-known/%74ea", "/t%65a", "/tea/;x=1", "/%74%65%61/x/v1.0.0/nothing" }) {
			assertTrue(App.isTeaErrorDispatch(dispatch(DispatcherType.ERROR, uri)), uri);
		}
	}

	@Test
	void noOtherSpellingIs() {
		// matching is case-sensitive, an encoded slash is part of a segment, and a URI that does not decode is no path
		for (String uri : new String[] { "/%74eapot", "/TEA", "/Tea/x/v1.0.0/products", "/.well-known/TEA", "/tea%2Fx",
				"/teapot;x=1", "/.well-known/tea;x=1/x", "/tea/%zz", "/tea%" }) {
			assertFalse(App.isTeaErrorDispatch(dispatch(DispatcherType.ERROR, uri)), uri);
		}
	}

	@Test
	void theContextPathIsNotPartOfTheTeaPath() {
		assertTrue(App.isTeaUri("/ctx/tea/x/v1.0.0/products", "/ctx"));
		assertTrue(App.isTeaUri("/ctx/.well-known/tea", "/ctx"));
		assertFalse(App.isTeaUri("/ctx/teapot", "/ctx"));
		assertFalse(App.isTeaUri("/tea/x", "/ctx"), "outside the application");
		MockHttpServletRequest r = dispatch(DispatcherType.ERROR, "/ctx/tea/x/v1.0.0/nothing");
		r.setContextPath("/ctx");
		assertTrue(App.isTeaErrorDispatch(r));
	}

	@Test
	void nothingElseIs() {
		for (String uri : new String[] { "/teapot", "/tea-x/y", "/teas", "/api/tea", "/.well-known/tea/x", "/.well-known/teapot", "/" }) {
			assertFalse(App.isTeaErrorDispatch(dispatch(DispatcherType.ERROR, uri)), uri);
		}
		assertFalse(App.isTeaErrorDispatch(dispatch(DispatcherType.ERROR, null)), "no original uri");
		assertFalse(App.isTeaErrorDispatch(dispatch(DispatcherType.REQUEST, "/tea")), "not an error dispatch");
	}
}
