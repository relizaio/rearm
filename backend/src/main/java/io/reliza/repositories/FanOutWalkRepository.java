/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.postgresql.PGStatement;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The slice statements of the change-driven synthetic fan-out walk (TPF-137). Each reads one
 * fixed slice of the canonical uuid space (cursor exclusive, sliceEnd inclusive) from
 * artifact_sbom_components in canonical order, as an ordered range scan of an index led by
 * canonical_artifact_uuid with the component array as a filter; DISTINCT and ORDER BY are on the
 * same column, so a streaming Unique under the LIMIT stops at the room-th distinct canonical and
 * never reads past the slice end. No index is added for it (operator constraint).
 *
 * <p>Plain JDBC rather than a Spring Data method because two settings must reach the driver's
 * statement: the client timeout (a JdbcTemplate statement does not get Hibernate's
 * jakarta.persistence.query.timeout) and pgjdbc's prepareThreshold 0. With the default threshold
 * the fifth execution on a connection becomes a named server-side statement whose plan cache may
 * switch to the generic plan, which knows neither the range nor the array and, measured on
 * PostgreSQL 16, reads every containment row of every array element in the whole estate per
 * statement. A one-shot statement is planned at Bind with the bound values on every execution.
 */
@Repository
public class FanOutWalkRepository {

	static final String CHANGED_IN_SLICE = """
			SELECT DISTINCT ascx.canonical_artifact_uuid
			FROM rearm.artifact_sbom_components ascx
			WHERE ascx.canonical_artifact_uuid > ?
			  AND ascx.canonical_artifact_uuid <= ?
			  AND ascx.sbom_component_uuid = ANY(?)
			  AND ascx.org = ?
			ORDER BY ascx.canonical_artifact_uuid
			LIMIT ?
			""";

	/** Full mode (the changed set is above the cap): every canonical of the slice. */
	static final String ALL_IN_SLICE = """
			SELECT DISTINCT ascx.canonical_artifact_uuid
			FROM rearm.artifact_sbom_components ascx
			WHERE ascx.canonical_artifact_uuid > ?
			  AND ascx.canonical_artifact_uuid <= ?
			  AND ascx.org = ?
			ORDER BY ascx.canonical_artifact_uuid
			LIMIT ?
			""";

	private final JdbcTemplate jdbcTemplate;
	private final long queryTimeoutMs;

	public FanOutWalkRepository(JdbcTemplate jdbcTemplate,
			@Value("${spring.jpa.properties.jakarta.persistence.query.timeout:120000}") long queryTimeoutMs) {
		this.jdbcTemplate = jdbcTemplate;
		this.queryTimeoutMs = queryTimeoutMs;
	}

	/** The org's canonicals in (cursor, sliceEnd] holding any of the changed components, ascending, at most room. */
	public List<UUID> findChangedCanonicalsInSlice(UUID org, UUID cursor, UUID sliceEnd, UUID[] changed, int room) {
		return jdbcTemplate.query(
				con -> slicePreparedStatement(con, org, cursor, sliceEnd, changed, room, queryTimeoutMs),
				(rs, i) -> rs.getObject(1, UUID.class));
	}

	/** Full mode: every canonical of the org in (cursor, sliceEnd], ascending, at most room. */
	public List<UUID> findCanonicalsInSlice(UUID org, UUID cursor, UUID sliceEnd, int room) {
		return jdbcTemplate.query(
				con -> slicePreparedStatement(con, org, cursor, sliceEnd, null, room, queryTimeoutMs),
				(rs, i) -> rs.getObject(1, UUID.class));
	}

	/**
	 * Prepares one slice statement: the changed-component form, or the full-mode form when
	 * {@code changed} is null; binds the uuids as uuid and the array as uuid[]; sets the client
	 * timeout and the one-shot prepare threshold. Package-private so a test can inspect it.
	 */
	static PreparedStatement slicePreparedStatement(Connection con, UUID org, UUID cursor, UUID sliceEnd,
			UUID[] changed, int room, long queryTimeoutMs) throws SQLException {
		PreparedStatement ps = con.prepareStatement(null == changed ? ALL_IN_SLICE : CHANGED_IN_SLICE);
		int i = 1;
		ps.setObject(i++, cursor);
		ps.setObject(i++, sliceEnd);
		if (null != changed) ps.setArray(i++, con.createArrayOf("uuid", changed));
		ps.setObject(i++, org);
		ps.setInt(i, room);
		ps.setQueryTimeout((int) (queryTimeoutMs / 1000));
		ps.unwrap(PGStatement.class).setPrepareThreshold(0);
		return ps;
	}
}
