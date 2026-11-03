/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.FanOutWalkTestSupport.iso;
import static io.reliza.service.FanOutWalkTestSupport.minutesAgo;
import static io.reliza.service.FanOutWalkTestSupport.purlsResolveTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import io.reliza.model.BucketFlowControl;
import io.reliza.model.SbomComponent;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilityDto;
import io.reliza.model.dto.ReleaseMetricsDto.VulnerabilitySeverity;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.FanOutWalkRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SyntheticDtrackBucketRepository;
import io.reliza.service.DTrackService.SyntheticFindings;
import io.reliza.service.IntegrationService.DtrackProjectDeletionOutcome;
import io.reliza.service.IntegrationService.VulnWithCpe;

/**
 * TPF-137 architecture 6.1 case 4: the fan-out bookkeeping each of the seven bucket saves records
 * (D2, D3, D4, D5, D25): the ingest records the whole membership united with the findings diff, the
 * submit only moves recordedAt, the resync and the retirement record their diff, the re-key its
 * re-keyed purls, and on a bucket still unrecorded and dated after the watermark the save records
 * the membership as loaded too (the fold). The SyntheticSbomServiceTest rig with DTrack stubbed;
 * the purl lookup maps p1, p2, p3 to u1, u2, u3 (terminal purls are left out by the stub, as the
 * query leaves them out); every save is snapshotted at the moment it is made.
 */
@ExtendWith(MockitoExtension.class)
class SyntheticSbomServiceIngestDiffTest {

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

	private static final String P1 = "pkg:npm/p1@1";
	private static final String P2 = "pkg:npm/p2@1";
	private static final String P3 = "pkg:npm/p3@1";
	private static final String GONE = "pkg:npm/gone@1";

	private final UUID ORG = UUID.randomUUID();
	private final UUID PROJ = UUID.randomUUID();
	private final UUID u1 = UUID.randomUUID();
	private final UUID u2 = UUID.randomUUID();
	private final UUID u3 = UUID.randomUUID();
	private final UUID u9 = UUID.randomUUID();
	private final ZonedDateTime T0 = minutesAgo(30);
	private final ZonedDateTime T1 = minutesAgo(20);
	private final ZonedDateTime T2 = minutesAgo(10);

	/** What one save wrote: the bucket's state, date and bookkeeping at that moment. */
	private record Saved(IngestState state, ZonedDateTime date, BucketFlowControl fc) {}

	private final List<Saved> saves = new ArrayList<>();

	@BeforeEach
	void rig() throws Exception {
		lenient().when(sbomComponentRepository.findUuidsByOrgAndCanonicalPurlIn(eq(ORG), any()))
				.thenAnswer(purlsResolveTo(Map.of(P1, u1, P2, u2, P3, u3)));
		lenient().when(bucketRepository.save(any())).thenAnswer(inv -> {
			SyntheticDtrackBucket b = inv.getArgument(0);
			saves.add(new Saved(b.getIngestState(), b.getLastUpdatedDate(), b.getFlowControl()));
			return b;
		});
		lenient().when(dTrackService.syntheticIsTokenProcessing(ORG, "tok")).thenReturn(false);
	}

	// ---------------- fixtures ----------------

	private SyntheticDtrackBucket bucket(IngestState state, ZonedDateTime date, String... members) {
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setOrg(ORG);
		b.setBucketIndex(0);
		b.setDtrackProjectUuid(PROJ);
		b.setIngestState(state);
		b.setLastUpdatedDate(date);
		b.setContentHash("old");
		for (String p : members) b.getRefMap().put(p, p);
		b.getRefMap().put("__token", "tok");
		b.setFindings(new HashMap<>());
		return b;
	}

	/** Recorded at its own date, with the given entries. */
	private static SyntheticDtrackBucket recorded(SyntheticDtrackBucket b, Map<UUID, ZonedDateTime> entries) {
		Map<String, String> pending = new LinkedHashMap<>();
		entries.forEach((u, at) -> pending.put(u.toString(), iso(at)));
		b.setFlowControl(new BucketFlowControl(pending, iso(b.getLastUpdatedDate())));
		return b;
	}

