/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.TableName;

class MitigationAttestationEnumsTest {

    @Test
    void attestationStatusValues() {
        assertNotNull(AttestationStatus.PENDING);
        assertNotNull(AttestationStatus.ATTESTED);
        assertNotNull(AttestationStatus.WAIVED);
        assertNotNull(AttestationStatus.EXPIRED);
        assertEquals(4, AttestationStatus.values().length);
    }

    @Test
    void claimTypeValues() {
        assertNotNull(ClaimType.WORKAROUND);
        assertNotNull(ClaimType.ENVIRONMENTAL_CONTROL);
        assertEquals(2, ClaimType.values().length);
    }

    @Test
    void tableNameRegistered() {
        assertNotNull(TableName.MITIGATION_ATTESTATION);
        assertEquals("mitigation_attestations", TableName.MITIGATION_ATTESTATION.toString());
    }
}
