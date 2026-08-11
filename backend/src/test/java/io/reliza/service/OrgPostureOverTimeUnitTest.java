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
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.dto.ChangelogRecords.FindingChangeKind;
import io.reliza.dto.ChangelogRecords.MetricsRevisionFindingChange;
import io.reliza.model.FindingChangeEvent;
import io.reliza.model.OrganizationData;
import io.reliza.repositories.MetricsAuditRepository;
import io.reliza.service.FindingComparisonService.FindingChangeTimelinePage;
import io.reliza.service.FindingComparisonService.OverTimeFindingChangesResult;

/**
 * Unit coverage for the ORG posture-over-time seam ({@link FindingComparisonService#loadOrgPostureTimeline}
 * and {@link FindingComparisonService#isOrgPostureReadAvailable}, board task #39). Exercises the read-side
 * logic in isolation (EXCLUDED-branch filtering, per-finding filter, newest-first ordering, pagination +
 * {@code total}, retention clamp, and the v3-only availability gate) with a mocked v3 hydrate. The DB query
 * itself ({@code findInRangeByOrgAndComponents}) and end-to-end behavior are validated on sandbox.
 */
public class OrgPostureOverTimeUnitTest {

	private BranchService branchService;
	private SharedReleaseService sharedReleaseService;
	private GetComponentService getComponentService;
	private MetricsAuditRepository metricsAuditRepository;
	private GetOrganizationService getOrganizationService;
	private FindingDimBackfillService findingDimBackfillService;
	private FindingComparisonService svc;

	private final UUID ORG = UUID.randomUUID();
	private final UUID COMP_A = UUID.randomUUID();
	private final UUID BRANCH_OK = UUID.randomUUID();
	private final UUID BRANCH_EXCLUDED = UUID.randomUUID();
	private final ZonedDateTime FROM = ZonedDateTime.now().minusDays(7);
	private final ZonedDateTime TO = ZonedDateTime.now();

	@BeforeEach
	void setUp() {
		branchService = mock(BranchService.class);
		sharedReleaseService = mock(SharedReleaseService.class);
		getComponentService = mock(GetComponentService.class);
		metricsAuditRepository = mock(MetricsAuditRepository.class);
		getOrganizationService = mock(GetOrganizationService.class);
		findingDimBackfillService = mock(FindingDimBackfillService.class);

		svc = new FindingComparisonService(branchService, sharedReleaseService, getComponentService,
				metricsAuditRepository, getOrganizationService);
		ReflectionTestUtils.setField(svc, "findingDimBackfillService", findingDimBackfillService);

		// No retention settings -> clampToRetention is a no-op (full history) unless a test overrides.
		when(getOrganizationService.getOrganizationData(any())).thenReturn(Optional.empty());
	}

	private FindingChangeEvent vuln(String key, UUID branch, ZonedDateTime when) {
		FindingChangeEvent ev = new FindingChangeEvent();
		ev.setFindingKind(FindingChangeEvent.FindingKind.VULNERABILITY);
		ev.setChangeKind(FindingChangeKind.APPEARED);
		ev.setChangeDate(when);
		ev.setFindingKey(key);
		ev.setVulnId(key);
		ev.setBranchUuid(branch);
		ev.setComponentUuid(COMP_A);
		return ev;
	}

	private void stubHydrate(List<FindingChangeEvent> events) {
		when(findingDimBackfillService.hydrateInRangeByComponentsV3(eq(ORG), anyCollection(), any(), any(), any()))
				.thenReturn(events);
	}

	@Test
	void excludedBranchEventsAreDropped() {
		stubHydrate(List.of(
				vuln("CVE-1", BRANCH_OK, TO.minusDays(1)),
				vuln("CVE-2", BRANCH_EXCLUDED, TO.minusDays(1)),
				vuln("CVE-3", BRANCH_OK, TO.minusDays(1))));

		FindingChangeTimelinePage page = svc.loadOrgPostureTimeline(
				ORG, Set.of(COMP_A), Set.of(BRANCH_EXCLUDED), FROM, TO, null, 0, 50);

		assertEquals(2, page.total(), "EXCLUDED-branch event must be filtered out");
		assertTrue(page.items().stream().noneMatch(i -> "CVE-2".equals(vulnId(i))));
	}

