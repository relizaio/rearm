/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.ws.App;

/**
 * SCORE-52 T-28 (ADR-8, F-2), replacing SCORE-47's migration test: the indexes rule B's reads use.
 * Each plan check EXPLAINs the {@link VariableQueries} constant itself, its named parameters
 * replaced by quoted random uuids, under {@code enable_seqscan = off} on one connection, so the test
 * follows the query text. The V95 migration is asserted by description and index name, never by
 * number.
 */
@SpringBootTest(classes = {App.class})
class SbomUpdateGuardIndexTest {

	private static final String V95_INDEX = "idx_artifacts_org_internal_bom";
	private static final Pattern NAMED_PARAMETER = Pattern.compile(":([A-Za-z][A-Za-z0-9]*)");

	@Autowired private JdbcTemplate jdbc;
	@Autowired private ReleaseRebomRepository releaseRebomRepository;

	/** {@code query} with every named parameter replaced by a quoted random uuid. */
	private static String bound(String query) {
		Matcher m = NAMED_PARAMETER.matcher(query);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			m.appendReplacement(sb, "'" + UUID.randomUUID() + "'");
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private String plan(String query) {
		String sql = bound(query);
		assertFalse(NAMED_PARAMETER.matcher(sql.replace("::", "")).find(), sql);
		return jdbc.execute((ConnectionCallback<String>) con -> {
			try (Statement st = con.createStatement()) {
				st.execute("SET enable_seqscan = off");
				try (ResultSet rs = st.executeQuery("EXPLAIN (FORMAT JSON) " + sql)) {
					rs.next();
					return rs.getString(1);
				} finally {
					st.execute("RESET enable_seqscan");
				}
			}
		});
	}

	@Test
	void theV95MigrationAppliedOnceAndTheIndexIsPartialOnBom() {
		List<Map<String, Object>> applied = jdbc.queryForList("SELECT version, success FROM public.flyway_schema_history"
				+ " WHERE description = 'artifacts internal bom index'");
		assertEquals(1, applied.size(), applied.toString());
		assertEquals(Boolean.TRUE, applied.get(0).get("success"), applied.toString());
		List<String> defs = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = 'rearm'"
				+ " AND tablename = 'artifacts' AND indexname = ?", String.class, V95_INDEX);
		assertEquals(1, defs.size(), defs.toString());
		String def = defs.get(0);
		assertTrue(def.contains("record_data ->> 'org'"), def);
		assertTrue(def.contains("internalBom"), def);
		assertTrue(def.contains("WHERE ((record_data ->> 'type'::text) = 'BOM'::text)"), def);
	}

	/**
	 * Like {@link #plan}, for the realistic shape of the serial-number lookup: an organization that already
	 * holds {@value #SEEDED_BOMS} BOM artifacts looks up one serial. Bound to a random organization that holds
	 * nothing, as {@link #plan} would do, the planner scores V95 and artifacts_org_fanout_pool_idx within 0.01
	 * of each other, and with a few hundred BOM rows in the table V67's partial index on created_date wins
	 * outright; which one a run got depended on what the database held when this ran, and since each surefire
	 * fork has a database of its own CI saw V67 chosen. With this organization's rows V95 costs about half of
	 * the next best index. The rows and the ANALYZE that makes the planner see them are rolled back.
	 */
	private String planForAnOrgWithManyBoms(String query) {
		String org = UUID.randomUUID().toString();
		String sql = bound(query.replaceAll(":org\\b", "'" + org + "'"));
		return jdbc.execute((ConnectionCallback<String>) con -> {
			boolean autoCommit = con.getAutoCommit();
			con.setAutoCommit(false);
			try (Statement st = con.createStatement()) {
				st.execute("INSERT INTO rearm.artifacts (record_data) SELECT jsonb_build_object('type', 'BOM', 'org', '" + org
						+ "', 'internalBom', jsonb_build_object('id', gen_random_uuid()::text))"
						+ " FROM generate_series(1, " + SEEDED_BOMS + ")");
				st.execute("ANALYZE rearm.artifacts");
				st.execute("SET LOCAL enable_seqscan = off");
				try (ResultSet rs = st.executeQuery("EXPLAIN (FORMAT JSON) " + sql)) {
					rs.next();
					return rs.getString(1);
				}
			} finally {
				con.rollback();
				con.setAutoCommit(autoCommit);
			}
		});
	}

	private static final int SEEDED_BOMS = 2000;

	@Test
	void theSerialNumberLookupUsesTheV95Index() {
		String query = VariableQueries.FIND_BOM_ARTIFACTS_BY_ORG_AND_INTERNAL_BOM;
		assertTrue(query.contains(":org"), query);
		String plan = planForAnOrgWithManyBoms(query);
		assertTrue(plan.contains(V95_INDEX), plan);
	}

	@Test
	void theContainerLookupUsesTheRecordDataGin() {
		String plan = plan(VariableQueries.FIND_ARTIFACTS_CONTAINING_ARTIFACT);
		assertTrue(plan.contains("idx_artifacts_record_data_gin"), plan);
	}

	@Test
	void theReleaseHolderLookupUsesTheRecordDataGin() {
		String plan = plan(VariableQueries.FIND_RELEASES_HOLDING_ARTIFACT);
		assertTrue(plan.contains("idx_releases_record_data_gin"), plan);
	}

	@Test
	void theSceHolderQueryUsesTheArtifactsGin() {
		String plan = plan(VariableQueries.FIND_SCE_ARTIFACT_HOLDERS);
		assertTrue(plan.contains("idx_source_code_entries_artifacts_gin")
				|| plan.contains("source_code_entries_artifacts_gin_idx"), plan);
	}

	@Test
	void releaseRebomExistsListingRebomIdFindsOnlyAMatchingRebomId() {
		UUID m = UUID.randomUUID();
		UUID other = UUID.randomUUID();
		UUID listing = UUID.randomUUID();
		UUID notListing = UUID.randomUUID();
		String insert = "INSERT INTO rearm.release_reboms (uuid, record_data) VALUES (?, cast(? as jsonb))";
		jdbc.update(insert, listing, "{\"reboms\":[{\"rebomId\":\"" + UUID.randomUUID() + "\"},{\"rebomId\":\"" + m
				+ "\"},{\"rebomId\":\"" + UUID.randomUUID() + "\"}]}");
		jdbc.update(insert, notListing, "{\"reboms\":[{\"rebomId\":\"" + other + "\"}]}");
		try {
			assertTrue(releaseRebomRepository.existsListingRebomId(m.toString()));
			assertTrue(releaseRebomRepository.existsListingRebomId(other.toString()));
			assertFalse(releaseRebomRepository.existsListingRebomId(UUID.randomUUID().toString()));
		} finally {
			jdbc.update("DELETE FROM rearm.release_reboms WHERE uuid IN (?, ?)", listing, notListing);
		}
	}
}
