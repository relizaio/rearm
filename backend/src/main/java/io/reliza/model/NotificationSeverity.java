/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

/**
 * Severity classification carried on notification events. Customer CEL
 * filters reference this via {@code event.severity in ['CRITICAL','HIGH']},
 * and the routes table (§7.4 of the design doc) gates per-channel
 * delivery on a minimum-severity threshold.
 *
 * <p>For vuln-shaped events the severity comes from the canonical
 * "highest across sources wins" computation on {@code VulnerabilityRecord}
 * (§13.1 of the design doc). Other event types stamp severity at
 * emission time using their own domain semantics.
 */
public enum NotificationSeverity {
    CRITICAL(50),
    HIGH(40),
    MEDIUM(30),
    LOW(20),
    INFO(10),
    NONE(0);

    /**
     * Explicit ordering weight. Higher = more severe. We don't rely on
     * {@link #ordinal()} for comparisons because a future refactor that
     * reorders or inserts an enum constant would silently flip
     * {@link #atLeast(NotificationSeverity)}. Per coding_principles.md,
     * keep the meaning out of the declaration order.
     */
    private final int weight;

    NotificationSeverity(int weight) {
        this.weight = weight;
    }

    /**
     * Returns true iff this severity is at least as urgent as the threshold.
     * Used by the route-table gate ("deliver to channel X when severity ≥ HIGH").
     */
    public boolean atLeast(NotificationSeverity threshold) {
        return this.weight >= threshold.weight;
    }
}
