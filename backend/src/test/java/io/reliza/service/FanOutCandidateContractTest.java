/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.Artifact;
import io.reliza.ws.App;

/**
 * Pins the fan-out scan-stamp and stall-counter contracts broken on prod 2026-07-25: every
 * processed candidate must drop out of the candidate set, and the [FANOUT-STALL] counters
 * must count only what fan-out can act on.
 *
 * <p>The incident: the idempotency guard in
 * {@code SharedArtifactService.updateArtifactDti} skipped unchanged artifacts
 * WITHOUT advancing {@code metrics.lastScanned}. After a mass bucket re-ingest
 * put ~800 canonicals (5,029 artifact rows) back in the pool, none of them
 * ever dropped out, the unordered LIMIT batch served the same 500 every tick,
 * and 3 genuinely-unscanned artifacts sat on "Scan pending" indefinitely with
 * every pipeline diagnostic reading healthy. The artifact-level pool itself is gone
 * (TPF-114: change-driven candidates per canonical, see FanOutChangeDrivenIntegrationTest).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FanOutCandidateContractTest extends FanOutCandidateTestBase {

	@Test
	public void advanceLastScannedOnlyStampsWithoutTouchingOtherMetrics() {
		UUID org = newOrg();
		Artifact a = saveArtifactWithLastScanned(org, STALE_EPOCH);

		double scannedAt = 1_790_000_000.25;
		artifactRepository.advanceLastScannedOnly(a.getUuid(), scannedAt);

		Map<String, Object> metrics = artifactRepository.findById(a.getUuid()).orElseThrow().getMetrics();
		double stamped = ((Number) metrics.get("lastScanned")).doubleValue();
		assertEquals(scannedAt, stamped, 1e-6, "lastScanned takes the caller's stamp, fraction included");
		assertEquals("keep-me", metrics.get("projectName"), "no other metrics field may be altered");
	}

	@Test
	public void neverScannedStallCounterSeesOnlyOldUnscannedMappedArtifacts() {
		UUID org = newOrg();
		mapArtifactWithComponent(org, "pkg:npm/old-unscanned@1.0.0", null);
		long counted = artifactCanonicalMapRepository.countNeverScannedMappedArtifactsOlderThan(
				org, ZonedDateTime.now().plusMinutes(5));
		assertEquals(1, counted, "a mapped never-scanned artifact older than the cutoff must be counted");
		long none = artifactCanonicalMapRepository.countNeverScannedMappedArtifactsOlderThan(
				org, ZonedDateTime.now().minusHours(2));
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
		UUID org = newOrg();
		// Mapped, never scanned, old -- but zero component rows.
		Artifact bare = saveArtifactWithLastScanned(org, null);
		Map<String, Object> metrics = new HashMap<>();
		metrics.put("projectName", "keep-me");
		bare.setMetrics(metrics);
		bare = artifactRepository.save(bare);
		mapRow(org, bare.getUuid(), UUID.randomUUID(), null);

		long counted = artifactCanonicalMapRepository.countNeverScannedMappedArtifactsOlderThan(
				org, ZonedDateTime.now().plusMinutes(5));
		assertEquals(0, counted,
				"zero-component artifacts are outside the scanning universe and must not be counted");
	}

	/**
	 * TPF-114 architecture 4.8 (a): a mapped non-BOM row (simulated: a VEX artifact with a
	 * direct map row) is a candidate of the never-stamped query, because the map is its
	 * source, but the stall counters count BOM-typed artifacts only.
	 */
	@Test
	public void stallCountersCountBomTypedMappedArtifactsOnly() {
		UUID org = newOrg();
		Artifact vex = saveArtifactWithLastScanned(org, null);
		vex.getRecordData().put("type", "VEX");
		vex = artifactRepository.save(vex);
		UUID vexCanonical = UUID.randomUUID();
		holdComponent(org, vexCanonical, component(org, uniquePurl("vex-dep"), 0));
		mapRow(org, vex.getUuid(), vexCanonical, null);
		Mapped bomTwin = mapBom(org, null, null, component(org, uniquePurl("bom-dep"), 0));

		assertTrue(neverFannedOut(org).contains(vexCanonical),
				"a mapped non-BOM row is still a never-stamped candidate: the map is the source");
		assertEquals(1, artifactCanonicalMapRepository.countNeverScannedMappedArtifactsOlderThan(
				org, ZonedDateTime.now().plusMinutes(5)), "the BOM twin counts, the VEX row does not");
		assertEquals(List.of(bomTwin.canonical()), artifactCanonicalMapRepository
				.findNeverScannedMappedArtifactCanonicalsOlderThan(org, ZonedDateTime.now().plusMinutes(5), 10));
	}
}
