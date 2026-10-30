/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import io.reliza.common.Utils;
import tools.jackson.databind.JsonNode;

/**
 * TPF-49 (architecture-2 4.7, case 3): the negative control of {@link IdpErrorPageIntegrationTest}.
 * With the live key set to {@code always}, the same failing request shows the exception text in
 * {@code message}: the rig sees the text whenever the setting lets it through. That is what a Spring
 * Boot 3.5 backend sent with the retired {@code server.error.include-message: always}. Its own Spring
 * context, built once.
 */
@TestPropertySource(properties = "spring.web.error.include-message=always")
class IdpErrorPageMessageControlIntegrationTest extends IdpErrorPageTestBase {

	@Test
	void theLiveKeySetToAlwaysShowsTheExceptionText() throws Exception {
		HttpResponse<String> r = failingRawDownload("application/json");
		assertStatus(500, r);
		JsonNode body = Utils.OM.readTree(r.body());
		assertTrue(body.has("message"), r.body());
		assertTrue(body.get("message").asString().contains("tpf49-marker"), r.body());
	}
}
