/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.common.Utils;
import io.reliza.model.VulnerabilityRecord;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.model.VulnerabilityRecordData.AffectedIdentityType;
import io.reliza.model.VulnerabilityRecordData.AffectedRange;
import io.reliza.model.VulnerabilityRecordData.UpstreamSource;
import io.reliza.service.IntegrationService.AffectedRangesFetchResult;
import io.reliza.service.IntegrationService.AffectedRangesFetchStatus;
import io.reliza.service.VulnerabilityRecordService.AffectedRangesWindow;
import io.reliza.service.VulnerabilityRecordService.FetchedAffectedRanges;

/**
 * The nightly affected-ranges sweep's control flow, over mocked record and
 * Dependency-Track access: what counts against the per-run limit, which
 * outcomes are stored, and when an org is left for the next run. Selection
 * itself (which rows are due) is SQL and is pinned against the database in
 * {@link VulnerabilityRecordAffectedRangesStoreTest}.
 */
class AffectedRangesServiceTest {

	private VulnerabilityRecordService records;
	private IntegrationService integration;
	private AffectedRangesService sweep;
	private final Map<UUID, VulnerabilityRecordData> byUuid = new HashMap<>();

	@BeforeEach
	void setUp() {
		records = mock(VulnerabilityRecordService.class);
		integration = mock(IntegrationService.class);
		sweep = new AffectedRangesService();
		ReflectionTestUtils.setField(sweep, "vulnerabilityRecordService", records);
		ReflectionTestUtils.setField(sweep, "integrationService", integration);
		ReflectionTestUtils.setField(sweep, "fetchLimit", 500);
		when(records.getRecord(any())).thenAnswer(i -> Optional.ofNullable(byUuid.get(i.getArgument(0)))
				.map(AffectedRangesServiceTest::row));
		when(records.storeAffectedRanges(any(), any())).thenReturn(true);
	}

	private static VulnerabilityRecord row(VulnerabilityRecordData d) {
		VulnerabilityRecord vr = new VulnerabilityRecord();
		vr.setUuid(d.getUuid());
		vr.setOrg(d.getOrg());
		vr.setPrimaryVulnId(d.getPrimaryVulnId());
		vr.setRecordData(Utils.dataToRecord(d));
		return vr;
	}

	private UUID due(UUID org, String vulnId) {
		VulnerabilityRecordData d = new VulnerabilityRecordData();
		d.setUuid(UUID.randomUUID());
		d.setOrg(org);
		d.setPrimaryVulnId(vulnId);
		byUuid.put(d.getUuid(), d);
		return d.getUuid();
	}

	/** The first pass: records due for another reason than age. */
	private static Instant firstPass() {
		return eq(Instant.EPOCH);
	}

	/** The second pass: refreshes by age. */
	private static Instant secondPass() {
		return argThat(i -> !Instant.EPOCH.equals(i));
	}

	/** One window holding {@code due} in the first pass, then the end of the table; nothing due by age. */
	private void windowOf(List<UUID> due) {
		when(records.nextAffectedRangesWindow(any(), anyInt(), any(), any(), any()))
				.thenReturn(new AffectedRangesWindow(List.of(), null));
		when(records.nextAffectedRangesWindow(any(), anyInt(), firstPass(), firstPass(), any()))
				.thenReturn(new AffectedRangesWindow(due, null));
	}

	/** Nothing due in the first pass; {@code window} for every window of the second. */
	private void byAgeWindows(AffectedRangesWindow window) {
		when(records.nextAffectedRangesWindow(any(), anyInt(), any(), any(), any())).thenReturn(window);
		when(records.nextAffectedRangesWindow(any(), anyInt(), firstPass(), firstPass(), any()))
				.thenReturn(new AffectedRangesWindow(List.of(), null));
	}

	private static AffectedRangesFetchResult fetched(int requests, AffectedRange... ranges) {
		return new AffectedRangesFetchResult(AffectedRangesFetchStatus.FETCHED,
				new FetchedAffectedRanges(List.of(ranges), ZonedDateTime.now(), Set.of()), requests);
	}

	private static AffectedRangesFetchResult status(AffectedRangesFetchStatus status) {
		return new AffectedRangesFetchResult(status, null, 1);
	}

	@Test
	void theLimitCountsOnlyRecordsThatNeededARequest() {
		ReflectionTestUtils.setField(sweep, "fetchLimit", 2);
		UUID org = UUID.randomUUID();
		List<UUID> due = new ArrayList<>();
		for (int i = 0; i < 5; i++) due.add(due(org, "V-" + i));
		windowOf(due);
		// The first record needs no request (no GitHub / OSV source); the others do.
		when(integration.fetchAffectedRanges(eq(org), any()))
				.thenReturn(fetched(0), fetched(1), fetched(1), fetched(1), fetched(1));

		sweep.refreshDueAffectedRanges();

		verify(integration, times(3)).fetchAffectedRanges(eq(org), any());
		verify(records, times(3)).storeAffectedRanges(any(), any());
	}

