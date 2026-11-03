/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static io.reliza.service.FanOutWalkTestSupport.iso;
import static io.reliza.service.FanOutWalkTestSupport.minutesAgo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.model.BucketFlowControl;
import io.reliza.model.SyntheticDtrackBucket;

/**
 * TPF-137 mutation round: the pure helpers of the bucket bookkeeping that SyntheticFanOutWalkTest reaches
 * only through whole passes. When a bucket counts as unrecorded (a missing or unparsable recordedAt; a date
 * finer than the column keeps); a bucket with no refMap; and the pending set read from buckets that carry no
 * map, a key that is no uuid or a value that is no instant.
 */
class FanOutWalkBookkeepingEdgesTest {

	private static final UUID LOW = UUID.fromString("00000000-0000-4000-8000-000000000001");
	private static final UUID HIGH = UUID.fromString("ffffffff-0000-4000-8000-000000000002");
	private static final UUID MID = UUID.fromString("7fffffff-0000-4000-8000-000000000003");

	private final ZonedDateTime T1 = minutesAgo(5);

	private SyntheticDtrackBucket bucketAt(ZonedDateTime date, BucketFlowControl fc) {
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setLastUpdatedDate(date);
		b.setFlowControl(fc);
		return b;
	}

	// ---------------- isUnrecorded ----------------

	@Test
	void aBucketWithoutBookkeepingOrWithoutADateIsUnrecorded() {
		assertTrue(SyntheticSbomService.isUnrecorded(bucketAt(T1, null)));
		assertTrue(SyntheticSbomService.isUnrecorded(bucketAt(null, new BucketFlowControl(Map.of(), iso(T1)))));
		assertFalse(SyntheticSbomService.isUnrecorded(bucketAt(T1, new BucketFlowControl(Map.of(), iso(T1)))),
				"the control: recorded at its own date");
	}

	@Test
	void aBucketWhoseRecordedAtIsMissingIsUnrecorded() {
		assertTrue(SyntheticSbomService.isUnrecorded(bucketAt(T1, new BucketFlowControl(Map.of(), null))));
	}

	@Test
	void aBucketWhoseRecordedAtDoesNotParseIsUnrecorded() {
		assertTrue(SyntheticSbomService.isUnrecorded(bucketAt(T1, new BucketFlowControl(Map.of(), "yesterday"))));
	}

	@Test
	void aBucketDatedFinerThanMicrosecondsIsRecordedAtItsMicroseconds() {
		ZonedDateTime fine = T1.truncatedTo(ChronoUnit.MICROS).plusNanos(789);

		assertFalse(SyntheticSbomService.isUnrecorded(bucketAt(fine, new BucketFlowControl(Map.of(), iso(T1)))),
				"the column keeps microseconds: the loaded date and recordedAt agree to them");
		assertTrue(SyntheticSbomService.isUnrecorded(bucketAt(fine, new BucketFlowControl(Map.of(), iso(T1.plusNanos(2_000))))));
	}

	// ---------------- membershipPurlsOf ----------------

	@Test
	void aBucketWithNoRefMapHasNoMembership() {
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setRefMap(null);

		assertEquals(Set.of(), SyntheticSbomService.membershipPurlsOf(b));
	}

	// ---------------- pendingChangedComponents ----------------

	@Test
	void thePendingSetSkipsBucketsWithoutAMapKeysThatAreNoUuidAndValuesThatAreNoInstant() {
		SyntheticDtrackBucket noMap = bucketAt(T1, new BucketFlowControl(null, iso(T1)));
		SyntheticDtrackBucket noFlowControl = bucketAt(T1, null);
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put(HIGH.toString(), iso(T1));
		entries.put("not-a-uuid", iso(T1));
		entries.put(MID.toString(), "garbage");
		SyntheticDtrackBucket mixed = bucketAt(T1, new BucketFlowControl(entries, iso(T1)));

		List<UUID> pending = SyntheticSbomService.pendingChangedComponents(List.of(noMap, noFlowControl, mixed), T1);

		assertEquals(List.of(HIGH), pending);
	}

	@Test
	void thePendingSetIsInUuidTextOrderWhateverTheOrderOfTheBuckets() {
		SyntheticDtrackBucket first = bucketAt(T1, new BucketFlowControl(Map.of(HIGH.toString(), iso(T1)), iso(T1)));
		SyntheticDtrackBucket second = bucketAt(T1, new BucketFlowControl(Map.of(LOW.toString(), iso(T1), MID.toString(), iso(T1)), iso(T1)));

		assertEquals(List.of(LOW, MID, HIGH),
				SyntheticSbomService.pendingChangedComponents(List.of(first, second), T1));
	}

	@Test
	void anEntryAtTheCutoffIsInAndOneAfterItIsOut() {
		Map<String, String> entries = new LinkedHashMap<>();
		entries.put(LOW.toString(), iso(T1));
		entries.put(MID.toString(), iso(T1.plusNanos(1_000)));
		entries.put(HIGH.toString(), iso(T1.minusNanos(1_000)));
		SyntheticDtrackBucket b = bucketAt(T1, new BucketFlowControl(entries, iso(T1)));

		assertEquals(List.of(LOW, HIGH), SyntheticSbomService.pendingChangedComponents(List.of(b), T1));
	}
}
