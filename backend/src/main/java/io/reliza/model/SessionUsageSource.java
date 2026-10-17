/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/

package io.reliza.model;

/**
 * Where a usage report came from, which is also how far it is to be trusted.
 *
 * <p>{@code SELF_REPORTED} is an agent stating its own numbers; nothing corroborates them.
 * {@code TRANSCRIPT} is parsed from the client's own record of the run. {@code OTEL} and
 * {@code PROVIDER} are reserved: a collector mapping OpenTelemetry metrics onto the report
 * mutation, and billing reconciliation from the provider, neither of which needs a schema change
 * when it arrives.
 *
 * <p>Only the last three upgrade a session's model assertion from DECLARED to RUNTIME_OBSERVED --
 * an agent's own claim about which model it ran is the declaration, not evidence about it.
 */
public enum SessionUsageSource {
	SELF_REPORTED,
	TRANSCRIPT,
	OTEL,
	PROVIDER
}
