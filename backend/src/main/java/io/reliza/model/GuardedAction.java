/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * An action a guard can withhold.
 *
 * <p>The trigger engine answers "when should something happen"; a guard answers "may this happen
 * at all". The two are different questions about the same events, which is why this enum exists
 * rather than a second trigger type: a guard never causes anything, it only refuses.
 *
 * <p>One member today. New actions are added here and contribute their own variables to the
 * expression context, so the storage, resolution, modes and audit stay shared.
 */
public enum GuardedAction {
	/** Moving a release forward through its lifecycle, however the move was requested. */
	RELEASE_PROMOTION
}
