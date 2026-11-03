/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;

import org.mockito.stubbing.Answer;

import io.reliza.common.Utils;
import io.reliza.model.BucketFlowControl;
import io.reliza.model.SyntheticDtrackBucket;
import io.reliza.model.SyntheticDtrackBucket.IngestState;
import io.reliza.repositories.SbomComponentRepository.ComponentUuidByPurl;

/**
 * Fixtures shared by the Mockito rigs of the change-driven fan-out walk (TPF-137): recorded and
 * unrecorded buckets, uuids placed in a chosen slice, PostgreSQL's unsigned uuid order, and an
 * answer for the slice statements that serves a sorted universe of canonicals as the database
 * would (greater than the cursor, at most the slice end, up to the room).
 */
final class FanOutWalkTestSupport {

	static final String COVERED = "pkg:npm/covered@1";

	private static final Random RANDOM = new Random();

	private FanOutWalkTestSupport() {}

	/** Now minus the given minutes, truncated to microseconds: the precision every bucket date has. */
	static ZonedDateTime minutesAgo(int minutes) {
		return ZonedDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS).minusMinutes(minutes);
	}

	static String iso(ZonedDateTime t) {
		return DateTimeFormatter.ISO_INSTANT.format(t.toInstant());
	}

	/**
	 * An INGESTED bucket covering {@link #COVERED}, recorded at its date (recordedAt equal to it, the
	 * shape every save of the TPF-137 code leaves), with a pending entry at that date for each
	 * changed component.
	 */
	static SyntheticDtrackBucket recordedBucket(UUID org, int index, ZonedDateTime date, UUID... changed) {
		Map<UUID, ZonedDateTime> entries = new LinkedHashMap<>();
		for (UUID c : changed) entries.put(c, date);
		return recordedBucket(org, index, date, entries);
	}

	/** A recorded INGESTED bucket with the given pending entries. */
	static SyntheticDtrackBucket recordedBucket(UUID org, int index, ZonedDateTime date, Map<UUID, ZonedDateTime> entries) {
		SyntheticDtrackBucket b = new SyntheticDtrackBucket();
		b.setOrg(org);
		b.setBucketIndex(index);
		b.setIngestState(IngestState.INGESTED);
		b.setFindings(new HashMap<>());
		b.getRefMap().put(COVERED, COVERED);
		b.setLastUpdatedDate(date);
		record(b, date, entries);
		return b;
	}

	/** Sets the bucket's date and records it at that date with the given pending entries. */
	static void record(SyntheticDtrackBucket b, ZonedDateTime date, Map<UUID, ZonedDateTime> entries) {
		Map<String, String> pending = new LinkedHashMap<>();
		entries.forEach((c, at) -> pending.put(c.toString(), iso(at)));
		b.setLastUpdatedDate(date);
		b.setFlowControl(new BucketFlowControl(pending, iso(date)));
	}

	/** An INGESTED bucket no save of the TPF-137 code reached: no flow_control. */
	static SyntheticDtrackBucket unrecordedBucket(UUID org, int index, ZonedDateTime date) {
		SyntheticDtrackBucket b = recordedBucket(org, index, date);
		b.setFlowControl(null);
		return b;
	}

	/** An INGESTED bucket whose recordedAt differs from its date (older or newer): unrecorded too. */
	static SyntheticDtrackBucket unrecordedBucket(UUID org, int index, ZonedDateTime date, String recordedAt) {
		SyntheticDtrackBucket b = recordedBucket(org, index, date);
		b.setFlowControl(new BucketFlowControl(new LinkedHashMap<>(), recordedAt));
		return b;
	}

	/** A random version-4-shaped uuid whose top byte is the given slice. */
	static UUID inSlice(int slice) {
		UUID r = UUID.randomUUID();
		long msb = (r.getMostSignificantBits() & 0x00FFFFFFFFFFFFFFL) | (((long) slice) << 56);
		return new UUID(msb, r.getLeastSignificantBits());
	}

	/** n uuids in the given slice, sorted as PostgreSQL sorts them. */
	static List<UUID> sortedInSlice(int slice, int n) {
		List<UUID> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(inSlice(slice));
		out.sort(FanOutWalkTestSupport::compareUnsigned);
		return out;
	}

	/** n uuids spread over random slices, sorted as PostgreSQL sorts them. */
	static List<UUID> sortedUniverse(int n) {
		List<UUID> out = new ArrayList<>(n);
		for (int i = 0; i < n; i++) out.add(inSlice(RANDOM.nextInt(FanOutWalkSlice.SLICES)));
		out.sort(FanOutWalkTestSupport::compareUnsigned);
		return out;
	}

	/** PostgreSQL's uuid order: the 16 bytes compared unsigned (UUID.compareTo compares signed longs). */
	static int compareUnsigned(UUID a, UUID b) {
		int c = Long.compareUnsigned(a.getMostSignificantBits(), b.getMostSignificantBits());
		return c != 0 ? c : Long.compareUnsigned(a.getLeastSignificantBits(), b.getLeastSignificantBits());
	}

	/** The universe's uuids in (cursor, sliceEnd], at most room, in order. */
	static List<UUID> page(List<UUID> universe, UUID cursor, UUID sliceEnd, int room) {
		List<UUID> out = new ArrayList<>();
		for (UUID u : universe) {
			if (out.size() >= room) break;
			if (compareUnsigned(u, cursor) > 0 && compareUnsigned(u, sliceEnd) <= 0) out.add(u);
		}
		return out;
	}

	/** An answer for findChangedCanonicalsInSlice(org, cursor, sliceEnd, changed, room) over a universe. */
	static Answer<List<UUID>> changedSliceAnswer(List<UUID> universe) {
		return inv -> page(universe, inv.getArgument(1), inv.getArgument(2), inv.getArgument(4));
	}

	/** The stamp check answering its whole batch: nothing stamped at or after the drain's cutoff. */
	@SuppressWarnings("unchecked")
	static Answer<List<UUID>> everyCanonicalNeedsFanOut() {
		return inv -> new ArrayList<>((Collection<UUID>) inv.getArgument(1));
	}

	/** One row of SbomComponentRepository.findUuidsByOrgAndCanonicalPurlIn. */
	record PurlRow(UUID uuid, String canonicalPurl) implements ComponentUuidByPurl {
		@Override public UUID getUuid() { return uuid; }
		@Override public String getCanonicalPurl() { return canonicalPurl; }
	}

	/** findUuidsByOrgAndCanonicalPurlIn(org, purls) as the query answers it: the rows of the purls that resolve. */
	@SuppressWarnings("unchecked")
	static Answer<List<ComponentUuidByPurl>> purlsResolveTo(Map<String, UUID> resolved) {
		return inv -> {
			List<ComponentUuidByPurl> out = new ArrayList<>();
			for (String p : (Collection<String>) inv.getArgument(1)) {
				if (resolved.containsKey(p)) out.add(new PurlRow(resolved.get(p), p));
			}
			return out;
		};
	}

	/** A stampFlowControl argument read back. */
	static BucketFlowControl parseFlowControl(String json) {
		return Utils.OM.readValue(json, BucketFlowControl.class);
	}

	/** A clock that lets exactly one slice statement run: reads 1 (the deadline) and 2 (the first check) are 0. */
	static LongSupplier oneStatementClock(AtomicInteger reads) {
		return () -> reads.incrementAndGet() <= 2 ? 0L : Long.MAX_VALUE / 2;
	}
}
