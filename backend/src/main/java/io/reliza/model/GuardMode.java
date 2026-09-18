/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

/**
 * What happens when a guard's expression is not satisfied.
 *
 * <p>Separate from the expression so a team can adopt a rule in reporting mode, watch what it
 * would have caught, and promote it to a refusal once they trust it.
 */
public enum GuardMode {
	/** Configured but not evaluated -- a rule kept without being enforced. */
	OFF,
	/** The action proceeds; the failure is recorded against it. */
	WARN,
	/** The action is refused, naming the guard. */
	BLOCK
}
