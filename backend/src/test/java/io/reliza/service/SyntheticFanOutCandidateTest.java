/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.FanOutWalkSlice.START;
import static io.reliza.service.FanOutWalkSlice.endOf;
import static io.reliza.service.FanOutWalkSlice.startOf;
import static io.reliza.service.FanOutWalkTestSupport.everyCanonicalNeedsFanOut;
import static io.reliza.service.FanOutWalkTestSupport.iso;
import static io.reliza.service.FanOutWalkTestSupport.minutesAgo;
import static io.reliza.service.FanOutWalkTestSupport.record;
import static io.reliza.service.FanOutWalkTestSupport.recordedBucket;
import static io.reliza.service.FanOutWalkTestSupport.sortedInSlice;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

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
import io.reliza.model.SystemInfoData.FanOutWalkState;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.FanOutWalkRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SyntheticDtrackBucketRepository;

/**
 * TPF-114, rewritten for TPF-137: the candidate selection of {@link SyntheticSbomService#fanOutOrg}:
 * the never-stamped query (A), the change-driven walk (B) with its watermark, budget and reserve
 * (TPF-114 architecture 4.4, 4.14, 4.15, 4.17), now a keyset walk over fixed uuid-space slices
 * driven by the buckets' pending change maps. Same Mockito rig as SyntheticSbomServiceTest; every
 * canonical is a coverage-gate skip (its one component is uncovered), so each processed canonical
 * costs exactly one stamp. Unstubbed slice statements return no row (a drained slice).
 */
@ExtendWith(MockitoExtension.class)
class SyntheticFanOutCandidateTest {

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
	private static final UUID S1 = startOf(1);

	private final UUID ORG = UUID.randomUUID();
	private final UUID C1 = UUID.randomUUID();
	// Every bucket date lies in the past: the watermark is clamped at now().
	private final ZonedDateTime T1 = minutesAgo(3);
	private final ZonedDateTime T2 = T1.plusMinutes(1);
	private final ZonedDateTime T3 = T2.plusMinutes(1);

	@BeforeEach
	void rig() {
		lenient().when(artifactCanonicalMapRepository.dbClockTimestamp()).thenReturn(P0);
		// Gate skip for every canonical: its component is matchable and uncovered.
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

	private List<UUID> stampedCanonicals(int expected) {
		ArgumentCaptor<UUID> canonical = ArgumentCaptor.forClass(UUID.class);
		verify(artifactCanonicalMapRepository, times(expected))
				.stampFanOutPass(eq(ORG), canonical.capture(), any(), eq(P0));
		return canonical.getAllValues();
	}

	private void verifyNoSliceStatement() {
		verify(fanOutWalkRepository, never()).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
		verify(fanOutWalkRepository, never()).findCanonicalsInSlice(any(), any(), any(), anyInt());
	}

	// ---------------- 4.4 ----------------

	@Test
	void noBucketChangeRunsNoBucketQueryAndADrainedPassRecordsItOnce() {
		// A bucket that moved with nothing pending (TPF-137 4.5 step 2): no slice statement, the drain recorded.
		SyntheticDtrackBucket b0 = bucket(0, T1);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(b0));

		service.fanOutOrg(ORG);

		verifyNoSliceStatement();
		verify(systemInfoService, times(1)).getFanOutDrainedAt(ORG);
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);

		service.fanOutOrg(ORG);

		verify(systemInfoService, times(1)).getFanOutDrainedAt(ORG);
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
		verify(artifactCanonicalMapRepository, times(2)).findNeverFannedOutCanonicals(ORG, 2000);

		record(b0, T2, Map.of());
		service.fanOutOrg(ORG);

