/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;

import io.reliza.model.AnalysisScope;
import io.reliza.model.AnalysisState;
import io.reliza.model.IssuerClass;
import io.reliza.model.TrustAction;
import io.reliza.model.VexImportMode;
import io.reliza.model.VulnAnalysisData;

class VexVerdictResolverTest {

    private final VexVerdictResolver r = new VexVerdictResolver();

    @Test
    void selfAutoAcceptPolicyAndAutoAcceptUserStaysAuto() {
        var v = r.resolve(IssuerClass.SELF, AnalysisState.NOT_AFFECTED, VexImportMode.AUTO_ACCEPT);
        assertEquals(TrustAction.AUTO_ACCEPT, v.action());
        assertNull(v.demotionReason());
    }

    @Test
    void selfAutoAcceptPolicyDemotedByUserStage() {
        var v = r.resolve(IssuerClass.SELF, AnalysisState.NOT_AFFECTED, VexImportMode.STAGE);
        assertEquals(TrustAction.STAGE, v.action());
    }

    @Test
    void thirdPartyStagePolicyStaysStageRegardlessOfUser() {
        assertEquals(TrustAction.STAGE,
            r.resolve(IssuerClass.THIRD_PARTY, AnalysisState.EXPLOITABLE, VexImportMode.AUTO_ACCEPT).action());
        assertEquals(TrustAction.STAGE,
            r.resolve(IssuerClass.THIRD_PARTY, AnalysisState.NOT_AFFECTED, VexImportMode.AUTO_ACCEPT).action());
    }

    @Test
    void userRejectIsSticky() {
        // Even SELF + EXPLOITABLE (auto-accept by policy) reduces to REJECT when user picks REJECT.
        assertEquals(TrustAction.REJECT,
            r.resolve(IssuerClass.SELF, AnalysisState.EXPLOITABLE, VexImportMode.REJECT).action());
    }

    @Test
    void nullUserModeDefaultsToAutoAccept() {
        // Legacy callers don't carry a user mode; treat as full-permission default.
        assertEquals(TrustAction.AUTO_ACCEPT,
            r.resolve(IssuerClass.SELF, AnalysisState.EXPLOITABLE, null).action());
        assertEquals(TrustAction.STAGE,
            r.resolve(IssuerClass.VENDOR, AnalysisState.NOT_AFFECTED, null).action());
    }

    @Test
    void vendorLoudAutoQuietStaged() {
        // Vendor + EXPLOITABLE → AUTO; vendor + NOT_AFFECTED → STAGE (regardless of user picking AUTO).
        assertEquals(TrustAction.AUTO_ACCEPT,
            r.resolve(IssuerClass.VENDOR, AnalysisState.EXPLOITABLE, VexImportMode.AUTO_ACCEPT).action());
        assertEquals(TrustAction.STAGE,
            r.resolve(IssuerClass.VENDOR, AnalysisState.NOT_AFFECTED, VexImportMode.AUTO_ACCEPT).action());
    }

    @Test
    void leastPermissiveOrderingHoldsForAllCombinations() {
        // policy=AUTO, user=AUTO → AUTO
        assertEquals(TrustAction.AUTO_ACCEPT,
            r.resolve(IssuerClass.SELF, AnalysisState.EXPLOITABLE, VexImportMode.AUTO_ACCEPT).action());
        // policy=AUTO, user=STAGE → STAGE
        assertEquals(TrustAction.STAGE,
            r.resolve(IssuerClass.SELF, AnalysisState.EXPLOITABLE, VexImportMode.STAGE).action());
        // policy=STAGE, user=AUTO → STAGE  (user can't widen past policy)
        assertEquals(TrustAction.STAGE,
            r.resolve(IssuerClass.THIRD_PARTY, AnalysisState.EXPLOITABLE, VexImportMode.AUTO_ACCEPT).action());
        // policy=STAGE, user=STAGE → STAGE
        assertEquals(TrustAction.STAGE,
            r.resolve(IssuerClass.THIRD_PARTY, AnalysisState.EXPLOITABLE, VexImportMode.STAGE).action());
        // policy=STAGE, user=REJECT → REJECT
        assertEquals(TrustAction.REJECT,
            r.resolve(IssuerClass.THIRD_PARTY, AnalysisState.EXPLOITABLE, VexImportMode.REJECT).action());
        // policy=AUTO, user=REJECT → REJECT
        assertEquals(TrustAction.REJECT,
            r.resolve(IssuerClass.SELF, AnalysisState.EXPLOITABLE, VexImportMode.REJECT).action());
    }

