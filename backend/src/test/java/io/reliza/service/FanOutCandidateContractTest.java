/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.Artifact;
import io.reliza.model.ArtifactCanonicalMap;
import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.SbomComponent;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.repositories.ArtifactCanonicalMapRepository;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.ArtifactSbomComponentRepository;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SyntheticDtrackBucketRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * Pins the fan-out candidate contract broken on prod 2026-07-25: every
 * processed candidate must drop out of
 * {@code findCanonicalArtifactsNeedingFanOut}'s pool, and never-scanned
 * artifacts must win the batch.
 *
 * <p>The incident: the idempotency guard in
 * {@code SharedArtifactService.updateArtifactDti} skipped unchanged artifacts
 * WITHOUT advancing {@code metrics.lastScanned}. After a mass bucket re-ingest
 * put ~800 canonicals (5,029 artifact rows) back in the pool, none of them
 * ever dropped out, the unordered LIMIT batch served the same 500 every tick,
 * and 3 genuinely-unscanned artifacts sat on "Scan pending" indefinitely with
 * every pipeline diagnostic reading healthy.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FanOutCandidateContractTest {

	@Autowired private ArtifactRepository artifactRepository;
	@Autowired private ArtifactCanonicalMapRepository artifactCanonicalMapRepository;
	@Autowired private ArtifactSbomComponentRepository artifactSbomComponentRepository;
	@Autowired private SbomComponentRepository sbomComponentRepository;
	@Autowired private SyntheticDtrackBucketRepository bucketRepository;
	@Autowired private TestInitializer testInitializer;

	/** Epoch stamp far in the past — always below any bucket's last_updated_date. */
	private static final double STALE_EPOCH = 1_000_000_000.0; // 2001

	@Test
	public void advanceLastScannedOnlyStampsWithoutTouchingOtherMetrics() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		Artifact a = saveArtifactWithLastScanned(org, STALE_EPOCH);

		artifactRepository.advanceLastScannedOnly(a.getUuid());

		Map<String, Object> metrics = artifactRepository.findById(a.getUuid()).orElseThrow().getMetrics();
		double stamped = ((Number) metrics.get("lastScanned")).doubleValue();
		assertTrue(stamped > STALE_EPOCH + 1, "lastScanned must advance to ~now");
		assertEquals("keep-me", metrics.get("projectName"), "no other metrics field may be altered");
	}

	@Test
	public void poolSlicePutsNeverScannedFirstThenStalestStamp() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// One INGESTED bucket so cutoff is meaningful for the caller.
		SyntheticDtrackBucket bucket = new SyntheticDtrackBucket();
		bucket.setOrg(org);
		bucket.setBucketIndex(0);
		bucket.setIngestState(IngestState.INGESTED);
		Map<String, Object> refMap = new java.util.HashMap<>();
		refMap.put("pkg:npm/looper@1.0.0", "x");
		refMap.put("pkg:npm/fresh@1.0.0", "y");
		bucket.setRefMap(refMap);
		bucketRepository.save(bucket);

		// A stale looper (stamp far in the past) and a never-scanned artifact.
		UUID looperCanonical = mapArtifactWithComponent(org, "pkg:npm/looper@1.0.0", STALE_EPOCH);
		UUID freshCanonical = mapArtifactWithComponent(org, "pkg:npm/fresh@1.0.0", null);
		double cutoff = 2_000_000_000.0; // above STALE_EPOCH: both are in the pool

		List<UUID> slice = artifactRepository.findFanOutPoolSlice(org, cutoff, 1000);
		// The org is fresh, so exactly our two artifacts qualify; never-scanned first.
		List<UUID> ours = slice.stream().filter(u ->
				artifactCanonicalMapRepository.findByArtifactUuid(u).isPresent()).toList();
		assertTrue(ours.size() >= 2, "both artifacts must be in the pool slice");
		UUID firstCanonical = artifactCanonicalMapRepository
				.findByArtifactUuid(ours.get(0)).orElseThrow().getCanonicalArtifactUuid();
		assertEquals(freshCanonical, firstCanonical,
				"never-scanned artifacts must lead the slice -- they are what users actively wait on");
		assertTrue(ours.stream().anyMatch(u -> looperCanonical.equals(
				artifactCanonicalMapRepository.findByArtifactUuid(u).orElseThrow().getCanonicalArtifactUuid())),
				"the stale looper must also be in the pool below the cutoff");
	}

	/** Steady state: every stamp postdates the cutoff -- the slice must be empty(-ish). */
	@Test
	public void poolSliceEmptyWhenStampsPostdateCutoff() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		mapArtifactWithComponent(org, "pkg:npm/settled@1.0.0", STALE_EPOCH);
		List<UUID> slice = artifactRepository.findFanOutPoolSlice(org, STALE_EPOCH - 1.0, 1000);
		assertEquals(0, slice.size(),
				"a stamp at or above the cutoff must not be pooled -- steady state is an empty index range");
	}

	@Test
	public void neverScannedStallCounterSeesOnlyOldUnscannedMappedArtifacts() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		mapArtifactWithComponent(org, "pkg:npm/old-unscanned@1.0.0", null);
		long counted = artifactCanonicalMapRepository.countNeverScannedMappedArtifactsOlderThan(
				org, java.time.ZonedDateTime.now().plusMinutes(5));
		assertEquals(1, counted, "a mapped never-scanned artifact older than the cutoff must be counted");
		long none = artifactCanonicalMapRepository.countNeverScannedMappedArtifactsOlderThan(
				org, java.time.ZonedDateTime.now().minusHours(2));
		assertEquals(0, none, "a fresh upload must not trip the stall counter");
	}

	/**
	 * An artifact outside the scanning universe -- mapped but with no matchable
	 * components (metadata-only / empty-parse BOM) -- must NOT be counted: it is
	 * deliberately never a fan-out candidate, so reporting it as starved is a
	 * permanent false stall (observed on the first live deployment: four
	 * 2025-04-era zero-component artifacts re-logged every interval).
	 */
	@Test
	public void artifactOutsideScanningUniverseDoesNotTripStallCounter() {
		UUID org = testInitializer.obtainOrganization().getUuid();
		// Mapped, never scanned, old -- but zero component rows.
		Artifact bare = saveArtifactWithLastScanned(org, null);
		Map<String, Object> metrics = new java.util.HashMap<>();
		metrics.put("projectName", "keep-me");
		bare.setMetrics(metrics);
		bare = artifactRepository.save(bare);
		ArtifactCanonicalMap m = new ArtifactCanonicalMap();
		m.setOrg(org);
		m.setArtifactUuid(bare.getUuid());
		m.setCanonicalArtifactUuid(UUID.randomUUID());
		artifactCanonicalMapRepository.save(m);

		long counted = artifactCanonicalMapRepository.countNeverScannedMappedArtifactsOlderThan(
				org, java.time.ZonedDateTime.now().plusMinutes(5));
		assertEquals(0, counted,
				"zero-component artifacts are outside the scanning universe and must not be counted");
	}

	// ---------------- fixtures ----------------

	private Artifact saveArtifactWithLastScanned(UUID org, Double lastScannedEpoch) {
		Artifact a = new Artifact();
		Map<String, Object> rd = new java.util.HashMap<>();
		rd.put("org", org.toString());
		a.setRecordData(rd);
		Map<String, Object> metrics = new java.util.HashMap<>();
		if (lastScannedEpoch != null) {
			metrics.put("lastScanned", lastScannedEpoch);
			metrics.put("firstScanned", lastScannedEpoch);
		}
		metrics.put("projectName", "keep-me");
		a.setMetrics(metrics);
		return artifactRepository.save(a);
	}

	/** Artifact + canonical map row + one bucketed component; returns the canonical uuid. */
	private UUID mapArtifactWithComponent(UUID org, String canonicalPurl, Double lastScannedEpoch) {
		Artifact a = saveArtifactWithLastScanned(org, lastScannedEpoch);
		if (lastScannedEpoch == null) {
			// never scanned: metrics present but without lastScanned
			Map<String, Object> metrics = new java.util.HashMap<>();
			metrics.put("projectName", "keep-me");
			a.setMetrics(metrics);
			a = artifactRepository.save(a);
			assertNull(a.getMetrics().get("lastScanned"));
		} else {
			assertNotNull(a.getMetrics().get("lastScanned"));
		}

		SbomComponent sc = new SbomComponent();
		sc.setOrg(org);
		sc.setCanonicalPurl(canonicalPurl);
		Map<String, Object> rec = new java.util.HashMap<>();
		rec.put("name", canonicalPurl);
		rec.put("type", "library");
		sc.setRecordData(rec);
		sc.setSyntheticBucketIndex(0);
		sc = sbomComponentRepository.save(sc);

		UUID canonical = UUID.randomUUID();
		ArtifactSbomComponent asc = new ArtifactSbomComponent();
		asc.setOrg(org);
		asc.setCanonicalArtifactUuid(canonical);
		asc.setSbomComponentUuid(sc.getUuid());
		asc.setExactPurl(canonicalPurl);
		asc.setParents(new java.util.ArrayList<>());
		artifactSbomComponentRepository.save(asc);

		ArtifactCanonicalMap m = new ArtifactCanonicalMap();
		m.setOrg(org);
		m.setArtifactUuid(a.getUuid());
		m.setCanonicalArtifactUuid(canonical);
		artifactCanonicalMapRepository.save(m);
		return canonical;
	}
}
