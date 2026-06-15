/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

/**
 * Why this {@link NotificationDelivery} exists. Orthogonal to
 * {@link NotificationDeliveryStatus} — a row's lifecycle (PENDING → SENT
 * etc.) is independent of whether it came from a real producer or from
 * the synthetic-injection primitive (§7.11 of the design doc).
 *
 * <p>Enum-typed rather than a boolean per the coding_principles.md
 * guidance — if we add REPLAY (from history) or DRY_RUN (preview-mode)
 * later, the domain extends cleanly without a boolean fork rewrite.
 */
public enum NotificationDeliveryOrigin {
	/** Produced by a real event emitter (vuln upsert, VEX change, etc.). */
	REAL,
	/** Produced by the synthetic-injection primitive — channel test, Quick Start verify, integration test harness. */
	SYNTHETIC;

	/** String constants for {@code @Query} annotations, mirroring {@link NotificationDeliveryStatus}'s pattern. */
	public static final String REAL_VALUE = "REAL";
	public static final String SYNTHETIC_VALUE = "SYNTHETIC";
}
