/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.dto.HistoricallyResolvedFinding;
import io.reliza.model.ComponentData;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;

/**
 * Unit tests for {@link FindingComparisonService#findHistoricallyResolvedForRelease}.
 *
 * Constructs the service with mocked collaborators ({@link BranchService},
 * {@link SharedReleaseService}) and stubs {@link ReleaseData} via Mockito so each test pins
 * one lineage shape. {@link VulnerabilityDto} and {@link ReleaseMetricsDto} are constructed
 * via their public APIs.
 */
@ExtendWith(MockitoExtension.class)
public class FindingComparisonServiceTest {

    private static final UUID TRUNK_BRANCH = UUID.fromString("00000000-0000-0000-0000-0000000000a0");
    private static final UUID FEATURE_BRANCH = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
    private static final UUID SIBLING_BRANCH = UUID.fromString("00000000-0000-0000-0000-0000000000c0");
    private static final UUID COMPONENT_UUID = UUID.fromString("00000000-0000-0000-0000-0000000000d0");

    private static final String LODASH_PURL = "pkg:npm/lodash@4.17.20";
    private static final String AXIOS_PURL = "pkg:npm/axios@0.21.1";

    // ---- Fixture helpers ----

    private static VulnerabilityDto vuln(String vulnId, String purl) {
        return new VulnerabilityDto(
                purl, vulnId, VulnerabilitySeverity.HIGH,
                Set.of(), Set.of(), Set.of(),
                null, null, null,
                null, null, null, null, null, null);
    }

    private static ReleaseMetricsDto metricsWith(VulnerabilityDto... vulns) {
        ReleaseMetricsDto m = new ReleaseMetricsDto();
        m.setVulnerabilityDetails(new LinkedList<>(List.of(vulns)));
        return m;
    }

    private static ReleaseData rdMock(UUID releaseUuid, UUID branchUuid, String version,
            String iso8601CreatedDate, ReleaseMetricsDto metrics) {
        ReleaseData rd = mock(ReleaseData.class);
        // lenient: not every test invocation needs every getter, but stubbing them up front
        // avoids per-test boilerplate.
        lenient().when(rd.getUuid()).thenReturn(releaseUuid);
        lenient().when(rd.getBranch()).thenReturn(branchUuid);
        lenient().when(rd.getComponent()).thenReturn(COMPONENT_UUID);
        lenient().when(rd.getVersion()).thenReturn(version);
        lenient().when(rd.getCreatedDate()).thenReturn(ZonedDateTime.parse(iso8601CreatedDate));
        lenient().when(rd.getMetrics()).thenReturn(metrics);
        return rd;
    }

    private static ComponentData componentDataMock(ComponentType type) {
        ComponentData cd = mock(ComponentData.class);
        lenient().when(cd.getType()).thenReturn(type);
        lenient().when(cd.getUuid()).thenReturn(COMPONENT_UUID);
        return cd;
    }

    // ---- U1: trunk-only lineage ----

