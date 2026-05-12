/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.exceptions.RelizaException;
import io.reliza.model.AnalysisScope;
import io.reliza.model.AttestationStatus;
import io.reliza.model.ClaimType;
import io.reliza.model.MitigationAttestation;
import io.reliza.model.MitigationAttestationData;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.MitigationAttestationRepository;

class MitigationAttestationServiceTest {

    private MitigationAttestationRepository repo;
    private AuditService audit;
    private MitigationAttestationService svc;

    @BeforeEach
    void setUp() {
        repo = mock(MitigationAttestationRepository.class);
        audit = mock(AuditService.class);
        svc = new MitigationAttestationService(repo);
        svc.auditService = audit;
    }

    private MitigationAttestation entityWith(MitigationAttestationData d) {
        MitigationAttestation e = new MitigationAttestation();
        e.setUuid(d.getUuid());
        e.setRecordData(d.toRecordData());
        return e;
    }

    @Test
    void attestFlipsStatusAndStampsAttestedAt() throws RelizaException {
        MitigationAttestationData d = MitigationAttestationData.createMitigationAttestationData(
            UUID.randomUUID(), UUID.randomUUID(), ClaimType.WORKAROUND, "do X",
            UUID.randomUUID(), AnalysisScope.RELEASE, UUID.randomUUID(), null);
        MitigationAttestation e = entityWith(d);
        when(repo.findByIdWriteLocked(d.getUuid())).thenReturn(Optional.of(e));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MitigationAttestationData out = svc.attest(d.getUuid(), "verified in prod cluster", WhoUpdated.getTestWhoUpdated());
        assertEquals(AttestationStatus.ATTESTED, out.getStatus());
        assertEquals("verified in prod cluster", out.getEvidence());
        assertNotNull(out.getAttestedAt());
    }

    @Test
    void waiveFlipsStatusAndDoesNotMutateAnalysis() throws RelizaException {
        MitigationAttestationData d = MitigationAttestationData.createMitigationAttestationData(
            UUID.randomUUID(), UUID.randomUUID(), ClaimType.WORKAROUND, "do X",
            UUID.randomUUID(), AnalysisScope.RELEASE, UUID.randomUUID(), null);
        MitigationAttestation e = entityWith(d);
        when(repo.findByIdWriteLocked(d.getUuid())).thenReturn(Optional.of(e));
        when(repo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        MitigationAttestationData out = svc.waive(d.getUuid(), "we won't apply this", WhoUpdated.getTestWhoUpdated());
        assertEquals(AttestationStatus.WAIVED, out.getStatus());
        assertEquals("we won't apply this", out.getStatusReason());
    }

    @Test
    void attestRejectsNonPending() throws RelizaException {
        MitigationAttestationData d = MitigationAttestationData.createMitigationAttestationData(
            UUID.randomUUID(), UUID.randomUUID(), ClaimType.WORKAROUND, "x",
            UUID.randomUUID(), AnalysisScope.RELEASE, UUID.randomUUID(), null);
        d.setStatus(AttestationStatus.WAIVED);
        when(repo.findByIdWriteLocked(d.getUuid())).thenReturn(Optional.of(entityWith(d)));
        assertThrows(RelizaException.class,
            () -> svc.attest(d.getUuid(), "ev", WhoUpdated.getTestWhoUpdated()));
    }
}
