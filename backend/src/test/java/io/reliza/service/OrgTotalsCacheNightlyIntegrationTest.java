/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;


import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.core.LogEvent;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.QueryTimeoutException;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.model.OrgTotalsTable;
import io.reliza.service.OrgTotalsCacheService.CountFailure;
import io.reliza.service.OrgTotalsCacheService.NightlyCountSummary;

/**
 * TPF-139 (architecture round 2, 6.6.1-6.6.3), store moved by TPF-141 (architecture round 2, 6.5.1-
 * 6.5.3): the nightly job counts the four cached tables of every listed organization after the
 * analytics pass and under its lock, into the organization's record data, with no revision and no
 * audit row; one failing count, an Exception or an Error, keeps that table's previous cell byte for
 * byte while the other tables count and stamp. The rig is {@link OrgTotalsCacheNightlyTestBase}.
 */
class OrgTotalsCacheNightlyIntegrationTest extends OrgTotalsCacheNightlyTestBase {

	/** 6.6.1 */
	@Test
	void theFirstRunWritesEveryListedOrganizationAfterTheAnalyticsPass() {
		UUID a = org();
		UUID b = org();
		fixtureA(a);
		assertTrue(totalsAbsent(a));
		assertTrue(totalsAbsent(b));
		List<Object> metaA = meta(a);
		List<Object> metaB = meta(b);
		long audits = auditRows(a, b);
		Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		schedulingService.computeAnalyticsMetrics();
		Instant t1 = Instant.now();
		assertEquals(metaA, meta(a), "the nightly stamp bumps no revision and no last_updated_date");
		assertEquals(metaB, meta(b), "the nightly stamp bumps no revision and no last_updated_date");
		assertEquals(audits, auditRows(a, b), "the nightly stamp writes no audit row");

		Map<String, Object> rowA = row(a);
		Map<String, Object> rowB = row(b);
		assertEquals(List.of(3L, 2L, 1L, 3L), counts(rowA), rowA.toString());
		assertEquals(List.of(0L, 0L, 0L, 0L), counts(rowB), rowB.toString());
		for (String t : TABLES) {
			assertWithin(at(rowA, t), t0, t1);
			assertWithin(at(rowB, t), t0, t1);
		}
		Map<String, Object> m = totals(a);
		assertEquals(List.of(3L, 2L, 1L, 3L), TABLES.stream().map(t -> ((Number) m.get(t)).longValue()).toList());
		assertWithin(Instant.parse((String) m.get("counted_at")), t0, t1);

		List<LogEvent> info = events(serviceLog, Level.INFO);
		assertEquals(1, info.size(), info.toString());
		assertTrue(info.get(0).getMessage().getFormattedMessage().contains("2 org(s) counted"),
				info.get(0).getMessage().getFormattedMessage());
		for (CapturingAppender appender : List.of(guardLog, serviceLog)) {
			assertEquals(List.of(), events(appender, Level.WARN));
			assertEquals(List.of(), events(appender, Level.ERROR));
		}

		InOrder order = inOrder(ossAnalyticsMetricsService, countRepository);
		order.verify(ossAnalyticsMetricsService, times(1)).computeAndRecordAnalyticsMetricsForAllOrgs();
		order.verify(countRepository, atLeastOnce()).count(any(), any());
		assertFalse(advisoryLockHeld(AdvisoryLockKey.COMPUTE_ANALYTICS_METRICS));
	}

