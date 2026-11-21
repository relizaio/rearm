/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.IssuerClass;
import io.reliza.model.ProposalStatus;
import io.reliza.model.SafetyContext;
import io.reliza.model.SafetyContextProposal;
import io.reliza.model.SafetyContextRecordData;
import io.reliza.model.TrustAction;
import io.reliza.model.dto.SafetyContextAcceptanceResult;
import io.reliza.repositories.SafetyContextProposalRepository;
import io.reliza.repositories.SafetyContextRepository;

/**
 * Applies import trust policy to staged records and moves only an eligible,
 * uniquely matched proposal into the accepted safety-context table.
 *
 * <p>The accepted record preserves assertionStatus and source JSON verbatim.
 * ReARM's ACCEPTED state and acceptedVia identify ingestion provenance, not
 * approval of a safety decision.
 */
@Service
public class SafetyContextAcceptanceService {
    private final SafetyContextProposalRepository proposalRepository;
    private final SafetyContextRepository safetyContextRepository;

    public SafetyContextAcceptanceService(
            SafetyContextProposalRepository proposalRepository,
            SafetyContextRepository safetyContextRepository) {
        this.proposalRepository = proposalRepository;
        this.safetyContextRepository = safetyContextRepository;
    }

    @Transactional
    public SafetyContextAcceptanceResult applyTrustGate(
            UUID proposalUuid, TrustAction requestedAction, IssuerClass issuerClass, UUID actorUuid)
            throws RelizaException {
        SafetyContextProposal entity = proposalRepository.findByIdForUpdate(proposalUuid)
            .orElseThrow(() -> new RelizaException("Safety-context proposal not found: " + proposalUuid));
        SafetyContextRecordData data = SafetyContextRecordData.dataFromRecord(entity);

        if (data.getStatus() == ProposalStatus.ACCEPTED) {
            return result(TrustAction.AUTO_ACCEPT, data);
        }
        if (data.getStatus() != ProposalStatus.PENDING) {
            return result(TrustAction.STAGE, data);
        }

        TrustAction action = SafetyContextTrustGate.effectiveAction(requestedAction, issuerClass);
        if (TrustAction.AUTO_ACCEPT == action
                && (!"MATCHED".equals(data.getMatchStatus()) || data.getSbomComponentUuid() == null)) {
            action = TrustAction.STAGE;
            data.setStatusReason("Auto-accept requires a unique component match in the release SBOM");
        }

        if (TrustAction.REJECT == action) {
            data.setStatus(ProposalStatus.REJECTED);
            data.setStatusReason("Rejected by the import trust policy");
            entity.setRecordData(data.toRecordData());
            proposalRepository.save(entity);
            return result(action, data);
        }
        if (TrustAction.STAGE == action) {
            if (data.getStatusReason() == null) {
                data.setStatusReason(issuerClass == IssuerClass.SELF
                    ? "Staged for review"
                    : "Issuer is not trusted for automatic import");
            }
            entity.setRecordData(data.toRecordData());
            proposalRepository.save(entity);
            return result(action, data);
        }

        UUID acceptedUuid = UUID.randomUUID();
        ZonedDateTime now = ZonedDateTime.now();
        SafetyContextRecordData acceptedData =
            Utils.OM.convertValue(data.toRecordData(), SafetyContextRecordData.class);
        acceptedData.setUuid(acceptedUuid);
        acceptedData.setStatus(ProposalStatus.ACCEPTED);
        acceptedData.setAcceptedVia("AUTO_ACCEPT");
        acceptedData.setAcceptedAt(now);
        acceptedData.setAcceptedBy(actorUuid);
        acceptedData.setStatusReason(null);

        SafetyContext accepted = new SafetyContext();
        accepted.setUuid(acceptedUuid);
        accepted.setRecordData(acceptedData.toRecordData());
        safetyContextRepository.save(accepted);

        data.setStatus(ProposalStatus.ACCEPTED);
        data.setAcceptedVia("AUTO_ACCEPT");
        data.setAcceptedAt(now);
        data.setAcceptedBy(actorUuid);
        data.setAcceptedRecordUuid(acceptedUuid);
        data.setStatusReason(null);
        entity.setRecordData(data.toRecordData());
        proposalRepository.save(entity);
        return new SafetyContextAcceptanceResult(action, dto(data), acceptedUuid);
    }

    private SafetyContextAcceptanceResult result(TrustAction action, SafetyContextRecordData data) {
        return new SafetyContextAcceptanceResult(action, dto(data), data.getAcceptedRecordUuid());
    }

    private SafetyContextAcceptanceResult.SafetyContextRecordData dto(SafetyContextRecordData data) {
        return new SafetyContextAcceptanceResult.SafetyContextRecordData(
            data.getUuid(), data.getOrg(), data.getReleaseUuid(), data.getSbomComponentUuid(),
            data.getMatchStatus(), data.getStatus() == null ? null : data.getStatus().name(),
            data.getStatusReason(), data.getAcceptedVia(), data.getAcceptedRecordUuid());
    }
}
