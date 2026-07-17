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

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.dto.FindingChangesWithAttribution;
import io.reliza.dto.VulnerabilityWithAttribution;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.model.FindingChangeEvent;
import io.reliza.model.FindingChangeEvent.FindingKind;
import io.reliza.repositories.MetricsAuditRepository;

/**
 * Unit tests for the COMPONENT-scope window posture-diff
 * ({@link FindingComparisonService#computePostureDiff} driven with a SINGLE-entry component map,
 * board task #38 phase 3).
 *
 * <p>Clones {@link OrgPostureDiffTest}'s mock harness (from-endpoint reconstructed by reverse-replaying
 * finding_change_events onto current metrics, current metrics for the to-endpoint). The difference is
 * scope: the map holds exactly ONE component, so {@code totalComponents == 1}
 * and the cross-component-only flags (isInheritedInAllComponents, cross-component New suppression)
 * degrade to no-ops. {@code computePostureDiff} is the scope-neutral core the org delegator also calls,
 * so these tests validate the component wiring against the same engine.
 */
@ExtendWith(MockitoExtension.class)
public class ComponentPostureDiffTest {

    private static final UUID ORG = UUID.fromString("b8e7c851-0000-0000-0000-000000000001");
    private static final UUID COMPONENT_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID BRANCH_A = UUID.fromString("00000000-0000-0000-0000-0000000000ba");
    private static final UUID BRANCH_A2 = UUID.fromString("00000000-0000-0000-0000-0000000000c2");
    private static final UUID REL_FROM = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID REL_TO = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final UUID REL_A2_IN = UUID.fromString("00000000-0000-0000-0000-0000000000f4");

    private static final String LODASH = "pkg:npm/lodash@4.17.20";

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

    // ---- fixtures (cloned from OrgPostureDiffTest) ----

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

    private static FindingChangeEvent appeared(UUID releaseUuid, String vulnId, String purl,
            VulnerabilitySeverity sev, ZonedDateTime date) {
        FindingChangeEvent ev = new FindingChangeEvent();
        ev.setOrg(ORG);
        ev.setReleaseUuid(releaseUuid);
        ev.setChangeDate(date);
        ev.setChangeKind(FindingChangeKind.APPEARED);
        ev.setFindingKind(FindingKind.VULNERABILITY);
        ev.setFindingKey(vulnId + "|" + (purl != null ? purl : ""));
        ev.setVulnId(vulnId);
        ev.setPurl(purl);
        if (sev != null) ev.setSeverity(sev.name());
        return ev;
    }

    private void stubNoEvents() {
        lenient().when(findingDimBackfillService.hydrateInRangeV3(any(), any(), any(), any()))
                .thenReturn(List.of());
    }

    private VulnerabilityWithAttribution findVuln(FindingChangesWithAttribution result, String vulnId) {
        return result.vulnerabilities().stream()
                .filter(v -> vulnId.equals(v.vulnId())).findFirst().orElse(null);
    }

    private FindingChangesWithAttribution run(Map<UUID, List<ReleaseData>> componentReleases) {
        Map<UUID, String> names = Map.of(COMPONENT_A, "comp-a");
        return service().computePostureDiff(ORG, componentReleases, names,
                new HashMap<>(), FROM, TO);
    }

    // ---- (a) empty-window: only release is BEFORE the window -> StillPresent ----

    /**
     * A component whose ONLY release is PRE-window (no in-window release) -- the single-entry map the
     * empty-window bypass in ChangeLogService now produces. The pre-window release is the from-anchor and,
     * with nothing newer, also the to-anchor, so its findings are present at BOTH endpoints. They must
     * classify as StillPresent (isStillPresent=true), NOT new-to-component (isNewToOrganization=false) and
     * NOT net-appeared/resolved. This proves the empty-window fix surfaces posture even with no in-window
     * release.
     */
    @Test
    void onlyPreWindowRelease_findingIsStillPresent_notNewToComponent() {
        VulnerabilityDto cve = vuln("CVE-2026-7001", LODASH, VulnerabilitySeverity.HIGH, false);
        // Single pre-window release; current (=to) metrics carry the CVE; from reconstructs to the CVE too.
        ReleaseData preWindow = release(REL_FROM, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));
        stubNoEvents();

