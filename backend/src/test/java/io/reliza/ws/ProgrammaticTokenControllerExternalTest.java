/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import io.reliza.common.OAuthErrors;
import io.reliza.service.ApiKeyService;
import io.reliza.service.ApiTokenService;

/**
 * Task TEA-3, design 4.7 case 34: an EXTERNAL key at the programmatic token endpoint is an unknown client, by
 * header and by body fields alike, and the key store is never asked.
 */
class ProgrammaticTokenControllerExternalTest {

	private final ApiKeyService keys = mock(ApiKeyService.class);
	private final ProgrammaticTokenController controller = new ProgrammaticTokenController(keys, mock(ApiTokenService.class),
			mock(io.reliza.service.ApiKeyAccessService.class));
	private final String externalId = "EXTERNAL__" + UUID.randomUUID() + "__ord__" + UUID.randomUUID();

	private static void assertRearmInvalidClient(ResponseEntity<Map<String, Object>> r) {
		assertEquals(401, r.getStatusCode().value());
		assertEquals(OAuthErrors.INVALID_CLIENT, r.getBody().get("error"));
		assertEquals("Basic realm=\"rearm\"", r.getHeaders().getFirst("WWW-Authenticate"));
	}

	@Test
	void byHeader() {
		MockHttpServletRequest req = new MockHttpServletRequest("POST", ProgrammaticTokenController.PATH);
		req.addHeader("Authorization", "Basic " + Base64.getEncoder().encodeToString((externalId + ":secret").getBytes(StandardCharsets.UTF_8)));
		req.addParameter("grant_type", "client_credentials");
		assertRearmInvalidClient(controller.token(req));
		verify(keys, never()).isMatchingApiKey(any());
	}

	@Test
	void byBodyFields() {
		MockHttpServletRequest req = new MockHttpServletRequest("POST", ProgrammaticTokenController.PATH);
		req.addParameter("grant_type", "client_credentials");
		req.addParameter("client_id", externalId);
		req.addParameter("client_secret", "secret");
		assertRearmInvalidClient(controller.token(req));
		verify(keys, never()).isMatchingApiKey(any());
	}
}
