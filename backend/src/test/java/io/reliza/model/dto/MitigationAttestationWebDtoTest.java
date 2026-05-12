/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.AnalysisScope;
import io.reliza.model.AttestationStatus;
import io.reliza.model.ClaimType;
import io.reliza.model.MitigationAttestationData;

class MitigationAttestationWebDtoTest {

    @Test
    void mapsCoreFields() {
        MitigationAttestationData d = MitigationAttestationData.createMitigationAttestationData(
            UUID.randomUUID(), UUID.randomUUID(), ClaimType.WORKAROUND, "configure mTLS",
            UUID.randomUUID(), AnalysisScope.RELEASE, UUID.randomUUID(), null);
        MitigationAttestationWebDto w = MitigationAttestationWebDto.fromData(d);
        assertEquals(d.getUuid(), w.getUuid());
        assertEquals(AttestationStatus.PENDING, w.getStatus());
        assertEquals(ClaimType.WORKAROUND, w.getClaimType());
        assertEquals("configure mTLS", w.getClaimText());
        assertNotNull(w.getAssignedAt());
    }
}
