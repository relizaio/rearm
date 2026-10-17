/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DataBufferLimitException;

import com.sun.net.httpserver.HttpServer;

import io.reliza.exceptions.RebomResponseTooLargeException;
import tools.jackson.databind.JsonNode;

/**
 * The buffer every rebom read goes through (task SCORE-19, design ADR-1 and ADR-2), against a stub
 * rebom answering {@code bomById} with a generated document of an exact size.
 *
 * <p>Pins the number both ways: 50 MB would refuse the 60 MB answer, an unbounded buffer would read
 * the 70 MB one, and the boundary itself is held at 67108864 bytes read and 67108865 refused. The
 * over-limit answer carries no text about size, and an error that quotes the overflow's text is not
 * an overflow, so the mapping is decided on the exception's class, never on a message; any other
 * failed read keeps its own exception.
 */
class RebomServiceReadLimitTest {

	private static final int MB = 1024 * 1024;

	private HttpServer server;
	private ExecutorService stubThreads;
	private volatile byte[] answer;
	private volatile int status;
	private RebomService service;

	@BeforeEach
	void start() throws Exception {
		status = 200;
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		stubThreads = Executors.newCachedThreadPool();
		server.setExecutor(stubThreads);
		server.createContext("/", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				in.readAllBytes();
			}
			byte[] out = answer;
			exchange.getResponseHeaders().add("Content-Type", "application/json");
			try {
				exchange.sendResponseHeaders(status, out.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(out);
				}
			} catch (IOException e) {
				// The client stopped reading at its limit; nothing more to send.
			}
		});
		server.start();
		service = new RebomService("http://127.0.0.1:" + server.getAddress().getPort() + "/");
	}

	@AfterEach
	void stop() {
		answer = null;
		server.stop(0);
		stubThreads.shutdownNow();
	}

	// T-1 (ADR-1)
	@Test
	void anAnswerBetween50And64MbIsRead() throws Exception {
		answer = bomByIdAnswerOf(60 * MB);
		JsonNode bom = service.findBomByIdJson(UUID.randomUUID(), UUID.randomUUID());
		assertEquals("last", lastComponentName(bom));
	}

	// T-2 (ADR-1 bounded, ADR-2)
	@Test
	void anAnswerOver64MbIsRefusedAsTooLarge() {
		answer = bomByIdAnswerOf(70 * MB);
		RebomResponseTooLargeException e = assertThrows(RebomResponseTooLargeException.class,
				() -> service.findBomByIdJson(UUID.randomUUID(), UUID.randomUUID()));
		assertTrue(causeChainHas(e, DataBufferLimitException.class), "the decoder's overflow is the cause");
	}

	// T-1 (tests/SCORE-19/run-1.md): the limit pinned from above; 64 MB plus one byte is refused
	@Test
	void anAnswerOneByteOver64MbIsRefusedAsTooLarge() {
		answer = bomByIdAnswerOf(67108865);
		assertThrows(RebomResponseTooLargeException.class,
				() -> service.findBomByIdJson(UUID.randomUUID(), UUID.randomUUID()));
	}

	// T-2 (tests/SCORE-19/run-1.md, ADR-2): a failure that is not an overflow keeps today's behaviour
	@Test
	void aServerErrorIsNotTooLarge() {
		status = 500;
		answer = "{}".getBytes(StandardCharsets.US_ASCII);
		assertNotTooLarge(assertThrows(RuntimeException.class,
				() -> service.findBomByIdJson(UUID.randomUUID(), UUID.randomUUID())));
	}

	// T-2 (tests/SCORE-19/run-1.md, ADR-2): a rebom error whose text reads like an overflow is not one;
	// the mapping decides on the class, never on the message
	@Test
	void aRebomErrorQuotingTheOverflowTextIsNotTooLarge() {
		answer = ("{\"errors\":[{\"message\":\"Exceeded limit on max bytes to buffer : 52428800\","
				+ "\"extensions\":{\"code\":\"INTERNAL_ERROR\"}}]}").getBytes(StandardCharsets.US_ASCII);
		assertNotTooLarge(assertThrows(RuntimeException.class,
				() -> service.findBomByIdJson(UUID.randomUUID(), UUID.randomUUID())));
	}

	// T-2 (tests/SCORE-19/run-1.md, ADR-2): a decoder failure that is not about size is not too large
	@Test
	void aMalformedAnswerIsNotTooLarge() {
		answer = "{\"data\":{\"bomById\":".getBytes(StandardCharsets.US_ASCII);
		assertNotTooLarge(assertThrows(RuntimeException.class,
				() -> service.findBomByIdJson(UUID.randomUUID(), UUID.randomUUID())));
	}

	// T-3 (ADR-1, limit inclusive)
	@Test
	void anAnswerOfExactly64MbIsRead() throws Exception {
		answer = bomByIdAnswerOf(67108864);
		JsonNode bom = service.findBomByIdJson(UUID.randomUUID(), UUID.randomUUID());
		assertEquals("last", lastComponentName(bom));
	}

	@Test
	void theGeneratedAnswerHasTheSizeAsked() {
		assertEquals(60 * MB, bomByIdAnswerOf(60 * MB).length);
		assertFalse(new String(bomByIdAnswerOf(70 * MB), StandardCharsets.US_ASCII).contains("limit"),
				"the answer says nothing about size");
	}

	private static String lastComponentName(JsonNode bom) {
		JsonNode components = bom.get("components");
		return components.get(components.size() - 1).get("name").asString();
	}

	private static void assertNotTooLarge(RuntimeException e) {
		assertFalse(e instanceof RebomResponseTooLargeException, e.toString());
		assertFalse(causeChainHas(e, DataBufferLimitException.class), e.toString());
	}

	private static boolean causeChainHas(Throwable t, Class<? extends Throwable> type) {
		for (Throwable c = t; null != c; c = c.getCause()) {
			if (type.isInstance(c)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code {"data":{"bomById":<a CycloneDX document>}}} of exactly {@code total} bytes: components
	 * with a 1000-character description, and a last component whose description takes up the rest.
	 */
	static byte[] bomByIdAnswerOf(int total) {
		String head = "{\"data\":{\"bomById\":{\"bomFormat\":\"CycloneDX\",\"specVersion\":\"1.6\",\"components\":[";
		String tail = "]}}}";
		String lastOpen = ",{\"name\":\"last\",\"description\":\"";
		String lastClose = "\"}";
		String pad = "x".repeat(1000);
		StringBuilder sb = new StringBuilder(total);
		sb.append(head);
		int reserve = lastOpen.length() + lastClose.length() + tail.length();
		for (int i = 0;; i++) {
			String c = (i == 0 ? "" : ",") + "{\"name\":\"c" + i + "\",\"description\":\"" + pad + "\"}";
			if (sb.length() + c.length() + reserve > total) {
				break;
			}
			sb.append(c);
		}
		int fill = total - sb.length() - reserve;
		sb.append(lastOpen).append("x".repeat(fill)).append(lastClose).append(tail);
		return sb.toString().getBytes(StandardCharsets.US_ASCII);
	}
}
