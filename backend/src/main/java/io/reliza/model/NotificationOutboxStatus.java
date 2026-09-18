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
	FAILED,
	/**
	 * Fan-out deliberately produced no deliveries because the event had
	 * nothing to say; terminal, and NOT an error. Today the only producer of
	 * this state is the vuln-event affected-release guard: a
	 * {@code NEW_VULN_AFFECTS_RELEASES} or {@code VULNERABILITY_RECORD_UPDATED}
	 * whose affected-release set was still empty after its deferred
	 * re-attempts is withheld rather than delivered, because a vulnerability
	 * notification that names no release is not actionable.
	 *
	 * <p>Deliberately distinct from {@link #FANNED_OUT}. Both can end with
	 * zero delivery rows, but they answer different questions:
	 * FANNED_OUT-with-no-deliveries means "offered, no subscription wanted
	 * it"; SUPPRESSED means "withheld on purpose". Collapsing them would make
	 * the withheld case unmeasurable -- and the count of suppressed events is
	 * exactly the evidence for whether the vuln emit should eventually move
	 * to the artifact-metrics write.
	 */
	SUPPRESSED;

	public static final String PENDING_VALUE = "PENDING";
	public static final String FANNED_OUT_VALUE = "FANNED_OUT";
	public static final String FAILED_VALUE = "FAILED";
	public static final String SUPPRESSED_VALUE = "SUPPRESSED";
}