    @Test
    void singleBranch_resolvedBeforeTarget_returnsOneEntry() throws Exception {
        // v1.0 had CVE-A (lodash); v1.5 (target) does not.
        VulnerabilityDto cveA = vuln("CVE-2024-0001", LODASH_PURL);

        ReleaseData v10 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000010"),
                TRUNK_BRANCH, "1.0", "2026-01-01T00:00:00Z", metricsWith(cveA));
        ReleaseData v15 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000015"),
                TRUNK_BRANCH, "1.5", "2026-02-01T00:00:00Z", metricsWith()); // empty

        SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
        BranchService branchService = mock(BranchService.class);
        GetComponentService getComponentService = mock(GetComponentService.class);
        // listReleaseDataOfBranch (uncapped, sorted) returns newest first.
        when(sharedReleaseService.listReleaseDataOfBranch(eq(TRUNK_BRANCH), anyInt(), anyBoolean()))
                .thenReturn(List.of(v15, v10));
        // No fork point — trunk branch is root.
        when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                eq(TRUNK_BRANCH), any(UUID.class), any(ReleaseData.class),
                any(), any())).thenReturn(null);

        FindingComparisonService service =
                new FindingComparisonService(branchService, sharedReleaseService, getComponentService);

        List<HistoricallyResolvedFinding> result = service.findHistoricallyResolvedForRelease(
                v15, /*recurseChildren=*/ false, /*cutOffDate=*/ null);

        assertEquals(1, result.size(), "Expected one historical-resolved finding");
        assertEquals("CVE-2024-0001", result.get(0).vulnerability().vulnId());
        assertEquals(LODASH_PURL, result.get(0).vulnerability().purl());
        assertEquals(v15.getUuid(), result.get(0).resolvingReleaseUuid(),
                "Resolving release is the one in which the CVE first disappeared");
        assertEquals("1.5", result.get(0).resolvingReleaseVersion());
    }

    // ---- U3: multi-resolution lineage ----

    @Test
    void singleBranch_resolvedTwiceOnLineage_latestResolutionWins() throws Exception {
        VulnerabilityDto cveA = vuln("CVE-2024-0010", LODASH_PURL);

        // v1.0 has CVE; v1.5 doesn't (resolution #1); v1.7 has it again; v2.0 doesn't (resolution #2);
        // target v2.5 doesn't.
        ReleaseData v10 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000110"),
                TRUNK_BRANCH, "1.0", "2026-01-01T00:00:00Z", metricsWith(cveA));
        ReleaseData v15 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000115"),
                TRUNK_BRANCH, "1.5", "2026-02-01T00:00:00Z", metricsWith());
        ReleaseData v17 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000117"),
                TRUNK_BRANCH, "1.7", "2026-03-01T00:00:00Z", metricsWith(cveA));
        ReleaseData v20 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000120"),
                TRUNK_BRANCH, "2.0", "2026-04-01T00:00:00Z", metricsWith());
        ReleaseData v25 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000125"),
                TRUNK_BRANCH, "2.5", "2026-05-01T00:00:00Z", metricsWith());

        SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
        BranchService branchService = mock(BranchService.class);
        GetComponentService getComponentService = mock(GetComponentService.class);
        when(sharedReleaseService.listReleaseDataOfBranch(eq(TRUNK_BRANCH), anyInt(), anyBoolean()))
                .thenReturn(List.of(v25, v20, v17, v15, v10));   // newest first per existing convention
        when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                eq(TRUNK_BRANCH), any(UUID.class), any(ReleaseData.class),
                any(), any())).thenReturn(null);

        FindingComparisonService service =
                new FindingComparisonService(branchService, sharedReleaseService, getComponentService);
        List<HistoricallyResolvedFinding> result = service.findHistoricallyResolvedForRelease(
                v25, false, null);

        assertEquals(1, result.size(), "Multiple resolutions of the same CVE must collapse to one entry");
        assertEquals(v20.getUuid(), result.get(0).resolvingReleaseUuid(),
                "The most recent resolution (v2.0, not v1.5) wins");
    }

    // ---- U4: CVE present in target's current metrics is excluded ----

    @Test
    void cveStillInCurrentMetrics_isExcluded() throws Exception {
        // CVE-A in v1.0 metrics, "missing" briefly in v1.3, present again in v1.5 (target).
        // Target's current scan has CVE-A → must NOT appear as historical-resolved.
        VulnerabilityDto cveA = vuln("CVE-2024-0020", LODASH_PURL);

        ReleaseData v10 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000210"),
                TRUNK_BRANCH, "1.0", "2026-01-01T00:00:00Z", metricsWith(cveA));
        ReleaseData v13 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000213"),
                TRUNK_BRANCH, "1.3", "2026-02-01T00:00:00Z", metricsWith());
        ReleaseData v15 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000215"),
                TRUNK_BRANCH, "1.5", "2026-03-01T00:00:00Z", metricsWith(cveA));

        SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
        BranchService branchService = mock(BranchService.class);
        GetComponentService getComponentService = mock(GetComponentService.class);
        when(sharedReleaseService.listReleaseDataOfBranch(eq(TRUNK_BRANCH), anyInt(), anyBoolean()))
                .thenReturn(List.of(v15, v13, v10));
        when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                eq(TRUNK_BRANCH), any(UUID.class), any(ReleaseData.class),
                any(), any())).thenReturn(null);

        FindingComparisonService service =
                new FindingComparisonService(branchService, sharedReleaseService, getComponentService);
        List<HistoricallyResolvedFinding> result = service.findHistoricallyResolvedForRelease(v15, false, null);

        assertTrue(result.isEmpty(),
                "CVE present in target's current metrics must not be emitted as historical-resolved");
    }

    // ---- U2: single fork-point hop ----

    @Test
    void singleForkPoint_resolvedAcrossFork_returnsOneEntry() throws Exception {
        VulnerabilityDto cveA = vuln("CVE-2024-0030", LODASH_PURL);

        UUID trunkV10Uuid = UUID.fromString("00000000-0000-0000-0000-000000000310");
        UUID featAuth1Uuid = UUID.fromString("00000000-0000-0000-0000-000000000311");
        ReleaseData trunkV10 = rdMock(trunkV10Uuid, TRUNK_BRANCH, "1.0",
                "2026-01-01T00:00:00Z", metricsWith(cveA));
        ReleaseData featAuth1 = rdMock(featAuth1Uuid, FEATURE_BRANCH, "1.0-auth1",
                "2026-02-01T00:00:00Z", metricsWith());                          // target

        SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
        BranchService branchService = mock(BranchService.class);
        GetComponentService getComponentService = mock(GetComponentService.class);
        // feature/auth has just the target, ascending.
        when(sharedReleaseService.listReleaseDataOfBranch(eq(FEATURE_BRANCH), anyInt(), anyBoolean()))
                .thenReturn(List.of(featAuth1));
        // trunk has v1.0.
        when(sharedReleaseService.listReleaseDataOfBranch(eq(TRUNK_BRANCH), anyInt(), anyBoolean()))
                .thenReturn(List.of(trunkV10));
        // Fork point: feature/auth's oldest (featAuth1) was forked from trunkV10.
        when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                eq(FEATURE_BRANCH), eq(featAuth1Uuid), any(ReleaseData.class), any(), any()))
                .thenReturn(trunkV10Uuid);
        // Trunk has no fork point.
        when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                eq(TRUNK_BRANCH), any(UUID.class), any(ReleaseData.class), any(), any()))
                .thenReturn(null);
        when(sharedReleaseService.getReleaseData(eq(trunkV10Uuid))).thenReturn(Optional.of(trunkV10));

        FindingComparisonService service =
                new FindingComparisonService(branchService, sharedReleaseService, getComponentService);
        List<HistoricallyResolvedFinding> result = service.findHistoricallyResolvedForRelease(
                featAuth1, false, null);

        assertEquals(1, result.size());
        assertEquals("CVE-2024-0030", result.get(0).vulnerability().vulnId());
        assertEquals(featAuth1Uuid, result.get(0).resolvingReleaseUuid(),
                "Resolution surfaced as the first lineage release where CVE is absent");
    }

    @Test
    void siblingBranchResolution_isNotInLineage() throws Exception {
        VulnerabilityDto cveB = vuln("CVE-2024-0040", AXIOS_PURL);

        UUID trunkV10Uuid = UUID.fromString("00000000-0000-0000-0000-000000000410");
        UUID featAuth1Uuid = UUID.fromString("00000000-0000-0000-0000-000000000411");
        UUID siblingExp1Uuid = UUID.fromString("00000000-0000-0000-0000-000000000412");
        ReleaseData trunkV10 = rdMock(trunkV10Uuid, TRUNK_BRANCH, "1.0",
                "2026-01-01T00:00:00Z", metricsWith());
        ReleaseData featAuth1 = rdMock(featAuth1Uuid, FEATURE_BRANCH, "1.0-auth1",
                "2026-02-01T00:00:00Z", metricsWith());                  // target — never had CVE-B
        // Sibling branch had CVE-B and resolved it — but lineage walk for `featAuth1` must not visit it.
        ReleaseData siblingExp1 = rdMock(siblingExp1Uuid, SIBLING_BRANCH, "1.0-exp1",
                "2026-02-15T00:00:00Z", metricsWith());

        SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
        BranchService branchService = mock(BranchService.class);
        GetComponentService getComponentService = mock(GetComponentService.class);
        when(sharedReleaseService.listReleaseDataOfBranch(eq(FEATURE_BRANCH), anyInt(), anyBoolean()))
                .thenReturn(List.of(featAuth1));
        when(sharedReleaseService.listReleaseDataOfBranch(eq(TRUNK_BRANCH), anyInt(), anyBoolean()))
                .thenReturn(List.of(trunkV10));
        // Note: the SIBLING_BRANCH stub is intentionally NOT wired — the test asserts the walk
        // never asks for its releases.
        when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                eq(FEATURE_BRANCH), eq(featAuth1Uuid), any(ReleaseData.class), any(), any()))
                .thenReturn(trunkV10Uuid);
        when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                eq(TRUNK_BRANCH), any(UUID.class), any(ReleaseData.class), any(), any()))
                .thenReturn(null);
        when(sharedReleaseService.getReleaseData(eq(trunkV10Uuid))).thenReturn(Optional.of(trunkV10));

        FindingComparisonService service =
                new FindingComparisonService(branchService, sharedReleaseService, getComponentService);
        List<HistoricallyResolvedFinding> result = service.findHistoricallyResolvedForRelease(
                featAuth1, false, null);

        // Sibling resolution must not appear.
        assertTrue(result.stream().noneMatch(f -> "CVE-2024-0040".equals(f.vulnerability().vulnId())),
                "Sibling-branch resolutions must not appear in the target's lineage");
        // The unused sibling fixture is here to make the test's intent clear; we don't verify it.
        assertTrue(siblingExp1.getUuid() != null);
    }

    // ---- U6: cutOffDate before resolution excludes the resolution ----

    @Test
    void cutOffDate_beforeResolution_excludesResolution() throws Exception {
        VulnerabilityDto cveA = vuln("CVE-2024-0050", LODASH_PURL);

        ReleaseData v10 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000510"),
                TRUNK_BRANCH, "1.0", "2026-01-01T00:00:00Z", metricsWith(cveA));
        ReleaseData v15 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000515"),
                TRUNK_BRANCH, "1.5", "2026-03-01T00:00:00Z", metricsWith()); // resolution date

        SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
        BranchService branchService = mock(BranchService.class);
        GetComponentService getComponentService = mock(GetComponentService.class);
        when(sharedReleaseService.listReleaseDataOfBranch(eq(TRUNK_BRANCH), anyInt(), anyBoolean()))
                .thenReturn(List.of(v15, v10));
        when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                eq(TRUNK_BRANCH), any(UUID.class), any(ReleaseData.class), any(), any()))
                .thenReturn(null);

        FindingComparisonService service =
                new FindingComparisonService(branchService, sharedReleaseService, getComponentService);

        ZonedDateTime cutOff = ZonedDateTime.parse("2026-02-01T00:00:00Z");   // BEFORE v1.5's createdDate
        List<HistoricallyResolvedFinding> result = service.findHistoricallyResolvedForRelease(v15, false, cutOff);

        assertTrue(result.isEmpty(),
                "Resolution after cutoff must be filtered out (the v1.5 release is itself dropped)");
    }

    // ---- U7: cutOffDate after resolution includes the resolution ----

    @Test
    void cutOffDate_afterResolution_includesResolution() throws Exception {
        VulnerabilityDto cveA = vuln("CVE-2024-0051", LODASH_PURL);

        ReleaseData v10 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000520"),
                TRUNK_BRANCH, "1.0", "2026-01-01T00:00:00Z", metricsWith(cveA));
        ReleaseData v15 = rdMock(UUID.fromString("00000000-0000-0000-0000-000000000525"),
                TRUNK_BRANCH, "1.5", "2026-03-01T00:00:00Z", metricsWith());

        SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
        BranchService branchService = mock(BranchService.class);
        GetComponentService getComponentService = mock(GetComponentService.class);
        when(sharedReleaseService.listReleaseDataOfBranch(eq(TRUNK_BRANCH), anyInt(), anyBoolean()))
                .thenReturn(List.of(v15, v10));
        when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                eq(TRUNK_BRANCH), any(UUID.class), any(ReleaseData.class), any(), any()))
                .thenReturn(null);

        FindingComparisonService service =
                new FindingComparisonService(branchService, sharedReleaseService, getComponentService);

        ZonedDateTime cutOff = ZonedDateTime.parse("2026-04-01T00:00:00Z");   // AFTER v1.5's createdDate
        List<HistoricallyResolvedFinding> result = service.findHistoricallyResolvedForRelease(v15, false, cutOff);

        assertEquals(1, result.size(), "Resolution before cutoff must be included");
    }

    // ---- U8: product recursion — union across child component releases ----

    @Test
    void product_recursesIntoChildren_andUnionsResults() throws Exception {
        VulnerabilityDto cveA = vuln("CVE-2024-0060", LODASH_PURL);
        VulnerabilityDto cveB = vuln("CVE-2024-0061", AXIOS_PURL);

        UUID childABranch = UUID.fromString("00000000-0000-0000-0000-0000000006a0");
        UUID childBBranch = UUID.fromString("00000000-0000-0000-0000-0000000006b0");
        UUID productBranch = UUID.fromString("00000000-0000-0000-0000-0000000006c0");

        UUID childAOldUuid = UUID.fromString("00000000-0000-0000-0000-000000000610");
        UUID childAFixUuid = UUID.fromString("00000000-0000-0000-0000-000000000611");
        UUID childBOldUuid = UUID.fromString("00000000-0000-0000-0000-000000000612");
        UUID childBFixUuid = UUID.fromString("00000000-0000-0000-0000-000000000613");
        UUID productUuid   = UUID.fromString("00000000-0000-0000-0000-0000000006ff");

        ReleaseData childAOld = rdMock(childAOldUuid, childABranch, "1.0",
                "2026-01-01T00:00:00Z", metricsWith(cveA));
        ReleaseData childAFix = rdMock(childAFixUuid, childABranch, "1.5",
                "2026-02-01T00:00:00Z", metricsWith());
        ReleaseData childBOld = rdMock(childBOldUuid, childBBranch, "1.0",
                "2026-01-15T00:00:00Z", metricsWith(cveB));
        ReleaseData childBFix = rdMock(childBFixUuid, childBBranch, "1.6",
                "2026-02-15T00:00:00Z", metricsWith());
        ReleaseData product   = rdMock(productUuid, productBranch, "p-1.0",
                "2026-03-01T00:00:00Z", metricsWith());

        SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
        BranchService branchService = mock(BranchService.class);
        GetComponentService getComponentService = mock(GetComponentService.class);

        // Stub branches.
        when(sharedReleaseService.listReleaseDataOfBranch(eq(childABranch), anyInt(), anyBoolean()))
                .thenReturn(List.of(childAFix, childAOld));
        when(sharedReleaseService.listReleaseDataOfBranch(eq(childBBranch), anyInt(), anyBoolean()))
                .thenReturn(List.of(childBFix, childBOld));
        when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                eq(childABranch), any(UUID.class), any(ReleaseData.class), any(), any()))
                .thenReturn(null);
        when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                eq(childBBranch), any(UUID.class), any(ReleaseData.class), any(), any()))
                .thenReturn(null);

        // Product type lookup.
        UUID productComponentUuid = UUID.fromString("00000000-0000-0000-0000-0000000006d0");
        when(product.getComponent()).thenReturn(productComponentUuid);
        ComponentData productCompData = componentDataMock(ComponentType.PRODUCT);
        when(getComponentService.getComponentData(productComponentUuid))
                .thenReturn(Optional.of(productCompData));

        // unwindReleaseDependencies returns the two child releases.
        when(sharedReleaseService.unwindReleaseDependencies(eq(product)))
                .thenReturn(new java.util.LinkedHashSet<>(List.of(childAFix, childBFix)));

        FindingComparisonService service =
                new FindingComparisonService(branchService, sharedReleaseService, getComponentService);

        List<HistoricallyResolvedFinding> result = service.findHistoricallyResolvedForRelease(
                product, /*recurseChildren=*/ true, /*cutOffDate=*/ null);

        assertEquals(2, result.size(), "Two child components, each with one resolved CVE -> two entries");
        assertTrue(result.stream().anyMatch(f -> "CVE-2024-0060".equals(f.vulnerability().vulnId())));
        assertTrue(result.stream().anyMatch(f -> "CVE-2024-0061".equals(f.vulnerability().vulnId())));
    }

    // ---- U9: product recursion — same CVE on multiple children dedups to latest ----

    @Test
    void product_sameCveResolvedOnTwoChildren_dedupsToLatest() throws Exception {
        VulnerabilityDto cveSame = vuln("CVE-2024-0070", LODASH_PURL);

        UUID childABranch = UUID.fromString("00000000-0000-0000-0000-0000000007a0");
        UUID childBBranch = UUID.fromString("00000000-0000-0000-0000-0000000007b0");
        UUID productBranch = UUID.fromString("00000000-0000-0000-0000-0000000007c0");

        UUID childAOldUuid = UUID.fromString("00000000-0000-0000-0000-000000000710");
        UUID childAFixUuid = UUID.fromString("00000000-0000-0000-0000-000000000711");
        UUID childBOldUuid = UUID.fromString("00000000-0000-0000-0000-000000000712");
        UUID childBFixUuid = UUID.fromString("00000000-0000-0000-0000-000000000713");
        UUID productUuid   = UUID.fromString("00000000-0000-0000-0000-0000000007ff");

        // Child A resolves earlier (Feb 1); Child B resolves later (Apr 1) — B should win.
        ReleaseData childAOld = rdMock(childAOldUuid, childABranch, "1.0",
                "2026-01-01T00:00:00Z", metricsWith(cveSame));
        ReleaseData childAFix = rdMock(childAFixUuid, childABranch, "1.5",
                "2026-02-01T00:00:00Z", metricsWith());
        ReleaseData childBOld = rdMock(childBOldUuid, childBBranch, "1.0",
                "2026-03-01T00:00:00Z", metricsWith(cveSame));
        ReleaseData childBFix = rdMock(childBFixUuid, childBBranch, "1.6",
                "2026-04-01T00:00:00Z", metricsWith());
        ReleaseData product   = rdMock(productUuid, productBranch, "p-1.0",
                "2026-05-01T00:00:00Z", metricsWith());

        SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
        BranchService branchService = mock(BranchService.class);
        GetComponentService getComponentService = mock(GetComponentService.class);

        when(sharedReleaseService.listReleaseDataOfBranch(eq(childABranch), anyInt(), anyBoolean()))
                .thenReturn(List.of(childAFix, childAOld));
        when(sharedReleaseService.listReleaseDataOfBranch(eq(childBBranch), anyInt(), anyBoolean()))
                .thenReturn(List.of(childBFix, childBOld));
        when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                eq(childABranch), any(UUID.class), any(ReleaseData.class), any(), any()))
                .thenReturn(null);
        when(sharedReleaseService.findPreviousReleasesOfBranchForRelease(
                eq(childBBranch), any(UUID.class), any(ReleaseData.class), any(), any()))
                .thenReturn(null);

        UUID productComponentUuid = UUID.fromString("00000000-0000-0000-0000-0000000007d0");
        when(product.getComponent()).thenReturn(productComponentUuid);
        ComponentData productCompData = componentDataMock(ComponentType.PRODUCT);
        when(getComponentService.getComponentData(productComponentUuid))
                .thenReturn(Optional.of(productCompData));
        when(sharedReleaseService.unwindReleaseDependencies(eq(product)))
                .thenReturn(new java.util.LinkedHashSet<>(List.of(childAFix, childBFix)));

        FindingComparisonService service =
                new FindingComparisonService(branchService, sharedReleaseService, getComponentService);

        List<HistoricallyResolvedFinding> result = service.findHistoricallyResolvedForRelease(
                product, true, null);

        assertEquals(1, result.size(), "Same CVE resolved on multiple children must collapse to one entry");
        assertEquals(childBFix.getUuid(), result.get(0).resolvingReleaseUuid(),
                "Latest resolution wins");
    }
}
