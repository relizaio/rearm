/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.FanOutWalkSlice.START;
import static io.reliza.service.FanOutWalkSlice.endOf;
import static io.reliza.service.FanOutWalkTestSupport.COVERED;
import static io.reliza.service.FanOutWalkTestSupport.everyCanonicalNeedsFanOut;
import static io.reliza.service.FanOutWalkTestSupport.minutesAgo;
import static io.reliza.service.FanOutWalkTestSupport.record;
import static io.reliza.service.FanOutWalkTestSupport.recordedBucket;
import static io.reliza.service.FanOutWalkTestSupport.sortedInSlice;
import static io.reliza.service.FanOutWalkTestSupport.unrecordedBucket;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.SbomComponent;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.FanOutWalkRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SyntheticDtrackBucketRepository;

/**
 * TPF-114 mutation round: the edges of {@link SyntheticSbomService#fanOutOrg} that
 * SyntheticFanOutCandidateTest leaves open, on the same Mockito rig. A pass over buckets that
 * cover nothing; a bucket with no date; where the watermark comes from (persisted, cache, both);
 * a watermark that would sit ahead of the clock or behind a later one; the selection budget to
 * the nanosecond; a stamp that fails on an applied canonical; a watermark that cannot be
 * persisted; an empty change-driven pass that must not read the database clock. Rewritten for
 * TPF-137: the walk is the slice walk over the buckets' pending entries, and "the bucket a pass
 * walks" becomes "the unrecorded bucket a pass catches up".
 */
@ExtendWith(MockitoExtension.class)
class SyntheticFanOutCandidateEdgesTest {

	@Mock private SbomComponentRepository sbomComponentRepository;
	@Mock private SyntheticDtrackBucketRepository bucketRepository;
	@Mock private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Mock private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Mock private SharedArtifactService sharedArtifactService;
	@Mock private DTrackService dTrackService;
	@Mock private RebomService rebomService;
	@Mock private IntegrationService integrationService;
	@Mock private VulnAnalysisService vulnAnalysisService;
	@Mock private SbomComponentService sbomComponentService;
	@Mock private SyntheticOrgLock syntheticOrgLock;
	@Mock private SystemInfoService systemInfoService;
	@Mock private FanOutWalkRepository fanOutWalkRepository;
	@Mock private ReleaseRepository releaseRepository;

	@InjectMocks private SyntheticSbomService service;

	private static final ZonedDateTime P0 = ZonedDateTime.of(2026, 10, 29, 0, 0, 0, 0, ZoneOffset.UTC);
	private static final UUID S0 = endOf(0);

	private final UUID ORG = UUID.randomUUID();
	private final UUID C1 = UUID.randomUUID();
	// Every bucket date lies in the past unless a test says otherwise: the watermark is clamped at now().
	private final ZonedDateTime T1 = minutesAgo(3);
	private final ZonedDateTime T2 = T1.plusMinutes(1);
	private final ZonedDateTime T3 = T2.plusMinutes(1);

	@BeforeEach
	void rig() {
		lenient().when(artifactCanonicalMapRepository.dbClockTimestamp()).thenReturn(P0);
		SbomComponent uncovered = new SbomComponent();
		uncovered.setUuid(UUID.randomUUID());
		uncovered.setOrg(ORG);
		uncovered.setCanonicalPurl("pkg:npm/uncovered@1");
		lenient().when(sbomComponentRepository.findAllById(any())).thenReturn(List.of(uncovered));
		// TPF-136: a warm org whose drain was never recorded: the walk runs from a null watermark as in TPF-114.
		lenient().when(artifactCanonicalMapRepository.countFanOutStamped(ORG)).thenReturn(1L);
		lenient().when(artifactCanonicalMapRepository.findCanonicalsStampedBefore(eq(ORG), any(), any()))
				.thenAnswer(everyCanonicalNeedsFanOut());
	}

	private SyntheticDtrackBucket bucket(int index, ZonedDateTime updated, UUID... changed) {
		return recordedBucket(ORG, index, updated, changed);
	}

	private void verifyNoSliceStatement() {
		verify(fanOutWalkRepository, never()).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
		verify(fanOutWalkRepository, never()).findCanonicalsInSlice(any(), any(), any(), anyInt());
	}

	private static List<UUID> uuids(int n) {
		List<UUID> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(UUID.randomUUID());
		return out;
	}

