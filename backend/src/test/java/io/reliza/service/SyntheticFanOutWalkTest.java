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
 * TPF-137 architecture 6.1 case 3: the change-driven drain of {@link SyntheticSbomService#fanOutOrg}
 * on the Mockito rig of SyntheticFanOutCandidateTest: the keyset walk over fixed uuid-space slices,
 * the persisted cursor and its restore, completion and the clear, the cap, the budget, the
 * back-pressure against the metrics backlog, the cold-start clear, the catch-up of unrecorded
 * buckets and the restart it can cause, and the requeue of a canonical whose findings could not be
 * applied (D26). (A) returns three canonicals unless a case says otherwise, so the first statement's
 * room is 497; no walk state is persisted unless stated; the stamp check answers its whole batch.
 * Unstubbed slice statements return no row (a drained slice); the slice statements a case stubs are
 * lenient, because a strict stub called with other arguments raises PotentialStubbingProblem, which
 * the per-slice catch would take as a failed slice.
 */
@ExtendWith(MockitoExtension.class)
class SyntheticFanOutWalkTest {

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

	// ---------------- start, resume, room, empty slices, completion ----------------

	@Test
	void aDrainStartsAtThePassCutoffAndWalksTheFirstSliceFromTheStart() {
		buckets(bucket(0, T1, c1, c2));
		List<UUID> five = sortedInSlice(0, 5);
		slice(START, S0, 497, five);
		AtomicInteger reads = new AtomicInteger();
		oneStatementPerPass(reads);

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(497));
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
		assertEquals(Set.of(c1, c2), arrayAt(START), "the array is exactly the pending set");
		verify(artifactCanonicalMapRepository, times(1)).findCanonicalsStampedBefore(ORG, five, T1);
		List<UUID> expected = new ArrayList<>(aThree);
		expected.addAll(five);
		assertEquals(expected, stampedCanonicals(8), "(A)'s three first, then the five in walk order");
		verify(systemInfoService, times(1)).stampFanOutWalk(ORG, T1, S1);
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());
	}

	@Test
	void theWalkResumesFromThePersistedCursor() {
		UUID x = inSlice(0x11);
		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(new FanOutWalkState(iso(T1), x.toString()));
		buckets(recordedBucket(ORG, 0, T2, Map.of(c1, T1, c9, T2)));

		service.fanOutOrg(ORG);

		InOrder first = inOrder(fanOutWalkRepository);
		first.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(x), eq(endOf(17)), any(), eq(497));
		assertEquals(Set.of(c1), arrayAt(x), "the drain keeps its cutoff T1 and its set: c9 is after it");
		verify(fanOutWalkRepository, never()).findChangedCanonicalsInSlice(eq(ORG), eq(START), any(), any(), anyInt());
		verify(systemInfoService, times(1)).getFanOutWalk(ORG);
		verify(bucketRepository, times(1)).clearFanOutChangedThrough(ORG, T1);
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	@Test
	void theRoomStopsADenseSliceAndTheCursorStaysInsideIt() {
		buckets(bucket(0, T1, c1));
		List<UUID> dense = sortedInSlice(0, 497);
		UUID last = dense.get(496);
		slice(START, S0, 497, dense);

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
		verify(systemInfoService, times(1)).stampFanOutWalk(ORG, T1, last);
		assertEquals(0, sliceOf(last), "the cursor stays inside the slice");
		verifyNoDrain();

		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(new FanOutWalkState(iso(T1), last.toString()));
		service.fanOutOrg(ORG);

		InOrder second = inOrder(fanOutWalkRepository);
		second.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(497));
		second.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(last), eq(S0), any(), eq(497));
	}

	@Test
	void anEmptySliceAdvancesTheCursorAndTheWalkGoesOn() {
		noNeverStamped();
		buckets(bucket(0, T1, c1));
		List<UUID> one = sortedInSlice(2, 1);
		slice(startOf(2), endOf(2), 500, one);

		service.fanOutOrg(ORG);

		InOrder walk = inOrder(fanOutWalkRepository);
		walk.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		walk.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(S1), eq(endOf(1)), any(), eq(500));
		walk.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(startOf(2)), eq(endOf(2)), any(), eq(500));
		walk.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(startOf(3)), eq(endOf(3)), any(), eq(499));
		walk.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(startOf(255)), eq(endOf(255)), any(), eq(499));
		assertEquals(one, stampedCanonicals(1));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	@Test
	void aDrainCompletesAndClearsTheChangedSetsTheStateAndMovesTheWatermark() {
		noNeverStamped();
		buckets(bucket(0, T1, c1));
		slice(startOf(255), endOf(255), 500, sortedInSlice(255, 2));

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(256)).findChangedCanonicalsInSlice(eq(ORG), any(), any(), any(), anyInt());
		stampedCanonicals(2);
		InOrder completion = inOrder(bucketRepository, systemInfoService);
		completion.verify(bucketRepository).clearFanOutChangedThrough(ORG, T1);
		completion.verify(systemInfoService).clearFanOutWalk(ORG);
		completion.verify(systemInfoService).stampFanOutDrainedAt(ORG, T1);
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());
		assertEquals(T1, cache().get(ORG));

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(256)).findChangedCanonicalsInSlice(eq(ORG), any(), any(), any(), anyInt());
		verify(systemInfoService, times(1)).getFanOutWalk(ORG);
		verify(systemInfoService, times(1)).getFanOutDrainedAt(ORG);
	}

	@Test
	void aCanonicalIsNeverReturnedTwiceInOneDrain() {
		noNeverStamped();
		buckets(bucket(0, T1, c1));
		List<UUID> universe = sortedUniverse(1200);
		AtomicInteger statements = new AtomicInteger();
		AtomicInteger fullPages = new AtomicInteger();
		lenient().when(fanOutWalkRepository.findChangedCanonicalsInSlice(eq(ORG), any(), any(), any(), anyInt()))
				.thenAnswer(inv -> {
					statements.incrementAndGet();
					List<UUID> rows = page(universe, inv.getArgument(1), inv.getArgument(2), inv.getArgument(4));
					if (rows.size() >= (int) inv.getArgument(4)) fullPages.incrementAndGet();
					return rows;
				});
		statefulWalk(null);
		AtomicInteger cleared = new AtomicInteger();
		doAnswer(inv -> {
			cleared.incrementAndGet();
			return 1;
		}).when(bucketRepository).clearFanOutChangedThrough(ORG, T1);

		int passes = 0;
		while (cleared.get() == 0 && passes < 10) {
			service.fanOutOrg(ORG);
			passes++;
		}

		assertEquals(1, cleared.get(), "the drain completed");
		List<UUID> stamped = stampedCanonicals(1200);
		assertEquals(1200, new HashSet<>(stamped).size(), "every canonical once");
		assertEquals(universe, stamped, "in universe order");
		assertEquals(256 + fullPages.get(), statements.get());
		assertTrue(passes >= 3, "a room of 500 spreads 1,200 canonicals over at least three passes");
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	@Test
	void aMovedBucketWithNothingPendingRecordsTheDrainAndWritesNoState() {
		buckets(bucket(0, T1));

		service.fanOutOrg(ORG);

		verifyNoSliceStatement();
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());
		verify(systemInfoService, never()).clearFanOutWalk(any());
		verify(bucketRepository, never()).clearFanOutChangedThrough(any(), any());
	}

	@Test
	void aChangedSetAboveTheCapWalksInFullMode() {
		Map<UUID, ZonedDateTime> many = new LinkedHashMap<>();
		for (int i = 0; i < SyntheticSbomService.FANOUT_CHANGED_SET_CAP + 1; i++) many.put(UUID.randomUUID(), T1);
		buckets(recordedBucket(ORG, 0, T1, many));

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findCanonicalsInSlice(ORG, START, S0, 497);
		verify(fanOutWalkRepository, never()).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	@Test
	void aChangedSetAtTheCapStillBindsTheArray() {
		Map<UUID, ZonedDateTime> many = new LinkedHashMap<>();
		for (int i = 0; i < SyntheticSbomService.FANOUT_CHANGED_SET_CAP; i++) many.put(UUID.randomUUID(), T1);
		buckets(recordedBucket(ORG, 0, T1, many));

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, never()).findCanonicalsInSlice(any(), any(), any(), anyInt());
		assertEquals(SyntheticSbomService.FANOUT_CHANGED_SET_CAP, arrayAt(START).size());
	}

	// ---------------- the cursor on failure ----------------

	@Test
	void aStampFailureRestoresTheCursorToThePassStart() {
		UUID x = inSlice(0x11);
		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(new FanOutWalkState(iso(T1), x.toString()));
		buckets(bucket(0, T1, c1));
		List<UUID> two = sortedInSlice(0x11, 2);
		slice(x, endOf(17), 497, two);
		lenient().when(artifactCanonicalMapRepository.stampFanOutPass(ORG, two.get(0), iso(T1), P0))
				.thenThrow(new RuntimeException("stamp failed"));
		// Reads 1-3: the deadline and the checks before slices 17 and 18; then the budget ends the walk.
		AtomicInteger reads = new AtomicInteger();
		ReflectionTestUtils.setField(service, "nanoTime", (LongSupplier) () -> reads.incrementAndGet() <= 3 ? 0L : Long.MAX_VALUE / 2);

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(startOf(18)), eq(endOf(18)), any(), anyInt());
		verify(fanOutWalkRepository, times(2)).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());
		verifyNoDrain();
	}

	@Test
	void aStampFailureOnAFreshDrainWritesNoState() {
		buckets(bucket(0, T1, c1));
		List<UUID> two = sortedInSlice(0, 2);
		slice(START, S0, 497, two);
		lenient().when(artifactCanonicalMapRepository.stampFanOutPass(ORG, two.get(1), iso(T1), P0))
				.thenThrow(new RuntimeException("stamp failed"));

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(256)).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());
		verifyNoDrain();
	}

	// ---------------- D26: a failed apply is requeued, not stamped ----------------

	private UUID k1;
	private UUID k2;
	private Artifact art1;
	private Artifact art2;

	/** K1 and K2 in slice 0, covered, one artifact each (A1, A2): both reach the apply branch. */
	private void twoAppliedCanonicals() {
		noNeverStamped();
		buckets(bucket(0, T1, c1));
		List<UUID> ks = sortedInSlice(0, 2);
		k1 = ks.get(0);
		k2 = ks.get(1);
		slice(START, S0, 500, ks);
		SbomComponent covered = new SbomComponent();
		covered.setUuid(UUID.randomUUID());
		covered.setOrg(ORG);
		covered.setCanonicalPurl(FanOutWalkTestSupport.COVERED);
		lenient().when(sbomComponentRepository.findAllById(any())).thenReturn(List.of(covered));
		ArtifactSbomComponent asc = mock(ArtifactSbomComponent.class);
		lenient().when(asc.getSbomComponentUuid()).thenReturn(covered.getUuid());
		lenient().when(artifactSbomComponentRepository.findByOrgAndCanonicalArtifactUuid(eq(ORG), any()))
				.thenReturn(List.of(asc));
		UUID a1 = UUID.randomUUID();
		UUID a2 = UUID.randomUUID();
		ArtifactCanonicalMap m1 = new ArtifactCanonicalMap();
		m1.setArtifactUuid(a1);
		ArtifactCanonicalMap m2 = new ArtifactCanonicalMap();
		m2.setArtifactUuid(a2);
		lenient().when(artifactCanonicalMapRepository.findByOrgAndCanonicalArtifactUuid(ORG, k1)).thenReturn(List.of(m1));
		lenient().when(artifactCanonicalMapRepository.findByOrgAndCanonicalArtifactUuid(ORG, k2)).thenReturn(List.of(m2));
		// Distinct artifacts, each with its own stub: an unmatched strict call would raise
		// PotentialStubbingProblem, which the apply's catch would read as FAILED.
		art1 = mock(Artifact.class);
		art2 = mock(Artifact.class);
		lenient().when(sharedArtifactService.getArtifact(a1)).thenReturn(Optional.of(art1));
		lenient().when(sharedArtifactService.getArtifact(a2)).thenReturn(Optional.of(art2));
		lenient().when(sharedArtifactService.updateArtifactDti(eq(art2), any(), any())).thenReturn(art2);
	}

	@Test
	void aFailedApplyLeavesTheCanonicalUnstampedAndRequeuedAndTheDrainCompletes() {
		twoAppliedCanonicals();
		when(sharedArtifactService.updateArtifactDti(eq(art1), any(), any()))
				.thenThrow(new ObjectOptimisticLockingFailureException(Artifact.class, "a1"))
				.thenReturn(art1);

		service.fanOutOrg(ORG);

		verify(artifactCanonicalMapRepository, times(1)).requeueFanOutForCanonical(ORG, k1);
		verify(artifactCanonicalMapRepository, never()).stampFanOutPass(eq(ORG), eq(k1), any(), any());
		verify(artifactCanonicalMapRepository, times(1)).stampFanOutPass(ORG, k2, iso(T1), P0);
		InOrder completion = inOrder(bucketRepository, systemInfoService);
		completion.verify(bucketRepository).clearFanOutChangedThrough(ORG, T1);
		completion.verify(systemInfoService).clearFanOutWalk(ORG);
		completion.verify(systemInfoService).stampFanOutDrainedAt(ORG, T1);

		// Pass 2: (A) returns K1 (the requeue removed its fanOutAt); the cutoff T1 is not after the cache.
		when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(List.of(k1));
		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(256)).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
		verify(artifactCanonicalMapRepository, times(1)).stampFanOutPass(ORG, k1, iso(T1), P0);
		verify(artifactCanonicalMapRepository, times(1)).requeueFanOutForCanonical(any(), any());
	}

	@Test
	void aRequeueThatThrowsIsAStampFailure() {
		twoAppliedCanonicals();
		when(sharedArtifactService.updateArtifactDti(eq(art1), any(), any()))
				.thenThrow(new ObjectOptimisticLockingFailureException(Artifact.class, "a1"));
		when(artifactCanonicalMapRepository.requeueFanOutForCanonical(ORG, k1))
				.thenThrow(new DataAccessResourceFailureException("connection lost"));

		service.fanOutOrg(ORG);

		verify(artifactCanonicalMapRepository, never()).stampFanOutPass(eq(ORG), eq(k1), any(), any());
		verify(artifactCanonicalMapRepository, times(1)).stampFanOutPass(eq(ORG), eq(k2), any(), any());
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());
		verifyNoDrain();
	}

	@Test
	void aGoneArtifactIsStampedAndNotRequeued() {
		twoAppliedCanonicals();
		lenient().when(sharedArtifactService.getArtifact(any())).thenAnswer(inv -> Optional.empty());

		service.fanOutOrg(ORG);

		verify(artifactCanonicalMapRepository, times(1)).stampFanOutPass(ORG, k1, iso(T1), P0);
		verify(artifactCanonicalMapRepository, never()).requeueFanOutForCanonical(any(), any());
		verify(sharedArtifactService, never()).updateArtifactDti(any(), any(), any());
	}

	// ---------------- a change during a drain ----------------

	@Test
	void aBucketChangeDuringADrainJoinsTheNextDrain() {
		SyntheticDtrackBucket b0 = bucket(0, T1, c1);
		buckets(b0);
		List<UUID> dense = sortedInSlice(0, 497);
		UUID last = dense.get(496);
		slice(START, S0, 497, dense);
		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(null,
				new FanOutWalkState(iso(T1), last.toString()), null);

		service.fanOutOrg(ORG);

		verify(systemInfoService, times(1)).stampFanOutWalk(ORG, T1, last);

		// Between the passes bucket 0 moves to T2: c1 keeps its T1 entry, c9 changes at T2.
		record(b0, T2, Map.of(c1, T1, c9, T2));
		service.fanOutOrg(ORG);

		assertEquals(Set.of(c1), arrayAt(last), "the running drain keeps its set");
		verify(bucketRepository, times(1)).clearFanOutChangedThrough(ORG, T1);
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);

		// The clear removed the T1 entry: c9 alone is pending, and a new drain starts at T2.
		record(b0, T2, Map.of(c9, T2));
		service.fanOutOrg(ORG);

		ArgumentCaptor<UUID[]> changed = ArgumentCaptor.forClass(UUID[].class);
		verify(fanOutWalkRepository, times(2)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), changed.capture(),
				anyInt());
		assertEquals(Set.of(c9), Set.of(changed.getAllValues().get(1)), "the next drain walks c9 from the start");
		verify(systemInfoService, times(1)).stampFanOutWalk(ORG, T2, last);
	}

	// ---------------- the budget ----------------

	@Test
	void theBudgetEndsTheWalkBetweenStatementsAndTheCursorIsPersisted() {
		buckets(bucket(0, T1, c1));
		AtomicInteger reads = new AtomicInteger();
		oneStatementPerPass(reads);

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(any(), any(), any(), any(), anyInt());
		verify(systemInfoService, times(1)).stampFanOutWalk(ORG, T1, S1);
		assertEquals(3, reads.get(), "the deadline, the check before slice 0, the check that breaks");
	}

	// ---------------- back-pressure (D19, D20) ----------------

	@Test
	void theWalkIsPausedWhileTheMetricsBacklogIsAtOrAboveTheThreshold() {
		buckets(bucket(0, T1, c1));
		List<UUID> pending = sortedInSlice(200, 2000);
		when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(pending);
		when(releaseRepository.countReleasesEligibleForMetricsCompute()).thenReturn(16887L);

		service.fanOutOrg(ORG);

		verifyNoSliceStatement();
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());
		assertEquals(pending.subList(0, 500), stampedCanonicals(500), "(A) and its surplus are not paced");
		verify(releaseRepository, times(1)).countReleasesEligibleForMetricsCompute();
		verifyNoDrain();
	}

	@Test
	void aBacklogAtTheThresholdPausesTheWalk() {
		buckets(bucket(0, T1, c1));
		when(releaseRepository.countReleasesEligibleForMetricsCompute()).thenReturn(1000L);

		service.fanOutOrg(ORG);

		verifyNoSliceStatement();
		verifyNoDrain();
	}

	@Test
	void aBacklogOneBelowTheThresholdLeavesARoomOfOne() {
		noNeverStamped();
		buckets(bucket(0, T1, c1));
		when(releaseRepository.countReleasesEligibleForMetricsCompute()).thenReturn(999L);

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(1));
	}

	@Test
	void aBacklogOf900LeavesARoomOf100() {
		noNeverStamped();
		buckets(bucket(0, T1, c1));
		when(releaseRepository.countReleasesEligibleForMetricsCompute()).thenReturn(900L);

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(100));
	}

	@Test
	void noBacklogLeavesTheFullRoom() {
		noNeverStamped();
		buckets(bucket(0, T1, c1));
		when(releaseRepository.countReleasesEligibleForMetricsCompute()).thenReturn(0L);

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
	}

	@Test
	void aFailingBacklogCountLeavesTheWalkUnpaced() {
		noNeverStamped();
		buckets(bucket(0, T1, c1));
		when(releaseRepository.countReleasesEligibleForMetricsCompute()).thenThrow(new RuntimeException("count failed"));

		service.fanOutOrg(ORG);

		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	// ---------------- the cold-start skip (D17) ----------------

	@Test
	void theColdStartSkipClearsThePendingEntriesUpToTheCutoff() {
		when(artifactCanonicalMapRepository.countFanOutStamped(ORG)).thenReturn(0L);
		buckets(bucket(0, T1, c1));

		service.fanOutOrg(ORG);

		verifyNoSliceStatement();
		verify(bucketRepository, times(1)).clearFanOutChangedThrough(ORG, T1);
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	@Test
	void theColdStartSkipCatchesNothingUp() {
		when(artifactCanonicalMapRepository.countFanOutStamped(ORG)).thenReturn(0L);
		buckets(unrecordedBucket(ORG, 0, T1));

		service.fanOutOrg(ORG);

		verify(bucketRepository, never()).stampFlowControl(any(), any());
		verify(sbomComponentRepository, never()).findUuidsByOrgAndCanonicalPurlIn(any(), any());
		verifyNoSliceStatement();
	}

	// ---------------- the catch-up (D22) and the restart (D23) ----------------

	private static final String PU1 = "pkg:npm/p1@1";
	private static final String PU2 = "pkg:npm/p2@1";

	private void p1p2ResolveToC1C2() {
		lenient().when(sbomComponentRepository.findUuidsByOrgAndCanonicalPurlIn(eq(ORG), any()))
				.thenAnswer(purlsResolveTo(Map.of(PU1, c1, PU2, c2)));
	}

	private void assertCaughtUpAtT1(SyntheticDtrackBucket b) {
		verify(sbomComponentRepository, times(1)).findUuidsByOrgAndCanonicalPurlIn(ORG, Set.of(PU1, PU2));
		ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
		verify(bucketRepository, times(1)).stampFlowControl(eq(b.getUuid()), json.capture());
		BucketFlowControl fc = parseFlowControl(json.getValue());
		assertEquals(Map.of(c1.toString(), iso(T1), c2.toString(), iso(T1)), fc.fanOutChanged());
		assertEquals(iso(T1), fc.recordedAt());
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), eq(500));
		assertEquals(Set.of(c1, c2), arrayAt(START));
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
		assertEquals(T1, b.getLastUpdatedDate(), "the catch-up never moves the bucket date");
	}

	@Test
	void anUnrecordedBucketDatedAfterTheWatermarkIsCaughtUpWithItsWholeMembership() {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T0);
		noNeverStamped();
		p1p2ResolveToC1C2();
		SyntheticDtrackBucket b = withMembers(unrecordedBucket(ORG, 0, T1), PU1, PU2);
		buckets(b);

		service.fanOutOrg(ORG);

		assertCaughtUpAtT1(b);
	}

	@Test
	void aBucketRecordedBeforeItsLastMoveIsCaughtUp() {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T0);
		noNeverStamped();
		p1p2ResolveToC1C2();
		SyntheticDtrackBucket b = withMembers(unrecordedBucket(ORG, 0, T1, iso(T0)), PU1, PU2);
		buckets(b);

		service.fanOutOrg(ORG);

		assertCaughtUpAtT1(b);
	}

	/** F-9: recordedAt after the bucket date (a behind-clock old replica's save after a new-code save). */
	@Test
	void aBucketBackdatedBelowItsRecordedAtIsCaughtUp() {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T0);
		noNeverStamped();
		p1p2ResolveToC1C2();
		SyntheticDtrackBucket b = withMembers(unrecordedBucket(ORG, 0, T1, iso(T2)), PU1, PU2);
		buckets(b);

		service.fanOutOrg(ORG);

		assertCaughtUpAtT1(b);
	}

	@Test
	void aRecordedBucketWithNothingPendingIsNotCaughtUp() {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T0);
		buckets(withMembers(bucket(0, T1), PU1, PU2));

		service.fanOutOrg(ORG);

		verify(bucketRepository, never()).stampFlowControl(any(), any());
		verifyNoSliceStatement();
		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, T1);
	}

	@Test
	void anUnrecordedBucketDatedAtOrBeforeTheWatermarkStartsNothing() {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T1);
		buckets(unrecordedBucket(ORG, 0, T1), unrecordedBucket(ORG, 1, T0));

		service.fanOutOrg(ORG);

		verify(bucketRepository, never()).stampFlowControl(any(), any());
		verify(systemInfoService, never()).getFanOutWalk(any());
		verifyNoSliceStatement();
	}

	@Test
	void anUnrecordedBucketAtTheWatermarkIsNotCaughtUpWhileAnotherBucketDrains() {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T0);
		SyntheticDtrackBucket old = unrecordedBucket(ORG, 1, T0);
		buckets(bucket(0, T1, c1), old);

		service.fanOutOrg(ORG);

		verify(bucketRepository, never()).stampFlowControl(any(), any());
		assertEquals(Set.of(c1), arrayAt(START));
	}

	@Test
	void theCatchUpCoversEveryBucketState() {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T0);
		noNeverStamped();
		lenient().when(sbomComponentRepository.findUuidsByOrgAndCanonicalPurlIn(eq(ORG), any()))
				.thenAnswer(purlsResolveTo(Map.of(PU2, c2)));
		SyntheticDtrackBucket submitted = withMembers(unrecordedBucket(ORG, 2, T1), PU2);
		submitted.setIngestState(IngestState.SUBMITTED);
		SyntheticDtrackBucket pending = unrecordedBucket(ORG, 3, T1);
		pending.setIngestState(IngestState.PENDING);
		pending.getRefMap().clear();
		buckets(bucket(0, T1, c1), submitted, pending);

		service.fanOutOrg(ORG);

		verify(sbomComponentRepository, times(1)).findUuidsByOrgAndCanonicalPurlIn(any(), any());
		ArgumentCaptor<String> json2 = ArgumentCaptor.forClass(String.class);
		verify(bucketRepository).stampFlowControl(eq(submitted.getUuid()), json2.capture());
		assertEquals(Map.of(c2.toString(), iso(T1)), parseFlowControl(json2.getValue()).fanOutChanged());
		ArgumentCaptor<String> json3 = ArgumentCaptor.forClass(String.class);
		verify(bucketRepository).stampFlowControl(eq(pending.getUuid()), json3.capture());
		BucketFlowControl fc3 = parseFlowControl(json3.getValue());
		assertTrue(fc3.fanOutChanged().isEmpty());
		assertEquals(iso(T1), fc3.recordedAt());
		assertEquals(Set.of(c1, c2), arrayAt(START));
	}

	@Test
	void aReKeyOnAnUnrecordedBucketRecordsItsWholeMembershipBeforeTheWalk() {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T0);
		noNeverStamped();
		SyntheticDtrackBucket b = reKeyFixture();
		ArgumentCaptor<SyntheticDtrackBucket> saved = ArgumentCaptor.forClass(SyntheticDtrackBucket.class);
		when(bucketRepository.save(saved.capture())).thenAnswer(inv -> inv.getArgument(0));

		service.fanOutOrg(ORG);

		BucketFlowControl fc = saved.getValue().getFlowControl();
		ZonedDateTime at = saved.getValue().getLastUpdatedDate();
		assertTrue(at.isAfter(T1), "the save bumped the date");
		assertEquals(at, at.truncatedTo(ChronoUnit.MICROS), "to the microsecond");
		assertEquals(Map.of(c1.toString(), iso(at), c2.toString(), iso(at), c3.toString(), iso(at)), fc.fanOutChanged());
		assertEquals(iso(at), fc.recordedAt());
		verify(bucketRepository, never()).stampFlowControl(any(), any());
		assertEquals(Set.of(c1, c2, c3), arrayAt(START));
		// No restart: the walk state is cleared once, by the completion, after the walk.
		InOrder order = inOrder(fanOutWalkRepository, systemInfoService);
		order.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), anyInt());
		order.verify(systemInfoService).clearFanOutWalk(ORG);
		verify(systemInfoService, times(1)).clearFanOutWalk(ORG);
		verify(bucketRepository, times(1)).clearFanOutChangedThrough(ORG, at);
	}

	@Test
	void aReKeyWhoseSaveFailsIsCaughtUpInPlaceAtItsOwnDate() {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T0);
		noNeverStamped();
		SyntheticDtrackBucket b = reKeyFixture();
		when(bucketRepository.save(any())).thenThrow(new RuntimeException("stale revision"));

		service.fanOutOrg(ORG);

		assertEquals(T1, b.getLastUpdatedDate(), "the date is restored");
		ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
		verify(bucketRepository, times(1)).stampFlowControl(eq(b.getUuid()), json.capture());
		BucketFlowControl fc = parseFlowControl(json.getValue());
		assertEquals(Map.of(c1.toString(), iso(T1), c2.toString(), iso(T1), c3.toString(), iso(T1)), fc.fanOutChanged(),
				"the restored flow_control was null: the catch-up records the whole membership at T1");
		assertEquals(Set.of(c1, c2, c3), arrayAt(START));
		verify(bucketRepository, times(1)).clearFanOutChangedThrough(ORG, T1);
	}

	private static final String ZLIB = "pkg:deb/debian/zlib@1:1.2.13.dfsg-1?distro=debian-12";
	private static final String ZLIB_DT = "pkg:deb/debian/zlib@1%3A1.2.13.dfsg-1?distro=debian-12";

	/** An unrecorded bucket at T1 whose findings are keyed by DTrack's re-encoded purl (the TPF-129-era shape). */
	private SyntheticDtrackBucket reKeyFixture() {
		SyntheticDtrackBucket b = withMembers(unrecordedBucket(ORG, 0, T1), PU1, PU2, ZLIB);
		Map<String, Object> v = new HashMap<>();
		v.put("vulnId", "CVE-2023-45853");
		Map<String, Object> entry = new HashMap<>();
		entry.put("vulns", new ArrayList<>(List.of(v)));
		Map<String, Object> findings = new HashMap<>();
		findings.put(ZLIB_DT, entry);
		b.setFindings(findings);
		buckets(b);
		lenient().when(sbomComponentRepository.findUuidsByOrgAndCanonicalPurlIn(eq(ORG), any()))
				.thenAnswer(purlsResolveTo(Map.of(PU1, c1, PU2, c2, ZLIB, c3)));
		return b;
	}

	@Test
	void aCatchUpAtOrBeforeARunningDrainsCutoffRestartsIt() {
		UUID x = inSlice(0x11);
		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(new FanOutWalkState(iso(T1), x.toString()));
		lenient().when(sbomComponentRepository.findUuidsByOrgAndCanonicalPurlIn(eq(ORG), any()))
				.thenAnswer(purlsResolveTo(Map.of(PU1, c4)));
		SyntheticDtrackBucket late = withMembers(unrecordedBucket(ORG, 1, T1), PU1);
		buckets(recordedBucket(ORG, 0, T2, Map.of(c1, T1, c9, T2)), late);
		AtomicInteger reads = new AtomicInteger();
		oneStatementPerPass(reads);

		service.fanOutOrg(ORG);

		verify(bucketRepository, times(1)).stampFlowControl(eq(late.getUuid()), any());
		InOrder restart = inOrder(systemInfoService, fanOutWalkRepository);
		restart.verify(systemInfoService).clearFanOutWalk(ORG);
		restart.verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(START), eq(S0), any(), anyInt());
		assertEquals(Set.of(c1, c9, c4), arrayAt(START), "every entry at or before the new cutoff T2");
		verify(systemInfoService, times(1)).stampFanOutWalk(ORG, T2, S1);
	}

	@Test
	void aCatchUpAfterARunningDrainsCutoffDoesNotRestartIt() {
		UUID x = inSlice(0x11);
		when(systemInfoService.getFanOutWalk(ORG)).thenReturn(new FanOutWalkState(iso(T1), x.toString()));
		lenient().when(sbomComponentRepository.findUuidsByOrgAndCanonicalPurlIn(eq(ORG), any()))
				.thenAnswer(purlsResolveTo(Map.of(PU1, c4)));
		SyntheticDtrackBucket late = withMembers(unrecordedBucket(ORG, 1, T2), PU1);
		buckets(recordedBucket(ORG, 0, T2, Map.of(c1, T1, c9, T2)), late);
		AtomicInteger reads = new AtomicInteger();
		oneStatementPerPass(reads);

		service.fanOutOrg(ORG);

		verify(bucketRepository, times(1)).stampFlowControl(eq(late.getUuid()), any());
		verify(systemInfoService, never()).clearFanOutWalk(any());
		verify(fanOutWalkRepository, times(1)).findChangedCanonicalsInSlice(eq(ORG), eq(x), eq(endOf(17)), any(), anyInt());
		assertEquals(Set.of(c1), arrayAt(x), "the running drain resumes with its own set (D12)");
	}

	// ---------------- every state (D24) ----------------

	@Test
	void thePendingSetReadsEveryBucketState() {
		SyntheticDtrackBucket submitted = bucket(1, T1, c2);
		submitted.setIngestState(IngestState.SUBMITTED);
		SyntheticDtrackBucket pendingRetire = bucket(2, T1, c3);
		pendingRetire.setIngestState(IngestState.PENDING);
		SyntheticDtrackBucket failed = bucket(3, T1, c4);
		failed.setIngestState(IngestState.FAILED);
		buckets(bucket(0, T1, c1), submitted, pendingRetire, failed);

		service.fanOutOrg(ORG);

		assertEquals(Set.of(c1, c2, c3, c4), arrayAt(START));
		verify(bucketRepository, never()).stampFlowControl(any(), any());
	}
}
