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
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.dto.FindingChangesWithAttribution;
import io.reliza.dto.VulnerabilityWithAttribution;
import io.reliza.service.FindingComparisonService.ProductConstituent;
import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.BranchData;
import io.reliza.model.BranchData.FindingAnalyticsParticipation;
import io.reliza.model.ComponentData;
import io.reliza.model.ParentRelease;
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
 * Unit tests for the PRODUCT-scope window posture-diff
 * ({@link FindingComparisonService#computeProductPostureDiff}, board task #38 phase 3 -- product path).
 *
 * <p>Clones {@link OrgPostureDiffTest}'s mock harness (reverse-replay of finding_change_events onto current
 * metrics for the from-endpoint, current metrics for the to-endpoint). The difference from the org /
 * component path is the ENDPOINT SOURCE: instead of date-picking a branch-latest release, the test supplies
 * the EXACT pinned constituent releases of the product's from-product-release and to-product-release (as
 * {@link ProductConstituent}s). This validates the two product-specific behaviors the org path cannot
 * express:
 * <ul>
 *   <li>RE-SCAN: the SAME pinned constituent release whose own scan added a finding between {@code from}
 *       and {@code to} -- caught by {@code reconstructLiveMetricsAt}; and</li>
 *   <li>RE-PIN: the to-product-release upgrades a constituent to a different version that dropped / added
 *       a finding -- folds as resolved / appeared via the pinned-release delta.</li>
 * </ul>
 * The endpoints then flow through the SAME {@code computeOrgFindingFlags} / {@code buildAttributedFindings}
 * tail, so bucket assertions validate the re-sourcing, not a rewritten flag engine.
 */
@ExtendWith(MockitoExtension.class)
public class ProductPostureDiffTest {

    private static final UUID ORG = UUID.fromString("b8e7c851-0000-0000-0000-000000000001");
    private static final UUID COMP_A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID COMP_B = UUID.fromString("00000000-0000-0000-0000-00000000000b");
    private static final UUID BRANCH_A = UUID.fromString("00000000-0000-0000-0000-0000000000ba");
    private static final UUID BRANCH_B = UUID.fromString("00000000-0000-0000-0000-0000000000bb");

    // Constituent releases the product pins at each endpoint.
    private static final UUID A_V1 = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID A_V2 = UUID.fromString("00000000-0000-0000-0000-0000000000a2");
    private static final UUID B_V1 = UUID.fromString("00000000-0000-0000-0000-0000000000b1");

    // Feature-set-scoped fixtures: a PRODUCT, ONE feature-set branch, and its two product-releases.
    private static final UUID PRODUCT = UUID.fromString("00000000-0000-0000-0000-0000000000f0");
    private static final UUID FEATURE_SET = UUID.fromString("00000000-0000-0000-0000-0000000000f5");
    private static final UUID PROD_REL_FROM = UUID.fromString("00000000-0000-0000-0000-0000000000f1");
    private static final UUID PROD_REL_TO = UUID.fromString("00000000-0000-0000-0000-0000000000f2");

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

    private static ReleaseData release(UUID uuid, UUID component, UUID branch, String version,
            ZonedDateTime created, ReleaseMetricsDto currentMetrics) {
        ReleaseData rd = mock(ReleaseData.class);
        lenient().when(rd.getUuid()).thenReturn(uuid);
        lenient().when(rd.getOrg()).thenReturn(ORG);
        lenient().when(rd.getComponent()).thenReturn(component);
        lenient().when(rd.getBranch()).thenReturn(branch);
        lenient().when(rd.getVersion()).thenReturn(version);
        lenient().when(rd.getCreatedDate()).thenReturn(created);
        lenient().when(rd.getLifecycle()).thenReturn(ReleaseLifecycle.ASSEMBLED);
        lenient().when(rd.getMetrics()).thenReturn(currentMetrics);
        return rd;
    }

