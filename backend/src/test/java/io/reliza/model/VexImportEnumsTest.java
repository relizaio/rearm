/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.TableName;

class VexImportEnumsTest {

    @Test
    void proposalStatusValues() {
        assertNotNull(ProposalStatus.PENDING);
        assertNotNull(ProposalStatus.ACCEPTED);
        assertNotNull(ProposalStatus.REJECTED);
        assertNotNull(ProposalStatus.SUPERSEDED);
        assertNotNull(ProposalStatus.ERRORED);
        assertEquals(5, ProposalStatus.values().length);
    }

    @Test
    void signatureStatusValues() {
        assertNotNull(SignatureStatus.UNSIGNED);
        assertNotNull(SignatureStatus.VERIFIED_TRUSTED);
        assertNotNull(SignatureStatus.VERIFIED_UNTRUSTED);
        assertNotNull(SignatureStatus.INVALID);
        assertEquals(4, SignatureStatus.values().length);
    }

    @Test
    void sourceFormatValues() {
        assertNotNull(SourceFormat.OPENVEX);
        assertNotNull(SourceFormat.CDX_VEX);
        assertEquals(2, SourceFormat.values().length);
    }

    @Test
    void tableNameRegistered() {
        assertNotNull(TableName.VEX_STATEMENT_PROPOSAL);
        assertEquals("vex_statement_proposals", TableName.VEX_STATEMENT_PROPOSAL.toString());
    }
}
