/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;

import tools.jackson.databind.JsonNode;

import io.reliza.common.Utils;

/**
 * PFA-3 architecture 4.1: a multipart request over the upload limit, through a real Tomcat (the only rig that
 * enforces the multipart limits and runs a container error dispatch). Before PFA-3 the size rejection was sent
 * through sendError(413) and re-dispatched to /error (the task's reproduction got the browser chain's 401 there; in
 * this rig the dispatch fails on the same parse and Tomcat writes its HTML 413 page); now the request that reaches
 * the DispatcherServlet gets the fixed 413 of {@link UploadLimitExceededHandler}, and every request refused before
 * the servlet keeps its answer.
 *
 * <p>The limits are 64 KB here, so the oversized body (about 200 KB) stays under Tomcat's default swallow limit of
 * 2 MB: the server drains it and the answer always arrives.
 */
@TestPropertySource(properties = { "spring.servlet.multipart.max-file-size=64KB",
		"spring.servlet.multipart.max-request-size=64KB" })
class UploadLimitServerIntegrationTest extends ProgrammaticArtifactDownloadTestBase {

	private static final String PROGRAMMATIC_GRAPHQL = "/api/programmatic/graphql";
	private static final String UPLOAD_OPERATIONS = "{\"query\":\"mutation ($file: Upload!) { __typename }\","
			+ "\"variables\":{\"file\":null}}";
	private static final String UPLOAD_MAP = "{\"0\":[\"variables.file\"]}";
	private static final int OVERSIZED_FILE_BYTES = 200 * 1024;
	/** Fresh addresses from 198.51.100.0/24 (documentation range), so no case shares a limiter bucket. */
	private static final AtomicInteger ADDRESSES = new AtomicInteger(1);

	/** A multipart body as rearm-client-go builds it: operations, map, then the file parts in order. */
	private record Multipart(String boundary, byte[] body) {
		String contentType() {
			return "multipart/form-data; boundary=" + boundary;
		}
	}

