/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.dto.FindingChangesWithAttribution;
import io.reliza.dto.VulnerabilityWithAttribution;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.FindingAnalyticsParticipation;
import io.reliza.model.ComponentData;
import io.reliza.model.FindingChangeEvent;
import io.reliza.model.FindingChangeEvent.FindingKind;
import io.reliza.model.OrganizationData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.repositories.MetricsAuditRepository;

/**
 * Unit tests for the POSTURE-ENDPOINT DIFF rollup
 * ({@link FindingComparisonService#computeOrgPostureDiff}, board task #38 phase 3).
 *
 * <p>Each test pins two window endpoints [from, to] and controls the metrics live at each endpoint via
 * (a) the anchor release's current metrics (to-endpoint, ~now) and (b) reverse-replay of mocked
 * {@code finding_change_events} onto those current metrics (from-endpoint) -- NO {@code metrics_audit}.
 * The KEV / severity overlay is driven by the same mocked events. The endpoints then flow through the
 * SAME {@code computeOrgFindingFlags} / {@code buildAttributedFindings} the legacy path uses, so bucket
 * assertions here validate the re-sourcing, not a rewritten flag engine.
 */
@ExtendWith(MockitoExtension.class)
public class OrgPostureDiffTest {

    private static final UUID ORG = UUID.fromString("b8e7c851-0000-0000-0000-000000000001");
    private static final UUID COMPONENT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BRANCH_A = UUID.fromString("00000000-0000-0000-0000-0000000000ba");
    private static final UUID REL_FROM = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID REL_TO = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

    private static final UUID COMPONENT_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID BRANCH_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
    private static final UUID REL_B_TO = UUID.fromString("00000000-0000-0000-0000-0000000000f3");

    private static final String LODASH = "pkg:npm/lodash@4.17.20";
    private static final String AXIOS = "pkg:npm/axios@0.21.1";

    private static final ZonedDateTime FROM = ZonedDateTime.parse("2026-06-01T00:00:00Z");
    private static final ZonedDateTime TO = ZonedDateTime.parse("2026-06-20T00:00:00Z");

