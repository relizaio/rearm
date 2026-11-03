/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Map;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import io.reliza.model.OrgTotalsTable;

/**
 * TPF-139: the nightly count of one table for one organization (VariableQueries.ORG_TOTALS_COUNT_*).
 *
 * <p>Plain JDBC rather than a Spring Data method (the FanOutWalkRepository precedent): Hibernate
 * applies the global jakarta.persistence.query.timeout of 120 s to every JPA query, which would
 * cancel a count that may legitimately run for minutes; a JdbcTemplate statement gets its own
 * client timeout instead. Run inside OrgTotalsCacheService.inNightlyCountTransaction: the template
 * works on the same DataSource, so it joins that transaction's connection and its SET LOCAL
 * settings (enable_seqscan off, jit off, the nightly statement_timeout) apply to the count.
 */
@Repository
public class OrgTotalsCountRepository {

	/** The server-side bound of one nightly count statement, a SET LOCAL statement_timeout text. */
	public static final String NIGHTLY_STATEMENT_TIMEOUT = "5min";

	/** The driver-side bound in seconds, above the server's so the server cancels first (SQLSTATE 57014). */
	public static final int NIGHTLY_CLIENT_TIMEOUT_SECONDS = 330;

	private final NamedParameterJdbcTemplate jdbc;

	public OrgTotalsCountRepository(DataSource dataSource) {
		JdbcTemplate template = new JdbcTemplate(dataSource);
		template.setQueryTimeout(NIGHTLY_CLIENT_TIMEOUT_SECONDS);
		this.jdbc = new NamedParameterJdbcTemplate(template);
	}

	/** One count of the organization, as the nightly statement of the table. */
	public long count(OrgTotalsTable table, UUID org) {
		String sql = switch (table) {
			case RELEASES -> VariableQueries.ORG_TOTALS_COUNT_RELEASES;
			case ARTIFACTS -> VariableQueries.ORG_TOTALS_COUNT_ARTIFACTS;
			case COMMITS -> VariableQueries.ORG_TOTALS_COUNT_COMMITS;
			case DELIVERABLES -> VariableQueries.ORG_TOTALS_COUNT_DELIVERABLES;
		};
		Long n = jdbc.queryForObject(sql, Map.of("orgUuidAsString", org.toString()), Long.class);
		return null == n ? 0L : n;
	}
}