	private static Multipart multipart(String operations, String map, byte[] file) throws Exception {
		String boundary = "pfa3-" + UUID.randomUUID();
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		textPart(out, boundary, "operations", operations);
		textPart(out, boundary, "map", map);
		if (null != file) {
			out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"0\"; filename=\"bom.json\"\r\n"
					+ "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
			out.write(file);
			out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
		}
		out.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));
		return new Multipart(boundary, out.toByteArray());
	}

	private static void textPart(ByteArrayOutputStream out, String boundary, String name, String value) throws Exception {
		out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
				.getBytes(StandardCharsets.UTF_8));
	}

	private static Multipart oversized() throws Exception {
		byte[] file = new byte[OVERSIZED_FILE_BYTES];
		Arrays.fill(file, (byte) 'a');
		return multipart(UPLOAD_OPERATIONS, UPLOAD_MAP, file);
	}

	/** The multipart POST, from a fresh address, with the given extra headers as name, value pairs. */
	private HttpResponse<String> send(String path, Multipart m, String... headers) throws Exception {
		HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
				.header("X-Forwarded-For", "198.51.100." + ADDRESSES.getAndIncrement())
				.header(HttpHeaders.CONTENT_TYPE, m.contentType())
				.POST(HttpRequest.BodyPublishers.ofByteArray(m.body()));
		for (int i = 0; i < headers.length; i += 2) b.header(headers[i], headers[i + 1]);
		return http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}

	private static void assertFixed413(HttpResponse<String> r, String what) {
		assertStatus(413, r, what);
		assertEquals(UploadLimitExceededHandler.BODY, r.body(), what);
		assertEquals("application/json", header(r, HttpHeaders.CONTENT_TYPE), what);
		assertEquals("no-store", header(r, HttpHeaders.CACHE_CONTROL), what);
		assertNull(header(r, HttpHeaders.WWW_AUTHENTICATE), what + ": no challenge");
	}

	/** T-1: a verified key on the programmatic endpoint, as a bearer and as Basic, gets the fixed 413. */
	@Test
	void anOversizedProgrammaticUploadWithAValidKeyIsTheFixed413() throws Exception {
		Multipart m = oversized();
		HttpResponse<String> asBearer = send(PROGRAMMATIC_GRAPHQL, m, HttpHeaders.AUTHORIZATION, bearer(exchanged(w.orgKey())));
		assertFixed413(asBearer, "bearer");
		assertNull(header(asBearer, HttpHeaders.SET_COOKIE), "bearer: no session cookie");
		HttpResponse<String> asBasic = send(PROGRAMMATIC_GRAPHQL, m, HttpHeaders.AUTHORIZATION, basic(w.orgKey()));
		assertFixed413(asBasic, "basic");
		assertNull(header(asBasic, HttpHeaders.SET_COOKIE), "basic: no session cookie");
	}

	/** T-2: without a credential, or with one the programmatic filter refuses, the 401 comes before the body is read. */
	@Test
	void anOversizedProgrammaticUploadWithoutAValidCredentialKeepsTheFilters401() throws Exception {
		Multipart m = oversized();
		HttpResponse<String> none = send(PROGRAMMATIC_GRAPHQL, m);
		assertStatus(401, none, "no Authorization");
		assertEquals(ProgrammaticAuthenticationFilter.NO_CREDENTIAL_CHALLENGE, header(none, HttpHeaders.WWW_AUTHENTICATE),
				"no Authorization: the filter's challenge");
		assertNotEquals(UploadLimitExceededHandler.BODY, none.body(), "no Authorization: not the handler's body");

		HttpResponse<String> garbage = send(PROGRAMMATIC_GRAPHQL, m, HttpHeaders.AUTHORIZATION, "Bearer garbage");
		assertStatus(401, garbage, "Bearer garbage");
		String challenge = header(garbage, HttpHeaders.WWW_AUTHENTICATE);
		assertNotNull(challenge, "Bearer garbage: a challenge");
		assertTrue(challenge.startsWith(INVALID_TOKEN), "Bearer garbage: " + challenge);
		assertNotEquals(UploadLimitExceededHandler.BODY, garbage.body(), "Bearer garbage: not the handler's body");
	}

	/**
	 * T-3: an anonymous client after the CSRF handshake reaches the parser on the legacy /graphql and gets the fixed
	 * 413. The token travels in the X-XSRF-TOKEN header: only the header is read without parsing the body.
	 */
	@Test
	void anOversizedAnonymousLegacyUploadAfterTheCsrfHandshakeIsTheFixed413() throws Exception {
		HttpResponse<String> handshake = http.send(HttpRequest.newBuilder(
				URI.create("http://localhost:" + port + "/api/manual/v1/fetchCsrf"))
				.header("X-Forwarded-For", "198.51.100." + ADDRESSES.getAndIncrement()).GET().build(),
				HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		assertStatus(200, handshake, "fetchCsrf");
		JsonNode csrf = Utils.OM.readTree(handshake.body());
		List<String> cookies = new ArrayList<>();
		for (String setCookie : handshake.headers().allValues(HttpHeaders.SET_COOKIE)) {
			cookies.add(setCookie.split(";", 2)[0]);
		}
		assertTrue(cookies.stream().anyMatch(c -> c.startsWith("XSRF-TOKEN=")), "fetchCsrf: the XSRF-TOKEN cookie " + cookies);

		HttpResponse<String> r = send("/graphql", oversized(), csrf.get("headerName").asText(), csrf.get("token").asText(),
				HttpHeaders.COOKIE, String.join("; ", cookies));
		assertFixed413(r, "anonymous /graphql after the handshake");
	}

	/**
	 * T-4: without a credential and without the CSRF handshake, a browser-chain path that needs authentication is
	 * refused before the DispatcherServlet, so the handler gives such a caller nothing and the answer is today's.
	 *
	 * <p>Departure from the design's assertion (401 with a Bearer challenge), recorded in the PFA-8 notes: the CSRF
	 * filter looks for a _csrf parameter, Tomcat 11 parses the oversized parts inside that getParameter call and throws
	 * InvalidParameterException (status 413) out of the filter; the /error dispatch fails on the same exception and
	 * Tomcat's own HTML 413 page is the answer, before and after PFA-3. What is pinned is that it is not the handler's.
	 */
	@Test
	void anOversizedAnonymousPostWithoutTheCsrfHeaderNeverReachesTheHandler() throws Exception {
		HttpResponse<String> r = send("/api/manual/v1/upload-limit-probe", oversized());
		assertStatus(413, r, "anonymous POST without CSRF");
		String contentType = header(r, HttpHeaders.CONTENT_TYPE);
		assertNotNull(contentType, "anonymous POST without CSRF: a content type");
		assertTrue(contentType.startsWith("text/html"), "anonymous POST without CSRF: the container's page, got " + contentType);
		assertNotEquals(UploadLimitExceededHandler.BODY, r.body(), "anonymous POST without CSRF: not the handler's body");
	}

	/** T-5: a multipart request under the limit is not answered by the handler and reaches GraphQL. */
	@Test
	void aMultipartRequestUnderTheLimitIsAnsweredByGraphql() throws Exception {
		Multipart m = multipart("{\"query\":\"{ __typename }\"}", "{}", null);
		HttpResponse<String> r = send(PROGRAMMATIC_GRAPHQL, m, HttpHeaders.AUTHORIZATION, bearer(exchanged(w.orgKey())));
		assertNotEquals(413, r.statusCode(), () -> "under the limit: " + r.body());
		JsonNode envelope = Utils.OM.readTree(r.body());
		assertTrue(envelope.has("data") || envelope.has("errors"), "under the limit: a GraphQL envelope, got " + r.body());
	}
}
