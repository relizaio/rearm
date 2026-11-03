/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import io.reliza.model.Artifact;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.BucketFlowControl;
import io.reliza.model.CanonicalMapFlowControl;
import io.reliza.model.SbomComponent;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.ReleaseRepository;
import io.reliza.repositories.FanOutWalkRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SyntheticDtrackBucketRepository;
import io.reliza.ws.oss.TestInitializer;

/**
 * Shared fixtures of the fan-out candidate integration tests (TPF-114 architecture 4.1):
 * FanOutCandidateContractTest (stall counters, scan stamp), FanOutChangeDrivenIntegrationTest
 * (the stall, the bucket query, the catch-up) and FanOutRequeueIntegrationTest (org scoping,
 * the requeue and the stamp precondition). Every test takes a fresh org and unique purls.
 * A real fan-out pass may stamp the org's drained-at watermark into the shared system_info
 * row; the key is removed after each test.
 */
abstract class FanOutCandidateTestBase {

	@Autowired protected ArtifactRepository artifactRepository;
	@Autowired protected ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Autowired protected ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Autowired protected SbomComponentRepository sbomComponentRepository;
	@Autowired protected SyntheticDtrackBucketRepository bucketRepository;
	@Autowired protected SyntheticSbomService syntheticSbomService;
	@Autowired protected SystemInfoService systemInfoService;
	@Autowired protected TestInitializer testInitializer;
	@Autowired protected JdbcTemplate jdbcTemplate;
	@Autowired protected FanOutWalkRepository fanOutWalkRepository;

	/** Epoch stamp far in the past -- always below any bucket's last_updated_date. */
	protected static final double STALE_EPOCH = 1_000_000_000.0; // 2001

	/** A precondition instant every requeue postdates, so a fixture stamp always writes. */
	protected static final ZonedDateTime FAR_PAST = ZonedDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

	private final Set<UUID> orgsUsed = ConcurrentHashMap.newKeySet();