		verifyNoSliceStatement();
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T2);
		verify(artifactCanonicalMapRepository, never()).stampFanOutPass(any(), any(), any(), any());
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());
	}

	@Test
	void aBucketWithAPendingEntryStartsADrain() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1, C1)));

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		verify(fanOutWalkRepository, times(256)).findChangedCanonicalsInSlice(eq(ORG), any(), any(), any(), eq(500));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	@Test
	void aSliceThatFillsTheRoomIsNotDrainedAndIsResumedFromItsCursor() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1, C1)));
		List<UUID> dense = sortedInSlice(0, 500);
		UUID last = dense.get(499);
		lenient().when(fanOutWalkRepository.findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500)))
				.thenReturn(dense);

		service.fanOutOrg(ORG);

		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());
		verify(systemInfoService, times(1)).stampFanOutWalk(ORG, T1, last);

		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(new FanOutWalkState(iso(T1), last.toString()));
		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(last), eq(S0), any(), eq(500));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	@Test
	void aFailedStampLeavesTheWatermarkAndTheSliceIsWalkedAgain() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1, C1)));
		UUID c = sortedInSlice(0, 1).get(0);
		lenient().when(fanOutWalkRepository.findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500)))
				.thenReturn(List.of(c));
		when(artifactCanonicalMapRepository.stampFanOutPass(ORG, c, iso(T1), P0))
				.thenThrow(new RuntimeException("stamp failed")).thenReturn(1);

		service.fanOutOrg(ORG);

		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(2)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	// ---------------- 4.14 ----------------

	@Test
	void anEmptyChangeDrivenPassRecordsTheDrain() {
		SyntheticDtrackBucket b0 = bucket(0, T1);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(b0));
		UUID c = sortedInSlice(0, 1).get(0);

		service.fanOutOrg(ORG);

		verify(artifactCanonicalMapRepository, never()).stampFanOutPass(any(), any(), any(), any());
		verify(sharedArtifactService, never()).updateArtifactDti(any(), any(), any());
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);

		service.fanOutOrg(ORG);

		verifyNoSliceStatement();
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);

		record(b0, T2, Map.of(C1, T2));
		lenient().when(fanOutWalkRepository.findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500)))
				.thenReturn(List.of(c));

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		verify(artifactCanonicalMapRepository, times(1)).stampFanOutPass(ORG, c, iso(T2), P0);
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T2);
	}

	// ---------------- 4.15 ----------------

	@Test
	void theWalkIsSliceBySliceAscendingAndResumesFromThePersistedCursor() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(7, T3, C1), bucket(5, T1, C1)));
		lenient().when(fanOutWalkRepository.findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500)))
				.thenReturn(sortedInSlice(0, 2));
		List<UUID> slice1 = sortedInSlice(1, 498);
		UUID last = slice1.get(497);
		lenient().when(fanOutWalkRepository.findChangedCanonicalsInSlice(eq(ORG), eq(S1), eq(endOf(1)), any(), eq(498)))
				.thenReturn(slice1);

		service.fanOutOrg(ORG);

		InOrder first = inOrder(fanOutWalkRepository);
		first.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		first.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(S1), eq(endOf(1)), any(), eq(498));
		verify(fanOutWalkRepository, never()).findChangedCanonicalsInSlice(eq(ORG), eq(startOf(2)), any(), any(), anyInt());
		verify(systemInfoService, times(1)).stampFanOutWalk(ORG, T3, last);
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());

		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(new FanOutWalkState(iso(T3), last.toString()));
		service.fanOutOrg(ORG);

		InOrder second = inOrder(fanOutWalkRepository);
		second.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(last), eq(endOf(1)), any(), eq(500));
		second.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(startOf(2)), eq(endOf(2)), any(), eq(500));
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), any(), any(), anyInt());
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T3);
	}

	@Test
	void theWalkStopsAtTheBudgetAndTheNextTickResumes() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(5, T1, C1), bucket(2, T2, C1)));
		// Calls 1 and 2 (the deadline and the check before slice 0) read 0; every later
		// read is past FANOUT_SELECT_BUDGET.
		AtomicInteger reads = new AtomicInteger();
		LongSupplier jumping = () -> reads.incrementAndGet() <= 2 ? 0L : Long.MAX_VALUE / 2;
		ReflectionTestUtils.setField(service, "nanoTime", jumping);

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
		verify(systemInfoService, times(1)).stampFanOutWalk(ORG, T2, S1);
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());

		ReflectionTestUtils.setField(service, "nanoTime", (LongSupplier) System::nanoTime);
		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(new FanOutWalkState(iso(T2), S1.toString()));
		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(S1), eq(endOf(1)), any(), eq(500));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T2);
	}

	// ---------------- 4.17 ----------------

	@Test
	void neverStampedKeepsTheFirstCallAndTheChangeDrivenQueryKeepsAReserve() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1, C1)));
		List<UUID> pending = new ArrayList<>(sortedInSlice(200, 2000));
		when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(pending);
		List<UUID> changed = sortedInSlice(0, 100);
		lenient().when(fanOutWalkRepository.findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(100)))
				.thenReturn(changed);

		service.fanOutOrg(ORG);

		List<UUID> stamped = stampedCanonicals(500);
		assertEquals(pending.subList(0, 400), stamped.subList(0, 400), "(A) first, in (A) order");
		assertEquals(changed, stamped.subList(400, 500), "(B) takes the reserve");
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
	}

	@Test
	void theSurplusOfNeverStampedFillsAnUnusedReserve() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1, C1)));
		List<UUID> pending = new ArrayList<>(sortedInSlice(200, 2000));
		when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(pending);

		service.fanOutOrg(ORG);

		assertEquals(pending.subList(0, 500), stampedCanonicals(500));
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(100));
	}

	@Test
	void aShortNeverStampedSetLeavesTheRestOfTheBatchToTheChangeDrivenQuery() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bucket(0, T1, C1)));
		List<UUID> pending = new ArrayList<>(sortedInSlice(200, 50));
		when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(pending);
		List<UUID> changed = sortedInSlice(0, 450);
		lenient().when(fanOutWalkRepository.findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(450)))
				.thenReturn(changed);

		service.fanOutOrg(ORG);

		List<UUID> stamped = stampedCanonicals(500);
		assertEquals(pending, stamped.subList(0, 50), "(A)'s 50 first");
		assertEquals(changed, stamped.subList(50, 500));
	}
}
