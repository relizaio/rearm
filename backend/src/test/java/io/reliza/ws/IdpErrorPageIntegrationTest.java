/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import tools.jackson.databind.JsonNode;

/**
 * TPF-49 (architecture-2 4.7, cases 1 and 2): a signed-in caller whose request fails gets Spring
 * Boot's error page without the exception's message, as JSON and as HTML. Pins
 * {@code spring.web.error.include-message: never} in application.yaml: setting the live key to
 * {@code always} turns both cases red. No extra configuration, so it shares the SCORE-17 context.
 */
class IdpErrorPageIntegrationTest extends IdpErrorPageTestBase {

	private static final String PATH_PREFIX = "/api/manual/v1/artifact/";

	/** Case 1: the JSON error body carries timestamp, status, error and path, and no message. */
	@Test
	void theJsonErrorPageCarriesNoExceptionText() throws Exception {
		HttpResponse<String> r = failingRawDownload("application/json");
		assertStatus(500, r);
		// an anonymous ERROR dispatch is the browser chain's 401 with a Bearer challenge (TPF-45 3.1):
		// a 500 without a challenge means the dispatch ran as the signed-in caller
		assertTrue(r.headers().firstValue("WWW-Authenticate").isEmpty(), r.headers().map().toString());
		assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"),
				r.headers().map().toString());
		JsonNode body = Utils.OM.readTree(r.body());
		assertTrue(body.isObject(), r.body());
		assertEquals(Set.of("timestamp", "status", "error", "path"), new TreeSet<>(body.propertyNames()), r.body());
		assertEquals(500, body.get("status").asInt(), r.body());
		assertEquals("Internal Server Error", body.get("error").asString(), r.body());
		assertEquals(PATH_PREFIX + w.releaseArtifact() + "/rawdownload", body.get("path").asString(), r.body());
		assertNoMarker(r);
	}

	/** Case 2: the HTML error page carries no exception text either. */
	@Test
	void theHtmlErrorPageCarriesNoExceptionText() throws Exception {
		HttpResponse<String> r = failingRawDownload("text/html");
		assertStatus(500, r);
		assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("text/html"),
				r.headers().map().toString());
		assertNoMarker(r);
	}

	private static void assertNoMarker(HttpResponse<String> r) {
		assertFalse(r.body().contains("tpf49-marker"), r.body());
		assertFalse(r.body().contains("rebom.internal.invalid"), r.body());
	}
}
