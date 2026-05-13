/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.model.AnalysisScope;
import io.reliza.model.VulnAnalysisData;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

/**
 * Pins the severity-resolution chain contract: parser → existing FindingAnalysis rows →
 * canonical vulnerability_records → null. See ai-plans/vex_imports/10_severity_resolution.md.
 */
class VexImportServiceTest {

    private VexImportService svc;
    private VulnerabilityRecordService recordService;

    @BeforeEach
    void setUp() {
        svc = new VexImportService();
        recordService = mock(VulnerabilityRecordService.class);
        svc.vulnerabilityRecordService = recordService;
    }

    @Test
    void parserSeverityWinsAndShortCircuits() {
        VulnerabilitySeverity out = svc.resolveSeverity(
            UUID.randomUUID(), VulnerabilitySeverity.HIGH, "CVE-1", List.of());
        assertEquals(VulnerabilitySeverity.HIGH, out);
        // Short-circuit: vulnerability_records must not be hit when statement carries severity.
        verify(recordService, never()).getByAlias(any(), any());
    }

    @Test
    void fallsBackToExistingFindingAnalysisAtNarrowestScope() {
        // RELEASE row should be preferred over BRANCH row when both carry severity.
        VulnAnalysisData branchRow = mock(VulnAnalysisData.class);
        when(branchRow.getScope()).thenReturn(AnalysisScope.BRANCH);
        when(branchRow.getSeverity()).thenReturn(VulnerabilitySeverity.LOW);
        VulnAnalysisData releaseRow = mock(VulnAnalysisData.class);
        when(releaseRow.getScope()).thenReturn(AnalysisScope.RELEASE);
        when(releaseRow.getSeverity()).thenReturn(VulnerabilitySeverity.CRITICAL);

        VulnerabilitySeverity out = svc.resolveSeverity(
            UUID.randomUUID(), null, "CVE-1", List.of(branchRow, releaseRow));
        assertEquals(VulnerabilitySeverity.CRITICAL, out);
        // No need to consult the canonical record when an analysis row already covers it.
        verify(recordService, never()).getByAlias(any(), any());
    }

    @Test
    void skipsAnalysisRowsWithNullSeverity() {
        // An ORG-scoped row exists but has no severity; a narrower BRANCH row does. Picks the BRANCH.
        VulnAnalysisData orgRow = mock(VulnAnalysisData.class);
        when(orgRow.getScope()).thenReturn(AnalysisScope.ORG);
        when(orgRow.getSeverity()).thenReturn(null);
        VulnAnalysisData branchRow = mock(VulnAnalysisData.class);
        when(branchRow.getScope()).thenReturn(AnalysisScope.BRANCH);
        when(branchRow.getSeverity()).thenReturn(VulnerabilitySeverity.MEDIUM);

        VulnerabilitySeverity out = svc.resolveSeverity(
            UUID.randomUUID(), null, "CVE-1", List.of(orgRow, branchRow));
        assertEquals(VulnerabilitySeverity.MEDIUM, out);
    }

    @Test
    void fallsBackToCanonicalVulnerabilityRecord() {
        UUID org = UUID.randomUUID();
        VulnerabilityRecordData rec = mock(VulnerabilityRecordData.class);
        when(rec.getSeverity()).thenReturn(VulnerabilitySeverity.HIGH);
        when(recordService.getByAlias(org, "CVE-1")).thenReturn(Optional.of(rec));

        VulnerabilitySeverity out = svc.resolveSeverity(org, null, "CVE-1", List.of());
        assertEquals(VulnerabilitySeverity.HIGH, out);
    }

    @Test
    void canonicalRecordSeverityNullPassesThroughAsNull() {
        UUID org = UUID.randomUUID();
        VulnerabilityRecordData rec = mock(VulnerabilityRecordData.class);
        when(rec.getSeverity()).thenReturn(null);
        when(recordService.getByAlias(org, "CVE-1")).thenReturn(Optional.of(rec));

        assertNull(svc.resolveSeverity(org, null, "CVE-1", List.of()));
    }

    @Test
    void chainExhaustionReturnsNull() {
        UUID org = UUID.randomUUID();
        when(recordService.getByAlias(org, "CVE-1")).thenReturn(Optional.empty());
        assertNull(svc.resolveSeverity(org, null, "CVE-1", List.of()));
    }

    private static <T> T any() { return org.mockito.ArgumentMatchers.any(); }
}
