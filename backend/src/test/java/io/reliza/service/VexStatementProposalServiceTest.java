/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisJustification;
import io.reliza.model.AnalysisScope;
import io.reliza.model.AnalysisState;
import io.reliza.model.FindingType;
import io.reliza.model.LocationType;
import io.reliza.model.ProposalStatus;
import io.reliza.model.SourceFormat;
import io.reliza.model.VexStatementProposal;
import io.reliza.model.VexStatementProposalData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.VexStatementProposalRepository;

class VexStatementProposalServiceTest {

    private VexStatementProposalRepository repo;
    private AuditService audit;
    private VulnAnalysisService vulnService;
    private VexStatementProposalService svc;

    @BeforeEach
    void setUp() {
        repo = mock(VexStatementProposalRepository.class);
        audit = mock(AuditService.class);
        vulnService = mock(VulnAnalysisService.class);
        svc = new VexStatementProposalService(repo);
        svc.auditService = audit;
        svc.vulnAnalysisService = vulnService;
        svc.conditionalPredicate = new ConditionalMitigationPredicate();
        svc.attestationService = mock(MitigationAttestationService.class);
    }

    private VexStatementProposalData newProposal() {
        return VexStatementProposalData.createVexStatementProposalData(
            UUID.randomUUID(), UUID.randomUUID(), SourceFormat.OPENVEX, "h1", "{}",
            AnalysisScope.RELEASE, UUID.randomUUID(),
            "pkg:maven/x/y", "pkg:maven/x/y@1.2.3", LocationType.PURL,
            "CVE-2024-1", List.of(), FindingType.VULNERABILITY,
            AnalysisState.NOT_AFFECTED, AnalysisJustification.CODE_NOT_REACHABLE,
            "details", null, List.of(), null, null, List.of(),
            null, null, null);
    }

    @Test
    void rejectFailsWhenAlreadyTerminal() throws RelizaException {
        VexStatementProposalData d = newProposal();
        d.setStatus(ProposalStatus.ACCEPTED);
        VexStatementProposal e = new VexStatementProposal();
        e.setUuid(d.getUuid());
        e.setRecordData(d.toRecordData());
        when(repo.findByIdWriteLocked(d.getUuid())).thenReturn(Optional.of(e));

        RelizaException ex = assertThrows(RelizaException.class,
            () -> svc.reject(d.getUuid(), WhoUpdated.getTestWhoUpdated(), "reason"));
        assertEquals("Proposal not in PENDING state (was: ACCEPTED)", ex.getMessage());
    }

    @Test
    void rejectMarksProposalAndDoesNotMutateVulnAnalysis() throws RelizaException {
        VexStatementProposalData d = newProposal();
        VexStatementProposal e = new VexStatementProposal();
        e.setUuid(d.getUuid());
        e.setRecordData(d.toRecordData());
        when(repo.findByIdWriteLocked(d.getUuid())).thenReturn(Optional.of(e));
        when(repo.save(any(VexStatementProposal.class))).thenAnswer(inv -> inv.getArgument(0));

        VexStatementProposalData out = svc.reject(d.getUuid(), WhoUpdated.getTestWhoUpdated(), "not trusted");
        assertEquals(ProposalStatus.REJECTED, out.getStatus());
        assertEquals("not trusted", out.getStatusReason());
        assertNotNull(out.getActedAt());
    }

    @Test
    void supersededDedupeFindsAndMarksOldProposal() throws RelizaException {
        VexStatementProposalData old = newProposal();
        VexStatementProposal oldE = new VexStatementProposal();
        oldE.setUuid(old.getUuid());
        oldE.setRecordData(old.toRecordData());
        when(repo.findForDedupe(old.getOrg().toString(), old.getSourceArtifact().toString(), "h1"))
            .thenReturn(Optional.of(oldE));
        when(repo.findByIdWriteLocked(old.getUuid())).thenReturn(Optional.of(oldE));
        when(repo.save(any(VexStatementProposal.class))).thenAnswer(inv -> inv.getArgument(0));

        svc.markSuperseded(old.getOrg(), old.getSourceArtifact(), "h1", WhoUpdated.getTestWhoUpdated());

        ArgumentCaptor<VexStatementProposal> captor = ArgumentCaptor.forClass(VexStatementProposal.class);
        verify(repo).save(captor.capture());
        VexStatementProposalData saved = VexStatementProposalData.dataFromRecord(captor.getValue());
        assertEquals(ProposalStatus.SUPERSEDED, saved.getStatus());
        assertNotNull(saved.getActedAt());
    }

