/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;


import org.junit.jupiter.api.Test;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.model.OrganizationData;
import io.reliza.model.WhoUpdated;
import io.reliza.service.OrgTotalsCacheService.NightlyCountSummary;

/**
 * TPF-139 (architecture round 2, 6.6.4-6.6.6), store moved by TPF-141 (architecture round 2, 6.5.4-
 * 6.5.7): an archived organization is skipped and its record left; a second scheduler that finds the
 * lock taken does nothing; an organization created after a run is pending until the next; a
 * whole-record save between two nights keeps the counts. The rig is
 * {@link OrgTotalsCacheNightlyTestBase}.
 */
class OrgTotalsCacheNightlyLifecycleIntegrationTest extends OrgTotalsCacheNightlyTestBase {

	/** 6.6.4 */
	@Test
	void anArchivedOrganizationIsSkippedAndItsRowLeft() {
		UUID a = org();
		UUID c = org();
		insert("releases", c, "ACTIVE");
		cleanRun();
		Map<String, Object> before = row(c);
		String beforeText = totalsText(c);
		assertEquals(4, before.values().stream().filter(v -> v instanceof OffsetDateTime).count(), before.toString());
		jdbc.update("UPDATE rearm.organizations SET record_data = jsonb_set(record_data, '{status}', '\"ARCHIVED\"')"
				+ " WHERE uuid = ?", c);
		insert("releases", c, "ACTIVE");

		NightlyCountSummary summary = orgTotalsCacheService.countAllOrgs();

		assertEquals(before, row(c));
		assertEquals(beforeText, totalsText(c), "the archived organization's totals are byte-identical");
		assertEquals("ARCHIVED", jdbc.queryForObject("SELECT record_data->>'status' FROM rearm.organizations WHERE uuid = ?",
				String.class, c));
		assertEquals(1, summary.orgsCounted());
		assertFalse(organizationService.listAllOrganizationData().stream().anyMatch(od -> c.equals(od.getUuid())));
		assertEquals(4, counts(row(a)).stream().filter(n -> 0L == n).count());
	}

	/** 6.6.5 */
	@Test
	void aSecondSchedulerThatFindsTheLockTakenDoesNothing() throws SQLException {
		UUID d = org();
		long key = AdvisoryLockKey.COMPUTE_ANALYTICS_METRICS.getQueryVal();
		try (Connection other = dataSource.getConnection()) {
			try (PreparedStatement lock = other.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
				lock.setLong(1, key);
				try (ResultSet rs = lock.executeQuery()) {
					rs.next();
					assertTrue(rs.getBoolean(1));
				}
			}
			schedulingService.computeAnalyticsMetrics();
			assertEquals(Map.of(), row(d));
			verify(ossAnalyticsMetricsService, never()).computeAndRecordAnalyticsMetricsForAllOrgs();
			verify(countRepository, never()).count(any(), any());
			try (PreparedStatement unlock = other.prepareStatement("SELECT pg_advisory_unlock(?)")) {
				unlock.setLong(1, key);
				unlock.executeQuery().close();
			}
		}
		assertFalse(advisoryLockHeld(AdvisoryLockKey.COMPUTE_ANALYTICS_METRICS));

		schedulingService.computeAnalyticsMetrics();
		Map<String, Object> first = row(d);
		assertEquals(List.of(0L, 0L, 0L, 0L), counts(first));
		assertFalse(advisoryLockHeld(AdvisoryLockKey.COMPUTE_ANALYTICS_METRICS));

		doAnswer(inv -> {
			throw new StackOverflowError("simulated analytics failure");
		}).when(ossAnalyticsMetricsService).computeAndRecordAnalyticsMetricsForAllOrgs();
		schedulingService.computeAnalyticsMetrics();
		Map<String, Object> second = row(d);
		for (String t : TABLES) {
			assertTrue(at(second, t).isAfter(at(first, t)), second.toString());
		}
		assertFalse(advisoryLockHeld(AdvisoryLockKey.COMPUTE_ANALYTICS_METRICS));
	}

	/** 6.6.6 */
	@Test
	void anOrganizationCreatedAfterARunIsPendingUntilTheNext() {
		org();
		schedulingService.computeAnalyticsMetrics();
		UUID e = org();
		Map<String, Object> pending = totals(e);
		for (String t : TABLES) {
			assertTrue(pending.containsKey(t), t);
			assertNull(pending.get(t), t);
		}
		assertTrue(pending.containsKey("counted_at"));
		assertNull(pending.get("counted_at"));

		schedulingService.computeAnalyticsMetrics();
		Map<String, Object> counted = totals(e);
		assertEquals(List.of(0L, 0L, 0L, 0L), TABLES.stream().map(t -> ((Number) counted.get(t)).longValue()).toList());
		assertNotNull(counted.get("counted_at"));
	}

	/** TPF-141 6.5.7: a whole-record save between two nights keeps the counts; the next night re-stamps. */
	@Test
	void aSettingsSaveBetweenTwoNightsKeepsTheCounts() throws Exception {
		UUID a = org();
		fixtureA(a);
		Map<String, Object> first = cleanRun(a);
		String stamped = totalsText(a);
		int revision = (Integer) meta(a).get(0);
		OrganizationData.Settings patch = new OrganizationData.Settings();
		patch.setJustificationMandatory(true);

		organizationService.updateSettings(a, patch, WhoUpdated.getTestWhoUpdated());

		assertEquals(stamped, totalsText(a), "the save writes the stored totals back byte for byte");
		assertEquals(revision + 1, meta(a).get(0));

		Map<String, Object> second = cleanRun(a);
		assertEquals(counts(first), counts(second));
		for (String t : TABLES) {
			assertTrue(at(second, t).isAfter(at(first, t)), second.toString());
		}
		assertEquals("true", jdbc.queryForObject(
				"SELECT record_data->'settings'->>'justificationMandatory' FROM rearm.organizations WHERE uuid = ?",
				String.class, a));
	}
}
