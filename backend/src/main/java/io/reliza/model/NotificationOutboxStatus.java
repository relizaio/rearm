/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

/**
 * Lifecycle status of a {@link NotificationOutboxEvent}.
 *
 * <p>String constants ({@code *_VALUE}) are exposed alongside the enum so they
 * can be referenced in {@code @Query} annotations and persisted in JSONB
 * stores while staying in sync with the enum domain — per the
 * "Constants live next to the enum" guidance in
 * {@code ai-plans/../coding_principles.md}.
 */
public enum NotificationOutboxStatus {
	/** Newly inserted; waiting for the outbox worker to fan it out. */
	PENDING,
	/** Worker has produced the per-channel delivery rows; terminal for the outbox row. */
	FANNED_OUT,
	/** Fan-out raised an unrecoverable error; terminal. Operator action required. */
	FAILED;

	public static final String PENDING_VALUE = "PENDING";
	public static final String FANNED_OUT_VALUE = "FANNED_OUT";
	public static final String FAILED_VALUE = "FAILED";
}
