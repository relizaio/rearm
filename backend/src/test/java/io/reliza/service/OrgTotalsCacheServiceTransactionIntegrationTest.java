/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.model.OrgTotalsTable;
import io.reliza.repositories.OrgTotalsCountRepository;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;
import jakarta.persistence.EntityManager;

/**
 * TPF-139 (architecture round 2, 6.5): the nightly count wrapper of {@link OrgTotalsCacheService}
 * is in force on the server and gone after it; a JdbcTemplate statement inside it runs on the
 * wrapper's connection and settings (the fact the plain JDBC count rests on); a server timeout
 * surfaces as the exception the nightly loop contains and leaves the connection clean. The
 * {@code OrganizationServiceTotalsTransactionIntegrationTest} shape, in this package because the
 * wrapper is package-private. TPF-141 (architecture round 2, 6.3): the in-place stamp of one cell
 * of the organization's record data on the real stack, and the locked read of the guard.
 */
@SpringBootTest(classes = {App.class})
class OrgTotalsCacheServiceTransactionIntegrationTest {

	@Autowired private OrgTotalsCacheService orgTotalsCacheService;
	@Autowired private OrgTotalsCountRepository orgTotalsCountRepository;
	@Autowired private EntityManager entityManager;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private OrganizationRepository organizationRepository;
	@Autowired private OrganizationService organizationService;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private TestInitializer testInitializer;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private DataSource dataSource;

	private static final String T_RELEASES = "2026-11-02T00:00:03.512417Z";

	private String show(String setting) {
		return (String) entityManager.createNativeQuery("SHOW " + setting).getSingleResult();
	}

	private List<String> defaultsThroughTheTemplate() {
		return List.of(jdbc.queryForObject("SHOW enable_seqscan", String.class),
				jdbc.queryForObject("SHOW jit", String.class),
				jdbc.queryForObject("SHOW statement_timeout", String.class),
				jdbc.queryForObject("SHOW transaction_read_only", String.class));
	}

	/** 6.5.1 */
	@Test
	void theSettingsHoldInsideTheWrapperAndRevertAfterIt() {
		List<String> inside = orgTotalsCacheService.inNightlyCountTransaction(() -> List.of(show("enable_seqscan"),
				show("jit"), show("statement_timeout"), show("transaction_read_only")));
		assertEquals(List.of("off", "off", "5min", "on"), inside);
		assertEquals(List.of("on", "on", "0", "off"), defaultsThroughTheTemplate());
	}

