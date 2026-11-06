/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.apache.logging.log4j.Level;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.internal.sqlscript.DefaultSqlScriptExecutor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.common.MigrationNoticeLogger;
import io.reliza.ws.tea.LogEventCapture;

/**
 * FDAW-47 (architecture round 2, section 4 rows 1 to 3, 6 and 7): a migration's server messages
 * reach the log. The convention for data migrations it pins: counts and identifiers a migration
 * reports are {@code RAISE NOTICE} lines, logged at INFO as {@code migration V<n> notice: <text>}
 * by {@link MigrationNoticeLogger} and as Flyway's {@code DB: <text>} by
 * {@code org.flywaydb.core.internal.sqlscript.DefaultSqlScriptExecutor}; {@code RAISE WARNING} is for
 * a condition the operator should act on and is logged at WARN on both loggers; a notice carries
 * counts and row uuids, never a credential, a person's name or e-mail, or user-written text; the
 * session admits notices because {@code spring.flyway.init-sqls} in {@code backend/application.yaml}
 * runs {@code SET client_min_messages TO NOTICE} on every connection Flyway opens.
 *
 * <p>The probe runs its own Flyway in the schema {@code fdaw47_probe} with its own history table,
 * from {@code classpath:db/fdaw47-probe} (outside the application's {@code db/migration}), as
 * {@link FlywayFailedNonTransactionalMigrationHistoryTest} does, so the application's history is
 * never read or written. The {@code client_min_messages} probe uses fresh driver connections, not
 * the pool: a {@code SET} on a pooled connection would stay on it.
 */
@SpringBootTest(classes = {App.class})
class FlywayMigrationNoticeLogTest {

	private static final String SCHEMA = "fdaw47_probe";
	private static final String NOTICE_TEXT = "fdaw47 probe: 3 rows migrated";
	private static final String WARNING_TEXT = "fdaw47 probe warning";

	@Autowired private DataSource dataSource;
	@Autowired private JdbcTemplate jdbc;
	@Autowired private Flyway flyway;

	@Value("${spring.datasource.url}") private String url;
	@Value("${spring.datasource.username}") private String username;
	@Value("${spring.datasource.password}") private String password;

	private static FluentConfiguration probe(FluentConfiguration conf) {
		return conf.schemas(SCHEMA).locations("classpath:db/fdaw47-probe").callbacks(new MigrationNoticeLogger());
	}

	private Flyway probeFlyway() {
		return probe(Flyway.configure().dataSource(dataSource)).load();
	}

	private Flyway freshConnectionProbeFlyway(String initSql) {
		return probe(Flyway.configure().dataSource(url, username, password)).initSql(initSql).load();
	}

	private boolean markerExists() {
		return null != jdbc.queryForObject("SELECT to_regclass('fdaw47_probe.marker')::text", String.class);
	}

	private void assertProbeMigrated(Flyway probe) {
		assertTrue(markerExists(), "the probe migration ran");
		assertEquals("1", probe.info().current().getVersion().getVersion());
	}

	@AfterEach
	void dropProbeSchema() {
		jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
	}

	@Test
	void everyServerMessageOfAMigrationStatementIsLoggedWithTheMigrationNumberBesideFlywaysOwnLine() {
		Flyway probe = probeFlyway();
		try (LogEventCapture app = LogEventCapture.of(MigrationNoticeLogger.class, Level.INFO);
				LogEventCapture db = LogEventCapture.of(DefaultSqlScriptExecutor.class, Level.INFO)) {
			probe.migrate();

			// row 1: the application's line, once per message; the CREATE TABLE raised nothing
			assertEquals(List.of("migration V1 notice: " + NOTICE_TEXT), app.messages(Level.INFO));
			List<String> appWarn = app.messages(Level.WARN);
			assertEquals(1, appWarn.size(), appWarn.toString());
			assertTrue(appWarn.get(0).startsWith("migration V1 warning: " + WARNING_TEXT + " (SQL State 01000"),
					appWarn.get(0));
			assertTrue(app.withThrowable().isEmpty(), "no stack trace");

			// row 2: Flyway's own line stays, under the logger the log4j2 files configure
			assertTrue(db.messages(Level.INFO).contains("DB: " + NOTICE_TEXT), db.messages(Level.INFO).toString());
			assertTrue(db.messages(Level.WARN).stream()
					.anyMatch(m -> m.startsWith("DB: " + WARNING_TEXT + " (SQL State: 01000")),
					db.messages(Level.WARN).toString());
		}
		assertProbeMigrated(probe);
	}

	@Test
	void theAutoConfiguredFlywayCarriesTheCallbackAndItHandlesOnlyStatementEvents() {
		assertTrue(Arrays.stream(flyway.getConfiguration().getCallbacks()).anyMatch(c -> c instanceof MigrationNoticeLogger),
				"Spring hands the @Component callback to the auto-configured Flyway");
		MigrationNoticeLogger callback = new MigrationNoticeLogger();
		assertTrue(callback.supports(Event.AFTER_EACH_MIGRATE_STATEMENT, null));
		assertTrue(callback.supports(Event.AFTER_EACH_MIGRATE_STATEMENT_ERROR, null));
		assertFalse(callback.supports(Event.AFTER_MIGRATE, null));
		assertFalse(callback.supports(Event.CREATE_SCHEMA, null));
		assertTrue(callback.canHandleInTransaction(Event.AFTER_EACH_MIGRATE_STATEMENT, null));
	}

	@Test
	void theApplicationYamlInitSqlsReachesTheAutoConfiguredFlyway() {
		assertEquals("SET client_min_messages TO NOTICE", flyway.getConfiguration().getInitSql());
	}

	@Test
	void flywaysInitSqlGovernsWhetherTheServerSendsTheNotice() {
		Flyway suppressed = freshConnectionProbeFlyway("SET client_min_messages TO WARNING");
		try (LogEventCapture app = LogEventCapture.of(MigrationNoticeLogger.class, Level.INFO);
				LogEventCapture db = LogEventCapture.of(DefaultSqlScriptExecutor.class, Level.INFO)) {
			suppressed.migrate();
			assertEquals(List.of(), app.messages(Level.INFO), "the server did not send the NOTICE");
			assertEquals(List.of(), db.messages(Level.INFO), "the server did not send the NOTICE");
			assertTrue(app.messages(Level.WARN).stream().anyMatch(m -> m.startsWith("migration V1 warning: " + WARNING_TEXT)),
					app.messages(Level.WARN).toString());
			assertTrue(db.messages(Level.WARN).stream().anyMatch(m -> m.startsWith("DB: " + WARNING_TEXT)),
					db.messages(Level.WARN).toString());
		}
		assertProbeMigrated(suppressed);
		dropProbeSchema();

		Flyway admitted = freshConnectionProbeFlyway("SET client_min_messages TO NOTICE");
		try (LogEventCapture app = LogEventCapture.of(MigrationNoticeLogger.class, Level.INFO);
				LogEventCapture db = LogEventCapture.of(DefaultSqlScriptExecutor.class, Level.INFO)) {
			admitted.migrate();
			assertEquals(List.of("migration V1 notice: " + NOTICE_TEXT), app.messages(Level.INFO));
			assertTrue(db.messages(Level.INFO).contains("DB: " + NOTICE_TEXT), db.messages(Level.INFO).toString());
		}
		assertProbeMigrated(admitted);
	}
}
