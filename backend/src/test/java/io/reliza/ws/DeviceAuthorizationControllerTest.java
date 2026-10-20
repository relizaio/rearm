/**
* Copyright 2019 - 2026 Reliza Incorporated. Licensed under MIT License.
* https://reliza.io
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.OAuthErrors;
import io.reliza.common.OAuthQueryGuard;
import io.reliza.service.CliSessionService;

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

	// ---- TPF-52 4.1: revoke refuses a repeated token and reads it by exact name only. The parameter map is built
	// by the test, so these rows prove the controller's reads; each request gets a fresh controller and mock.

	private static final String ADDR = "198.51.100.23";

	private record Revoked(ResponseEntity<Map<String, Object>> answer, CliSessionService service) {}

	/** One revoke request on a fresh controller whose lookup answers {@code verdict}; {@code params} fills the request. */
	private static Revoked revoke(boolean verdict, Consumer<MockHttpServletRequest> params) {
		CliSessionService service = mock(CliSessionService.class);
		when(service.revokeByRefreshToken(any(), any())).thenReturn(verdict);
		DeviceAuthorizationController controller = new DeviceAuthorizationController();
		ReflectionTestUtils.setField(controller, "cliSessionService", service);
		MockHttpServletRequest r = new MockHttpServletRequest("POST", DeviceAuthorizationController.REVOKE_PATH);
		r.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
		r.setRemoteAddr(ADDR);
		params.accept(r);
		return new Revoked(controller.revoke(r), service);
	}

	/** The RFC 7009 answer: 200, no body, not stored. */
	private static void assertAnswered(Revoked r) {
		assertEquals(200, r.answer().getStatusCode().value());
		assertNull(r.answer().getBody(), "success has no body");
		assertEquals("no-store", r.answer().getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
	}

	private static String refusedDescription(Revoked r) {
		assertEquals(400, r.answer().getStatusCode().value());
		assertEquals(OAuthErrors.INVALID_REQUEST, r.answer().getBody().get("error"));
		assertEquals("no-store", r.answer().getHeaders().getFirst(HttpHeaders.CACHE_CONTROL));
		verifyNoInteractions(r.service());
		return (String) r.answer().getBody().get("error_description");
	}

	// row 1: a repeated token is refused before any lookup, with the token endpoint's text
	@Test
	void revokeRefusesARepeatedTokenBeforeAnyLookup() {
		Revoked r = revoke(true, q -> q.setParameter("token", "a", "b"));
		assertEquals(OAuthQueryGuard.ONCE_ONLY_DESCRIPTION + "; sent more than once: token", refusedDescription(r));
	}

	// row 2: a decorated or case-changed name is absent to the read: the lookup gets null, as for a request without a token
	@Test
	void revokeNeverReadsADecoratedTokenName() {
		for (String name : new String[] {"token[]", "token[0]", "token.value", "Token", "TOKEN"}) {
			Revoked r = revoke(true, q -> q.setParameter(name, "a"));
			assertAnswered(r);
			verify(r.service()).revokeByRefreshToken(isNull(), eq(ADDR));
			verifyNoMoreInteractions(r.service());
		}
	}

	// row 3: no token at all keeps today's answer
	@Test
	void revokeWithoutATokenIsStill200() {
		Revoked r = revoke(false, q -> {});
		assertAnswered(r);
		verify(r.service()).revokeByRefreshToken(isNull(), eq(ADDR));
	}

	// row 4: a single token reaches the lookup as today; the answer does not depend on the verdict
	@Test
	void revokeOfASingleTokenAnswersTheSameWhateverTheVerdict() {
		for (boolean verdict : new boolean[] {true, false}) {
			Revoked r = revoke(verdict, q -> q.setParameter("token", "a"));
			assertAnswered(r);
			verify(r.service()).revokeByRefreshToken("a", ADDR);
		}
	}

	// row 5: a repeated name outside the set (token_type_hint, which this server ignores) is not refused
	@Test
	void revokeIgnoresARepeatedNameOutsideTheSet() {
		Revoked r = revoke(true, q -> {
			q.setParameter("token", "a");
			q.setParameter("token_type_hint", "x", "y");
		});
		assertAnswered(r);
		verify(r.service()).revokeByRefreshToken("a", ADDR);
	}

	// row 6: the query-string refusal comes before the repeat refusal
	@Test
	void revokeRefusesATokenInTheQueryBeforeARepeat() {
		Revoked r = revoke(true, q -> {
			q.setQueryString("token=a");
			q.setParameter("token", "a", "b");
		});
		String description = refusedDescription(r);
		assertTrue(description.startsWith(OAuthQueryGuard.BODY_ONLY_DESCRIPTION), description);
		assertTrue(description.contains("token"), description);
	}
}
