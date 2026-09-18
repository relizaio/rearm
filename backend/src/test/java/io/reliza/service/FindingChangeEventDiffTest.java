/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.model.FindingChangeEvent;
import io.reliza.model.FindingChangeEvent.FindingKind;
import io.reliza.model.MetricsAudit;
import io.reliza.model.MetricsAudit.MetricsEntityType;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationDto;
import io.reliza.model.dto.ReleaseMetricsDto.ViolationType;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.model.dto.ReleaseMetricsDto.WeaknessDto;
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.service.FindingComparisonService.EventAttribution;

/**
 * Unit tests for the SINGLE SOURCE OF TRUTH per-pair diff
 * {@link FindingComparisonService#diffPairToEvents}. Each test pins one snapshot pair and asserts
 * the emitted {@link FindingChangeEvent} rows. The same diff feeds both the write-time emit
 * (SharedReleaseService) and the #252 read path (via the appendTransitionChanges adapter), so these
 * tests pin the contract both depend on.
 */
@ExtendWith(MockitoExtension.class)
public class FindingChangeEventDiffTest {

    private static final UUID ORG = UUID.fromString("00000000-0000-0000-0000-0000000000e1");
    private static final UUID RELEASE = UUID.fromString("00000000-0000-0000-0000-0000000000e2");
    private static final UUID COMPONENT = UUID.fromString("00000000-0000-0000-0000-0000000000e3");
    private static final UUID BRANCH = UUID.fromString("00000000-0000-0000-0000-0000000000e4");
    private static final String LODASH = "pkg:npm/lodash@4.17.20";
    private static final ZonedDateTime CHANGE_DATE = ZonedDateTime.parse("2026-02-05T00:00:00Z");
    private static final int TO_REV = 7;

    private static final EventAttribution ATTR =
            new EventAttribution(ORG, RELEASE, "1.0", COMPONENT, "comp-a", BRANCH);

    private FindingComparisonService service() {
        // diffPairToEvents only uses compareMetrics + local helpers; collaborators are unused.
        return new FindingComparisonService(
                mock(BranchService.class), mock(SharedReleaseService.class),
                mock(GetComponentService.class), mock(MetricsAuditRepository.class),
                mock(GetOrganizationService.class));
    }

    // ---- fixtures ----

    private static VulnerabilityDto vuln(String id, VulnerabilitySeverity sev, Boolean kev) {
        return new VulnerabilityDto(LODASH, id, sev, Set.of(), Set.of(), Set.of(),
                null, null, ZonedDateTime.now(), null, null, null, null, null, kev);
    }