	private static VulnerabilityDto vuln(String purl, String vulnId, VulnerabilitySeverity severity) {
		return new VulnerabilityDto(purl, vulnId, severity, null, null, null, null, null, null,
				null, null, null, null, null, null);
	}

	private static SyntheticFindings findings(VulnerabilityDto... vulns) {
		List<VulnWithCpe> out = new ArrayList<>();
		for (VulnerabilityDto v : vulns) out.add(new VulnWithCpe(v, null));
		return new SyntheticFindings(out, List.of());
	}

	private Saved lastSave() {
		return saves.get(saves.size() - 1);
	}

	/** The pending map of a save as uuid to instant text. */
	private static Map<String, String> pendingOf(Saved s) {
		return s.fc() == null || s.fc().fanOutChanged() == null ? Map.of() : s.fc().fanOutChanged();
	}

	private static Map<String, String> entriesAt(ZonedDateTime at, UUID... uuids) {
		Map<String, String> m = new HashMap<>();
		for (UUID u : uuids) m.put(u.toString(), iso(at));
		return m;
	}

	/** recordedAt equals the saved date, both microsecond-truncated, and the date moved past {@code after}. */
	private static void assertRecordedAtTheSave(Saved s, ZonedDateTime after) {
		assertEquals(s.date(), s.date().truncatedTo(ChronoUnit.MICROS), "the bumped date is micro-truncated");
		assertEquals(iso(s.date()), s.fc().recordedAt(), "recordedAt is the saved date");
		assertTrue(s.date().isAfter(after), "the save bumped the date");
	}

