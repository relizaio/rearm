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
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.reliza.model.AnalysisScope;
import io.reliza.model.AnalysisState;
import io.reliza.model.ImportMode;
import io.reliza.model.IssuerClass;
import io.reliza.model.ReleaseData;
import io.reliza.model.SourceFormat;
import io.reliza.model.VexStatementProposalData;
import io.reliza.model.VulnAnalysisData;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.model.dto.CdxVexStatement;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.model.dto.VexImportInput;
import io.reliza.model.dto.VexImportResult;
import io.reliza.model.dto.VexParseEntry;
import io.reliza.model.dto.VexParseResult;

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

    /**
     * Release-bound COMPONENT import where the PURL also appears in another component's
     * release: exactly one proposal, on the uploading release's component.
     */
    @Test
    void releaseBoundComponentImportTargetsOnlyUploadingComponent() throws Exception {
        UUID org = UUID.randomUUID();
        UUID boundRelease = UUID.randomUUID();
        UUID otherRelease = UUID.randomUUID();
        UUID componentA = UUID.randomUUID();
        UUID componentB = UUID.randomUUID();
        String purl = "pkg:npm/foo@1.2.3";

        CdxVexParser parser = mock(CdxVexParser.class);
        CdxVexStatement stmt = new CdxVexStatement("CVE-2026-0001", List.of(), List.of(purl),
            AnalysisState.NOT_AFFECTED, null, null, null, null, List.of(),
            VulnerabilitySeverity.HIGH, "{}");
        when(parser.parse(any())).thenReturn(new VexParseResult(
            List.of(new VexParseEntry(stmt, List.of())), null, 0, List.of()));
        svc.cdxVexParser = parser;

        PurlReleaseResolver purlResolver = mock(PurlReleaseResolver.class);
        when(purlResolver.resolve(org, purl)).thenReturn(Set.of(boundRelease, otherRelease));
        SharedReleaseService srs = mock(SharedReleaseService.class);
        ReleaseData boundRd = mock(ReleaseData.class);
        when(boundRd.getComponent()).thenReturn(componentA);
        ReleaseData otherRd = mock(ReleaseData.class);
        when(otherRd.getComponent()).thenReturn(componentB);
        when(srs.getReleaseDataLight(boundRelease, org)).thenReturn(Optional.of(boundRd));
        when(srs.getReleaseDataLight(otherRelease, org)).thenReturn(Optional.of(otherRd));
        PurlScopeMatcher matcher = new PurlScopeMatcher();
        matcher.purlReleaseResolver = purlResolver;
        matcher.scopeTargetResolver = new ScopeTargetResolver();
        matcher.scopeTargetResolver.sharedReleaseService = srs;
        svc.matcher = matcher;

        svc.vulnAnalysisService = mock(VulnAnalysisService.class);
        svc.verdictResolver = new ImportVerdictResolver();
        VexStatementProposalService proposals = mock(VexStatementProposalService.class);
        ArgumentCaptor<VexStatementProposalData> created =
            ArgumentCaptor.forClass(VexStatementProposalData.class);
        when(proposals.createProposal(created.capture(), any())).thenAnswer(inv -> inv.getArgument(0));
        svc.proposalService = proposals;

        VexImportInput input = new VexImportInput();
        input.setOrg(org);
        input.setSourceArtifact(UUID.randomUUID());
        input.setContent("{}");
        input.setFormat(SourceFormat.CDX_VEX);
        input.setIssuerClass(IssuerClass.VENDOR);
        input.setRestrictToRelease(boundRelease);
        input.setVexScope(AnalysisScope.COMPONENT);
        input.setVexImportMode(ImportMode.STAGE);

        VexImportResult result = svc.importPipeline(input, null);

        assertEquals(1, result.getProposalsCreated());
        assertEquals(1, created.getAllValues().size());
        assertEquals(AnalysisScope.COMPONENT, created.getValue().getScope());
        assertEquals(componentA, created.getValue().getScopeUuid());
    }

    private static <T> T any() { return org.mockito.ArgumentMatchers.any(); }
}
