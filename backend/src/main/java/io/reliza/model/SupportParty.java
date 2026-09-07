/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Whether the manufacturer is the first party for a component's support
 * facts (it is the org's own device/release) or a third party making a
 * claim about a component it has no operational control over (e.g. log4j).
 * Orthogonal to {@link SupportSource}: a supplier-provided BOM asserting a
 * date for the org's OWN in-house library is a first-party fact learned
 * through a supplier channel, not a third-party one.
 *
 * <p>Nullable on {@link SbomComponent} means UNKNOWN -- never infer an
 * implicit {@code THIRD_PARTY}. {@code isRoot} may prefill a UI suggestion
 * but never silently derives the stored fact (a non-root first-party
 * in-house library would be wrong under pure derivation).
 */
public enum SupportParty {
	FIRST_PARTY,
	THIRD_PARTY
}