	private void ingest(SyntheticDtrackBucket b, SyntheticFindings f) throws Exception {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(b));
		when(dTrackService.syntheticFetchFindings(ORG, PROJ)).thenReturn(f);
		service.ingestOrgBuckets(ORG);
	}

	private void resync(SyntheticDtrackBucket b, SyntheticFindings f) throws Exception {
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(b));
		when(integrationService.retrieveUnsyncedDtrackProjects(eq(ORG), any())).thenReturn(Set.of(PROJ));
		when(dTrackService.syntheticFetchFindings(ORG, PROJ)).thenReturn(f);
		service.resyncFindingsForOrg(ORG, ZonedDateTime.now().minusDays(1));
	}

	// ---------------- the ingest (D4) ----------------

	@Test
	void aFirstIngestRecordsEveryMemberAtTheNewDate() throws Exception {
		SyntheticDtrackBucket b = bucket(IngestState.SUBMITTED, T1, P1, P2);

		ingest(b, findings(vuln(P1, "CVE-1", VulnerabilitySeverity.HIGH)));

		Saved s = lastSave();
		assertEquals(IngestState.INGESTED, s.state());
		assertRecordedAtTheSave(s, T1);
		assertEquals(entriesAt(s.date(), u1, u2), pendingOf(s), "exactly the members, at the saved date");
	}

	@Test
	void aSecondIngestWithIdenticalFindingsStillRecordsTheWholeMembership() throws Exception {
		SyntheticDtrackBucket b = bucket(IngestState.SUBMITTED, T1, P1, P2);
		SyntheticFindings f = findings(vuln(P1, "CVE-1", VulnerabilitySeverity.HIGH));
		ingest(b, f);
		Saved first = lastSave();
		b.setIngestState(IngestState.SUBMITTED);

		ingest(b, f);

		Saved second = lastSave();
		assertRecordedAtTheSave(second, T1);
		assertFalse(second.date().isBefore(first.date()), "recordedAt moved");
		assertEquals(entriesAt(second.date(), u1, u2), pendingOf(second));
	}

	@Test
	void aSecondIngestWithOneChangedPurlRecordsTheUnionOnce() throws Exception {
		SyntheticDtrackBucket b = bucket(IngestState.SUBMITTED, T1, P1, P2);
		ingest(b, findings(vuln(P1, "CVE-1", VulnerabilitySeverity.HIGH)));
		b.setIngestState(IngestState.SUBMITTED);

		ingest(b, findings(vuln(P1, "CVE-1", VulnerabilitySeverity.CRITICAL)));

		Saved s = lastSave();
		assertEquals(entriesAt(s.date(), u1, u2), pendingOf(s), "the members, the changed purl among them once");
	}

	@Test
	void aMemberThatLeftIsRecordedBesideTheMembers() throws Exception {
		SyntheticDtrackBucket b = bucket(IngestState.SUBMITTED, T1, P1, P2, P3);
		ingest(b, findings(vuln(P3, "CVE-3", VulnerabilitySeverity.LOW)));
		b.getRefMap().remove(P3);
		b.setIngestState(IngestState.SUBMITTED);

		ingest(b, findings());

		Saved s = lastSave();
		assertEquals(entriesAt(s.date(), u1, u2, u3), pendingOf(s), "the departed purl's canonicals must drop its findings");
	}

	// ---------------- the submit ----------------

	private SbomComponent member(String purl) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(ORG);
		sc.setCanonicalPurl(purl);
		sc.setSyntheticBucketIndex(0);
		Map<String, Object> rd = new HashMap<>();
		rd.put("name", purl);
		sc.setRecordData(rd);
		return sc;
	}

	private void submit(SyntheticDtrackBucket b, boolean uploadFails, String... newMembers) throws Exception {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(false);
		List<SbomComponent> members = new ArrayList<>();
		for (String p : newMembers) members.add(member(p));
		when(sbomComponentRepository.findMatchableByOrgOrdered(ORG.toString())).thenReturn(members);
		when(bucketRepository.findByOrgAndBucketIndex(ORG, 0)).thenReturn(Optional.of(b));
		lenient().when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(b));
		if (uploadFails) {
			when(dTrackService.syntheticUploadBom(eq(ORG), eq(PROJ), any(), any(), any()))
					.thenThrow(new RuntimeException("upload failed"));
		} else {
			when(dTrackService.syntheticUploadBom(eq(ORG), eq(PROJ), any(), any(), any())).thenReturn("tok");
		}
		service.submitOrg(ORG);
	}

	@Test
	void aSubmitOfARecordedBucketMovesRecordedAtOnly() throws Exception {
		SyntheticDtrackBucket b = recorded(bucket(IngestState.INGESTED, T1, P1, P2), Map.of(u9, T0));

		submit(b, false, P1, P2, P3);

		Saved s = lastSave();
		assertEquals(IngestState.SUBMITTED, s.state());
		assertRecordedAtTheSave(s, T1);
		assertEquals(Map.of(u9.toString(), iso(T0)), pendingOf(s), "an earlier ingest's entries are kept");
		verify(sbomComponentRepository, never()).findUuidsByOrgAndCanonicalPurlIn(any(), any());
	}

	@Test
	void aFailedSubmitOfARecordedBucketMovesRecordedAtOnly() throws Exception {
		SyntheticDtrackBucket b = recorded(bucket(IngestState.INGESTED, T1, P1, P2), Map.of(u9, T0));

		submit(b, true, P1, P2, P3);

		Saved s = lastSave();
		assertEquals(IngestState.FAILED, s.state());
		assertRecordedAtTheSave(s, T1);
		assertEquals(Map.of(u9.toString(), iso(T0)), pendingOf(s));
		verify(sbomComponentRepository, never()).findUuidsByOrgAndCanonicalPurlIn(any(), any());
	}

	@Test
	void aSubmitOfAnUnrecordedBucketRecordsTheMembershipAsLoaded() throws Exception {
		SyntheticDtrackBucket b = bucket(IngestState.INGESTED, T1, P1, P2);

		submit(b, false, P2, P3);

		Saved s = lastSave();
		assertEquals(IngestState.SUBMITTED, s.state());
		assertEquals(entriesAt(s.date(), u1, u2), pendingOf(s), "the old members p1 and p2, not the new p3");
		assertRecordedAtTheSave(s, T1);
	}

	@Test
	void aFailedSubmitOfAnUnrecordedBucketRecordsTheMembershipAsLoaded() throws Exception {
		SyntheticDtrackBucket b = bucket(IngestState.INGESTED, T1, P1, P2);

		submit(b, true, P2, P3);

		Saved s = lastSave();
		assertEquals(IngestState.FAILED, s.state());
		assertEquals(entriesAt(s.date(), u1, u2), pendingOf(s));
	}

	// ---------------- the resync ----------------

	@Test
	void aResyncWithIdenticalFindingsSavesAndRecordsNothing() throws Exception {
		SyntheticDtrackBucket b = bucket(IngestState.INGESTED, T1, P1, P2);
		SyntheticFindings f = findings(vuln(P1, "CVE-1", VulnerabilitySeverity.HIGH));
		resync(b, f);
		recorded(b, Map.of());
		b.setLastUpdatedDate(lastSave().date());
		BucketFlowControl before = b.getFlowControl();
		saves.clear();

		resync(b, f);

		assertTrue(saves.isEmpty(), "sameFindings: no save");
		assertSame(before, b.getFlowControl(), "no entry, recordedAt untouched");
	}

	@Test
	void aResyncWithOneChangedPurlRecordsThatUuidAndKeepsTheOtherEntries() throws Exception {
		SyntheticDtrackBucket b = recorded(bucket(IngestState.INGESTED, T1, P1, P2), Map.of(u9, T0));

		resync(b, findings(vuln(P1, "CVE-1", VulnerabilitySeverity.HIGH)));

		Saved s = lastSave();
		assertRecordedAtTheSave(s, T1);
		Map<String, String> expected = entriesAt(s.date(), u1);
		expected.put(u9.toString(), iso(T0));
		assertEquals(expected, pendingOf(s));
	}

	@Test
	void aPurlThatResolvesToNoComponentRecordsNothing() throws Exception {
		SyntheticDtrackBucket b = recorded(bucket(IngestState.INGESTED, T1, P1, GONE), Map.of());

		assertDoesNotThrow(() -> resync(b, findings(vuln(GONE, "CVE-9", VulnerabilitySeverity.HIGH))));

		Saved s = lastSave();
		assertTrue(pendingOf(s).isEmpty());
		assertRecordedAtTheSave(s, T1);
	}

	// ---------------- the fold on a resync (D25, F-6, F-9) ----------------

	private Saved resyncOneChangedPurlOn(SyntheticDtrackBucket b) throws Exception {
		resync(b, findings(vuln(P1, "CVE-1", VulnerabilitySeverity.HIGH)));
		return lastSave();
	}

	@Test
	void aResyncOnAnUnrecordedBucketRecordsTheWholeMembership() throws Exception {
		Saved s = resyncOneChangedPurlOn(bucket(IngestState.INGESTED, T1, P1, P2, P3));

		assertEquals(entriesAt(s.date(), u1, u2, u3), pendingOf(s));
		assertRecordedAtTheSave(s, T1);
	}

	@Test
	void aResyncOnABucketRecordedBeforeItsLastMoveRecordsTheWholeMembership() throws Exception {
		SyntheticDtrackBucket b = bucket(IngestState.INGESTED, T1, P1, P2, P3);
		b.setFlowControl(new BucketFlowControl(new HashMap<>(), iso(T0)));

		Saved s = resyncOneChangedPurlOn(b);

		assertEquals(entriesAt(s.date(), u1, u2, u3), pendingOf(s));
	}

	@Test
	void aResyncOnABucketRecordedAfterItsDateRecordsTheWholeMembership() throws Exception {
		SyntheticDtrackBucket b = bucket(IngestState.INGESTED, T1, P1, P2, P3);
		b.setFlowControl(new BucketFlowControl(new HashMap<>(), iso(T2)));

		Saved s = resyncOneChangedPurlOn(b);

		assertEquals(entriesAt(s.date(), u1, u2, u3), pendingOf(s), "the rule is differs, not after");
	}

	@Test
	void aResyncOnARecordedBucketRecordsTheDiffOnly() throws Exception {
		Saved s = resyncOneChangedPurlOn(recorded(bucket(IngestState.INGESTED, T1, P1, P2, P3), Map.of()));

		assertEquals(entriesAt(s.date(), u1), pendingOf(s));
	}

	@Test
	void anUnrecordedBucketDatedAtTheWatermarkIsNotFolded() throws Exception {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T1);

		Saved s = resyncOneChangedPurlOn(bucket(IngestState.INGESTED, T1, P1, P2, P3));

		assertEquals(entriesAt(s.date(), u1), pendingOf(s), "drained by the old walk: the diff only");
		assertRecordedAtTheSave(s, T1);
	}

	@Test
	void anUnrecordedBucketDatedAfterTheWatermarkIsFolded() throws Exception {
		when(systemInfoService.getFanOutDrainedAt(ORG)).thenReturn(T0);

		Saved s = resyncOneChangedPurlOn(bucket(IngestState.INGESTED, T1, P1, P2, P3));

		assertEquals(entriesAt(s.date(), u1, u2, u3), pendingOf(s));
	}

	@Test
	void theFoldFiresOnce() throws Exception {
		SyntheticDtrackBucket b = bucket(IngestState.INGESTED, T1, P1, P2, P3);
		resyncOneChangedPurlOn(b);
		Saved first = lastSave();

		resync(b, findings(vuln(P1, "CVE-1", VulnerabilitySeverity.HIGH), vuln(P2, "CVE-2", VulnerabilitySeverity.LOW)));

		Saved second = lastSave();
		Map<String, String> expected = new HashMap<>(entriesAt(first.date(), u1, u3));
		expected.putAll(entriesAt(second.date(), u2));
		assertEquals(expected, pendingOf(second), "the second save records its diff (p2) only");
	}

	// ---------------- the retirement ----------------

	private void retire(SyntheticDtrackBucket b, DtrackProjectDeletionOutcome outcome) throws Exception {
		when(rebomService.isEnrichmentConfigured(ORG)).thenReturn(false);
		when(sbomComponentRepository.findMatchableByOrgOrdered(ORG.toString())).thenReturn(List.of());
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(b));
		when(integrationService.deleteDtrackProjectForOrg(ORG, PROJ.toString())).thenReturn(outcome);
		service.submitOrg(ORG);
	}

	private SyntheticDtrackBucket withFindingsFor(SyntheticDtrackBucket b, String... purls) throws Exception {
		resync(b, findings(java.util.Arrays.stream(purls)
				.map(p -> vuln(p, "CVE-" + p, VulnerabilitySeverity.HIGH)).toArray(VulnerabilityDto[]::new)));
		recorded(b, Map.of());
		b.setLastUpdatedDate(lastSave().date());
		b.setFlowControl(new BucketFlowControl(new HashMap<>(), iso(b.getLastUpdatedDate())));
		saves.clear();
		clearInvocations(sbomComponentRepository);
		return b;
	}

	@Test
	void aRetirementRecordsItsFindingsAtEverySaveAndKeepsThemPending() throws Exception {
		SyntheticDtrackBucket b = withFindingsFor(bucket(IngestState.INGESTED, T1, P1, P2), P1, P2);
		ZonedDateTime loaded = b.getLastUpdatedDate();

		retire(b, DtrackProjectDeletionOutcome.FAILED);

		Saved pending = lastSave();
		assertEquals(IngestState.PENDING, pending.state());
		assertEquals(entriesAt(pending.date(), u1, u2), pendingOf(pending));
		assertEquals(iso(pending.date()), pending.fc().recordedAt());
		assertFalse(pending.date().isBefore(loaded));

		// The retry: locally clear, the deletion fails again.
		retire(b, DtrackProjectDeletionOutcome.FAILED);

		Saved retried = lastSave();
		assertEquals(IngestState.PENDING, retried.state());
		assertEquals(pendingOf(pending), pendingOf(retried), "the entries stay pending");
		assertEquals(iso(retried.date()), retried.fc().recordedAt(), "recordedAt moved");

		retire(b, DtrackProjectDeletionOutcome.DELETED);

		Saved converged = lastSave();
		assertEquals(IngestState.INGESTED, converged.state());
		assertEquals(pendingOf(pending), pendingOf(converged));
		assertEquals(iso(converged.date()), converged.fc().recordedAt());
	}

	@Test
	void aRetirementOfAnUnrecordedBucketRecordsTheMembershipAsLoaded() throws Exception {
		SyntheticDtrackBucket b = withFindingsFor(bucket(IngestState.INGESTED, T1, P1, P2), P1);
		b.setFlowControl(null);

		retire(b, DtrackProjectDeletionOutcome.FAILED);

		Saved pending = lastSave();
		assertEquals(entriesAt(pending.date(), u1, u2), pendingOf(pending),
				"p2 from the membership captured before the clear, p1 from the diff");
	}

	@Test
	void aConvergingRetirementOfARecordedBucketRecordsTheDiffOnly() throws Exception {
		SyntheticDtrackBucket b = withFindingsFor(bucket(IngestState.INGESTED, T1, P1, P2), P1);

		retire(b, DtrackProjectDeletionOutcome.DELETED);

		Saved converged = lastSave();
		assertEquals(IngestState.INGESTED, converged.state());
		assertEquals(entriesAt(converged.date(), u1), pendingOf(converged));
	}

	// ---------------- the re-key in fanOutOrg ----------------

	private static final String ZLIB = "pkg:deb/debian/zlib@1:1.2.13.dfsg-1?distro=debian-12";
	private static final String ZLIB_DT = "pkg:deb/debian/zlib@1%3A1.2.13.dfsg-1?distro=debian-12";

	private SyntheticDtrackBucket reKeyBucket() throws Exception {
		SyntheticDtrackBucket b = bucket(IngestState.INGESTED, T1, P1, ZLIB);
		Map<String, Object> v = new HashMap<>();
		v.put("vulnId", "CVE-2023-45853");
		Map<String, Object> entry = new HashMap<>();
		entry.put("vulns", new ArrayList<>(List.of(v)));
		Map<String, Object> findings = new HashMap<>();
		findings.put(ZLIB_DT, entry);
		b.setFindings(findings);
		when(bucketRepository.findByOrg(ORG)).thenReturn(List.of(b));
		lenient().when(sbomComponentRepository.findUuidsByOrgAndCanonicalPurlIn(eq(ORG), any()))
				.thenAnswer(purlsResolveTo(Map.of(P1, u1, ZLIB, u3)));
		lenient().when(artifactCanonicalMapRepository.countFanOutStamped(ORG)).thenReturn(1L);
		lenient().when(artifactCanonicalMapRepository.findCanonicalsStampedBefore(eq(ORG), any(), any()))
				.thenAnswer(FanOutWalkTestSupport.everyCanonicalNeedsFanOut());
		return b;
	}

	@Test
	void theReKeyRecordsTheReKeyedPurlsAtTheBumpedDate() throws Exception {
		SyntheticDtrackBucket b = recorded(reKeyBucket(), Map.of());

		service.fanOutOrg(ORG);

		Saved s = saves.get(0);
		assertRecordedAtTheSave(s, T1);
		assertEquals(entriesAt(s.date(), u3), pendingOf(s), "the re-keyed canonical; the variant key resolves to nothing");
		assertEquals(Set.of(ZLIB), b.getFindings().keySet());
	}

	@Test
	void aFailedReKeySaveRestoresTheDateAndTheBookkeeping() throws Exception {
		SyntheticDtrackBucket b = recorded(reKeyBucket(), Map.of(u9, T0));
		BucketFlowControl before = b.getFlowControl();
		doThrow(new RuntimeException("stale revision")).when(bucketRepository).save(any());

		service.fanOutOrg(ORG);

		assertEquals(T1, b.getLastUpdatedDate());
		assertSame(before, b.getFlowControl(), "the object the bucket held before the call");
		assertEquals(Map.of(u9.toString(), iso(T0)), before.fanOutChanged(), "never mutated");
		verify(bucketRepository, never()).stampFlowControl(any(), any());
	}

	@Test
	void theReKeyOnAnUnrecordedBucketRecordsTheWholeMembershipBesideTheReKeyedPurl() throws Exception {
		reKeyBucket();

		service.fanOutOrg(ORG);

		Saved s = saves.get(0);
		assertEquals(entriesAt(s.date(), u1, u3), pendingOf(s));
		verify(fanOutWalkRepository).findChangedCanonicalsInSlice(eq(ORG), eq(FanOutWalkSlice.START),
				eq(FanOutWalkSlice.endOf(0)), any(), anyInt());
	}
}
