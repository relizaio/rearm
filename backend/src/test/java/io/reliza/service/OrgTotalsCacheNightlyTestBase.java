/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.sql.DataSource;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import io.reliza.common.AdvisoryLockKey;
import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.SchedulerGuard;
import io.reliza.common.Utils;
import io.reliza.model.OrganizationData;
import io.reliza.repositories.OrgTotalsCountRepository;
import io.reliza.service.OrgTotalsCacheService.NightlyCountSummary;
import io.reliza.service.oss.OssAnalyticsMetricsService;
import io.reliza.ws.App;
import io.reliza.ws.SchedulingConfig;
import io.reliza.ws.oss.TestInitializer;

/**
 * The shared rig of the nightly totals integration tests (TPF-139 architecture round 2, 6.6; store
 * moved by TPF-141 architecture round 2, 6.5): the spies, the narrowed listing (only the test's own
 * organizations), the analytics pass stubbed out (doNothing), the two log appenders, the fixture
 * inserts and the readers of the stored totals cell by cell. The SchedulerIsolationTest idioms.
 */
@SpringBootTest(classes = {App.class})
@TestPropertySource(properties = SchedulingConfig.SCHEDULING_ENABLED_PROPERTY + "=false")
abstract class OrgTotalsCacheNightlyTestBase {

	protected static final List<String> TABLES = List.of("releases", "artifacts", "commits", "deliverables");

	@MockitoSpyBean protected OrganizationService organizationService;
	@MockitoSpyBean protected OssAnalyticsMetricsService ossAnalyticsMetricsService;
	@MockitoSpyBean protected OrgTotalsCountRepository countRepository;

	@Autowired protected SchedulingService schedulingService;
	@Autowired protected OrgTotalsCacheService orgTotalsCacheService;
	@Autowired protected JdbcTemplate jdbc;
	@Autowired protected DataSource dataSource;
	@Autowired protected TestInitializer testInitializer;
	@Autowired protected GetOrganizationService getOrganizationService;

	/** In-memory appender: the level, the message and the throwable, nothing else. */
	protected static class CapturingAppender extends AbstractAppender {
		private final List<LogEvent> events = Collections.synchronizedList(new LinkedList<>());
		CapturingAppender() { super("capture-" + UUID.randomUUID(), null, null, true, Property.EMPTY_ARRAY); }
		@Override public void append(LogEvent event) { events.add(event.toImmutable()); }
	}

	protected CapturingAppender guardLog;
	protected CapturingAppender serviceLog;

	/** The organizations the narrowed listing returns, in the real listing's order. */
	protected final Set<UUID> ours = Collections.synchronizedSet(new HashSet<>());
	/** Rows inserted by the fixture, as table and uuid, for the cleanup. */
	protected final List<String[]> inserted = new ArrayList<>();

	@BeforeEach
	void setUp() {
		guardLog = attach(SchedulerGuard.class);
		serviceLog = attach(OrgTotalsCacheService.class);
		doAnswer(inv -> {
			@SuppressWarnings("unchecked")
			List<OrganizationData> all = (List<OrganizationData>) inv.callRealMethod();
			return all.stream().filter(od -> ours.contains(od.getUuid())).collect(Collectors.toCollection(ArrayList::new));
		}).when(organizationService).listAllOrganizationData();
		doNothing().when(ossAnalyticsMetricsService).computeAndRecordAnalyticsMetricsForAllOrgs();
	}

	@AfterEach
	void tearDown() {
		detach(SchedulerGuard.class, guardLog);
		detach(OrgTotalsCacheService.class, serviceLog);
		for (String[] row : inserted) {
			jdbc.update("DELETE FROM rearm." + row[0] + " WHERE uuid = cast(? as uuid)", row[1]);
		}
		inserted.clear();
		ours.clear();
	}