    private static WeaknessDto weakness(String cweId, VulnerabilitySeverity sev) {
        return new WeaknessDto(cweId, "rule-1", "src/Foo.java:10", "fp-" + cweId, sev,
                Set.of(), null, null, null);
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

    private List<FindingChangeEvent> diff(ReleaseMetricsDto older, ReleaseMetricsDto newer) {
        return service().diffPairToEvents(older, newer, ATTR, CHANGE_DATE, TO_REV);
    }

    private static List<FindingChangeEvent> ofKind(List<FindingChangeEvent> events, FindingChangeKind kind) {
        return events.stream().filter(e -> e.getChangeKind() == kind).collect(Collectors.toList());
    }

    // ---- APPEARED ----

    @Test
    void vulnAppeared() {
        List<FindingChangeEvent> events = diff(metricsVuln(), metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, null)));
        assertEquals(1, events.size());
        FindingChangeEvent ev = events.get(0);
        assertEquals(FindingChangeKind.APPEARED, ev.getChangeKind());
        assertEquals(FindingKind.VULNERABILITY, ev.getFindingKind());
        assertEquals("CVE-1", ev.getVulnId());
        assertEquals("HIGH", ev.getSeverity());
        assertEquals(CHANGE_DATE, ev.getChangeDate());
        assertEquals(TO_REV, ev.getToMetricsRevision());
        assertEquals(ORG, ev.getOrg());
        assertEquals(RELEASE, ev.getReleaseUuid());
        assertEquals(COMPONENT, ev.getComponentUuid());
        assertEquals("comp-a", ev.getComponentName());
        assertNull(ev.getPreviousSeverity());
    }

    // ---- RESOLVED ----

    @Test
    void vulnResolved() {
        List<FindingChangeEvent> events = diff(metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, null)), metricsVuln());
        assertEquals(1, events.size());
        assertEquals(FindingChangeKind.RESOLVED, events.get(0).getChangeKind());
        assertEquals("CVE-1", events.get(0).getVulnId());
    }

    // ---- SEVERITY_INCREASED (LOW -> HIGH) with previousSeverity ----

    @Test
    void vulnSeverityIncreased_lowToHigh_carriesPreviousSeverity() {
        List<FindingChangeEvent> events = diff(
                metricsVuln(vuln("CVE-1", VulnerabilitySeverity.LOW, null)),
                metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, null)));
        List<FindingChangeEvent> sevUp = ofKind(events, FindingChangeKind.SEVERITY_INCREASED);
        assertEquals(1, sevUp.size());
        assertEquals("HIGH", sevUp.get(0).getSeverity());
        assertEquals("LOW", sevUp.get(0).getPreviousSeverity());
        // No spurious APPEARED/RESOLVED for a finding present in both snapshots.
        assertTrue(ofKind(events, FindingChangeKind.APPEARED).isEmpty());
        assertTrue(ofKind(events, FindingChangeKind.RESOLVED).isEmpty());
    }

    @Test
    void weaknessSeverityIncreased_carriesPreviousSeverity() {
        List<FindingChangeEvent> events = diff(
                metricsWeak(weakness("CWE-79", VulnerabilitySeverity.LOW)),
                metricsWeak(weakness("CWE-79", VulnerabilitySeverity.CRITICAL)));
        List<FindingChangeEvent> sevUp = ofKind(events, FindingChangeKind.SEVERITY_INCREASED);
        assertEquals(1, sevUp.size());
        assertEquals(FindingKind.WEAKNESS, sevUp.get(0).getFindingKind());
        assertEquals("CRITICAL", sevUp.get(0).getSeverity());
        assertEquals("LOW", sevUp.get(0).getPreviousSeverity());
    }

    // ---- severity DECREASE emits SEVERITY_DECREASED (bidirectional log; reverse-replay needs it) ----

    @Test
    void severityDecrease_emitsDecreasedWithPreviousSeverity() {
        List<FindingChangeEvent> events = diff(
                metricsVuln(vuln("CVE-1", VulnerabilitySeverity.CRITICAL, null)),
                metricsVuln(vuln("CVE-1", VulnerabilitySeverity.LOW, null)));
        assertTrue(ofKind(events, FindingChangeKind.SEVERITY_INCREASED).isEmpty(),
                "A severity decrease must not produce a SEVERITY_INCREASED row");
        List<FindingChangeEvent> sevDown = ofKind(events, FindingChangeKind.SEVERITY_DECREASED);
        assertEquals(1, sevDown.size());
        assertEquals("CVE-1", sevDown.get(0).getVulnId());
        assertEquals("LOW", sevDown.get(0).getSeverity(), "new (lower) severity");
        assertEquals("CRITICAL", sevDown.get(0).getPreviousSeverity(),
                "previousSeverity carries the pre-change (higher) severity for reverse-replay");
    }

    @Test
    void kevRemoved_trueToFalse_emitsKevRemoved() {
        List<FindingChangeEvent> events = diff(
                metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, true)),
                metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, false)));
        assertTrue(ofKind(events, FindingChangeKind.KEV_ADDED).isEmpty(),
                "A KEV removal must not produce a KEV_ADDED row");
        List<FindingChangeEvent> kevGone = ofKind(events, FindingChangeKind.KEV_REMOVED);
        assertEquals(1, kevGone.size());
        assertEquals("CVE-1", kevGone.get(0).getVulnId());
    }

    // ---- KEV_ADDED (false/null -> true) ----

    @Test
    void kevAdded_falseToTrue() {
        List<FindingChangeEvent> events = diff(
                metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, false)),
                metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, true)));
        List<FindingChangeEvent> kev = ofKind(events, FindingChangeKind.KEV_ADDED);
        assertEquals(1, kev.size());
        assertEquals("CVE-1", kev.get(0).getVulnId());
        assertEquals(Boolean.TRUE, kev.get(0).getKnownExploited());
    }

    @Test
    void kevAdded_nullToTrue() {
        List<FindingChangeEvent> events = diff(
                metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, null)),
                metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, true)));
        assertEquals(1, ofKind(events, FindingChangeKind.KEV_ADDED).size());
    }

    @Test
    void kevUnchangedTrue_notEmitted() {
        List<FindingChangeEvent> events = diff(
                metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, true)),
                metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, true)));
        assertTrue(events.isEmpty(), "KEV already true must not re-emit KEV_ADDED");
    }

    // ---- attributedAt difference alone is ignored ----

    @Test
    void attributedAtDifferenceOnly_isIgnored() {
        VulnerabilityDto older = new VulnerabilityDto(LODASH, "CVE-1", VulnerabilitySeverity.HIGH,
                Set.of(), Set.of(), Set.of(), null, null,
                ZonedDateTime.parse("2026-01-01T00:00:00Z"), null, null, null, null, null, null);
        VulnerabilityDto newer = new VulnerabilityDto(LODASH, "CVE-1", VulnerabilitySeverity.HIGH,
                Set.of(), Set.of(), Set.of(), null, null,
                ZonedDateTime.parse("2026-02-02T00:00:00Z"), null, null, null, null, null, null);
        List<FindingChangeEvent> events = diff(metricsVuln(older), metricsVuln(newer));
        assertTrue(events.isEmpty(), "A change only in attributedAt must not emit any event");
    }

    // ---- null / rev-0 baseline -> empty (no spurious APPEARED against an empty/null older) ----

    @Test
    void nullOlder_treatedAsEmpty_emitsAppearedForAll() {
        // A null older snapshot is treated as empty -> every finding in newer APPEARS.
        List<FindingChangeEvent> events = diff(null, metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, null)));
        assertEquals(1, events.size());
        assertEquals(FindingChangeKind.APPEARED, events.get(0).getChangeKind());
    }

    @Test
    void identicalSnapshots_emitNothing() {
        ReleaseMetricsDto m = metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, true));
        List<FindingChangeEvent> events = diff(m, metricsVuln(vuln("CVE-1", VulnerabilitySeverity.HIGH, true)));
        assertTrue(events.isEmpty(), "Identical snapshots produce no change rows");
    }

    @Test
    void bothEmpty_emitNothing() {
        assertTrue(diff(new ReleaseMetricsDto(), new ReleaseMetricsDto()).isEmpty());
    }

    // ---- GOLDEN INVERSE: reverseReplay(newer, diff(older,newer)) reconstructs older ----

    private static ViolationDto violation(ViolationType type, String purl) {
        return new ViolationDto(purl, type, null, null, Set.of(), null, null, null);
    }

    private static ReleaseMetricsDto metricsAll(List<VulnerabilityDto> v, List<ViolationDto> vi,
            List<WeaknessDto> w) {
        ReleaseMetricsDto m = new ReleaseMetricsDto();
        m.setVulnerabilityDetails(new LinkedList<>(v));
        m.setViolationDetails(new LinkedList<>(vi));
        m.setWeaknessDetails(new LinkedList<>(w));
        return m;
    }

    /** Projects the folded fields (key + severity + kev) the posture-diff reads, order-independent. */
    private static Set<String> project(ReleaseMetricsDto m) {
        Set<String> out = new HashSet<>();
        for (VulnerabilityDto v : m.getVulnerabilityDetails())
            out.add("V|" + v.vulnId() + "|" + v.purl() + "|" + v.severity() + "|" + v.knownExploited());
        for (ViolationDto v : m.getViolationDetails())
            out.add("X|" + v.type() + "|" + v.purl());
        for (WeaknessDto w : m.getWeaknessDetails())
            out.add("W|" + (w.cweId() != null ? w.cweId() : w.ruleId()) + "|" + w.location() + "|" + w.severity());
        return out;
    }

    @SuppressWarnings("unchecked")
    private ReleaseMetricsDto reverseReplay(FindingComparisonService svc, ReleaseMetricsDto current,
            List<FindingChangeEvent> events) {
        return (ReleaseMetricsDto) ReflectionTestUtils.invokeMethod(svc, "reverseReplay", current, events);
    }

    /**
     * The invariant the whole serving path rests on: reverse-replaying {@code diffPairToEvents(A,B)} onto
     * B must reconstruct A (for the fields the fold reads). Exercises EVERY event kind in one shot --
     * APPEARED, RESOLVED, KEV_ADDED, KEV_REMOVED, SEVERITY_INCREASED, SEVERITY_DECREASED -- across vulns,
     * violations and weaknesses. If forward-emit and reverse-replay ever drift, this fails.
     */
    @Test
    void goldenInverse_reverseReplayOfDiff_reconstructsOlder() {
        // A (older):
        VulnerabilityDto aKev = vuln("CVE-KEVADD", VulnerabilitySeverity.HIGH, false);      // -> KEV_ADDED
        VulnerabilityDto aKevRm = vuln("CVE-KEVRM", VulnerabilitySeverity.HIGH, true);       // -> KEV_REMOVED
        VulnerabilityDto aSevUp = vuln("CVE-SEVUP", VulnerabilitySeverity.LOW, false);       // -> SEVERITY_INCREASED
        VulnerabilityDto aSevDn = vuln("CVE-SEVDN", VulnerabilitySeverity.CRITICAL, false);  // -> SEVERITY_DECREASED
        VulnerabilityDto aResolved = vuln("CVE-RESOLVED", VulnerabilitySeverity.MEDIUM, true); // -> RESOLVED
        ViolationDto aVioResolved = violation(ViolationType.LICENSE, LODASH);                 // -> RESOLVED
        WeaknessDto aWeakSevUp = weakness("CWE-79", VulnerabilitySeverity.LOW);               // -> SEVERITY_INCREASED
        ReleaseMetricsDto older = metricsAll(
                List.of(aKev, aKevRm, aSevUp, aSevDn, aResolved),
                List.of(aVioResolved),
                List.of(aWeakSevUp));

        // B (newer): the transitions applied.
        VulnerabilityDto bKev = vuln("CVE-KEVADD", VulnerabilitySeverity.HIGH, true);
        VulnerabilityDto bKevRm = vuln("CVE-KEVRM", VulnerabilitySeverity.HIGH, false);
        VulnerabilityDto bSevUp = vuln("CVE-SEVUP", VulnerabilitySeverity.HIGH, false);
        VulnerabilityDto bSevDn = vuln("CVE-SEVDN", VulnerabilitySeverity.LOW, false);
        VulnerabilityDto bAppeared = vuln("CVE-APPEARED", VulnerabilitySeverity.HIGH, false); // -> APPEARED
        ViolationDto bVioAppeared = violation(ViolationType.SECURITY, LODASH);                 // -> APPEARED
        WeaknessDto bWeakSevUp = weakness("CWE-79", VulnerabilitySeverity.CRITICAL);
        ReleaseMetricsDto newer = metricsAll(
                List.of(bKev, bKevRm, bSevUp, bSevDn, bAppeared),
                List.of(bVioAppeared),
                List.of(bWeakSevUp));

        FindingComparisonService svc = service();
        List<FindingChangeEvent> events = svc.diffPairToEvents(older, newer, ATTR, CHANGE_DATE, TO_REV);
        ReleaseMetricsDto reconstructed = reverseReplay(svc, newer, events);

        assertEquals(project(older), project(reconstructed),
                "reverseReplay(newer, diff(older,newer)) must reconstruct older's folded posture");
    }

    private static List<FindingChangeEvent> after(List<FindingChangeEvent> events, ZonedDateTime at) {
        return events.stream().filter(e -> e.getChangeDate().isAfter(at)).collect(Collectors.toList());
    }

    /**
     * S3 GOLDEN (v3 == v1/v2 reconstruction). Derives BOTH event streams from the REAL producers -- v1
     * from {@link FindingComparisonService#backfillEventsForRelease}, v3 (branch-chained) from
     * {@link FindingComparisonService#backfillEventsForReleaseV3} -- for a successor release R that
     * INHERITED CVE-A from its branch predecessor, then had a within-release severity bump on A and
     * carries a new CVE-C. Reverse-replays each stream and asserts:
     * <ol>
     *   <li>Reconstructing R at an instant WITHIN its scanned life (after first scan) is BYTE-IDENTICAL
     *       between v1 and v3 -- the real-world changelog case. v3 stores fewer rows (no re-declared
     *       APPEARED for the inherited CVE-A) yet reconstructs the same posture, because the inherited
     *       finding is carried by R.live and never had an in-window APPEARED to invert.</li>
     *   <li>The ONLY divergence is the documented Risk-1 gap: reconstructing R at an instant BEFORE its
     *       own first scan. v1 empties R (its initial APPEARED invert removes everything); v3 retains the
     *       inherited CVE-A. This is a pre-existing v1/v2 imperfection (R had no metrics then; the correct
     *       answer is the predecessor's posture) where v3 is arguably closer. It only bites when a
     *       changelog window endpoint lands in a release's created->first-scan gap.</li>
     * </ol>
     */
    @Test
    void v3_reconstructionByteIdenticalWithinScannedLife_gapIsKnownDelta() {
        FindingComparisonService svc = service();
        VulnerabilityDto aHigh = vuln("CVE-A", VulnerabilitySeverity.HIGH, null);
        VulnerabilityDto aCrit = vuln("CVE-A", VulnerabilitySeverity.CRITICAL, null);
        VulnerabilityDto c = vuln("CVE-C", VulnerabilitySeverity.LOW, null);
        ReleaseMetricsDto live = metricsVuln(aCrit, c);
        // R's timeline: empty(rev0) -> {A(HIGH),C}(rev1, first scan) -> {A(CRITICAL),C}(rev2, A bumped).
        List<MetricsAudit> rows = List.of(
                audit(0, metricsVuln()), audit(1, metricsVuln(aHigh, c)), audit(2, metricsVuln(aCrit, c)));
        // Inherited from the branch predecessor: CVE-A (present before R). CVE-C is new at R.
        Set<String> inherited = svc.findingKeysOf(metricsVuln(aHigh));

        List<FindingChangeEvent> v1 = svc.backfillEventsForRelease(rows, live, ATTR);
        List<FindingChangeEvent> v3 = svc.backfillEventsForReleaseV3(rows, live, ATTR, inherited);
        // Precondition: v3 stored strictly fewer rows (the inherited CVE-A first-APPEARED is gone).
        assertEquals(v1.size() - 1, v3.size(), "v3 drops exactly the inherited CVE-A initial APPEARED");

        // audit(rev) is stamped 2026-02-01 + rev days; events carry the OLDER snapshot's date:
        // APPEARED @ 2026-02-01 (rev0), SEVERITY_INCREASED @ 2026-02-02 (rev1).
        ZonedDateTime withinLife = ZonedDateTime.parse("2026-02-01T12:00:00Z"); // after first scan, before the bump
        assertEquals(
                project(reverseReplay(svc, live, after(v1, withinLife))),
                project(reverseReplay(svc, live, after(v3, withinLife))),
                "within scanned life: v3 reconstructs BYTE-IDENTICAL to v1 (A restored to HIGH, C present)");

        ZonedDateTime fullyScanned = ZonedDateTime.parse("2026-02-05T00:00:00Z"); // after everything
        assertEquals(
                project(reverseReplay(svc, live, after(v1, fullyScanned))),
                project(reverseReplay(svc, live, after(v3, fullyScanned))),
                "after all scans: identical (both == live)");

        // Risk-1 gap: BEFORE R's first scan. Documented known-delta (v3 more-correct).
        ZonedDateTime gap = ZonedDateTime.parse("2026-01-31T00:00:00Z");
        Set<String> v1Gap = project(reverseReplay(svc, live, after(v1, gap)));
        Set<String> v3Gap = project(reverseReplay(svc, live, after(v3, gap)));
        assertTrue(v1Gap.isEmpty(), "v1 empties R before its first scan (all initial APPEARED inverted)");
        assertEquals(Set.of("V|CVE-A|" + LODASH + "|HIGH|null"), v3Gap,
                "Risk-1 known-delta: v3 retains the inherited CVE-A in the pre-first-scan gap");
    }

    /**
     * Flap with an IDENTICAL change_date but different to_metrics_revision on one key. Reverse-replay must
     * break the tie by revision (higher = later) so APPEARED@rev2 (remove) is applied before RESOLVED@rev1
     * (re-add) -- reconstructing the pre-flap ABSENT state deterministically.
     */
    @Test
    void sameChangeDateFlap_tieBrokenByRevision_reconstructsDeterministically() {
        // current (after both events) = present. Events at the SAME change_date: RESOLVED@rev1, APPEARED@rev2.
        // True order by revision: rev1 (resolve) then rev2 (appear) -> so before rev1 the finding was PRESENT,
        // and reconstructing to before-rev1 should yield PRESENT... but we reconstruct to before BOTH events:
        // reverse rev2 APPEARED (remove) then rev1 RESOLVED (re-add) -> PRESENT. With a wrong tie order the
        // result would flip. Assert the deterministic PRESENT outcome.
        VulnerabilityDto cur = vuln("CVE-FLAP", VulnerabilitySeverity.HIGH, false);
        FindingChangeEvent resolvedRev1 = new FindingChangeEvent();
        resolvedRev1.setReleaseUuid(RELEASE); resolvedRev1.setOrg(ORG);
        resolvedRev1.setChangeDate(CHANGE_DATE); resolvedRev1.setToMetricsRevision(1);
        resolvedRev1.setChangeKind(FindingChangeKind.RESOLVED); resolvedRev1.setFindingKind(FindingKind.VULNERABILITY);
        resolvedRev1.setFindingKey("CVE-FLAP|" + LODASH); resolvedRev1.setVulnId("CVE-FLAP");
        resolvedRev1.setPurl(LODASH); resolvedRev1.setSeverity("HIGH"); resolvedRev1.setKnownExploited(false);
        FindingChangeEvent appearedRev2 = new FindingChangeEvent();
        appearedRev2.setReleaseUuid(RELEASE); appearedRev2.setOrg(ORG);
        appearedRev2.setChangeDate(CHANGE_DATE); appearedRev2.setToMetricsRevision(2);
        appearedRev2.setChangeKind(FindingChangeKind.APPEARED); appearedRev2.setFindingKind(FindingKind.VULNERABILITY);
        appearedRev2.setFindingKey("CVE-FLAP|" + LODASH); appearedRev2.setVulnId("CVE-FLAP");
        appearedRev2.setPurl(LODASH); appearedRev2.setSeverity("HIGH");

        FindingComparisonService svc = service();
        // Feed events in the "wrong" list order to prove the sort (not input order) decides.
        ReleaseMetricsDto reconstructed = reverseReplay(svc, metricsVuln(cur),
                List.of(appearedRev2, resolvedRev1));
        assertTrue(project(reconstructed).contains("V|CVE-FLAP|" + LODASH + "|HIGH|false"),
                "revision tie-break: reverse rev2-APPEARED then rev1-RESOLVED -> finding present pre-flap");
    }

    // ---- v3 branch-chained producer: drop the initial APPEARED for INHERITED findings ----

    private MetricsAudit audit(int rev, ReleaseMetricsDto m) {
        MetricsAudit a = new MetricsAudit();
        a.setEntityType(MetricsEntityType.RELEASE);
        a.setMetricsRevision(rev);
        a.setRevisionCreatedDate(ZonedDateTime.parse("2026-02-01T00:00:00Z").plusDays(rev));
        @SuppressWarnings("unchecked")
        Map<String, Object> raw = Utils.OM.convertValue(m, Map.class);
        a.setMetrics(raw);
        return a;
    }

    @Test
    void v3_dropsInheritedFirstAppearance_keepsNew() {
        FindingComparisonService svc = service();
        VulnerabilityDto a = vuln("CVE-A", VulnerabilitySeverity.HIGH, null);
        VulnerabilityDto c = vuln("CVE-C", VulnerabilitySeverity.LOW, null);
        ReleaseMetricsDto firstScan = metricsVuln(a, c);
        // Branch predecessor carried CVE-A (inherited); CVE-C is genuinely new at this release.
        Set<String> inherited = svc.findingKeysOf(metricsVuln(a));
        List<FindingChangeEvent> events = svc.backfillEventsForReleaseV3(
                List.of(audit(0, metricsVuln()), audit(1, firstScan)), firstScan, ATTR, inherited);
        List<FindingChangeEvent> appeared = ofKind(events, FindingChangeKind.APPEARED);
        assertEquals(1, appeared.size(), "inherited CVE-A first-APPEARED dropped, new CVE-C kept");
        assertEquals("CVE-C", appeared.get(0).getVulnId());
    }

    @Test
    void v3_keepsWithinReleaseReappearanceOfInheritedFinding() {
        FindingComparisonService svc = service();
        VulnerabilityDto a = vuln("CVE-A", VulnerabilitySeverity.HIGH, null);
        ReleaseMetricsDto withA = metricsVuln(a);
        Set<String> inherited = svc.findingKeysOf(withA);
        // Timeline empty -> {A} -> {} -> {A}: only the INITIAL inherited APPEARED is dropped; the
        // within-release RESOLVED and the flap re-APPEARED are genuine transitions and stay.
        List<FindingChangeEvent> events = svc.backfillEventsForReleaseV3(
                List.of(audit(0, metricsVuln()), audit(1, withA), audit(2, metricsVuln()), audit(3, withA)),
                withA, ATTR, inherited);
        List<FindingChangeEvent> appeared = ofKind(events, FindingChangeKind.APPEARED);
        assertEquals(1, appeared.size(),
                "initial inherited APPEARED dropped; the within-release re-APPEARED (flap) is kept");
        // The SURVIVING APPEARED must be the flap ({} -> {A} pair, stamped the older rev 2), NOT the
        // initial (empty -> {A} pair, older rev 0) -- proves the drop hit the initial, not the flap.
        assertEquals(2, appeared.get(0).getToMetricsRevision(),
                "surviving APPEARED is the within-release flap (rev-2 pair), not the dropped initial");
        assertEquals(1, ofKind(events, FindingChangeKind.RESOLVED).size(),
                "the within-release RESOLVED is untouched");
    }

    @Test
    void v3_trickleInInheritedFinding_keepsAppeared_reconstructsAbsentBeforeTrickle() {
        FindingComparisonService svc = service();
        VulnerabilityDto x = vuln("CVE-X", VulnerabilitySeverity.HIGH, null);
        VulnerabilityDto c = vuln("CVE-C", VulnerabilitySeverity.LOW, null);
        // The DEMO shape in miniature (board task F1): the release's BIRTH scan is {X}; CVE-C TRICKLES IN on a
        // later re-scan (a newly-disclosed CVE matched against the already-shipped release), and is ALSO in the
        // branch predecessor's terminal metrics (predecessor re-scanned too), so it counts as "inherited".
        // C is NOT born-with (absent from the birth scan {X}), so its APPEARED is KEPT -- reverse-replay needs it
        // to show C ABSENT before it trickled in. The earlier rule dropped it (pinning C open back to birth), the
        // F1 over-count. audit(rev) is stamped 2026-02-01 + rev days; C's APPEARED carries the OLDER (rev1) date.
        Set<String> inherited = svc.findingKeysOf(metricsVuln(c));
        ReleaseMetricsDto live = metricsVuln(x, c);
        List<FindingChangeEvent> events = svc.backfillEventsForReleaseV3(
                List.of(audit(1, metricsVuln(x)), audit(2, metricsVuln(x, c))), live, ATTR, inherited);
        List<FindingChangeEvent> appeared = ofKind(events, FindingChangeKind.APPEARED);
        assertEquals(1, appeared.size(), "trickle-in CVE-C's APPEARED is KEPT (not born-with)");
        assertEquals("CVE-C", appeared.get(0).getVulnId());

        // Reconstruction: C's APPEARED is dated at the rev1 pair (2026-02-02). Before that -> C ABSENT; after -> present.
        ZonedDateTime beforeTrickle = ZonedDateTime.parse("2026-02-01T12:00:00Z");
        ZonedDateTime afterTrickle = ZonedDateTime.parse("2026-02-05T00:00:00Z");
        assertFalse(project(reverseReplay(svc, live, after(events, beforeTrickle)))
                        .contains("V|CVE-C|" + LODASH + "|LOW|null"),
                "F1: reconstruct BEFORE the trickle -> CVE-C correctly ABSENT (its APPEARED is reversed out)");
        assertTrue(project(reverseReplay(svc, live, after(events, afterTrickle)))
                        .contains("V|CVE-C|" + LODASH + "|LOW|null"),
                "reconstruct AFTER the trickle -> CVE-C present");
    }

    @Test
    void v3_leadingEmptySnapshots_stillDropsInheritedInitialAppearance() {
        FindingComparisonService svc = service();
        VulnerabilityDto a = vuln("CVE-A", VulnerabilitySeverity.HIGH, null);
        VulnerabilityDto c = vuln("CVE-C", VulnerabilitySeverity.LOW, null);
        ReleaseMetricsDto firstScan = metricsVuln(a, c);
        Set<String> inherited = svc.findingKeysOf(metricsVuln(a));
        // TWO leading empty snapshots (a real case: an empty-metrics save adds an extra empty audit row),
        // so the initial appearance is NOT pair 0. The drop must still fire on the first empty->non-empty.
        List<FindingChangeEvent> events = svc.backfillEventsForReleaseV3(
                List.of(audit(0, metricsVuln()), audit(1, metricsVuln()), audit(2, firstScan)),
                firstScan, ATTR, inherited);
        List<FindingChangeEvent> appeared = ofKind(events, FindingChangeKind.APPEARED);
        assertEquals(1, appeared.size(), "inherited CVE-A dropped at the first empty->non-empty pair (not just i==0)");
        assertEquals("CVE-C", appeared.get(0).getVulnId());
    }

    /**
     * The repair sweep's alert has to say WHICH of its two causes it is looking at, and when the store held
     * nothing for the repaired revisions those two are indistinguishable from the store alone: an emit that
     * never ran leaves it empty, and so does an emit that ran and correctly wrote nothing because every
     * finding was inherited. {@code emitRuleWouldHaveProduced} is the tiebreak -- it replays the LIVE
     * EMIT's rule over the same input. Pinned here because the alert built on it would quietly start
     * accusing the wrong component if the emitter's rule and this replay ever diverged.
     */
    @Test
    void v3_emitRuleReplay_separatesNeverRanFromCorrectlySilent() {
        VulnerabilityDto a = vuln("CVE-A", VulnerabilitySeverity.HIGH, false);
        VulnerabilityDto b = vuln("CVE-B", VulnerabilitySeverity.HIGH, false);
        ReleaseMetricsDto firstScan = metricsVuln(a, b);
        List<MetricsAudit> rows = List.of(audit(0, metricsVuln()), audit(1, firstScan));
        FindingComparisonService svc = service();

        // Nothing inherited: the emit would have written both APPEAREDs. An empty store therefore means it
        // never ran -- the sweep may say so.
        assertEquals(2, svc.produceForReleaseV3(rows, firstScan, ATTR, Set.of())
                        .byRevision().get(0).emitRuleWouldHaveProduced(),
                "with nothing inherited the live emit keeps every APPEARED, so an empty store means it never ran");

        // Everything inherited: the emit would have written nothing at all, so an empty store is CORRECT and
        // any rows the sweep adds are a producer disagreement, not a lost write.
        Set<String> allInherited = Set.of("CVE-A|" + LODASH, "CVE-B|" + LODASH);
        assertEquals(0, svc.produceForReleaseV3(rows, firstScan, ATTR, allInherited)
                        .byRevision().get(0).emitRuleWouldHaveProduced(),
                "with everything inherited the live emit writes nothing, so an empty store must NOT be "
                + "reported as a lost emit");

        // The half the earlier version of this test never exercised: an inherited finding APPEARING on a
        // NON-first-scan pair. The emit applies its drop only on a first scan, so this must be KEPT -- an
        // implementation that dropped inherited APPEAREDs on every pair would return 0 here and the alert
        // would start calling lost emits "disagreements".
        ReleaseMetricsDto trickleIn = metricsVuln(a, b);
        List<MetricsAudit> withTrickle = List.of(
                audit(0, metricsVuln()), audit(1, metricsVuln(a)), audit(2, trickleIn));
        assertEquals(1, svc.produceForReleaseV3(withTrickle, trickleIn, ATTR, allInherited)
                        .byRevision().get(1).emitRuleWouldHaveProduced(),
                "an inherited finding appearing on a RE-SCAN keeps its APPEARED under the emit's rule, "
                + "because that rule only drops on a first scan");

        // The revision's changeDate is the ONLY input to the sweep's lifecycle lookup, so an off-by-one
        // here (taking the newer snapshot's date) would silently ask "was it cancelled" about the wrong
        // transition -- and nothing else in the suite would notice.
        assertEquals(ZonedDateTime.parse("2026-02-01T00:00:00Z").plusDays(1),
                svc.produceForReleaseV3(withTrickle, trickleIn, ATTR, allInherited)
                        .byRevision().get(1).changeDate(),
                "each revision must carry the date of the OLDER snapshot in its pair -- the instant that "
                + "transition happened, which is what the lifecycle lookup asks about");
    }

    /** Canonical per-event key for stream-equality (order-independent-of-uuid). */
    private static String evKey(FindingChangeEvent e) {
        return e.getChangeKind() + "|" + e.getFindingKind() + "|" + e.getFindingKey()
                + "|" + e.getToMetricsRevision() + "|" + e.getSeverity() + "|" + e.getPreviousSeverity();
    }

    @Test
    void v3_windowedPairPath_identicalToWholeListProducer() {
        FindingComparisonService svc = service();
        VulnerabilityDto a = vuln("CVE-A", VulnerabilitySeverity.HIGH, null);
        VulnerabilityDto c = vuln("CVE-C", VulnerabilitySeverity.LOW, null);
        // Two leading empties + a within-release flap, so the per-release appeared-set must survive across pairs.
        List<MetricsAudit> auditRows = List.of(
                audit(0, metricsVuln()), audit(1, metricsVuln()), audit(2, metricsVuln(a, c)),
                audit(3, metricsVuln()), audit(4, metricsVuln(a, c)));
        ReleaseMetricsDto live = metricsVuln(a, c);
        Set<String> inherited = svc.findingKeysOf(metricsVuln(a));

        // Whole-list producer (the tested reference).
        List<String> wholeList = svc.backfillEventsForReleaseV3(auditRows, live, ATTR, inherited)
                .stream().map(FindingChangeEventDiffTest::evKey).collect(Collectors.toList());

        // Windowed pair path (what the backfill service walks), pairwise + terminal, carrying the appeared-set
        // and the born-with set (keys of the first non-empty snapshot = {A,C}) the driver computes on its walk.
        Set<String> handled = new HashSet<>();
        Set<String> bornWith = svc.findingKeysOf(metricsVuln(a, c));
        List<FindingChangeEvent> windowedEvents = new java.util.ArrayList<>();
        for (int i = 0; i < auditRows.size() - 1; i++) {
            windowedEvents.addAll(svc.diffAuditPairToEventsV3(
                    auditRows.get(i), auditRows.get(i + 1), ATTR, inherited, bornWith, handled));
        }
        windowedEvents.addAll(svc.diffAuditToLiveEventsV3(
                auditRows.get(auditRows.size() - 1), live, ATTR, inherited, bornWith, handled));
        List<String> windowed = windowedEvents.stream().map(FindingChangeEventDiffTest::evKey).collect(Collectors.toList());

        assertEquals(wholeList, windowed,
                "windowed pair path must produce the byte-identical v3 stream as the whole-list producer");
        // And it actually did the dedup: the INITIAL inherited CVE-A APPEARED is dropped, only the later
        // within-release flap re-APPEARED for CVE-A survives -> exactly 1 remains.
        assertEquals(1, windowedEvents.stream().filter(e -> e.getChangeKind() == FindingChangeKind.APPEARED
                && "CVE-A".equals(e.getVulnId())).count(),
                "initial inherited CVE-A APPEARED dropped; the flap re-APPEARED kept");
    }

    @Test
    void v3_firstReleaseNothingInherited_identicalToPerRelease() {
        FindingComparisonService svc = service();
        VulnerabilityDto a = vuln("CVE-A", VulnerabilitySeverity.HIGH, null);
        VulnerabilityDto c = vuln("CVE-C", VulnerabilitySeverity.LOW, null);
        ReleaseMetricsDto firstScan = metricsVuln(a, c);
        List<MetricsAudit> rows = List.of(audit(0, metricsVuln()), audit(1, firstScan));
        // First release on a branch -> nothing inherited -> v3 == the per-release backfill exactly.
        List<FindingChangeEvent> perRelease = svc.backfillEventsForRelease(rows, firstScan, ATTR);
        List<FindingChangeEvent> v3 = svc.backfillEventsForReleaseV3(rows, firstScan, ATTR, Set.of());
        assertEquals(perRelease.size(), v3.size(), "no inherited findings -> v3 identical to per-release");
        assertEquals(2, ofKind(v3, FindingChangeKind.APPEARED).size(), "both first-scan findings APPEAR");
    }

    /**
     * The two counters the repair sweep's alert uses to say WHY it kept an APPEARED the live emit dropped.
     * They exist because "the two producers derived different sets" is not actionable on its own: a
     * re-appearance means the release's metrics flapped to empty and back (chase the metrics writer), while
     * a not-born-with means the finding fell outside the slice's born-with set (possibly just an artefact of
     * the lookback window, chase nothing).
     *
     * <p>Pinned here rather than through the log line: an earlier attempt asserted on rendered output via
     * OutputCaptureExtension and was flaky, because log4j2 caches System.out at init so capture depends on
     * ordering. These are record components on the public production result, so they can be asserted
     * directly.
     *
     * <p>The gate on {@code firstScanPair} is the subtle part and is pinned last. Without it the counters
     * fired on ordinary trickle-in, where the emit keeps everything and the producers therefore AGREE --
     * which is the opposite of what the fields mean.
     */
    @Test
    void v3_keptCounters_separateAFlapFromALookbackArtefact() {
        VulnerabilityDto a = vuln("CVE-A", VulnerabilitySeverity.HIGH, false);
        VulnerabilityDto b = vuln("CVE-B", VulnerabilitySeverity.HIGH, false);
        Set<String> inheritedA = Set.of("CVE-A|" + LODASH);
        FindingComparisonService svc = service();

        // FLAP: present, wiped, back. The pair that brings it back has an EMPTY older side, so the emit
        // calls it a first scan and drops the inherited APPEARED -- but this producer has already seen the
        // finding appear on this release, so it keeps it. That difference IS the customer's alert.
        ReleaseMetricsDto recovered = metricsVuln(a);
        List<MetricsAudit> flap = List.of(
                audit(0, metricsVuln()), audit(1, metricsVuln(a)), audit(2, metricsVuln()));
        var flapProd = svc.produceForReleaseV3(flap, recovered, ATTR, inheritedA);
        assertEquals(1, flapProd.byRevision().get(2).keptAsReappearance(),
                "the finding already appeared on this release, so its return is a RE-appearance -- the "
                + "counter must say so, because that is what sends the reader to the metrics writer");
        assertEquals(0, flapProd.byRevision().get(2).keptAsNotBornWith(),
                "and it must not be attributed to the born-with gate, which would send them nowhere");

        // TRICKLE-IN on a re-scan: the emit keeps everything on a non-first-scan pair, so the producers
        // AGREE and neither counter may fire. This is the case that was wrong before the firstScanPair gate.
        ReleaseMetricsDto trickled = metricsVuln(a, b);
        List<MetricsAudit> trickle = List.of(
                audit(0, metricsVuln()), audit(1, metricsVuln(a)), audit(2, trickled));
        var trickleProd = svc.produceForReleaseV3(trickle, trickled, ATTR,
                Set.of("CVE-A|" + LODASH, "CVE-B|" + LODASH));
        assertEquals(0, trickleProd.byRevision().get(1).keptAsReappearance(),
                "on a re-scan pair the emit keeps every APPEARED, so there is nothing it dropped and "
                + "nothing to explain -- a non-zero counter here means the gate regressed");
        assertEquals(0, trickleProd.byRevision().get(1).keptAsNotBornWith(),
                "same: counting on non-first-scan pairs made this fire where the producers agreed");

        // AGREEMENT on a genuine first scan: everything inherited AND born-with, so both producers drop and
        // there is no difference to decompose.
        ReleaseMetricsDto born = metricsVuln(a);
        List<MetricsAudit> firstScanOnly = List.of(audit(0, metricsVuln()), audit(1, born));
        var agreeProd = svc.produceForReleaseV3(firstScanOnly, born, ATTR, inheritedA);
        assertEquals(0, agreeProd.byRevision().get(0).keptAsReappearance(),
                "a plain first scan of an inherited finding is dropped by BOTH producers, so neither "
                + "counter may claim a difference");
        assertEquals(0, agreeProd.byRevision().get(0).keptAsNotBornWith(),
                "on the birth pair the newer snapshot IS the born-with set, so this arm cannot fire");
    }
}
