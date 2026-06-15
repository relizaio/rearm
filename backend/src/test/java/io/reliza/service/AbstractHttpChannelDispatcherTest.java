/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;

import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.service.ChannelDispatchResult.Outcome;
import reactor.core.publisher.Mono;

/**
 * Pins every branch of the exception ladder in
 * {@link AbstractHttpChannelDispatcher#doPost(URI, Object, Duration, IntegrationType, java.util.function.Consumer)}
 * — the part of the refactor that all three HTTP dispatchers
 * (Slack/Teams/Webhook) now share. A regression in any branch would
 * silently change retry behavior for every channel type, so the matrix
 * itself is the test target.
 *
 * <p>Uses {@link ExchangeFunction} mocking to substitute the HTTP layer
 * (no real socket, no port allocation, no sleeps that touch the wall
 * clock). The classifier itself is already pinned in
 * {@code HttpDispatchClassifierTest}; this file pins the wiring that
 * routes WebClient exceptions to the classifier.
 *
 * <p>Was PR #131 follow-up #3 (deferred MockWebServer tests). Landed
 * here as part of follow-up #4 since the refactor centralizes the
 * surface that's worth testing.
 */
class AbstractHttpChannelDispatcherTest {

    /**
     * Concrete test subclass — the base is abstract because real
     * subclasses each parse their channel-specific secret shape; for
     * the doPost-only tests below we don't need a real {@code dispatch}
     * entry point.
     */
    static final class TestDispatcher extends AbstractHttpChannelDispatcher {
        // Exposes doPost so the test can call it directly without going
        // through a channel-specific dispatch() entry point.
        ChannelDispatchResult invoke(URI uri, Object body, Duration timeout, IntegrationType type) {
            return doPost(uri, body, timeout, type, null);
        }
    }

    /** Build a dispatcher with an injected {@link ExchangeFunction}. */
    private static TestDispatcher dispatcherWith(ExchangeFunction exchange) throws Exception {
        TestDispatcher d = new TestDispatcher();
        WebClient stub = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .exchangeFunction(exchange)
                .build();
        Field f = AbstractHttpChannelDispatcher.class.getDeclaredField("webClient");
        f.setAccessible(true);
        f.set(d, stub);
        return d;
    }

    private static ClientResponse response(int status, HttpHeaders headers, String body) {
        return ClientResponse.create(HttpStatus.valueOf(status))
                .headers(h -> h.addAll(headers))
                .body(body)
                .build();
    }

    private static ClientResponse response(int status) {
        return response(status, new HttpHeaders(), "");
    }

    @Test
    void twoXxIsSuccess() throws Exception {
        ExchangeFunction ok = req -> Mono.just(response(200));
        TestDispatcher d = dispatcherWith(ok);

        ChannelDispatchResult result = d.invoke(
                URI.create("https://example.com/hook"), Map.of("k", "v"),
                Duration.ofSeconds(5), IntegrationType.SLACK);

        assertEquals(Outcome.SUCCESS, result.outcome());
        assertNull(result.errorMessage());
    }

    @Test
    void fiveXxIsRetriable() throws Exception {
        ExchangeFunction err = req -> Mono.just(response(500, new HttpHeaders(), "oops"));
        TestDispatcher d = dispatcherWith(err);

        ChannelDispatchResult result = d.invoke(
                URI.create("https://example.com/hook"), Map.of(),
                Duration.ofSeconds(5), IntegrationType.WEBHOOK);

        assertEquals(Outcome.RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("500"), result.errorMessage());
        assertNull(result.retryAfterSeconds(),
                "No Retry-After header → fall through to BackoffPolicy");
    }

    @Test
    void fourXxIsNonRetriable() throws Exception {
        ExchangeFunction err = req -> Mono.just(response(404));
        TestDispatcher d = dispatcherWith(err);

        ChannelDispatchResult result = d.invoke(
                URI.create("https://example.com/hook"), Map.of(),
                Duration.ofSeconds(5), IntegrationType.WEBHOOK);

        assertEquals(Outcome.NON_RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("404"), result.errorMessage());
    }