	/** A fresh org, remembered so its fan-out watermark key is removed afterwards. */
	protected UUID newOrg() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		orgsUsed.add(org);
		return org;
	}

	private Object backlogReaderOwner;
	private Object realReleaseRepository;

	/**
	 * The walk of a real fan-out pass pauses while the instance-wide metrics compute backlog (releases
	 * eligible for compute, every organization) is at or above ReleaseService.METRICS_BACKLOG_PRESSURE_THRESHOLD
	 * (TPF-137 D19). In one JVM on one database the classes that ran before leave releases of their own, and
	 * a backlog of 1100 was measured when these classes ran: the walk paused and the tests that expect it to run
	 * failed (found in the full suite by the mutation round). These classes are about the walk, not the
	 * pacing, which SyntheticFanOutWalkTest pins on its own, so the pass reads an empty backlog here.
	 */
	@BeforeEach
	void readNoMetricsBacklog() {
		backlogReaderOwner = AopTestUtils.getUltimateTargetObject(syntheticSbomService);
		realReleaseRepository = ReflectionTestUtils.getField(backlogReaderOwner, "releaseRepository");
		ReflectionTestUtils.setField(backlogReaderOwner, "releaseRepository", Mockito.mock(ReleaseRepository.class));
	}

	@AfterEach
	void readTheRealMetricsBacklogAgain() {
		ReflectionTestUtils.setField(backlogReaderOwner, "releaseRepository", realReleaseRepository);
	}

	@AfterEach
	void removeFanOutWatermarkKeys() {
		for (UUID org : orgsUsed) {
			jdbcTemplate.update("update rearm.system_info set data = data #- cast(? as text[]) where id = 1",
					"{fanOutDrainedAt," + org + "}");
			jdbcTemplate.update("update rearm.system_info set data = data #- cast(? as text[]) where id = 1",
					"{fanOutWalk," + org + "}");
		}
		orgsUsed.clear();
	}

	/** Now, at the database's microsecond precision, so a stored date compares exactly. */
	protected static ZonedDateTime nowMicros() {
		return ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
	}

	protected static String iso(ZonedDateTime t) {
		return DateTimeFormatter.ISO_INSTANT.format(t.toInstant());
	}

	protected static String uniquePurl(String name) {
		return "pkg:npm/" + name + "-" + UUID.randomUUID() + "@1.0.0";
	}

	protected Artifact saveArtifactWithLastScanned(UUID org, Double lastScannedEpoch) {
		Artifact a = new Artifact();
		Map<String, Object> rd = new HashMap<>();
		rd.put("org", org.toString());
		rd.put("type", "BOM");
		a.setRecordData(rd);
		Map<String, Object> metrics = new HashMap<>();
		if (lastScannedEpoch != null) {
			metrics.put("lastScanned", lastScannedEpoch);
			metrics.put("firstScanned", lastScannedEpoch);
		}
		metrics.put("projectName", "keep-me");
		a.setMetrics(metrics);
		return artifactRepository.save(a);
	}

	/** A non-BOM artifact (an attestation): no metrics stamp, no map row. */
	protected Artifact nonBomArtifact(UUID org) {
		Artifact a = new Artifact();
		Map<String, Object> rd = new HashMap<>();
		rd.put("org", org.toString());
		rd.put("type", "ATTESTATION");
		a.setRecordData(rd);
		return a;
	}

	protected Artifact saveNonBomArtifact(UUID org) {
		return artifactRepository.save(nonBomArtifact(org));
	}

	/** A component in the given synthetic bucket (null = unbucketed), not enriched. */
	protected SbomComponent component(UUID org, String canonicalPurl, Integer bucketIndex) {
		SbomComponent sc = new SbomComponent();
		sc.setOrg(org);
		sc.setCanonicalPurl(canonicalPurl);
		Map<String, Object> rec = new HashMap<>();
		rec.put("name", canonicalPurl);
		rec.put("type", "library");
		sc.setRecordData(rec);
		if (bucketIndex != null) sc.setSyntheticBucketIndex(bucketIndex);
		return sbomComponentRepository.save(sc);
	}

	/** An un-enriched, unbucketed component, as an un-enriched component of a BEAR org is. */
	protected SbomComponent unbucketedComponent(UUID org, String canonicalPurl) {
		return component(org, canonicalPurl, null);
	}

	protected void holdComponent(UUID org, UUID canonical, SbomComponent sc) {
		ArtifactSbomComponent asc = new ArtifactSbomComponent();
		asc.setOrg(org);
		asc.setCanonicalArtifactUuid(canonical);
		asc.setSbomComponentUuid(sc.getUuid());
		asc.setExactPurl(sc.getCanonicalPurl());
		asc.setParents(new ArrayList<>());
		artifactSbomComponentRepository.save(asc);
	}

	protected ArtifactCanonicalMap mapRow(UUID org, UUID artifactUuid, UUID canonical, ZonedDateTime createdDate) {
		ArtifactCanonicalMap m = new ArtifactCanonicalMap();
		m.setOrg(org);
		m.setArtifactUuid(artifactUuid);
		m.setCanonicalArtifactUuid(canonical);
		if (createdDate != null) m.setCreatedDate(createdDate);
		return artifactCanonicalMapRepository.save(m);
	}

	/** One BOM artifact mapped to a new canonical holding the given components. */
	protected record Mapped(UUID artifact, UUID canonical) {}

	protected Mapped mapBom(UUID org, Double lastScannedEpoch, ZonedDateTime createdDate, SbomComponent... comps) {
		Artifact a = saveArtifactWithLastScanned(org, lastScannedEpoch);
		UUID canonical = UUID.randomUUID();
		for (SbomComponent sc : comps) holdComponent(org, canonical, sc);
		mapRow(org, a.getUuid(), canonical, createdDate);
		return new Mapped(a.getUuid(), canonical);
	}

	/** Artifact + canonical map row + one component in bucket 0; returns the canonical uuid. */
	protected UUID mapArtifactWithComponent(UUID org, String canonicalPurl, Double lastScannedEpoch) {
		Artifact a = saveArtifactWithLastScanned(org, lastScannedEpoch);
		if (lastScannedEpoch == null) {
			assertNull(a.getMetrics().get("lastScanned"));
		} else {
			assertNotNull(a.getMetrics().get("lastScanned"));
		}
		UUID canonical = UUID.randomUUID();
		holdComponent(org, canonical, component(org, canonicalPurl, 0));
		mapRow(org, a.getUuid(), canonical, null);
		return canonical;
	}

	/**
	 * An INGESTED bucket covering the given purls, dated {@code updated} and recorded at that date
	 * (TPF-137: recordedAt equal to the date, nothing pending, so no fixture is caught up); asserts
	 * the stored date.
	 */
	protected SyntheticDtrackBucket bucketAt(UUID org, int index, ZonedDateTime updated, String... purls) {
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setOrg(org);
		b.setBucketIndex(index);
		b.setIngestState(IngestState.INGESTED);
		Map<String, Object> refMap = new HashMap<>();
		for (String p : purls) refMap.put(p, p);
		b.setRefMap(refMap);
		b.setLastUpdatedDate(updated);
		b.setFlowControl(new BucketFlowControl(new LinkedHashMap<>(), iso(updated)));
		b = bucketRepository.save(b);
		return assertStoredDate(b, updated);
	}

	/**
	 * Move an existing bucket's last update, recorded at the new date with its pending entries kept
	 * (TPF-137: a save of the new code that changed nothing pending, e.g. a submit).
	 */
	protected SyntheticDtrackBucket bump(SyntheticDtrackBucket b, ZonedDateTime updated) {
		SyntheticDtrackBucket fresh = bucketRepository.findById(b.getUuid()).orElseThrow();
		fresh.setLastUpdatedDate(updated);
		fresh.setFlowControl(new BucketFlowControl(pendingOf(fresh), iso(updated)));
		return assertStoredDate(bucketRepository.save(fresh), updated);
	}

	/**
	 * Move an existing bucket to {@code updated} with the components of the given purls pending at
	 * that date and recordedAt the date: the shape a resync that changed those purls' findings
	 * writes (TPF-137).
	 */
	protected SyntheticDtrackBucket bumpWithChange(SyntheticDtrackBucket b, ZonedDateTime updated, String... purls) {
		SyntheticDtrackBucket fresh = bucketRepository.findById(b.getUuid()).orElseThrow();
		Map<String, String> pending = pendingOf(fresh);
		for (SbomComponentRepository.ComponentUuidByPurl row : sbomComponentRepository
				.findUuidsByOrgAndCanonicalPurlIn(fresh.getOrg(), List.of(purls))) {
			pending.put(row.getUuid().toString(), iso(updated));
		}
		fresh.setLastUpdatedDate(updated);
		fresh.setFlowControl(new BucketFlowControl(pending, iso(updated)));
		return assertStoredDate(bucketRepository.save(fresh), updated);
	}

	/** Null the bucket's flow_control (the pre-TPF-137 shape of every bucket row). */
	protected void unrecord(SyntheticDtrackBucket b) {
		jdbcTemplate.update("update rearm.synthetic_dtrack_bucket set flow_control = null where uuid = ?", b.getUuid());
	}

	/** The bucket's stored flow_control, read fresh. */
	protected BucketFlowControl bucketFlowControlOf(SyntheticDtrackBucket b) {
		return bucketRepository.findById(b.getUuid()).orElseThrow().getFlowControl();
	}

	private static Map<String, String> pendingOf(SyntheticDtrackBucket b) {
		Map<String, String> pending = new LinkedHashMap<>();
		if (b.getFlowControl() != null && b.getFlowControl().fanOutChanged() != null) {
			pending.putAll(b.getFlowControl().fanOutChanged());
		}
		return pending;
	}

	/**
	 * The keyset sweep of TPF-137 4.5 step 3 through the real slice statement, without the stamp
	 * check: every slice from the start of the space, resumed inside a slice the room filled.
	 * {@code changed} null = full mode. Asserts each returned uuid is after its cursor and at most
	 * its slice end; returns the uuids in walk order.
	 */
	protected List<UUID> sweep(UUID org, UUID[] changed, int room) {
		List<UUID> out = new ArrayList<>();
		UUID cursor = FanOutWalkSlice.START;
		while (true) {
			int slice = FanOutWalkSlice.sliceOf(cursor);
			UUID sliceEnd = FanOutWalkSlice.endOf(slice);
			List<UUID> found = changed == null
					? fanOutWalkRepository.findCanonicalsInSlice(org, cursor, sliceEnd, room)
					: fanOutWalkRepository.findChangedCanonicalsInSlice(org, cursor, sliceEnd, changed, room);
			for (UUID u : found) {
				assertTrue(FanOutWalkTestSupport.compareUnsigned(u, cursor) > 0, "after its cursor");
				assertTrue(FanOutWalkTestSupport.compareUnsigned(u, sliceEnd) <= 0, "within its slice");
			}
			out.addAll(found);
			if (found.size() >= room) cursor = found.get(found.size() - 1);
			else if (slice == FanOutWalkSlice.SLICES - 1) return out;
			else cursor = FanOutWalkSlice.startOf(slice + 1);
		}
	}

	/** Passes until the org's drain completes (no walk state), at most {@code maxPasses}; returns the passes run. */
	protected int fanOutUntilDrained(UUID org, int maxPasses) {
		int passes = 0;
		do {
			syntheticSbomService.fanOutOrg(org);
			passes++;
		} while (systemInfoService.getFanOutWalk(org) != null && passes < maxPasses);
		assertNull(systemInfoService.getFanOutWalk(org), "the drain completed within " + maxPasses + " passes");
		return passes;
	}

	private SyntheticDtrackBucket assertStoredDate(SyntheticDtrackBucket saved, ZonedDateTime expected) {
		SyntheticDtrackBucket stored = bucketRepository.findById(saved.getUuid()).orElseThrow();
		assertEquals(expected.toInstant(), stored.getLastUpdatedDate().toInstant(),
				"the bucket date must be stored as given");
		return stored;
	}

	/** Stamp a canonical's map rows with a FAR_PAST precondition, so the stamp always writes. */
	protected int stampFanOut(UUID org, UUID canonical, ZonedDateTime fanOutAt) {
		return artifactCanonicalMapRepository.stampFanOutPass(org, canonical, iso(fanOutAt), FAR_PAST);
	}

	protected CanonicalMapFlowControl flowControlOf(UUID artifactUuid) {
		return artifactCanonicalMapRepository.findByArtifactUuid(artifactUuid).orElseThrow().getFlowControl();
	}

	protected String fanOutAtOf(UUID artifactUuid) {
		CanonicalMapFlowControl fc = flowControlOf(artifactUuid);
		return fc == null ? null : fc.fanOutAt();
	}

	protected Double lastScannedOf(UUID artifactUuid) {
		Map<String, Object> metrics = artifactRepository.findById(artifactUuid).orElseThrow().getMetrics();
		if (metrics == null || metrics.get("lastScanned") == null) return null;
		return ((Number) metrics.get("lastScanned")).doubleValue();
	}

	protected List<UUID> neverFannedOut(UUID org) {
		return artifactCanonicalMapRepository.findNeverFannedOutCanonicals(org, 2000);
	}
}
