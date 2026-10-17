/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.net.httpserver.HttpServer;

import io.reliza.common.Utils;
import io.reliza.exceptions.SbomScoreException;
import io.reliza.exceptions.SbomScoreException.SbomScoreErrorReason;
import tools.jackson.databind.JsonNode;

/**
 * {@link RebomService#scoreBom} against a stub rebom (task SCORE-5, design R-3, R-17 to R-23).
 *
 * <p>The stub records every request body and answers what the case sets. Every mapping case uses a
 * message that contradicts its reason, so a decision on message text fails here.
 */
class RebomServiceScoreBomTest {

	private static final String REPORT = "{\"reportVersion\":1,\"profiles\":[]}";
	private static final List<String> PROFILES = List.of("fda", "cisa-2026");

	private HttpServer server;
	private ExecutorService stubThreads;
	private final List<byte[]> requests = Collections.synchronizedList(new ArrayList<>());
	private volatile int status;
	private volatile String contentType;
	private volatile String body;
	private volatile long delayMillis;
	private RebomService service;

	@BeforeEach
	void start() throws Exception {
		status = 200;
		contentType = "application/json";
		body = answer(REPORT);
		delayMillis = 0;
		server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		stubThreads = Executors.newCachedThreadPool();
		server.setExecutor(stubThreads);
		server.createContext("/", exchange -> {
			try (InputStream in = exchange.getRequestBody()) {
				requests.add(in.readAllBytes());
			}
			if (delayMillis > 0) {
				try {
					Thread.sleep(delayMillis);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			byte[] out = body.getBytes(StandardCharsets.UTF_8);
			exchange.getResponseHeaders().add("Content-Type", contentType);
			try {
				exchange.sendResponseHeaders(status, out.length);
				try (OutputStream os = exchange.getResponseBody()) {
					os.write(out);
				}
			} catch (IOException e) {
				// The client gave up (the timeout case); nothing to answer.
			}
		});
		server.start();
		service = new RebomService(url(server));
	}

	@AfterEach
	void stop() {
		server.stop(0);
		stubThreads.shutdownNow();
	}

	private static String url(HttpServer s) {
		return "http://127.0.0.1:" + s.getAddress().getPort() + "/";
	}

	/** rebom's success envelope around a report string. */
	private static String answer(String report) {
		Map<String, Object> data = new LinkedHashMap<>();
		data.put("scoreBomProbe", report);
		return Utils.OM.writeValueAsString(Map.of("data", data));
	}

	/** rebom's error envelope for one error with the given code and details reason. */
	private static String error(String code, String reason, String message) {
		Map<String, Object> extensions = new LinkedHashMap<>();
		extensions.put("code", code);
		if (null != reason) extensions.put("details", Map.of("reason", reason));
		Map<String, Object> err = new LinkedHashMap<>();
		err.put("message", message);
		err.put("extensions", extensions);
		Map<String, Object> envelope = new LinkedHashMap<>();
		envelope.put("errors", List.of(err));
		envelope.put("data", null);
		return Utils.OM.writeValueAsString(envelope);
	}

	private void answerWith(int st, String ct, String b) {
		status = st;
		contentType = ct;
		body = b;
	}

	private static Set<String> fieldNames(JsonNode n) {
		return Set.copyOf(n.propertyNames());
	}

	// U-1 (R-17)
	@Test
	void theRequestCarriesTheDocumentAndProfilesUnchanged() {
		String doc = "{\"bomFormat\" : \"CycloneDX\",\n\t\"path\": \"C:\\\\x\", \"name\": \"caf\u00e9 \u2192\"}  \n\t ";
		List<String> profiles = List.of("fda", "cisa-2026");

		service.scoreBom(doc, profiles);

		assertEquals(1, requests.size());
		JsonNode sent = Utils.OM.readTree(requests.get(0));
		assertEquals(Set.of("query", "variables"), fieldNames(sent), "no format, no org, nothing else");
		assertTrue(sent.get("query").stringValue().contains("scoreBomProbe(bom: $bom, profiles: $profiles)"));
		JsonNode variables = sent.get("variables");
		assertEquals(Set.of("bom", "profiles"), fieldNames(variables));
		assertEquals(doc, variables.get("bom").stringValue(), "the document is sent as given: no trim, no re-serialisation");
		List<String> sentProfiles = new ArrayList<>();
		variables.get("profiles").forEach(p -> sentProfiles.add(p.stringValue()));
		assertEquals(List.of("fda", "cisa-2026"), sentProfiles, "same elements, same order");
	}

	// U-2 (R-3, R-22)
	@Test
	void theReportIsReturnedVerbatimWhateverItsVersion() {
		String odd = "{  \"summary\" : {\"b\":1,   \"a\":2},\n \"reportVersion\" :1 }";
		answerWith(200, "application/json", answer(odd));
		assertEquals(odd, service.scoreBom("{}", PROFILES), "the original string, not the parsed tree");

		String later = "{\"reportVersion\":2,\"z\":[1,2 ,3]}";
		answerWith(200, "application/json", answer(later));
		assertEquals(later, service.scoreBom("{}", PROFILES), "a later report version is the reader's to judge");
	}

	// U-3 (R-22)
	@ParameterizedTest
	@MethodSource("invalidReports")
	void aReportThatIsNotAVersionedObjectIsAFailure(String report) {
		answerWith(200, "application/json", answer(report));
		SbomScoreException e = assertThrows(SbomScoreException.class, () -> service.scoreBom("{}", PROFILES));
		assertEquals(SbomScoreErrorReason.SCORING_FAILED, e.getReason());
		assertEquals(RebomService.SCORE_FAILED_MESSAGE, e.getMessage());
	}

	static Stream<String> invalidReports() {
		return Stream.of("not json", "[]", "{}", "{\"reportVersion\":\"1\"}", "{\"reportVersion\":0}",
				"{\"reportVersion\":1.5}");
	}

	// U-4 (R-18)
	@Test
	void aDocumentOverTheLimitIsRefusedWithoutARequestAndOneAtTheLimitIsSent() {
		SbomScoreException over = assertThrows(SbomScoreException.class,
				() -> service.scoreBom("a".repeat(52428801), PROFILES));
		assertEquals(SbomScoreErrorReason.INPUT_TOO_LARGE, over.getReason());
		assertEquals(RebomService.SCORE_TOO_LARGE_MESSAGE, over.getMessage());
		assertEquals(0, requests.size(), "refused before any request");

		answerWith(413, "text/html", "<html><body>Payload Too Large</body></html>");
		SbomScoreException at = assertThrows(SbomScoreException.class,
				() -> service.scoreBom("a".repeat(52428800), PROFILES));
		assertEquals(1, requests.size(), "a document of exactly the limit is sent");
		assertEquals(SbomScoreErrorReason.INPUT_TOO_LARGE, at.getReason(), "and rebom's 413 refuses it");
	}

	// U-5 (R-19)
	@Test
	void http413IsTooLargeDecidedOnTheStatusAlone() {
		answerWith(413, "text/html", "<html><body>Bad Gateway: scoring failed</body></html>");
		SbomScoreException e = assertThrows(SbomScoreException.class, () -> service.scoreBom("{}", PROFILES));
		assertEquals(SbomScoreErrorReason.INPUT_TOO_LARGE, e.getReason());
		assertEquals(RebomService.SCORE_TOO_LARGE_MESSAGE, e.getMessage());
	}

	// U-6 (R-20, R-21 row 5)
	@Test
	void aSlowRebomTimesOut() {
		RebomService fast = new RebomService(url(server), 1);
		delayMillis = 3000;
		long started = System.nanoTime();
		SbomScoreException e = assertThrows(SbomScoreException.class, () -> fast.scoreBom("{}", PROFILES));
		long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
		assertEquals(SbomScoreErrorReason.TIMEOUT, e.getReason());
		assertEquals(RebomService.SCORE_TIMEOUT_MESSAGE, e.getMessage());
		assertTrue(elapsedMillis < 2500, "timed out after " + elapsedMillis + " ms");
	}

	// U-6 (R-20): the property and its default
	@Test
	void theTimeoutDefaultsTo180Seconds() throws Exception {
		assertEquals(Duration.ofSeconds(180), ReflectionTestUtils.getField(service, "scoreTimeout"));
		Constructor<RebomService> injected = RebomService.class.getConstructor(String.class, long.class);
		assertTrue(injected.isAnnotationPresent(Autowired.class), "the constructor Spring uses");
		Value timeout = injected.getParameters()[1].getAnnotation(Value.class);
		assertEquals("${relizaprops.rebom.score-timeout-seconds:180}", timeout.value());
	}

	/** One rebom outcome and what the caller must get for it; {@code null} message = rebom's own. */
	record Case(String name, int status, String contentType, String body, SbomScoreErrorReason reason,
			String message, String rebomReason, boolean warns) {
		@Override
		public String toString() {
			return name;
		}
	}

	static Stream<Case> outcomes() {
		String json = "application/json";
		return Stream.of(
				new Case("UNKNOWN_PROFILE", 200, json, error("BOM_SCORE_ERROR", "UNKNOWN_PROFILE", "the engine timed out"),
						SbomScoreErrorReason.UNKNOWN_PROFILE, null, "UNKNOWN_PROFILE", false),
				new Case("INPUT_REFUSED", 200, json, error("BOM_SCORE_ERROR", "INPUT_REFUSED", "unknown profile: fda"),
						SbomScoreErrorReason.INPUT_REFUSED, null, "INPUT_REFUSED", false),
				new Case("INPUT_TOO_LARGE", 200, json, error("BOM_SCORE_ERROR", "INPUT_TOO_LARGE", "unsupported SBOM: x"),
						SbomScoreErrorReason.INPUT_TOO_LARGE, RebomService.SCORE_TOO_LARGE_MESSAGE, "INPUT_TOO_LARGE", false),
				new Case("TIMEOUT", 200, json, error("BOM_SCORE_ERROR", "TIMEOUT", "input too large"),
						SbomScoreErrorReason.TIMEOUT, RebomService.SCORE_TIMEOUT_MESSAGE, "TIMEOUT", true),
				new Case("CLI_UNAVAILABLE", 200, json, error("BOM_SCORE_ERROR", "CLI_UNAVAILABLE", "timed out"),
						SbomScoreErrorReason.SCORING_UNAVAILABLE, RebomService.SCORE_UNAVAILABLE_MESSAGE, "CLI_UNAVAILABLE", true),
				new Case("CLI_FAILED", 200, json, error("BOM_SCORE_ERROR", "CLI_FAILED", "unknown profile: x"),
						SbomScoreErrorReason.SCORING_FAILED, RebomService.SCORE_FAILED_MESSAGE, "CLI_FAILED", true),
				new Case("REPORT_INVALID", 200, json, error("BOM_SCORE_ERROR", "REPORT_INVALID", "not available"),
						SbomScoreErrorReason.SCORING_FAILED, RebomService.SCORE_FAILED_MESSAGE, "REPORT_INVALID", true),
				new Case("unknown reason X", 200, json, error("BOM_SCORE_ERROR", "X", "unsupported SBOM: refused"),
						SbomScoreErrorReason.SCORING_FAILED, RebomService.SCORE_FAILED_MESSAGE, "X", true),
				new Case("GRAPHQL_VALIDATION_FAILED at 400", 400, json,
						error("GRAPHQL_VALIDATION_FAILED", null, "Cannot query field \"scoreBomProbe\" on type \"Query\"."),
						SbomScoreErrorReason.SCORING_UNAVAILABLE, RebomService.SCORE_UNAVAILABLE_MESSAGE, null, true),
				new Case("GRAPHQL_VALIDATION_FAILED at 200", 200, json,
						error("GRAPHQL_VALIDATION_FAILED", null, "Cannot query field"),
						SbomScoreErrorReason.SCORING_UNAVAILABLE, RebomService.SCORE_UNAVAILABLE_MESSAGE, null, true),
				new Case("INTERNAL_ERROR", 200, json, error("INTERNAL_ERROR", "UNKNOWN_PROFILE", "unknown profile"),
						SbomScoreErrorReason.SCORING_FAILED, RebomService.SCORE_FAILED_MESSAGE, null, true),
				new Case("500 with a report", 500, json, answer(REPORT),
						SbomScoreErrorReason.SCORING_FAILED, RebomService.SCORE_FAILED_MESSAGE, null, true),
				new Case("500 with text", 500, "text/plain", "Internal Server Error",
						SbomScoreErrorReason.SCORING_FAILED, RebomService.SCORE_FAILED_MESSAGE, null, true),
				new Case("200 with data null", 200, json, "{\"data\":null}",
						SbomScoreErrorReason.SCORING_FAILED, RebomService.SCORE_FAILED_MESSAGE, null, true),
				new Case("200 with a body that is not JSON", 200, "text/html", "<html>ok</html>",
						SbomScoreErrorReason.SCORING_FAILED, RebomService.SCORE_FAILED_MESSAGE, null, true));
	}

	private static String rebomMessage(Case c) {
		return Utils.OM.readTree(c.body()).get("errors").get(0).get("message").stringValue();
	}

	// U-7 (R-21)
	@ParameterizedTest(name = "{0}")
	@MethodSource("outcomes")
	void eachRebomOutcomeMapsToItsReason(Case c) {
		answerWith(c.status(), c.contentType(), c.body());
		SbomScoreException e = assertThrows(SbomScoreException.class, () -> service.scoreBom("{}", PROFILES));
		assertEquals(c.reason(), e.getReason());
		if (null == c.message()) {
			assertEquals(rebomMessage(c), e.getMessage(), "a caller error carries rebom's message");
		} else {
			assertEquals(c.message(), e.getMessage());
			if (c.body().contains("\"errors\"")) {
				assertNotEquals(rebomMessage(c), e.getMessage(), "rebom's message is not passed on");
			}
		}
	}

	// U-7 (R-21 row 7): connection refused
	@Test
	void aRebomThatIsDownIsAFailure() throws Exception {
		String closedUrl;
		try (ServerSocket closed = new ServerSocket(0, 0, InetAddress.getLoopbackAddress())) {
			closedUrl = "http://127.0.0.1:" + closed.getLocalPort() + "/";
		}
		SbomScoreException e = assertThrows(SbomScoreException.class,
				() -> new RebomService(closedUrl).scoreBom("{}", PROFILES));
		assertEquals(SbomScoreErrorReason.SCORING_FAILED, e.getReason());
		assertEquals(RebomService.SCORE_FAILED_MESSAGE, e.getMessage());
	}

	// U-8 (R-23)
	@Test
	void neitherTheDocumentNorTheReportIsLoggedAndOnlyFailuresWarn() throws Exception {
		String docMarker = "DOC-MARKER-" + UUID.randomUUID();
		String reportMarker = "REPORT-MARKER-" + UUID.randomUUID();
		String doc = "{\"bomFormat\":\"CycloneDX\",\"note\":\"" + docMarker + "\"}";
		String report = "{\"reportVersion\":1,\"note\":\"" + reportMarker + "\"}";

		try (LogCapture capture = new LogCapture()) {
			answerWith(200, "application/json", answer(report));
			assertEquals(report, service.scoreBom(doc, PROFILES));
			assertTrue(capture.rebomServiceAtInfoOrAbove().isEmpty(), "a success logs nothing above DEBUG");
			assertFalse(capture.anyContains(docMarker) || capture.anyContains(reportMarker), "success");

			for (Case c : outcomes().toList()) {
				capture.clear();
				answerWith(c.status(), c.contentType(), c.body());
				assertThrows(SbomScoreException.class, () -> service.scoreBom(doc, PROFILES));
				assertFalse(capture.anyContains(docMarker), c.name() + ": the document is never logged");
				assertFalse(capture.anyContains(reportMarker), c.name() + ": the report is never logged");
				List<LogEvent> above = capture.rebomServiceAtInfoOrAbove();
				if (c.warns()) {
					assertEquals(1, above.size(), c.name() + ": exactly one line above DEBUG, got " + above.size());
					assertEquals(Level.WARN, above.get(0).getLevel(), c.name());
					if (null != c.rebomReason()) {
						assertTrue(above.get(0).getMessage().getFormattedMessage().contains(c.rebomReason()),
								c.name() + ": the WARN names rebom's reason");
					}
				} else {
					assertTrue(above.isEmpty(), c.name() + ": a caller error logs nothing above DEBUG");
				}
			}

			// Row 7 with a report in hand: one that fails the check, and a valid one under a 500.
			for (int st : List.of(200, 500)) {
				capture.clear();
				answerWith(st, "application/json", answer(200 == st
						? "{\"reportVersion\":0,\"note\":\"" + reportMarker + "\"}" : report));
				assertThrows(SbomScoreException.class, () -> service.scoreBom(doc, PROFILES));
				assertFalse(capture.anyContains(docMarker) || capture.anyContains(reportMarker), "report at " + st);
				assertEquals(1, capture.rebomServiceAtInfoOrAbove().size(), "report at " + st + " warns once");
			}

			capture.clear();
			answerWith(413, "text/html", "<html>" + reportMarker + "</html>");
			assertThrows(SbomScoreException.class, () -> service.scoreBom(doc, PROFILES));
			assertTrue(capture.rebomServiceAtInfoOrAbove().isEmpty(), "413 is a caller error");
			assertFalse(capture.anyContains(docMarker) || capture.anyContains(reportMarker), "413");

			capture.clear();
			assertThrows(SbomScoreException.class, () -> service.scoreBom(docMarker + "a".repeat(52428801), PROFILES));
			assertTrue(capture.rebomServiceAtInfoOrAbove().isEmpty(), "the pre-check is a caller error");
			assertFalse(capture.anyContains(docMarker), "pre-check");

			capture.clear();
			delayMillis = 3000;
			answerWith(200, "application/json", answer(report));
			assertThrows(SbomScoreException.class, () -> new RebomService(url(server), 1).scoreBom(doc, PROFILES));
			assertEquals(1, capture.rebomServiceAtInfoOrAbove().size(), "a timeout warns once");
			assertFalse(capture.anyContains(docMarker) || capture.anyContains(reportMarker), "timeout");
		}
	}

	/**
	 * Every log event of the JVM at every level while open: every configured logger is lowered to ALL
	 * and the capture sits on each one an event can end at, so a document reaching any logger at DEBUG
	 * or TRACE is seen, under the bare default configuration and under the application's. Restored on
	 * close.
	 */
	private static final class LogCapture implements AutoCloseable {
		private final LoggerContext ctx;
		private final Configuration config;
		private final Map<String, Level> savedLevels = new HashMap<>();
		private final List<LoggerConfig> withAppender = new ArrayList<>();
		private final List<LogEvent> events = Collections.synchronizedList(new ArrayList<>());
		private final AbstractAppender appender;

		LogCapture() {
			ctx = ((Logger) LogManager.getLogger(RebomService.class)).getContext();
			config = ctx.getConfiguration();
			appender = new AbstractAppender("score-capture-" + UUID.randomUUID(), null, null, true,
					Property.EMPTY_ARRAY) {
				@Override
				public void append(LogEvent event) {
					events.add(event.toImmutable());
				}
			};
			appender.start();
			for (LoggerConfig lc : configs()) {
				savedLevels.put(lc.getName(), lc.getLevel());
				lc.setLevel(Level.ALL);
				// An event stops at the first non-additive config on its way to the root, so
				// exactly one of these sees it: the application's configuration declares
				// io.reliza and org.springframework non-additive.
				if (!lc.isAdditive() || lc == config.getRootLogger()) {
					lc.addAppender(appender, Level.ALL, null);
					withAppender.add(lc);
				}
			}
			ctx.updateLoggers();
		}

		private Set<LoggerConfig> configs() {
			Set<LoggerConfig> all = Collections.newSetFromMap(new IdentityHashMap<>());
			all.addAll(config.getLoggers().values());
			all.add(config.getRootLogger());
			return all;
		}

		void clear() {
			events.clear();
		}

		boolean anyContains(String marker) {
			synchronized (events) {
				return events.stream().anyMatch(e -> render(e).contains(marker));
			}
		}

		List<LogEvent> rebomServiceAtInfoOrAbove() {
			synchronized (events) {
				return events.stream()
						.filter(e -> RebomService.class.getName().equals(e.getLoggerName()))
						.filter(e -> e.getLevel().isMoreSpecificThan(Level.INFO))
						.toList();
			}
		}

		private static String render(LogEvent e) {
			StringBuilder sb = new StringBuilder(String.valueOf(e.getMessage().getFormattedMessage()));
			for (Throwable t = e.getThrown(); null != t; t = t.getCause()) {
				sb.append(' ').append(t);
			}
			return sb.toString();
		}

		@Override
		public void close() {
			for (LoggerConfig lc : withAppender) {
				lc.removeAppender(appender.getName());
			}
			for (LoggerConfig lc : configs()) {
				if (savedLevels.containsKey(lc.getName())) lc.setLevel(savedLevels.get(lc.getName()));
			}
			ctx.updateLoggers();
			appender.stop();
		}
	}
}
