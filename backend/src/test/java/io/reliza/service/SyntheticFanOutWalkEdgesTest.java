/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.FanOutWalkSlice.START;
import static io.reliza.service.FanOutWalkSlice.endOf;
import static io.reliza.service.FanOutWalkSlice.sliceOf;
import static io.reliza.service.FanOutWalkSlice.startOf;
import static io.reliza.service.FanOutWalkTestSupport.everyCanonicalNeedsFanOut;
import static io.reliza.service.FanOutWalkTestSupport.inSlice;
import static io.reliza.service.FanOutWalkTestSupport.iso;
import static io.reliza.service.FanOutWalkTestSupport.minutesAgo;
import static io.reliza.service.FanOutWalkTestSupport.oneStatementClock;
import static io.reliza.service.FanOutWalkTestSupport.page;
import static io.reliza.service.FanOutWalkTestSupport.parseFlowControl;
import static io.reliza.service.FanOutWalkTestSupport.purlsResolveTo;
import static io.reliza.service.FanOutWalkTestSupport.record;
import static io.reliza.service.FanOutWalkTestSupport.recordedBucket;
import static io.reliza.service.FanOutWalkTestSupport.sortedInSlice;
import static io.reliza.service.FanOutWalkTestSupport.sortedUniverse;
import static io.reliza.service.FanOutWalkTestSupport.unrecordedBucket;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.Artifact;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.BucketFlowControl;
import io.reliza.model.SbomComponent;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.model.SystemInfoData.FanOutWalkState;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.FanOutWalkRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SyntheticDtrackBucketRepository;

/**
 * TPF-137 mutation round: what SyntheticFanOutWalkTest leaves open in the drain of
 * {@link SyntheticSbomService#fanOutOrg}, on the same Mockito rig: the back-pressure room shared by the
 * slices of one pass, a slice that finds nothing asking nothing of the stamp check, a resumed drain
 * keeping its own cutoff when it persists its cursor, a resumed drain with nothing pending clearing its
 * state, the oldest bucket the catch-up recorded deciding the restart, an unrecorded bucket with nothing
 * to record deciding nothing, and the instants the catch-up writes at microsecond precision.
 */
@ExtendWith(MockitoExtension.class)
class SyntheticFanOutWalkEdgesTest {

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
	private final UUID c1 = UUID.randomUUID();
	private final UUID c2 = UUID.randomUUID();
	private final UUID c3 = UUID.randomUUID();
	private final UUID c4 = UUID.randomUUID();
	private final UUID c9 = UUID.randomUUID();
	private final ZonedDateTime T0 = minutesAgo(10);
	private final ZonedDateTime T1 = minutesAgo(5);
	private final ZonedDateTime T2 = T1.plusMinutes(1);
	private final List<UUID> aThree = sortedInSlice(200, 3);

	@BeforeEach
	void rig() {
		lenient().when(artifactCanonicalMapRepository.dbClockTimestamp()).thenReturn(P0);
		// Gate skip for every canonical: its component is matchable and uncovered.
		SbomComponent uncovered = new SbomComponent();
		uncovered.setUuid(UUID.randomUUID());
		uncovered.setOrg(ORG);
		uncovered.setCanonicalPurl("pkg:npm/uncovered@1");
		lenient().when(sbomComponentRepository.findAllById(any())).thenReturn(List.of(uncovered));
		lenient().when(artifactCanonicalMapRepository.countFanOutStamped(ORG)).thenReturn(1L);
		lenient().when(artifactCanonicalMapRepository.findCanonicalsStampedBefore(eq(ORG), any(), any()))
				.thenAnswer(everyCanonicalNeedsFanOut());
		lenient().when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(aThree);
	}

	// ---------------- helpers ----------------

	private SyntheticDtrackBucket bucket(int index, ZonedDateTime date, UUID... changed) {
		return recordedBucket(ORG, index, date, changed);
	}

