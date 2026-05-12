/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisResponse;
import io.reliza.model.AnalysisState;

class ConditionalMitigationPredicateTest {

    private final ConditionalMitigationPredicate p = new ConditionalMitigationPredicate();

    @Test
    void affectedWithWorkaroundFires() {
        assertTrue(p.needsAttestation(AnalysisState.EXPLOITABLE, null, "disable feature X", List.of()));
    }

    @Test
    void affectedWithWorkaroundResponseFires() {
        assertTrue(p.needsAttestation(AnalysisState.EXPLOITABLE, null, null, List.of(AnalysisResponse.WORKAROUND_AVAILABLE)));
    }

    @Test
    void affectedWithoutWorkaroundDoesNotFire() {
        assertFalse(p.needsAttestation(AnalysisState.EXPLOITABLE, null, null, List.of()));
        assertFalse(p.needsAttestation(AnalysisState.EXPLOITABLE, null, null, List.of(AnalysisResponse.UPDATE)));
    }

    @Test
    void notAffectedWithEnvironmentalJustificationFires() {
        assertTrue(p.needsAttestation(AnalysisState.NOT_AFFECTED, AnalysisJustification.PROTECTED_AT_PERIMETER, null, List.of()));
        assertTrue(p.needsAttestation(AnalysisState.NOT_AFFECTED, AnalysisJustification.PROTECTED_AT_RUNTIME, null, List.of()));
        assertTrue(p.needsAttestation(AnalysisState.NOT_AFFECTED, AnalysisJustification.PROTECTED_BY_COMPILER, null, List.of()));
        assertTrue(p.needsAttestation(AnalysisState.NOT_AFFECTED, AnalysisJustification.PROTECTED_BY_MITIGATING_CONTROL, null, List.of()));
        assertTrue(p.needsAttestation(AnalysisState.NOT_AFFECTED, AnalysisJustification.REQUIRES_CONFIGURATION, null, List.of()));
        assertTrue(p.needsAttestation(AnalysisState.NOT_AFFECTED, AnalysisJustification.REQUIRES_ENVIRONMENT, null, List.of()));
    }

    @Test
    void notAffectedWithSbomVerifiableJustificationDoesNotFire() {
        assertFalse(p.needsAttestation(AnalysisState.NOT_AFFECTED, AnalysisJustification.CODE_NOT_PRESENT, null, List.of()));
        assertFalse(p.needsAttestation(AnalysisState.NOT_AFFECTED, AnalysisJustification.CODE_NOT_REACHABLE, null, List.of()));
        assertFalse(p.needsAttestation(AnalysisState.NOT_AFFECTED, AnalysisJustification.REQUIRES_DEPENDENCY, null, List.of()));
    }

    @Test
    void resolvedAndFalsePositiveDoNotFire() {
        assertFalse(p.needsAttestation(AnalysisState.RESOLVED, null, null, List.of()));
        assertFalse(p.needsAttestation(AnalysisState.FALSE_POSITIVE, null, null, List.of()));
    }
}