    private void stubNoEvents() {
        lenient().when(findingDimBackfillService.hydrateInRangeV3(any(), any(), any(), any()))
                .thenReturn(List.of());
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

    private static ProductConstituent constituent(UUID component, String name, ReleaseData release) {
        return new ProductConstituent(component, name, release);
    }

    /** A PRODUCT-release that pins the given constituent release UUIDs via getParentReleases(). */
    private static ReleaseData productRelease(UUID uuid, UUID branch, String version,
            ZonedDateTime created, UUID... pinnedConstituents) {
        ReleaseData rd = mock(ReleaseData.class);
        lenient().when(rd.getUuid()).thenReturn(uuid);
        lenient().when(rd.getComponent()).thenReturn(PRODUCT);
        lenient().when(rd.getBranch()).thenReturn(branch);
        lenient().when(rd.getVersion()).thenReturn(version);
        lenient().when(rd.getCreatedDate()).thenReturn(created);
        lenient().when(rd.getLifecycle()).thenReturn(ReleaseLifecycle.ASSEMBLED);
        List<ParentRelease> parents = new LinkedList<>();
        for (UUID c : pinnedConstituents) {
            parents.add(ParentRelease.minimalParentReleaseFactory(c));
        }
        lenient().when(rd.getParentReleases()).thenReturn(parents);
        return rd;
    }

    private static ComponentData componentData(UUID uuid, String name) {
        ComponentData cd = mock(ComponentData.class);
        lenient().when(cd.getUuid()).thenReturn(uuid);
        lenient().when(cd.getName()).thenReturn(name);
        return cd;
    }

    /** Wires a ChangeLogService with the mocks its product-branch posture path touches. */
    private ChangeLogService changeLogService() {
        ChangeLogService cls = new ChangeLogService();
        ReflectionTestUtils.setField(cls, "sharedReleaseService", sharedReleaseService);
        ReflectionTestUtils.setField(cls, "getComponentService", getComponentService);
        ReflectionTestUtils.setField(cls, "findingComparisonService", service());
        // Feature-set posture path reads the branch's findingAnalyticsParticipation (board task F2); default
        // mock returns Optional.empty() -> treated as participating, so these fixtures behave unchanged.
        ReflectionTestUtils.setField(cls, "branchService", branchService);
        return cls;
    }

    @SuppressWarnings("unchecked")
    private FindingChangesWithAttribution invokeProductBranchPostureDiff(
            ChangeLogService cls, ComponentData product) {
        return (FindingChangesWithAttribution) ReflectionTestUtils.invokeMethod(
                cls, "computeProductBranchPostureDiff", product, FEATURE_SET, ORG, FROM, TO);
    }

    private VulnerabilityWithAttribution findVuln(FindingChangesWithAttribution result, String vulnId) {
        return result.vulnerabilities().stream()
                .filter(v -> vulnId.equals(v.vulnId())).findFirst().orElse(null);
    }

    // ---- (a) product with NO in-window product release -> findings StillPresent (empty-window) ----

    /**
     * The product has no in-window product-release: the from-product-release and to-product-release are the
     * SAME (the latest {@code <= from} carries through to {@code to}). Its pinned constituent A@v1 carries a
     * finding at BOTH reconstructed endpoints. It must classify StillPresent (not new-to-product, not net
     * appeared/resolved) -- the product empty-window fix surfaces posture even with no in-window release.
     */
    @Test
    void noInWindowProductRelease_findingIsStillPresent_notNewToProduct() {
        VulnerabilityDto cve = vuln("CVE-2026-8001", LODASH, VulnerabilitySeverity.HIGH, false);
        ReleaseData aV1 = release(A_V1, COMP_A, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));
        stubNoEvents(); // from degrades to current (=to) metrics -> present at both endpoints

        // SAME pinned constituent at from and to (no in-window product release -> same product release).
        List<ProductConstituent> from = List.of(constituent(COMP_A, "comp-a", aV1));
        List<ProductConstituent> to = List.of(constituent(COMP_A, "comp-a", aV1));

        FindingChangesWithAttribution result = service()
                .computeProductPostureDiff(ORG, from, to, FROM, TO);

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-8001");
        assertNotNull(v, "pinned-constituent finding present at both endpoints -> in the product posture-diff");
        assertTrue(v.isStillPresent(), "present at both endpoints via the same pinned constituent -> StillPresent");
        assertFalse(v.isNetAppeared());
        assertFalse(v.isNetResolved());
        assertFalse(v.orgContext().isNewToOrganization(), "present at `from` -> NOT new-to-product");
        assertEquals(0, result.totalAppeared());
        assertEquals(0, result.totalResolved());
    }

    // ---- (b) RE-SCAN: SAME pinned constituent release, finding added via re-scan in-window -> appeared ----

