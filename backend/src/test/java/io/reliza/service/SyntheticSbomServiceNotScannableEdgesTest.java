/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.model.Artifact;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactData.DependencyTrackIntegration;
import io.reliza.model.ArtifactNotScannable;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.NoMatchableComponentsReason;
import io.reliza.model.SbomComponent;
import io.reliza.model.SbomComponentFlowControl;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.model.dto.ReleaseMetricsDto;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.FanOutWalkRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SyntheticDtrackBucketRepository;
import io.reliza.service.SyntheticSbomService.GateView;

/**
 * TPF-126 mutation round: what SyntheticSbomServiceNotScannableTest leaves open on the same Mockito rig.
 * The stall summary with every class present at once and unequal; a root row that is also terminal;
 * a stamp that fails on a not-scannable canonical (the drain is not recorded); a marker write and a
 * marker clear that fail without stopping the pass.
 */
@ExtendWith(MockitoExtension.class)
class SyntheticSbomServiceNotScannableEdgesTest {

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

	/** The stubbed database clock of every fan-out pass (TPF-114), also the marker's at. */
	private static final ZonedDateTime P0 = ZonedDateTime.of(2026, 10, 29, 0, 0, 0, 0, ZoneOffset.UTC);

	/** The INGESTED bucket's date: the pass cutoff every stamp carries. */
	private static final ZonedDateTime BUCKET_DATE = ZonedDateTime.of(2026, 10, 28, 12, 0, 0, 0, ZoneOffset.UTC);

	private final UUID ORG = UUID.randomUUID();

	@BeforeEach
	void stubClock() {
		lenient().doAnswer(inv -> {
			ReleaseMetricsDto m = inv.getArgument(3);
			m.computeMetricsFromFacts();
			return null;
		}).when(vulnAnalysisService).processReleaseMetricsDto(any(), any(), any(), any());
		lenient().when(artifactCanonicalMapRepository.dbClockTimestamp()).thenReturn(P0);
		// TPF-136: a warm org whose drain was never recorded: the walk runs from a null watermark as in TPF-114.
		lenient().when(artifactCanonicalMapRepository.countFanOutStamped(ORG)).thenReturn(1L);
	}

	private static String iso(ZonedDateTime t) {
		return DateTimeFormatter.ISO_INSTANT.format(t.toInstant());
	}

	private SbomComponent row(String canonicalPurl) {
		SbomComponent sc = new SbomComponent();
		sc.setUuid(UUID.randomUUID());
		sc.setOrg(ORG);
		sc.setCanonicalPurl(canonicalPurl);
		sc.setRecordData(new HashMap<>());
		return sc;
	}

	private SbomComponent rootRow(String canonicalPurl) {
		SbomComponent sc = row(canonicalPurl);
		sc.getRecordData().put("isRoot", true);
		return sc;
	}

	private SbomComponent terminalRow(String canonicalPurl) {
		SbomComponent sc = row(canonicalPurl);
		sc.setFlowControl(new SbomComponentFlowControl("2026-10-01T00:00:00Z", "OWN_BOM_UNPULLABLE"));
		return sc;
	}

	/** An INGESTED bucket covering the given purls, dated BUCKET_DATE. */
	private SyntheticDtrackBucket ingestedBucket(String... coveredPurls) {
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setIngestState(IngestState.INGESTED);
		b.setBucketIndex(0);
		b.setFindings(new HashMap<>());
		b.setLastUpdatedDate(BUCKET_DATE);
		for (String p : coveredPurls) b.getRefMap().put(p, p);
		return b;
	}

	/**
	 * One never-fanned-out canonical holding the given rows, with one map row for {@code artifact}
	 * (null: no map row stub) and an existing artifact entity behind it when {@code withArtifact}.
	 */
	private UUID candidate(List<SbomComponent> rows, UUID artifact, boolean withArtifact) {
		UUID canonical = UUID.randomUUID();
		when(artifactCanonicalMapRepository.findNeverFannedOutCanonicals(ORG, 2000)).thenReturn(List.of(canonical));
		List<ArtifactSbomComponent> ascs = new ArrayList<>();
		for (SbomComponent sc : rows) {
			ArtifactSbomComponent asc = mock(ArtifactSbomComponent.class);
			when(asc.getSbomComponentUuid()).thenReturn(sc.getUuid());
			ascs.add(asc);
		}
		when(artifactSbomComponentRepository.findByOrgAndCanonicalArtifactUuid(ORG, canonical)).thenReturn(ascs);
		when(sbomComponentRepository.findAllById(any())).thenReturn(rows);
		if (artifact != null) {
			ArtifactCanonicalMap acm = mock(ArtifactCanonicalMap.class);
			when(acm.getArtifactUuid()).thenReturn(artifact);
			when(artifactCanonicalMapRepository.findByOrgAndCanonicalArtifactUuid(ORG, canonical))
					.thenReturn(List.of(acm));
			if (withArtifact) {
				when(sharedArtifactService.getArtifact(artifact)).thenReturn(Optional.of(mock(Artifact.class)));
			}
		}
		return canonical;
	}

	/** The marker the pass handed to markArtifactNotScannable, asserting it was marked exactly once. */
	private ArtifactNotScannable markedOnce() {
		ArgumentCaptor<ArtifactNotScannable> captor = ArgumentCaptor.forClass(ArtifactNotScannable.class);
		verify(sharedArtifactService, times(1)).markArtifactNotScannable(any(), captor.capture());
		return captor.getValue();
	}

