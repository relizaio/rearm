/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.RecordComponent;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;

/**
 * The assertions every in-place bookkeeping stamp is tested with (TPF-71 / TPF-105). A stamp is a
 * native jsonb_set keyed by uuid: no revision, no last_updated_date, no who-updated keys and no
 * audit row. Everything here reads through SQL, so no persistence context can answer for the row.
 *
 * <p>Children of TPF-71 reuse this class unchanged for any {@link TableName}; an array a stamp writes
 * whole also gets {@link #assertOnlyNestedKeyChanged} on a fixture that sets every component of its
 * element. A release's update_events column, which an append writes (TPF-106), is read with
 * {@link #updateEvents} and checked with {@link #assertAppendedOnly}.
 */
public final class StampAssertions {

	/** The four keys a whole-record save rewrites (WhoUpdated.injectWhoUpdatedIntoMap) and a stamp must leave alone. */
	public static final List<String> WHO_UPDATED_KEYS = List.of("lastUpdatedBy", "createdType", "lastUpdatedIp",
			"lastUpdatedActor");

	private StampAssertions() {}

	/** One row as the database holds it: revision, last_updated_date, its audit row count and record_data. */
	public record RowSnapshot(int revision, Timestamp lastUpdatedDate, int auditRows, Map<String, Object> recordData) {}

	@SuppressWarnings("unchecked")
	public static RowSnapshot snapshot(JdbcTemplate jdbc, TableName tn, UUID uuid) {
		Map<String, Object> row = jdbc.queryForMap("select revision, last_updated_date, cast(record_data as text) as rd from rearm."
				+ tn.toString() + " where uuid = ?", uuid);
		Integer audit = jdbc.queryForObject("select count(*) from rearm.audit where entity_name = ? and entity_uuid = ?",
				Integer.class, tn.toString(), uuid);
		Map<String, Object> recordData = Utils.OM.readValue((String) row.get("rd"), Map.class);
		return new RowSnapshot(((Number) row.get("revision")).intValue(), (Timestamp) row.get("last_updated_date"),
				null == audit ? 0 : audit, recordData);
	}

	/**
	 * A stamp: revision, last_updated_date, the audit count and the who-updated keys unchanged; every
	 * top-level key outside {@code stampedKeys} unchanged; each stamped key present after.
	 */
	public static void assertStampedOnly(RowSnapshot before, RowSnapshot after, Set<String> stampedKeys) {
		assertEquals(before.revision(), after.revision(), "a stamp is not a revision");
		assertEquals(before.lastUpdatedDate(), after.lastUpdatedDate(), "a stamp does not move last_updated_date");
		assertEquals(before.auditRows(), after.auditRows(), "a stamp writes no audit row");
		for (String k : WHO_UPDATED_KEYS) {
			assertEquals(before.recordData().get(k), after.recordData().get(k), "a stamp leaves the who-updated key " + k);
		}
		Set<String> keys = new TreeSet<>(before.recordData().keySet());
		keys.addAll(after.recordData().keySet());
		keys.removeAll(stampedKeys);
		List<String> moved = keys.stream()
				.filter(k -> !Objects.equals(before.recordData().get(k), after.recordData().get(k)))
				.toList();
		assertTrue(moved.isEmpty(), "the stamp changed keys outside " + stampedKeys + ": " + moved);
		for (String k : stampedKeys) {
			assertTrue(after.recordData().containsKey(k), "the stamped key " + k + " is present after the stamp");
		}
	}

	/**
	 * The stored JSON type of a top-level key, as the database names it ("number", "boolean", "string",
	 * "object", "array", "null"): a stamp writes numbers and booleans with to_jsonb, so a value written as text
	 * would still read back through Jackson but not through a filter on the stored value.
	 */
	public static void assertJsonType(JdbcTemplate jdbc, TableName tn, UUID uuid, String key, String expected) {
		String actual = jdbc.queryForObject("select jsonb_typeof(record_data->?) from rearm." + tn.toString() + " where uuid = ?",
				String.class, key, uuid);
		assertEquals(expected, actual, tn + "." + key + " is stored as a JSON " + actual + ", not a " + expected);
	}

	/** A content save: one revision, one audit row, and a later last_updated_date. */
	public static void assertContentSave(RowSnapshot before, RowSnapshot after) {
		assertEquals(before.revision() + 1, after.revision(), "a content save is one revision");
		assertEquals(before.auditRows() + 1, after.auditRows(), "a content save writes one audit row");
		assertTrue(after.lastUpdatedDate().after(before.lastUpdatedDate()),
				"a content save moves last_updated_date: " + before.lastUpdatedDate() + " -> " + after.lastUpdatedDate());
	}

