/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.tea.TeaOpenApiSchemas.assertConforms;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import io.reliza.ws.RelizaConfigProps;
import io.reliza.ws.tea.LogEventCapture;
import io.reliza.ws.tea.TeaErrorAdvice;
import io.reliza.ws.tea.TeaTokenController;

/**
 * Task TEA-6, design 4.8 cases 29, 30 and 33: the listing parameters (one page, no token ever
 * issued, page size validated and not applied, sort applied), the identifier filters, the removed
 * 0.4.0 base and beta flag (round 2: every unmounted TEA path answers a non-TEA body). Case 34, the
 * artifact stub, went with the stub (task TEA-10).
 */
public class TeaSurfaceRequestsIntegrationTest extends TeaSurfaceIntegrationTestBase {

	private static void assertError(HttpResponse<String> r, int status, String error, String what) {
		assertEquals(status, r.statusCode(), what + ": " + r.body());
		assertEquals(error, json(r).get("error"), what);
		assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null), what);
		assertConforms(r.body(), "error-response");
	}

	// 29
	@Test
	void listingParametersAreValidatedAndThePageHoldsEveryResult() throws Exception {
		TeaWorld w = world();
		String rel = w.base() + "/componentReleases";
		assertError(get(rel + "?pageToken=anything", null), 400, "INVALID_PAGE_TOKEN", "pageToken");
		assertError(get(rel + "?pageToken=", null), 400, "INVALID_PAGE_TOKEN", "empty pageToken");
		for (String size : List.of("0", "101", "x")) {
			assertError(get(rel + "?pageSize=" + size, null), 400, "INVALID_REQUEST", "pageSize=" + size);
		}
		HttpResponse<String> one = get(rel + "?pageSize=1", w.cover());
		assertEquals(200, one.statusCode(), one.body());
		assertEquals(2, results(one).size(), "pageSize is validated and not applied: " + one.body());
		assertEquals(Boolean.FALSE, json(one).get("hasNext"));
		assertFalse(json(one).containsKey("nextPageToken"));
		assertError(get(rel + "?sortField=bogus", null), 400, "INVALID_REQUEST", "sortField");
		assertError(get(rel + "?sortOrder=sideways", null), 400, "INVALID_REQUEST", "sortOrder");
		assertError(get(w.base() + "/products?sortField=version", null), 400, "INVALID_REQUEST", "products by version");

		HttpResponse<String> asc = get(rel + "?sortField=version", w.cover());
		HttpResponse<String> desc = get(rel + "?sortField=version&sortOrder=desc", w.cover());
		List<String> ascVersions = results(asc).stream().map(m -> (String) m.get("version")).toList();
		List<String> descVersions = results(desc).stream().map(m -> (String) m.get("version")).toList();
		List<String> sorted = new ArrayList<>(ascVersions);
		sorted.sort(Comparator.naturalOrder());
		assertEquals(sorted, ascVersions, "codepoint order ascending");
		List<String> reversed = new ArrayList<>(sorted);
		java.util.Collections.reverse(reversed);
		assertEquals(reversed, descVersions, "and reversed");
		HttpResponse<String> prs = get(w.base() + "/productReleases?sortField=version&sortOrder=desc", w.cover());
		assertEquals(200, prs.statusCode(), prs.body());
		assertEquals(List.of(w.prod().head().toString()), uuidsOf(prs));
	}

	// 30
	@Test
	void identifierFiltersApplyToWhatIsServed() throws Exception {
		TeaWorld w = world();
		HttpResponse<String> byPurl = get(w.base() + "/componentReleases?idType=PURL&idValue="
				+ java.net.URLEncoder.encode(w.pub().purl(), java.nio.charset.StandardCharsets.UTF_8), null);
		assertEquals(List.of(w.pub().head().toString()), uuidsOf(byPurl));
		HttpResponse<String> byValue = get(w.base() + "/componentReleases?idValue="
				+ java.net.URLEncoder.encode(w.priv().purl(), java.nio.charset.StandardCharsets.UTF_8), w.cover());
		assertEquals(List.of(w.priv().head().toString()), uuidsOf(byValue));
		HttpResponse<String> hiddenFromAnonymous = get(w.base() + "/componentReleases?idValue="
				+ java.net.URLEncoder.encode(w.priv().purl(), java.nio.charset.StandardCharsets.UTF_8), null);
		assertTrue(uuidsOf(hiddenFromAnonymous).isEmpty());
		HttpResponse<String> compliance = get(w.base() + "/components?idType=COMPLIANCE_DOCUMENT", w.cover());
		assertEquals(List.of(w.pub().teaComponent().toString()), uuidsOf(compliance));
		assertTrue(uuidsOf(get(w.base() + "/products?idType=COMPLIANCE_DOCUMENT", w.cover())).isEmpty());
		assertError(get(w.base() + "/components?idType=SERIAL", null), 400, "INVALID_REQUEST", "idType=SERIAL");
	}

	// 33 (round 2, T-1): every unmounted TEA path, the removed 0.4.0 base included, answers a body that is
	// not a TEA error-response; the browser chain keeps its answer outside /tea/
	@Test
	void theZeroFourBaseIsGoneAndTheBetaFlagIsInert() throws Exception {
		TeaWorld w = world();
		String org = root() + "/tea/" + w.teaOrg();
		List<String> unmounted = List.of(root() + "/tea/v0.4.0/products", root() + "/tea/v0.4.0/product/" + UUID.randomUUID(),
				org + "/v0.4.0/products", org + "/products", root() + "/tea", w.base() + "/nothing",
				w.base() + "/componentRelease/" + w.pub().head() + "/");
		for (String url : unmounted) {
			for (String bearer : java.util.Arrays.asList(null, BAD, w.cover())) {
				assertNotTeaError(send("GET", url, bearer, null), 404, "Not Found", url + " " + bearer);
			}
		}
		assertNotTeaError(send("GET", w.base() + "/componentRelease/" + w.pub().head() + ";x=1", null, null), 400,
				"Bad Request", "a path parameter");
		assertNotTeaError(send("POST", w.base() + "/products", null, null), 405, "Method Not Allowed", "POST");
		assertNotTeaError(send("GET", w.base() + "/products", null, "text/plain"), 406, "Not Acceptable", "Accept text/plain");
		// the negative control: an unmounted path outside the TEA chain keeps the browser chain's answer
		HttpResponse<String> outside = send("GET", root() + "/.well-known/tea/x", null, null);
		assertEquals(401, outside.statusCode(), outside.body());
		assertFalse(NOT_FOUND_BODY.equals(outside.body()), outside.body());

		RelizaConfigProps bound = new Binder(new MapConfigurationPropertySource(Map.of(
				"relizaprops.enableBetaTea", "true", "relizaprops.baseuri", "http://x"))).bind("relizaprops", RelizaConfigProps.class).get();
		assertEquals("http://x", bound.getBaseuri(), "an unknown relizaprops key binds without error");
	}

	// Task TEA-21, design section 5 rows 10 to 13 and 16: parameters Tomcat could not decode are answered on the TEA
	// chain, through the real container, without the container's ERROR line. The root capture sees what reaches root
	// (the container's StandardWrapperValve ERROR line would; the non-additive io.reliza and Spring configurations
	// never do), levels unchanged; the advice's and the token controller's loggers are opened at DEBUG. Never raise
	// DispatcherServlet to DEBUG here: its request logging reads the parameters before any handler and brings the
	// ERROR line back (design section 6).
	private static final String UNDECODABLE = "TEA request refused, parameters could not be decoded (%s): path %s, parameter %s";

	/**
	 * No ERROR and no stack trace reached root, and no WARN from a thread other than the test's own (the test's
	 * schema validator, assertConforms, warns once about an unknown keyword on its first use).
	 */
	private static void assertNothingReachedRootAboveInfo(LogEventCapture root) {
		String testThread = Thread.currentThread().getName();
		List<LogEvent> loud = root.events().stream().filter(e -> e.getLevel().isMoreSpecificThan(Level.ERROR)
				|| null != e.getThrown() || (Level.WARN.equals(e.getLevel()) && !testThread.equals(e.getThreadName()))).toList();
		assertTrue(loud.isEmpty(), "no ERROR, WARN or stack trace reaches root: " + loud.stream()
				.map(e -> e.getLevel() + " " + e.getLoggerName() + " " + e.getMessage().getFormattedMessage()).toList());
	}

	private static void assertUndecodable(HttpResponse<String> r, String what) {
		assertError(r, 400, "INVALID_REQUEST", what);
		assertEquals("the query string could not be decoded", json(r).get("message"), what);
		assertTrue(r.headers().firstValue("Content-Type").orElse("").startsWith("application/json"), what);
		assertTrue(r.headers().firstValue("Set-Cookie").isEmpty(), what);
	}

	/** The single DEBUG line the capture holds, which carries no throwable; the capture is then cleared. */
	private static void assertOneDebugLine(LogEventCapture logs, String expected) {
		List<LogEvent> events = logs.events();
		assertEquals(1, events.size(), String.valueOf(events.stream().map(e -> e.getMessage().getFormattedMessage()).toList()));
		assertEquals(Level.DEBUG, events.get(0).getLevel());
		assertEquals(null, events.get(0).getThrown());
		assertEquals(expected, events.get(0).getMessage().getFormattedMessage());
		logs.clear();
	}

	// TEA-21 rows 10, 11, 12 and 16
	@Test
	void aQueryValueThatIsNotUtf8IsInvalidRequestWithoutTheContainersErrorLine() throws Exception {
		TeaWorld w = world();
		String path = "/tea/" + w.teaOrg() + "/v1.0.0";
		String any = UUID.randomUUID().toString();
		try (LogEventCapture root = LogEventCapture.root();
				LogEventCapture advice = LogEventCapture.of(TeaErrorAdvice.class, Level.DEBUG)) {
			// 10
			for (String listing : List.of("/products", "/components")) {
				assertUndecodable(get(w.base() + listing + "?idValue=%C3%28", null), listing);
				assertOneDebugLine(advice, UNDECODABLE.formatted("MalformedInputException", path + listing, "idValue"));
			}
			// 11: the mounted download paths, mediaType the first parameter read; not /artifact/<uuid>/download,
			// which is no download path (F-1)
			String latest = "/artifact/" + any + "/latest/download";
			assertUndecodable(get(w.base() + latest + "?mediaType=%C3%28", null), "GET latest");
			assertOneDebugLine(advice, UNDECODABLE.formatted("MalformedInputException", path + latest, "mediaType"));
			HttpResponse<String> head = send("HEAD", w.base() + latest + "?mediaType=%C3%28", null, null);
			assertEquals(400, head.statusCode(), head.body());
			assertEquals("", head.body());
			assertEquals("no-store", head.headers().firstValue("Cache-Control").orElse(null));
			assertTrue(head.headers().firstValue("Set-Cookie").isEmpty());
			assertOneDebugLine(advice, UNDECODABLE.formatted("MalformedInputException", path + latest, "mediaType"));
			String versioned = "/artifact/" + any + "/1/download";
			assertUndecodable(get(w.base() + versioned + "?mediaType=%C3%28", null), "GET /1");
			assertOneDebugLine(advice, UNDECODABLE.formatted("MalformedInputException", path + versioned, "mediaType"));
			// 16: the empty-name producer, no cause
			assertUndecodable(get(w.base() + "/products?=x", null), "?=x");
			assertOneDebugLine(advice, UNDECODABLE.formatted("InvalidParameterException", path + "/products", "-"));
			// 12: valid UTF-8 decodes as before
			HttpResponse<String> valid = get(w.base() + "/products?idValue=%C3%A9", null);
			assertEquals(200, valid.statusCode(), valid.body());
			assertTrue(results(valid).isEmpty(), valid.body());
			assertTrue(advice.events().isEmpty(), String.valueOf(advice.events()));
			assertNothingReachedRootAboveInfo(root);
		}
	}

	// TEA-21 row 13: the token endpoint, form body and query string, in its RFC 6749 shape
	@Test
	void theTokenEndpointAnswersUndecodableParametersAsInvalidRequest() throws Exception {
		TeaWorld w = world();
		String path = "/tea/" + w.teaOrg() + "/v1.0.0/token";
		try (LogEventCapture root = LogEventCapture.root();
				LogEventCapture token = LogEventCapture.of(TeaTokenController.class, Level.DEBUG)) {
			HttpResponse<String> body = postForm(root() + path, "grant_type=client_credentials&client_id=%C3%28");
			assertTokenUndecodable(body, "form body");
			assertOneDebugLine(token, UNDECODABLE.formatted("MalformedInputException", path, "-"));
			HttpResponse<String> query = postForm(root() + path + "?x=%C3%28", "grant_type=client_credentials");
			assertTokenUndecodable(query, "query");
			assertOneDebugLine(token, UNDECODABLE.formatted("MalformedInputException", path, "x"));
			assertNothingReachedRootAboveInfo(root);
		}
	}

	private static void assertTokenUndecodable(HttpResponse<String> r, String what) {
		assertEquals(400, r.statusCode(), what + ": " + r.body());
		assertEquals("invalid_request", json(r).get("error"), what);
		assertEquals("the request parameters could not be decoded", json(r).get("error_description"), what);
		assertEquals("no-store", r.headers().firstValue("Cache-Control").orElse(null), what);
		assertTrue(r.headers().firstValue("Set-Cookie").isEmpty(), what);
	}

	HttpResponse<String> postForm(String url, String form) throws Exception {
		return http.send(java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).header("X-Forwarded-For", randomIp())
				.header("Content-Type", "application/x-www-form-urlencoded")
				.POST(java.net.http.HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString());
	}

	HttpResponse<String> send(String method, String url, String bearer, String accept) throws Exception {
		java.net.http.HttpRequest.Builder b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
				.method(method, java.net.http.HttpRequest.BodyPublishers.noBody()).header("X-Forwarded-For", randomIp());
		if (null != bearer) b.header("Authorization", "Bearer " + bearer);
		if (null != accept) b.header("Accept", accept);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
	}
}
