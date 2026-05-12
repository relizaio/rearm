/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class MitigationAttestationDataTest {

    @Test
    void roundTripsThroughJsonb() {
        UUID proposal = UUID.randomUUID();
        UUID assignee = UUID.randomUUID();
        UUID release = UUID.randomUUID();
        UUID org = UUID.randomUUID();
        MitigationAttestationData d = MitigationAttestationData.createMitigationAttestationData(
            org, proposal, ClaimType.ENVIRONMENTAL_CONTROL,
            "deployment is behind mTLS-protected mesh",
            assignee, AnalysisScope.RELEASE, release, null);
        Map<String, Object> map = d.toRecordData();
        MitigationAttestationData restored = MitigationAttestationData.dataFromMap(map, d.getUuid());
        assertEquals(d.getUuid(), restored.getUuid());
        assertEquals(proposal, restored.getProposal());
        assertEquals(ClaimType.ENVIRONMENTAL_CONTROL, restored.getClaimType());
        assertEquals("deployment is behind mTLS-protected mesh", restored.getClaimText());
        assertEquals(assignee, restored.getAssignedTo());
        assertEquals(AttestationStatus.PENDING, restored.getStatus());
        assertNull(restored.getDeadline());
    }
}
