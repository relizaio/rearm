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
 * TPF-125 / TPF-126 architecture 6.1: a canonical with no matchable component is marked not scannable
 * (metrics.notScannable with its reason and the gate's counts) instead of stamped scanned, its map rows
 * still stamped fanOutAt; the gate skip clears the marker; classifyForGate follows the TPF-121 4.6
 * precedence and agrees with isGateMatchable; the stall sampler counts not-scannable canonicals apart.
 */
@ExtendWith(MockitoExtension.class)
class SyntheticSbomServiceNotScannableTest {

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

	private void assertMarker(ArtifactNotScannable m, NoMatchableComponentsReason reason, int components, int root,
			int terminal, int unmatchable) {
		assertEquals(reason, m.reason());
		assertEquals(components, m.components());
		assertEquals(root, m.root());
		assertEquals(terminal, m.terminal());
		assertEquals(unmatchable, m.unmatchable());
		assertEquals(P0, m.at(), "the marker's at is the pass's database-clock instant");
	}

	private void assertNotScannedButStamped(UUID canonical) {
		verify(sharedArtifactService, never()).updateArtifactDti(any(), any(), any());
		verify(sharedArtifactService, never()).clearArtifactNotScannable(any());
		verify(artifactCanonicalMapRepository, times(1)).stampFanOutPass(ORG, canonical, iso(BUCKET_DATE), P0);
	}

	// ---- 6.1 rows 1-4: each reason lands on its fixture, never a scan stamp ----

	@Test
	void fanOutMarksNoPurlOrCpeCanonicalNotScannable() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket("pkg:npm/unrelated@1")));
		UUID artifact = UUID.randomUUID();
		UUID canonical = candidate(List.of(row(null), row("generic:thing")), artifact, true);

		service.fanOutOrg(ORG);

		assertMarker(markedOnce(), NoMatchableComponentsReason.NO_PURL_OR_CPE, 2, 0, 0, 2);
		assertNotScannedButStamped(canonical);
	}

	@Test
	void fanOutMarksOnlyRootCanonicalNotScannable() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket("pkg:npm/unrelated@1")));
		UUID artifact = UUID.randomUUID();
		UUID canonical = candidate(List.of(rootRow("pkg:generic/app@1.0")), artifact, true);

		service.fanOutOrg(ORG);

		assertMarker(markedOnce(), NoMatchableComponentsReason.ONLY_ROOT, 1, 1, 0, 0);
		assertNotScannedButStamped(canonical);
	}

	@Test
	void fanOutMarksZeroRowCanonicalNoComponents() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket("pkg:npm/unrelated@1")));
		UUID artifact = UUID.randomUUID();
		UUID canonical = candidate(List.of(), artifact, true);

		service.fanOutOrg(ORG);

		assertMarker(markedOnce(), NoMatchableComponentsReason.NO_COMPONENTS, 0, 0, 0, 0);
		assertNotScannedButStamped(canonical);
	}

	@Test
	void fanOutMarksAllTerminalCanonicalNotScannable() {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket("pkg:npm/unrelated@1")));
		UUID artifact = UUID.randomUUID();
		UUID canonical = candidate(List.of(rootRow("pkg:generic/app@1.0"), terminalRow("pkg:npm/t1@1"),
				terminalRow("pkg:npm/t2@1")), artifact, true);

		service.fanOutOrg(ORG);

		assertMarker(markedOnce(), NoMatchableComponentsReason.ALL_TERMINAL, 3, 1, 2, 0);
		assertNotScannedButStamped(canonical);
	}

	// ---- 6.1 row 5: the precedence on every shape and on mixed sets ----

	@Test
	void classifyForGateFollowsThePrecedence() {
		GateView a = SyntheticSbomService.classifyForGate(List.of());
		assertFalse(a.scannable());
		assertEquals(NoMatchableComponentsReason.NO_COMPONENTS, a.reason());

		GateView b = SyntheticSbomService.classifyForGate(List.of(rootRow("pkg:generic/app@1")));
		assertFalse(b.scannable());
		assertEquals(NoMatchableComponentsReason.ONLY_ROOT, b.reason());

		GateView c = SyntheticSbomService.classifyForGate(List.of(rootRow("pkg:generic/app@1"),
				terminalRow("pkg:npm/t1@1"), terminalRow("cpe:2.3:a:x:y:1")));
		assertFalse(c.scannable());
		assertEquals(NoMatchableComponentsReason.ALL_TERMINAL, c.reason());

		GateView d = SyntheticSbomService.classifyForGate(List.of(row(null), row("generic:x")));
		assertFalse(d.scannable());
		assertEquals(NoMatchableComponentsReason.NO_PURL_OR_CPE, d.reason());
		assertEquals(2, d.unmatchable());

		GateView e = SyntheticSbomService.classifyForGate(List.of(terminalRow("pkg:npm/t1@1"),
				terminalRow("pkg:npm/t2@1"), terminalRow("pkg:npm/t3@1"), row(null)));
		assertFalse(e.scannable());
		assertEquals(NoMatchableComponentsReason.NO_PURL_OR_CPE, e.reason());
		assertEquals(3, e.terminal());
		assertEquals(1, e.unmatchable());

		GateView f = SyntheticSbomService.classifyForGate(List.of(terminalRow("pkg:npm/t1@1"), row(null),
				row("generic:y"), row("swid:z")));
		assertFalse(f.scannable());
		assertEquals(NoMatchableComponentsReason.NO_PURL_OR_CPE, f.reason());
		assertEquals(1, f.terminal());
		assertEquals(3, f.unmatchable());

		GateView g = SyntheticSbomService.classifyForGate(List.of(rootRow("pkg:generic/app@1"),
				row("pkg:npm/covered@1")));
		assertTrue(g.scannable());
		assertEquals(Set.of("pkg:npm/covered@1"), g.matchablePurls());
		assertEquals(1, g.root());
	}

	// ---- 6.1 row 6: a covered canonical is scanned exactly as today ----

	@Test
	void fanOutStillMarksCoveredCanonicalScanned() {
		String depPurl = "pkg:npm/dep@1.0";
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket(depPurl)));
		UUID artifact = UUID.randomUUID();
		UUID canonical = candidate(List.of(row(depPurl), rootRow("pkg:generic/app@1.0")), artifact, true);

		service.fanOutOrg(ORG);

		ArgumentCaptor<DependencyTrackIntegration> dti = ArgumentCaptor.forClass(DependencyTrackIntegration.class);
		verify(sharedArtifactService, times(1)).updateArtifactDti(any(), dti.capture(), any());
		assertNotNull(dti.getValue().getLastScanned(), "a covered canonical is stamped scanned");
		verify(sharedArtifactService, never()).markArtifactNotScannable(any(), any());
		verify(sharedArtifactService, never()).clearArtifactNotScannable(any());
		verify(artifactCanonicalMapRepository, times(1)).stampFanOutPass(ORG, canonical, iso(BUCKET_DATE), P0);
	}

	// ---- 6.1 row 7: the gate skip clears the marker, then stamps ----

	@Test
	void fanOutGateSkipClearsTheMarkerBeforeStamping() {
		String aPurl = "pkg:npm/a@1";
		String bPurl = "pkg:npm/b@1";
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket(aPurl)));
		UUID artifact = UUID.randomUUID();
		UUID canonical = candidate(List.of(row(aPurl), row(bPurl)), artifact, false);

		service.fanOutOrg(ORG);

		InOrder order = inOrder(sharedArtifactService, artifactCanonicalMapRepository);
		order.verify(sharedArtifactService, times(1)).clearArtifactNotScannable(artifact);
		order.verify(artifactCanonicalMapRepository, times(1)).stampFanOutPass(ORG, canonical, iso(BUCKET_DATE), P0);
		verify(sharedArtifactService, never()).updateArtifactDti(any(), any(), any());
		verify(sharedArtifactService, never()).markArtifactNotScannable(any(), any());
	}

	// ---- 6.1 row 8: the parse lands, the requeued canonical is scanned on the next pass ----

	@Test
	void notScannableCanonicalBecomesScannableWhenItsParseLands() {
		String latePurl = "pkg:npm/late@1";
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket(latePurl)));
		UUID artifact = UUID.randomUUID();
		UUID canonical = candidate(List.of(), artifact, true);

		service.fanOutOrg(ORG);

		assertMarker(markedOnce(), NoMatchableComponentsReason.NO_COMPONENTS, 0, 0, 0, 0);
		verify(artifactCanonicalMapRepository, times(1)).stampFanOutPass(ORG, canonical, iso(BUCKET_DATE), P0);

		clearInvocations(sharedArtifactService, artifactCanonicalMapRepository);
		SbomComponent late = row(latePurl);
		ArtifactSbomComponent asc = mock(ArtifactSbomComponent.class);
		when(asc.getSbomComponentUuid()).thenReturn(late.getUuid());
		when(artifactSbomComponentRepository.findByOrgAndCanonicalArtifactUuid(ORG, canonical)).thenReturn(List.of(asc));
		when(sbomComponentRepository.findAllById(any())).thenReturn(List.of(late));

		service.fanOutOrg(ORG);

		verify(sharedArtifactService, times(1)).updateArtifactDti(any(), any(), any());
		verify(artifactCanonicalMapRepository, times(1)).stampFanOutPass(ORG, canonical, iso(BUCKET_DATE), P0);
		verify(sharedArtifactService, never()).markArtifactNotScannable(any(), any());
	}

	// ---- 6.1 row 9: classifyForGate and isGateMatchable never disagree ----

	@Test
	void classifyForGateMatchesIsGateMatchable() {
		String[] purls = { null, "generic:a", "pkg:npm/b@1", "cpe:2.3:a:v:p:1", "swid:x", "pkg:maven/c/d@2" };
		List<SbomComponent> rows = new ArrayList<>();
		int i = 0;
		for (int kind = 0; kind < 4; kind++) {
			for (String p : purls) {
				String purl = p == null ? null : p + "-" + (i++);
				SbomComponent sc = switch (kind) {
					case 0 -> row(purl);
					case 1 -> rootRow(purl);
					case 2 -> terminalRow(purl);
					default -> row(purl);
				};
				rows.add(sc);
			}
		}
		assertEquals(24, rows.size());
		assertTrue(rows.stream().anyMatch(r -> r.isRoot() && r.getCanonicalPurl() != null
				&& r.getCanonicalPurl().startsWith("pkg:")), "a root with a pkg: purl is among the rows");
		assertTrue(rows.stream().anyMatch(r -> r.isEnrichmentTerminal() && r.getCanonicalPurl() != null
				&& r.getCanonicalPurl().startsWith("cpe:")), "a terminal row with a cpe: purl is among the rows");

		GateView gate = SyntheticSbomService.classifyForGate(rows);

		Set<String> expected = new HashSet<>();
		for (SbomComponent sc : rows) if (SyntheticSbomService.isGateMatchable(sc)) expected.add(sc.getCanonicalPurl());
		assertEquals(expected, gate.matchablePurls());
		assertEquals(gate.components(), gate.root() + gate.terminal() + gate.unmatchable() + gate.matchablePurls().size());
		assertEquals(24, gate.components());
		assertEquals(6, gate.root());
		assertEquals(6, gate.terminal());
	}

	// ---- 6.1 row 10: the stall sampler's fourth bucket ----

	@Test
	void stallSummaryCountsNotScannableSeparately() {
		String covered = "pkg:npm/x@1";
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(ingestedBucket(covered)));
		UUID c1 = UUID.randomUUID();
		UUID c2 = UUID.randomUUID();
		when(artifactCanonicalMapRepository.findNeverScannedMappedArtifactCanonicalsOlderThan(eq(ORG), any(), eq(5)))
				.thenReturn(List.of(c1, c2));
		SbomComponent clear = row(covered);
		SbomComponent purlLess = row(null);
		Map<UUID, SbomComponent> byCanonical = Map.of(c1, clear, c2, purlLess);
		for (Map.Entry<UUID, SbomComponent> e : byCanonical.entrySet()) {
			ArtifactSbomComponent asc = mock(ArtifactSbomComponent.class);
			when(asc.getSbomComponentUuid()).thenReturn(e.getValue().getUuid());
			when(artifactSbomComponentRepository.findByOrgAndCanonicalArtifactUuid(ORG, e.getKey()))
					.thenReturn(List.of(asc));
			when(sbomComponentRepository.findAllById(Set.of(e.getValue().getUuid())))
					.thenReturn(List.of(e.getValue()));
		}

		assertEquals("of 2 sampled: 0 blocked on un-enriched components, 0 blocked on other uncovered components, "
				+ "1 coverage-clear (starvation suspects), 1 not scannable",
				service.summarizeNeverScannedBlockers(ORG, 5));
	}
}