    /**
     * The product pins the SAME constituent release A@v1 at both endpoints (no re-pin). But A@v1's OWN scan
     * changed inside the window: the finding was ABSENT in the metrics live at {@code from} and PRESENT in
     * the metrics live at {@code to}. Because each endpoint is reconstructed live-at-T by reverse-replaying finding_change_events,
     * the diff correctly classifies it net-appeared / new-to-product -- a re-pin did not happen, only a
     * re-scan of the pinned constituent.
     */
    @Test
    void reScanOfSamePinnedConstituent_findingAppearsInWindow_isNewToProduct() {
        VulnerabilityDto cve = vuln("CVE-2026-8002", AXIOS, VulnerabilitySeverity.HIGH, false);
        // A@v1 current (=live at to) has the CVE; live-at-from is EMPTY -> a re-scan APPEARED it in-window,
        // so reverse-replay removes it from the from-posture.
        ReleaseData aV1 = release(A_V1, COMP_A, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));

        lenient().when(findingDimBackfillService.hydrateInRangeV3(any(), any(), any(), any()))
                .thenReturn(List.of(vulnEvent(A_V1, FindingChangeKind.APPEARED, "CVE-2026-8002", AXIOS,
                        VulnerabilitySeverity.HIGH, false, null, ZonedDateTime.parse("2026-06-05T00:00:00Z"))));

        List<ProductConstituent> from = List.of(constituent(COMP_A, "comp-a", aV1));
        List<ProductConstituent> to = List.of(constituent(COMP_A, "comp-a", aV1));

