/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import io.reliza.model.Artifact;
import io.reliza.model.SbomComponent;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.ws.App;

/**
 * TPF-114 architecture 4.5 to 4.7, on the test database through the real
 * {@link SyntheticSbomService#fanOutOrg}: the 2026-10 starvation (never-stamped non-BOM rows
 * no longer stand in front of a stale scanned BOM), the per-bucket change-driven query, and
 * the newest-first catch-up after deploy.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FanOutChangeDrivenIntegrationTest extends FanOutCandidateTestBase {

	/** 4.5: 2,100 never-stamped non-BOM rows no longer starve a stale scanned BOM. */
	@Test
	public void aStaleScannedBomBehindThousandsOfNeverScannedRowsIsReachedInOneTick() {
		UUID org = newOrg();
		List<Artifact> nonBom = new ArrayList<>();
		for (int i = 0; i < 2100; i++) nonBom.add(nonBomArtifact(org));
		List<UUID> nonBomUuids = new ArrayList<>();
		for (Artifact a : artifactRepository.saveAll(nonBom)) nonBomUuids.add(a.getUuid());

		String dep = uniquePurl("dep");
		Mapped s = mapBom(org, STALE_EPOCH, null, component(org, dep, 0));
		ZonedDateTime t0 = nowMicros().minusHours(2);
		stampFanOut(org, s.canonical(), t0);
		ZonedDateTime t1 = nowMicros().minusMinutes(1);
		SyntheticDtrackBucket bucket = bumpWithChange(bucketAt(org, 0, t1, dep), t1, dep);

		syntheticSbomService.fanOutOrg(org);

		Double scanned = lastScannedOf(s.artifact());
		assertNotNull(scanned);
		assertTrue(scanned > STALE_EPOCH, "the stale BOM is re-stamped within one tick");
		assertEquals(iso(bucket.getLastUpdatedDate()), fanOutAtOf(s.artifact()));
		for (UUID u : List.of(nonBomUuids.get(0), nonBomUuids.get(1049), nonBomUuids.get(2099))) {
			assertNull(lastScannedOf(u), "a non-BOM row is never touched");
			assertTrue(artifactCanonicalMapRepository.findByArtifactUuid(u).isEmpty(), "and never mapped");
		}

		syntheticSbomService.fanOutOrg(org);

		assertEquals(iso(t1), fanOutAtOf(s.artifact()), "no bucket moved: the stamp stays");
		assertEquals(scanned, lastScannedOf(s.artifact()), "no bucket moved: nothing is re-applied");
	}

	/** 4.6: a bucket change re-checks only the canonicals holding its components. */
	@Test
	public void aBucketChangeRechecksOnlyTheCanonicalsHoldingItsComponents() {
		UUID org = newOrg();
		String aPurl = uniquePurl("a"), bPurl = uniquePurl("b"), ePurl = uniquePurl("e");
		SbomComponent a = component(org, aPurl, 0);
		SbomComponent b = component(org, bPurl, 1);
		SbomComponent e = component(org, ePurl, 1);
		assertEquals(1, sbomComponentRepository.markEnrichmentTerminal(e.getUuid(), "OWN_BOM_PULLED_UNMATCHED"));
		Mapped ca = mapBom(org, null, null, a);
		Mapped cb = mapBom(org, null, null, b);
		Mapped cd = mapBom(org, null, null, a, b);
		Mapped ce = mapBom(org, null, null, e);
		ZonedDateTime t1 = nowMicros().minusMinutes(3);
		bucketAt(org, 0, t1, aPurl);
		SyntheticDtrackBucket b1 = bucketAt(org, 1, t1, bPurl);
		for (Mapped m : List.of(ca, cb, cd, ce)) assertEquals(1, stampFanOut(org, m.canonical(), t1));

		ZonedDateTime t2 = nowMicros().minusMinutes(1);
		// TPF-137: the resync records the changed purls; the terminal one resolves to no entry.
		SyntheticDtrackBucket moved = bumpWithChange(b1, t2, bPurl, ePurl);
		assertEquals(Set.of(b.getUuid().toString()), moved.getFlowControl().fanOutChanged().keySet(),
				"only the non-terminal changed component is pending");

		assertEquals(Set.of(cb.canonical(), cd.canonical()),
				new HashSet<>(sweep(org, new UUID[] {b.getUuid()}, 500)),
				"the changed component's canonicals, not the terminal component's");

		syntheticSbomService.fanOutOrg(org);

		assertEquals(iso(t1), fanOutAtOf(ca.artifact()));
		assertEquals(iso(t2), fanOutAtOf(cb.artifact()));
		assertEquals(iso(t2), fanOutAtOf(cd.artifact()));
		assertEquals(iso(t1), fanOutAtOf(ce.artifact()), "a terminal-only canonical is not re-qualified");
	}

	/** 4.7: the catch-up after deploy is newest first and stamps every canonical once. */
	@Test
	public void theCatchUpIsNewestFirstAndStampsEveryCanonicalOnce() {
		UUID org = newOrg();
		ZonedDateTime base = nowMicros().minusMinutes(30);
		List<String> purls = new ArrayList<>();
		List<Mapped> canonicals = new ArrayList<>();
		for (int i = 1; i <= 5; i++) {
			String p = uniquePurl("catchup" + i);
			purls.add(p);
			canonicals.add(mapBom(org, null, base.plusMinutes(i), component(org, p, 0)));
		}
		// A duplicate upload of the t3 canonical, mapped at t6.
		UUID dup = canonicals.get(2).canonical();
		Artifact dupArtifact = saveArtifactWithLastScanned(org, null);
		mapRow(org, dupArtifact.getUuid(), dup, base.plusMinutes(6));
		ZonedDateTime t1 = nowMicros().minusMinutes(2);
		bucketAt(org, 0, t1, purls.toArray(new String[0]));

		assertEquals(List.of(dup, canonicals.get(4).canonical(), canonicals.get(3).canonical(), dup,
				canonicals.get(1).canonical(), canonicals.get(0).canonical()), neverFannedOut(org),
				"map rows newest first, the duplicate's two rows each at their own position");

		syntheticSbomService.fanOutOrg(org);

		for (Mapped m : canonicals) assertEquals(iso(t1), fanOutAtOf(m.artifact()));
		assertEquals(iso(t1), fanOutAtOf(dupArtifact.getUuid()));
		assertTrue(neverFannedOut(org).isEmpty());
		assertEquals(0, artifactCanonicalMapRepository.countFanOutPendingOlderThan(org, ZonedDateTime.now().plusHours(1)));

		// A new upload of known content: its row alone is pending, and the next pass applies it.
		Artifact again = saveArtifactWithLastScanned(org, null);
		UUID first = canonicals.get(0).canonical();
		mapRow(org, again.getUuid(), first, null);
		assertEquals(List.of(first), neverFannedOut(org));

		syntheticSbomService.fanOutOrg(org);

		assertNotNull(lastScannedOf(again.getUuid()));
		assertEquals(iso(t1), fanOutAtOf(again.getUuid()));
		assertFalse(neverFannedOut(org).contains(first));
	}
}