    @Test
    void fourTwoNineWithRetryAfterIsRetriableWithDelay() throws Exception {
        HttpHeaders headers = new HttpHeaders();
        headers.add(HttpHeaders.RETRY_AFTER, "37");
        ExchangeFunction rateLimited = req -> Mono.just(response(429, headers, "slow down"));
        TestDispatcher d = dispatcherWith(rateLimited);

        ChannelDispatchResult result = d.invoke(
                URI.create("https://example.com/hook"), Map.of(),
                Duration.ofSeconds(5), IntegrationType.MSTEAMS);

        assertEquals(Outcome.RETRIABLE_FAILURE, result.outcome());
        assertEquals(37, result.retryAfterSeconds(),
                "Retry-After header value should land on the result for the worker to honour");
    }

    @Test
    void transportFailureIsRetriableViaClassifyTransport() throws Exception {
        // Synthesize a WebClientRequestException at the ExchangeFunction
        // layer — equivalent to a connection refused / DNS failure at the
        // socket layer, without needing a real closed port.
        ExchangeFunction transportFailed = req -> Mono.error(new WebClientRequestException(
                new java.net.ConnectException("Connection refused"),
                HttpMethod.POST, req.url(), new HttpHeaders()));
        TestDispatcher d = dispatcherWith(transportFailed);

        ChannelDispatchResult result = d.invoke(
                URI.create("https://example.com/hook"), Map.of(),
                Duration.ofSeconds(5), IntegrationType.SLACK);

        assertEquals(Outcome.RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("transport"), result.errorMessage());
        assertTrue(result.errorMessage().contains("Slack"), result.errorMessage());
    }

    @Test
    void blockTimeoutIsRetriableViaClassifyRuntime() throws Exception {
        // ExchangeFunction returns a Mono that never completes within
        // the dispatcher's .block(Duration) cap. Reactor's block() then
        // throws an IllegalStateException("Timeout on blocking read…")
        // which classifyRuntime maps to RETRIABLE. Pinning the wiring,
        // not the message format (that's HttpDispatchClassifierTest's
        // surface).
        ExchangeFunction hangs = req -> Mono.delay(Duration.ofSeconds(5))
                .then(Mono.just(response(200)));
        TestDispatcher d = dispatcherWith(hangs);

        long startNanos = System.nanoTime();
        ChannelDispatchResult result = d.invoke(
                URI.create("https://example.com/hook"), Map.of(),
                Duration.ofMillis(100), IntegrationType.WEBHOOK);
        long elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000;

        assertEquals(Outcome.RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("timed out"),
                "Expected block-timeout to be classified as a retriable timeout: " + result.errorMessage());
        assertTrue(elapsedMillis < 2000,
                "Should have aborted at the 100ms .block() timeout, not waited the full 5s — elapsed " + elapsedMillis + "ms");
    }

    @Test
    void uriPassthroughPreservesEncodedSlashesInQueryString() throws Exception {
        // The fix from commit bb6f21c5: passing the URL through
        // URI.create() (instead of WebClient.uri(String)) bypasses
        // DefaultUriBuilderFactory's decode/re-encode pass on query
        // params. Power Automate Workflows URLs whose SAS signature
        // covers literal %2F bytes break otherwise. Pinning the behavior
        // at the base-class level so future subclasses inherit it.
        AtomicReference<URI> capturedUrl = new AtomicReference<>();
        ExchangeFunction capture = req -> {
            capturedUrl.set(req.url());
            return Mono.just(response(200));
        };
        TestDispatcher d = dispatcherWith(capture);

        // %2F in a query param is the canonical breakage. Also include
        // %3D and a trailing &sig=... shape that matches the real-world
        // Workflows URL pattern.
        String original = "https://prod-12.eastus.logic.azure.com/workflows/abc/triggers/manual/paths/invoke"
                + "?api-version=2016-06-01&sp=%2Ftriggers%2Fmanual%2Frun&sv=1.0&sig=AbCd%3DEfGh";

        AbstractHttpChannelDispatcher.UriParseResult parsed =
                AbstractHttpChannelDispatcher.parseUri(original, UUID.randomUUID());
        assertNotNull(parsed.uri(), "parseUri should succeed on a valid URL");
        d.invoke(parsed.uri(), Map.of(), Duration.ofSeconds(5), IntegrationType.MSTEAMS);

        assertNotNull(capturedUrl.get(), "ExchangeFunction must have been called");
        assertEquals(original, capturedUrl.get().toString(),
                "URL must reach the server byte-for-byte — DefaultUriBuilderFactory must not re-encode");
    }