        FindingChangesWithAttribution result = service()
                .computeProductPostureDiff(ORG, from, to, FROM, TO);

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-8002");
        assertNotNull(v);
        assertTrue(v.isNetAppeared(), "absent in reconstructed from-posture, present at to (re-scan) -> net-New");
        assertTrue(v.orgContext().isNewToOrganization(), "new-to-product");
        assertEquals(1, result.totalAppeared());
        assertEquals(0, result.totalResolved());
    }

    // ---- (c) RE-PIN: to-product upgrades a constituent to a version that dropped a finding -> resolved ----

    /**
     * The product re-pins constituent A from A@v1 (carries the CVE) at {@code from} to A@v2 (dropped the
     * CVE) at {@code to}. The finding is present at from (via A@v1) and absent at to (via A@v2), so it folds
     * as net-resolved / fully-resolved -- driven purely by the exact pinned-release delta, no date-picking.
     */
    @Test
    void rePinConstituentToVersionThatDroppedFinding_isNetResolved() {
        VulnerabilityDto cve = vuln("CVE-2026-8003", LODASH, VulnerabilitySeverity.HIGH, false);
        ReleaseData aV1 = release(A_V1, COMP_A, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));   // from-pinned: has CVE
        ReleaseData aV2 = release(A_V2, COMP_A, BRANCH_A, "2.0",
                ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics());       // to-pinned: dropped CVE
        stubNoEvents(); // both endpoints degrade to their pinned release's current metrics

        List<ProductConstituent> from = List.of(constituent(COMP_A, "comp-a", aV1));
        List<ProductConstituent> to = List.of(constituent(COMP_A, "comp-a", aV2));

        FindingChangesWithAttribution result = service()
                .computeProductPostureDiff(ORG, from, to, FROM, TO);

        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-8003");
        assertNotNull(v);
        assertTrue(v.isNetResolved(), "present@from via A@v1, absent@to via re-pinned A@v2 -> net resolved");
        assertFalse(v.isNetAppeared());
        assertTrue(v.orgContext().isFullyResolved());
        assertEquals(1, result.totalResolved());
        assertEquals(0, result.totalAppeared());
    }

    // ---- (d) constituent ADDED in to-product -> its findings appear ----

    /**
     * The to-product-release adds a brand-new constituent component B (absent from the from-product-release)
     * carrying a finding. Since B contributes nothing at {@code from} and a finding at {@code to}, that
     * finding folds as net-appeared / new-to-product. Component A (present at both, unchanged) stays
     * StillPresent, so the addition is isolated to B.
     */
    @Test
    void constituentAddedInToProduct_itsFindingsAppear() {
        VulnerabilityDto aCve = vuln("CVE-2026-8004", LODASH, VulnerabilitySeverity.MEDIUM, false);
        VulnerabilityDto bCve = vuln("CVE-2026-8005", AXIOS, VulnerabilitySeverity.HIGH, false);
        ReleaseData aV1 = release(A_V1, COMP_A, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(aCve));   // present at both endpoints
        ReleaseData bV1 = release(B_V1, COMP_B, BRANCH_B, "1.0",
                ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics(bCve));   // added only in to-product
        stubNoEvents();

        // from: only A. to: A (unchanged) + newly-added B.
        List<ProductConstituent> from = List.of(constituent(COMP_A, "comp-a", aV1));
        List<ProductConstituent> to = List.of(
                constituent(COMP_A, "comp-a", aV1),
                constituent(COMP_B, "comp-b", bV1));

        FindingChangesWithAttribution result = service()
                .computeProductPostureDiff(ORG, from, to, FROM, TO);

        VulnerabilityWithAttribution vb = findVuln(result, "CVE-2026-8005");
        assertNotNull(vb, "added constituent's finding must surface");
        assertTrue(vb.isNetAppeared(), "constituent B added in to-product -> its finding is net-appeared");
        assertTrue(vb.orgContext().isNewToOrganization(), "new-to-product");

        VulnerabilityWithAttribution va = findVuln(result, "CVE-2026-8004");
        assertNotNull(va);
        assertTrue(va.isStillPresent(), "unchanged constituent A -> StillPresent");
        assertFalse(va.isNetAppeared());

        assertEquals(1, result.totalAppeared(), "only B's finding is net-New");
        assertEquals(0, result.totalResolved());
    }

    // ---- (e) FEATURE-SET scope: single-branch product-release resolution, re-pin -> Resolved ----

    /**
     * FEATURE-SET-scoped (single product branch) posture-diff -- the actual board-task #38 phase-3
     * product-feature-set fix in {@link ChangeLogService#computeProductBranchPostureDiff}. A "feature set" is
     * a product's branch, so this drives {@code componentChangelogByDate} on a PRODUCT with {@code branchUuid}
     * set. Unlike the all-feature-sets path it resolves the from/to product-releases on THAT ONE feature set
     * only via {@link SharedReleaseService#getBranchLatestReleaseAtOrBeforeDate} -- never enumerating the
     * product's other branches (the mock only stubs that call for {@code FEATURE_SET}).
     *
     * <p>The feature set's from-product-release pins a VULN constituent (A@v1 carrying CVE); its
     * to-product-release re-pins that constituent to a PATCHED version (A@v2, CVE dropped). The finding must
     * fold Resolved at feature-set scope -- proving the single-branch product-release resolution feeds the
     * SAME pinned-constituent + {@code FindingComparisonService.computeProductPostureDiff} machinery as the
     * all-branches path.
     */
    @Test
    void featureSetScope_rePinsConstituentToPatchedVersion_isNetResolved() {
        VulnerabilityDto cve = vuln("CVE-2026-8006", LODASH, VulnerabilitySeverity.HIGH, false);
        ReleaseData aV1 = release(A_V1, COMP_A, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));  // from-pinned: has CVE
        ReleaseData aV2 = release(A_V2, COMP_A, BRANCH_A, "2.0",
                ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics());      // to-pinned: dropped CVE
        stubNoEvents();

        // Single-branch (feature-set) resolution: the from/to product-releases come ONLY from FEATURE_SET.
        ReleaseData fromProd = productRelease(PROD_REL_FROM, FEATURE_SET, "10.0",
                ZonedDateTime.parse("2026-05-20T00:00:00Z"), A_V1);
        ReleaseData toProd = productRelease(PROD_REL_TO, FEATURE_SET, "11.0",
                ZonedDateTime.parse("2026-06-12T00:00:00Z"), A_V2);
        lenient().when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(FEATURE_SET, FROM))
                .thenReturn(Optional.of(fromProd));
        lenient().when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(FEATURE_SET, TO))
                .thenReturn(Optional.of(toProd));
        lenient().when(sharedReleaseService.getReleaseDataList(any(), any()))
                .thenReturn(List.of(aV1, aV2));
        ComponentData compA = componentData(COMP_A, "comp-a");
        lenient().when(getComponentService.getComponentData(COMP_A)).thenReturn(Optional.of(compA));

        ComponentData product = componentData(PRODUCT, "the-product");
        FindingChangesWithAttribution result =
                invokeProductBranchPostureDiff(changeLogService(), product);

        assertNotNull(result, "feature-set-scoped product posture must be populated, not null");
        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-8006");
        assertNotNull(v);
        assertTrue(v.isNetResolved(),
                "present@from via A@v1, absent@to via re-pinned A@v2 on the SAME feature set -> net resolved");
        assertFalse(v.isNetAppeared());
        assertEquals(1, result.totalResolved());
        assertEquals(0, result.totalAppeared());
    }

    /**
     * A retired (END_OF_LIFE) feature-set product-release leaves the posture. The SAME product release
     * spans from->to (the common retirement pattern: a release transitions to EOL in place), so both
     * endpoint selectors resolve to it and both drop it -> the pinned constituent's finding surfaces at
     * neither endpoint, and there is NO phantom "resolved". Proves the CLS product endpoint selector
     * (latestProductBranchReleaseAtOrBefore) skips retired releases on the product path, which bypasses
     * the component/branch in-window filter in computePostureDiff.
     */
    @Test
    void featureSetScope_retiredProductRelease_leavesPosture() {
        VulnerabilityDto cve = vuln("CVE-2026-8009", LODASH, VulnerabilitySeverity.HIGH, false);
        ReleaseData aV1 = release(A_V1, COMP_A, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));
        stubNoEvents();

        // ONE retired product release spanning both endpoints (transitioned to EOL in place).
        ReleaseData retiredProd = productRelease(PROD_REL_FROM, FEATURE_SET, "10.0",
                ZonedDateTime.parse("2026-05-20T00:00:00Z"), A_V1);
        lenient().when(retiredProd.getLifecycle()).thenReturn(ReleaseLifecycle.END_OF_LIFE);
        lenient().when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(FEATURE_SET, FROM))
                .thenReturn(Optional.of(retiredProd));
        lenient().when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(FEATURE_SET, TO))
                .thenReturn(Optional.of(retiredProd));
        lenient().when(sharedReleaseService.getReleaseDataList(any(), any()))
                .thenReturn(List.of(aV1));
        ComponentData compA = componentData(COMP_A, "comp-a");
        lenient().when(getComponentService.getComponentData(COMP_A)).thenReturn(Optional.of(compA));

        ComponentData product = componentData(PRODUCT, "the-product");
        FindingChangesWithAttribution result =
                invokeProductBranchPostureDiff(changeLogService(), product);

        // Both endpoints drop the same retired release -> no surviving product release -> no posture at all
        // (off the board), and therefore no phantom "resolved".
        assertNull(result,
                "a retired (END_OF_LIFE) feature-set product release must yield no posture-diff (off the board)");
    }

    // ---- F2: an EXCLUDED feature set has no analytics participation -> empty posture-diff ----

    /**
     * REGRESSION GUARD for board task F2 (feature-set path). A feature set whose
     * {@code findingAnalyticsParticipation == EXCLUDED} has no analytics chart line, so its posture-diff
     * Summary must be empty too -- {@link ChangeLogService#computeProductBranchPostureDiff} returns null
     * before resolving any product-release.
     *
     * <p>The fixture wires the SAME populated scenario as
     * {@code featureSetScope_rePinsConstituentToPatchedVersion_isNetResolved} (a re-pin that folds to a real
     * Net-Resolved), so WITHOUT the guard this method would return a populated, non-null diff -- the test is
     * RED without the fix. WITH the guard the EXCLUDED feature set short-circuits to null before any
     * product-release is resolved.
     */
    @Test
    void featureSetScope_excludedFeatureSet_returnsNullPosture() {
        VulnerabilityDto cve = vuln("CVE-2026-8008", LODASH, VulnerabilitySeverity.HIGH, false);
        ReleaseData aV1 = release(A_V1, COMP_A, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));  // from-pinned: has CVE
        ReleaseData aV2 = release(A_V2, COMP_A, BRANCH_A, "2.0",
                ZonedDateTime.parse("2026-06-10T00:00:00Z"), metrics());      // to-pinned: dropped CVE
        stubNoEvents();

        ReleaseData fromProd = productRelease(PROD_REL_FROM, FEATURE_SET, "10.0",
                ZonedDateTime.parse("2026-05-20T00:00:00Z"), A_V1);
        ReleaseData toProd = productRelease(PROD_REL_TO, FEATURE_SET, "11.0",
                ZonedDateTime.parse("2026-06-12T00:00:00Z"), A_V2);
        lenient().when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(FEATURE_SET, FROM))
                .thenReturn(Optional.of(fromProd));
        lenient().when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(FEATURE_SET, TO))
                .thenReturn(Optional.of(toProd));
        lenient().when(sharedReleaseService.getReleaseDataList(any(), any()))
                .thenReturn(List.of(aV1, aV2));
        ComponentData compA = componentData(COMP_A, "comp-a");
        lenient().when(getComponentService.getComponentData(COMP_A)).thenReturn(Optional.of(compA));

        // The feature set is EXCLUDED from finding analytics -> the guard short-circuits to null.
        BranchData excludedFs = mock(BranchData.class);
        lenient().when(excludedFs.getUuid()).thenReturn(FEATURE_SET);
        lenient().when(excludedFs.getStatus()).thenReturn(StatusEnum.ACTIVE);
        lenient().when(excludedFs.getFindingAnalyticsParticipation())
                .thenReturn(FindingAnalyticsParticipation.EXCLUDED);
        lenient().when(branchService.getBranchData(FEATURE_SET)).thenReturn(Optional.of(excludedFs));

        ComponentData product = componentData(PRODUCT, "the-product");
        FindingChangesWithAttribution result =
                invokeProductBranchPostureDiff(changeLogService(), product);

        assertNull(result, "EXCLUDED feature set must yield no posture-diff even though a real re-pin "
                + "(A@v1 -> A@v2) would otherwise fold to Net-Resolved (matches the analytics chart)");
    }

    // ---- (f) FEATURE-SET scope, EMPTY WINDOW: only a pre-window product-release -> StillPresent ----

    /**
     * FEATURE-SET-scoped EMPTY-WINDOW case: the feature set has a product-release {@code <= from} but NONE
     * in-window, so both endpoints resolve to the SAME pre-window product-release (its pinned constituent
     * carries a finding at both reconstructed endpoints). It must classify StillPresent -- the feature-set
     * empty-window bypass surfaces posture even with no in-window product-release, scoped to the single
     * branch.
     */
    @Test
    void featureSetScope_emptyWindowOnlyPreWindowProductRelease_isStillPresent() {
        VulnerabilityDto cve = vuln("CVE-2026-8007", AXIOS, VulnerabilitySeverity.HIGH, false);
        ReleaseData aV1 = release(A_V1, COMP_A, BRANCH_A, "1.0",
                ZonedDateTime.parse("2026-05-15T00:00:00Z"), metrics(cve));
        stubNoEvents();  // from degrades to current (=to) metrics -> present at both endpoints

        // Only a pre-window product-release on the feature set; it carries through to `to` (same release).
        ReleaseData preWindowProd = productRelease(PROD_REL_FROM, FEATURE_SET, "10.0",
                ZonedDateTime.parse("2026-05-20T00:00:00Z"), A_V1);
        lenient().when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(FEATURE_SET, FROM))
                .thenReturn(Optional.of(preWindowProd));
        lenient().when(sharedReleaseService.getBranchLatestReleaseAtOrBeforeDate(FEATURE_SET, TO))
                .thenReturn(Optional.of(preWindowProd));
        lenient().when(sharedReleaseService.getReleaseDataList(any(), any()))
                .thenReturn(List.of(aV1));
        ComponentData compA = componentData(COMP_A, "comp-a");
        lenient().when(getComponentService.getComponentData(COMP_A)).thenReturn(Optional.of(compA));

        ComponentData product = componentData(PRODUCT, "the-product");
        FindingChangesWithAttribution result =
                invokeProductBranchPostureDiff(changeLogService(), product);

        assertNotNull(result);
        VulnerabilityWithAttribution v = findVuln(result, "CVE-2026-8007");
        assertNotNull(v, "pre-window pinned-constituent finding must surface in feature-set posture");
        assertTrue(v.isStillPresent(),
                "present at both endpoints via the same pre-window product-release -> StillPresent");
        assertFalse(v.isNetAppeared());
        assertFalse(v.isNetResolved());
        assertEquals(0, result.totalAppeared());
        assertEquals(0, result.totalResolved());
    }
}