	@Test
	void anOrgWithoutDependencyTrackIsStampedWithTheRangesItHas() {
		UUID uuid = due(UUID.randomUUID(), "GHSA-f2jv-r9rf-7988");
		AffectedRange kept = new AffectedRange();
		kept.setIdentityType(AffectedIdentityType.PURL);
		kept.setIdentity("pkg:npm/handlebars");
		kept.setVersionEndExcluding("4.7.7");
		kept.setSources(List.of(UpstreamSource.GITHUB));
		byUuid.get(uuid).setAffectedRanges(List.of(kept));
		windowOf(List.of(uuid));
		when(integration.fetchAffectedRanges(any(), any()))
				.thenReturn(new AffectedRangesFetchResult(AffectedRangesFetchStatus.NO_INTEGRATION, null, 0));

		sweep.refreshDueAffectedRanges();

		ArgumentCaptor<FetchedAffectedRanges> stored = ArgumentCaptor.forClass(FetchedAffectedRanges.class);
		verify(records).storeAffectedRanges(eq(uuid), stored.capture());
		assertEquals(byUuid.get(uuid).getAffectedRanges(), stored.getValue().ranges());
	}

	@Test
	void aRefusedKeySkipsTheRestOfThatOrgButNotOtherOrgs() {
		UUID refused = UUID.randomUUID();
		UUID fine = UUID.randomUUID();
		UUID r1 = due(refused, "A-1");
		UUID r2 = due(refused, "A-2");
		UUID f1 = due(fine, "B-1");
		windowOf(List.of(r1, r2, f1));
		when(integration.fetchAffectedRanges(eq(refused), any())).thenReturn(status(AffectedRangesFetchStatus.AUTH_REJECTED));
		when(integration.fetchAffectedRanges(eq(fine), any())).thenReturn(fetched(1));

		sweep.refreshDueAffectedRanges();

		verify(integration, times(1)).fetchAffectedRanges(eq(refused), any());
		verify(records, never()).storeAffectedRanges(eq(r1), any());
		verify(records).storeAffectedRanges(eq(f1), any());
	}

	@Test
	void threeUnreachableInARowSkipTheOrg() {
		UUID org = UUID.randomUUID();
		List<UUID> due = new ArrayList<>();
		for (int i = 0; i < 5; i++) due.add(due(org, "V-" + i));
		windowOf(due);
		when(integration.fetchAffectedRanges(eq(org), any())).thenReturn(status(AffectedRangesFetchStatus.UNREACHABLE));

		sweep.refreshDueAffectedRanges();

		verify(integration, times(3)).fetchAffectedRanges(eq(org), any());
		verify(records, never()).storeAffectedRanges(any(), any());
	}

	@Test
	void aReachableAnswerResetsTheUnreachableCount() {
		UUID org = UUID.randomUUID();
		List<UUID> due = new ArrayList<>();
		for (int i = 0; i < 5; i++) due.add(due(org, "V-" + i));
		windowOf(due);
		when(integration.fetchAffectedRanges(eq(org), any())).thenReturn(
				status(AffectedRangesFetchStatus.UNREACHABLE), status(AffectedRangesFetchStatus.UNREACHABLE), fetched(1),
				status(AffectedRangesFetchStatus.UNREACHABLE), status(AffectedRangesFetchStatus.UNREACHABLE));

		sweep.refreshDueAffectedRanges();

		verify(integration, times(5)).fetchAffectedRanges(eq(org), any());
		verify(records, times(1)).storeAffectedRanges(any(), any());
	}

	@Test
	void recordsThatAlwaysFailDoNotStarveTheRestOfTheirOrg() {
		UUID org = UUID.randomUUID();
		List<UUID> due = new ArrayList<>();
		for (int i = 0; i < 6; i++) due.add(due(org, "V-" + i));
		windowOf(due);
		// Dependency-Track answers, but fails these vulnerabilities; the last one it serves.
		when(integration.fetchAffectedRanges(eq(org), any())).thenReturn(
				status(AffectedRangesFetchStatus.FAILED), status(AffectedRangesFetchStatus.FAILED),
				status(AffectedRangesFetchStatus.FAILED), status(AffectedRangesFetchStatus.FAILED),
				status(AffectedRangesFetchStatus.FAILED), fetched(1));

		sweep.refreshDueAffectedRanges();

		verify(integration, times(6)).fetchAffectedRanges(eq(org), any());
		verify(records).storeAffectedRanges(eq(due.get(5)), any());
		verify(records, never()).storeAffectedRanges(eq(due.get(0)), any());
	}

