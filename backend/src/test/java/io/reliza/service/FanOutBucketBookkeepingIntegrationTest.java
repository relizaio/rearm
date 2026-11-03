/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.common.Utils;
import io.reliza.model.BucketFlowControl;
import io.reliza.model.SbomComponent;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.ws.App;

/**
 * TPF-137 architecture 6.2 case 13, on the test database: the two in-place writes of the bucket
 * bookkeeping. clearFanOutChangedThrough removes the entries dated at or before the cutoff from
 * every bucket of the org whatever its state, keeps newer entries and recordedAt, rewrites only rows
 * with something to remove (the EXISTS guard) and leaves rows without the map and other orgs alone;
 * the pending set reads the same rows, so a drain over buckets in three states re-fans the three
 * holders. stampFlowControl writes the given object and nothing else: no revision, the date
 * untouched. A class of its own (the walk class would pass 500 lines).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FanOutBucketBookkeepingIntegrationTest extends FanOutCandidateTestBase {

	/** A bucket of the org in the given state, recorded at {@code date}, with the given raw entries. */
	private SyntheticDtrackBucket bucketWith(UUID org, int index, IngestState state, ZonedDateTime date,
			Map<String, String> entries) {
		SyntheticDtrackBucket b = bucketAt(org, index, date, uniquePurl("m" + index));
		b.setIngestState(state);
		b.setFlowControl(new BucketFlowControl(new LinkedHashMap<>(entries), iso(date)));
		return bucketRepository.save(b);
	}

	private Map<String, String> pendingOf(SyntheticDtrackBucket b) {
		return bucketFlowControlOf(b).fanOutChanged();
	}

	private long revisionOf(SyntheticDtrackBucket b) {
		return jdbcTemplate.queryForObject("select revision from rearm.synthetic_dtrack_bucket where uuid = ?",
				Long.class, b.getUuid());
	}

	@Test
	public void theClearRemovesTheEntriesThroughTheCutoffInEveryStateAndNothingElse() {
		UUID org = newOrg();
		UUID other = newOrg();
		ZonedDateTime t1 = nowMicros().minusMinutes(10);
		ZonedDateTime t2 = nowMicros().minusMinutes(5);
		String e1 = UUID.randomUUID().toString();
		String e2 = UUID.randomUUID().toString();
		SyntheticDtrackBucket ingested = bucketWith(org, 0, IngestState.INGESTED, t2, Map.of(e1, iso(t1), e2, iso(t2)));
		SyntheticDtrackBucket submitted = bucketWith(org, 1, IngestState.SUBMITTED, t2, Map.of(e1, iso(t1)));
		SyntheticDtrackBucket pending = bucketWith(org, 2, IngestState.PENDING, t2, Map.of(e2, iso(t1)));
		SyntheticDtrackBucket allNewer = bucketWith(org, 3, IngestState.INGESTED, t2, Map.of(e1, iso(t2)));
		SyntheticDtrackBucket noMap = bucketAt(org, 4, t2, uniquePurl("nomap"));
		unrecord(noMap);
		SyntheticDtrackBucket foreign = bucketWith(other, 0, IngestState.INGESTED, t2, Map.of(e1, iso(t1)));
		long allNewerRevision = revisionOf(allNewer);

		assertEquals(3, bucketRepository.clearFanOutChangedThrough(org, t1), "the three buckets with an entry <= t1");

		assertEquals(Map.of(e2, iso(t2)), pendingOf(ingested), "the newer entry stays");
		assertEquals(iso(t2), bucketFlowControlOf(ingested).recordedAt(), "recordedAt untouched");
		assertTrue(pendingOf(submitted).isEmpty(), "a SUBMITTED bucket is cleared too");
		assertTrue(pendingOf(pending).isEmpty(), "and a PENDING one");
		assertEquals(Map.of(e1, iso(t2)), pendingOf(allNewer));
		assertEquals(allNewerRevision, revisionOf(allNewer), "a row with nothing to remove is not rewritten");
		assertNull(bucketFlowControlOf(noMap), "a row without the map is untouched");
		assertEquals(Map.of(e1, iso(t1)), pendingOf(foreign), "another org's bucket is untouched");

		assertEquals(0, bucketRepository.clearFanOutChangedThrough(org, t1), "nothing left to remove");
	}

	@Test
	public void thePendingSetReadsEveryBucketState() {
		UUID org = newOrg();
		String a = uniquePurl("a");
		String b = uniquePurl("b");
		String c = uniquePurl("c");
		SbomComponent ca = component(org, a, 0);
		SbomComponent cb = component(org, b, 1);
		SbomComponent cc = component(org, c, 2);
		Mapped ka = mapBom(org, null, null, ca);
		Mapped kb = mapBom(org, null, null, cb);
		Mapped kc = mapBom(org, null, null, cc);
		ZonedDateTime t0 = nowMicros().minusHours(1);
		for (Mapped m : List.of(ka, kb, kc)) stampFanOut(org, m.canonical(), t0);
		ZonedDateTime t1 = nowMicros().minusMinutes(1);
		SyntheticDtrackBucket b0 = bumpWithChange(bucketAt(org, 0, t1, a), t1, a);
		SyntheticDtrackBucket b1 = bumpWithChange(bucketAt(org, 1, t1, b), t1, b);
		b1.setIngestState(IngestState.SUBMITTED);
		b1 = bucketRepository.save(b1);
		SyntheticDtrackBucket b2 = bumpWithChange(bucketAt(org, 2, t1, c), t1, c);
		b2.setIngestState(IngestState.PENDING);
		b2 = bucketRepository.save(b2);

		fanOutUntilDrained(org, 300);

		for (Mapped m : List.of(ka, kb, kc)) {
			assertEquals(iso(t1), fanOutAtOf(m.artifact()), "every holder re-fanned, whatever its bucket's state");
		}
		for (SyntheticDtrackBucket bk : List.of(b0, b1, b2)) {
			assertTrue(pendingOf(bk).isEmpty(), "and every entry cleared");
		}
	}

	@Test
	public void stampFlowControlWritesTheObjectAndNothingElse() {
		UUID org = newOrg();
		ZonedDateTime t1 = nowMicros().minusMinutes(10);
		SyntheticDtrackBucket target = bucketAt(org, 0, t1, uniquePurl("target"));
		SyntheticDtrackBucket neighbour = bucketAt(org, 1, t1, uniquePurl("neighbour"));
		long revision = revisionOf(target);
		BucketFlowControl neighbourBefore = bucketFlowControlOf(neighbour);
		String entry = UUID.randomUUID().toString();
		BucketFlowControl written = new BucketFlowControl(Map.of(entry, iso(t1)), iso(t1));

		assertEquals(1, bucketRepository.stampFlowControl(target.getUuid(), Utils.OM.writeValueAsString(written)));

		SyntheticDtrackBucket stored = bucketRepository.findById(target.getUuid()).orElseThrow();
		assertEquals(written, stored.getFlowControl());
		assertEquals(t1.toInstant(), stored.getLastUpdatedDate().toInstant(), "last_updated_date untouched");
		assertEquals(revision, revisionOf(target), "no revision");
		assertEquals(neighbourBefore, bucketFlowControlOf(neighbour), "another bucket untouched");
	}
}