	@Test
	void findingKeyFilterNarrowsToOneFinding() {
		stubHydrate(List.of(
				vuln("CVE-1", BRANCH_OK, TO.minusDays(2)),
				vuln("CVE-2", BRANCH_OK, TO.minusDays(1))));

		FindingChangeTimelinePage page = svc.loadOrgPostureTimeline(
				ORG, Set.of(COMP_A), Set.of(), FROM, TO, "CVE-2", 0, 50);

		assertEquals(1, page.total());
		assertEquals("CVE-2", vulnId(page.items().get(0)));
	}

	@Test
	void newestFirstOrderingAndPagination() {
		ZonedDateTime t1 = TO.minusDays(5); // oldest
		ZonedDateTime t2 = TO.minusDays(3);
		ZonedDateTime t3 = TO.minusDays(1); // newest
		stubHydrate(List.of(vuln("OLD", BRANCH_OK, t1), vuln("MID", BRANCH_OK, t2), vuln("NEW", BRANCH_OK, t3)));

		FindingChangeTimelinePage p0 = svc.loadOrgPostureTimeline(ORG, Set.of(COMP_A), Set.of(), FROM, TO, null, 0, 2);
		assertEquals(3, p0.total(), "total is the full uncapped count");
		assertEquals(2, p0.items().size());
		assertEquals("NEW", vulnId(p0.items().get(0)), "newest first");
		assertEquals("MID", vulnId(p0.items().get(1)));

		FindingChangeTimelinePage p1 = svc.loadOrgPostureTimeline(ORG, Set.of(COMP_A), Set.of(), FROM, TO, null, 1, 2);
		assertEquals(1, p1.items().size());
		assertEquals("OLD", vulnId(p1.items().get(0)), "second page is the oldest remaining");
	}

	// ---- F4: truncated flag on the org ALL_POSTURE timeline (drawer) ----

	@Test
	void orgTimeline_underCap_notTruncated() {
		stubHydrate(List.of(vuln("CVE-1", BRANCH_OK, TO.minusDays(1))));
		FindingChangeTimelinePage page = svc.loadOrgPostureTimeline(ORG, Set.of(COMP_A), Set.of(), FROM, TO, null, 0, 50);
		assertFalse(page.truncated(), "scan under the cap -> total is the true count");
	}

	@Test
	void orgTimeline_scanHitsCap_truncated() {
		// A hydrate returning exactly ORG_POSTURE_SCAN_CAP rows means older events were never scanned, so the
		// paged total is a floor -> truncated must be flagged so the drawer renders "N+" not an exact count.
		List<FindingChangeEvent> capful = new ArrayList<>(FindingComparisonService.ORG_POSTURE_SCAN_CAP);
		for (int i = 0; i < FindingComparisonService.ORG_POSTURE_SCAN_CAP; i++) {
			capful.add(vuln("CVE-CAP-" + i, BRANCH_OK, TO.minusSeconds(i + 1)));
		}
		stubHydrate(capful);
		FindingChangeTimelinePage page = svc.loadOrgPostureTimeline(ORG, Set.of(COMP_A), Set.of(), FROM, TO, null, 0, 50);
		assertTrue(page.truncated(), "scan hit the row cap -> truncated");
	}

	@Test
	void releaseTimeline_neverTruncated() {
		// Release-anchored read is unbounded, so the drawer's total is always the true count.
		when(findingDimBackfillService.hydrateInRangeV3(eq(ORG), anyCollection(), any(), any()))
				.thenReturn(List.of(vuln("CVE-1", BRANCH_OK, TO.minusDays(1))));
		FindingChangeTimelinePage page = svc.loadFindingChangeTimeline(
				ORG, Set.of(UUID.randomUUID()), FROM, TO, null, 0, 50);
		assertFalse(page.truncated(), "release-anchored timeline is never truncated");
	}

	@Test
	void retentionClampRaisesFromAndSurfacesSince() {
		// Org with retention enabled at 30 days; requested FROM (7d) is INSIDE the horizon so no clamp,
		// but a far-back request must be clamped. Use a 3-day retention to force it.
		OrganizationData od = mock(OrganizationData.class);
		OrganizationData.Settings settings = mock(OrganizationData.Settings.class);
		when(settings.isFindingChangeRetentionEnabled()).thenReturn(true);
		when(settings.getFindingChangeRetentionDays()).thenReturn(3);
		when(od.getSettings()).thenReturn(settings);
		when(getOrganizationService.getOrganizationData(eq(ORG))).thenReturn(Optional.of(od));
		stubHydrate(List.of(vuln("CVE-1", BRANCH_OK, TO.minusDays(1))));

		ArgumentCaptor<ZonedDateTime> fromCaptor = ArgumentCaptor.forClass(ZonedDateTime.class);
		FindingChangeTimelinePage page = svc.loadOrgPostureTimeline(
				ORG, Set.of(COMP_A), Set.of(), FROM, TO, null, 0, 50);

		assertNotNull(page.since(), "clampedSince disclosed when FROM predates the 3-day horizon");
		verify(findingDimBackfillService)
				.hydrateInRangeByComponentsV3(eq(ORG), anyCollection(), fromCaptor.capture(), any(), any());
		long daysBack = ChronoUnit.DAYS.between(fromCaptor.getValue().toLocalDate(), TO.toLocalDate());
		assertEquals(3, daysBack, "the v3 read is executed from the retention horizon, not the raw FROM");
	}

