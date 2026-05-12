/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

import io.reliza.model.dto.OpenVexStatement;
import io.reliza.model.dto.OpenVexValidationResult;

class OpenVexStatementValidatorTest {

    private final OpenVexStatementValidator v = new OpenVexStatementValidator();

    private OpenVexStatement stmt(String status, String just, String impact, String action) {
        return new OpenVexStatement("CVE-2024-1", List.of("pkg:maven/x/y@1"), status, just, impact, action);
    }

    @Test
    void unknownStatusRejected() {
        assertFalse(v.validate(stmt("garbage", null, null, null)).valid());
    }

    @Test
    void notAffectedRequiresJustificationOrImpact() {
        assertFalse(v.validate(stmt("not_affected", null, null, null)).valid());
        assertTrue(v.validate(stmt("not_affected", "vulnerable_code_not_present", null, null)).valid());
        assertTrue(v.validate(stmt("not_affected", null, "explained", null)).valid());
    }

    @Test
    void notAffectedForbidsActionStatement() {
        assertFalse(v.validate(stmt("not_affected", "vulnerable_code_not_present", null, "do something")).valid());
    }

    @Test
    void affectedRequiresActionStatement() {
        assertFalse(v.validate(stmt("affected", null, null, null)).valid());
        assertTrue(v.validate(stmt("affected", null, null, "upgrade")).valid());
    }

    @Test
    void affectedForbidsJustificationAndImpact() {
        assertFalse(v.validate(stmt("affected", "vulnerable_code_not_present", null, "upgrade")).valid());
        assertFalse(v.validate(stmt("affected", null, "impact text", "upgrade")).valid());
    }

    @Test
    void fixedForbidsAllOptionalFields() {
        assertTrue(v.validate(stmt("fixed", null, null, null)).valid());
        assertFalse(v.validate(stmt("fixed", "vulnerable_code_not_present", null, null)).valid());
        assertFalse(v.validate(stmt("fixed", null, "impact", null)).valid());
        assertFalse(v.validate(stmt("fixed", null, null, "action")).valid());
    }

    @Test
    void underInvestigationForbidsAllOptionalFields() {
        assertTrue(v.validate(stmt("under_investigation", null, null, null)).valid());
        assertFalse(v.validate(stmt("under_investigation", "vulnerable_code_not_present", null, null)).valid());
    }

    @Test
    void unknownJustificationRejected() {
        assertFalse(v.validate(stmt("not_affected", "made_up_value", null, null)).valid());
    }
}
