/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model.dto;

import java.time.ZonedDateTime;
import java.util.UUID;

import io.reliza.model.AnalysisScope;
import io.reliza.model.AttestationStatus;
import io.reliza.model.ClaimType;
import io.reliza.model.MitigationAttestationData;
import lombok.Data;

@Data
public class MitigationAttestationWebDto {
    private UUID uuid;
    private UUID org;
    private UUID proposal;
    private ClaimType claimType;
    private String claimText;
    private AnalysisScope scope;
    private UUID scopeUuid;
    private UUID assignedTo;
    private ZonedDateTime assignedAt;
    private ZonedDateTime deadline;
    private AttestationStatus status;
    private String evidence;
    private ZonedDateTime attestedAt;
    private UUID attestedBy;
    private String statusReason;

    public static MitigationAttestationWebDto fromData(MitigationAttestationData d) {
        MitigationAttestationWebDto w = new MitigationAttestationWebDto();
        w.uuid = d.getUuid();
        w.org = d.getOrg();
        w.proposal = d.getProposal();
        w.claimType = d.getClaimType();
        w.claimText = d.getClaimText();
        w.scope = d.getScope();
        w.scopeUuid = d.getScopeUuid();
        w.assignedTo = d.getAssignedTo();
        w.assignedAt = d.getAssignedAt();
        w.deadline = d.getDeadline();
        w.status = d.getStatus();
        w.evidence = d.getEvidence();
        w.attestedAt = d.getAttestedAt();
        w.attestedBy = d.getAttestedBy();
        w.statusReason = d.getStatusReason();
        return w;
    }
}