        Map<UUID, List<ReleaseData>> componentReleases = Map.of(COMPONENT_A, List.of(preWindow));
        FindingChangesWithAttribution result = run(componentReleases);

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-7001");
        assertNotNull(v, "pre-window finding present at both endpoints -> in the component posture-diff");
        assertTrue(v.isStillPresent(), "present at both endpoints via the pre-window anchor -> StillPresent");
        assertFalse(v.isNetAppeared(), "no set-membership change -> not net-appeared");
        assertFalse(v.isNetResolved());
        assertFalse(v.orgContext().isNewToOrganization(),
                "present at `from` via the pre-window release -> NOT new-to-component");
        assertEquals(0, result.totalAppeared());
        assertEquals(0, result.totalResolved());
    }

    // ---- (a2) BRANCH-scoped empty window: single branch, only a pre-window release -> StillPresent ----

    /**
     * BRANCH-scope empty-window bypass (board task #38, phase 3 -- branch path). When a specific branch is
     * viewed and it has NO in-window release but a pre-window from-anchor,
     * {@code ChangeLogService.computeBranchPostureDiff} builds a single-entry component map whose ONLY
     * releases belong to THAT ONE branch (no enumeration of the component's other branches) and delegates to
     * {@code computePostureDiff}. This asserts that shape: the branch's pre-window finding is the from-anchor
     * and (nothing newer) the to-anchor, so it is present at BOTH endpoints -- StillPresent
     * (isStillPresent=true), NOT new-to-organization (isNewToOrganization=false), and not net
     * appeared/resolved. Proves the branch empty-window bypass surfaces posture with no in-window release.
     */
    @Test
    void branchScoped_onlyPreWindowRelease_findingIsStillPresent_notNewToOrganization() {
        VulnerabilityDto cve = vuln("CVE-2026-7010", LODASH, VulnerabilitySeverity.HIGH, false);
        // The single branch's only release is pre-window; it is both the from- and to-anchor.
        ReleaseData preWindow = release(REL_FROM, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));
        stubNoEvents();

        // Branch-scoped map: single component entry, releases restricted to the ONE viewed branch -- the
        // exact shape ChangeLogService.computeBranchPostureDiff produces (no other-branch enumeration).
        Map<UUID, List<ReleaseData>> branchScoped = Map.of(COMPONENT_A, List.of(preWindow));
        FindingChangesWithAttribution result = run(branchScoped);

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-7010");
        assertNotNull(v, "branch pre-window finding present at both endpoints -> in the branch posture-diff");
        assertTrue(v.isStillPresent(),
                "present at both branch endpoints via the pre-window anchor -> StillPresent");
        assertFalse(v.orgContext().isNewToOrganization(),
                "present at `from` via the branch's pre-window release -> NOT new-to-organization");
        assertFalse(v.isNetAppeared(), "no set-membership change on the branch -> not net-appeared");
        assertFalse(v.isNetResolved());
        assertEquals(0, result.totalAppeared());
        assertEquals(0, result.totalResolved());
    }

    // ---- (b) present at from + newly on a second branch in-window -> StillPresent ----

    /**
     * A finding present at {@code from} on branch a1 (via a pre-window anchor) that NEWLY appears on a
     * second branch a2 (forked mid-window, no from-baseline) INSIDE the window. Within the single
     * component the finding is present at BOTH endpoints (inherited on a1), so the mid-window appearance on
     * a2 must NOT be classified as new-to-component: it is StillPresent, not net-appeared.
     */
    @Test
    void presentAtFrom_newlyOnSecondBranchInWindow_isStillPresent_notNewToComponent() {
        VulnerabilityDto cve = vuln("CVE-2026-7002", LODASH, VulnerabilitySeverity.CRITICAL, false);

        // Branch a1: pre-window anchor carrying the CVE at both endpoints.
        ReleaseData a1PreWindow = release(REL_FROM, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));

        // Branch a2: forked mid-window -> no from-anchor; its only in-window release carries the CVE.
        ReleaseData a2InWindow = release(REL_A2_IN, BRANCH_A2, "2.0",
                ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics(cve));
        lenient().when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(BRANCH_A2, FROM))
                .thenReturn(Optional.empty());
        stubNoEvents();

        Map<UUID, List<ReleaseData>> componentReleases = new LinkedHashMap<>();
        componentReleases.put(COMPONENT_A, List.of(a2InWindow, a1PreWindow));
        FindingChangesWithAttribution result = run(componentReleases);

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-7002");
        assertNotNull(v, "CVE present at both component endpoints -> in the posture-diff");
        assertFalse(v.orgContext().isNewToOrganization(),
                "already present at `from` via branch a1 -> NOT new-to-component");
        assertFalse(v.isNetAppeared(),
                "present at both component endpoints -> the a2 mid-window appearance is not net-New");
        assertTrue(v.isStillPresent(), "inherited across the window at component scope -> StillPresent");
        assertEquals(0, result.totalAppeared(),
                "the forked-branch appearance must not inflate totalAppeared");
    }

    // ---- (c) single component -> cross-component-only flags are false ----

    /**
     * With exactly ONE component in the map, {@code totalComponents == 1}, so the cross-component-only flag
     * isInheritedInAllComponents (which requires {@code totalComponents > 1}) must be false even for a
     * finding that is inherited across the window in that single component.
     */
    @Test
    void singleComponent_inheritedFinding_isInheritedInAllComponentsIsFalse() {
        VulnerabilityDto cve = vuln("CVE-2026-7003", LODASH, VulnerabilitySeverity.MEDIUM, false);
        ReleaseData preWindow = release(REL_TO, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));
        stubNoEvents();

        Map<UUID, List<ReleaseData>> componentReleases = Map.of(COMPONENT_A, List.of(preWindow));
        FindingChangesWithAttribution result = run(componentReleases);

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-7003");
        assertNotNull(v);
        assertTrue(v.isStillPresent(), "inherited finding -> StillPresent");
        assertFalse(v.orgContext().isInheritedInAllComponents(),
                "totalComponents==1 -> isInheritedInAllComponents must be false (requires > 1 component)");
    }

    // ---- (d) re-scan New at COMPONENT scope, reconstructed via reverse-replay (per-call, null prefetch) ----

    /**
     * COMPONENT-scope reverse-replay: the single release's CURRENT metrics carry the CVE, but a re-scan
     * APPEARED it in-window (an APPEARED event after {@code from}). The component path takes the per-call
     * (null-prefetch) reconstruction, so this exercises {@code reconstructLiveMetricsAt}'s single-release
     * {@code findInRange} branch -- reverse-replay must remove the CVE from the from-posture, yielding net-New.
     * Guards that the component scope actually reverse-replays events (not just the no-event still-present path).
     */
    @Test
    void reScanAppearedInWindow_componentScope_isNetNew() {
        VulnerabilityDto cve = vuln("CVE-2026-7004", LODASH, VulnerabilitySeverity.HIGH, false);
        ReleaseData rel = release(REL_TO, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve)); // current has the CVE
        // CVE appeared at 2026-06-10 (> from) -> reverse-replay removes it from the from-state.
        lenient().when(findingDimBackfillService.hydrateInRangeV3(any(), any(), any(), any()))
                .thenReturn(List.of(appeared(REL_TO, "CVE-2026-7004", LODASH, VulnerabilitySeverity.HIGH,
                        ZonedDateTime.parse("2026-06-10T00:00:00Z"))));

        FindingChangesWithAttribution result = run(Map.of(COMPONENT_A, List.of(rel)));

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-7004");
        assertNotNull(v);
        assertTrue(v.isNetAppeared(), "absent in reverse-replayed from-posture, present at to -> net-New");
        assertTrue(v.orgContext().isNewToOrganization());
        assertEquals(1, result.totalAppeared());
        assertEquals(0, result.totalResolved());
    }

    // ---- retired (END_OF_SUPPORT+) releases leave the posture ----

    /**
     * The negative contrast to {@link #onlyPreWindowRelease_findingIsStillPresent_notNewToComponent}:
     * an identical single pre-window release, but END_OF_LIFE, must be dropped from the posture-diff
     * entirely (retired releases leave the board). Its finding does not appear at either endpoint, so
     * there is no StillPresent/appeared/resolved classification for it.
     */
    @Test
    void retiredBranchTip_excludedFromPosture() {
        stubNoEvents();
        ReleaseData retired = release(REL_FROM, BRANCH_A, "1.0",
                FROM.minusDays(5), metrics(vuln("CVE-2026-9999", LODASH, VulnerabilitySeverity.HIGH, false)));
        lenient().when(retired.getLifecycle()).thenReturn(ReleaseLifecycle.END_OF_LIFE);

        FindingChangesWithAttribution result = run(Map.of(COMPONENT_A, List.of(retired)));

        assertNull(findVuln(result, "CVE-2026-9999"),
                "a retired (END_OF_LIFE) branch tip must be excluded from the posture-diff");
        assertEquals(0, result.totalAppeared());
        assertEquals(0, result.totalResolved());
    }

    /**
     * Threshold guard: END_OF_DISTRIBUTION is still supported (below END_OF_SUPPORT), so the same
     * pre-window release MUST remain in the posture (StillPresent) -- confirms the retirement cutoff
     * is END_OF_SUPPORT, not any earlier end-of-* state.
     */
    @Test
    void endOfDistributionBranchTip_staysInPosture() {
        stubNoEvents();
        ReleaseData eod = release(REL_FROM, BRANCH_A, "1.0",
                FROM.minusDays(5), metrics(vuln("CVE-2026-9998", LODASH, VulnerabilitySeverity.HIGH, false)));
        lenient().when(eod.getLifecycle()).thenReturn(ReleaseLifecycle.END_OF_DISTRIBUTION);

        FindingChangesWithAttribution result = run(Map.of(COMPONENT_A, List.of(eod)));

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-9998");
        assertNotNull(v, "END_OF_DISTRIBUTION is still supported and must stay in the posture-diff");
        assertTrue(v.isStillPresent());
    }

    /**
     * Covers the reconstructBranchPosture from-baseline re-fetch guard: the branch survives the in-window
     * filter via an in-window NON-retired release, but the FROM endpoint has no in-window release <= from,
     * so it re-fetches the from-baseline via getBranchLatestReleaseAtOrBeforeDate -- a lookup that drops
     * only CANCELLED/REJECTED. Stubbed to a RETIRED baseline, the guard must drop it: the finding is then
     * absent at from and present at to -> net-appeared (without the guard it would read StillPresent).
     */
    @Test
    void retiredOutOfWindowFromBaseline_isExcluded_findingNetAppeared() {
        stubNoEvents();
        VulnerabilityDto cve = vuln("CVE-2026-9997", LODASH, VulnerabilitySeverity.HIGH, false);
        // In-window, non-retired tip -> survives the choke -> reconstructBranchPosture runs for this branch.
        ReleaseData inWindow = release(REL_TO, BRANCH_A, "2.0",
                ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics(cve));
        // No in-window release <= from, so the FROM endpoint re-fetches the baseline; stub it RETIRED.
        ReleaseData retiredBaseline = release(REL_FROM, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));
        lenient().when(retiredBaseline.getLifecycle()).thenReturn(ReleaseLifecycle.END_OF_LIFE);
        lenient().when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(BRANCH_A, FROM))
                .thenReturn(Optional.of(retiredBaseline));

        FindingChangesWithAttribution result = run(Map.of(COMPONENT_A, List.of(inWindow)));

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-9997");
        assertNotNull(v);
        assertTrue(v.isNetAppeared(),
                "retired out-of-window from-baseline excluded -> absent at from, present at to -> net-appeared");
        assertEquals(1, result.totalAppeared());
    }
}
