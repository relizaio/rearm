/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.ArtifactSbomComponent;
import io.reliza.model.BucketFlowControl;
import io.reliza.model.SbomComponent;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.ws.App;

/**
 * TPF-137 architecture 6.2 case 6, on the test database through the real
 * {@link SyntheticSbomService#fanOutOrg} and {@link io.reliza.repositories.FanOutWalkRepository}:
 * a drain re-fans only the canonicals holding a changed component, the walk follows PostgreSQL's
 * unsigned uuid order across the sign bit, a keyset sweep returns every canonical once and never
 * past its slice, the deploy transition catches up an unrecorded bucket, a same-hash re-ingest
 * re-evaluates the canonical gate-skipped during its window, and another org's rows are never
 * returned. Every test takes a fresh org and unique purls; the walk state and the watermark keys
 * are removed after each test (FanOutCandidateTestBase).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FanOutWalkIntegrationTest extends FanOutCandidateTestBase {

	@Test
	public void aDrainReFansOnlyTheCanonicalsHoldingAChangedComponent() {
		UUID org = newOrg();
		String p = uniquePurl("p");
		String q = uniquePurl("q");
		SbomComponent cp = component(org, p, 0);
		SbomComponent cq = component(org, q, 0);
		Mapped c1 = mapBom(org, null, null, cp);
		Mapped c2 = mapBom(org, null, null, cq);
		Mapped c3 = mapBom(org, null, null, cp, cq);
		ZonedDateTime t0 = nowMicros().minusHours(1);
		for (Mapped m : List.of(c1, c2, c3)) assertEquals(1, stampFanOut(org, m.canonical(), t0));
		ZonedDateTime t1 = nowMicros().minusMinutes(1);
		SyntheticDtrackBucket bucket = bumpWithChange(bucketAt(org, 0, t1, p, q), t1, p);

		fanOutUntilDrained(org, 300);

		assertEquals(iso(t1), fanOutAtOf(c1.artifact()));
		assertEquals(iso(t0), fanOutAtOf(c2.artifact()), "q did not change: c2 is not walked");
		assertEquals(iso(t1), fanOutAtOf(c3.artifact()));
		assertTrue(bucketFlowControlOf(bucket).fanOutChanged().isEmpty(), "the completion cleared the entry");
		assertEquals(iso(t1), bucketFlowControlOf(bucket).recordedAt(), "and kept recordedAt");
		assertEquals(iso(t1), iso(systemInfoService.getFanOutDrainedAt(org)));
	}

	@Test
	public void theWalkFollowsPostgresUuidOrderAcrossTheSignBit() {
		UUID org = newOrg();
		SbomComponent c = component(org, uniquePurl("signbit"), 0);
		UUID k7f = FanOutWalkTestSupport.inSlice(0x7f);
		UUID k80 = FanOutWalkTestSupport.inSlice(0x80);
		UUID kff = FanOutWalkTestSupport.inSlice(0xff);
		for (UUID k : List.of(kff, k80, k7f)) {
			holdComponent(org, k, c);
			mapRow(org, saveArtifactWithLastScanned(org, null).getUuid(), k, null);
		}
		UUID[] changed = {c.getUuid()};

		List<UUID> first = fanOutWalkRepository.findChangedCanonicalsInSlice(org, FanOutWalkSlice.startOf(127),
				FanOutWalkSlice.endOf(127), changed, 1);
		assertEquals(List.of(k7f), first);
		assertEquals(127, FanOutWalkSlice.sliceOf(first.get(0)));
		assertTrue(fanOutWalkRepository.findChangedCanonicalsInSlice(org, k7f, FanOutWalkSlice.endOf(127), changed, 1)
				.isEmpty(), "nothing after 7f inside slice 127");
		List<UUID> second = fanOutWalkRepository.findChangedCanonicalsInSlice(org, FanOutWalkSlice.startOf(128),
				FanOutWalkSlice.endOf(128), changed, 1);
		assertEquals(List.of(k80), second);
		assertEquals(128, FanOutWalkSlice.sliceOf(second.get(0)));
		List<UUID> third = fanOutWalkRepository.findChangedCanonicalsInSlice(org, FanOutWalkSlice.startOf(255),
				FanOutWalkSlice.endOf(255), changed, 1);
		assertEquals(List.of(kff), third);
		assertEquals(255, FanOutWalkSlice.sliceOf(third.get(0)));
		assertEquals(List.of(k7f, k80, kff), sweep(org, changed, 1), "the whole sweep in unsigned order");
	}

	@Test
	public void aKeysetSweepReturnsEveryCanonicalOnceAndNeverPastTheSlice() {
		UUID org = newOrg();
		SbomComponent c = component(org, uniquePurl("sweep"), 0);
		List<ArtifactSbomComponent> rows = new ArrayList<>();
		Set<UUID> canonicals = new HashSet<>();
		for (int i = 0; i < 2000; i++) {
			UUID k = UUID.randomUUID();
			canonicals.add(k);
			ArtifactSbomComponent asc = new ArtifactSbomComponent();
			asc.setOrg(org);
			asc.setCanonicalArtifactUuid(k);
			asc.setSbomComponentUuid(c.getUuid());
			asc.setExactPurl(c.getCanonicalPurl());
			asc.setParents(new ArrayList<>());
			rows.add(asc);
		}
		artifactSbomComponentRepository.saveAll(rows);

		List<UUID> swept = sweep(org, new UUID[] {c.getUuid()}, 100);

		assertEquals(2000, swept.size(), "each canonical at most once");
		assertEquals(canonicals, new HashSet<>(swept), "and every one of them");
		List<UUID> sorted = new ArrayList<>(swept);
		sorted.sort(FanOutWalkTestSupport::compareUnsigned);
		assertEquals(sorted, swept, "in PostgreSQL's uuid order");
		assertEquals(2000, sweep(org, null, 100).size(), "the full-mode statement sweeps the same set");
	}

	/** F-1: the pre-deploy state of a bucket (no flow_control) dated after the watermark is caught up. */
	@Test
	public void theDeployTransitionCatchesUpAnUnrecordedBucket() {
		UUID org = newOrg();
		String p = uniquePurl("p");
		String q = uniquePurl("q");
		Mapped c1 = mapBom(org, null, null, component(org, p, 0));
		Mapped c2 = mapBom(org, null, null, component(org, q, 1));
		ZonedDateTime t0 = nowMicros().minusHours(1);
		stampFanOut(org, c1.canonical(), t0);
		stampFanOut(org, c2.canonical(), t0);
		systemInfoService.stampFanOutDrainedAt(org, t0);
		ZonedDateTime t1 = nowMicros().minusMinutes(1);
		SyntheticDtrackBucket b0 = bucketAt(org, 0, t1, p);
		SyntheticDtrackBucket b1 = bucketAt(org, 1, t0, q);
		unrecord(b0);
		unrecord(b1);

		fanOutUntilDrained(org, 300);

		BucketFlowControl fc0 = bucketFlowControlOf(b0);
		assertNotNull(fc0, "bucket 0 was caught up");
		assertEquals(iso(t1), fc0.recordedAt(), "at its own date");
		assertTrue(fc0.fanOutChanged().isEmpty(), "and its entries cleared at completion");
		assertEquals(iso(t1), fanOutAtOf(c1.artifact()));
		assertEquals(iso(t0), fanOutAtOf(c2.artifact()));
		assertNull(bucketFlowControlOf(b1), "dated at the watermark: never caught up");
		assertEquals(t1.toInstant(), bucketRepository.findById(b0.getUuid()).orElseThrow().getLastUpdatedDate().toInstant(),
				"the catch-up does not move the bucket date");
		assertEquals(iso(t1), iso(systemInfoService.getFanOutDrainedAt(org)));
	}

	/** F-2 with the F-7 fixture: the ingest that ends a same-hash window re-evaluates the gate-skipped canonical. */
	@Test
	public void aSameHashReingestReEvaluatesTheGateSkippedCanonical() {
		UUID org = newOrg();
		String p = uniquePurl("p");
		String q = uniquePurl("q");
		SbomComponent cq = component(org, q, 1);
		SbomComponent cp = component(org, p, 0);
		ZonedDateTime t0 = nowMicros().minusHours(1);
		bucketAt(org, 1, t0, q);
		SyntheticDtrackBucket b0 = bucketAt(org, 0, t0, p);
		Mapped control = mapBom(org, STALE_EPOCH, null, cq);
		stampFanOut(org, control.canonical(), t0);
		systemInfoService.stampFanOutDrainedAt(org, t0);
		// Bucket 0 moves to SUBMITTED at t1 with the same content hash, recorded (the shape submitBucket leaves).
		ZonedDateTime t1 = nowMicros().minusMinutes(30);
		SyntheticDtrackBucket submitted = bump(b0, t1);
		submitted.setIngestState(IngestState.SUBMITTED);
		submitted = bucketRepository.save(submitted);
		Mapped fresh = mapBom(org, null, null, cp);

		syntheticSbomService.fanOutOrg(org);

		assertEquals(iso(t0), fanOutAtOf(fresh.artifact()), "gate-skipped and stamped at the only INGESTED date");
		assertNull(lastScannedOf(fresh.artifact()));
		assertNull(systemInfoService.getFanOutWalk(org), "t0 is not after the watermark: no walk");
		assertEquals(iso(t0), iso(systemInfoService.getFanOutDrainedAt(org)));

		// The ingest that ends the window: identical findings, the whole membership recorded at t2
		// (SyntheticSbomServiceIngestDiffTest pins that the ingest records it; DTrack is not reachable here).
		ZonedDateTime t2 = nowMicros().minusMinutes(1);
		SyntheticDtrackBucket ingested = bumpWithChange(submitted, t2, p);
		ingested.setIngestState(IngestState.INGESTED);
		ingested = bucketRepository.save(ingested);
		assertEquals(Set.of(cp.getUuid().toString()), bucketFlowControlOf(ingested).fanOutChanged().keySet());
		assertEquals(iso(t2), bucketFlowControlOf(ingested).recordedAt());

		fanOutUntilDrained(org, 300);

		assertNotNull(lastScannedOf(fresh.artifact()), "the artifact is scanned once its bucket is covered again");
		assertEquals(iso(t2), fanOutAtOf(fresh.artifact()));
		assertEquals(iso(t0), fanOutAtOf(control.artifact()), "q has no pending entry: the drain followed p only");
		assertEquals(Double.valueOf(STALE_EPOCH), lastScannedOf(control.artifact()));
		assertEquals(iso(t2), iso(systemInfoService.getFanOutDrainedAt(org)));
	}

	@Test
	public void anotherOrgsRowsOfTheSameCanonicalUuidAreNotReturned() {
		UUID org1 = newOrg();
		UUID org2 = newOrg();
		SbomComponent foreign = component(org2, uniquePurl("org2-only"), 0);
		Mapped k2 = mapBom(org2, null, null, foreign);

		assertTrue(sweep(org1, new UUID[] {foreign.getUuid()}, 500).isEmpty(),
				"a forged array naming another org's component returns nothing for org1");
		assertTrue(sweep(org1, null, 500).stream().noneMatch(k2.canonical()::equals), "nor does the full-mode statement");
		assertTrue(artifactCanonicalMapRepository.findCanonicalsStampedBefore(org1, List.of(k2.canonical()), nowMicros())
				.isEmpty(), "the stamp check for org1 does not see org2's canonical");
		assertEquals(List.of(k2.canonical()), sweep(org2, new UUID[] {foreign.getUuid()}, 500), "org2 sees its own");
	}
}
