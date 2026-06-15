/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.reliza.model.IntegrationData.IntegrationType;

/**
 * Pin the response-code matrix, Retry-After parsing, transport-error,
 * block-timeout, and bearer-token redaction that both
 * {@link SlackChannelDispatcher} and {@link WebhookChannelDispatcher}
 * delegate to. A regression in the matrix would silently change retry
 * behavior across every channel type, so the matrix is the test target.
 */
class HttpDispatchClassifierTest {

    @Test
    void retriableStatusMatrixCoversFiveHundredsAnd408_425_429() {
        assertTrue(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(500)));
        assertTrue(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(502)));
        assertTrue(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(503)));
        assertTrue(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(504)));
        assertTrue(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(599)));
        assertTrue(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(408)));
        assertTrue(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(425)));
        assertTrue(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(429)));
    }

    @Test
    void nonRetriableStatusMatrixExcludesNormal4xx() {
        // Misconfigured webhook / revoked token / bad request — retry
        // will not recover, so the worker must mark FAILED.
        assertFalse(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(400)));
        assertFalse(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(401)));
        assertFalse(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(403)));
        assertFalse(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(404)));
        assertFalse(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(410)));
        assertFalse(HttpDispatchClassifier.isRetriableStatus(HttpStatusCode.valueOf(422)));
    }

    @Test
    void classifyResponse500IsRetriable() {
        WebClientResponseException wcre = WebClientResponseException.create(
                500, "Internal Server Error", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        ChannelDispatchResult result = HttpDispatchClassifier.classifyResponse(wcre, IntegrationType.SLACK);
        assertEquals(ChannelDispatchResult.Outcome.RETRIABLE_FAILURE, result.outcome());
        assertNull(result.retryAfterSeconds());
        assertTrue(result.errorMessage().contains("Slack"));
        assertTrue(result.errorMessage().contains("500"));
    }

    @Test
    void classifyResponse429WithRetryAfterIsRetriableAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "42");
        WebClientResponseException wcre = WebClientResponseException.create(
                429, "Too Many Requests", headers, new byte[0], StandardCharsets.UTF_8);
        ChannelDispatchResult result = HttpDispatchClassifier.classifyResponse(wcre, IntegrationType.WEBHOOK);
        assertEquals(ChannelDispatchResult.Outcome.RETRIABLE_FAILURE, result.outcome());
        assertEquals(42, result.retryAfterSeconds());
    }

    @Test
    void classifyResponse429WithoutRetryAfterFallsBackToBackoff() {
        WebClientResponseException wcre = WebClientResponseException.create(
                429, "Too Many Requests", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        ChannelDispatchResult result = HttpDispatchClassifier.classifyResponse(wcre, IntegrationType.WEBHOOK);
        assertEquals(ChannelDispatchResult.Outcome.RETRIABLE_FAILURE, result.outcome());
        assertNull(result.retryAfterSeconds(),
                "Missing Retry-After should fall through to BackoffPolicy, not pin to a constant");
    }

    @Test
    void classifyResponse429WithNonNumericRetryAfterFallsBackToBackoff() {
        // HTTP-date form (RFC 7231) is legal but rare; we don't parse it,
        // so the worker should fall back to its own backoff curve rather
        // than treat the header as zero (which would hot-loop).
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "Wed, 21 Oct 2026 07:28:00 GMT");
        WebClientResponseException wcre = WebClientResponseException.create(
                429, "Too Many Requests", headers, new byte[0], StandardCharsets.UTF_8);
        ChannelDispatchResult result = HttpDispatchClassifier.classifyResponse(wcre, IntegrationType.WEBHOOK);
        assertEquals(ChannelDispatchResult.Outcome.RETRIABLE_FAILURE, result.outcome());
        assertNull(result.retryAfterSeconds());
    }

    @Test
    void classifyResponse429WithZeroOrNegativeRetryAfterFallsBackToBackoff() {
        // A receiver that sends "Retry-After: 0" would otherwise cause
        // the worker to schedule the next attempt immediately, defeating
        // the rate-limit. Treat zero/negative as missing.
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "0");
        WebClientResponseException wcre = WebClientResponseException.create(
                429, "Too Many Requests", headers, new byte[0], StandardCharsets.UTF_8);
        ChannelDispatchResult result = HttpDispatchClassifier.classifyResponse(wcre, IntegrationType.WEBHOOK);
        assertNull(result.retryAfterSeconds());
    }

    @Test
    void classifyResponse404IsNonRetriable() {
        WebClientResponseException wcre = WebClientResponseException.create(
                404, "Not Found", new HttpHeaders(), new byte[0], StandardCharsets.UTF_8);
        ChannelDispatchResult result = HttpDispatchClassifier.classifyResponse(wcre, IntegrationType.WEBHOOK);
        assertEquals(ChannelDispatchResult.Outcome.NON_RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("non-retriable"));
        assertTrue(result.errorMessage().contains("404"));
    }

    @Test
    void classifyResponseRedactsBearerTokenFromBody() {
        // Defense-in-depth: misconfigured receivers sometimes echo the
        // Authorization header back into a 4xx response body. The
        // classifier scrubs it before the worker writes it into the
        // delivery error column + operator log.
        String body = "Unauthorized: Authorization: Bearer abc123xyz was rejected";
        WebClientResponseException wcre = WebClientResponseException.create(
                401, "Unauthorized", new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        ChannelDispatchResult result = HttpDispatchClassifier.classifyResponse(wcre, IntegrationType.WEBHOOK);
        assertFalse(result.errorMessage().contains("abc123xyz"),
                "Token must be redacted before persisting: " + result.errorMessage());
        assertTrue(result.errorMessage().contains("<redacted>"),
                "Expected redaction marker, got: " + result.errorMessage());
    }

    @Test
    void classifyResponseTruncatesLongBody() {
        // Delivery error column is capped at 1024 chars upstream; the
        // body slice is held to 200 so the prefix + truncation + caller
        // metadata still fit comfortably.
        String body = "x".repeat(5000);
        WebClientResponseException wcre = WebClientResponseException.create(
                500, "Internal", new HttpHeaders(), body.getBytes(StandardCharsets.UTF_8),
                StandardCharsets.UTF_8);
        ChannelDispatchResult result = HttpDispatchClassifier.classifyResponse(wcre, IntegrationType.SLACK);
        assertTrue(result.errorMessage().length() < 400,
                "Error message should be truncated, was " + result.errorMessage().length() + " chars");
    }

    @Test
    void classifyTransportIsAlwaysRetriable() {
        WebClientRequestException e = new WebClientRequestException(
                new java.net.ConnectException("Connection refused"),
                HttpMethod.POST, URI.create("https://example.com/hook"), new HttpHeaders());
        ChannelDispatchResult result = HttpDispatchClassifier.classifyTransport(e, IntegrationType.WEBHOOK);
        assertEquals(ChannelDispatchResult.Outcome.RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("transport"));
        assertTrue(result.errorMessage().contains("Webhook"));
    }

    @Test
    void classifyRuntimeBlockTimeoutIsRetriable() {
        // The exact message Reactor's .block(Duration) throws when the
        // upstream takes longer than the cap. Pinned literally so a
        // Reactor upgrade that changes the wording surfaces here, not
        // silently in prod as "config-side fault, non-retriable".
        IllegalStateException timeout = new IllegalStateException(
                "Timeout on blocking read for 10000000000 NANOSECONDS");
        ChannelDispatchResult result = HttpDispatchClassifier.classifyRuntime(
                timeout, Duration.ofSeconds(10), IntegrationType.SLACK);
        assertEquals(ChannelDispatchResult.Outcome.RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("timed out"));
        assertTrue(result.errorMessage().contains("10s"));
    }

    @Test
    void classifyRuntimeOtherIllegalStateIsNonRetriable() {
        // IllegalStateException without "timeout" in the message — a
        // config-side fault that won't recover on retry.
        IllegalStateException other = new IllegalStateException("Bearer auth requires a non-blank token");
        ChannelDispatchResult result = HttpDispatchClassifier.classifyRuntime(
                other, Duration.ofSeconds(10), IntegrationType.WEBHOOK);
        assertEquals(ChannelDispatchResult.Outcome.NON_RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("likely config"));
    }

    @Test
    void classifyRuntimeMalformedUrlIsNonRetriable() {
        // IllegalArgumentException from a malformed URI is a config-side
        // fault — flag non-retriable so it surfaces in the DLQ view.
        ChannelDispatchResult result = HttpDispatchClassifier.classifyRuntime(
                new IllegalArgumentException("Invalid URI"),
                Duration.ofSeconds(10), IntegrationType.WEBHOOK);
        assertEquals(ChannelDispatchResult.Outcome.NON_RETRIABLE_FAILURE, result.outcome());
    }

    @Test
    void redactAuthMaterialMasksBearerTokens() {
        String input = "Unauthorized: Authorization: Bearer secret-token-xyz123 was rejected";
        String redacted = HttpDispatchClassifier.redactAuthMaterial(input);
        assertFalse(redacted.contains("secret-token-xyz123"),
                "Token should have been redacted: " + redacted);
        assertTrue(redacted.contains("Bearer <redacted>"),
                "Expected redaction marker, got: " + redacted);
    }

    @Test
    void redactAuthMaterialHandlesMixedCase() {
        String redacted = HttpDispatchClassifier.redactAuthMaterial("auth: bearer mySecret456");
        assertFalse(redacted.contains("mySecret456"),
                "Case-insensitive redaction should catch lowercase bearer: " + redacted);
    }

    @Test
    void redactAuthMaterialPassesThroughNull() {
        assertNull(HttpDispatchClassifier.redactAuthMaterial(null));
    }

    @Test
    void parseRetryAfterSecondsHandlesBlankAndWhitespace() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "   ");
        WebClientResponseException wcre = WebClientResponseException.create(
                429, "Too Many Requests", headers, new byte[0], StandardCharsets.UTF_8);
        assertNull(HttpDispatchClassifier.parseRetryAfterSeconds(wcre));
    }

    @Test
    void parseRetryAfterSecondsTrimsValue() {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "  17  ");
        WebClientResponseException wcre = WebClientResponseException.create(
                429, "Too Many Requests", headers, new byte[0], StandardCharsets.UTF_8);
        assertEquals(17, HttpDispatchClassifier.parseRetryAfterSeconds(wcre));
    }

    @Test
    void classifyResponse503WithRetryAfterHonorsHeader() {
        // RFC 7231 allows Retry-After on any retriable response, not just
        // 429. Pin the 503 path so a "503 + Retry-After" reply (seen from
        // some upstreams during maintenance windows) still gets the
        // honoured-wait scheduling.
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "120");
        WebClientResponseException wcre = WebClientResponseException.create(
                503, "Service Unavailable", headers, new byte[0], StandardCharsets.UTF_8);
        ChannelDispatchResult result = HttpDispatchClassifier.classifyResponse(wcre, IntegrationType.WEBHOOK);
        assertEquals(ChannelDispatchResult.Outcome.RETRIABLE_FAILURE, result.outcome());
        assertEquals(120, result.retryAfterSeconds());
    }

    @Test
    void classifyRuntimeIllegalStateSubclassTimeoutStillRetriable() {
        // Use instanceof not getSimpleName-equals so a Reactor subclass
        // (or any future re-typing of the timeout signal) still classifies
        // as a block-timeout when the message matches.
        IllegalStateException sub = new IllegalStateException(
                "Timeout on blocking read for 10000000000 NANOSECONDS") {};
        ChannelDispatchResult result = HttpDispatchClassifier.classifyRuntime(
                sub, Duration.ofSeconds(10), IntegrationType.SLACK);
        assertEquals(ChannelDispatchResult.Outcome.RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("timed out"));
    }

    @Test
    void displayLabelCoversAllChannelTypes() {
        // Exhaustive switch on the enum means a new value is a compile
        // error in displayLabel() — pin every existing value here so a
        // typo in a future rename is also a test failure.
        assertEquals("Slack", HttpDispatchClassifier.displayLabel(IntegrationType.SLACK));
        assertEquals("MS Teams", HttpDispatchClassifier.displayLabel(IntegrationType.MSTEAMS));
        assertEquals("Email", HttpDispatchClassifier.displayLabel(IntegrationType.EMAIL));
        assertEquals("Webhook", HttpDispatchClassifier.displayLabel(IntegrationType.WEBHOOK));
        assertEquals("Sentinel", HttpDispatchClassifier.displayLabel(IntegrationType.SENTINEL));
    }
}
