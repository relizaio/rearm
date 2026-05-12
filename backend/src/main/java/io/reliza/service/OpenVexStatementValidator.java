/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.Set;
import org.springframework.stereotype.Service;

import io.reliza.model.dto.OpenVexStatement;
import io.reliza.model.dto.OpenVexValidationResult;

/**
 * Encodes the status-conditional invariants from go-vex's Statement.Validate().
 * Shared by import (intake) and export (OpenVexService strictness check).
 * See ai-plans/vex_imports/07_govex_validator_audit.md.
 */
@Service
public class OpenVexStatementValidator {

    private static final Set<String> STATUSES = Set.of("not_affected", "affected", "fixed", "under_investigation");

    private static final Set<String> JUSTIFICATIONS = Set.of(
        "component_not_present",
        "vulnerable_code_not_present",
        "vulnerable_code_not_in_execute_path",
        "vulnerable_code_cannot_be_controlled_by_adversary",
        "inline_mitigations_already_exist"
    );

    public OpenVexValidationResult validate(OpenVexStatement s) {
        String status = s.status();
        if (status == null || !STATUSES.contains(status)) {
            return OpenVexValidationResult.fail("status must be one of " + STATUSES + " (was: " + status + ")");
        }

        boolean hasJust   = s.justification() != null && !s.justification().isBlank();
        boolean hasImpact = s.impactStatement() != null && !s.impactStatement().isBlank();
        boolean hasAction = s.actionStatement() != null && !s.actionStatement().isBlank();

        if (hasJust && !JUSTIFICATIONS.contains(s.justification())) {
            return OpenVexValidationResult.fail("justification not a known enum: " + s.justification());
        }

        return switch (status) {
            case "not_affected" -> {
                if (!hasJust && !hasImpact) yield OpenVexValidationResult.fail("not_affected requires justification or impact_statement");
                if (hasAction) yield OpenVexValidationResult.fail("not_affected forbids action_statement");
                yield OpenVexValidationResult.ok();
            }
            case "affected" -> {
                if (!hasAction) yield OpenVexValidationResult.fail("affected requires action_statement");
                if (hasJust) yield OpenVexValidationResult.fail("affected forbids justification");
                if (hasImpact) yield OpenVexValidationResult.fail("affected forbids impact_statement");
                yield OpenVexValidationResult.ok();
            }
            case "fixed", "under_investigation" -> {
                if (hasJust) yield OpenVexValidationResult.fail(status + " forbids justification");
                if (hasImpact) yield OpenVexValidationResult.fail(status + " forbids impact_statement");
                if (hasAction) yield OpenVexValidationResult.fail(status + " forbids action_statement");
                yield OpenVexValidationResult.ok();
            }
            default -> OpenVexValidationResult.fail("unreachable");
        };
    }
}
