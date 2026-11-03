/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.FanOutWalkSlice.START;
import static io.reliza.service.FanOutWalkSlice.endOf;
import static io.reliza.service.FanOutWalkTestSupport.everyCanonicalNeedsFanOut;
import static io.reliza.service.FanOutWalkTestSupport.minutesAgo;
import static io.reliza.service.FanOutWalkTestSupport.record;
import static io.reliza.service.FanOutWalkTestSupport.recordedBucket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
 * TPF-136 (architecture 6.3): the cold start of {@link SyntheticSbomService#fanOutOrg}. With no
 * watermark and no stamped map row (countFanOutStamped unstubbed, so 0) the bucket walk is skipped,
 * (A) serves the org and the pass cutoff is recorded as drained whatever the stamps did; with
 * stamped rows, or once a watermark exists, the walk runs as in TPF-114. Same Mockito rig as
 * SyntheticFanOutCandidateTest.
 */
@ExtendWith(MockitoExtension.class)
class SyntheticFanOutColdStartTest {

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
	private final UUID C2 = UUID.randomUUID();
	// Every bucket date lies in the past: the watermark is clamped at now().
	private final ZonedDateTime T1 = minutesAgo(5);
	private final ZonedDateTime T0 = T1.minusMinutes(1);
	private final ZonedDateTime T2 = T1.plusMinutes(1);
	private final ZonedDateTime T3 = T2.plusMinutes(1);
	private final ZonedDateTime T4 = T3.plusMinutes(1);

	@BeforeEach
	void rig() {
		lenient().when(artifactCanonicalMapRepository.dbClockTimestamp()).thenReturn(P0);
		// Gate skip for every canonical: its component is matchable and uncovered.
		SbomComponent uncovered = new SbomComponent();
		uncovered.setUuid(UUID.randomUUID());
		uncovered.setOrg(ORG);
		uncovered.setCanonicalPurl("pkg:npm/uncovered@1");
		lenient().when(sbomComponentRepository.findAllById(any())).thenReturn(List.of(uncovered));
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

	private static String iso(ZonedDateTime t) {
		return FanOutWalkTestSupport.iso(t);
	}

	private List<UUID> stampedCanonicals(int expected) {
		ArgumentCaptor<UUID> canonical = ArgumentCaptor.forClass(UUID.class);
		verify(artifactCanonicalMapRepository, times(expected))
				.stampFanOutPass(eq(ORG), canonical.capture(), any(), eq(P0));
		return canonical.getAllValues();
	}

	@SuppressWarnings("unchecked")
	private ZonedDateTime cachedWatermark() {
		return ((Map<UUID, ZonedDateTime>) ReflectionTestUtils.getField(service, "fanOutDrainedAt")).get(ORG);
	}

	@Test
	void aColdStartWithNoStampedRowSkipsTheWalkAndRecordsTheCutoff() {
		SyntheticDtrackBucket b2 = bucket(2, T2);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(5, T1), b2, bucket(9, T3)));
		List<UUID> pending = uuids(2000);
		when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(pending);

		// Pass 1: the cold start skips the walk, clears the pending entries up to the cutoff (TPF-137 D17)
		// and records the cutoff T3.
		service.fanOutOrg(ORG);

		verifyNoSliceStatement();
		verify(bucketRepository, times(1)).clearFanOutChangedThrough(ORG, T3);
		verify(artifactCanonicalMapRepository, times(1)).countFanOutStamped(ORG);
		verify(systemInfoService, times(1)).getFanOutDrainedAt(ORG);
		assertEquals(pending.subList(0, 500), stampedCanonicals(500));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T3);
		assertEquals(T3, cachedWatermark());

		// Pass 2: no bucket moved: no watermark read, no count, no walk, no drain.
		service.fanOutOrg(ORG);

		verify(systemInfoService, times(1)).getFanOutDrainedAt(ORG);
		verify(artifactCanonicalMapRepository, times(1)).countFanOutStamped(ORG);
		verifyNoSliceStatement();
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(any(), any());

		// Pass 3: bucket 2 moves to T4 with a pending entry; (B) walks only its component, with room 100
		// ((A) took 400).
		record(b2, T4, Map.of(C2, T4));
		service.fanOutOrg(ORG);

		ArgumentCaptor<UUID[]> changed = ArgumentCaptor.forClass(UUID[].class);
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), changed.capture(), eq(100));
		assertEquals(List.of(C2), List.of(changed.getValue()));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T4);
		// A watermark exists: the stamped-row count is not read again (D8).
		verify(artifactCanonicalMapRepository, times(1)).countFanOutStamped(ORG);
	}

	@Test
	void aColdStartWithNothingPendingRecordsTheCutoffWithoutReadingTheDatabaseClock() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1)));

		service.fanOutOrg(ORG);

		verifyNoSliceStatement();
		verify(artifactCanonicalMapRepository, never()).dbClockTimestamp();
		verify(artifactCanonicalMapRepository, never()).stampFanOutPass(any(), any(), any(), any());
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	@Test
	void aColdStartWhoseStampFailsStillRecordsTheCutoffAndTheRowIsServedAgainByA() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1)));
		UUID c1 = UUID.randomUUID();
		UUID c2 = UUID.randomUUID();
		// On the database c2 is stamped and leaves the index; c1's stamp threw, so it stays pending.
		when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000))
				.thenReturn(List.of(c1, c2))
				.thenReturn(List.of(c1));
		when(artifactCanonicalMapRepository.stampFanOutPass(ORG, c1, iso(T1), P0))
				.thenThrow(new RuntimeException("stamp failed"))
				.thenReturn(1);
		when(artifactCanonicalMapRepository.stampFanOutPass(ORG, c2, iso(T1), P0)).thenReturn(1);

		// Pass 1: the drain is recorded despite the failed stamp.
		service.fanOutOrg(ORG);

		verifyNoSliceStatement();
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
		assertEquals(T1, cachedWatermark());

		// Pass 2: warm (the cache holds T1, the cutoff T1 is not after it): (A) serves c1 again.
		service.fanOutOrg(ORG);

		verify(systemInfoService, times(1)).getFanOutDrainedAt(ORG);
		verify(artifactCanonicalMapRepository, times(1)).countFanOutStamped(ORG);
		verifyNoSliceStatement();
		verify(artifactCanonicalMapRepository, times(2)).stampFanOutPass(ORG, c1, iso(T1), P0);
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(any(), any());
	}

	@Test
	void aColdStartWithStampedRowsWalksTheBuckets() {
		when(artifactCanonicalMapRepository.countFanOutStamped(ORG)).thenReturn(7L);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(5, T1, C2), bucket(2, T2)));

		service.fanOutOrg(ORG);

		InOrder walk = inOrder(fanOutWalkRepository);
		walk.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		walk.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(FanOutWalkSlice.startOf(255)), eq(endOf(255)), any(), eq(500));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T2);
		verify(artifactCanonicalMapRepository, times(1)).countFanOutStamped(ORG);

		// Pass 2: the drain is recorded, so neither the count nor the walk runs again.
		service.fanOutOrg(ORG);

		verify(artifactCanonicalMapRepository, times(1)).countFanOutStamped(ORG);
		verify(fanOutWalkRepository, times(256)).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
	}

	@Test
	void theStampedRowCountIsNotReadWhenAWatermarkExists() {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T0);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1, C2)));

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		verify(artifactCanonicalMapRepository, never()).countFanOutStamped(any());
	}
}