	/**
	 * The element guard for an array a stamp writes whole: same length and order, every element equal
	 * once {@code nestedKey} is removed, and {@code nestedKey} changed exactly at {@code changedIndexes}
	 * (so the pin cannot pass on a stamp that wrote nothing).
	 */
	public static void assertOnlyNestedKeyChanged(RowSnapshot before, RowSnapshot after, String arrayKey,
			String nestedKey, Set<Integer> changedIndexes) {
		List<Map<String, Object>> was = elements(before, arrayKey);
		List<Map<String, Object>> is = elements(after, arrayKey);
		assertEquals(was.size(), is.size(), arrayKey + " changed length");
		for (int i = 0; i < was.size(); i++) {
			Map<String, Object> b = new LinkedHashMap<>(was.get(i));
			Map<String, Object> a = new LinkedHashMap<>(is.get(i));
			b.remove(nestedKey);
			a.remove(nestedKey);
			assertEquals(b, a, arrayKey + "[" + i + "] changed outside " + nestedKey + ": " + differingKeys(b, a));
		}
		for (int i = 0; i < was.size(); i++) {
			Object x = was.get(i).get(nestedKey);
			Object y = is.get(i).get(nestedKey);
			if (changedIndexes.contains(i)) {
				assertNotEquals(x, y, arrayKey + "[" + i + "]." + nestedKey + " was expected to change");
			} else {
				assertEquals(x, y, arrayKey + "[" + i + "]." + nestedKey + " was expected to stay");
			}
		}
	}

	/**
	 * A release's update_events column as the database holds it (TPF-106): update_events is a column,
	 * not a record_data key, so {@link #assertStampedOnly} cannot see it. SQL NULL and JSON null read
	 * as an empty list.
	 */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> updateEvents(JdbcTemplate jdbc, UUID release) {
		String text = jdbc.queryForObject("select cast(update_events as text) from rearm.releases where uuid = ?",
				String.class, release);
		if (null == text) return new ArrayList<>();
		List<Map<String, Object>> events = Utils.OM.readValue(text, List.class);
		return null == events ? new ArrayList<>() : new ArrayList<>(events);
	}

	/**
	 * An in-place append of {@code appended} update events (TPF-106): {@link #assertStampedOnly} with no
	 * stamped key (record_data, revision, last_updated_date, the audit count and the who-updated keys
	 * unchanged), exactly {@code appended} more events, and the existing events unchanged and in order
	 * ({@link #assertPrefixKept}). With {@code appended = 0} nothing at all was written.
	 */
	public static void assertAppendedOnly(RowSnapshot before, RowSnapshot after, List<Map<String, Object>> eventsBefore,
			List<Map<String, Object>> eventsAfter, int appended) {
		assertStampedOnly(before, after, Set.of());
		assertEquals(eventsBefore.size() + appended, eventsAfter.size(), "events appended");
		assertPrefixKept(eventsBefore, eventsAfter);
	}

	/** The events that were in the row are still its first events, unchanged and in order (TPF-106). */
	public static void assertPrefixKept(List<Map<String, Object>> eventsBefore, List<Map<String, Object>> eventsAfter) {
		assertTrue(eventsAfter.size() >= eventsBefore.size(),
				"update_events shrank: " + eventsBefore.size() + " -> " + eventsAfter.size());
		assertEquals(eventsBefore, eventsAfter.subList(0, eventsBefore.size()),
				"the existing events are unchanged and in order");
	}

	/** The elements of a top-level array of {@code snapshot}, as maps; a missing key is an empty list. */
	@SuppressWarnings("unchecked")
	public static List<Map<String, Object>> elements(RowSnapshot snapshot, String arrayKey) {
		Object v = snapshot.recordData().get(arrayKey);
		return null == v ? List.of() : new ArrayList<>((List<Map<String, Object>>) v);
	}

	public static List<Map<String, Object>> signOffsOf(RowSnapshot snapshot) {
		return elements(snapshot, "signOffs");
	}

	public static List<Map<String, Object>> returnsOf(RowSnapshot snapshot) {
		return elements(snapshot, "returns");
	}

	/** A record's component names, which are its JSON keys when no component is renamed. */
	public static Set<String> componentNames(Class<? extends Record> c) {
		return Arrays.stream(c.getRecordComponents()).map(RecordComponent::getName).collect(Collectors.toSet());
	}

	private static List<String> differingKeys(Map<String, Object> b, Map<String, Object> a) {
		Set<String> union = new TreeSet<>(b.keySet());
		union.addAll(a.keySet());
		return union.stream().filter(k -> !Objects.equals(b.get(k), a.get(k))).toList();
	}
}
