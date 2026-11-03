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
import static io.reliza.service.FanOutWalkTestSupport.recordedBucket;
import static io.reliza.service.FanOutWalkTestSupport.sortedInSlice;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
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
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.SbomComponent;
import io.reliza.model.SystemInfoData.FanOutWalkState;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.FanOutWalkRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SyntheticDtrackBucketRepository;

/**
 * TPF-136 (architecture 6.2), carried over by TPF-137: a slice statement of the change-driven walk
 * of {@link SyntheticSbomService#fanOutOrg} that throws is caught; the cursor stays before the
 * failed slice and the walk ends there (TPF-137 D13), (A) and its surplus are stamped and the drain
 * rules hold. Same Mockito rig as SyntheticFanOutCandidateTest, with a warm org (countFanOutStamped
 * 1) so the walk runs from a null watermark. The slice statements a case stubs are lenient: an
 * unmatched call of a strictly stubbed method raises PotentialStubbingProblem, which the catch
 * would take as a failed slice.
 */
@ExtendWith(MockitoExtension.class)
class SyntheticFanOutWalkFailureTest {

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

	@BeforeEach
	void rig() {
		lenient().when(artifactCanonicalMapRepository.dbClockTimestamp()).thenReturn(P0);
		// Gate skip for every canonical: its component is matchable and uncovered.
		SbomComponent uncovered = new SbomComponent();
		uncovered.setUuid(UUID.randomUUID());
		uncovered.setOrg(ORG);
		uncovered.setCanonicalPurl("pkg:npm/uncovered@1");
		lenient().when(sbomComponentRepository.findAllById(any())).thenReturn(List.of(uncovered));
		// A warm org whose drain was never recorded: the walk runs from a null watermark.
		lenient().when(artifactCanonicalMapRepository.countFanOutStamped(ORG)).thenReturn(1L);
		lenient().when(artifactCanonicalMapRepository.findCanonicalsStampedBefore(eq(ORG), any(), any()))
				.thenAnswer(everyCanonicalNeedsFanOut());
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(recordedBucket(ORG, 0, T1, C1)));
	}

	private List<UUID> stampedCanonicals(int expected) {
		ArgumentCaptor<UUID> canonical = ArgumentCaptor.forClass(UUID.class);
		verify(artifactCanonicalMapRepository, times(expected))
				.stampFanOutPass(eq(ORG), canonical.capture(), any(), eq(P0));
		return canonical.getAllValues();
	}

	/** TPF-137 6.1.3 (TPF-136 case 1 carried over, one copy): D13 and "retried next tick without losing the cursor". */
	@Test
	void aFailedSliceStatementIsRetriedNextTickWithoutLosingTheCursor() {
		lenient().when(fanOutWalkRepository.findChangedCanonicalsInSlice(eq(ORG), eq(S1), eq(endOf(1)), any(), anyInt()))
				.thenThrow(new QueryTimeoutException("canceling statement due to user request"))
				.thenReturn(List.of());

		assertDoesNotThrow(() -> service.fanOutOrg(ORG));

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(S1), eq(endOf(1)), any(), eq(500));
		verify(fanOutWalkRepository, never()).findChangedCanonicalsInSlice(eq(ORG), eq(startOf(2)), any(), any(), anyInt());
		verify(systemInfoService, times(1)).stampFanOutWalk(ORG, T1, S1);
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());
		verify(bucketRepository, never()).clearFanOutChangedThrough(any(), any());

		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(new FanOutWalkState(iso(T1), S1.toString()));
		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), any(), any(), anyInt());
		verify(fanOutWalkRepository, times(2)).findChangedCanonicalsInSlice(eq(ORG), eq(S1), eq(endOf(1)), any(), eq(500));
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(startOf(255)), eq(endOf(255)), any(), eq(500));
		verify(bucketRepository, times(1)).clearFanOutChangedThrough(ORG, T1);
		verify(systemInfoService, times(1)).clearFanOutWalk(ORG);
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	@Test
	void aFailedFirstSliceRecordsNoDrainAndTheNeverStampedCandidatesAreStillStamped() {
		List<UUID> pending = sortedInSlice(200, 2000);
		when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(pending);
		when(fanOutWalkRepository.findChangedCanonicalsInSlice(eq(ORG), any(), any(), any(), anyInt()))
				.thenThrow(new RuntimeException("slice 0 failed"));

		service.fanOutOrg(ORG);

		// (A)'s 400 plus the surplus, because (B) left the room.
		assertEquals(pending.subList(0, 500), stampedCanonicals(500));
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(2)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(100));
		verify(fanOutWalkRepository, times(2)).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());
		stampedCanonicals(1000);
	}

	@Test
	void aTimedOutStatementSpendsTheBudgetAndTheWalkEndsThere() {
		when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(sortedInSlice(200, 3));
		List<UUID> found = sortedInSlice(0, 1);
		lenient().when(fanOutWalkRepository.findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(497)))
				.thenThrow(new RuntimeException("slice 0 timed out"))
				.thenReturn(found);
		// Read 1 the deadline, read 2 the check before slice 0; every later read is far past the budget.
		AtomicInteger reads = new AtomicInteger();
		LongSupplier jumping = () -> reads.incrementAndGet() <= 2 ? 0L : Long.MAX_VALUE / 2;
		ReflectionTestUtils.setField(service, "nanoTime", jumping);

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());
		stampedCanonicals(3);
		// Read 3 is the elapsed read in the catch; the walk then ends (D13: break, no further check).
		assertEquals(3, reads.get());

		ReflectionTestUtils.setField(service, "nanoTime", (LongSupplier) System::nanoTime);
		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(2)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(497));
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(S1), eq(endOf(1)), any(), eq(496));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
		stampedCanonicals(7);
	}
}
