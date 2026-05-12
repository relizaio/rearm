/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import org.springframework.stereotype.Service;

import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisState;

/**
 * OpenVEX 0.2.0 → CDX vocabulary expansion with locked-in defaults.
 * See ai-plans/vex_imports/02_cdx_openvex_relationship.md.
 *
 * Defaults preserve maximum information: never infer a stronger claim than the producer made.
 */
@Service
public class OpenVexInverseMapper {

    public AnalysisState toState(String openVexStatus) {
        if (openVexStatus == null) return null;
        return switch (openVexStatus) {
            case "not_affected"       -> AnalysisState.NOT_AFFECTED;     // not FALSE_POSITIVE — that's a stronger claim than producer made
            case "affected"           -> AnalysisState.EXPLOITABLE;
            case "fixed"              -> AnalysisState.RESOLVED;          // ReARM has no RESOLVED_WITH_PEDIGREE — collapses to RESOLVED
            case "under_investigation"-> AnalysisState.IN_TRIAGE;
            default                   -> null;
        };
    }

    public AnalysisJustification toJustification(String openVexJustification) {
        if (openVexJustification == null) return null;
        return switch (openVexJustification) {
            case "component_not_present"                          -> AnalysisJustification.REQUIRES_DEPENDENCY;
            case "vulnerable_code_not_present"                    -> AnalysisJustification.CODE_NOT_PRESENT;
            case "vulnerable_code_not_in_execute_path"            -> AnalysisJustification.CODE_NOT_REACHABLE;
            case "vulnerable_code_cannot_be_controlled_by_adversary" -> AnalysisJustification.REQUIRES_ENVIRONMENT; // broader of {REQUIRES_ENVIRONMENT, REQUIRES_CONFIGURATION}
            case "inline_mitigations_already_exist"               -> AnalysisJustification.PROTECTED_BY_MITIGATING_CONTROL; // most generic of 4 PROTECTED_* targets
            default                                               -> null;
        };
    }
}
