/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.AttestationStatus;
import io.reliza.model.MitigationAttestation;
import io.reliza.model.MitigationAttestationData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.MitigationAttestationRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class MitigationAttestationService {

    @Autowired AuditService auditService;

    // Proposal-side write on ATTESTED is invoked from VexImportService.onAttested(...) — see Task 20.
    // This service intentionally does not call back into VexStatementProposalService.

    private final MitigationAttestationRepository repository;

    MitigationAttestationService(MitigationAttestationRepository repository) {
        this.repository = repository;
    }

    public Optional<MitigationAttestationData> get(UUID uuid) {
        return repository.findById(uuid).map(MitigationAttestationData::dataFromRecord);
    }

    public List<MitigationAttestationData> listForOrgAndStatus(UUID org, AttestationStatus status) {
        return repository.findByOrgAndStatus(org.toString(), status.name()).stream()
            .map(MitigationAttestationData::dataFromRecord).collect(Collectors.toList());
    }

    public List<MitigationAttestationData> listForAssignee(UUID assignee, AttestationStatus status) {
        return repository.findByAssignee(assignee.toString(), status.name()).stream()
            .map(MitigationAttestationData::dataFromRecord).collect(Collectors.toList());
    }

    public Optional<MitigationAttestationData> findByProposal(UUID proposal) {
        return repository.findByProposal(proposal.toString()).map(MitigationAttestationData::dataFromRecord);
    }

    @Transactional
    public MitigationAttestationData create(MitigationAttestationData data, WhoUpdated wu) {
        MitigationAttestation e = new MitigationAttestation();
        e.setUuid(data.getUuid());
        return save(e, data.toRecordData(), wu);
    }

    @Transactional
    public MitigationAttestationData attest(UUID uuid, String evidence, WhoUpdated wu) throws RelizaException {
        Optional<MitigationAttestation> opt = repository.findByIdWriteLocked(uuid);
        if (opt.isEmpty()) throw new RelizaException("Attestation not found: " + uuid);
        MitigationAttestation e = opt.get();
        MitigationAttestationData d = MitigationAttestationData.dataFromRecord(e);
        if (d.getStatus() != AttestationStatus.PENDING) {
            throw new RelizaException("Attestation not in PENDING state (was: " + d.getStatus() + ")");
        }
        d.setStatus(AttestationStatus.ATTESTED);
        d.setEvidence(evidence);
        d.setAttestedAt(ZonedDateTime.now());
        d.setAttestedBy(wu != null ? wu.getLastUpdatedBy() : null);
        return save(e, d.toRecordData(), wu);
        // Caller (VexAttestationBridge.attestAndPropagate) is responsible for triggering the
        // deferred VulnAnalysis write on the linked proposal.
    }

    @Transactional
    public MitigationAttestationData waive(UUID uuid, String reason, WhoUpdated wu) throws RelizaException {
        Optional<MitigationAttestation> opt = repository.findByIdWriteLocked(uuid);
        if (opt.isEmpty()) throw new RelizaException("Attestation not found: " + uuid);
        MitigationAttestation e = opt.get();
        MitigationAttestationData d = MitigationAttestationData.dataFromRecord(e);
        if (d.getStatus() != AttestationStatus.PENDING) {
            throw new RelizaException("Attestation not in PENDING state (was: " + d.getStatus() + ")");
        }
        d.setStatus(AttestationStatus.WAIVED);
        d.setStatusReason(reason);
        d.setAttestedAt(ZonedDateTime.now());
        d.setAttestedBy(wu != null ? wu.getLastUpdatedBy() : null);
        return save(e, d.toRecordData(), wu);
    }

    private MitigationAttestationData save(MitigationAttestation e, Map<String, Object> recordData, WhoUpdated wu) {
        Optional<MitigationAttestation> existing = repository.findById(e.getUuid());
        if (existing.isPresent()) {
            auditService.createAndSaveAuditRecord(TableName.MITIGATION_ATTESTATION, e);
            e.setRevision(e.getRevision() + 1);
            e.setLastUpdatedDate(ZonedDateTime.now());
        }
        e.setRecordData(recordData);
        e = (MitigationAttestation) WhoUpdated.injectWhoUpdatedData(e, wu);
        return MitigationAttestationData.dataFromRecord(repository.save(e));
    }
}
