/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.MitigationAttestationData;
import io.reliza.model.WhoUpdated;
import lombok.extern.slf4j.Slf4j;

/**
 * Bridges the attestation lifecycle to the VEX proposal lifecycle: when an attestation flips
 * to ATTESTED, the deferred VulnAnalysis write fires on the originating proposal.
 *
 * Owning this here (rather than as a method on VexImportService) keeps the import-side
 * cluster narrowly about importing, and lets the DataFetcher call a single bridge method
 * without needing to know the proposal-applies-to-vuln-analysis details.
 */
@Slf4j
@Service
public class VexAttestationBridge {

    @Autowired MitigationAttestationService attestationService;
    @Autowired VexStatementProposalService proposalService;

    @Transactional
    public MitigationAttestationData attestAndPropagate(UUID attestationUuid, String evidence, WhoUpdated wu) throws RelizaException {
        MitigationAttestationData attested = attestationService.attest(attestationUuid, evidence, wu);
        proposalService.applyAttestedVulnAnalysis(attested.getProposal(), wu);
        return attested;
    }
}
