/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.model.MitigationAttestationData;
import io.reliza.model.WhoUpdated;

class VexAttestationBridgeTest {

    private MitigationAttestationService attestationService;
    private VexStatementProposalService proposalService;
    private VexAttestationBridge bridge;

    @BeforeEach
    void setUp() {
        attestationService = mock(MitigationAttestationService.class);
        proposalService = mock(VexStatementProposalService.class);
        bridge = new VexAttestationBridge();
        bridge.attestationService = attestationService;
        bridge.proposalService = proposalService;
    }

    @Test
    void attestThenPropagateToProposal() throws Exception {
        UUID attUuid = UUID.randomUUID();
        UUID proposalUuid = UUID.randomUUID();
        WhoUpdated wu = WhoUpdated.getTestWhoUpdated();

        MitigationAttestationData attested = new MitigationAttestationData();
        attested.setUuid(attUuid);
        attested.setProposal(proposalUuid);
        when(attestationService.attest(eq(attUuid), eq("evidence-text"), any(WhoUpdated.class)))
            .thenReturn(attested);

        MitigationAttestationData out = bridge.attestAndPropagate(attUuid, "evidence-text", wu);

        assertEquals(attUuid, out.getUuid());
        verify(attestationService).attest(eq(attUuid), eq("evidence-text"), any(WhoUpdated.class));
        verify(proposalService).applyAttestedVulnAnalysis(eq(proposalUuid), any(WhoUpdated.class));
    }
}
