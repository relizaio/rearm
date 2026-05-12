/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

/**
 * Default trust verdict for an inbound VEX statement, keyed on (IssuerClass, AnalysisState).
 *
 * v1.x ships only the static defaults; configurable rules + signature-verified issuer
 * allowlists are deferred to v2 (alongside the admin UI).
 *
 * Truth table (mirrors ai-plans/vex_imports/08_trust_policy.md):
 *
 * <pre>
 *   issuerClass    | non-suppressing (EXPLOITABLE, IN_TRIAGE) | suppressing (NOT_AFFECTED, FALSE_POSITIVE, RESOLVED)
 *   ---------------+------------------------------------------+------------------------------------------------------
 *   SELF           | AUTO_ACCEPT                              | AUTO_ACCEPT
 *   VENDOR         | AUTO_ACCEPT                              | STAGE
 *   THIRD_PARTY    | STAGE                                    | STAGE
 *   null (unknown) | AUTO_ACCEPT                              | STAGE
 * </pre>
 *
 * Rationale: SELF is fully trusted (own assertions about own software). VENDOR's louder
 * states ("this is exploitable in our deliverable") auto-accept; their quieter states
 * ("this is not affected") get staged. THIRD_PARTY (no provenance binding) always stages.
 * Null (legacy callers) is treated as VENDOR.
 */
public final class VexTrustGate {

    private VexTrustGate() {}

    public static TrustAction defaultAction(IssuerClass issuerClass, AnalysisState state) {
        if (state == null) return TrustAction.STAGE;
        if (issuerClass == IssuerClass.SELF) return TrustAction.AUTO_ACCEPT;
        if (issuerClass == IssuerClass.THIRD_PARTY) return TrustAction.STAGE;
        return isSuppressing(state) ? TrustAction.STAGE : TrustAction.AUTO_ACCEPT;
    }

    private static boolean isSuppressing(AnalysisState state) {
        return state == AnalysisState.NOT_AFFECTED
            || state == AnalysisState.FALSE_POSITIVE
            || state == AnalysisState.RESOLVED;
    }
}