	private void ingestedAndNotScannable() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket("pkg:npm/unrelated@1")));
	}

	// ---- the stall summary, every class at once ----

	@Test
	void stallSummaryNamesEachClassItsOwnCount() {
		String covered = "pkg:npm/x@1";
		String uncoveredUnenriched = "pkg:npm/u@1";
		String uncoveredEnriched = "pkg:npm/e@1";
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket(covered)));
		SbomComponent clear1 = row(covered);
		SbomComponent clear2 = row(covered);
		SbomComponent purlLess = row(null);
		SbomComponent unenriched = row(uncoveredUnenriched);
		SbomComponent enriched = row(uncoveredEnriched);
		enriched.setEnrichedAt(P0);
		List<SbomComponent> rows = List.of(clear1, clear2, purlLess, unenriched, enriched);
		List<UUID> canonicals = new ArrayList<>();
		for (SbomComponent sc : rows) canonicals.add(UUID.randomUUID());
		when(artifactCanonicalMapRepository.findNeverScannedMappedArtifactCanonicalsOlderThan(eq(ORG), any(), eq(10)))
				.thenReturn(canonicals);
		for (int i = 0; i < rows.size(); i++) {
			ArtifactSbomComponent asc = mock(ArtifactSbomComponent.class);
			when(asc.getSbomComponentUuid()).thenReturn(rows.get(i).getUuid());
			when(artifactSbomComponentRepository.findByOrgAndCanonicalArtifactUuid(ORG, canonicals.get(i)))
					.thenReturn(List.of(asc));
			when(sbomComponentRepository.findAllById(Set.of(rows.get(i).getUuid()))).thenReturn(List.of(rows.get(i)));
		}

		assertEquals("of 5 sampled: 1 blocked on un-enriched components, 1 blocked on other uncovered components, "
				+ "2 coverage-clear (starvation suspects), 1 not scannable",
				service.summarizeNeverScannedBlockers(ORG, 10));
	}

	// ---- the classification of a row that is both root and terminal ----

	@Test
	void aRootRowThatIsAlsoTerminalCountsAsRootOnly() {
		SbomComponent both = rootRow("pkg:generic/app@1.0");
		both.setFlowControl(new SbomComponentFlowControl("2026-10-01T00:00:00Z", "OWN_BOM_UNPULLABLE"));

		GateView gate = SyntheticSbomService.classifyForGate(List.of(both, row(null)));

		assertEquals(2, gate.components());
		assertEquals(1, gate.root(), "the gate tests root first");
		assertEquals(0, gate.terminal());
		assertEquals(1, gate.unmatchable());
		assertEquals(NoMatchableComponentsReason.NO_PURL_OR_CPE, gate.reason());
	}

	// ---- a stamp that fails on a not-scannable canonical ----

	@Test
	void aFailedStampOnANotScannableCanonicalLeavesTheDrainUnrecorded() {
		ingestedAndNotScannable();
		UUID artifact = UUID.randomUUID();
		candidate(List.of(rootRow("pkg:generic/app@1.0")), artifact, true);
		when(artifactCanonicalMapRepository.stampFanOutPass(any(), any(), any(), any()))
				.thenThrow(new RuntimeException("stamp failed"));

		service.fanOutOrg(ORG);

		verify(sharedArtifactService, times(1)).markArtifactNotScannable(any(), any());
		verify(systemInfoService, never()).stampFanOutDrainedAt(any(), any());
	}

	@Test
	void aStampThatLandsOnANotScannableCanonicalRecordsTheDrain() {
		ingestedAndNotScannable();
		UUID artifact = UUID.randomUUID();
		candidate(List.of(rootRow("pkg:generic/app@1.0")), artifact, true);

		service.fanOutOrg(ORG);

		verify(systemInfoService, times(1)).stampFanOutDrainedAt(ORG, BUCKET_DATE);
	}

	// ---- failures of the two marker writes never stop the pass ----

	@Test
	void aMarkThatFailsDoesNotStopTheStampOfTheCanonical() {
		ingestedAndNotScannable();
		UUID artifact = UUID.randomUUID();
		UUID canonical = candidate(List.of(rootRow("pkg:generic/app@1.0")), artifact, true);
		doThrow(new RuntimeException("artifact table down")).when(sharedArtifactService)
				.markArtifactNotScannable(any(), any());

		service.fanOutOrg(ORG);

		verify(artifactCanonicalMapRepository, times(1)).stampFanOutPass(ORG, canonical, iso(BUCKET_DATE), P0);
	}

	@Test
	void aClearThatFailsDoesNotStopTheStampOfTheCanonical() {
		String aPurl = "pkg:npm/a@1";
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket(aPurl)));
		UUID artifact = UUID.randomUUID();
		UUID canonical = candidate(List.of(row(aPurl), row("pkg:npm/b@1")), artifact, false);
		doThrow(new RuntimeException("artifact table down")).when(sharedArtifactService).clearArtifactNotScannable(any());

		service.fanOutOrg(ORG);

		verify(sharedArtifactService, times(1)).clearArtifactNotScannable(artifact);
		verify(artifactCanonicalMapRepository, times(1)).stampFanOutPass(ORG, canonical, iso(BUCKET_DATE), P0);
	}
}
