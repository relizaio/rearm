/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

/**
 * Lifecycle status of a {@link NotificationSubscription}.
 *
 * <p>PREVIEW mode (see §6.5 of the design doc) lets users see what a
 * subscription <em>would</em> have done over real events for some time
 * window before promoting it to ACTIVE.
 *
 * <p>String constants ({@code *_VALUE}) follow the "Constants live next to
 * the enum" convention from {@code coding_principles.md}.
 */
public enum NotificationSubscriptionStatus {
	ACTIVE,
	DISABLED,
	PREVIEW;

	public static final String ACTIVE_VALUE = "ACTIVE";
	public static final String DISABLED_VALUE = "DISABLED";
	public static final String PREVIEW_VALUE = "PREVIEW";
}