	private void buckets(SyntheticDtrackBucket... bs) {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bs));
	}

	private void noNeverStamped() {
		when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(List.of());
	}

	private void slice(UUID cursor, UUID end, int room, List<UUID> rows) {
		lenient().when(fanOutWalkRepository.findChangedCanonicalsInSlice(eq(ORG), eq(cursor), eq(end), any(), eq(room)))
				.thenReturn(rows);
	}

	private List<UUID> stampedCanonicals(int expected) {
		ArgumentCaptor<UUID> canonical = ArgumentCaptor.forClass(UUID.class);
		verify(artifactCanonicalMapRepository, times(expected))
				.stampFanOutPass(eq(ORG), canonical.capture(), any(), eq(P0));
		return canonical.getAllValues();
	}

	/** The array of the first statement that started at the given cursor, as a set. */
	private Set<UUID> arrayAt(UUID cursor) {
		ArgumentCaptor<UUID[]> changed = ArgumentCaptor.forClass(UUID[].class);
		verify(fanOutWalkRepository, atLeastOnce()).findChangedCanonicalsInSlice(eq(ORG), eq(cursor), any(),
				changed.capture(), anyInt());
		return Set.of(changed.getAllValues().get(0));
	}

	private void verifyNoSliceStatement() {
		verify(fanOutWalkRepository, never()).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
		verify(fanOutWalkRepository, never()).findCanonicalsInSlice(any(), any(), any(), anyInt());
	}

	private void verifyNoDrain() {
		verify(bucketRepository, never()).clearFanOutChangedThrough(any(), any());
		verify(systemInfoService, never()).clearFanOutWalk(any());
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());
	}

	private void oneStatementPerPass(AtomicInteger reads) {
		ReflectionTestUtils.setField(service, "nanoTime", oneStatementClock(reads));
	}

	@SuppressWarnings("unchecked")
	private Map<UUID, ZonedDateTime> cache() {
		return (Map<UUID, ZonedDateTime>) ReflectionTestUtils.getField(service, "fanOutDrainedAt");
	}

	/** A bucket whose refMap holds exactly the given purls plus the DTrack token. */
	private static SyntheticDtrackBucket withMembers(SyntheticDtrackBucket b, String... purls) {
		b.getRefMap().clear();
		for (String p : purls) b.getRefMap().put(p, p);
		b.getRefMap().put("__token", "t");
		return b;
	}

	/** System info with a walk state that the service's own stamps and clears update. */
	private AtomicReference<FanOutWalkState> statefulWalk(FanOutWalkState initial) {
		AtomicReference<FanOutWalkState> state = new AtomicReference<>(initial);
		lenient().when(systemInfoService.getFanOutWalk(ORG)).thenAnswer(inv -> state.get());
		lenient().doAnswer(inv -> {
			state.set(new FanOutWalkState(iso(inv.getArgument(1)), inv.getArgument(2).toString()));
			return null;
		}).when(systemInfoService).stampFanOutWalk(eq(ORG), any(), any());
		lenient().doAnswer(inv -> {
			state.set(null);
			return null;
		}).when(systemInfoService).clearFanOutWalk(ORG);
		return state;
	}

	private static final String PU1 = "pkg:npm/p1@1";

	private ZonedDateTime withNanos(ZonedDateTime t) {
		return t.truncatedTo(ChronoUnit.MICROS).plusNanos(789);
	}

	// ---------------- the stamp check filters the walked batch ----------------

	@Test
	void onlyTheWalkedCanonicalsTheStampCheckReturnsAreFannedOutAndStamped() {
		noNeverStamped();
		buckets(bucket(0, T1, c1));
		List<UUID> four = sortedInSlice(0, 4);
		slice(START, S0, 500, four);
		doReturn(List.of(four.get(0), four.get(2))).when(artifactCanonicalMapRepository)
				.findCanonicalsStampedBefore(eq(ORG), any(), any());

		service.fanOutOrg(ORG);

		assertEquals(List.of(four.get(0), four.get(2)), stampedCanonicals(2));
	}

	@Test
	void theRoomCountsOnlyTheCanonicalsTheStampCheckReturned() {
		noNeverStamped();
		buckets(bucket(0, T1, c1));
		when(releaseRepository.countReleasesEligibleForMetricsCompute()).thenReturn(990L);
		List<UUID> four = sortedInSlice(0, 4);
		slice(START, S0, 10, four);
		doReturn(List.of(four.get(1))).when(artifactCanonicalMapRepository)
				.findCanonicalsStampedBefore(eq(ORG), any(), any());

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(S1), eq(endOf(1)), any(), eq(9));
		assertEquals(List.of(four.get(1)), stampedCanonicals(1));
	}

	// ---------------- the back-pressure room is the pass's, not each slice's ----------------

	@Test
	void theBacklogRoomIsSharedByTheSlicesOfOnePass() {
		noNeverStamped();
		buckets(bucket(0, T1, c1));
		when(releaseRepository.countReleasesEligibleForMetricsCompute()).thenReturn(990L);
		List<UUID> four = sortedInSlice(0, 4);
		slice(START, S0, 10, four);

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(10));
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(S1), eq(endOf(1)), any(), eq(6));
		assertEquals(four, stampedCanonicals(4));
	}

	@Test
	void theRoomOfASliceIsWhatTheBacklogLeavesAfterTheCanonicalsTheWalkAlreadyTook() {
		noNeverStamped();
		buckets(bucket(0, T1, c1));
		when(releaseRepository.countReleasesEligibleForMetricsCompute()).thenReturn(990L);
		List<UUID> ten = sortedInSlice(1, 10);
		slice(START, S0, 10, List.of());
		slice(S1, endOf(1), 10, ten);

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, never()).findChangedCanonicalsInSlice(eq(ORG), eq(startOf(2)), any(), any(), anyInt());
		assertEquals(ten, stampedCanonicals(10), "the room is spent: the walk stops inside slice 1");
	}

	// ---------------- an empty slice asks the stamp check nothing ----------------

	@Test
	void aWalkThatFindsNothingNeverAsksTheStampCheck() {
		noNeverStamped();
		buckets(bucket(0, T1, c1));

		service.fanOutOrg(ORG);

		verify(artifactCanonicalMapRepository, never()).findCanonicalsStampedBefore(any(), any(), any());
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	// ---------------- a resumed drain ----------------

	@Test
	void aResumedDrainPersistsItsCursorUnderItsOwnCutoffNotThePassCutoff() {
		UUID x = inSlice(0x11);
		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(new FanOutWalkState(iso(T1), x.toString()));
		buckets(recordedBucket(ORG, 0, T2, Map.of(c1, T1, c9, T2)));
		List<UUID> dense = sortedInSlice(0x11, 497);
		slice(x, endOf(17), 497, dense);

		service.fanOutOrg(ORG);

		verify(systemInfoService, times(1)).stampFanOutWalk(ORG, T1, dense.get(dense.size() - 1));
		verify(systemInfoService, never()).stampFanOutWalk(eq(ORG), eq(T2), any());
	}

	@Test
	void aResumedDrainWhoseSetIsNowEmptyClearsItsStateAndRecordsItsOwnCutoffAsDrained() {
		UUID x = inSlice(0x11);
		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(new FanOutWalkState(iso(T1), x.toString()));
		buckets(recordedBucket(ORG, 0, T2));
		noNeverStamped();

		service.fanOutOrg(ORG);

		verifyNoSliceStatement();
		verify(systemInfoService, times(1)).clearFanOutWalk(ORG);
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
		verify(bucketRepository, never()).clearFanOutChangedThrough(any(), any());
	}

	// ---------------- the restart is decided by the OLDEST bucket the catch-up recorded ----------------

	@Test
	void theOldestOfTwoCaughtUpBucketsDecidesTheRestart() {
		UUID x = inSlice(0x11);
		ZonedDateTime older = T0;
		ZonedDateTime newer = T2;
		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(new FanOutWalkState(iso(T1), x.toString()));
		lenient().when(sbomComponentRepository.findUuidsByOrgAndCanonicalPurlIn(eq(ORG), any()))
				.thenAnswer(purlsResolveTo(Map.of(PU1, c4)));
		SyntheticDtrackBucket b1 = withMembers(unrecordedBucket(ORG, 1, older), PU1);
		SyntheticDtrackBucket b2 = withMembers(unrecordedBucket(ORG, 2, newer), PU1);
		buckets(recordedBucket(ORG, 0, T2, Map.of(c1, T1)), b1, b2);
		AtomicInteger reads = new AtomicInteger();
		oneStatementPerPass(reads);

		service.fanOutOrg(ORG);

		verify(systemInfoService, times(1)).clearFanOutWalk(ORG);
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), anyInt());
	}

	@Test
	void anUnrecordedBucketWithNothingToRecordDoesNotRestartARunningDrain() {
		UUID x = inSlice(0x11);
		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(new FanOutWalkState(iso(T1), x.toString()));
		SyntheticDtrackBucket empty = unrecordedBucket(ORG, 1, T0);
		empty.getRefMap().clear();
		buckets(recordedBucket(ORG, 0, T2, Map.of(c1, T1)), empty);
		AtomicInteger reads = new AtomicInteger();
		oneStatementPerPass(reads);

		service.fanOutOrg(ORG);

		verify(systemInfoService, never()).clearFanOutWalk(any());
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(x), eq(endOf(17)), any(), anyInt());
	}

	// ---------------- the catch-up writes microseconds ----------------

	@Test
	void theCatchUpRecordsAtMicrosecondPrecision() {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T0);
		noNeverStamped();
		lenient().when(sbomComponentRepository.findUuidsByOrgAndCanonicalPurlIn(eq(ORG), any()))
				.thenAnswer(purlsResolveTo(Map.of(PU1, c1)));
		ZonedDateTime precise = withNanos(T1);
		SyntheticDtrackBucket b = withMembers(unrecordedBucket(ORG, 0, T1), PU1);
		b.setLastUpdatedDate(precise);
		buckets(b);

		service.fanOutOrg(ORG);

		ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
		verify(bucketRepository, times(1)).stampFlowControl(eq(b.getUuid()), json.capture());
		BucketFlowControl fc = parseFlowControl(json.getValue());
		assertEquals(iso(T1), fc.recordedAt(), "recordedAt keeps the microseconds the column keeps");
		assertEquals(Map.of(c1.toString(), iso(T1)), fc.fanOutChanged());
	}
}
