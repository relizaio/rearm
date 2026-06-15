/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

/**
 * Lifecycle status of a {@link NotificationDelivery}.
 *
 * <p>String constants ({@code *_VALUE}) are exposed alongside the enum so they
 * can be referenced in {@code @Query} annotations and persisted in JSONB
 * stores while staying in sync with the enum domain — per the
 * "Constants live next to the enum" guidance in
 * {@code ai-plans/../coding_principles.md}.
 */
public enum NotificationDeliveryStatus {
	/** Ready for the channel worker to attempt delivery (initial state, or back here after a retriable failure). */
	PENDING,
	/** Channel transport reported success. */
	SENT,
	/** User has acknowledged the delivery in the inbox (v2). */
	ACKED,
	/** Max retries exhausted, or a non-retriable error. Terminal. */
	FAILED,
	/** Rate limit hit at fan-out time — delivery row persisted for history but never dispatched. */
	RATE_LIMITED,
	/** CEL evaluation aborted on the wall-clock budget; subscription circuit-breaker may auto-disable. */
	EVAL_TIMEOUT,
	/** Customer-initiated channel test from the "send test event" UI button. */
	TEST,
	/** Subscription is in PREVIEW mode; render captured for history, never dispatched. */
	PREVIEW,
	/** Held in an EMAIL channel's rolling-cap digest window; flushed as one digest email when the window expires. */
	BATCHED;

	public static final String PENDING_VALUE = "PENDING";
	public static final String SENT_VALUE = "SENT";
	public static final String ACKED_VALUE = "ACKED";
	public static final String FAILED_VALUE = "FAILED";
	public static final String RATE_LIMITED_VALUE = "RATE_LIMITED";
	public static final String EVAL_TIMEOUT_VALUE = "EVAL_TIMEOUT";
	public static final String TEST_VALUE = "TEST";
	public static final String PREVIEW_VALUE = "PREVIEW";
	public static final String BATCHED_VALUE = "BATCHED";
}