	/** 6.6.2 */
	@Test
	void oneFailingCountKeepsItsValueAndTheOtherThreeAreFresh() {
		UUID a = org();
		UUID b = org();
		fixtureA(a);
		schedulingService.computeAnalyticsMetrics();
		clear(guardLog, serviceLog);
		insert("releases", a, "ACTIVE");
		Map<String, Object> before = row(a);
		Instant prev = at(before, "artifacts");
		String prevArtifacts = cellText(a, "artifacts");
		Instant prevB = at(row(b), "releases");
		QueryTimeoutException injected = new QueryTimeoutException("simulated statement timeout");
		doThrow(injected).when(countRepository).count(eq(OrgTotalsTable.ARTIFACTS), eq(a));

		schedulingService.computeAnalyticsMetrics();

		Map<String, Object> after = row(a);
		assertEquals(4L, after.get("releases"));
		assertTrue(at(after, "releases").isAfter(prev), after.toString());
		assertEquals(2L, after.get("artifacts"));
		assertEquals(prev, at(after, "artifacts"));
		assertEquals(prevArtifacts, cellText(a, "artifacts"), "the failed table's cell is byte-identical");
		assertTrue(at(after, "commits").isAfter(prev), after.toString());
		assertTrue(at(after, "deliverables").isAfter(prev), after.toString());
		assertEquals(DateTimeFormatter.ISO_INSTANT.format(prev), totals(a).get("counted_at"));

		List<LogEvent> errors = events(guardLog, Level.ERROR);
		assertEquals(1, errors.size(), errors.toString());
		String message = errors.get(0).getMessage().getFormattedMessage();
		assertTrue(message.contains("ARTIFACTS") && message.contains(a.toString()), message);
		assertSame(injected, errors.get(0).getThrown());
		List<LogEvent> warns = events(serviceLog, Level.WARN);
		assertEquals(1, warns.size(), warns.toString());
		String summaryLine = warns.get(0).getMessage().getFormattedMessage();
		assertTrue(summaryLine.contains("1 count(s) failed"), summaryLine);
		assertTrue(summaryLine.contains(new CountFailure(a, OrgTotalsTable.ARTIFACTS).toString()), summaryLine);

		Map<String, Object> rowB = row(b);
		assertEquals(List.of(0L, 0L, 0L, 0L), counts(rowB));
		for (String t : TABLES) {
			assertTrue(at(rowB, t).isAfter(prevB), rowB.toString());
		}
		assertFalse(advisoryLockHeld(AdvisoryLockKey.COMPUTE_ANALYTICS_METRICS));

		NightlyCountSummary summary = orgTotalsCacheService.countAllOrgs();
		assertEquals(2, summary.orgsCounted());
		assertEquals(List.of(new CountFailure(a, OrgTotalsTable.ARTIFACTS)), summary.failures());
	}

	/** 6.6.3 (a) and (b): an Error on one table is reported for that table alone. */
	@Test
	void anErrorOnTheFirstTableIsThatTablesAlone() {
		anErrorOnOneTableIsThatTablesAlone(OrgTotalsTable.RELEASES);
	}

	@Test
	void anErrorOnTheThirdTableIsThatTablesAlone() {
		anErrorOnOneTableIsThatTablesAlone(OrgTotalsTable.COMMITS);
	}

	private void anErrorOnOneTableIsThatTablesAlone(OrgTotalsTable poisoned) {
		UUID a = org();
		UUID b = org();
		fixtureA(a);
		Map<String, Object> before = cleanRun(a);
		String poisonedBefore = cellText(a, poisoned.getMapKey());
		Instant prevB = at(row(b), "releases");
		for (String t : TABLES) {
			insert(switch (t) {
				case "commits" -> "source_code_entries";
				default -> t;
			}, a, "ACTIVE");
		}
		StackOverflowError injected = new StackOverflowError("simulated");
		doAnswer(inv -> {
			throw injected;
		}).when(countRepository).count(eq(poisoned), eq(a));

		NightlyCountSummary summary = orgTotalsCacheService.countAllOrgs();

		assertEquals(List.of(new CountFailure(a, poisoned)), summary.failures());
		Map<String, Object> after = row(a);
		for (OrgTotalsTable table : OrgTotalsTable.values()) {
			String t = table.getMapKey();
			if (table == poisoned) {
				assertEquals(before.get(t), after.get(t), t);
				assertEquals(at(before, t), at(after, t), t);
				assertEquals(poisonedBefore, cellText(a, t), "the poisoned cell is byte-identical");
			} else {
				assertEquals((Long) before.get(t) + 1, after.get(t), t + " in " + after);
				assertTrue(at(after, t).isAfter(at(before, t)), t + " in " + after);
			}
		}
		Map<String, Object> rowB = row(b);
		for (String t : TABLES) {
			assertTrue(at(rowB, t).isAfter(prevB), rowB.toString());
		}
		List<LogEvent> errors = events(guardLog, Level.ERROR);
		assertEquals(1, errors.size(), errors.toString());
		String message = errors.get(0).getMessage().getFormattedMessage();
		assertTrue(message.contains(poisoned.name()) && message.contains(a.toString()), message);
		assertSame(injected, errors.get(0).getThrown());
		List<LogEvent> warns = events(serviceLog, Level.WARN);
		assertEquals(1, warns.size(), warns.toString());
		String summaryLine = warns.get(0).getMessage().getFormattedMessage();
		assertTrue(summaryLine.contains("1 count(s) failed"), summaryLine);
		assertTrue(summaryLine.contains(new CountFailure(a, poisoned).toString()), summaryLine);
		assertFalse(advisoryLockHeld(AdvisoryLockKey.COMPUTE_ANALYTICS_METRICS));
	}
}
