/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.common.Utils;
import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.dto.ChangelogRecords.MetricsRevisionFindingChange;
import io.reliza.model.MetricsAudit;
import io.reliza.model.MetricsAudit.MetricsEntityType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.model.dto.ReleaseMetricsDto.WeaknessDto;
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.service.FindingComparisonService.AuditChangeReleaseContext;

/**
 * Unit tests for {@link FindingComparisonService#computeMetricsAuditChanges} (board task #37).
 *
 * <p>The repository is mocked so each test pins one snapshot timeline. metrics_audit rows store the
 * metrics IN EFFECT BEFORE the overwrite; the transition older->newer is bucketed at the older
 * row's revisionCreatedDate (when the newer state came into effect). attributedAt is deliberately
 * varied in some fixtures to prove it is never consulted.
 */
@ExtendWith(MockitoExtension.class)
public class MetricsAuditChangesTest {

    private static final UUID RELEASE = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID COMPONENT = UUID.fromString("00000000-0000-0000-0000-0000000000f2");
    private static final String LODASH = "pkg:npm/lodash@4.17.20";

    private static final ZonedDateTime FROM = ZonedDateTime.parse("2026-02-01T00:00:00Z");
    private static final ZonedDateTime TO = ZonedDateTime.parse("2026-03-01T00:00:00Z");
    // Two in-window transition timestamps (older-row revisionCreatedDate of each pair).
    private static final ZonedDateTime T1 = ZonedDateTime.parse("2026-02-05T00:00:00Z");
    private static final ZonedDateTime T2 = ZonedDateTime.parse("2026-02-10T00:00:00Z");

    private final MetricsAuditRepository repo = mock(MetricsAuditRepository.class);
    private final BranchService branchService = mock(BranchService.class);
    private final SharedReleaseService sharedReleaseService = mock(SharedReleaseService.class);
    private final GetComponentService getComponentService = mock(GetComponentService.class);

    private FindingComparisonService service() {
        return new FindingComparisonService(branchService, sharedReleaseService, getComponentService, repo,
                mock(GetOrganizationService.class));
    }

    // ---- fixtures ----

    private static VulnerabilityDto vuln(String id, VulnerabilitySeverity sev, Boolean kev,
            ZonedDateTime attributedAt) {
        return new VulnerabilityDto(LODASH, id, sev, Set.of(), Set.of(), Set.of(),
                null, null, attributedAt, null, null, null, null, null, kev);
    }

    private static WeaknessDto weakness(String cweId, VulnerabilitySeverity sev, ZonedDateTime attributedAt) {
        return new WeaknessDto(cweId, "rule-1", "src/Foo.java:10", "fp-" + cweId, sev,
                Set.of(), null, null, attributedAt);
    }

    private static ReleaseMetricsDto metricsVuln(VulnerabilityDto... vulns) {
        ReleaseMetricsDto m = new ReleaseMetricsDto();
        m.setVulnerabilityDetails(new LinkedList<>(List.of(vulns)));
        return m;
    }

    private static ReleaseMetricsDto metricsWeak(WeaknessDto... weaks) {
        ReleaseMetricsDto m = new ReleaseMetricsDto();
        m.setWeaknessDetails(new LinkedList<>(List.of(weaks)));
        return m;
    }

