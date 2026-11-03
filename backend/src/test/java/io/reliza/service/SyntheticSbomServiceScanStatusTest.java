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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Appender;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;

import io.reliza.model.Artifact;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactData;
import io.reliza.model.ArtifactData.ArtifactType;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.BucketFlowControl;
import io.reliza.model.ArtifactScanState;
import io.reliza.model.CanonicalMapFlowControl;
import io.reliza.model.ArtifactNotScannable;
import io.reliza.model.NoMatchableComponentsReason;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentFlowControl;
import io.reliza.model.ScanBlockerEnrichmentState;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.model.dto.ArtifactScanStatusDto;
import io.reliza.model.dto.ArtifactScanStatusDto.ScanBlockerDto;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.FanOutWalkRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SyntheticDtrackBucketRepository;

/**
 * TPF-121 architecture-3 section 6, rows 1-12 and 20-23: SyntheticSbomService.explainArtifactScanStatus, the
 * candidate rule it mirrors (isInFanOutCandidateSet), the gate helpers extracted from fanOutOrg and the
 * database-precision rounding of rule (B). The SyntheticSbomServiceTest fixture idiom; a shared AfterEach pins
 * that the explainer writes nothing (row 11).
 */
@ExtendWith(MockitoExtension.class)
class SyntheticSbomServiceScanStatusTest {

	@Mock private SbomComponentRepository sbomComponentRepository;
	@Mock private SyntheticDtrackBucketRepository bucketRepository;
	@Mock private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Mock private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Mock private SystemInfoService systemInfoService;
	@Mock private SharedArtifactService sharedArtifactService;
	@Mock private DTrackService dTrackService;
	@Mock private RebomService rebomService;
	@Mock private IntegrationService integrationService;
	@Mock private VulnAnalysisService vulnAnalysisService;
	@Mock private SbomComponentService sbomComponentService;
	@Mock private SyntheticOrgLock syntheticOrgLock;
	@Mock private FanOutWalkRepository fanOutWalkRepository;
	@Mock private ReleaseRepository releaseRepository;

	@InjectMocks private SyntheticSbomService service;

	/** The stubbed database clock of every fan-out pass (TPF-114), as in SyntheticSbomServiceTest. */
	private static final ZonedDateTime P0 = ZonedDateTime.of(2026, 10, 29, 0, 0, 0, 0, ZoneOffset.UTC);

	private final UUID ORG = UUID.randomUUID();
	private final UUID ARTIFACT = UUID.randomUUID();
	private final UUID CANONICAL = UUID.randomUUID();
	private final ZonedDateTime MAPPED_AT = ZonedDateTime.of(2026, 10, 1, 12, 0, 0, 0, ZoneOffset.UTC);

	@BeforeEach
	void setUp() {
		lenient().when(artifactCanonicalMapRepository.dbClockTimestamp()).thenReturn(P0);
	}

