/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

/**
 * Outcome of a single channel-dispatch attempt. Returned by every
 * channel-specific dispatcher (Slack, Teams, Email, Webhook, Sentinel
 * — Slack is the only one in v1) and consumed by
 * {@link NotificationDeliveryWorker} to decide:
 *   - mark the delivery SENT (success)
 *   - bump attempt_count and schedule a retry via BackoffPolicy
 *     (retriable failure)
 *   - mark the delivery FAILED (non-retriable failure)
 *
 * <p>The {@code retryAfterSeconds} field carries an upstream
 * {@code Retry-After} header value when the destination sent one
 * (typically on HTTP 429). When set, the worker uses it instead of
 * the exponential BackoffPolicy curve for the next attempt's timing.
 */
public record ChannelDispatchResult(
        Outcome outcome,
        String errorMessage,
        Integer retryAfterSeconds) {

    public enum Outcome {
        /** Delivery succeeded; mark SENT. */
        SUCCESS,
        /** Transient failure; bump attempt_count and back off. */
        RETRIABLE_FAILURE,
        /** Permanent failure; mark FAILED. */
        NON_RETRIABLE_FAILURE;
    }

    public static ChannelDispatchResult success() {
        return new ChannelDispatchResult(Outcome.SUCCESS, null, null);
    }

    public static ChannelDispatchResult retriable(String errorMessage) {
        return new ChannelDispatchResult(Outcome.RETRIABLE_FAILURE, errorMessage, null);
    }

    public static ChannelDispatchResult retriableAfter(String errorMessage, int retryAfterSeconds) {
        return new ChannelDispatchResult(Outcome.RETRIABLE_FAILURE, errorMessage, retryAfterSeconds);
    }

    public static ChannelDispatchResult nonRetriable(String errorMessage) {
        return new ChannelDispatchResult(Outcome.NON_RETRIABLE_FAILURE, errorMessage, null);
    }
}
