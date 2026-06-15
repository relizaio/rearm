/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.time.Duration;

import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClientRequestException;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import io.reliza.model.IntegrationData.IntegrationType;
import lombok.extern.slf4j.Slf4j;

/**
 * Shared HTTP-response classification for outbound channel dispatchers.
 * Both {@link SlackChannelDispatcher} and {@link WebhookChannelDispatcher}
 * route {@code WebClient} exceptions through here — the response-code
 * matrix (5xx / 408 / 425 / 429 → retriable; other 4xx → non-retriable),
 * the {@code Retry-After} parser, and the block-timeout detection are
 * identical between them.
 *
 * <p>Extracted at the rule-of-three trigger when Phase 4 added the
 * webhook dispatcher; further channel types (MS Teams / Sentinel) will
 * route through here too.
 *
 * <p>Stateless static helpers — no DI, no Spring lifecycle. Outlier in
 * this package (siblings are {@code @Service}) but justified: every
 * caller is itself a {@code @Service}, the classifier holds no policy
 * variables, and tests can call methods directly without any framework
 * setup. Promote to {@code @Component} if per-org/per-channel policy
 * (e.g. configurable Retry-After cap) is ever needed.
 */
@Slf4j
public final class HttpDispatchClassifier {

    private HttpDispatchClassifier() {}

    /**
     * Operator-facing display label for a channel type — used as the
     * prefix on the error message that lands in the delivery row's
     * {@code error} column + operator log. Covers the five notification
     * destination {@link IntegrationType} values; the non-destination
     * CI types (DEPENDENCYTRACK / GITHUB / …) never reach a dispatcher
     * (the worker gate rejects them first), so they fall to the default
     * arm with a name-based prefix rather than a "null" one.
     */
    static String displayLabel(IntegrationType type) {
        return switch (type) {
            case SLACK -> "Slack";
            case MSTEAMS -> "MS Teams";
            case EMAIL -> "Email";
            case WEBHOOK -> "Webhook";
            case SENTINEL -> "Sentinel";
            default -> type != null ? type.name() : "Channel";
        };
    }

    /**
     * Map an HTTP response status from an upstream channel into a
     * delivery outcome. Same set as §5.6 of the design doc:
     * <ul>
     *   <li>408 (request timeout) — retriable</li>
     *   <li>425 (too early) — retriable</li>
     *   <li>429 (rate limit) — retriable, honour {@code Retry-After}</li>
     *   <li>5xx — retriable</li>
     *   <li>everything else — non-retriable</li>
     * </ul>
     *
     * <p>The body is truncated to keep delivery error columns under the
     * 1024-char cap.
     */
    public static ChannelDispatchResult classifyResponse(
            WebClientResponseException wcre, IntegrationType channelType) {
        HttpStatusCode status = wcre.getStatusCode();
        // Defense: redact `Bearer <token>` substrings from response bodies
        // before they land in the delivery error column (some misconfigured
        // receivers echo the Authorization header into 4xx bodies in dev mode).
        String safeBody = redactAuthMaterial(wcre.getResponseBodyAsString());
        String label = displayLabel(channelType);
        if (isRetriableStatus(status)) {
            Integer retryAfter = parseRetryAfterSeconds(wcre);
            String msg = label + " returned " + status.value() + ": "
                    + StringUtils.truncate(safeBody, 200);
            return retryAfter != null
                    ? ChannelDispatchResult.retriableAfter(msg, retryAfter)
                    : ChannelDispatchResult.retriable(msg);
        }
        return ChannelDispatchResult.nonRetriable(
                label + " returned non-retriable " + status.value() + ": "
                        + StringUtils.truncate(safeBody, 200));
    }

    /**
     * Network / connection / DNS / read-side I/O failure — always
     * retriable.
     */
    public static ChannelDispatchResult classifyTransport(
            WebClientRequestException e, IntegrationType channelType) {
        return ChannelDispatchResult.retriable(
                displayLabel(channelType) + " dispatch failed (transport): "
                        + e.getClass().getSimpleName() + ": "
                        + StringUtils.defaultString(e.getMessage()));
    }

    /**
     * Catch-all for any other {@code RuntimeException} the WebClient
     * call surfaces — splits two cases:
     * <ul>
     *   <li>{@code IllegalStateException("Timeout on blocking read…")}
     *       from {@code .block(Duration)} — retriable.</li>
     *   <li>Anything else (NPE, IllegalArgumentException from a malformed
     *       URL, etc.) — non-retriable. Config-side fault that retry
     *       won't recover.</li>
     * </ul>
     */
    public static ChannelDispatchResult classifyRuntime(
            RuntimeException e, Duration timeout, IntegrationType channelType) {
        String msg = StringUtils.defaultString(e.getMessage());
        // instanceof (not getSimpleName().equals(...)) so a Reactor
        // subclass of IllegalStateException still classifies as a
        // block-timeout when the message matches.
        boolean isBlockTimeout = e instanceof IllegalStateException
                && msg.toLowerCase().contains("timeout");
        String label = displayLabel(channelType);
        if (isBlockTimeout) {
            return ChannelDispatchResult.retriable(
                    label + " dispatch timed out after " + timeout.getSeconds()
                            + "s: " + msg);
        }
        return ChannelDispatchResult.nonRetriable(
                label + " dispatch failed (likely config): "
                        + e.getClass().getSimpleName() + ": " + msg);
    }

    /**
     * 408 / 425 / 429 / 5xx → retriable. Public so tests can pin the
     * status-code matrix directly.
     */
    public static boolean isRetriableStatus(HttpStatusCode status) {
        int code = status.value();
        if (code >= 500) return true;
        return code == 408 || code == 425 || code == 429;
    }

    /**
     * Parse {@code Retry-After} as a delta-seconds integer. Returns null
     * if the header is missing, blank, non-numeric, or zero/negative
     * (caller falls back to the {@code BackoffPolicy} curve when null).
     */
    public static Integer parseRetryAfterSeconds(WebClientResponseException wcre) {
        String headerValue = wcre.getHeaders().getFirst(HttpHeaders.RETRY_AFTER);
        if (StringUtils.isBlank(headerValue)) return null;
        try {
            int seconds = Integer.parseInt(headerValue.trim());
            return seconds > 0 ? seconds : null;
        } catch (NumberFormatException e) {
            log.debug("Channel returned non-numeric Retry-After header: {}", headerValue);
            return null;
        }
    }

    /**
     * Defense-in-depth: some misconfigured receivers echo request
     * headers (including {@code Authorization: Bearer ...}) into their
     * 4xx response body, especially in dev mode. Both dispatchers write
     * the response body into the delivery's error column AND the
     * operator log; an unredacted echo would bottle the token into
     * both. Pattern match is case-insensitive to handle mixed-case
     * server echoes.
     */
    static String redactAuthMaterial(String responseBody) {
        if (responseBody == null) return null;
        // Match "Bearer <non-space chars>" — RFC 6750 bearer tokens
        // are non-whitespace ASCII.
        return responseBody.replaceAll("(?i)Bearer\\s+\\S+", "Bearer <redacted>");
    }
}
