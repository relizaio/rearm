/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;

import io.reliza.model.AnalysisScope;
import io.reliza.model.AnalysisState;
import io.reliza.model.IssuerClass;
import io.reliza.model.TrustAction;
import io.reliza.model.VexImportMode;
import io.reliza.model.VexTrustGate;
import io.reliza.model.VulnAnalysisData;

/**
 * Resolves the per-statement verdict (AUTO_ACCEPT / STAGE / REJECT) by composing the
 * trust-gate default with the user's per-upload import mode and broader-scope conflict guard.
 *
 * Precedence chain (full design in ai-plans/vex_imports/09_scope_and_conflict.md §3.B):
 * <pre>
 *   1. policy   = VexTrustGate.defaultAction(issuerClass, state)
 *   2. user     = userImportMode  (defaults to AUTO_ACCEPT when null)
 *   3. combined = leastPermissive(policy, user)   // monotonic narrowing
 *   4. if combined == AUTO_ACCEPT and a strictly-broader-scope VulnAnalysis exists with
 *      different suppression class for (org, location, findingId, findingType):
 *           combined = STAGE
 *           demotionReason = BROADER_SCOPE_CONFLICT
 * </pre>
 *
 * The resolver is pure: existing-VulnAnalysis lookup happens in the caller and the
 * (possibly empty) candidate row + its scope is passed into {@link #resolveWithConflictCheck}.
 */
@Service
public class VexVerdictResolver {

    public static final String DEMOTION_REASON_BROADER_SCOPE = "BROADER_SCOPE_CONFLICT";
    public static final String DEMOTION_REASON_SEVERITY_MISSING = "SEVERITY_MISSING";

    /** Steps 1-3 of the precedence chain — gate × user-mode only, no conflict check. */
    public Verdict resolve(IssuerClass issuerClass, AnalysisState state, VexImportMode userMode) {
        TrustAction policy = VexTrustGate.defaultAction(issuerClass, state);
        TrustAction user = toAction(userMode);
        TrustAction combined = leastPermissive(policy, user);
        return new Verdict(combined, null);
    }

    /** Full v1.2 precedence chain including step 4 (broader-scope conflict guard). */
    public Verdict resolveWithConflictCheck(
            IssuerClass issuerClass, AnalysisState state, VexImportMode userMode,
            AnalysisScope candidateScope, List<VulnAnalysisData> existingRows) {
        Verdict initial = resolve(issuerClass, state, userMode);
        if (initial.action() != TrustAction.AUTO_ACCEPT) return initial;
        Optional<VulnAnalysisData> conflict = findBroaderConflict(existingRows, candidateScope, state);
        if (conflict.isPresent()) {
            return new Verdict(TrustAction.STAGE, DEMOTION_REASON_BROADER_SCOPE);
        }
        return initial;
    }

    /**
     * Among {@code existingRows}, find a row at strictly-broader scope than {@code candidateScope}
     * whose state is in a different suppression class than {@code candidateState}. Returns the
     * most-specific broader row (smallest scope-rank that's still broader), if any.
     */
    private Optional<VulnAnalysisData> findBroaderConflict(
            List<VulnAnalysisData> existingRows, AnalysisScope candidateScope, AnalysisState candidateState) {
        if (existingRows == null || existingRows.isEmpty()) return Optional.empty();
        int candidateRank = scopeRank(candidateScope);
        return existingRows.stream()
            .filter(r -> r.getScope() != null && scopeRank(r.getScope()) > candidateRank)
            .filter(r -> isSuppressing(r.getAnalysisState()) != isSuppressing(candidateState))
            .min((a, b) -> Integer.compare(scopeRank(a.getScope()), scopeRank(b.getScope())));
    }

    private TrustAction toAction(VexImportMode mode) {
        if (mode == null) return TrustAction.AUTO_ACCEPT;
        return switch (mode) {
            case AUTO_ACCEPT -> TrustAction.AUTO_ACCEPT;
            case STAGE -> TrustAction.STAGE;
            case REJECT -> TrustAction.REJECT;
        };
    }

    private TrustAction leastPermissive(TrustAction a, TrustAction b) {
        if (a == TrustAction.REJECT || b == TrustAction.REJECT) return TrustAction.REJECT;
        if (a == TrustAction.STAGE  || b == TrustAction.STAGE)  return TrustAction.STAGE;
        return TrustAction.AUTO_ACCEPT;
    }

    private static int scopeRank(AnalysisScope s) {
        if (s == null) return 0;
        return switch (s) {
            case RELEASE -> 1;
            case BRANCH -> 2;
            case COMPONENT -> 3;
            case ORG -> 4;
            case RESOURCE_GROUP -> 5;
        };
    }

    private static boolean isSuppressing(AnalysisState state) {
        return state == AnalysisState.NOT_AFFECTED
            || state == AnalysisState.FALSE_POSITIVE
            || state == AnalysisState.RESOLVED;
    }

    /** Verdict + audit-only demotion reason (null if combined matches policy). */
    public record Verdict(TrustAction action, String demotionReason) {}
}
