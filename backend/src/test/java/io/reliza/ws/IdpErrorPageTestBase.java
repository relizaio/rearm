/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doThrow;

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpHeaders;

/**
 * TPF-49 (architecture-2 4.7): the error page a signed-in caller gets when the request fails. A
 * valid IdP bearer authenticates the ERROR dispatch on the browser chain, so {@code /error} renders
 * Spring Boot's error attributes for that caller; the UI nginx now passes that answer through as
 * sent. The raw download on the browser path is the failing request: its rebom call throws with
 * {@link #MARKER}, no MVC exception handler maps it, and the container dispatches to {@code /error}.
 *
 * <p>Over the SCORE-17 base, so a real server on a random port with the real chains; the rebom mock
 * resets after each test, so the classes sharing the context are untouched.
 */
abstract class IdpErrorPageTestBase extends ProgrammaticArtifactDownloadTestBase {

	/** An exception text that must never reach the caller: it names an internal host and port. */
	static final String MARKER = "tpf49-marker rebom.internal.invalid:9093";

	/** Runs after the base's serverReady, which stubs rebom to answer. */
	@BeforeEach
	void rebomThrowsTheMarker() throws Exception {
		doThrow(new IllegalStateException(MARKER)).when(rebomService).findRawBomById(any(), any());
		doThrow(new IllegalStateException(MARKER)).when(rebomService).findRawBomByVersion(any(), any(), anyInt());
	}

	/** The downloader's raw download of the release artifact on the browser path, with its IdP token. */
	HttpResponse<String> failingRawDownload(String accept) throws Exception {
		HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port
				+ "/api/manual/v1/artifact/" + w.releaseArtifact() + "/rawdownload"))
				.header(HttpHeaders.AUTHORIZATION, bearer(idpToken))
				.header(HttpHeaders.ACCEPT, accept)
				.GET().build();
		return http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
	}
}