	@SuppressWarnings("unchecked")
	private Map<UUID, ZonedDateTime> cache() {
		return (Map<UUID, ZonedDateTime>) ReflectionTestUtils.getField(service, "fanOutDrainedAt");
	}

	// ---------------- nothing to apply ----------------

	@Test
	void bucketsThatCoverNothingRunNoPassAndRecordNothing() {
		SyntheticDtrackBucket empty = bucket(0, T1, C1);
		empty.getRefMap().clear();
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(empty));
		lenient().when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(uuids(3));

		service.fanOutOrg(ORG);

		verify(artifactCanonicalMapRepository, never()).findNeverFannedOutCanonicals(any(), anyInt());
		verify(artifactCanonicalMapRepository, never()).stampFanOutPass(any(), any(), any(), any());
		verifyNoSliceStatement();
		verify(systemInfoService, never()).getFanOutDrainedAt(any());
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());
	}

	@Test
	void anOrgWithNoBucketsRunsNoPass() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of());

		service.fanOutOrg(ORG);

		verify(artifactCanonicalMapRepository, never()).findNeverFannedOutCanonicals(any(), anyInt());
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());
	}

	@Test
	void aBucketWithNoDateIsLeftOutOfTheCatchUpAndTheCutoff() {
		SyntheticDtrackBucket undated = bucket(3, T1);
		undated.setLastUpdatedDate(null);
		undated.setFlowControl(null);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1, C1), undated));

		assertDoesNotThrow(() -> service.fanOutOrg(ORG));

		ArgumentCaptor<UUID[]> changed = ArgumentCaptor.forClass(UUID[].class);
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), changed.capture(), eq(500));
		assertEquals(List.of(C1), List.of(changed.getValue()));
		verify(bucketRepository, never()).stampFlowControl(any(), any());
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	// ---------------- an empty change-driven pass ----------------

	@Test
	void anEmptyChangeDrivenPassRecordsItsDrainWithoutReadingTheDatabaseClock() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1, C1)));

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(256)).findChangedCanonicalsInSlice(eq(ORG), any(), any(), any(), eq(500));
		verify(artifactCanonicalMapRepository, never()).dbClockTimestamp();
		verify(artifactCanonicalMapRepository, never()).stampFanOutPass(any(), any(), any(), any());
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	// ---------------- the stamp of an applied canonical ----------------

	@Test
	void aStampThatFailsOnACanonicalThatWasAppliedLeavesTheWatermark() {
		SyntheticDtrackBucket b0 = bucket(0, T1, C1);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(b0));
		SbomComponent covered = new SbomComponent();
		covered.setUuid(UUID.randomUUID());
		covered.setOrg(ORG);
		covered.setCanonicalPurl(COVERED);
		when(sbomComponentRepository.findAllById(any())).thenReturn(List.of(covered));
		UUID c = sortedInSlice(0, 1).get(0);
		lenient().when(fanOutWalkRepository.findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500)))
				.thenReturn(List.of(c));
		when(artifactCanonicalMapRepository.stampFanOutPass(eq(ORG), eq(c), any(), eq(P0)))
				.thenThrow(new RuntimeException("stamp failed")).thenReturn(1);

		service.fanOutOrg(ORG);

		verify(artifactCanonicalMapRepository, times(1)).findByOrgAndCanonicalArtifactUuid(ORG, c);
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(2)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	// ---------------- where the watermark comes from ----------------

	@Test
	void aReplicaWithNoCacheStartsFromThePersistedWatermark() {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T2);
		// Bucket 0 is dated before the watermark, unrecorded: never caught up.
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(unrecordedBucket(ORG, 0, T1), bucket(1, T3, C1)));

		service.fanOutOrg(ORG);

		verify(bucketRepository, never()).stampFlowControl(any(), any());
		ArgumentCaptor<UUID[]> changed = ArgumentCaptor.forClass(UUID[].class);
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), changed.capture(), eq(500));
		assertEquals(List.of(C1), List.of(changed.getValue()));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T3);
	}

	@Test
	void aCachedWatermarkAheadOfThePersistedOneIsKept() {
		cache().put(ORG, T2);
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T1);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(unrecordedBucket(ORG, 0, T2), bucket(1, T3, C1)));

		service.fanOutOrg(ORG);

		// Under the persisted T1 bucket 0 (T2) would be caught up; under the cached T2 it is not.
		verify(bucketRepository, never()).stampFlowControl(any(), any());
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
	}

	@Test
	void aPersistedWatermarkAheadOfTheCachedOneReplacesIt() {
		cache().put(ORG, T1);
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T2);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(unrecordedBucket(ORG, 0, T2), bucket(1, T3, C1)));

		service.fanOutOrg(ORG);

		verify(bucketRepository, never()).stampFlowControl(any(), any());
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		assertEquals(T3, cache().get(ORG));
	}

	@Test
	void thePersistedWatermarkIsReadOnceAndThenCached() {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T1);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1, C1)));

		service.fanOutOrg(ORG);
		service.fanOutOrg(ORG);

		verify(systemInfoService, times(1)).getFanOutDrainedAt(ORG);
		verifyNoSliceStatement();
		verify(systemInfoService, never()).getFanOutWalk(any());
		assertEquals(T1, cache().get(ORG));
	}

	@Test
	void aDrainAdvancesTheCacheSoTheNextPassReadsNothingAndWalksNothing() {
		SyntheticDtrackBucket b0 = bucket(0, T1);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(b0));

		service.fanOutOrg(ORG);
		record(b0, T2, Map.of(C1, T2));
		service.fanOutOrg(ORG);
		service.fanOutOrg(ORG);

		verify(systemInfoService, times(2)).getFanOutDrainedAt(ORG);
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T2);
		assertEquals(T2, cache().get(ORG));
	}

	// ---------------- the clamp and the guard ----------------

	@Test
	void aBucketDatedAheadOfTheClockNeverParksTheWatermarkThere() {
		ZonedDateTime future = minutesAgo(-60);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, future)));
		ZonedDateTime before = ZonedDateTime.now(ZoneOffset.UTC);

		service.fanOutOrg(ORG);

		ArgumentCaptor<ZonedDateTime> stamped = ArgumentCaptor.forClass(ZonedDateTime.class);
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(eq(ORG), stamped.capture());
		ZonedDateTime after = ZonedDateTime.now(ZoneOffset.UTC);
		assertFalse(stamped.getValue().isBefore(before), "the clamp is now, taken during the pass");
		assertFalse(stamped.getValue().isAfter(after), "and never the future date");
		assertFalse(cache().get(ORG).isAfter(after));
	}

	@Test
	void aWatermarkThatIsAheadOfThisReplicasClockIsNotMovedBack() {
		ZonedDateTime ahead = minutesAgo(-60);
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(ahead);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, ahead.plusHours(1), C1)));

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());
		assertEquals(ahead, cache().get(ORG));
	}

	@Test
	void aWatermarkThatCannotBePersistedDoesNotFailThePassAndTheCacheKeepsIt() {
		doThrow(new RuntimeException("system_info down")).when(systemInfoService).stampFanOutDrainedAt(ORG, T1);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1, C1)));

		assertDoesNotThrow(() -> service.fanOutOrg(ORG));
		assertEquals(T1, cache().get(ORG));

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
	}

	// ---------------- the selection budget, to the nanosecond ----------------

	@Test
	void theWalkGoesOnAtTheBudgetAndStopsOneNanosecondPastIt() {
		long budget = Duration.ofSeconds(20).toNanos();
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1, C1), bucket(1, T2, C1)));
		// read 1 sets the deadline at the budget; the check before each slice statement then reads the budget itself
		AtomicInteger reads = new AtomicInteger();
		LongSupplier atTheBudget = () -> reads.incrementAndGet() == 1 ? 0L : budget;
		ReflectionTestUtils.setField(service, "nanoTime", atTheBudget);

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(256)).findChangedCanonicalsInSlice(eq(ORG), any(), any(), any(), eq(500));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T2);
	}

	@Test
	void theWalkStopsWhenTheClockIsOneNanosecondPastTheBudget() {
		long budget = Duration.ofSeconds(20).toNanos();
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1, C1), bucket(1, T2, C1)));
		AtomicInteger reads = new AtomicInteger();
		LongSupplier pastTheBudget = () -> reads.incrementAndGet() == 1 ? 0L : budget + 1;
		ReflectionTestUtils.setField(service, "nanoTime", pastTheBudget);

		service.fanOutOrg(ORG);

		verifyNoSliceStatement();
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());
		assertTrue(reads.get() >= 2);
	}
}