	protected static CapturingAppender attach(Class<?> loggerOwner) {
		CapturingAppender appender = new CapturingAppender();
		appender.start();
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerOwner)).addAppender(appender);
		return appender;
	}

	protected static void detach(Class<?> loggerOwner, CapturingAppender appender) {
		((org.apache.logging.log4j.core.Logger) LogManager.getLogger(loggerOwner)).removeAppender(appender);
		appender.stop();
	}

	/** The events of {@code appender} at {@code level} whose message contains "org totals". */
	protected static List<LogEvent> events(CapturingAppender appender, Level level) {
		synchronized (appender.events) {
			return appender.events.stream()
					.filter(e -> e.getLevel() == level && e.getMessage().getFormattedMessage().contains("org totals"))
					.toList();
		}
	}

	protected static void clear(CapturingAppender... appenders) {
		for (CapturingAppender appender : appenders) {
			appender.events.clear();
		}
	}

	/** Whether any session holds the lock, read from pg_locks (the SchedulerIsolationTest helper). */
	protected boolean advisoryLockHeld(AdvisoryLockKey alk) {
		String sql = """
				SELECT EXISTS (SELECT 1 FROM pg_locks
				               WHERE locktype = 'advisory' AND granted AND objsubid = 1
				                 AND database = (SELECT oid FROM pg_database WHERE datname = current_database())
				                 AND ((classid::bigint << 32) | objid::bigint) = ?)
				""";
		try (Connection conn = dataSource.getConnection();
				PreparedStatement stmt = conn.prepareStatement(sql)) {
			stmt.setLong(1, alk.getQueryVal());
			try (ResultSet rs = stmt.executeQuery()) {
				rs.next();
				return rs.getBoolean(1);
			}
		} catch (SQLException e) {
			throw new IllegalStateException(e);
		}
	}

	protected UUID org() {
		UUID uuid = testInitializer.obtainOrganization().getUuid();
		ours.add(uuid);
		return uuid;
	}

	protected void insert(String table, UUID org, String status) {
		UUID uuid = UUID.randomUUID();
		String doc = "{\"org\":\"" + org + "\"" + (null == status ? "" : ",\"status\":\"" + status + "\"") + "}";
		jdbc.update("INSERT INTO rearm." + table + " (uuid, record_data) VALUES (?, cast(? as jsonb))", uuid, doc);
		inserted.add(new String[] {table, uuid.toString()});
	}

	/** Organization A of row 1: releases 3 ACTIVE + 1 ARCHIVED, artifacts 2, commits 1, deliverables 2 ACTIVE + 1 without status. */
	protected void fixtureA(UUID a) {
		for (String status : List.of("ACTIVE", "ACTIVE", "ACTIVE", "ARCHIVED")) {
			insert("releases", a, status);
		}
		insert("artifacts", a, null);
		insert("artifacts", a, null);
		insert("source_code_entries", a, null);
		insert("deliverables", a, "ACTIVE");
		insert("deliverables", a, "ACTIVE");
		insert("deliverables", a, null);
	}

	/**
	 * The stored totals of {@code org}, read from its record data: per present cell the count and
	 * the instant, or an empty map when the record has no totals key.
	 */
	protected Map<String, Object> row(UUID org) {
		Map<String, Object> out = new LinkedHashMap<>();
		String text = totalsText(org);
		if (null == text) return out;
		@SuppressWarnings("unchecked")
		Map<String, Object> totals = Utils.OM.readValue(text, LinkedHashMap.class);
		for (String t : TABLES) {
			if (!totals.containsKey(t)) continue;
			@SuppressWarnings("unchecked")
			Map<String, Object> cell = (Map<String, Object>) totals.get(t);
			Object count = cell.get("count");
			Object countedAt = cell.get("countedAt");
			out.put(t, null == count ? null : ((Number) count).longValue());
			out.put(t + "_counted_at", null == countedAt ? null : OffsetDateTime.parse((String) countedAt));
		}
		return out;
	}

	/** The stored totals key of {@code org} as jsonb text, or null when the key is absent. */
	protected String totalsText(UUID org) {
		return jdbc.queryForObject("SELECT (record_data->'totals')::text FROM rearm.organizations WHERE uuid = ?",
				String.class, org);
	}

	/** One stored cell of {@code org} as jsonb text. */
	protected String cellText(UUID org, String table) {
		return jdbc.queryForObject("SELECT (record_data->'totals'->CAST(? AS text))::text FROM rearm.organizations WHERE uuid = ?",
				String.class, table, org);
	}

	protected boolean totalsAbsent(UUID org) {
		return jdbc.queryForObject("SELECT record_data->'totals' IS NULL FROM rearm.organizations WHERE uuid = ?",
				Boolean.class, org);
	}

	/** Revision and last-updated date of the organization row. */
	protected List<Object> meta(UUID org) {
		return jdbc.queryForObject("SELECT revision, last_updated_date FROM rearm.organizations WHERE uuid = ?",
				(rs, n) -> List.of(rs.getInt(1), rs.getObject(2, OffsetDateTime.class)), org);
	}

	protected long auditRows(UUID... orgs) {
		long total = 0;
		for (UUID org : orgs) {
			total += jdbc.queryForObject("SELECT count(*) FROM rearm.audit WHERE entity_uuid = ? AND entity_name = ?",
					Long.class, org, TableName.ORGANIZATIONS.toString());
		}
		return total;
	}

	/** The totalsAnalytics map of {@code org}, from the record loaded once (the datafetcher's shape). */
	protected Map<String, Object> totals(UUID org) {
		return organizationService.getNumericAnalytics(getOrganizationService.getOrganizationData(org).orElseThrow());
	}

	protected static List<Long> counts(Map<String, Object> row) {
		return TABLES.stream().map(t -> (Long) row.get(t)).toList();
	}

	protected static Instant at(Map<String, Object> row, String table) {
		OffsetDateTime at = (OffsetDateTime) row.get(table + "_counted_at");
		assertNotNull(at, table + " counted_at in " + row);
		return at.toInstant();
	}

	protected static void assertWithin(Instant at, Instant t0, Instant t1) {
		Instant ms = at.truncatedTo(ChronoUnit.MILLIS);
		assertFalse(ms.isBefore(t0), at + " before " + t0);
		assertFalse(ms.isAfter(t1), at + " after " + t1);
	}

	protected Map<String, Object> cleanRun(UUID... orgs) {
		NightlyCountSummary summary = orgTotalsCacheService.countAllOrgs();
		assertEquals(List.of(), summary.failures());
		clear(guardLog, serviceLog);
		return orgs.length > 0 ? row(orgs[0]) : Map.of();
	}

}