    @Test
    void conflictGuardDemotesAutoToStageWhenBroaderRowDiffersInSuppression() {
        // ORG row says NOT_AFFECTED (suppressing); incoming RELEASE proposal says EXPLOITABLE (loud).
        VulnAnalysisData broader = analysis(AnalysisScope.ORG, AnalysisState.NOT_AFFECTED);
        var v = r.resolveWithConflictCheck(IssuerClass.SELF, AnalysisState.EXPLOITABLE,
            VexImportMode.AUTO_ACCEPT, AnalysisScope.RELEASE, List.of(broader));
        assertEquals(TrustAction.STAGE, v.action());
        assertEquals(VexVerdictResolver.DEMOTION_REASON_BROADER_SCOPE, v.demotionReason());
    }

    @Test
    void conflictGuardDoesNotDemoteWhenBroaderRowSharesSuppressionClass() {
        // Both suppressing (NOT_AFFECTED ↔ FALSE_POSITIVE): no metric impact, no demotion.
        VulnAnalysisData broader = analysis(AnalysisScope.ORG, AnalysisState.NOT_AFFECTED);
        var v = r.resolveWithConflictCheck(IssuerClass.SELF, AnalysisState.FALSE_POSITIVE,
            VexImportMode.AUTO_ACCEPT, AnalysisScope.RELEASE, List.of(broader));
        assertEquals(TrustAction.AUTO_ACCEPT, v.action());
        assertNull(v.demotionReason());
    }

    @Test
    void conflictGuardIgnoresNarrowerRows() {
        // Existing RELEASE row, incoming COMPONENT proposal — RELEASE is NOT broader than COMPONENT.
        VulnAnalysisData narrower = analysis(AnalysisScope.RELEASE, AnalysisState.NOT_AFFECTED);
        var v = r.resolveWithConflictCheck(IssuerClass.SELF, AnalysisState.EXPLOITABLE,
            VexImportMode.AUTO_ACCEPT, AnalysisScope.COMPONENT, List.of(narrower));
        assertEquals(TrustAction.AUTO_ACCEPT, v.action());
    }

    @Test
    void conflictGuardOnlyAppliesWhenInitialIsAutoAccept() {
        // Already STAGE (e.g., user picked STAGE) — no demotion, no reason needed.
        VulnAnalysisData broader = analysis(AnalysisScope.ORG, AnalysisState.NOT_AFFECTED);
        var v = r.resolveWithConflictCheck(IssuerClass.SELF, AnalysisState.EXPLOITABLE,
            VexImportMode.STAGE, AnalysisScope.RELEASE, List.of(broader));
        assertEquals(TrustAction.STAGE, v.action());
        assertNull(v.demotionReason());
    }

    @Test
    void conflictGuardEmptyExistingRowsLetsAutoThrough() {
        var v = r.resolveWithConflictCheck(IssuerClass.SELF, AnalysisState.EXPLOITABLE,
            VexImportMode.AUTO_ACCEPT, AnalysisScope.RELEASE, List.of());
        assertEquals(TrustAction.AUTO_ACCEPT, v.action());
    }

    @Test
    void conflictGuardPicksMostSpecificBroaderRow() {
        // Both COMPONENT and ORG have rows; COMPONENT is "more specific broader" relative to RELEASE.
        // Either triggers demotion; we just verify demotion fires.
        VulnAnalysisData orgRow = analysis(AnalysisScope.ORG, AnalysisState.NOT_AFFECTED);
        VulnAnalysisData componentRow = analysis(AnalysisScope.COMPONENT, AnalysisState.NOT_AFFECTED);
        var v = r.resolveWithConflictCheck(IssuerClass.SELF, AnalysisState.EXPLOITABLE,
            VexImportMode.AUTO_ACCEPT, AnalysisScope.RELEASE, List.of(orgRow, componentRow));
        assertEquals(TrustAction.STAGE, v.action());
        assertNotNull(v.demotionReason());
    }

    private VulnAnalysisData analysis(AnalysisScope scope, AnalysisState state) {
        VulnAnalysisData d = mock(VulnAnalysisData.class);
        when(d.getScope()).thenReturn(scope);
        when(d.getAnalysisState()).thenReturn(state);
        return d;
    }
}
