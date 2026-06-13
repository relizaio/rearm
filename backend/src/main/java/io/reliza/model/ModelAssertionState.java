/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * How much trust the recorded session model carries. The model on an
 * {@link AgentSessionData} is only ever as good as its source, and the UI
 * must not imply more authority than the source earned.
 *
 * <ul>
 *   <li>{@code DECLARED} — the agent self-reported the model as a string
 *       via the CLI ({@code session init}/{@code touch}). ReARM trusts the
 *       agent's word; it is not independently verified.</li>
 *   <li>{@code RUNTIME_OBSERVED} — the model was read from the runtime's
 *       own record (e.g. a host-side hook reading the Claude Code
 *       transcript's {@code message.model}), not from the agent's
 *       assertion. Reserved for the Path-B integration; not yet emitted.</li>
 * </ul>
 */
public enum ModelAssertionState {
	DECLARED,
	RUNTIME_OBSERVED
}