    /** Builds an audit row whose stored metrics is the pre-overwrite snapshot at the given date. */
    private static MetricsAudit auditRow(int revision, ZonedDateTime revisionCreatedDate,
            ReleaseMetricsDto metrics) {
        MetricsAudit a = new MetricsAudit();
        a.setEntityType(MetricsEntityType.RELEASE);
        a.setEntityUuid(RELEASE);
        a.setMetricsRevision(revision);
        a.setRevisionCreatedDate(revisionCreatedDate);
        a.setEntityCreatedDate(FROM);
        // org intentionally left null to mimic a pre-V20 row (must still be processed).
        a.setMetrics(toMap(metrics));
        return a;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> toMap(ReleaseMetricsDto m) {
        return Utils.OM.convertValue(m, Map.class);
    }

    private AuditChangeReleaseContext ctx(ReleaseMetricsDto liveMetrics) {
        return new AuditChangeReleaseContext(RELEASE, "1.0", COMPONENT, "comp-a",
                ReleaseLifecycle.ASSEMBLED, liveMetrics);
    }

    private void stubRepo(List<MetricsAudit> inWindow, List<MetricsAudit> seed) {
        when(repo.findRevisionsInRange(anyString(), any(Collection.class), any(), any()))
                .thenReturn(inWindow);
        lenient().when(repo.findLatestRevisionBeforeDate(anyString(), any(Collection.class), any()))
                .thenReturn(seed);
    }

    // ---- tests ----

    @Test
    void appearedThenKevAdded_bucketsAtRevisionDates() {
        // Timeline: empty (rev0 @T1) -> CVE (rev1 @T2) -> CVE+KEV (LIVE)
        VulnerabilityDto cve = vuln("CVE-2024-0001", VulnerabilitySeverity.HIGH, false, null);
        VulnerabilityDto cveKev = vuln("CVE-2024-0001", VulnerabilitySeverity.HIGH, true, null);

        MetricsAudit rev0 = auditRow(0, T1, new ReleaseMetricsDto()); // empty before T1 overwrite
        MetricsAudit rev1 = auditRow(1, T2, metricsVuln(cve));        // CVE before T2 overwrite
        stubRepo(List.of(rev0, rev1), List.of());

        List<MetricsRevisionFindingChange> changes = service()
                .computeMetricsAuditChanges(List.of(ctx(metricsVuln(cveKev))), FROM, TO);

        assertEquals(2, changes.size());
        // date-sorted: APPEARED@T1 then KEV_ADDED@T2
        MetricsRevisionFindingChange appeared = changes.get(0);
        assertEquals(FindingChangeKind.APPEARED, appeared.changeKind());
        assertEquals(T1, appeared.changeDate());
        assertEquals("CVE-2024-0001", appeared.vulnerability().vulnId());

        MetricsRevisionFindingChange kev = changes.get(1);
        assertEquals(FindingChangeKind.KEV_ADDED, kev.changeKind());
        assertEquals(T2, kev.changeDate());
        assertEquals("CVE-2024-0001", kev.vulnerability().vulnId());
    }

    @Test
    void severityIncrease_lowToHigh_detectedWithPreviousSeverity() {
        // Timeline: CVE@LOW (rev0 @T1) -> CVE@HIGH (LIVE). attributedAt differs but is ignored.
        VulnerabilityDto low = vuln("CVE-2024-0002", VulnerabilitySeverity.LOW, false, T1);
        VulnerabilityDto high = vuln("CVE-2024-0002", VulnerabilitySeverity.HIGH, false, T2);

        MetricsAudit rev0 = auditRow(0, T1, metricsVuln(low));
        stubRepo(List.of(rev0), List.of());

        List<MetricsRevisionFindingChange> changes = service()
                .computeMetricsAuditChanges(List.of(ctx(metricsVuln(high))), FROM, TO);

        assertEquals(1, changes.size());
        MetricsRevisionFindingChange c = changes.get(0);
        assertEquals(FindingChangeKind.SEVERITY_INCREASED, c.changeKind());
        assertEquals(T1, c.changeDate());
        assertEquals("HIGH", c.vulnerability().severity());
        assertEquals("LOW", c.previousSeverity());
    }

    @Test
    void resolved_detected() {
        // Timeline: CVE (rev0 @T1) -> empty (LIVE)
        VulnerabilityDto cve = vuln("CVE-2024-0003", VulnerabilitySeverity.MEDIUM, false, null);
        MetricsAudit rev0 = auditRow(0, T1, metricsVuln(cve));
        stubRepo(List.of(rev0), List.of());

        List<MetricsRevisionFindingChange> changes = service()
                .computeMetricsAuditChanges(List.of(ctx(new ReleaseMetricsDto())), FROM, TO);

        assertEquals(1, changes.size());
        assertEquals(FindingChangeKind.RESOLVED, changes.get(0).changeKind());
        assertEquals(T1, changes.get(0).changeDate());
        assertEquals("CVE-2024-0003", changes.get(0).vulnerability().vulnId());
    }

    @Test
    void emptyToFindings_allRegisterAsAppeared() {
        // rev0 / null metrics treated as empty -> first real snapshot's findings are APPEARED.
        VulnerabilityDto cveA = vuln("CVE-2024-0010", VulnerabilitySeverity.HIGH, false, null);
        VulnerabilityDto cveB = vuln("CVE-2024-0011", VulnerabilitySeverity.LOW, false, null);

        MetricsAudit rev0 = auditRow(0, T1, null); // null metrics
        stubRepo(List.of(rev0), List.of());

        List<MetricsRevisionFindingChange> changes = service()
                .computeMetricsAuditChanges(List.of(ctx(metricsVuln(cveA, cveB))), FROM, TO);

        assertEquals(2, changes.size());
        assertTrue(changes.stream().allMatch(c -> c.changeKind() == FindingChangeKind.APPEARED));
        assertTrue(changes.stream().allMatch(c -> T1.equals(c.changeDate())));
    }

    @Test
    void attributedAtDifferences_ignored_noSpuriousChanges() {
        // Same CVE, same severity, same KEV across both snapshots -- only attributedAt differs.
        // attributedAt is min-preserved ("first ever seen") and must NEVER drive a change.
        VulnerabilityDto early = vuln("CVE-2024-0004", VulnerabilitySeverity.HIGH, false,
                ZonedDateTime.parse("2026-01-01T00:00:00Z"));
        VulnerabilityDto later = vuln("CVE-2024-0004", VulnerabilitySeverity.HIGH, false,
                ZonedDateTime.parse("2026-02-20T00:00:00Z"));

        MetricsAudit rev0 = auditRow(0, T1, metricsVuln(early));
        stubRepo(List.of(rev0), List.of());

        List<MetricsRevisionFindingChange> changes = service()
                .computeMetricsAuditChanges(List.of(ctx(metricsVuln(later))), FROM, TO);

        assertTrue(changes.isEmpty(), "attributedAt-only differences must produce no changes");
    }

    @Test
    void weaknessSeverityIncrease_detected() {
        WeaknessDto low = weakness("CWE-79", VulnerabilitySeverity.LOW, null);
        WeaknessDto critical = weakness("CWE-79", VulnerabilitySeverity.CRITICAL, null);

        MetricsAudit rev0 = auditRow(0, T1, metricsWeak(low));
        stubRepo(List.of(rev0), List.of());

        List<MetricsRevisionFindingChange> changes = service()
                .computeMetricsAuditChanges(List.of(ctx(metricsWeak(critical))), FROM, TO);

        assertEquals(1, changes.size());
        assertEquals(FindingChangeKind.SEVERITY_INCREASED, changes.get(0).changeKind());
        assertEquals("CRITICAL", changes.get(0).weakness().severity());
        assertEquals("LOW", changes.get(0).previousSeverity());
    }

    @Test
    void seedBaseline_preWindowFindingNotReAppeared() {
        // Seed (pre-window) already has the CVE; first in-window row also has it; LIVE also has it.
        // No appeared should fire because the CVE existed before the window and never changed.
        VulnerabilityDto cve = vuln("CVE-2024-0005", VulnerabilitySeverity.HIGH, false, null);
        MetricsAudit seed = auditRow(0, ZonedDateTime.parse("2026-01-15T00:00:00Z"), metricsVuln(cve));
        MetricsAudit rev1 = auditRow(1, T1, metricsVuln(cve));
        stubRepo(List.of(rev1), List.of(seed));

        List<MetricsRevisionFindingChange> changes = service()
                .computeMetricsAuditChanges(List.of(ctx(metricsVuln(cve))), FROM, TO);

        assertTrue(changes.isEmpty(), "Unchanged pre-existing finding must not surface as a change");
    }

    @Test
    void cancelledRelease_skipped() {
        VulnerabilityDto cve = vuln("CVE-2024-0006", VulnerabilitySeverity.HIGH, false, null);
        MetricsAudit rev0 = auditRow(0, T1, new ReleaseMetricsDto());
        // Repo would return rows, but the cancelled release is filtered before the query is used.
        lenient().when(repo.findRevisionsInRange(anyString(), any(Collection.class), any(), any()))
                .thenReturn(List.of(rev0));

        AuditChangeReleaseContext cancelled = new AuditChangeReleaseContext(
                RELEASE, "1.0", COMPONENT, "comp-a", ReleaseLifecycle.CANCELLED, metricsVuln(cve));

        List<MetricsRevisionFindingChange> changes = service()
                .computeMetricsAuditChanges(List.of(cancelled), FROM, TO);

        assertTrue(changes.isEmpty());
    }

    @Test
    void noInWindowAuditRows_returnsEmpty() {
        stubRepo(List.of(), List.of());
        List<MetricsRevisionFindingChange> changes = service()
                .computeMetricsAuditChanges(
                        List.of(ctx(metricsVuln(vuln("CVE-2024-0007", VulnerabilitySeverity.HIGH, false, null)))),
                        FROM, TO);
        assertTrue(changes.isEmpty());
    }

    @Test
    void severityDecrease_notReported() {
        // HIGH -> LOW is a de-escalation, not an increase: no SEVERITY_INCREASED.
        VulnerabilityDto high = vuln("CVE-2024-0008", VulnerabilitySeverity.HIGH, false, null);
        VulnerabilityDto low = vuln("CVE-2024-0008", VulnerabilitySeverity.LOW, false, null);
        MetricsAudit rev0 = auditRow(0, T1, metricsVuln(high));
        stubRepo(List.of(rev0), List.of());

        List<MetricsRevisionFindingChange> changes = service()
                .computeMetricsAuditChanges(List.of(ctx(metricsVuln(low))), FROM, TO);

        assertFalse(changes.stream().anyMatch(c -> c.changeKind() == FindingChangeKind.SEVERITY_INCREASED));
    }
}
