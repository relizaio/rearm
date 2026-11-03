/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.postgresql.PGStatement;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.ws.App;

/**
 * TPF-137 architecture 6.2 case 7 (D21) on the real driver and a pooled connection: the slice
 * statement carries the 120 s client timeout of every Hibernate statement and pgjdbc's
 * prepareThreshold 0, so it stays a one-shot statement, planned with its bound values on every
 * execution, and never becomes the named server-side statement whose generic plan reads the whole
 * containment of the array; a control statement with the default threshold does become one after
 * five executions. No plan assertion: the table is near-empty here (the plans are in the PR).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class FanOutWalkStatementIntegrationTest {

	@Autowired private DataSource dataSource;

	private static long namedStatements(Connection con, String like) throws SQLException {
		try (PreparedStatement ps = con.prepareStatement("SELECT count(*) FROM pg_prepared_statements WHERE statement LIKE ?")) {
			PGStatement pg = ps.unwrap(PGStatement.class);
			pg.setPrepareThreshold(0);
			ps.setString(1, like);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	private static void bind(PreparedStatement ps, Connection con) throws SQLException {
		ps.setObject(1, UUID.randomUUID());
		ps.setObject(2, new UUID(-1L, -1L));
		ps.setArray(3, con.createArrayOf("uuid", new UUID[] {UUID.randomUUID()}));
		ps.setObject(4, UUID.randomUUID());
		ps.setInt(5, 10);
	}

	@Test
	public void theSliceStatementIsAOneShotStatementWithTheClientTimeout() throws Exception {
		String marker = "control-" + UUID.randomUUID();
		try (Connection con = dataSource.getConnection()) {
			for (int i = 0; i < 6; i++) {
				try (PreparedStatement ps = FanOutWalkRepository.slicePreparedStatement(con, UUID.randomUUID(),
						UUID.randomUUID(), new UUID(-1L, -1L), new UUID[] {UUID.randomUUID()}, 10, 120_000L)) {
					assertEquals(120, ps.getQueryTimeout(), "the JPA query timeout, in seconds");
					assertEquals(0, ps.unwrap(PGStatement.class).getPrepareThreshold());
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) { /* drain */ }
					}
				}
			}
			assertEquals(0, namedStatements(con, "%ORDER BY ascx.canonical_artifact_uuid%LIMIT $5"),
					"six executions left no named server-side statement");

			String control = FanOutWalkRepository.CHANGED_IN_SLICE + " -- " + marker;
			try (PreparedStatement ps = con.prepareStatement(control)) {
				for (int i = 0; i < 6; i++) {
					bind(ps, con);
					try (ResultSet rs = ps.executeQuery()) {
						while (rs.next()) { /* drain */ }
					}
				}
				assertEquals(1, namedStatements(con, "%" + marker + "%"),
						"the default threshold names the statement after five executions");
			}
		}
	}

	@Test
	public void theFullModeStatementCarriesTheSameSettings() throws Exception {
		try (Connection con = dataSource.getConnection();
				PreparedStatement ps = FanOutWalkRepository.slicePreparedStatement(con, UUID.randomUUID(),
						UUID.randomUUID(), new UUID(-1L, -1L), null, 10, 120_000L)) {
			assertEquals(120, ps.getQueryTimeout());
			assertEquals(0, ps.unwrap(PGStatement.class).getPrepareThreshold());
			try (ResultSet rs = ps.executeQuery()) {
				while (rs.next()) { /* drain */ }
			}
		}
	}
}
