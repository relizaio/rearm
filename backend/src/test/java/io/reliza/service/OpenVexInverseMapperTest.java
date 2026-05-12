/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisState;

class OpenVexInverseMapperTest {

    private final OpenVexInverseMapper m = new OpenVexInverseMapper();

    @Test
    void statusToState_defaults() {
        assertEquals(AnalysisState.NOT_AFFECTED, m.toState("not_affected"));
        assertEquals(AnalysisState.EXPLOITABLE, m.toState("affected"));
        assertEquals(AnalysisState.RESOLVED, m.toState("fixed"));
        assertEquals(AnalysisState.IN_TRIAGE, m.toState("under_investigation"));
    }

    @Test
    void statusToState_unknownReturnsNull() {
        assertNull(m.toState("garbage"));
        assertNull(m.toState(null));
    }

    @Test
    void justificationToCdx_oneToOne() {
        assertEquals(AnalysisJustification.CODE_NOT_PRESENT,        m.toJustification("vulnerable_code_not_present"));
        assertEquals(AnalysisJustification.CODE_NOT_REACHABLE,      m.toJustification("vulnerable_code_not_in_execute_path"));
        assertEquals(AnalysisJustification.REQUIRES_DEPENDENCY,     m.toJustification("component_not_present"));
    }

    @Test
    void justificationToCdx_ambiguousDefaults() {
        // Per 02_cdx_openvex_relationship.md: pick the most generic / broadest CDX target.
        assertEquals(AnalysisJustification.REQUIRES_ENVIRONMENT,
            m.toJustification("vulnerable_code_cannot_be_controlled_by_adversary"));
        assertEquals(AnalysisJustification.PROTECTED_BY_MITIGATING_CONTROL,
            m.toJustification("inline_mitigations_already_exist"));
    }

    @Test
    void justificationToCdx_nullPassThrough() {
        assertNull(m.toJustification(null));
        assertNull(m.toJustification("made_up"));
    }
}