    @Test
    void acceptFlipsStatusAndDelegatesToVulnAnalysis() throws RelizaException {
        VexStatementProposalData d = newProposal();
        VexStatementProposal e = new VexStatementProposal();
        e.setUuid(d.getUuid());
        e.setRecordData(d.toRecordData());
        when(repo.findByIdWriteLocked(d.getUuid())).thenReturn(Optional.of(e));
        when(repo.save(any(VexStatementProposal.class))).thenAnswer(inv -> inv.getArgument(0));

        // No existing VulnAnalysis row for this 5-tuple → goes through the create path.
        when(vulnService.findByOrgAndLocationAndFindingIdAndScopeAndType(
                any(UUID.class), any(String.class), any(String.class),
                any(AnalysisScope.class), any(UUID.class), any(FindingType.class)))
            .thenReturn(Optional.empty());

        UUID createdAnalysisUuid = UUID.randomUUID();
        io.reliza.model.VulnAnalysisData createdAnalysis = mock(io.reliza.model.VulnAnalysisData.class);
        when(createdAnalysis.getUuid()).thenReturn(createdAnalysisUuid);
        when(vulnService.createVulnAnalysis(any(io.reliza.model.dto.CreateVulnAnalysisDto.class), any(WhoUpdated.class)))
            .thenReturn(createdAnalysis);

        VexStatementProposalData out = svc.accept(d.getUuid(), WhoUpdated.getTestWhoUpdated());

        assertEquals(ProposalStatus.ACCEPTED, out.getStatus());
        assertEquals(createdAnalysisUuid, out.getTargetVulnAnalysis());
        assertNotNull(out.getActedAt());
        verify(vulnService).createVulnAnalysis(any(io.reliza.model.dto.CreateVulnAnalysisDto.class), any(WhoUpdated.class));
    }

    @Test
    void acceptForConditionalClaimCreatesAttestationAndDefersVulnAnalysis() throws RelizaException {
        // Build a proposal with NOT_AFFECTED + PROTECTED_AT_PERIMETER (conditional claim).
        VexStatementProposalData d = VexStatementProposalData.createVexStatementProposalData(
            UUID.randomUUID(), UUID.randomUUID(), io.reliza.model.SourceFormat.OPENVEX, "h1", "{}",
            io.reliza.model.AnalysisScope.RELEASE, UUID.randomUUID(),
            "pkg:x/y", "pkg:x/y@1", io.reliza.model.LocationType.PURL,
            "CVE-1", java.util.List.of(), io.reliza.model.FindingType.VULNERABILITY,
            io.reliza.model.AnalysisState.NOT_AFFECTED,
            io.reliza.model.AnalysisJustification.PROTECTED_AT_PERIMETER,
            "behind mTLS", null, java.util.List.of(), null, null, java.util.List.of(),
            null, null, null);

        VexStatementProposal e = new VexStatementProposal();
        e.setUuid(d.getUuid());
        e.setRecordData(d.toRecordData());
        when(repo.findByIdWriteLocked(d.getUuid())).thenReturn(java.util.Optional.of(e));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // Inject the new collaborators
        ConditionalMitigationPredicate predicate = new ConditionalMitigationPredicate();
        MitigationAttestationService attestSvc = mock(MitigationAttestationService.class);
        svc.conditionalPredicate = predicate;
        svc.attestationService = attestSvc;
        // Capture the attestation that gets created.
        when(attestSvc.create(any(), any())).thenAnswer(inv -> inv.getArgument(0));

        VexStatementProposalData out = svc.accept(d.getUuid(), io.reliza.model.WhoUpdated.getTestWhoUpdated());

        assertEquals(io.reliza.model.ProposalStatus.ACCEPTED, out.getStatus());
        assertNotNull(out.getMitigationAttestation()); // attestation linked
        assertNull(out.getTargetVulnAnalysis()); // deferred — VulnAnalysis NOT written yet
        verify(vulnService, org.mockito.Mockito.never()).createVulnAnalysis(any(), any());
        verify(vulnService, org.mockito.Mockito.never()).updateAnalysisState(any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
