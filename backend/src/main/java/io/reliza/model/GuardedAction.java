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
	RELEASE_PROMOTION(java.util.Set.of("targetLifecycle", "actor")),
	/**
	 * Recording approvals on a release, from a person or an API key (task 3204c981). The
	 * expression sees {@code action.approvals}, the entries being set in this call.
	 */
	RELEASE_APPROVAL(java.util.Set.of("approvals", "actor"));

	private final java.util.Set<String> actionKeys;

	GuardedAction(java.util.Set<String> actionKeys) {
		this.actionKeys = actionKeys;
	}

	/**
	 * The {@code action.*} keys a guard of this action is evaluated with. A guard reading another
	 * action's key fails closed on every call, so the key is refused when the guard is saved.
	 */
	public java.util.Set<String> actionKeys() {
		return actionKeys;
	}
}
