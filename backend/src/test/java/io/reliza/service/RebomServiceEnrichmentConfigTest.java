/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

/**
 * {@link RebomService#isEnrichmentConfigured}: the field is Boolean! in
 * rebom's schema, so an answer without it is a failure, never "not
 * configured". The synthetic submitter skips its pass on a failure; on a
 * guessed "not configured" a BEAR org would ship un-enriched licenses.
 */
class RebomServiceEnrichmentConfigTest {

	private HttpServer server;
	private volatile String body;
	private RebomService service;

	@BeforeEach
	void start() throws Exception {
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/", exchange -> {
			byte[] out = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			exchange.sendResponseHeaders(200, out.length);
			try (OutputStream os = exchange.getResponseBody()) {
				os.write(out);
			}
		});
		server.start();
		service = new RebomService("http://127.0.0.1:" + server.getAddress().getPort() + "/");
	}

	@AfterEach
	void stop() {
		server.stop(0);
	}

	@Test
	void anAnswerIsTakenAsGiven() {
		body = "{\"data\":{\"isEnrichmentConfigured\":true}}";
		assertTrue(service.isEnrichmentConfigured(UUID.randomUUID()));
		body = "{\"data\":{\"isEnrichmentConfigured\":false}}";
		assertFalse(service.isEnrichmentConfigured(UUID.randomUUID()));
	}

	@Test
	void anAnswerWithoutTheFieldIsAFailureNotANo() {
		body = "{\"data\":{}}";
		assertThrows(IllegalStateException.class, () -> service.isEnrichmentConfigured(UUID.randomUUID()));
	}
}
