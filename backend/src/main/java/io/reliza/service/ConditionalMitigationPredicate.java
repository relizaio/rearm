/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisResponse;
import io.reliza.model.AnalysisState;
import io.reliza.model.ClaimType;

/**
 * The Option-B conditional-mitigation trigger predicate.
 * See ai-plans/vex_imports/06_conditional_mitigation_workflow.md §"Scope — DECISION".
 *
 * Returns true when a VEX claim's effectiveness depends on consumer-side state
 * (a workaround being applied, or an environment-conditional control being in place).
 * When true, the import path must create a MitigationAttestation and defer the
 * VulnAnalysis mutation until attestation completes.
 */
@Service
public class ConditionalMitigationPredicate {

    private static final Set<AnalysisJustification> ENVIRONMENTAL = Set.of(
        AnalysisJustification.PROTECTED_BY_COMPILER,
        AnalysisJustification.PROTECTED_AT_RUNTIME,
        AnalysisJustification.PROTECTED_AT_PERIMETER,
        AnalysisJustification.PROTECTED_BY_MITIGATING_CONTROL,
        AnalysisJustification.REQUIRES_CONFIGURATION,
        AnalysisJustification.REQUIRES_ENVIRONMENT
    );

    public boolean needsAttestation(AnalysisState state, AnalysisJustification just, String workaround, List<AnalysisResponse> responses) {
        if (state == null) return false;
        return switch (state) {
            case EXPLOITABLE -> {
                boolean hasWorkaroundText = workaround != null && !workaround.isBlank();
                boolean hasWorkaroundResponse = responses != null && responses.contains(AnalysisResponse.WORKAROUND_AVAILABLE);
                yield hasWorkaroundText || hasWorkaroundResponse;
            }
            case NOT_AFFECTED -> just != null && ENVIRONMENTAL.contains(just);
            default -> false;
        };
    }

    /**
     * Classify which side of the conditional-mitigation predicate triggered the attestation.
     * Caller should only invoke this when {@link #needsAttestation} returned true.
     */
    public ClaimType classify(AnalysisState state, AnalysisJustification justification, String workaround) {
        if (workaround != null && !workaround.isBlank()) return ClaimType.WORKAROUND;
        if (justification != null && ENVIRONMENTAL.contains(justification)) return ClaimType.ENVIRONMENTAL_CONTROL;
        // EXPLOITABLE + WORKAROUND_AVAILABLE response (no workaround text) — still a workaround claim.
        return ClaimType.WORKAROUND;
    }
}