	/** Row 11: after every row, none of the eight write methods reachable from the service was called. */
	@AfterEach
	void explainerWritesNothing() {
		verify(bucketRepository, never()).save(any());
		verify(artifactCanonicalMapRepository, never()).save(any());
		verify(artifactCanonicalMapRepository, never()).stampFanOutPass(any(), any(), any(), any());
		verify(artifactCanonicalMapRepository, never()).requeueFanOutForComponents(any(), any());
		verify(artifactCanonicalMapRepository, never()).requeueFanOutForCanonical(any(), any());
		verify(sbomComponentRepository, never()).save(any());
		verify(sharedArtifactService, never()).updateArtifactDti(any(), any(), any());
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());
		verify(bucketRepository, never()).stampFlowControl(any(), any());
		verify(bucketRepository, never()).clearFanOutChangedThrough(any(), any());
		verify(systemInfoService, never()).stampFanOutWalk(any(), any(), any());
		verify(systemInfoService, never()).clearFanOutWalk(any());
	}

	// ---- fixture ------------------------------------------------------------------------------------------

	private static String iso(ZonedDateTime t) {
		return DateTimeFormatter.ISO_INSTANT.format(t.toInstant());
	}

	/** A bucket-column date: timestamptz keeps microseconds, so a fixture date never carries more. */
	private static ZonedDateTime columnNow() {
		return ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	private ArtifactData artifact(ArtifactType type, ZonedDateTime firstScanned, ZonedDateTime lastScanned) {
		ArtifactData ad = new ArtifactData();
		ReflectionTestUtils.setField(ad, "uuid", ARTIFACT);
		ad.setOrg(ORG);
		ad.setType(type);
		DependencyTrackIntegration m = new DependencyTrackIntegration();
		m.setFirstScanned(firstScanned);
		m.setLastScanned(lastScanned);
		ad.setMetrics(m);
		return ad;
	}

	private ArtifactData unscannedBom() {
		return artifact(ArtifactType.BOM, null, null);
	}

	private ArtifactCanonicalMap mapRow(UUID artifactUuid, CanonicalMapFlowControl fc) {
		ArtifactCanonicalMap m = new ArtifactCanonicalMap();
		m.setOrg(ORG);
		m.setArtifactUuid(artifactUuid);
		m.setCanonicalArtifactUuid(CANONICAL);
		m.setCreatedDate(MAPPED_AT);
		m.setFlowControl(fc);
		return m;
	}

	/** The own map row, found by artifact uuid. */
	private ArtifactCanonicalMap ownRow(String fanOutAt, String fanOutRequeuedAt) {
		CanonicalMapFlowControl fc = (null == fanOutAt && null == fanOutRequeuedAt) ? null
				: new CanonicalMapFlowControl(null, fanOutAt, fanOutRequeuedAt);
		ArtifactCanonicalMap own = mapRow(ARTIFACT, fc);
		when(artifactCanonicalMapRepository.findByArtifactUuid(ARTIFACT)).thenReturn(Optional.of(own));
		return own;
	}

	/** A stamped own row whose sibling read returns the own row only. */
	private ArtifactCanonicalMap stampedOwnRow(String fanOutAt) {
		ArtifactCanonicalMap own = ownRow(fanOutAt, null);
		when(artifactCanonicalMapRepository.findByOrgAndCanonicalArtifactUuid(ORG, CANONICAL)).thenReturn(List.of(own));
		return own;
	}

	private SbomComponent comp(String purl) {
		SbomComponent sc = new SbomComponent();
		sc.setUuid(UUID.randomUUID());
		sc.setOrg(ORG);
		sc.setCanonicalPurl(purl);
		return sc;
	}

	private SbomComponent enriched(String purl, Integer bucketIndex) {
		SbomComponent sc = comp(purl);
		sc.setEnrichedAt(ZonedDateTime.of(2026, 10, 2, 0, 0, 0, 0, ZoneOffset.UTC));
		sc.setSyntheticBucketIndex(bucketIndex);
		return sc;
	}

	private SbomComponent root(String purl) {
		SbomComponent sc = comp(purl);
		Map<String, Object> rd = new HashMap<>();
		rd.put("isRoot", true);
		sc.setRecordData(rd);
		return sc;
	}

	private SbomComponent terminal(String purl, String reason) {
		SbomComponent sc = comp(purl);
		sc.setFlowControl(new SbomComponentFlowControl("2026-10-01T00:00:00Z", reason));
		return sc;
	}

	/** The canonical's components, joined as the gate joins them. */
	private void components(SbomComponent... comps) {
		List<ArtifactSbomComponent> ascs = new ArrayList<>();
		for (SbomComponent sc : comps) {
			ArtifactSbomComponent asc = mock(ArtifactSbomComponent.class);
			when(asc.getSbomComponentUuid()).thenReturn(sc.getUuid());
			ascs.add(asc);
		}
		when(artifactSbomComponentRepository.findByOrgAndCanonicalArtifactUuid(ORG, CANONICAL)).thenReturn(ascs);
		when(sbomComponentRepository.findAllById(any())).thenReturn(List.of(comps));
	}

	private static SyntheticDtrackBucket bucket(int index, IngestState state, ZonedDateTime updated, String... purls) {
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setBucketIndex(index);
		b.setIngestState(state);
		b.setLastUpdatedDate(updated);
		b.setFindings(new HashMap<>());
		for (String p : purls) b.getRefMap().put(p, p);
		return b;
	}

	private void buckets(SyntheticDtrackBucket... bs) {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(bs));
	}

	/** The WARN lines the service logs while the action runs. */
	private List<String> warnLinesOf(Runnable action) {
		List<String> warns = new CopyOnWriteArrayList<>();
		Logger logger = (Logger) LogManager.getLogger(SyntheticSbomService.class);
		Appender appender = new AbstractAppender("scanStatusTestWarns", null, null, true, Property.EMPTY_ARRAY) {
			@Override
			public void append(LogEvent event) {
				if (event.getLevel() == Level.WARN) warns.add(event.getMessage().getFormattedMessage());
			}
		};
		// Outside a Spring context log4j2 runs its default configuration (ERROR): open WARN for the capture.
		// Set after addAppender, which re-derives the logger's level from its configuration.
		Level previous = logger.getLevel();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.WARN);
		try {
			action.run();
		} finally {
			logger.removeAppender(appender);
			appender.stop();
			logger.setLevel(previous);
		}
		return warns;
	}

	// ---- rows ---------------------------------------------------------------------------------------------

	/** Row 1: an unmapped BOM. */
	@Test
	void unmappedBomReadsNoBucketOrComponent() {
		when(artifactCanonicalMapRepository.findByArtifactUuid(ARTIFACT)).thenReturn(Optional.empty());

		ArtifactScanStatusDto s = service.explainArtifactScanStatus(unscannedBom());

		assertEquals(ArtifactScanState.UNMAPPED, s.state());
		assertFalse(s.mapped());
		assertNull(s.canonicalArtifactUuid());
		assertEquals(0, s.matchableComponentCount());
		assertEquals(0, s.rootComponentCount());
		assertEquals(0, s.terminalComponentCount());
		assertEquals(0, s.unmatchableComponentCount());
		assertEquals(0, s.ingestedBucketCount());
		assertEquals(0, s.blockerTotal());
		assertNotNull(s.blockers());
		assertTrue(s.blockers().isEmpty());
		assertFalse(s.inCandidateSet());
		assertTrue(s.candidateRule().startsWith("not mapped"), s.candidateRule());
		verifyNoInteractions(bucketRepository, artifactSbomComponentRepository, sbomComponentRepository, rebomService);
	}

	/** Row 2: a non-BOM artifact. */
	@Test
	void nonBomReadsNothing() {
		ArtifactScanStatusDto s = service.explainArtifactScanStatus(artifact(ArtifactType.VEX, null, null));

		assertEquals(ArtifactScanState.NOT_A_BOM, s.state());
		assertFalse(s.mapped());
		assertEquals("VEX", s.type());
		assertNotNull(s.blockers());
		assertTrue(s.blockers().isEmpty());
		assertFalse(s.inCandidateSet());
		assertTrue(s.candidateRule().startsWith("not a BOM"), s.candidateRule());
		verifyNoInteractions(bucketRepository, artifactSbomComponentRepository, sbomComponentRepository,
				artifactCanonicalMapRepository);
	}

	/** Row 3: blocked on an un-enriched component in a BEAR org (the fixture of the gate-skip test). */
	@Test
	void blockedOnUnenrichedComponentInBearOrg() {
		ZonedDateTime t1 = columnNow().minusMinutes(3);
		buckets(bucket(0, IngestState.INGESTED, t1, "pkg:npm/a@1"));
		components(comp("pkg:npm/a@1"), comp("pkg:npm/b@1"));
		ownRow(null, null);
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);

		ArtifactScanStatusDto s = service.explainArtifactScanStatus(unscannedBom());

		assertEquals(ArtifactScanState.BLOCKED_ON_COVERAGE, s.state());
		assertTrue(s.mapped());
		assertEquals(CANONICAL, s.canonicalArtifactUuid());
		assertEquals(MAPPED_AT, s.mappedAt());
		assertEquals(1, s.blockers().size());
		ScanBlockerDto b = s.blockers().get(0);
		assertEquals("pkg:npm/b@1", b.purl());
		assertEquals(ScanBlockerEnrichmentState.UNENRICHED, b.enrichment());
		assertNull(b.terminalReason());
		assertNull(b.bucketIndex());
		assertNull(b.bucketIngestState());
		assertNull(b.bucketLastUpdatedDate());
		assertEquals("not enriched yet; submitOrg ships only enriched components", b.reason());
		assertEquals(Boolean.TRUE, s.enrichmentGated());
		assertEquals(2, s.matchableComponentCount());
		assertEquals(1, s.blockerTotal());
		assertFalse(s.blockersTruncated());
		assertEquals(1, s.ingestedBucketCount());
		assertEquals(t1, s.newestIngestedBucketUpdate());
		assertTrue(s.inCandidateSet());
		assertEquals("never fanned out: this artifact's map row has no fanOutAt (rule A, newest map row first)",
				s.candidateRule());
	}

	/** Row 4: blocked on a bucket still SUBMITTED; the row carries the bucket fields, no DTrack project uuid. */
	@Test
	void blockedOnSubmittedBucketShowsBucketFieldsToAReader() {
		ZonedDateTime t0 = columnNow().minusMinutes(10);
		ZonedDateTime t7 = columnNow().minusMinutes(2);
		SyntheticDtrackBucket b7 = bucket(7, IngestState.SUBMITTED, t7);
		b7.setDtrackProjectUuid(UUID.randomUUID());
		buckets(bucket(0, IngestState.INGESTED, t0, "pkg:npm/a@1"), b7);
		components(comp("pkg:npm/a@1"), enriched("pkg:npm/b@1", 7));
		ownRow(null, null);
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);

		ArtifactScanStatusDto s = service.explainArtifactScanStatus(unscannedBom());

		assertEquals(ArtifactScanState.BLOCKED_ON_COVERAGE, s.state());
		assertEquals(1, s.blockers().size());
		ScanBlockerDto b = s.blockers().get(0);
		// The record has exactly the seven components below: no dtrackProjectUuid accessor exists.
		ScanBlockerDto expectedShape = new ScanBlockerDto(b.purl(), b.enrichment(), b.terminalReason(),
				b.bucketIndex(), b.bucketIngestState(), b.bucketLastUpdatedDate(), b.reason());
		assertEquals(expectedShape, b);
		assertEquals(7, ScanBlockerDto.class.getRecordComponents().length);
		assertEquals(ScanBlockerEnrichmentState.ENRICHED, b.enrichment());
		assertEquals(Integer.valueOf(7), b.bucketIndex());
		assertEquals("SUBMITTED", b.bucketIngestState());
		assertEquals(t7, b.bucketLastUpdatedDate());
		assertTrue(b.reason().startsWith("bucket 7 is SUBMITTED since "), b.reason());
		assertEquals(1, s.ingestedBucketCount());
		assertEquals(t0, s.newestIngestedBucketUpdate());
	}

	/** Row 5: a terminal component is not a blocker. */
	@Test
	void terminalComponentIsNotABlocker() {
		buckets(bucket(0, IngestState.INGESTED, columnNow(), "pkg:npm/dep@1.0"));
		components(comp("pkg:npm/dep@1.0"), root("pkg:generic/app@1.0"),
				terminal("pkg:npm/gone@1", "OWN_BOM_UNPULLABLE"));
		ownRow(null, null);

		ArtifactScanStatusDto s = service.explainArtifactScanStatus(unscannedBom());

		assertEquals(ArtifactScanState.WAITING_FOR_FAN_OUT, s.state());
		assertEquals(1, s.matchableComponentCount());
		assertEquals(1, s.terminalComponentCount());
		assertEquals(1, s.rootComponentCount());
		assertEquals(0, s.unmatchableComponentCount());
		assertTrue(s.blockers().isEmpty());
		assertEquals(0, s.blockerTotal());
		assertNull(s.noMatchableComponentsReason());
		assertNull(s.enrichmentGated());
	}

	/** How the covering bucket's fan-out bookkeeping reads (TPF-137 4.10). */
	private enum Bookkeeping { RECORDED, RECORDED_WITH_ENTRY, UNRECORDED }

	/** A bucket recorded at its date, with a pending entry at that date for each component given. */
	private static SyntheticDtrackBucket recorded(SyntheticDtrackBucket b, SbomComponent... changed) {
		Map<String, String> pending = new HashMap<>();
		for (SbomComponent sc : changed) pending.put(sc.getUuid().toString(), iso(b.getLastUpdatedDate()));
		b.setFlowControl(new BucketFlowControl(pending, iso(b.getLastUpdatedDate())));
		return b;
	}

	private ArtifactScanStatusDto scannedWithBucketAt(ZonedDateTime bucketUpdated) {
		return scannedWithBucketAt(bucketUpdated, IngestState.INGESTED, Bookkeeping.UNRECORDED);
	}

	private ArtifactScanStatusDto scannedWithBucketAt(ZonedDateTime bucketUpdated, IngestState state,
			Bookkeeping bookkeeping) {
		ZonedDateTime first = ZonedDateTime.of(2026, 10, 28, 10, 0, 1, 0, ZoneOffset.UTC);
		ZonedDateTime last = ZonedDateTime.of(2026, 10, 28, 10, 0, 2, 0, ZoneOffset.UTC);
		SbomComponent a = enriched("pkg:npm/a@1", 0);
		SyntheticDtrackBucket b = bucket(0, state, bucketUpdated, "pkg:npm/a@1");
		if (Bookkeeping.RECORDED == bookkeeping) recorded(b);
		if (Bookkeeping.RECORDED_WITH_ENTRY == bookkeeping) recorded(b, a);
		buckets(b);
		components(a);
		stampedOwnRow("2026-10-28T10:00:00Z");
		ArtifactScanStatusDto s = service.explainArtifactScanStatus(artifact(ArtifactType.BOM, first, last));
		assertEquals(ArtifactScanState.SCANNED, s.state());
		assertEquals(first, s.firstScanned());
		assertEquals(last, s.lastScanned());
		assertEquals(ZonedDateTime.of(2026, 10, 28, 10, 0, 0, 0, ZoneOffset.UTC), s.fanOutAt());
		assertEquals(ZoneOffset.UTC, s.fanOutAt().getZone());
		assertNull(s.fanOutRequeuedAt());
		verify(artifactCanonicalMapRepository, times(1)).findByOrgAndCanonicalArtifactUuid(ORG, CANONICAL);
		return s;
	}

	/**
	 * Row 6 (a), TPF-137: scanned, the covering bucket recorded and dated after the stamp with nothing
	 * pending: not a candidate (a bucket move alone no longer re-qualifies a canonical).
	 */
	@Test
	void scannedAndNotACandidate() {
		ArtifactScanStatusDto s = scannedWithBucketAt(ZonedDateTime.of(2026, 10, 28, 11, 0, 0, 0, ZoneOffset.UTC),
				IngestState.INGESTED, Bookkeeping.RECORDED);
		assertFalse(s.inCandidateSet());
		assertEquals("not a candidate: fanned out at 2026-10-28T10:00:00Z and no component of it has a pending "
				+ "change since", s.candidateRule());
	}

	/** Row 6 (a'): an unrecorded covering bucket older than the stamp: not a candidate either. */
	@Test
	void anUnrecordedBucketOlderThanTheStampIsNoCandidate() {
		ArtifactScanStatusDto s = scannedWithBucketAt(ZonedDateTime.of(2026, 10, 28, 9, 0, 0, 0, ZoneOffset.UTC));
		assertFalse(s.inCandidateSet());
	}

	/** Row 6 (b), TPF-137 B1: the component has a pending entry after the stamp: rule B. */
	@Test
	void scannedAndACandidateUnderRuleB() {
		ArtifactScanStatusDto s = scannedWithBucketAt(ZonedDateTime.of(2026, 10, 28, 11, 0, 0, 0, ZoneOffset.UTC),
				IngestState.INGESTED, Bookkeeping.RECORDED_WITH_ENTRY);
		assertTrue(s.inCandidateSet());
		assertEquals("component pkg:npm/a@1 changed in bucket 0 at 2026-10-28T11:00:00Z after fanOutAt "
				+ "2026-10-28T10:00:00Z (rule B, change-driven)", s.candidateRule());
	}

	/** TPF-137 B2: an unrecorded covering bucket dated after the stamp (the catch-up, mirrored). */
	@Test
	void anUnrecordedBucketMovedAfterTheStampIsACandidateUnderRuleB() {
		ArtifactScanStatusDto s = scannedWithBucketAt(ZonedDateTime.of(2026, 10, 28, 11, 0, 0, 0, ZoneOffset.UTC));
		assertTrue(s.inCandidateSet());
		assertEquals("bucket 0 holding component pkg:npm/a@1 moved at 2026-10-28T11:00:00Z after fanOutAt "
				+ "2026-10-28T10:00:00Z and is not yet recorded (rule B, change-driven; the next pass records it)",
				s.candidateRule());
	}

	/** TPF-137 D24: a SUBMITTED bucket's pending entry counts as well. */
	@Test
	void aSubmittedBucketWithAPendingEntryIsACandidate() {
		ArtifactScanStatusDto s = scannedWithBucketAt(ZonedDateTime.of(2026, 10, 28, 11, 0, 0, 0, ZoneOffset.UTC),
				IngestState.SUBMITTED, Bookkeeping.RECORDED_WITH_ENTRY);
		assertTrue(s.inCandidateSet());
		assertTrue(s.candidateRule().startsWith("component pkg:npm/a@1 changed in bucket 0"), s.candidateRule());
	}

	/** Row 7: the gate skips and the explainer blocks on the same fixture (the gate-skip test, first pass). */
	@Test
	void predicateEqualsTheGateOnTheSkipSide() {
		String aPurl = "pkg:npm/a@1", bPurl = "pkg:npm/b@1";
		ZonedDateTime t1 = columnNow().minusMinutes(3);
		SyntheticDtrackBucket bucket = bucket(0, IngestState.INGESTED, t1, aPurl);
		buckets(bucket);
		SbomComponent ca = comp(aPurl);
		SbomComponent cb = comp(bPurl);
		components(ca, cb);
		when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(List.of(CANONICAL));
		when(artifactCanonicalMapRepository.stampFanOutPass(ORG, CANONICAL, iso(t1), P0)).thenReturn(1);

		service.fanOutOrg(ORG);

		verify(sharedArtifactService, never()).updateArtifactDti(any(), any(), any());
		verify(artifactCanonicalMapRepository, times(1)).stampFanOutPass(ORG, CANONICAL, iso(t1), P0);
		clearInvocations(artifactCanonicalMapRepository, bucketRepository, sharedArtifactService, systemInfoService,
				sbomComponentRepository);

		stampedOwnRow(iso(t1));
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		ArtifactScanStatusDto s = service.explainArtifactScanStatus(unscannedBom());

		assertEquals(Set.of(bPurl), Set.copyOf(s.blockers().stream().map(ScanBlockerDto::purl).toList()));
		assertTrue(SyntheticSbomService.isGateMatchable(ca));
		assertTrue(SyntheticSbomService.isGateMatchable(cb));
		assertEquals(Set.of(aPurl), SyntheticSbomService.coveredPurlsOf(List.of(bucket)));
		assertEquals(t1, SyntheticSbomService.candidateCutoffOf(List.of(bucket)));
		assertEquals(ArtifactScanState.BLOCKED_ON_COVERAGE, s.state());
		// The stamp equals the bucket date: the strict comparison fails, as TPF-114 3.2 states.
		assertFalse(s.inCandidateSet());
		assertTrue(s.candidateRule().startsWith("not a candidate"), s.candidateRule());
	}

	/** Row 8: the gate passes and the explainer reports SCANNED on the same fixture (the root test). */
	@Test
	void predicateEqualsTheGateOnThePassSide() {
		String depPurl = "pkg:npm/dep@1.0", rootPurl = "pkg:generic/app@1.0";
		UUID artifactOfCanonical = UUID.randomUUID();
		SyntheticDtrackBucket bucket = bucket(0, IngestState.INGESTED, columnNow(), depPurl);
		buckets(bucket);
		components(comp(depPurl), root(rootPurl));
		when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(List.of(CANONICAL));
		ArtifactCanonicalMap passRow = mapRow(artifactOfCanonical, null);
		when(artifactCanonicalMapRepository.findByOrgAndCanonicalArtifactUuid(ORG, CANONICAL)).thenReturn(List.of(passRow));
		when(sharedArtifactService.getArtifact(artifactOfCanonical)).thenReturn(Optional.of(mock(Artifact.class)));
		ArgumentCaptor<String> stamp = ArgumentCaptor.forClass(String.class);
		when(artifactCanonicalMapRepository.stampFanOutPass(eq(ORG), eq(CANONICAL), stamp.capture(), eq(P0)))
				.thenReturn(1);
		ArgumentCaptor<DependencyTrackIntegration> dti = ArgumentCaptor.forClass(DependencyTrackIntegration.class);

		service.fanOutOrg(ORG);

		verify(sharedArtifactService, times(1)).updateArtifactDti(any(), dti.capture(), any());
		ZonedDateTime lastScanned = dti.getValue().getLastScanned();
		assertEquals(iso(bucket.getLastUpdatedDate()), stamp.getValue());
		clearInvocations(artifactCanonicalMapRepository, bucketRepository, sharedArtifactService, systemInfoService,
				sbomComponentRepository);

		stampedOwnRow(stamp.getValue());
		ArtifactScanStatusDto s = service.explainArtifactScanStatus(
				artifact(ArtifactType.BOM, lastScanned, lastScanned));

		assertEquals(ArtifactScanState.SCANNED, s.state());
		assertTrue(s.blockers().isEmpty());
		assertEquals(1, s.rootComponentCount());
		assertEquals(1, s.matchableComponentCount());
		assertEquals(Instant.parse(stamp.getValue()), s.fanOutAt().toInstant());
		assertFalse(s.inCandidateSet());
	}

	/** Row 9: only a root and a terminal component. */
	@Test
	void noMatchableComponents() {
		buckets(bucket(0, IngestState.INGESTED, columnNow(), "pkg:npm/x@1"));
		components(root("pkg:generic/app@1.0"), terminal("pkg:npm/gone@1", "OWN_BOM_PULLED_UNMATCHED"));
		ownRow(null, null);

		ArtifactScanStatusDto s = service.explainArtifactScanStatus(unscannedBom());

		assertEquals(ArtifactScanState.NO_MATCHABLE_COMPONENTS, s.state());
		assertEquals(0, s.matchableComponentCount());
		assertEquals(1, s.rootComponentCount());
		assertEquals(1, s.terminalComponentCount());
		assertTrue(s.blockers().isEmpty());
		assertEquals(NoMatchableComponentsReason.ALL_TERMINAL, s.noMatchableComponentsReason());
		assertTrue(s.inCandidateSet());
	}

	/** Row 10: blocker cap and order, enrichment-waiting rows first, then by purl. */
	@Test
	void blockerCapAndOrder() {
		buckets(bucket(0, IngestState.INGESTED, columnNow(), "pkg:npm/covered@1"));
		List<SbomComponent> comps = new ArrayList<>();
		for (int i = 0; i < 60; i++) {
			String purl = String.format("pkg:npm/c%02d", i);
			comps.add(i % 2 == 0 ? comp(purl) : enriched(purl, null));
		}
		components(comps.toArray(new SbomComponent[0]));
		ownRow(null, null);
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);

		ArtifactScanStatusDto s = service.explainArtifactScanStatus(unscannedBom());

		assertEquals(50, s.blockers().size());
		for (int r = 0; r < 30; r++) {
			assertEquals(ScanBlockerEnrichmentState.UNENRICHED, s.blockers().get(r).enrichment());
			assertEquals(String.format("pkg:npm/c%02d", 2 * r), s.blockers().get(r).purl());
		}
		for (int r = 30; r < 50; r++) {
			assertEquals(ScanBlockerEnrichmentState.ENRICHED, s.blockers().get(r).enrichment());
			assertEquals(String.format("pkg:npm/c%02d", 2 * (r - 30) + 1), s.blockers().get(r).purl());
		}
		assertEquals(60, s.blockerTotal());
		assertTrue(s.blockersTruncated());
		assertEquals(60, s.matchableComponentCount());
		verify(rebomService, times(1)).isEnrichmentConfigured(ORG);
	}

	/** Row 12: the extracted helpers on their own. */
	@Test
	void helpersMatchTheGate() {
		ZonedDateTime older = columnNow().minusHours(2);
		ZonedDateTime newer = columnNow().minusHours(1);
		ZonedDateTime newest = columnNow();
		SyntheticDtrackBucket ingested = bucket(0, IngestState.INGESTED, older, "pkg:npm/a@1", "__token");
		SyntheticDtrackBucket ingestedNewer = bucket(1, IngestState.INGESTED, newer, "cpe:2.3:a:x:y:1");
		SyntheticDtrackBucket submitted = bucket(2, IngestState.SUBMITTED, newest, "pkg:npm/b@1");
		SyntheticDtrackBucket noRefMap = bucket(3, IngestState.INGESTED, older);
		noRefMap.setRefMap(null);

		assertEquals(Set.of("pkg:npm/a@1", "cpe:2.3:a:x:y:1"),
				SyntheticSbomService.coveredPurlsOf(List.of(ingested, ingestedNewer, submitted, noRefMap)));
		assertEquals(newer, SyntheticSbomService.candidateCutoffOf(List.of(ingested, ingestedNewer, submitted)));
		assertNull(SyntheticSbomService.candidateCutoffOf(List.of(submitted)));
		assertNull(SyntheticSbomService.candidateCutoffOf(List.of()));

		assertFalse(SyntheticSbomService.isGateMatchable(root("pkg:npm/app@1")));
		assertFalse(SyntheticSbomService.isGateMatchable(terminal("pkg:npm/t@1", "UNMATCHABLE_PURL_TYPE")));
		assertFalse(SyntheticSbomService.isGateMatchable(comp(null)));
		assertFalse(SyntheticSbomService.isGateMatchable(comp("generic:foo")));
		assertTrue(SyntheticSbomService.isGateMatchable(comp("pkg:npm/a@1")));
		assertTrue(SyntheticSbomService.isGateMatchable(comp("cpe:2.3:a:x:y:1")));
	}

	/** Row 20 (a): a requeued row waits, with its requeue stamp parsed at microseconds. */
	@Test
	void requeuedRowIsARuleACandidate() {
		buckets(bucket(0, IngestState.INGESTED, columnNow(), "pkg:npm/a@1"));
		components(comp("pkg:npm/a@1"));
		ownRow(null, "2026-10-29T01:02:03.123456Z");

		ArtifactScanStatusDto s = service.explainArtifactScanStatus(unscannedBom());

		assertEquals(ArtifactScanState.WAITING_FOR_FAN_OUT, s.state());
		assertEquals(Instant.parse("2026-10-29T01:02:03.123456Z"), s.fanOutRequeuedAt().toInstant());
		assertNull(s.fanOutAt());
		assertTrue(s.inCandidateSet());
		assertTrue(s.candidateRule().startsWith("requeued at 2026-10-29T01:02:03.123456Z"), s.candidateRule());
		verify(artifactCanonicalMapRepository, never()).findByOrgAndCanonicalArtifactUuid(any(), any());
	}

	/** Row 20 (b): a stamped own row with an unstamped sibling of the same canonical. */
	@Test
	void unstampedSiblingMakesARuleACandidate() {
		ZonedDateTime t1 = columnNow().minusMinutes(5);
		buckets(bucket(0, IngestState.INGESTED, t1, "pkg:npm/a@1"));
		components(comp("pkg:npm/a@1"));
		ArtifactCanonicalMap own = ownRow(iso(t1), null);
		ArtifactCanonicalMap sibling = mapRow(UUID.randomUUID(), null);
		when(artifactCanonicalMapRepository.findByOrgAndCanonicalArtifactUuid(ORG, CANONICAL))
				.thenReturn(List.of(own, sibling));

		ArtifactScanStatusDto s = service.explainArtifactScanStatus(unscannedBom());

		assertTrue(s.inCandidateSet());
		assertTrue(s.candidateRule().contains("another artifact of the same canonical"), s.candidateRule());
	}

	/** Row 20 (c): a fresh reconcile insert (flow_control NULL) reads no siblings. */
	@Test
	void freshMapRowIsARuleACandidateWithoutASiblingRead() {
		buckets(bucket(0, IngestState.INGESTED, columnNow(), "pkg:npm/a@1"));
		components(comp("pkg:npm/a@1"));
		ownRow(null, null);

		ArtifactScanStatusDto s = service.explainArtifactScanStatus(unscannedBom());

		assertNull(s.fanOutAt());
		assertNull(s.fanOutRequeuedAt());
		assertTrue(s.inCandidateSet());
		assertTrue(s.candidateRule().startsWith("never fanned out: this artifact's map row has no fanOutAt"),
				s.candidateRule());
		verify(artifactCanonicalMapRepository, never()).findByOrgAndCanonicalArtifactUuid(any(), any());
	}

	private ArtifactScanStatusDto unscannedWith(ArtifactData ad, SbomComponent... comps) {
		buckets(bucket(0, IngestState.INGESTED, columnNow(), "pkg:npm/x@1"));
		components(comps);
		ownRow(null, null);
		ArtifactScanStatusDto s = service.explainArtifactScanStatus(ad);
		assertEquals(0, s.matchableComponentCount());
		assertTrue(s.blockers().isEmpty());
		assertTrue(s.inCandidateSet());
		return s;
	}

	/** Row 21 (a): zero component rows. */
	@Test
	void noMatchableReasonNoComponents() {
		ArtifactScanStatusDto s = unscannedWith(unscannedBom());
		assertEquals(ArtifactScanState.NO_MATCHABLE_COMPONENTS, s.state());
		assertEquals(NoMatchableComponentsReason.NO_COMPONENTS, s.noMatchableComponentsReason());
	}

	/** Row 21 (b): one root only. */
	@Test
	void noMatchableReasonOnlyRoot() {
		ArtifactScanStatusDto s = unscannedWith(unscannedBom(), root("pkg:generic/app@1.0"));
		assertEquals(ArtifactScanState.NO_MATCHABLE_COMPONENTS, s.state());
		assertEquals(NoMatchableComponentsReason.ONLY_ROOT, s.noMatchableComponentsReason());
	}

	/** Row 21 (c): a root plus two terminal rows. */
	@Test
	void noMatchableReasonAllTerminal() {
		ArtifactScanStatusDto s = unscannedWith(unscannedBom(), root("pkg:generic/app@1.0"),
				terminal("pkg:npm/t1@1", "OWN_BOM_PULLED_UNMATCHED"), terminal("pkg:npm/t2@1", "UNMATCHABLE_PURL_TYPE"));
		assertEquals(ArtifactScanState.NO_MATCHABLE_COMPONENTS, s.state());
		assertEquals(NoMatchableComponentsReason.ALL_TERMINAL, s.noMatchableComponentsReason());
	}

	/** Row 21 (d): two non-root non-terminal rows without a pkg:/cpe: purl. */
	@Test
	void noMatchableReasonNoPurlOrCpe() {
		ArtifactScanStatusDto s = unscannedWith(unscannedBom(), comp(null), comp("generic:foo"));
		assertEquals(ArtifactScanState.NO_MATCHABLE_COMPONENTS, s.state());
		assertEquals(NoMatchableComponentsReason.NO_PURL_OR_CPE, s.noMatchableComponentsReason());
		assertEquals(2, s.unmatchableComponentCount());
	}

	/** Row 21 (e): a SCANNED artifact with fixture (d), the vacuous-coverage pair. */
	@Test
	void scannedWithNoMatchableReason() {
		ZonedDateTime first = ZonedDateTime.of(2026, 10, 28, 10, 0, 0, 0, ZoneOffset.UTC);
		ArtifactScanStatusDto s = unscannedWith(artifact(ArtifactType.BOM, first, first), comp(null),
				comp("generic:foo"));
		assertEquals(ArtifactScanState.SCANNED, s.state());
		assertEquals(NoMatchableComponentsReason.NO_PURL_OR_CPE, s.noMatchableComponentsReason());
	}

	/**
	 * Row 21 (e)'s sibling (TPF-126 step 3a): fixture (d) on a scanned artifact that carries the not-scannable
	 * marker explains as NO_MATCHABLE_COMPONENTS from the marker, which wins over firstScanned.
	 */
	@Test
	void markedScannedArtifactExplainsAsNotScannable() {
		ZonedDateTime first = ZonedDateTime.of(2026, 10, 28, 10, 0, 0, 0, ZoneOffset.UTC);
		ZonedDateTime markedAt = ZonedDateTime.of(2026, 10, 29, 10, 0, 0, 0, ZoneOffset.UTC);
		ArtifactData ad = artifact(ArtifactType.BOM, first, first);
		ad.getMetrics().setNotScannable(new ArtifactNotScannable(NoMatchableComponentsReason.NO_PURL_OR_CPE,
				markedAt, 2, 0, 0, 2));
		ArtifactScanStatusDto s = unscannedWith(ad, comp(null), comp("generic:foo"));
		assertEquals(ArtifactScanState.NO_MATCHABLE_COMPONENTS, s.state());
		assertEquals(NoMatchableComponentsReason.NO_PURL_OR_CPE, s.noMatchableComponentsReason());
		assertEquals(markedAt, s.notScannableAt());
		assertEquals(first, s.firstScanned(), "firstScanned is still reported");
	}

	/**
	 * Step 3a's disagreement (TPF-126 3.9): the marker stands while the canonical has gained a matchable row the
	 * clearing pass has not seen. The state and the reason are the marker's, the live counts show the gained row,
	 * and inCandidateSet says the canonical qualifies for the clearing pass.
	 */
	@Test
	void markerWinsOverALiveMatchableRow() {
		ZonedDateTime markedAt = ZonedDateTime.of(2026, 10, 29, 10, 0, 0, 0, ZoneOffset.UTC);
		ArtifactData ad = unscannedBom();
		ad.getMetrics().setNotScannable(new ArtifactNotScannable(NoMatchableComponentsReason.NO_COMPONENTS,
				markedAt, 0, 0, 0, 0));
		buckets(bucket(0, IngestState.INGESTED, columnNow(), "pkg:npm/x@1"));
		components(comp("pkg:npm/late@1"));
		ownRow(null, "2026-10-29T11:00:00Z");

		ArtifactScanStatusDto s = service.explainArtifactScanStatus(ad);

		assertEquals(ArtifactScanState.NO_MATCHABLE_COMPONENTS, s.state());
		assertEquals(NoMatchableComponentsReason.NO_COMPONENTS, s.noMatchableComponentsReason());
		assertEquals(markedAt, s.notScannableAt());
		assertEquals(1, s.matchableComponentCount(), "the live count shows the disagreement");
		assertEquals(1, s.blockerTotal());
		assertTrue(s.inCandidateSet());
	}

	/** TPF-126 6.1 row 5 (case 2): the fan-out's reason and the explainer's agree on row 21's fixtures (a)-(d). */
	@Test
	void classifyForGateAgreesWithTheExplainer() {
		List<List<SbomComponent>> fixtures = List.of(List.of(), List.of(root("pkg:generic/app@1.0")),
				List.of(root("pkg:generic/app@1.0"), terminal("pkg:npm/t1@1", "OWN_BOM_PULLED_UNMATCHED"),
						terminal("pkg:npm/t2@1", "UNMATCHABLE_PURL_TYPE")),
				List.of(comp(null), comp("generic:foo")));
		for (List<SbomComponent> rows : fixtures) {
			ArtifactScanStatusDto s = unscannedWith(unscannedBom(), rows.toArray(new SbomComponent[0]));
			assertEquals(SyntheticSbomService.classifyForGate(rows).reason(), s.noMatchableComponentsReason());
			assertNull(s.notScannableAt(), "no marker, no notScannableAt");
		}
	}

	private ArtifactScanStatusDto precisionCase(String stamp, String bucketUpdated) {
		ZonedDateTime first = ZonedDateTime.of(2026, 10, 29, 9, 0, 0, 0, ZoneOffset.UTC);
		// TPF-137: the precision cases compare the component's pending entry (B1), not the bucket date.
		SbomComponent a = enriched("pkg:npm/a@1", 0);
		buckets(recorded(bucket(0, IngestState.INGESTED, Instant.parse(bucketUpdated).atZone(ZoneOffset.UTC),
				"pkg:npm/a@1"), a));
		components(a);
		stampedOwnRow(stamp);
		return service.explainArtifactScanStatus(artifact(ArtifactType.BOM, first, first));
	}

	/** Row 22 (a): the reviewer's probe, a nanosecond stamp against the microsecond column it rounds to. */
	@Test
	void ruleBAtDatabasePrecisionEqualAfterRounding() {
		ArtifactScanStatusDto s = precisionCase("2026-10-29T10:00:00.123456789Z", "2026-10-29T10:00:00.123457Z");
		assertFalse(s.inCandidateSet());
		assertTrue(s.candidateRule().startsWith("not a candidate: fanned out at 2026-10-29T10:00:00.123456789Z"),
				s.candidateRule());
		assertEquals(Instant.parse("2026-10-29T10:00:00.123456789Z"), s.fanOutAt().toInstant());
	}

	/** Row 22 (b): the bucket one microsecond later is a rule-B move. */
	@Test
	void ruleBAtDatabasePrecisionOneMicrosecondLater() {
		ArtifactScanStatusDto s = precisionCase("2026-10-29T10:00:00.123456789Z", "2026-10-29T10:00:00.123458Z");
		assertTrue(s.inCandidateSet());
		assertTrue(s.candidateRule().contains("changed in bucket 0 at 2026-10-29T10:00:00.123458Z"), s.candidateRule());
		assertTrue(s.candidateRule().contains("rule B"), s.candidateRule());
	}

	/** Row 22 (c): a stamp that rounds down is before the bucket. */
	@Test
	void ruleBAtDatabasePrecisionRoundsDown() {
		ArtifactScanStatusDto s = precisionCase("2026-10-29T10:00:00.1234564Z", "2026-10-29T10:00:00.123457Z");
		assertTrue(s.inCandidateSet());
		assertTrue(s.candidateRule().contains("rule B"), s.candidateRule());
	}

	/** Row 22 (d): the helper itself, as the timestamptz cast reads the five values. */
	@Test
	void atDatabasePrecisionMatchesTheCast() {
		assertEquals(Instant.parse("2026-10-29T10:00:00.123457Z"),
				SyntheticSbomService.atDatabasePrecision(Instant.parse("2026-10-29T10:00:00.123456789Z")));
		assertEquals(Instant.parse("2026-10-29T10:00:00.123456Z"),
				SyntheticSbomService.atDatabasePrecision(Instant.parse("2026-10-29T10:00:00.1234564Z")));
		assertEquals(Instant.parse("2026-10-29T10:00:01.000000Z"),
				SyntheticSbomService.atDatabasePrecision(Instant.parse("2026-10-29T10:00:00.9999996Z")));
		assertEquals(Instant.parse("2026-10-29T10:00:00.123Z"),
				SyntheticSbomService.atDatabasePrecision(Instant.parse("2026-10-29T10:00:00.123Z")));
		assertEquals(Instant.parse("2026-10-29T10:00:00.123457Z"),
				SyntheticSbomService.atDatabasePrecision(Instant.parse("2026-10-29T10:00:00.123457Z")));
	}

	/** Row 23 fixture: a@1 covered by bucket 0 INGESTED, b@1 uncovered as given. */
	private ArtifactScanStatusDto gatingCase(SbomComponent b, SyntheticDtrackBucket... extra) {
		List<SyntheticDtrackBucket> bs = new ArrayList<>();
		bs.add(bucket(0, IngestState.INGESTED, columnNow().minusMinutes(10), "pkg:npm/a@1"));
		bs.addAll(List.of(extra));
		buckets(bs.toArray(new SyntheticDtrackBucket[0]));
		components(comp("pkg:npm/a@1"), b);
		ownRow(null, null);
		ArtifactScanStatusDto s = service.explainArtifactScanStatus(unscannedBom());
		verify(rebomService, times(1)).isEnrichmentConfigured(ORG);
		assertEquals(ArtifactScanState.BLOCKED_ON_COVERAGE, s.state());
		assertEquals(1, s.blockers().size());
		return s;
	}

	private SbomComponent unenrichedIn(Integer bucketIndex) {
		SbomComponent sc = comp("pkg:npm/b@1");
		sc.setSyntheticBucketIndex(bucketIndex);
		return sc;
	}

	/** Row 23 (a): non-BEAR org, an un-enriched component in a SUBMITTED bucket: the bucket decides. */
	@Test
	void gatingNonBearBucketedComponent() {
		ZonedDateTime t7 = columnNow().minusMinutes(1);
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(false);
		ArtifactScanStatusDto s = gatingCase(unenrichedIn(7), bucket(7, IngestState.SUBMITTED, t7));
		ScanBlockerDto b = s.blockers().get(0);
		assertEquals(ScanBlockerEnrichmentState.UNENRICHED, b.enrichment());
		assertEquals(Integer.valueOf(7), b.bucketIndex());
		assertEquals("SUBMITTED", b.bucketIngestState());
		assertEquals(t7, b.bucketLastUpdatedDate());
		assertTrue(b.reason().startsWith("bucket 7 is SUBMITTED since "), b.reason());
		assertEquals(Boolean.FALSE, s.enrichmentGated());
	}

	/** Row 23 (b): non-BEAR org, un-enriched and unbucketed. */
	@Test
	void gatingNonBearUnbucketedComponent() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(false);
		ArtifactScanStatusDto s = gatingCase(unenrichedIn(null));
		assertEquals("not assigned a synthetic bucket yet; this organization has no BEAR enrichment, so submitOrg "
				+ "ships it un-enriched on a coming pass", s.blockers().get(0).reason());
		assertEquals(Boolean.FALSE, s.enrichmentGated());
	}

	/** Row 23 (c): BEAR org, the rows of (a): the same bucket text. */
	@Test
	void gatingBearBucketedComponent() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(true);
		ArtifactScanStatusDto s = gatingCase(unenrichedIn(7),
				bucket(7, IngestState.SUBMITTED, columnNow().minusMinutes(1)));
		assertTrue(s.blockers().get(0).reason().startsWith("bucket 7 is SUBMITTED since "),
				s.blockers().get(0).reason());
		assertEquals(Boolean.TRUE, s.enrichmentGated());
	}

	/** Row 23 (d): rebom does not answer: the status still answers, unknown, one WARN line. */
	@Test
	void gatingUnknownWhenRebomThrows() {
		when(rebomService.isEnrichmentConfigured(ORG)).thenThrow(
				new IllegalStateException("rebom gave no answer to isEnrichmentConfigured for org " + ORG));
		List<ArtifactScanStatusDto> answer = new ArrayList<>();
		List<String> warns = warnLinesOf(() -> answer.add(gatingCase(unenrichedIn(null))));
		ArtifactScanStatusDto s = answer.get(0);
		assertNull(s.enrichmentGated());
		assertEquals("not assigned a synthetic bucket yet; enrichment state unknown (rebom did not answer whether "
				+ "BEAR enrichment is configured)", s.blockers().get(0).reason());
		assertEquals(1, warns.size(), warns.toString());
		assertTrue(warns.get(0).contains(ORG.toString()), warns.get(0));
	}

	/** Row 23 (e): without a blocker rebom is never asked (the fixtures of rows 1, 2, 5, 6 and 9). */
	@Test
	void gatingNeverAskedWithoutABlocker() {
		when(artifactCanonicalMapRepository.findByArtifactUuid(ARTIFACT)).thenReturn(Optional.empty());
		assertNull(service.explainArtifactScanStatus(unscannedBom()).enrichmentGated());
		assertNull(service.explainArtifactScanStatus(artifact(ArtifactType.VEX, null, null)).enrichmentGated());
		verifyNoInteractions(rebomService);
	}

	/** Row 23 (e), continued: rows 5, 6 and 9. */
	@Test
	void gatingNeverAskedForClearScannedOrNoMatchable() {
		terminalComponentIsNotABlocker();
		scannedAndNotACandidate();
		noMatchableComponents();
		verifyNoInteractions(rebomService);
	}
}