	@Test
	void theNextRunResumesAfterTheLastRecordLookedAt() {
		ReflectionTestUtils.setField(sweep, "fetchLimit", 2);
		UUID org = UUID.randomUUID();
		List<UUID> due = new ArrayList<>();
		for (int i = 0; i < 4; i++) due.add(due(org, "V-" + i));
		byAgeWindows(new AffectedRangesWindow(due, UUID.randomUUID()));
		when(integration.fetchAffectedRanges(eq(org), any())).thenReturn(fetched(1));

		sweep.refreshDueAffectedRanges();
		sweep.refreshDueAffectedRanges();

		ArgumentCaptor<UUID> cursors = ArgumentCaptor.forClass(UUID.class);
		verify(records, times(2)).nextAffectedRangesWindow(cursors.capture(), anyInt(), secondPass(), secondPass(), any());
		// The first run starts at the beginning and stops after two fetches; the second picks up there.
		assertEquals(Arrays.asList(null, due.get(1)), cursors.getAllValues());
	}

	@Test
	void walksEveryWindowUntilTheTableEnds() {
		UUID org = UUID.randomUUID();
		UUID first = due(org, "V-1");
		UUID second = due(org, "V-2");
		UUID cursor = UUID.randomUUID();
		when(records.nextAffectedRangesWindow(any(), anyInt(), any(), any(), any()))
				.thenReturn(new AffectedRangesWindow(List.of(), null));
		when(records.nextAffectedRangesWindow(any(), anyInt(), firstPass(), firstPass(), any()))
				.thenReturn(new AffectedRangesWindow(List.of(first), cursor))
				.thenReturn(new AffectedRangesWindow(List.of(), cursor))
				.thenReturn(new AffectedRangesWindow(List.of(second), null));
		when(integration.fetchAffectedRanges(eq(org), any())).thenReturn(fetched(1));

		sweep.refreshDueAffectedRanges();

		verify(records, times(3)).nextAffectedRangesWindow(any(), anyInt(), firstPass(), firstPass(), any());
		verify(records).storeAffectedRanges(eq(first), any());
		verify(records).storeAffectedRanges(eq(second), any());
	}

	@Test
	void recordsWithoutAnUpdatedDateHaveTheirOwnRefreshWindow() {
		ReflectionTestUtils.setField(sweep, "refreshDays", 90);
		ReflectionTestUtils.setField(sweep, "undatedRefreshDays", 7);
		windowOf(List.of());

		sweep.refreshDueAffectedRanges();

		ArgumentCaptor<Instant> staleBefore = ArgumentCaptor.forClass(Instant.class);
		ArgumentCaptor<Instant> undatedStaleBefore = ArgumentCaptor.forClass(Instant.class);
		verify(records, times(2)).nextAffectedRangesWindow(any(), anyInt(), staleBefore.capture(),
				undatedStaleBefore.capture(), any());
		// The first pass leaves out every refresh by age; the second applies both windows.
		assertEquals(List.of(Instant.EPOCH, Instant.EPOCH),
				List.of(staleBefore.getAllValues().get(0), undatedStaleBefore.getAllValues().get(0)));
		assertEquals(Duration.ofDays(83),
				Duration.between(staleBefore.getAllValues().get(1), undatedStaleBefore.getAllValues().get(1)));
	}

	@Test
	void everyWindowKnowsTheOrgsOnDependencyTrack5() {
		UUID v5Org = UUID.randomUUID();
		when(integration.listOrgsWithDtrackV5()).thenReturn(Set.of(v5Org));
		windowOf(List.of());

		sweep.refreshDueAffectedRanges();

		// Looked up once per run, handed to both passes.
		verify(integration, times(1)).listOrgsWithDtrackV5();
		verify(records, times(2)).nextAffectedRangesWindow(any(), anyInt(), any(), any(), eq(Set.of(v5Org)));
	}

	@Test
	void aRefreshWindowOfZeroDaysStillLeavesADay() {
		ReflectionTestUtils.setField(sweep, "refreshDays", 0);
		ReflectionTestUtils.setField(sweep, "undatedRefreshDays", -3);
		windowOf(List.of());

		sweep.refreshDueAffectedRanges();
		Instant dayBeforeTheRunEnded = Instant.now().minus(Duration.ofDays(1));

		ArgumentCaptor<Instant> staleBefore = ArgumentCaptor.forClass(Instant.class);
		ArgumentCaptor<Instant> undatedStaleBefore = ArgumentCaptor.forClass(Instant.class);
		verify(records, times(2)).nextAffectedRangesWindow(any(), anyInt(), staleBefore.capture(),
				undatedStaleBefore.capture(), any());
		assertFalse(staleBefore.getAllValues().get(1).isAfter(dayBeforeTheRunEnded));
		assertFalse(undatedStaleBefore.getAllValues().get(1).isAfter(dayBeforeTheRunEnded));
	}

