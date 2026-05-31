/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.ArtifactData;
import io.reliza.model.ClaimType;
import io.reliza.model.DeliverableData;
import io.reliza.model.MitigationAttestationData;
import io.reliza.model.ProposalStatus;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceCodeEntryData;
import io.reliza.model.SourceCodeEntryData.SCEArtifact;
import io.reliza.model.VariantData;
import io.reliza.model.VexStatementProposal;
import io.reliza.model.VexStatementProposalData;
import io.reliza.model.VulnAnalysisData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.CreateVulnAnalysisDto;
import io.reliza.repositories.VexStatementProposalRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class VexStatementProposalService {

    @Autowired AuditService auditService;
    @Autowired VulnAnalysisService vulnAnalysisService;
    @Autowired ConditionalMitigationPredicate conditionalPredicate;
    @Autowired MitigationAttestationService attestationService;
    @Autowired SharedReleaseService sharedReleaseService;
    @Autowired ArtifactService artifactService;
    @Autowired GetDeliverableService getDeliverableService;
    @Autowired GetSourceCodeEntryService getSourceCodeEntryService;
    @Autowired VariantService variantService;

    private final VexStatementProposalRepository repository;

    VexStatementProposalService(VexStatementProposalRepository repository) {
        this.repository = repository;
    }

    public Optional<VexStatementProposalData> getProposal(UUID uuid) {
        return repository.findById(uuid).map(VexStatementProposalData::dataFromRecord);
    }

    public List<VexStatementProposalData> listForOrg(UUID org) {
        return repository.findByOrg(org.toString()).stream()
            .map(VexStatementProposalData::dataFromRecord).collect(Collectors.toList());
    }

    public List<VexStatementProposalData> listForOrgAndStatus(UUID org, ProposalStatus status) {
        return repository.findByOrgAndStatus(org.toString(), status.name()).stream()
            .map(VexStatementProposalData::dataFromRecord).collect(Collectors.toList());
    }

    public List<VexStatementProposalData> listForArtifact(UUID artifact) {
        return repository.findBySourceArtifact(artifact.toString()).stream()
            .map(VexStatementProposalData::dataFromRecord).collect(Collectors.toList());
    }

    /**
     * Lists VEX proposals whose source artifact was uploaded to the given release —
     * "what VEX has been uploaded here". A VEX artifact can be bound to a release
     * through several paths, so this walks all of them: the release's own artifacts,
     * its deliverables (release-level inbound plus each variant's outbound), and its
     * source code entries (export SCE plus commit SCEs). The deliverable/SCE upload
     * paths attach the VEX artifact to that child object, not to the release's own
     * artifact list (see {@code ReleaseService.processDeliverableArtifacts} /
     * {@code processSceArtifacts}), so a release-artifacts-only walk silently misses
     * deliverable- and SCE-bound VEX.
     *
     * <p>The view stays provenance-local: it answers "which VEX documents were
     * uploaded against this release", not "which VEX decisions affect it". Proposals
     * whose effect crosses scope levels (e.g. an ORG-scoped proposal touching many
     * releases) are surfaced in the org-wide VEX Proposals inbox, not here.
     */
    public List<VexStatementProposalData> listForRelease(UUID org, UUID release) {
        Optional<ReleaseData> ord = sharedReleaseService.getReleaseData(release, org);
        if (ord.isEmpty()) {
            return List.of();
        }
        List<String> vexArtifactUuids = collectVexArtifactUuids(ord.get()).stream()
            .map(UUID::toString)
            .collect(Collectors.toList());
        if (vexArtifactUuids.isEmpty()) return List.of();
        return repository.findByOrgAndSourceArtifactIn(org.toString(), vexArtifactUuids).stream()
            .map(VexStatementProposalData::dataFromRecord).collect(Collectors.toList());
    }

    /**
     * Collects every VEX-typed artifact bound to a release — directly, or through one
     * of its deliverables or source code entries. Returns a de-duplicated set in
     * discovery order. Dangling child references are tolerated: a missing deliverable
     * or SCE is skipped, never thrown (the model carries no DB-level FKs — see
     * {@code coding_principles.md}).
     */
    private Set<UUID> collectVexArtifactUuids(ReleaseData rd) {
        Set<UUID> candidates = new LinkedHashSet<>();
        if (rd.getArtifacts() != null) {
            candidates.addAll(rd.getArtifacts());
        }

        // Deliverables: release-level inbound + each variant's outbound.
        Set<UUID> deliverableUuids = new LinkedHashSet<>(rd.getInboundDeliverables());
        for (VariantData vd : variantService.getVariantsOfRelease(rd.getUuid())) {
            if (vd.getOutboundDeliverables() != null) {
                deliverableUuids.addAll(vd.getOutboundDeliverables());
            }
        }
        for (DeliverableData dd : getDeliverableService.getDeliverableDataList(deliverableUuids)) {
            if (dd.getArtifacts() != null) {
                candidates.addAll(dd.getArtifacts());
            }
        }

        // Source code entries: export SCE + commit SCEs (getAllCommits unions both).
        Set<UUID> sceUuids = rd.getAllCommits().stream()
            .filter(Objects::nonNull)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        for (SourceCodeEntryData sced : getSourceCodeEntryService.getSourceCodeEntryDataList(sceUuids)) {
            if (sced.getArtifacts() != null) {
                sced.getArtifacts().stream()
                    .map(SCEArtifact::artifactUuid)
                    .filter(Objects::nonNull)
                    .forEach(candidates::add);
            }
        }

        // Keep only VEX-typed artifacts: the proposal table only ever keys off a VEX
        // source artifact, and this trims the IN-list handed to the repository. One
        // batch lookup — missing artifacts are simply absent from the result.
        return artifactService.getArtifactDataListLight(candidates).stream()
            .filter(ad -> ad.getType() == ArtifactData.ArtifactType.VEX)
            .map(ArtifactData::getUuid)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Transactional
    public VexStatementProposalData createProposal(VexStatementProposalData data, WhoUpdated wu) throws RelizaException {
        // Dedupe per (org, artifact, hash, scope, scopeUuid) — one inbound statement can resolve
        // to many scope-targets and each target's proposal has its own supersession lifecycle.
        markSuperseded(data.getOrg(), data.getSourceArtifact(), data.getSourceStatementHash(),
            data.getScope(), data.getScopeUuid(), wu);

        VexStatementProposal e = new VexStatementProposal();
        e.setUuid(data.getUuid());
        return saveProposal(e, data.toRecordData(), wu);
    }

    @Transactional
    public VexStatementProposalData accept(UUID proposalUuid, String comment, WhoUpdated wu) throws RelizaException {
        Optional<VexStatementProposal> opt = repository.findByIdWriteLocked(proposalUuid);
        if (opt.isEmpty()) throw new RelizaException("Proposal not found: " + proposalUuid);
        VexStatementProposal e = opt.get();
        VexStatementProposalData d = VexStatementProposalData.dataFromRecord(e);
        if (d.getStatus() != ProposalStatus.PENDING) {
            throw new RelizaException("Proposal not in PENDING state (was: " + d.getStatus() + ")");
        }
        if (d.getSeverity() == null) {
            throw new RelizaException("Severity is required before accepting a VEX proposal. "
                + "Use Modify to set it (CRITICAL, HIGH, MEDIUM, LOW, or UNASSIGNED).");
        }

        boolean conditional = conditionalPredicate.needsAttestation(
            d.getAnalysisState(), d.getAnalysisJustification(),
            d.getWorkaround(), d.getResponses());

        if (conditional) {
            // Conditional claim — create the attestation and DEFER the VulnAnalysis write.
            // The actual VulnAnalysis mutation fires later when MitigationAttestationService.attest()
            // is called and reaches back through VexImportService.onAttested(...) (Task 20).
            ClaimType claimType = conditionalPredicate.classify(
                d.getAnalysisState(), d.getAnalysisJustification(), d.getWorkaround());
            String claimText = (d.getWorkaround() != null && !d.getWorkaround().isBlank())
                ? d.getWorkaround()
                : ("not_affected with " + d.getAnalysisJustification() + ": " + (d.getDetails() == null ? "" : d.getDetails()));
            MitigationAttestationData attest = MitigationAttestationData.createMitigationAttestationData(
                d.getOrg(), d.getUuid(), claimType, claimText,
                null /* assignee — set by reviewer in UI */,
                d.getScope(), d.getScopeUuid(), null /* deadline */);
            attestationService.create(attest, wu);
            d.setMitigationAttestation(attest.getUuid());
        } else {
            VulnAnalysisData target = applyToVulnAnalysis(d, wu);
            d.setTargetVulnAnalysis(target.getUuid());
        }

        d.setStatus(ProposalStatus.ACCEPTED);
        d.setActedAt(ZonedDateTime.now());
        d.setActedBy(wu != null ? wu.getLastUpdatedBy() : null);
        if (comment != null && !comment.isBlank()) d.setStatusReason(comment);
        return saveProposal(e, d.toRecordData(), wu);
    }

    /** Convenience overload preserving the older signature (no acceptance comment). */
    @Transactional
    public VexStatementProposalData accept(UUID proposalUuid, WhoUpdated wu) throws RelizaException {
        return accept(proposalUuid, null, wu);
    }

    /**
     * Apply user edits to a PENDING proposal in-place. The user-editable fields are the
     * triage-affecting ones (state, justification, details, severity, responses,
     * recommendation, workaround). All other fields (provenance, match keys, audit fields)
     * are immutable. Updates do not transition status — the user still has to Accept or Reject.
     */
    @Transactional
    public VexStatementProposalData updateProposal(UUID proposalUuid, VexStatementProposalData updates, WhoUpdated wu) throws RelizaException {
        Optional<VexStatementProposal> opt = repository.findByIdWriteLocked(proposalUuid);
        if (opt.isEmpty()) throw new RelizaException("Proposal not found: " + proposalUuid);
        VexStatementProposal e = opt.get();
        VexStatementProposalData d = VexStatementProposalData.dataFromRecord(e);
        if (d.getStatus() != ProposalStatus.PENDING) {
            throw new RelizaException("Only PENDING proposals can be edited (was: " + d.getStatus() + ")");
        }
        if (updates.getAnalysisState() != null) d.setAnalysisState(updates.getAnalysisState());
        if (updates.getAnalysisJustification() != null) d.setAnalysisJustification(updates.getAnalysisJustification());
        if (updates.getDetails() != null) d.setDetails(updates.getDetails());
        if (updates.getSeverity() != null) d.setSeverity(updates.getSeverity());
        if (updates.getResponses() != null) d.setResponses(updates.getResponses());
        if (updates.getRecommendation() != null) d.setRecommendation(updates.getRecommendation());
        if (updates.getWorkaround() != null) d.setWorkaround(updates.getWorkaround());
        return saveProposal(e, d.toRecordData(), wu);
    }

    @Transactional
    public VexStatementProposalData reject(UUID proposalUuid, WhoUpdated wu, String reason) throws RelizaException {
        Optional<VexStatementProposal> opt = repository.findByIdWriteLocked(proposalUuid);
        if (opt.isEmpty()) throw new RelizaException("Proposal not found: " + proposalUuid);
        VexStatementProposal e = opt.get();
        VexStatementProposalData d = VexStatementProposalData.dataFromRecord(e);
        if (d.getStatus() != ProposalStatus.PENDING) {
            throw new RelizaException("Proposal not in PENDING state (was: " + d.getStatus() + ")");
        }
        d.setStatus(ProposalStatus.REJECTED);
        d.setStatusReason(reason);
        d.setActedAt(ZonedDateTime.now());
        d.setActedBy(wu != null ? wu.getLastUpdatedBy() : null);
        return saveProposal(e, d.toRecordData(), wu);
    }

    @Transactional
    public void markSuperseded(UUID org, UUID sourceArtifact, String sourceStatementHash,
                               io.reliza.model.AnalysisScope scope, UUID scopeUuid,
                               WhoUpdated wu) throws RelizaException {
        if (scope == null || scopeUuid == null) {
            // Defensive: VEX proposals always carry scope + scopeUuid (set by ScopeTargetResolver
            // before createProposal is invoked); if a caller forgets, we'd otherwise key dedupe
            // against the literal string "null" and silently fail to supersede anything.
            throw new IllegalArgumentException(
                "markSuperseded requires scope + scopeUuid (per-target dedupe key)");
        }
        Optional<VexStatementProposal> opt = repository.findForDedupe(
            org.toString(), sourceArtifact.toString(), sourceStatementHash,
            scope.name(), scopeUuid.toString());
        if (opt.isEmpty()) return;
        Optional<VexStatementProposal> locked = repository.findByIdWriteLocked(opt.get().getUuid());
        if (locked.isEmpty()) return;
        VexStatementProposal e = locked.get();
        VexStatementProposalData d = VexStatementProposalData.dataFromRecord(e);
        if (d.getStatus() != ProposalStatus.PENDING) return;
        d.setStatus(ProposalStatus.SUPERSEDED);
        d.setActedAt(ZonedDateTime.now());
        saveProposal(e, d.toRecordData(), wu);
    }

    /**
     * Called when a MitigationAttestation linked to this proposal flips to ATTESTED.
     * Writes the deferred VulnAnalysis. No-op if the proposal is not ACCEPTED with a linked attestation.
     */
    @Transactional
    public void applyAttestedVulnAnalysis(UUID proposalUuid, WhoUpdated wu) throws RelizaException {
        Optional<VexStatementProposal> opt = repository.findByIdWriteLocked(proposalUuid);
        if (opt.isEmpty()) return;
        VexStatementProposal e = opt.get();
        VexStatementProposalData d = VexStatementProposalData.dataFromRecord(e);
        if (d.getStatus() != ProposalStatus.ACCEPTED) return;
        if (d.getMitigationAttestation() == null) return;
        if (d.getTargetVulnAnalysis() != null) return; // already written
        VulnAnalysisData target = applyToVulnAnalysis(d, wu);
        d.setTargetVulnAnalysis(target.getUuid());
        saveProposal(e, d.toRecordData(), wu);
    }

    private VulnAnalysisData applyToVulnAnalysis(VexStatementProposalData d, WhoUpdated wu) throws RelizaException {
        Optional<VulnAnalysisData> existing = vulnAnalysisService.findByOrgAndLocationAndFindingIdAndScopeAndType(
            d.getOrg(), d.getLocation(), d.getFindingId(), d.getScope(), d.getScopeUuid(), d.getFindingType());
        if (existing.isPresent()) {
            return vulnAnalysisService.updateAnalysisState(
                existing.get().getUuid(),
                d.getAnalysisState(), d.getAnalysisJustification(), d.getDetails(),
                d.getFindingAliases(), d.getSeverity(),
                d.getResponses(), d.getRecommendation(), d.getWorkaround(), wu);
        }
        CreateVulnAnalysisDto dto = new CreateVulnAnalysisDto();
        dto.setOrg(d.getOrg());
        dto.setLocation(d.getRawLocation() != null ? d.getRawLocation() : d.getLocation());
        dto.setLocationType(d.getLocationType());
        dto.setFindingId(d.getFindingId());
        dto.setFindingAliases(d.getFindingAliases());
        dto.setFindingType(d.getFindingType());
        dto.setScope(d.getScope());
        dto.setScopeUuid(d.getScopeUuid());
        dto.setState(d.getAnalysisState());
        dto.setJustification(d.getAnalysisJustification());
        dto.setDetails(d.getDetails());
        dto.setSeverity(d.getSeverity());
        dto.setResponses(d.getResponses());
        dto.setRecommendation(d.getRecommendation());
        dto.setWorkaround(d.getWorkaround());
        return vulnAnalysisService.createVulnAnalysis(dto, wu);
    }

    private VexStatementProposalData saveProposal(VexStatementProposal e, Map<String, Object> recordData, WhoUpdated wu) {
        Optional<VexStatementProposal> existing = repository.findById(e.getUuid());
        if (existing.isPresent()) {
            auditService.createAndSaveAuditRecord(TableName.VEX_STATEMENT_PROPOSAL, e);
            e.setRevision(e.getRevision() + 1);
            e.setLastUpdatedDate(ZonedDateTime.now());
        }
        e.setRecordData(recordData);
        e = (VexStatementProposal) WhoUpdated.injectWhoUpdatedData(e, wu);
        VexStatementProposal saved = repository.save(e);
        return VexStatementProposalData.dataFromRecord(saved);
    }
}
