/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class VexTrustGateTest {

    @Test
    void selfAlwaysAutoAccepts() {
        assertEquals(TrustAction.AUTO_ACCEPT, VexTrustGate.defaultAction(IssuerClass.SELF, AnalysisState.EXPLOITABLE));
        assertEquals(TrustAction.AUTO_ACCEPT, VexTrustGate.defaultAction(IssuerClass.SELF, AnalysisState.IN_TRIAGE));
        assertEquals(TrustAction.AUTO_ACCEPT, VexTrustGate.defaultAction(IssuerClass.SELF, AnalysisState.NOT_AFFECTED));
        assertEquals(TrustAction.AUTO_ACCEPT, VexTrustGate.defaultAction(IssuerClass.SELF, AnalysisState.FALSE_POSITIVE));
        assertEquals(TrustAction.AUTO_ACCEPT, VexTrustGate.defaultAction(IssuerClass.SELF, AnalysisState.RESOLVED));
    }

    @Test
    void vendorAutoOnLoudStageOnQuiet() {
        assertEquals(TrustAction.AUTO_ACCEPT, VexTrustGate.defaultAction(IssuerClass.VENDOR, AnalysisState.EXPLOITABLE));
        assertEquals(TrustAction.AUTO_ACCEPT, VexTrustGate.defaultAction(IssuerClass.VENDOR, AnalysisState.IN_TRIAGE));
        assertEquals(TrustAction.STAGE,       VexTrustGate.defaultAction(IssuerClass.VENDOR, AnalysisState.NOT_AFFECTED));
        assertEquals(TrustAction.STAGE,       VexTrustGate.defaultAction(IssuerClass.VENDOR, AnalysisState.FALSE_POSITIVE));
        assertEquals(TrustAction.STAGE,       VexTrustGate.defaultAction(IssuerClass.VENDOR, AnalysisState.RESOLVED));
    }

    @Test
    void thirdPartyAlwaysStages() {
        assertEquals(TrustAction.STAGE, VexTrustGate.defaultAction(IssuerClass.THIRD_PARTY, AnalysisState.EXPLOITABLE));
        assertEquals(TrustAction.STAGE, VexTrustGate.defaultAction(IssuerClass.THIRD_PARTY, AnalysisState.IN_TRIAGE));
        assertEquals(TrustAction.STAGE, VexTrustGate.defaultAction(IssuerClass.THIRD_PARTY, AnalysisState.NOT_AFFECTED));
        assertEquals(TrustAction.STAGE, VexTrustGate.defaultAction(IssuerClass.THIRD_PARTY, AnalysisState.FALSE_POSITIVE));
        assertEquals(TrustAction.STAGE, VexTrustGate.defaultAction(IssuerClass.THIRD_PARTY, AnalysisState.RESOLVED));
    }

    @Test
    void nullIssuerClassMatchesVendorTreatment() {
        // Legacy callers that didn't carry a binding land here.
        assertEquals(TrustAction.AUTO_ACCEPT, VexTrustGate.defaultAction(null, AnalysisState.EXPLOITABLE));
        assertEquals(TrustAction.AUTO_ACCEPT, VexTrustGate.defaultAction(null, AnalysisState.IN_TRIAGE));
        assertEquals(TrustAction.STAGE,       VexTrustGate.defaultAction(null, AnalysisState.NOT_AFFECTED));
    }

    @Test
    void nullStateAlwaysStages() {
        // Defensive: a parser that produces a statement without a state shouldn't auto-write.
        assertEquals(TrustAction.STAGE, VexTrustGate.defaultAction(IssuerClass.SELF, null));
        assertEquals(TrustAction.STAGE, VexTrustGate.defaultAction(null, null));
    }
}
