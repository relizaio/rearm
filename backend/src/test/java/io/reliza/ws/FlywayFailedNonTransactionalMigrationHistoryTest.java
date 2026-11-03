/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.MigrationState;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * TPF-131 (architecture round 6, 6.2 row 11): the bookkeeping V103's self-healing rests on, with the
 * real Flyway jars. A non-transactional script (its {@code .sql.conf} says
 * {@code executeInTransaction=false}) whose second statement fails leaves its first statement
 * committed and, under Flyway's default PostgreSQL transactional advisory lock, NO history row, so
 * the next run re-runs it; under the session lock it leaves a {@code success = false} row that the
 * next migrate refuses. The probe runs in its own schema {@code tpf131_probe} with its own history
 * table, from {@code classpath:db/tpf131-probe} (outside the application's {@code db/migration}),
 * so the application's history is never read or written.
 */
@SpringBootTest(classes = {App.class})
class FlywayFailedNonTransactionalMigrationHistoryTest {

	private static final String SCHEMA = "tpf131_probe";

	@Autowired private DataSource dataSource;
	@Autowired private JdbcTemplate jdbc;

	private Flyway probeFlyway() {
		return Flyway.configure().dataSource(dataSource).schemas(SCHEMA)
				.locations("classpath:db/tpf131-probe").load();
	}

	private boolean markerExists() {
		return null != jdbc.queryForObject("SELECT to_regclass('tpf131_probe.marker')::text", String.class);
	}

	private int historyRowsOfVersionOne() {
		return jdbc.queryForObject("SELECT count(*) FROM tpf131_probe.flyway_schema_history WHERE version = '1'",
				Integer.class);
	}

	@AfterEach
	void dropProbeSchema() {
		jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
	}

	@Test
	void underTheDefaultLockAFailedRunLeavesNoHistoryRow() {
		Flyway flyway = probeFlyway();
		PostgreSQLConfigurationExtension ext = flyway.getConfiguration().getPluginRegister()
				.getExact(PostgreSQLConfigurationExtension.class);
		assertNotNull(ext);
		assertTrue(ext.isTransactionalLock(), "the default is the transactional advisory lock");

		assertThrows(FlywayException.class, flyway::migrate);

		assertTrue(markerExists(), "the first statement committed: the script ran non-transactionally");
		assertEquals(0, historyRowsOfVersionOne(), "no history row for the failed version");
		MigrationInfo[] pending = flyway.info().pending();
		assertEquals(1, pending.length, Arrays.toString(pending));
		assertEquals("1", pending[0].getVersion().getVersion(), "the next run re-runs it");
		assertFalse(Arrays.stream(flyway.info().all()).anyMatch(i -> i.getState().isFailed()),
				"nothing is recorded as failed");
	}

	@Test
	void withTheSessionLockAFailedRunLeavesAFailedRow() {
		Flyway flyway = probeFlyway();
		flyway.getConfiguration().getPluginRegister().getExact(PostgreSQLConfigurationExtension.class)
				.setTransactionalLock(false);

		assertThrows(FlywayException.class, flyway::migrate);

		assertTrue(markerExists(), "the first statement committed");
		assertEquals(1, historyRowsOfVersionOne());
		assertEquals(Boolean.FALSE, jdbc.queryForObject(
				"SELECT success FROM tpf131_probe.flyway_schema_history WHERE version = '1'", Boolean.class));
		List<MigrationState> states = Arrays.stream(flyway.info().all())
				.filter(i -> i.getVersion() != null && "1".equals(i.getVersion().getVersion()))
				.map(MigrationInfo::getState).toList();
		assertEquals(List.of(MigrationState.FAILED), states);
		FlywayException refused = assertThrows(FlywayException.class, flyway::migrate);
		assertTrue(refused.getMessage().contains("Detected failed migration to version 1"), refused.getMessage());
	}
}