	@Test
	void emptyAllowedComponentsReturnsEmpty() {
		FindingChangeTimelinePage page = svc.loadOrgPostureTimeline(ORG, Set.of(), Set.of(), FROM, TO, null, 0, 50);
		assertEquals(FindingChangeTimelinePage.EMPTY, page);
	}

	@Test
	void availabilityGateRequiresBackfillServiceAndOrg() {
		assertTrue(svc.isOrgPostureReadAvailable(ORG), "backfill service wired -> available");

		ReflectionTestUtils.setField(svc, "findingDimBackfillService", null);
		assertFalse(svc.isOrgPostureReadAvailable(ORG), "no backfill service -> not available");

		ReflectionTestUtils.setField(svc, "findingDimBackfillService", findingDimBackfillService);
		assertFalse(svc.isOrgPostureReadAvailable(null), "null org -> not available");
	}

	// ---- loadOrgPostureOverTime: rescan-inclusive org over-time list (board task #42) ----

	@Test
	void orgOverTime_excludedBranchDroppedAndSortedAscending() {
		stubHydrate(List.of(
				vuln("CVE-3", BRANCH_OK, TO.minusDays(1)),
				vuln("CVE-2", BRANCH_EXCLUDED, TO.minusDays(2)),
				vuln("CVE-1", BRANCH_OK, TO.minusDays(3))));

		OverTimeFindingChangesResult res = svc.loadOrgPostureOverTime(
				ORG, Set.of(COMP_A), Set.of(BRANCH_EXCLUDED), FROM, TO);

		assertEquals(2, res.total(), "EXCLUDED-branch event must be dropped from the total");
		assertEquals(2, res.changes().size());
		assertTrue(res.changes().stream().noneMatch(c -> "CVE-2".equals(vulnId(c))));
		// inline list is date-ASC (oldest first), matching loadOverTimeFindingChanges
		assertTrue(res.changes().get(0).changeDate().isBefore(res.changes().get(1).changeDate()));
	}

	@Test
	void orgOverTime_returnsEmptyWhenReadUnavailable() {
		// No v3 hydration service wired -> the org over-time read is unavailable and the caller falls back
		// to the release-anchored path.
		ReflectionTestUtils.setField(svc, "findingDimBackfillService", null);
		OverTimeFindingChangesResult res = svc.loadOrgPostureOverTime(ORG, Set.of(COMP_A), Set.of(), FROM, TO);
		assertEquals(OverTimeFindingChangesResult.EMPTY, res, "read-unavailable orgs fall back (release-anchored)");
	}

	@Test
	void orgOverTime_returnsEmptyForEmptyComponents() {
		OverTimeFindingChangesResult res = svc.loadOrgPostureOverTime(ORG, Set.of(), Set.of(), FROM, TO);
		assertEquals(OverTimeFindingChangesResult.EMPTY, res);
	}

	@Test
	void orgOverTime_retentionClampSurfacesSince() {
		OrganizationData od = mock(OrganizationData.class);
		OrganizationData.Settings settings = mock(OrganizationData.Settings.class);
		when(settings.isFindingChangeRetentionEnabled()).thenReturn(true);
		when(settings.getFindingChangeRetentionDays()).thenReturn(3);
		when(od.getSettings()).thenReturn(settings);
		when(getOrganizationService.getOrganizationData(eq(ORG))).thenReturn(Optional.of(od));
		stubHydrate(List.of(vuln("CVE-1", BRANCH_OK, TO.minusDays(1))));

		OverTimeFindingChangesResult res = svc.loadOrgPostureOverTime(ORG, Set.of(COMP_A), Set.of(), FROM, TO);
		assertNotNull(res.clampedSince(), "clampedSince disclosed when FROM predates the retention horizon");
	}

	private static String vulnId(MetricsRevisionFindingChange c) {
		return c.vulnerability() == null ? null : c.vulnerability().vulnId();
	}
}