	@Test
	void recordsDueForAnotherReasonGoBeforeRefreshesByAge() {
		ReflectionTestUtils.setField(sweep, "fetchLimit", 2);
		UUID org = UUID.randomUUID();
		UUID edited = due(org, "EDITED");
		List<UUID> old = List.of(due(org, "OLD-1"), due(org, "OLD-2"), due(org, "OLD-3"));
		when(records.nextAffectedRangesWindow(any(), anyInt(), any(), any(), any()))
				.thenReturn(new AffectedRangesWindow(old, null));
		when(records.nextAffectedRangesWindow(any(), anyInt(), firstPass(), firstPass(), any()))
				.thenReturn(new AffectedRangesWindow(List.of(edited), null));
		when(integration.fetchAffectedRanges(eq(org), any())).thenReturn(fetched(1));

		sweep.refreshDueAffectedRanges();

		verify(records).storeAffectedRanges(eq(edited), any());
		verify(records).storeAffectedRanges(eq(old.get(0)), any());
		verify(records, never()).storeAffectedRanges(eq(old.get(1)), any());
	}

	@Test
	void theFirstPassResumesSoRecordsThatKeepFailingDoNotHoldBackTheRest() {
		ReflectionTestUtils.setField(sweep, "fetchLimit", 2);
		UUID org = UUID.randomUUID();
		List<UUID> failing = List.of(due(org, "F-1"), due(org, "F-2"));
		UUID later = due(org, "LATER");
		windowOf(List.of(failing.get(0), failing.get(1), later));
		when(integration.fetchAffectedRanges(eq(org), any())).thenReturn(status(AffectedRangesFetchStatus.FAILED));

		sweep.refreshDueAffectedRanges();
		sweep.refreshDueAffectedRanges();

		ArgumentCaptor<UUID> cursors = ArgumentCaptor.forClass(UUID.class);
		verify(records, times(2)).nextAffectedRangesWindow(cursors.capture(), anyInt(), firstPass(), firstPass(), any());
		// The failing records filled the first run's limit; the second run's first pass goes on after them.
		assertEquals(Arrays.asList(null, failing.get(1)), cursors.getAllValues());
	}

	@Test
	void aRecordThatFailedInTheFirstPassIsNotTriedAgainInTheSecond() {
		UUID org = UUID.randomUUID();
		UUID failing = due(org, "V-1");
		when(records.nextAffectedRangesWindow(any(), anyInt(), any(), any(), any()))
				.thenReturn(new AffectedRangesWindow(List.of(failing), null));
		when(integration.fetchAffectedRanges(eq(org), any())).thenReturn(status(AffectedRangesFetchStatus.FAILED));

		sweep.refreshDueAffectedRanges();

		verify(records, times(2)).nextAffectedRangesWindow(any(), anyInt(), any(), any(), any());
		verify(integration, times(1)).fetchAffectedRanges(eq(org), any());
	}

	@Test
	void aRunStoppedInTheFirstPassKeepsWhereTheRefreshesByAgeStopped() {
		ReflectionTestUtils.setField(sweep, "fetchLimit", 1);
		UUID org = UUID.randomUUID();
		UUID old1 = due(org, "OLD-1");
		UUID old2 = due(org, "OLD-2");
		when(integration.fetchAffectedRanges(eq(org), any())).thenReturn(fetched(1));
		// Run 1: nothing else due; the refreshes by age stop after OLD-1.
		byAgeWindows(new AffectedRangesWindow(List.of(old1, old2), null));
		sweep.refreshDueAffectedRanges();
		// Run 2: two edited records fill the limit in the first pass.
		when(records.nextAffectedRangesWindow(any(), anyInt(), firstPass(), firstPass(), any()))
				.thenReturn(new AffectedRangesWindow(List.of(due(org, "EDITED-1"), due(org, "EDITED-2")), null));
		sweep.refreshDueAffectedRanges();
		// Run 3: the refreshes by age go on after OLD-1.
		when(records.nextAffectedRangesWindow(any(), anyInt(), firstPass(), firstPass(), any()))
				.thenReturn(new AffectedRangesWindow(List.of(), null));
		sweep.refreshDueAffectedRanges();

		ArgumentCaptor<UUID> cursors = ArgumentCaptor.forClass(UUID.class);
		verify(records, times(2)).nextAffectedRangesWindow(cursors.capture(), anyInt(), secondPass(), secondPass(), any());
		assertEquals(Arrays.asList(null, old1), cursors.getAllValues());
	}

	@Test
	void nothingDueMeansNoFetch() {
		windowOf(List.of());
		sweep.refreshDueAffectedRanges();
		verify(integration, never()).fetchAffectedRanges(any(), any());
	}
}