    private final MetricsAuditRepository metricsAuditRepository = mock(MetricsAuditRepository.class);
    private final FindingDimBackfillService findingDimBackfillService = mock(FindingDimBackfillService.class);
    private final BranchService branchService = mock(BranchService.class);
    private final SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
    private final GetComponentService getComponentService = mock(GetComponentService.class);
    private final GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);

    private FindingComparisonService service() {
        FindingComparisonService svc = new FindingComparisonService(branchService, sharedReleaseService,
                getComponentService, metricsAuditRepository, getOrganizationService);
        org.springframework.test.util.ReflectionTestUtils.setField(svc, "findingDimBackfillService", findingDimBackfillService);
        return svc;
    }

    /** Opt ORG into a bounded finding-change window so the retention horizon / clamp path is exercised. */
    private void stubBoundedRetention(int days) {
        OrganizationData.Settings settings = new OrganizationData.Settings();
        settings.setFindingChangeRetentionDays(days);
        OrganizationData od = mock(OrganizationData.class);
        lenient().when(od.getSettings()).thenReturn(settings);
        lenient().when(getOrganizationService.getOrganizationData(ORG)).thenReturn(Optional.of(od));
    }

    // ---- fixtures ----

    private static VulnerabilityDto vuln(String id, String purl, VulnerabilitySeverity sev, Boolean kev) {
        return new VulnerabilityDto(purl, id, sev, Set.of(), Set.of(), Set.of(),
                null, null, null, null, null, null, null, null, kev);
    }

    private static ReleaseMetricsDto metrics(VulnerabilityDto... vulns) {
        ReleaseMetricsDto m = new ReleaseMetricsDto();
        m.setVulnerabilityDetails(new LinkedList<>(List.of(vulns)));
        return m;
    }

    private static ReleaseData release(UUID uuid, UUID branch, String version, ZonedDateTime created,
            ReleaseMetricsDto currentMetrics) {
        ReleaseData rd = mock(ReleaseData.class);
        lenient().when(rd.getUuid()).thenReturn(uuid);
        lenient().when(rd.getOrg()).thenReturn(ORG);
        lenient().when(rd.getBranch()).thenReturn(branch);
        lenient().when(rd.getComponent()).thenReturn(COMPONENT_A);
        lenient().when(rd.getVersion()).thenReturn(version);
        lenient().when(rd.getCreatedDate()).thenReturn(created);
        lenient().when(rd.getLifecycle()).thenReturn(ReleaseLifecycle.ASSEMBLED);
        lenient().when(rd.getMetrics()).thenReturn(currentMetrics);
        return rd;
    }

    private static BranchData branchData(UUID uuid, StatusEnum status,
            FindingAnalyticsParticipation participation) {
        BranchData bd = mock(BranchData.class);
        lenient().when(bd.getUuid()).thenReturn(uuid);
        lenient().when(bd.getStatus()).thenReturn(status);
        lenient().when(bd.getFindingAnalyticsParticipation()).thenReturn(participation);
        return bd;
    }

    private static FindingChangeEvent kevEvent(String vulnId, String purl, ZonedDateTime date) {
        FindingChangeEvent ev = new FindingChangeEvent();
        ev.setOrg(ORG);
        ev.setReleaseUuid(REL_TO);
        ev.setChangeDate(date);
        ev.setChangeKind(FindingChangeKind.KEV_ADDED);
        ev.setFindingKind(FindingKind.VULNERABILITY);
        ev.setFindingKey(vulnId + "|" + (purl != null ? purl : ""));
        ev.setVulnId(vulnId);
        ev.setPurl(purl);
        return ev;
    }

    private static FindingChangeEvent sevEvent(String vulnId, String purl, ZonedDateTime date, String prevSev) {
        FindingChangeEvent ev = new FindingChangeEvent();
        ev.setOrg(ORG);
        ev.setReleaseUuid(REL_TO);
        ev.setChangeDate(date);
        ev.setChangeKind(FindingChangeKind.SEVERITY_INCREASED);
        ev.setFindingKind(FindingKind.VULNERABILITY);
        ev.setFindingKey(vulnId + "|" + (purl != null ? purl : ""));
        ev.setVulnId(vulnId);
        ev.setPurl(purl);
        ev.setPreviousSeverity(prevSev);
        return ev;
    }

    /** A vuln finding-change event on {@code releaseUuid} for reverse-replay reconstruction. */
    private static FindingChangeEvent vulnEvent(UUID releaseUuid, FindingChangeKind kind, String vulnId,
            String purl, VulnerabilitySeverity sev, Boolean kev, String prevSev, ZonedDateTime date) {
        FindingChangeEvent ev = new FindingChangeEvent();
        ev.setOrg(ORG);
        ev.setReleaseUuid(releaseUuid);
        ev.setChangeDate(date);
        ev.setChangeKind(kind);
        ev.setFindingKind(FindingKind.VULNERABILITY);
        ev.setFindingKey(vulnId + "|" + (purl != null ? purl : ""));
        ev.setVulnId(vulnId);
        ev.setPurl(purl);
        if (sev != null) ev.setSeverity(sev.name());
        ev.setKnownExploited(kev);
        ev.setPreviousSeverity(prevSev);
        return ev;
    }

    private static FindingChangeEvent appeared(UUID releaseUuid, String vulnId, String purl,
            VulnerabilitySeverity sev, ZonedDateTime date) {
        return vulnEvent(releaseUuid, FindingChangeKind.APPEARED, vulnId, purl, sev, false, null, date);
    }

    private static FindingChangeEvent resolved(UUID releaseUuid, String vulnId, String purl,
            VulnerabilitySeverity sev, ZonedDateTime date) {
        return vulnEvent(releaseUuid, FindingChangeKind.RESOLVED, vulnId, purl, sev, false, null, date);
    }

    private void stubNoEvents() {
        lenient().when(findingDimBackfillService.hydrateInRangeV3(any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    private VulnerabilityWithAttribution findVuln(FindingChangesWithAttribution result, String vulnId) {
        return result.vulnerabilities().stream()
                .filter(v -> vulnId.equals(v.vulnId())).findFirst().orElse(null);
    }

    /**
     * Single-branch scenario helper: one from-baseline release (current & live-at-from = fromMetrics)
     * and one to release (current = toMetrics). from is reconstructed via audit; to uses current metrics.
     */
    private FindingChangesWithAttribution runSingleBranch(ReleaseMetricsDto fromMetrics,
            ReleaseMetricsDto toMetrics, List<FindingChangeEvent> events) {
        // Two-anchor topology: the from-anchor relFrom's CURRENT metrics ARE the from-state (no events
        // under its UUID -> reverse-replay is a no-op -> reconstruct@from == fromMetrics); the to-anchor
        // relTo's current metrics are the to-state. Overlay/reconstruction events are supplied via
        // findInRange; those carry releaseUuid=REL_TO and in-window change dates <= TO, so they are grouped
        // under REL_TO and filtered out of the to-endpoint reconstruction (isAfter(TO) == false) while
        // still feeding the worsened overlay. NO metrics_audit involved.
        ReleaseData relFrom = release(REL_FROM, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), fromMetrics);
        ReleaseData relTo = release(REL_TO, BRANCH_A, "2.0",
                ZonedDateTime.parse("2026-06-10T00:00:00Z"), toMetrics);

        lenient().when(findingDimBackfillService.hydrateInRangeV3(any(), any(), any(), any()))
                .thenReturn(events);

        Map<UUID, List<ReleaseData>> componentReleases = Map.of(COMPONENT_A, List.of(relTo, relFrom));
        Map<UUID, String> names = Map.of(COMPONENT_A, "comp-a");
        return service().computeOrgPostureDiff(ORG, componentReleases, names, new HashMap<>(), FROM, TO);
    }

    // ---- bucket tests ----

    @Test
    void newFinding_absentAtFrom_presentAtTo_isNewAndNetAppeared() {
        VulnerabilityDto cve = vuln("CVE-2026-0001", LODASH, VulnerabilitySeverity.HIGH, false);
        FindingChangesWithAttribution result = runSingleBranch(metrics(), metrics(cve), List.of());

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-0001");
        assertNotNull(v);
        assertTrue(v.isNetAppeared(), "absent@from, present@to -> net appeared");
        assertFalse(v.isNetResolved());
        assertTrue(v.orgContext().isNewToOrganization());
        assertEquals(1, result.totalAppeared());
        assertEquals(0, result.totalResolved());
    }

    @Test
    void fullyResolved_presentAtFrom_absentAtTo_isNetResolved() {
        VulnerabilityDto cve = vuln("CVE-2026-0002", LODASH, VulnerabilitySeverity.HIGH, false);
        FindingChangesWithAttribution result = runSingleBranch(metrics(cve), metrics(), List.of());

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-0002");
        assertNotNull(v);
        assertTrue(v.isNetResolved(), "present@from, absent@to -> net resolved");
        assertFalse(v.isNetAppeared());
        assertTrue(v.orgContext().isFullyResolved());
        assertEquals(1, result.totalResolved());
        assertEquals(0, result.totalAppeared());
    }

    @Test
    void stillPresent_presentBothEndpoints_isStillPresentNoNetDelta() {
        VulnerabilityDto cve = vuln("CVE-2026-0003", LODASH, VulnerabilitySeverity.HIGH, false);
        FindingChangesWithAttribution result = runSingleBranch(metrics(cve), metrics(cve), List.of());

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-0003");
        assertNotNull(v);
        assertTrue(v.isStillPresent());
        assertFalse(v.isNetAppeared());
        assertFalse(v.isNetResolved());
        assertEquals(0, result.totalAppeared());
        assertEquals(0, result.totalResolved());
    }

    // ---- KEV annotation ----

    @Test
    void kevAddedInWindow_onStillPresentFinding_setsIsNewlyKev_noNetDelta() {
        // CVE present at BOTH endpoints (no set-membership change), but a KEV_ADDED event fired in-window.
        VulnerabilityDto cveNoKev = vuln("CVE-2026-0100", LODASH, VulnerabilitySeverity.HIGH, false);
        VulnerabilityDto cveKev = vuln("CVE-2026-0100", LODASH, VulnerabilitySeverity.HIGH, true);
        FindingChangeEvent kev = kevEvent("CVE-2026-0100", LODASH,
                ZonedDateTime.parse("2026-06-16T00:00:00Z"));

        FindingChangesWithAttribution result = runSingleBranch(
                metrics(cveNoKev), metrics(cveKev), List.of(kev));

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-0100");
        assertNotNull(v);
        assertTrue(v.isStillPresent());
        assertFalse(v.isNetAppeared(), "KEV badge must not create a New delta");
        assertFalse(v.isNetResolved());
        assertTrue(v.orgContext().isNewlyKev(), "in-window KEV_ADDED on still-present -> isNewlyKev");
        assertEquals(1, result.totalNewlyKev());
        // No double-count into headline buckets.
        assertEquals(0, result.totalAppeared());
        assertEquals(0, result.totalResolved());
    }

    @Test
    void kevAddedInWindow_onNetNewFinding_isJustNew_notNewlyKev() {
        // CVE is net-New (absent@from, present@to) AND has a KEV_ADDED event in-window. A brand-new finding
        // that appears already-KEV is NEW, not "Newly-KEV" -- it belongs in the New bucket only (board task
        // F10: isNetNewlyKev now requires present-at-from, symmetric with isNetSeverityIncreased).
        VulnerabilityDto cveKev = vuln("CVE-2026-0101", LODASH, VulnerabilitySeverity.HIGH, true);
        FindingChangeEvent kev = kevEvent("CVE-2026-0101", LODASH,
                ZonedDateTime.parse("2026-06-16T00:00:00Z"));

        FindingChangesWithAttribution result = runSingleBranch(
                metrics(), metrics(cveKev), List.of(kev));

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-0101");
        assertNotNull(v);
        assertTrue(v.isNetAppeared(), "net-New");
        assertFalse(Boolean.TRUE.equals(v.orgContext().isNewlyKev()),
                "a from-absent finding is New, NOT Newly-KEV (F10)");
        assertEquals(1, result.totalAppeared());
        assertEquals(0, result.totalNewlyKev(), "no Newly-KEV badge for a brand-new finding");
    }

    @Test
    void severityIncreasedInWindow_onStillPresent_setsFlagAndPreviousSeverity() {
        VulnerabilityDto low = vuln("CVE-2026-0200", LODASH, VulnerabilitySeverity.LOW, false);
        VulnerabilityDto high = vuln("CVE-2026-0200", LODASH, VulnerabilitySeverity.HIGH, false);
        FindingChangeEvent sev = sevEvent("CVE-2026-0200", LODASH,
                ZonedDateTime.parse("2026-06-14T00:00:00Z"), "LOW");

        FindingChangesWithAttribution result = runSingleBranch(
                metrics(low), metrics(high), List.of(sev));

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-0200");
        assertNotNull(v);
        assertTrue(v.isStillPresent());
        assertTrue(v.orgContext().isSeverityIncreased());
        assertEquals("LOW", v.orgContext().previousSeverity());
        assertEquals(1, result.totalSeverityIncreased());
        assertEquals(0, result.totalAppeared());
    }

    // ---- no double count: first-scan APPEARED that is ALSO cross-release New counts once ----

    @Test
    void kevEventOnResolvedFinding_notPresentAtTo_isNotBadged() {
        // CVE resolved (present@from, absent@to) but a stale KEV event exists in-window: since it is not
        // present at `to`, it must NOT be badged Newly-KEV (present-at-to guard).
        VulnerabilityDto cve = vuln("CVE-2026-0300", LODASH, VulnerabilitySeverity.HIGH, false);
        FindingChangeEvent kev = kevEvent("CVE-2026-0300", LODASH,
                ZonedDateTime.parse("2026-06-05T00:00:00Z"));

        FindingChangesWithAttribution result = runSingleBranch(
                metrics(cve), metrics(), List.of(kev));

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-0300");
        assertNotNull(v);
        assertTrue(v.isNetResolved());
        assertFalse(Boolean.TRUE.equals(v.orgContext().isNewlyKev()), "resolved finding not badged");
        assertEquals(0, result.totalNewlyKev());
    }

    // ---- retention fallback ----

    @Test
    void retentionEdge_boundedOrg_fromBeforeHorizon_degradesToCurrentMetrics() {
        // For an org that OPTED INTO a bounded window, `from` predating its retention horizon means the
        // pre-horizon events were purged, so the from-posture cannot be reverse-reconstructed and degrades
        // to the from-anchor's CURRENT metrics. An APPEARED event that WOULD make it net-New is ignored
        // because `from` is beyond the horizon -> still-present (accepted degrade-to-today), plus a
        // DISCLOSED clamp. (Under the default full-history policy there is no horizon -- see the companion.)
        stubBoundedRetention(730);
        ZonedDateTime ancientFrom = ZonedDateTime.parse("2020-01-01T00:00:00Z"); // > 730d before now
        ZonedDateTime recentTo = ZonedDateTime.parse("2026-06-20T00:00:00Z");
        VulnerabilityDto cve = vuln("CVE-2026-0400", AXIOS, VulnerabilitySeverity.MEDIUM, false);
        ReleaseData relFrom = release(REL_FROM, BRANCH_A, "1.0",
                ZonedDateTime.parse("2019-05-15T00:00:00Z"), metrics(cve));
        ReleaseData relTo = release(REL_TO, BRANCH_A, "2.0",
                ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics(cve));
        // An APPEARED event exists -- but from is beyond the horizon so reconstruction must NOT trust it.
        lenient().when(findingDimBackfillService.hydrateInRangeV3(any(), any(), any(), any()))
                .thenReturn(List.of(appeared(REL_FROM, "CVE-2026-0400", AXIOS, VulnerabilitySeverity.MEDIUM,
                        ZonedDateTime.parse("2026-06-05T00:00:00Z"))));

        Map<UUID, List<ReleaseData>> componentReleases = Map.of(COMPONENT_A, List.of(relTo, relFrom));
        Map<UUID, String> names = Map.of(COMPONENT_A, "comp-a");
        FindingChangesWithAttribution result = service()
                .computeOrgPostureDiff(ORG, componentReleases, names, new HashMap<>(),
                        ancientFrom, recentTo);

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-0400");
        assertNotNull(v);
        assertTrue(v.isStillPresent(), "degrade-to-today: from beyond horizon uses current metrics -> present both");
        assertFalse(v.isNetAppeared());
        assertEquals(0, result.totalAppeared());
        assertNotNull(result.reconstructionClampedSince(),
                "bounded org, from beyond the event-retention horizon -> reconstruction clamp must be DISCLOSED");
    }

    @Test
    void retentionDisabledByDefault_ancientFromReconstructs_noClamp() {
        // FULL HISTORY (default, no retention setting): there is NO horizon, so an ancient `from` is
        // reverse-reconstructed from the retained events -- the APPEARED event IS trusted, the finding is
        // net-New over the window, and NO clamp is disclosed. This is the completeness win the default buys.
        ZonedDateTime ancientFrom = ZonedDateTime.parse("2020-01-01T00:00:00Z");
        ZonedDateTime recentTo = ZonedDateTime.parse("2026-06-20T00:00:00Z");
        VulnerabilityDto cve = vuln("CVE-2026-0400", AXIOS, VulnerabilitySeverity.MEDIUM, false);
        ReleaseData relFrom = release(REL_FROM, BRANCH_A, "1.0",
                ZonedDateTime.parse("2019-05-15T00:00:00Z"), metrics(cve));
        ReleaseData relTo = release(REL_TO, BRANCH_A, "2.0",
                ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics(cve));
        lenient().when(findingDimBackfillService.hydrateInRangeV3(any(), any(), any(), any()))
                .thenReturn(List.of(appeared(REL_FROM, "CVE-2026-0400", AXIOS, VulnerabilitySeverity.MEDIUM,
                        ZonedDateTime.parse("2026-06-05T00:00:00Z"))));

        Map<UUID, List<ReleaseData>> componentReleases = Map.of(COMPONENT_A, List.of(relTo, relFrom));
        Map<UUID, String> names = Map.of(COMPONENT_A, "comp-a");
        FindingChangesWithAttribution result = service()
                .computeOrgPostureDiff(ORG, componentReleases, names, new HashMap<>(),
                        ancientFrom, recentTo);

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-0400");
        assertNotNull(v);
        assertTrue(v.isNetAppeared(),
                "full history: the in-window APPEARED event is trusted -> net-New over the ancient window");
        assertEquals(1, result.totalAppeared());
        assertNull(result.reconstructionClampedSince(),
                "retention disabled -> no horizon -> no clamp even for an ancient from");
    }

    @Test
    void withinRetention_noClampDisclosed() {
        // A normal recent window (FROM/TO well inside a bounded 730d horizon) must NOT set the clamp.
        stubBoundedRetention(730);
        VulnerabilityDto cve = vuln("CVE-2026-0410", LODASH, VulnerabilitySeverity.HIGH, false);
        FindingChangesWithAttribution result = runSingleBranch(metrics(), metrics(cve), List.of());
        assertNull(result.reconstructionClampedSince(),
                "window within retention -> no clamp disclosure");
    }

    // ---- endpoint reconstruction genuinely reverse-replays events (not just current metrics) ----

    @Test
    void fromEndpoint_reverseReplayed_differsFromReleaseCurrentMetrics() {
        // The from-release's CURRENT metrics contain the CVE, but the metrics LIVE at `from` (reverse-
        // replayed from current) did NOT -- the CVE APPEARED later within the release's own scans (an
        // APPEARED event in-window). A naive current-metrics diff would call this still-present; the
        // posture-diff correctly calls it net-New because reverse-replay removes it from the from-posture.
        VulnerabilityDto cve = vuln("CVE-2026-0500", LODASH, VulnerabilitySeverity.HIGH, false);
        ReleaseData relFrom = release(REL_FROM, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve)); // current has CVE
        ReleaseData relTo = release(REL_TO, BRANCH_A, "2.0",
                ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics(cve));
        // CVE appeared on relFrom at 2026-06-05 (> from) -> reverse-replay removes it from the from-state.
        lenient().when(findingDimBackfillService.hydrateInRangeV3(any(), any(), any(), any()))
                .thenReturn(List.of(appeared(REL_FROM, "CVE-2026-0500", LODASH, VulnerabilitySeverity.HIGH,
                        ZonedDateTime.parse("2026-06-05T00:00:00Z"))));

        Map<UUID, List<ReleaseData>> componentReleases = Map.of(COMPONENT_A, List.of(relTo, relFrom));
        Map<UUID, String> names = Map.of(COMPONENT_A, "comp-a");
        FindingChangesWithAttribution result = service()
                .computeOrgPostureDiff(ORG, componentReleases, names, new HashMap<>(), FROM, TO);

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-0500");
        assertNotNull(v);
        assertTrue(v.isNetAppeared(), "absent in reverse-replayed from-posture -> net New");
        assertEquals(1, result.totalAppeared());
    }

    // ---- B1: HISTORICAL `to` is reconstructed, NOT diffed against current metrics ----

    @Test
    void historicalTo_reconstructedFromAudit_notCurrentMetrics() {
        // `to` (2026-06-15) is HISTORICAL: there is an audit overwrite (rev2 @ 2026-06-25) BETWEEN `to`
        // and now, so the release's CURRENT metrics != the state live at `to`.
        //   audit timeline for the single anchor release REL_TO (created before `from`):
        //     rev0 @ 2026-05-20 (before from) : empty  -> retained-history seed (retention guard off)
        //     rev1 @ 2026-06-05 (from<..<to)  : state live AT from  = {X present}
        //     rev2 @ 2026-06-25 (to<..<now)   : state live AT to    = {X present, Y present}
        //   current metrics (live now)        : {X absent, Y absent}  (both resolved AFTER `to`)
        // Correct posture-diff (TO reconstructed to rev2):
        //   X present@from & present@to  -> still-present (NOT net-New, NOT net-resolved)
        //   Y absent@from  & present@to  -> net-New at to
        // Buggy path (TO == current metrics): X would be present@from, absent@to -> net-RESOLVED (wrong),
        // and Y would be absent both -> vanish. So the assertions below fail under the old behavior.
        // Single anchor release REL_TO (created before `from`). Timeline via reverse-replay from CURRENT:
        //   state@from (06-01) = {X}      ; state@to (06-15) = {X, Y}      ; current (now) = {}
        // Reverse-replayable events on REL_TO:
        //   Y APPEARED @06-05  (so Y is absent before it -> absent@from, present@to)
        //   X RESOLVED @06-25, Y RESOLVED @06-25  (both resolved AFTER `to` -> present@to, absent now)
        // Reconstruct@to (reverse events >06-15): re-add X, re-add Y -> {X,Y}.
        // Reconstruct@from (reverse events >06-01): re-add X,Y then remove Y -> {X}.
        ReleaseMetricsDto currentNow = metrics();          // both resolved after `to`

        ReleaseData relTo = release(REL_TO, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), currentNow);

        lenient().when(findingDimBackfillService.hydrateInRangeV3(any(), any(), any(), any()))
                .thenReturn(List.of(
                        appeared(REL_TO, "CVE-2026-0601", AXIOS, VulnerabilitySeverity.HIGH,
                                ZonedDateTime.parse("2026-06-05T00:00:00Z")),
                        resolved(REL_TO, "CVE-2026-0600", LODASH, VulnerabilitySeverity.HIGH,
                                ZonedDateTime.parse("2026-06-25T00:00:00Z")),
                        resolved(REL_TO, "CVE-2026-0601", AXIOS, VulnerabilitySeverity.HIGH,
                                ZonedDateTime.parse("2026-06-25T00:00:00Z"))));

        Map<UUID, List<ReleaseData>> componentReleases = Map.of(COMPONENT_A, List.of(relTo));
        Map<UUID, String> names = Map.of(COMPONENT_A, "comp-a");
        FindingChangesWithAttribution result = service()
                .computeOrgPostureDiff(ORG, componentReleases, names, new HashMap<>(),
                        FROM, ZonedDateTime.parse("2026-06-15T00:00:00Z"));

        VulnerabilityWithAttribution vx = findVuln(result, "CVE-2026-0600");
        assertNotNull(vx, "X present at both reconstructed endpoints -> in result");
        assertTrue(vx.isStillPresent(), "X present@from & present@to (reconstructed) -> still-present");
        assertFalse(vx.isNetResolved(), "must NOT be net-resolved: it is only resolved AFTER `to`");
        assertFalse(vx.isNetAppeared());

        VulnerabilityWithAttribution vy = findVuln(result, "CVE-2026-0601");
        assertNotNull(vy, "Y present at reconstructed `to` -> in result (would vanish under current-metrics bug)");
        assertTrue(vy.isNetAppeared(), "Y absent@from, present@to (reconstructed) -> net-New");
        assertEquals(1, result.totalAppeared());
        assertEquals(0, result.totalResolved());
    }

    // ---- S1: worsened overlay reflects NET escalation at `to`, not mere in-window event existence ----

    @Test
    void severityUpThenDownWithinWindow_netUnchanged_notBadged() {
        // A SEVERITY_INCREASED event exists in-window, but the from-state and to-state severities are
        // EQUAL (LOW): the severity went up then back down inside the window and nets to no change.
        // It must NOT be badged isSeverityIncreased.
        VulnerabilityDto low = vuln("CVE-2026-0700", LODASH, VulnerabilitySeverity.LOW, false);
        FindingChangeEvent sev = sevEvent("CVE-2026-0700", LODASH,
                ZonedDateTime.parse("2026-06-08T00:00:00Z"), "LOW");

        FindingChangesWithAttribution result = runSingleBranch(
                metrics(low), metrics(low), List.of(sev));

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-0700");
        assertNotNull(v);
        assertTrue(v.isStillPresent());
        assertFalse(Boolean.TRUE.equals(v.orgContext().isSeverityIncreased()),
                "up-then-down nets to no change -> not badged");
        assertEquals(0, result.totalSeverityIncreased());
    }

    @Test
    void kevAddedThenRemovedWithinWindow_netUnchanged_notBadged() {
        // A KEV_ADDED event exists in-window, but KEV is false at BOTH the from-state and the to-state:
        // KEV was added then removed inside the window. It must NOT be badged isNewlyKev.
        VulnerabilityDto noKevFrom = vuln("CVE-2026-0701", LODASH, VulnerabilitySeverity.HIGH, false);
        VulnerabilityDto noKevTo = vuln("CVE-2026-0701", LODASH, VulnerabilitySeverity.HIGH, false);
        FindingChangeEvent kev = kevEvent("CVE-2026-0701", LODASH,
                ZonedDateTime.parse("2026-06-08T00:00:00Z"));

        FindingChangesWithAttribution result = runSingleBranch(
                metrics(noKevFrom), metrics(noKevTo), List.of(kev));

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-0701");
        assertNotNull(v);
        assertFalse(Boolean.TRUE.equals(v.orgContext().isNewlyKev()),
                "KEV added-then-removed nets to false -> not badged");
        assertEquals(0, result.totalNewlyKev());
    }

    // ---- S3: cross-component inherited finding that folds as appeared on a forked branch is NOT Net-New ----

    @Test
    void inheritedOnCompA_appearedOnForkedCompB_notCountedAsNetNew() {
        // Finding Z is INHERITED on component A (present at both endpoints) and APPEARED on component B
        // (a branch forked mid-window, so it has no from-baseline -> absent@from, present@to). The shared
        // flag engine marks it isNetAppeared (appearedIn non-empty on B), but at ORG level it is NOT
        // net-New -- it was already present in A. The posture-diff path must exclude it from totalAppeared.
        VulnerabilityDto z = vuln("CVE-2026-0800", LODASH, VulnerabilitySeverity.HIGH, false);

        // Component A: single release, inherited (Z present throughout -> no events -> from-state =
        // to-state = current = {Z}).
        ReleaseData relA = release(REL_TO, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(z)); // current (=to=from) has Z

        // Component B: forked mid-window -> no release at-or-before `from`; the only release (created after
        // `from`) carries Z at `to`.
        ReleaseData relB = release(REL_B_TO, BRANCH_B, "1.0",
                ZonedDateTime.parse("2026-06-08T00:00:00Z"), metrics(z));
        lenient().when(relB.getComponent()).thenReturn(COMPONENT_B);

        // from anchor lookup for branch B (forked after `from`) -> none.
        lenient().when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(BRANCH_B, FROM))
                .thenReturn(Optional.empty());
        stubNoEvents();

        Map<UUID, List<ReleaseData>> componentReleases = new LinkedHashMap<>();
        componentReleases.put(COMPONENT_A, List.of(relA));
        componentReleases.put(COMPONENT_B, List.of(relB));
        Map<UUID, String> names = Map.of(COMPONENT_A, "comp-a", COMPONENT_B, "comp-b");

        FindingChangesWithAttribution result = service()
                .computeOrgPostureDiff(ORG, componentReleases, names, new HashMap<>(), FROM, TO);

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-0800");
        assertNotNull(v);
        assertFalse(v.orgContext().isNewToOrganization(), "already present in comp-a -> not org-New");
        assertFalse(v.isNetAppeared(), "cross-component inherited -> suppressed from Net-New on posture path");
        assertEquals(0, result.totalAppeared(), "must not inflate totalAppeared with the forked-branch appearance");
    }

    // ---- classification guard: finding present at both endpoints via a SEPARATE branch is StillPresent ----

    /**
     * CLASSIFICATION HALF of the org-rollup correctness fix (board task #38 finding-change-events).
     *
     * <p>Scenario: two components carry the SAME finding CVE-X.
     * <ul>
     *   <li>Component A / branch a1: one release A@1.0 created BEFORE the window {@code from}
     *       (at T-1 = 2026-05-15) carrying CVE-X, and NO release inside [from, to]. Its latest release
     *       {@code <= from} (A@1.0) is the from-anchor; with no newer in-window release it is ALSO the
     *       to-anchor, so A carries CVE-X at BOTH endpoints.</li>
     *   <li>Component B / branch b1: one release B@1.1 created INSIDE the window (at T1 = 2026-06-10),
     *       also carrying CVE-X. b1 forked mid-window so it has no from-baseline
     *       (getBranchLatestReleaseAtOrBeforeDate(b1, from) is empty) -> absent@from, present@to.</li>
     * </ul>
     *
     * <p>This test feeds {@code computeOrgPostureDiff} the COMPLETE map the fixed ChangeLogService
     * enumeration now produces -- component A's from-anchor A@1.0 INCLUDED -- and asserts the correct
     * classification. Because CVE-X is inherited (present at both endpoints) in component A, the shared
     * flag engine must mark it StillPresent / not-new-to-org and suppress the mid-window appearance on
     * component B from Net-New. This guards {@code computeOrgFindingFlags} given a complete map; it is
     * green after the fix and does not depend on the ChangeLogService enumeration edit (it feeds the map
     * directly -- that is intentional: this is the classification-half guard). The enumeration-half guard
     * is {@link #fetchWithFromBaseline_branchWithNoInWindowRelease_stillReturnsFromAnchor()}.
     */
    @Test
    void findingPresentAtBothEndpointsViaSeparateBranch_isStillPresent_notNewToOrg() {
        VulnerabilityDto cveX = vuln("CVE-2026-9001", LODASH, VulnerabilitySeverity.CRITICAL, false);

        // Component A: single pre-window release A@1.0 (from-anchor). With no in-window release it is the
        // to-anchor too, so A carries CVE-X at BOTH endpoints. from is reconstructed via audit -> {CVE-X}.
        ReleaseData relA = release(REL_FROM, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cveX)); // current (=to) has CVE-X
        lenient().when(relA.getComponent()).thenReturn(COMPONENT_A);

        // Component B: forked mid-window -> no from-anchor; its only (in-window) release carries CVE-X.
        ReleaseData relB = release(REL_B_TO, BRANCH_B, "1.1",
                ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics(cveX));
        lenient().when(relB.getComponent()).thenReturn(COMPONENT_B);
        lenient().when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(BRANCH_B, FROM))
                .thenReturn(Optional.empty());

        // CVE-X present throughout on A (no events) -> A's from-endpoint = to-endpoint = current = {CVE-X}.
        // B has no from-anchor -> absent@from, present@to.
        stubNoEvents();

        // COMPLETE map the fixed enumeration produces: component A INCLUDED via its from-anchor A@1.0.
        Map<UUID, List<ReleaseData>> componentReleases = new LinkedHashMap<>();
        componentReleases.put(COMPONENT_A, List.of(relA));
        componentReleases.put(COMPONENT_B, List.of(relB));
        Map<UUID, String> names = Map.of(COMPONENT_A, "comp-a", COMPONENT_B, "comp-b");
        FindingChangesWithAttribution result = service()
                .computeOrgPostureDiff(ORG, componentReleases, names, new HashMap<>(), FROM, TO);

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-9001");
        assertNotNull(v, "CVE-X must appear in the org rollup");
        // TRUE posture: present at both endpoints via A@1.0 -> StillPresent, not New.
        assertFalse(v.orgContext().isNewToOrganization(),
                "CVE-X was already present at `from` via A@1.0 (pre-window release on a separate branch) "
                        + "-> NOT first-occurrence-in-org");
        assertFalse(v.isNetAppeared(),
                "CVE-X present at both org endpoints -> must not be net-New");
        assertTrue(v.isStillPresent(),
                "CVE-X inherited across the window at org level -> StillPresent");
        assertEquals(0, result.totalAppeared(),
                "must not over-count New: the pre-window A@1.0 carried CVE-X at `from`");
    }

    // ---- ENUMERATION guard (the actual fix): fetchReleasesForComponentWithFromBaseline anchors a
    //      branch that has NO in-window release ----

    /**
     * REGRESSION GUARD for the enumeration fix (board task #38 finding-change-events).
     *
     * <p>Exercises {@link ChangeLogService#fetchReleasesForComponentWithFromBaseline} for a component
     * whose only release is PRE-window (a branch with NO in-window release). The fixed method must
     * enumerate ALL active branches (not just those with an in-window release) and pull each branch's
     * latest release {@code <= from} as the from-anchor, so the finding present at {@code from} via that
     * branch is anchored at the endpoint.
     *
     * <p>Pre-fix this test is RED: the old method returned early with an empty list when there was no
     * in-window release ({@code if (inWindow.isEmpty()) return inWindow;}) and derived its branch set from
     * the in-window releases only -- so a branch with no in-window release was never anchored. Post-fix it
     * is GREEN. This is the real regression guard for the map-building half of the fix.
     */
    @Test
    void fetchWithFromBaseline_branchWithNoInWindowRelease_stillReturnsFromAnchor() {
        // No in-window release for the component.
        when(sharedReleaseService.listReleaseDataOfComponentBetweenDates(
                COMPONENT_A, FROM, TO, ReleaseLifecycle.DRAFT)).thenReturn(new ArrayList<>());

        // One active, participating branch on the component.
        BranchData branchA = branchData(BRANCH_A, StatusEnum.ACTIVE, FindingAnalyticsParticipation.INCLUDED);
        when(branchService.listBranchDataOfComponent(COMPONENT_A, null)).thenReturn(List.of(branchA));

        // That branch's latest release <= from is a pre-window release (the from-anchor).
        ReleaseData preWindowAnchor = release(REL_FROM, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics());
        when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(BRANCH_A, FROM))
                .thenReturn(Optional.of(preWindowAnchor));

        ChangeLogService changeLogService = new ChangeLogService();
        ReflectionTestUtils.setField(changeLogService, "sharedReleaseService", sharedReleaseService);
        ReflectionTestUtils.setField(changeLogService, "branchService", branchService);

        List<ReleaseData> result = changeLogService.fetchReleasesForComponentWithFromBaseline(
                COMPONENT_A, FROM, TO);

        assertFalse(result.isEmpty(),
                "a branch with no in-window release must still be anchored at `from`");
        assertTrue(result.stream().anyMatch(r -> REL_FROM.equals(r.getUuid())),
                "the pre-window from-anchor release must be present in the merged result");
    }

    // ---- F2: EXCLUDED-participation branch leak in the Summary rollup ----

    /**
     * REGRESSION GUARD for board task F2 (EXCLUDED-participation branch leak). The component-scope posture
     * enumerator {@link ChangeLogService#fetchReleasesForComponentWithFromBaseline} must drop releases on
     * branches whose {@code findingAnalyticsParticipation == EXCLUDED} -- from BOTH the in-window release
     * set AND the from-anchor set -- so the Summary posture-diff counts the same branch set as the analytics
     * chart / over-time paths. Pre-fix (branch enumeration only) the EXCLUDED branch's in-window release
     * leaks in; post-fix it is dropped while the INCLUDED branch's releases remain.
     */
    @Test
    void fetchWithFromBaseline_excludedBranchReleases_areDropped() {
        UUID relIncludedInWindow = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
        UUID relExcludedInWindow = UUID.fromString("00000000-0000-0000-0000-0000000000e2");

        // Two in-window releases: one on the INCLUDED branch, one on the EXCLUDED branch.
        ReleaseData includedRel = release(relIncludedInWindow, BRANCH_A, "1.1",
                ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics());
        ReleaseData excludedRel = release(relExcludedInWindow, BRANCH_B, "9.9",
                ZonedDateTime.parse("2026-06-11T00:00:00Z"), metrics());
        when(sharedReleaseService.listReleaseDataOfComponentBetweenDates(
                COMPONENT_A, FROM, TO, ReleaseLifecycle.DRAFT))
                .thenReturn(new ArrayList<>(List.of(includedRel, excludedRel)));

        // BRANCH_A INCLUDED, BRANCH_B EXCLUDED (e.g. a TAG branch or operator opt-out).
        BranchData incBranch = branchData(BRANCH_A, StatusEnum.ACTIVE, FindingAnalyticsParticipation.INCLUDED);
        BranchData excBranch = branchData(BRANCH_B, StatusEnum.ACTIVE, FindingAnalyticsParticipation.EXCLUDED);
        when(branchService.listBranchDataOfComponent(COMPONENT_A, null))
                .thenReturn(List.of(incBranch, excBranch));

        // Only the INCLUDED branch is anchored (EXCLUDED branch is skipped as an anchor too).
        ReleaseData includedAnchor = release(REL_FROM, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics());
        lenient().when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(BRANCH_A, FROM))
                .thenReturn(Optional.of(includedAnchor));

        ChangeLogService changeLogService = new ChangeLogService();
        ReflectionTestUtils.setField(changeLogService, "sharedReleaseService", sharedReleaseService);
        ReflectionTestUtils.setField(changeLogService, "branchService", branchService);

        List<ReleaseData> result = changeLogService.fetchReleasesForComponentWithFromBaseline(
                COMPONENT_A, FROM, TO);

        assertTrue(result.stream().anyMatch(r -> relIncludedInWindow.equals(r.getUuid())),
                "INCLUDED-branch in-window release must be kept");
        assertFalse(result.stream().anyMatch(r -> relExcludedInWindow.equals(r.getUuid())),
                "EXCLUDED-branch in-window release must be dropped (F2 leak)");
    }

    /**
     * REGRESSION GUARD for board task F2 at the org-rollup enumerator
     * {@link ChangeLogService#buildOrgPostureReleasesMap}. An EXCLUDED branch's in-window release from the
     * org-wide query must not slip into the posture-releases map even though the org-wide fetch is not itself
     * participation-aware.
     */
    @Test
    void buildOrgPostureReleasesMap_excludedBranchReleases_areDropped() {
        UUID relIncluded = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
        UUID relExcluded = UUID.fromString("00000000-0000-0000-0000-0000000000e4");

        ComponentData componentA = mock(ComponentData.class);
        lenient().when(componentA.getUuid()).thenReturn(COMPONENT_A);
        lenient().when(componentA.getName()).thenReturn("component-a");

        // Org-wide in-window query returns releases on BOTH the INCLUDED and EXCLUDED branch of component A.
        ReleaseData includedRel = release(relIncluded, BRANCH_A, "1.1",
                ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics());
        ReleaseData excludedRel = release(relExcluded, BRANCH_B, "9.9",
                ZonedDateTime.parse("2026-06-11T00:00:00Z"), metrics());
        lenient().when(excludedRel.getComponent()).thenReturn(COMPONENT_A);
        when(sharedReleaseService.listReleaseDataOfOrgBetweenDatesByComponent(
                ORG, FROM, TO, ReleaseLifecycle.DRAFT))
                .thenReturn(new HashMap<>(Map.of(COMPONENT_A, List.of(includedRel, excludedRel))));

        // BRANCH_A INCLUDED + ACTIVE, BRANCH_B EXCLUDED + ACTIVE, both on component A.
        BranchData bA = branchData(BRANCH_A, StatusEnum.ACTIVE, FindingAnalyticsParticipation.INCLUDED);
        BranchData bB = branchData(BRANCH_B, StatusEnum.ACTIVE, FindingAnalyticsParticipation.EXCLUDED);
        lenient().when(bA.getComponent()).thenReturn(COMPONENT_A);
        lenient().when(bB.getComponent()).thenReturn(COMPONENT_A);
        when(branchService.listBranchDataOfOrg(ORG)).thenReturn(List.of(bA, bB));

        // Only BRANCH_A is enumerated for a from-anchor (BRANCH_B is EXCLUDED). No anchor needed for this assertion.
        lenient().when(sharedReleaseService.getBranchLatestReleasesAtOrBeforeDate(any(), any()))
                .thenReturn(new HashMap<>());

        ChangeLogService changeLogService = new ChangeLogService();
        ReflectionTestUtils.setField(changeLogService, "sharedReleaseService", sharedReleaseService);
        ReflectionTestUtils.setField(changeLogService, "branchService", branchService);

        Map<UUID, String> componentNames = new HashMap<>();
        Map<UUID, List<ReleaseData>> map = changeLogService.buildOrgPostureReleasesMap(
                ORG, List.of(componentA), componentNames, FROM, TO);

        List<ReleaseData> forA = map.getOrDefault(COMPONENT_A, List.of());
        assertTrue(forA.stream().anyMatch(r -> relIncluded.equals(r.getUuid())),
                "INCLUDED-branch in-window release must be kept");
        assertFalse(forA.stream().anyMatch(r -> relExcluded.equals(r.getUuid())),
                "EXCLUDED-branch in-window release must be dropped from the org rollup (F2 leak)");
    }

    // ---- representative kev-ui-test dual-run fixture: 4x KEV_ADDED on 16-June ----

    @Test
    void kevUiTestFixture_fourKevAddedInWindow_surfaceAsFourNewlyKev() {
        // Mirror the sandbox org b8e7c851 kev-ui-test@1.0.0 case: 4 findings present at BOTH endpoints,
        // each with a KEV_ADDED event on 2026-06-16. New rollup: 4 Newly-KEV, 0 New, 0 Resolved. Old
        // (set-diff) rollup would surface 0 KEV escalations.
        String[] cveIds = {"CVE-2026-1001", "CVE-2026-1002", "CVE-2026-1003", "CVE-2026-1004"};
        LinkedList<VulnerabilityDto> fromVulns = new LinkedList<>();
        LinkedList<VulnerabilityDto> toVulns = new LinkedList<>();
        LinkedList<FindingChangeEvent> events = new LinkedList<>();
        ZonedDateTime june16 = ZonedDateTime.parse("2026-06-16T12:00:00Z");
        for (String id : cveIds) {
            String purl = "pkg:npm/pkg-" + id + "@1.0.0";
            fromVulns.add(vuln(id, purl, VulnerabilitySeverity.HIGH, false));
            toVulns.add(vuln(id, purl, VulnerabilitySeverity.HIGH, true));
            events.add(kevEvent(id, purl, june16));
        }
        ReleaseMetricsDto fromMetrics = new ReleaseMetricsDto();
        fromMetrics.setVulnerabilityDetails(fromVulns);
        ReleaseMetricsDto toMetrics = new ReleaseMetricsDto();
        toMetrics.setVulnerabilityDetails(toVulns);

        FindingChangesWithAttribution result = runSingleBranch(fromMetrics, toMetrics, events);

        assertEquals(4, result.totalNewlyKev(), "4 in-window KEV_ADDED on still-present findings");
        assertEquals(0, result.totalAppeared(), "no set-membership New");
        assertEquals(0, result.totalResolved(), "no set-membership Resolved");
        long badged = result.vulnerabilities().stream()
                .filter(v -> Boolean.TRUE.equals(v.orgContext().isNewlyKev())).count();
        assertEquals(4, badged);
    }

    // ---- BATCHED prefetch parity (org posture-diff N+1 elimination) ----

    /**
     * PARITY GUARD for the batched reconstruction prefetch (N+1 elimination). Runs the SAME org rollup
     * twice over an identical two-component / two-branch map: once through the per-call path (null
     * prefetch -> per-anchor {@code findingChangeEventRepository.findInRange}) and once through the batched
     * path ({@code prefetchPostureReconstruction} -> one grouped events fetch, consulted by
     * {@code reconstructLiveMetricsAt}). Both must produce IDENTICAL buckets. The {@code findInRange} stub
     * is releaseUuid-aware so both paths see the same per-release events; any divergence is a bug in the
     * prefetch wiring rather than the fixture.
     *
     * <p>Scenario: CVE-X on component A only (present at {@code from}, RESOLVED by {@code to}) -> org-level
     * Resolved; CVE-Y on component B only (present at {@code from} and still at {@code to}) -> StillPresent.
     * Two components, two branches; A's from-endpoint is reverse-replayed from a RESOLVED event -- enough
     * surface to catch a mis-keyed prefetch grouping. The {@code findInRange} stub is releaseUuid-aware so
     * the per-call path (single-release fetch) and the batched prefetch (grouped by release) are fed the
     * same per-release events.
     */
    @Test
    void batchedPrefetch_producesIdenticalBucketsToPerCallPath() {
        VulnerabilityDto cveY = vuln("CVE-2026-8888", AXIOS, VulnerabilitySeverity.HIGH, false);

        // Component A / branch a: CVE-X present at `from`, RESOLVED by `to` (current) -> org-level Resolved.
        ReleaseData relA = release(REL_FROM, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics()); // current(=to) has NO CVE-X
        lenient().when(relA.getComponent()).thenReturn(COMPONENT_A);
        // Component B / branch b: CVE-Y present throughout (no events) -> StillPresent.
        ReleaseData relB = release(REL_B_TO, BRANCH_B, "1.1",
                ZonedDateTime.parse("2026-05-16T00:00:00Z"), metrics(cveY)); // current(=to=from) has CVE-Y
        lenient().when(relB.getComponent()).thenReturn(COMPONENT_B);

        // One RESOLVED event on A: reverse-replay re-adds CVE-X at `from` (present@from), absent@to=current.
        List<FindingChangeEvent> allEvents = List.of(
                resolved(REL_FROM, "CVE-2026-7777", LODASH, VulnerabilitySeverity.HIGH,
                        ZonedDateTime.parse("2026-06-10T00:00:00Z")));
        // releaseUuid-aware: return only events whose release is in the requested set, so the per-call
        // (single-release) and batched (grouped-by-release) paths are fed identically.
        lenient().when(findingDimBackfillService.hydrateInRangeV3(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Collection<UUID> rels = inv.getArgument(1);
                    return allEvents.stream().filter(e -> rels.contains(e.getReleaseUuid())).toList();
                });

        Map<UUID, List<ReleaseData>> componentReleases = new LinkedHashMap<>();
        componentReleases.put(COMPONENT_A, List.of(relA));
        componentReleases.put(COMPONENT_B, List.of(relB));
        Map<UUID, String> names = Map.of(COMPONENT_A, "comp-a", COMPONENT_B, "comp-b");

        FindingComparisonService svc = service();

        // (a) per-call path (null prefetch).
        FindingChangesWithAttribution perCall = svc.computeOrgPostureDiff(
                ORG, componentReleases, names, new HashMap<>(), FROM, TO);

        // (b) batched path via the real prefetch builder over the batch repo stubs.
        FindingComparisonService.PostureReconstructionPrefetch prefetch =
                svc.prefetchPostureReconstruction(componentReleases, FROM, TO);
        FindingChangesWithAttribution batched = svc.computeOrgPostureDiff(
                ORG, componentReleases, names, new HashMap<>(), FROM, TO, prefetch);

        // Identical buckets.
        assertEquals(perCall.totalAppeared(), batched.totalAppeared(), "New count parity");
        assertEquals(perCall.totalResolved(), batched.totalResolved(), "Resolved count parity");
        assertEquals(perCall.totalNewlyKev(), batched.totalNewlyKev(), "Newly-KEV count parity");
        assertEquals(countStillPresent(perCall), countStillPresent(batched), "StillPresent count parity");
        assertEquals(perCall.vulnerabilities().size(), batched.vulnerabilities().size(), "vuln count parity");
        // And the scenario actually exercises a mixed rollup (Resolved on A, StillPresent on B).
        assertEquals(1, batched.totalResolved(), "component A dropped CVE-X by `to` -> Resolved");
        assertEquals(1, countStillPresent(batched), "component B still carries CVE-X at `to` -> StillPresent");
    }

    private static long countStillPresent(FindingChangesWithAttribution r) {
        return r.vulnerabilities().stream().filter(VulnerabilityWithAttribution::isStillPresent).count();
    }

    // ---- drill-down (findingAttributionByDate) filter consistency ----

    @Test
    void drillDownFilter_isCountConsistentAndFullyMaterialized() {
        // A finding present at `to` in TWO branches of one component => presentInCount == 2, which exceeds the
        // K=1 inline preview cap. The single-finding drill-down (keyFilter + cap = MAX) must reproduce the
        // SAME counts and flags as the unfiltered build, keep ONLY the target finding, and materialize the
        // FULL presentIn list (cap lifted). This guards that the inline "+N more" count == the drawer total.
        VulnerabilityDto cve = vuln("CVE-2026-0777", LODASH, VulnerabilitySeverity.HIGH, false);
        UUID branchB = UUID.fromString("00000000-0000-0000-0000-0000000000bb");
        UUID relFromB = UUID.fromString("00000000-0000-0000-0000-0000000000f3");
        UUID relToB = UUID.fromString("00000000-0000-0000-0000-0000000000f4");
        // Present at BOTH endpoints in both branches -> still-present, presentInCount = 2.
        ReleaseData relFromA = release(REL_FROM, BRANCH_A, "1.0", ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));
        ReleaseData relToA = release(REL_TO, BRANCH_A, "2.0", ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics(cve));
        ReleaseData relFromBr = release(relFromB, branchB, "1.0", ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));
        ReleaseData relToBr = release(relToB, branchB, "2.0", ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics(cve));
        stubNoEvents();
        Map<UUID, List<ReleaseData>> componentReleases = Map.of(
                COMPONENT_A, List.of(relToA, relFromA, relToBr, relFromBr));
        Map<UUID, String> names = Map.of(COMPONENT_A, "comp-a");

        FindingChangesWithAttribution unfiltered = service()
                .computeOrgPostureDiff(ORG, componentReleases, names, new HashMap<>(), FROM, TO);
        VulnerabilityWithAttribution vAll = findVuln(unfiltered, "CVE-2026-0777");
        assertNotNull(vAll);
        assertEquals(2, vAll.presentInCount(), "present at `to` in both branches");
        assertEquals(1, vAll.presentIn().size(), "inline preview capped at ATTRIBUTION_PREVIEW_CAP (1)");

        String key = "CVE-2026-0777|" + LODASH;
        FindingChangesWithAttribution filtered = service()
                .computePostureDiff(ORG, componentReleases, names, new HashMap<>(), FROM, TO, key, Integer.MAX_VALUE);

        assertEquals(1, filtered.vulnerabilities().size(), "keyFilter keeps ONLY the target finding");
        VulnerabilityWithAttribution vOne = filtered.vulnerabilities().get(0);
        assertEquals(key, vOne.findingKey());
        // Counts + flags are identical to the unfiltered build (cap-independent).
        assertEquals(vAll.presentInCount(), vOne.presentInCount(), "presentInCount cap-independent");
        assertEquals(vAll.appearedInCount(), vOne.appearedInCount());
        assertEquals(vAll.resolvedInCount(), vOne.resolvedInCount());
        assertEquals(vAll.isStillPresent(), vOne.isStillPresent());
        assertEquals(vAll.isNetAppeared(), vOne.isNetAppeared());
        assertEquals(vAll.isNetResolved(), vOne.isNetResolved());
        // The drill-down materializes the FULL bucket (drawer total == inline count).
        assertEquals(vOne.presentInCount(), vOne.presentIn().size(),
                "drill-down (cap=MAX) materializes the full presentIn list");

        // A filter for a DIFFERENT key yields nothing (no accidental cross-finding leakage).
        FindingChangesWithAttribution other = service()
                .computePostureDiff(ORG, componentReleases, names, new HashMap<>(), FROM, TO,
                        "CVE-DOES-NOT-EXIST|" + LODASH, Integer.MAX_VALUE);
        assertTrue(other.vulnerabilities().isEmpty(), "non-matching keyFilter -> empty");

        // Same filter consistency on the LEGACY (pairwise) path: only the target key, full list, same count.
        FindingChangesWithAttribution legacy = service()
                .compareMetricsAcrossComponents(componentReleases, names, new HashMap<>(), new HashMap<>(),
                        key, Integer.MAX_VALUE);
        assertEquals(1, legacy.vulnerabilities().size(), "legacy keyFilter keeps ONLY the target finding");
        VulnerabilityWithAttribution vLegacy = legacy.vulnerabilities().get(0);
        assertEquals(key, vLegacy.findingKey());
        assertEquals(vLegacy.presentInCount(), vLegacy.presentIn().size(),
                "legacy drill-down (cap=MAX) materializes the full presentIn list");
    }
}