	/** 6.5.2 */
	@Test
	void theTemplateJoinsTheWrapperTransaction() {
		List<Object> inside = orgTotalsCacheService.inNightlyCountTransaction(() -> List.of(
				jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class),
				((Number) entityManager.createNativeQuery("SELECT pg_backend_pid()").getSingleResult()).intValue(),
				jdbc.queryForObject("SHOW statement_timeout", String.class)));
		assertEquals(inside.get(0), inside.get(1), inside.toString());
		assertEquals("5min", inside.get(2));
	}

	/** 6.5.3 */
	@Test
	void aServerTimeoutIsAQueryTimeoutAndLeavesTheConnectionClean() {
		DataAccessException thrown = assertThrows(DataAccessException.class,
				() -> orgTotalsCacheService.inNightlyCountTransaction(() -> {
					jdbc.execute("SET LOCAL statement_timeout = '50ms'");
					return jdbc.queryForObject("SELECT count(*) FROM pg_sleep(1)", Long.class);
				}));
		assertInstanceOf(QueryTimeoutException.class, thrown, thrown.toString());
		SQLException sql = null;
		for (Throwable t = thrown; null != t; t = t.getCause()) {
			if (t instanceof SQLException e) {
				sql = e;
			}
		}
		assertNotNull(sql, thrown.toString());
		assertEquals("57014", sql.getSQLState(), sql.toString());
		assertEquals(List.of("on", "on", "0", "off"), defaultsThroughTheTemplate());
	}

	/** 6.5.4 */
	@Test
	void anOrganizationWithNoRowsCountsZero() {
		assertEquals(0L, orgTotalsCountRepository.count(OrgTotalsTable.RELEASES, UUID.randomUUID()));
	}

	// ---------------------------------------------------------------- TPF-141 6.3

	private UUID freshOrg() {
		return testInitializer.obtainOrganization().getUuid();
	}

	private boolean totalsAbsent(UUID org) {
		return jdbc.queryForObject("SELECT record_data->'totals' IS NULL FROM rearm.organizations WHERE uuid = ?",
				Boolean.class, org);
	}

	private String totalsText(UUID org) {
		return jdbc.queryForObject("SELECT record_data->>'totals' FROM rearm.organizations WHERE uuid = ?",
				String.class, org);
	}

	private String cellText(UUID org, String table) {
		return jdbc.queryForObject("SELECT (record_data->'totals'->CAST(? AS text))::text FROM rearm.organizations WHERE uuid = ?",
				String.class, table, org);
	}

	private Map<String, Object> totals(UUID org) {
		return organizationService.getNumericAnalytics(getOrganizationService.getOrganizationData(org).orElseThrow());
	}

	private Optional<String> lockedTotals(UUID org) {
		return new TransactionTemplate(transactionManager).execute(s -> organizationRepository.currentTotalsForUpdate(org));
	}

	/** 6.3.1: the first stamp creates the key; a second table adds a cell; a re-stamp replaces one cell whole. */
	@Test
	void theFirstStampCreatesTheKeyAndLaterStampsReplaceOneCell() {
		UUID org = freshOrg();
		assertTrue(totalsAbsent(org));

		assertEquals(1, organizationRepository.stampTotal(org, "releases", 3L, T_RELEASES));
		assertEquals("{\"releases\": {\"count\": 3, \"countedAt\": \"" + T_RELEASES + "\"}}", totalsText(org));
		String releasesCell = cellText(org, "releases");

		assertEquals(1, organizationRepository.stampTotal(org, "artifacts", 0L, "2026-11-02T00:00:01.250Z"));
		assertEquals(releasesCell, cellText(org, "releases"));
		assertEquals("{\"count\": 0, \"countedAt\": \"2026-11-02T00:00:01.250Z\"}", cellText(org, "artifacts"));
		String artifactsCell = cellText(org, "artifacts");

		assertEquals(1, organizationRepository.stampTotal(org, "releases", 4L, "2026-11-03T00:00:03.6Z"));
		assertEquals("{\"count\": 4, \"countedAt\": \"2026-11-03T00:00:03.6Z\"}", cellText(org, "releases"));
		assertEquals(artifactsCell, cellText(org, "artifacts"));

		Map<String, Object> m = totals(org);
		assertEquals(4L, m.get("releases"));
		assertEquals(0L, m.get("artifacts"));
		assertNull(m.get("commits"));
		assertNull(m.get("deliverables"));
		assertEquals("2026-11-02T00:00:01.250Z", m.get("counted_at"));
	}

	/** 6.3.2: a full countAndStampOrg is bookkeeping: no revision, no last-updated date, no audit row. */
	@Test
	void theStampIsBookkeeping() {
		UUID org = freshOrg();
		String metaSql = "SELECT revision, last_updated_date, record_data->>'name', record_data->>'status'"
				+ " FROM rearm.organizations WHERE uuid = ?";
		String auditSql = "SELECT count(*) FROM rearm.audit WHERE entity_uuid = ? AND entity_name = ?";
		List<Object> before = jdbc.queryForObject(metaSql, (rs, n) -> Arrays.asList(rs.getInt(1),
				rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getString(4)), org);
		long auditsBefore = jdbc.queryForObject(auditSql, Long.class, org, TableName.ORGANIZATIONS.toString());
		Instant t0 = Instant.now().truncatedTo(ChronoUnit.MILLIS);

		assertEquals(List.of(), orgTotalsCacheService.countAndStampOrg(org));
		Instant t1 = Instant.now();

		List<Object> after = jdbc.queryForObject(metaSql, (rs, n) -> Arrays.asList(rs.getInt(1),
				rs.getObject(2, OffsetDateTime.class), rs.getString(3), rs.getString(4)), org);
		assertEquals(before, after);
		assertEquals(auditsBefore, jdbc.queryForObject(auditSql, Long.class, org, TableName.ORGANIZATIONS.toString()));
		for (OrgTotalsTable table : OrgTotalsTable.values()) {
			String key = table.getMapKey();
			assertEquals("0", jdbc.queryForObject("SELECT record_data->'totals'->CAST(? AS text)->>'count'"
					+ " FROM rearm.organizations WHERE uuid = ?", String.class, key, org), key);
			String countedAt = jdbc.queryForObject("SELECT record_data->'totals'->CAST(? AS text)->>'countedAt'"
					+ " FROM rearm.organizations WHERE uuid = ?", String.class, key, org);
			assertNotNull(countedAt, key);
			assertTrue(countedAt.endsWith("Z"), countedAt);
			assertTrue(countedAt.length() <= 27, countedAt);
			Instant at = Instant.parse(countedAt).truncatedTo(ChronoUnit.MILLIS);
			assertFalse(at.isBefore(t0), countedAt + " before " + t0);
			assertFalse(at.isAfter(t1), countedAt + " after " + t1);
		}
	}

	/** 6.3.3: a stored JSON null is repaired to an object holding the stamped cell. */
	@Test
	void aJsonNullTotalsBecomesAnObject() {
		UUID org = freshOrg();
		jdbc.update("UPDATE rearm.organizations SET record_data = jsonb_set(record_data, '{totals}', 'null'::jsonb)"
				+ " WHERE uuid = ?", org);

		assertEquals(1, organizationRepository.stampTotal(org, "commits", 2L, T_RELEASES));

		assertEquals("object", jdbc.queryForObject("SELECT jsonb_typeof(record_data->'totals') FROM rearm.organizations"
				+ " WHERE uuid = ?", String.class, org));
		assertEquals("{\"commits\": {\"count\": 2, \"countedAt\": \"" + T_RELEASES + "\"}}", totalsText(org));
		assertEquals(2L, totals(org).get("commits"));
	}

	/** 6.3.4: a vanished row stamps nothing; all four tables fail and the test's own organization is unchanged. */
	@Test
	void aVanishedRowIsFourFailuresAndTouchesNoOtherOrganization() {
		UUID org = freshOrg();
		assertEquals(0, organizationRepository.stampTotal(UUID.randomUUID(), "releases", 1L, T_RELEASES));
		String sql = "SELECT revision, last_updated_date, record_data::text FROM rearm.organizations WHERE uuid = ?";
		List<Object> before = jdbc.queryForObject(sql, (rs, n) -> List.of(rs.getInt(1),
				rs.getObject(2, OffsetDateTime.class), rs.getString(3)), org);

		assertEquals(List.of(OrgTotalsTable.RELEASES, OrgTotalsTable.ARTIFACTS, OrgTotalsTable.COMMITS,
				OrgTotalsTable.DELIVERABLES), orgTotalsCacheService.countAndStampOrg(UUID.randomUUID()));

		assertEquals(before, jdbc.queryForObject(sql, (rs, n) -> List.of(rs.getInt(1),
				rs.getObject(2, OffsetDateTime.class), rs.getString(3)), org));
	}

	/** 6.3.5: the guard's read: empty, the stored text, empty on a JSON null; and the row is locked. */
	@Test
	void theLockedReadAnswersTheStoredTextAndLocksTheRow() throws Exception {
		UUID org = freshOrg();
		assertEquals(Optional.empty(), lockedTotals(org));
		organizationRepository.stampTotal(org, "releases", 3L, T_RELEASES);
		assertEquals(Optional.of(totalsText(org)), lockedTotals(org));

		UUID nulled = freshOrg();
		jdbc.update("UPDATE rearm.organizations SET record_data = jsonb_set(record_data, '{totals}', 'null'::jsonb)"
				+ " WHERE uuid = ?", nulled);
		assertEquals(Optional.empty(), lockedTotals(nulled));

		String nowait = "SELECT 1 FROM rearm.organizations WHERE uuid = ? FOR UPDATE NOWAIT";
		new TransactionTemplate(transactionManager).executeWithoutResult(s -> {
			organizationRepository.currentTotalsForUpdate(org);
			try (Connection other = dataSource.getConnection(); PreparedStatement stmt = other.prepareStatement(nowait)) {
				stmt.setObject(1, org);
				SQLException refused = assertThrows(SQLException.class, stmt::executeQuery);
				assertEquals("55P03", refused.getSQLState(), refused.toString());
			} catch (SQLException e) {
				throw new IllegalStateException(e);
			}
		});
		try (Connection other = dataSource.getConnection(); PreparedStatement stmt = other.prepareStatement(nowait)) {
			stmt.setObject(1, org);
			stmt.executeQuery().close();
		}
	}
}
