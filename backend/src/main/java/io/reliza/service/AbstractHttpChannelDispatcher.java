/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.net.URI;
import java.time.Duration;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.reliza.model.IntegrationData.IntegrationType;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared HTTP scaffolding for outbound channel dispatchers that POST a
 * serialized event body to a single URL and classify the response through
 * {@link HttpDispatchClassifier}.
 *
 * <p>Subclasses today: {@link SlackChannelDispatcher},
 * {@link WebhookChannelDispatcher}, {@link TeamsChannelDispatcher}. All
 * three share the same skeleton: build a {@link WebClient}, parse the
 * decrypted webhook URL into a {@link URI}, POST a JSON body, and route
 * any {@code WebClient} exception through the classifier. This base
 * collapses that skeleton so the three subclasses only carry the parts
 * that vary (secret decoding, URL host validation, payload formatting).
 *
 * <h3>What lives here, and why</h3>
 * <ul>
 *   <li><b>{@link #webClient}</b> — a single {@code WebClient} per
 *       dispatcher instance with the default {@code Content-Type} header
 *       set from {@link #contentType()}. Non-final so tests can swap it
 *       via reflection (the existing pattern in
 *       {@code WebhookChannelDispatcherTest}).</li>
 *   <li><b>{@link #parseUri(String, UUID)}</b> — wraps
 *       {@link URI#create(String)} so the URL reaches the server byte-for-byte.
 *       Calling {@code WebClient.uri(String)} routes through
 *       {@code DefaultUriBuilderFactory}, which decodes and re-encodes
 *       query parameters; that broke Power Automate Workflows URLs whose
 *       SAS signature covers the literal {@code %2F} bytes of
 *       {@code sp=%2Ftriggers%2Fmanual%2Frun} (see commit {@code bb6f21c5}).
 *       Slack and Webhook URLs don't carry such signatures today but might
 *       in future, so the passthrough lives in the base rather than the
 *       single subclass that needed it.</li>
 *   <li><b>{@link #doPost(URI, Object, Duration, IntegrationType, Consumer)}</b>
 *       — the try/catch ladder that maps {@code WebClient} exceptions to
 *       {@link ChannelDispatchResult} via {@link HttpDispatchClassifier}.
 *       Identical across Slack, Webhook, and Teams in the pre-refactor
 *       code; centralizing it keeps the retry-classification matrix in
 *       lock-step.</li>
 * </ul>
 *
 * <h3>What stays in subclasses</h3>
 * The {@code dispatch(event, channel)} entry point: decrypting the
 * channel secret, validating the URL against the per-vendor host suffix
 * rule, formatting the payload, and then calling {@link #doPost}. Each
 * channel type has its own secret shape (raw URL for Slack/Teams, JSON
 * blob with auth-scheme for Webhook), formatter, and validator — those
 * stay channel-specific.
 *
 * <h3>Why {@link SentinelChannelDispatcher} deliberately doesn't extend this</h3>
 * Sentinel needs an Azure AD bearer-token pre-amble + a one-shot 401
 * cache-invalidate-and-retry path before it can POST. That doesn't fit
 * the simple POST-and-classify shape, and dragging it into the base
 * would either bloat this class or force Sentinel to override most of
 * its surface anyway. The token-fetch is the load-bearing difference;
 * if someone tries to wedge Sentinel into this base later, they'll
 * re-discover why it doesn't fit.
 */
@Slf4j
public abstract class AbstractHttpChannelDispatcher {

    /**
     * Default per-request timeout. Bounds a hung receiver from stalling
     * the batch — channel worker drains a {@code @Scheduled} batch under
     * an advisory lock, so a sync HTTP call is appropriate. Subclasses
     * override via {@link #timeout()} if they need a different cap.
     */
    protected static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofSeconds(10);

    /**
     * Single {@code WebClient} per dispatcher instance, initialized with
     * the default {@code Content-Type} header from {@link #contentType()}.
     * Non-final so tests can substitute a stub via reflection — the
     * existing per-dispatcher tests already use this pattern.
     */
    protected WebClient webClient = WebClient.builder()
            .defaultHeader(HttpHeaders.CONTENT_TYPE, contentType().toString())
            .build();

    /**
     * Default {@code Content-Type} for outgoing requests. All three
     * current subclasses use JSON; the hook exists so a future subclass
     * can override without re-implementing the WebClient init.
     */
    protected MediaType contentType() {
        return MediaType.APPLICATION_JSON;
    }

    /**
     * Per-request timeout. Defaults to {@link #DEFAULT_HTTP_TIMEOUT};
     * override if a channel needs a different bound (e.g. Sentinel runs
     * 15s because of the AAD token round-trip, but Sentinel doesn't
     * extend this class — see the class javadoc).
     */
    protected Duration timeout() {
        return DEFAULT_HTTP_TIMEOUT;
    }

    /**
     * Parse a decrypted webhook URL into a {@link URI} without going
     * through {@code DefaultUriBuilderFactory}. Returns
     * {@link ChannelDispatchResult#nonRetriable(String)} on
     * {@link IllegalArgumentException} so the caller can short-circuit.
     *
     * <p>The byte-for-byte passthrough is load-bearing for SAS-signed
     * URLs (Power Automate Workflows today; any future signed-URL
     * scheme tomorrow). See class javadoc.
     */
    protected static UriParseResult parseUri(String urlString, UUID channelUuid) {
        try {
            return UriParseResult.ok(URI.create(urlString));
        } catch (IllegalArgumentException e) {
            return UriParseResult.error(ChannelDispatchResult.nonRetriable(
                    "Channel " + channelUuid + " webhook URL is not a valid URI: " + e.getMessage()));
        }
    }

    /**
     * Carrier for {@link #parseUri(String, UUID)} — either a parsed URI
     * or a non-retriable dispatch result the caller should return as-is.
     */
    protected record UriParseResult(URI uri, ChannelDispatchResult error) {
        public static UriParseResult ok(URI uri) {
            return new UriParseResult(uri, null);
        }
        public static UriParseResult error(ChannelDispatchResult error) {
            return new UriParseResult(null, error);
        }
        public boolean isError() {
            return error != null;
        }
    }

    /**
     * Execute the POST and map any {@code WebClient} exception through
     * {@link HttpDispatchClassifier}. {@code headers} runs after the
     * default {@code Content-Type} so a subclass can add e.g.
     * {@code Authorization: Bearer ...} or an HMAC signature header
     * without losing the default. Pass {@code null} (or an empty
     * lambda) when no extra headers are needed.
     *
     * <p>The {@code body} is sent unmodified via
     * {@code .bodyValue(body)}. Subclasses pass either a {@code Map}
     * (let the Jackson encoder serialize) or a pre-serialized
     * {@code String} (Webhook does this so the HMAC signature signs
     * exactly the bytes that go on the wire).
     */
    protected ChannelDispatchResult doPost(URI uri, Object body, Duration timeout,
            IntegrationType channelType,
            Consumer<WebClient.RequestBodySpec> headers) {
        WebClient.RequestBodySpec spec = webClient.post().uri(uri);
        if (headers != null) {
            headers.accept(spec);
        }
        try {
            spec.bodyValue(body)
                    .retrieve()
                    .toEntity(String.class)
                    .block(timeout);
            return ChannelDispatchResult.success();
        } catch (WebClientResponseException wcre) {
            return HttpDispatchClassifier.classifyResponse(wcre, channelType);
        } catch (WebClientRequestException e) {
            return HttpDispatchClassifier.classifyTransport(e, channelType);
        } catch (RuntimeException e) {
            return HttpDispatchClassifier.classifyRuntime(e, timeout, channelType);
        }
    }
}