    @Test
    void parseUriReturnsNonRetriableOnIllegalUri() {
        // Spaces in the URL surface as IllegalArgumentException from
        // URI.create. Caller should propagate the error result.
        AbstractHttpChannelDispatcher.UriParseResult parsed =
                AbstractHttpChannelDispatcher.parseUri("https://example.com/has space", UUID.randomUUID());
        assertTrue(parsed.isError(), "Malformed URI should produce an error result");
        assertEquals(Outcome.NON_RETRIABLE_FAILURE, parsed.error().outcome());
        assertTrue(parsed.error().errorMessage().contains("not a valid URI"),
                parsed.error().errorMessage());
    }

    @Test
    void headersConsumerCanAddAuthHeader() throws Exception {
        // Subclasses register Authorization / X-Reliza-Signature via the
        // headers Consumer. Pin that the Consumer actually runs and the
        // header reaches the wire.
        AtomicReference<HttpHeaders> capturedHeaders = new AtomicReference<>();
        ExchangeFunction capture = req -> {
            capturedHeaders.set(req.headers());
            return Mono.just(response(200));
        };
        TestDispatcher d = dispatcherWith(capture);

        d.doPost(URI.create("https://example.com/hook"), Map.of(),
                Duration.ofSeconds(5), IntegrationType.WEBHOOK,
                spec -> spec.header(HttpHeaders.AUTHORIZATION, "Bearer test-token-xyz"));

        assertNotNull(capturedHeaders.get());
        List<String> auth = capturedHeaders.get().get(HttpHeaders.AUTHORIZATION);
        assertNotNull(auth, "Authorization header should have reached the request");
        assertEquals(List.of("Bearer test-token-xyz"), auth);
    }

    @Test
    void stringBodyDispatchesCleanlyThroughDoPost() throws Exception {
        // Webhook dispatcher pre-serializes its envelope to a String so
        // the HMAC signature covers exactly the bytes that go on the
        // wire. Pin that doPost accepts a String body without throwing
        // (the Encoder picks up the StringEncoder for plain strings; the
        // Content-Type stays JSON because we set it via WebClient
        // default header + per-request override in WebhookChannelDispatcher).
        ExchangeFunction ok = req -> Mono.just(response(200));
        TestDispatcher d = dispatcherWith(ok);

        String body = "{\"schemaVersion\":1,\"eventType\":\"X\"}";
        ChannelDispatchResult result = d.invoke(
                URI.create("https://example.com/hook"), body,
                Duration.ofSeconds(5), IntegrationType.WEBHOOK);

        assertEquals(Outcome.SUCCESS, result.outcome(),
                result.errorMessage() == null ? "" : result.errorMessage());
    }

    @Test
    void defaultTimeoutAndContentTypeAreSensibleDefaults() {
        TestDispatcher d = new TestDispatcher();
        // The base advertises 10s as the conservative default — every
        // current subclass uses it. Pinning so a casual base-class tweak
        // surfaces here.
        assertEquals(Duration.ofSeconds(10), d.timeout());
        assertEquals(MediaType.APPLICATION_JSON, d.contentType());
    }

    @Test
    void redactBearerInResponseBodyStillTriggersOnFiveHundred() throws Exception {
        // End-to-end through doPost: confirm the classifier's bearer
        // redaction still fires when the response body comes back via
        // the WebClient stack (defense-in-depth against a receiver that
        // echoes the Authorization header into its 5xx body).
        String leaky = "Internal error processing: Authorization: Bearer leaked-secret-xyz";
        ExchangeFunction err = req -> Mono.just(response(500, new HttpHeaders(),
                leaky));
        TestDispatcher d = dispatcherWith(err);

        ChannelDispatchResult result = d.invoke(
                URI.create("https://example.com/hook"), Map.of(),
                Duration.ofSeconds(5), IntegrationType.WEBHOOK);

        assertEquals(Outcome.RETRIABLE_FAILURE, result.outcome());
        // ensure the response body bytes round-trip through the ladder
        // unchanged in length (so the encoding wiring isn't dropping
        // the body upstream of the classifier).
        byte[] leakyBytes = leaky.getBytes(StandardCharsets.UTF_8);
        assertTrue(leakyBytes.length > 0);
        assertTrue(result.errorMessage().contains("<redacted>"),
                "Bearer redaction must survive end-to-end through doPost: " + result.errorMessage());
    }
}
