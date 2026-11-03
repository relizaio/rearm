/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.time.ZonedDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.ws.App;

/**
 * TPF-136 architecture 6.4 cases 10 and 11, on the test database through the real
 * {@link SyntheticSbomService#fanOutOrg}: a cold start with no stamped map row skips the bucket
 * walk and records the cutoff as the org's drained-at watermark, and the next bucket change is
 * walked from it; countFanOutStamped counts one org's stamped rows only.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FanOutColdStartIntegrationTest extends FanOutCandidateTestBase {

	@Test
	public void aColdStartRecordsTheCutoffAndTheChangeDrivenWalkResumesFromIt() {
		UUID org = newOrg();
		ZonedDateTime t1 = nowMicros().minusMinutes(2);
		String p = uniquePurl("p");
		Mapped m = mapBom(org, null, null, component(org, p, 0));
		SyntheticDtrackBucket bucket = bucketAt(org, 0, t1, p);
		assertEquals(0, artifactCanonicalMapRepository.countFanOutStamped(org));

		syntheticSbomService.fanOutOrg(org);

		assertEquals(iso(t1), fanOutAtOf(m.artifact()));
		assertNotNull(lastScannedOf(m.artifact()));
		assertEquals(iso(t1), iso(systemInfoService.getFanOutDrainedAt(org)),
				"the skip records the cutoff through the real in-place stamp");
		assertEquals(1, artifactCanonicalMapRepository.countFanOutStamped(org));

		ZonedDateTime t2 = nowMicros().minusMinutes(1);
		// TPF-137: the move records p as changed (a resync's shape); a move alone no longer re-fans.
		bumpWithChange(bucket, t2, p);

		syntheticSbomService.fanOutOrg(org);

		assertEquals(iso(t2), fanOutAtOf(m.artifact()),
				"the walk from the recorded watermark finds the stamped row through its pending component");
		assertEquals(iso(t2), iso(systemInfoService.getFanOutDrainedAt(org)));
	}

	@Test
	public void countFanOutStampedCountsThisOrgsStampedRowsOnly() {
		UUID org1 = newOrg();
		UUID org2 = newOrg();
		ZonedDateTime t1 = nowMicros().minusMinutes(1);
		Mapped c1 = mapBom(org1, null, null, component(org1, uniquePurl("c1"), 0));
		mapBom(org1, null, null, component(org1, uniquePurl("c2"), 0));
		Mapped d1 = mapBom(org2, null, null, component(org2, uniquePurl("d1"), 0));
		stampFanOut(org1, c1.canonical(), t1);
		stampFanOut(org2, d1.canonical(), t1);

		assertEquals(1, artifactCanonicalMapRepository.countFanOutStamped(org1));
		assertEquals(1, artifactCanonicalMapRepository.countFanOutStamped(org2));

		artifactCanonicalMapRepository.requeueFanOutForCanonical(org1, c1.canonical());

		assertEquals(0, artifactCanonicalMapRepository.countFanOutStamped(org1), "a requeued row does not count");
		assertEquals(1, artifactCanonicalMapRepository.countFanOutStamped(org2));
	}
}
