/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.reliza.common.CommonVariables;
import io.reliza.common.Utils;
import io.reliza.model.OrgTotalsTable;
import io.reliza.model.OrganizationData;
import io.reliza.ws.App;
import tools.jackson.databind.JsonNode;

/**
 * TPF-70 (architecture round 2, test 4.1): the org totals indexes exist as designed and the
 * Statistics totals statements take them under {@code enable_seqscan = off}, the setting their
 * production transactions run with. TPF-139 (architecture round 2, 6.2 and 6.8) split the former
 * nine-count statement into the live five ({@link VariableQueries#GET_NUMERIC_ANALYTICS_LIVE_FOR_ORG})
 * and the four nightly counts ({@code ORG_TOTALS_COUNT_*}); each is planned here and held to its
 * text. The plan check EXPLAINs the constant itself (named parameters replaced by quoted random
 * uuids, the {@code SbomUpdateGuardIndexTest} idiom), so the test follows the query text. The
 * migration is asserted by description and index name, never by number. TPF-141 (architecture
 * round 2, 6.8): the dropped org totals cache migration, its table and its entity are absent, by
 * description and name, never by version number; the cached counts live under the totals key of
 * the organization record.
 */
@SpringBootTest(classes = {App.class})
class OrgTotalsIndexTest {

	private static final Pattern NAMED_PARAMETER = Pattern.compile(":([A-Za-z][A-Za-z0-9]*)");

	private static final List<String[]> INDEXES = List.of(
			new String[] {"releases_org_status_idx", "releases"},
			new String[] {"deliverables_org_status_idx", "deliverables"},
			new String[] {"source_code_entries_org_idx", "source_code_entries"},
			new String[] {"components_org_idx", "components"},
			new String[] {"branches_org_idx", "branches"});

	/** The four tables the live statement must never touch (TPF-139). */
	private static final List<String> CACHED_TABLES = List.of("releases", "artifacts", "source_code_entries",
			"deliverables");

	private static final List<String> CACHED_TABLES_KEYS = List.of("releases", "artifacts", "commits", "deliverables");

	/** A named parameter glued to the next token (the TPF-139 round 1 text-block trap). */
	private static final Pattern GLUED_PARAMETER = Pattern.compile(":orgUuidAsString(?![\\s)])");

	private static final String ACTIVE_ORG_PREDICATE =
			"where record_data->>'org' = :orgUuidAsString and coalesce(record_data->>'status', 'ACTIVE') = 'ACTIVE'";
	private static final String BRANCH_ACTIVE_ORG_PREDICATE =
			"where b.record_data->>'org' = :orgUuidAsString and coalesce(b.record_data->>'status', 'ACTIVE') = 'ACTIVE'";

	/**
	 * The four derived tables of the former nine-count statement (TPF-70, VariableQueries at
	 * rearm-saas a833ed59), white space normalised, each with its alias dropped.
	 */
	private static final String LEGACY_RELEASES_SUBQUERY = "select count(*) from rearm.releases"
			+ " where record_data->>'org' = :orgUuidAsString and coalesce(record_data->>'status', 'ACTIVE') = 'ACTIVE'";
	private static final String LEGACY_ARTIFACTS_SUBQUERY =
			"select count(*) from rearm.artifacts where record_data->>'org' = :orgUuidAsString";
	private static final String LEGACY_COMMITS_SUBQUERY =
			"select count(*) from rearm.source_code_entries where record_data->>'org' = :orgUuidAsString";
	private static final String LEGACY_DELIVERABLES_SUBQUERY = "select count(*) from rearm.deliverables"
			+ " where record_data->>'org' = :orgUuidAsString and coalesce(record_data->>'status', 'ACTIVE') = 'ACTIVE'";

	@Autowired private JdbcTemplate jdbc;

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
	void theOrgTotalsMigrationAppliedOnce() {
		List<Map<String, Object>> applied = jdbc.queryForList("SELECT version, success FROM public.flyway_schema_history"
				+ " WHERE description = 'org totals indexes'");
		assertEquals(1, applied.size(), applied.toString());
		assertEquals(Boolean.TRUE, applied.get(0).get("success"), applied.toString());
	}

	@Test
	void theFiveIndexesArePlainBtreesOnTheOrgExpression() {
		for (String[] index : INDEXES) {
			List<String> defs = jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = 'rearm'"
					+ " AND tablename = ? AND indexname = ?", String.class, index[1], index[0]);
			assertEquals(1, defs.size(), index[0] + " " + defs);
			String def = defs.get(0);
			assertTrue(def.contains("record_data ->> 'org'"), def);
			assertFalse(def.contains("WHERE"), def);
			assertFalse(def.toLowerCase().contains("gin"), def);
			if (index[0].endsWith("_org_status_idx")) {
				assertTrue(def.contains("COALESCE((record_data ->> 'status'"), def);
				assertTrue(def.contains("'ACTIVE'"), def);
			}
		}
	}

	/** TPF-139 6.2.2: the live five take their index paths and never touch the four cached tables. */
	@Test
	void theLiveTotalsStatementTakesTheIndexPaths() {
		String plan = plan(VariableQueries.GET_NUMERIC_ANALYTICS_LIVE_FOR_ORG);
		for (String name : List.of("components_org_idx", "branches_org_idx", "components_pkey",
				"vcs_repositories_org_uri_unique")) {
			assertTrue(plan.contains(name), name + " missing from " + plan);
		}
		assertFalse(plan.contains("SubPlan"), plan);
		assertFalse(plan.contains("\"Node Type\": \"Seq Scan\""), plan);
		for (String table : CACHED_TABLES) {
			assertFalse(plan.contains("\"Relation Name\": \"" + table + "\""), table + " in " + plan);
		}
	}

	/** TPF-139 6.2.3: each nightly count on its V98 index; releases and deliverables with the status in the Index Cond. */
	@Test
	void theNightlyCountsTakeTheIndexPaths() {
		assertStatusInIndexCond(plan(VariableQueries.ORG_TOTALS_COUNT_RELEASES), "releases", "releases_org_status_idx");
		assertStatusInIndexCond(plan(VariableQueries.ORG_TOTALS_COUNT_DELIVERABLES), "deliverables",
				"deliverables_org_status_idx");
		String commits = plan(VariableQueries.ORG_TOTALS_COUNT_COMMITS);
		assertTrue(commits.contains("source_code_entries_org_idx"), commits);
		assertFalse(commits.contains("Seq Scan"), commits);
		String artifacts = plan(VariableQueries.ORG_TOTALS_COUNT_ARTIFACTS);
		assertTrue(artifacts.contains("artifacts_org_"), artifacts);
		assertFalse(artifacts.contains("Seq Scan"), artifacts);
	}

	private static void assertStatusInIndexCond(String plan, String table, String index) {
		assertTrue(plan.contains(index), index + " missing from " + plan);
		assertFalse(plan.contains("Seq Scan"), plan);
		List<JsonNode> scans = new ArrayList<>();
		collectScans(Utils.OM.readTree(plan), table, scans);
		assertEquals(1, scans.size(), table + " scans in " + plan);
		JsonNode scan = scans.get(0);
		JsonNode filter = scan.get("Filter");
		assertTrue(null == filter || !filter.toString().contains("status"), table + " status Filter in " + plan);
		JsonNode cond = indexCond(scan);
		assertNotNull(cond, table + " has no Index Cond in " + plan);
		assertTrue(cond.toString().contains("COALESCE"), table + " Index Cond without COALESCE in " + plan);
	}

	/** White space normalised, so the line breaks of the text blocks do not matter. */
	private static String norm(String s) {
		return s.strip().replaceAll("\\s+", " ");
	}

	private static int occurrences(String haystack, String needle) {
		int n = 0;
		for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1)) {
			n++;
		}
		return n;
	}

	/** TPF-139 6.8.1: the live text holds the former predicates and none of the cached tables. */
	@Test
	void theLiveTextKeepsTheFormerPredicatesAndNoCachedTable() {
		String live = norm(VariableQueries.GET_NUMERIC_ANALYTICS_LIVE_FOR_ORG);
		assertEquals(2, occurrences(live, ACTIVE_ORG_PREDICATE), live);
		assertEquals(1, occurrences(live, BRANCH_ACTIVE_ORG_PREDICATE), live);
		assertTrue(live.contains("cast(b.record_data->>'component' as uuid)"), live);
		for (String absent : List.of("rearm.releases", "rearm.artifacts", "rearm.source_code_entries",
				"rearm.deliverables", "release_count", "artifact_count", "sce_count", "deliverable_count", "::")) {
			assertFalse(live.contains(absent), absent + " in " + live);
		}
		assertTrue(live.startsWith("select component_count.components, component_count.products, vcs_count.vcs,"
				+ " branch_count.branches, branch_count.feature_sets from"), live);
	}

	/** TPF-139 6.8.2: each nightly text equals the former derived table it came from, alias dropped. */
	@Test
	void theNightlyTextsAreTheFormerDerivedTables() {
		assertEquals(LEGACY_RELEASES_SUBQUERY, norm(VariableQueries.ORG_TOTALS_COUNT_RELEASES));
		assertEquals(LEGACY_ARTIFACTS_SUBQUERY, norm(VariableQueries.ORG_TOTALS_COUNT_ARTIFACTS));
		assertEquals(LEGACY_COMMITS_SUBQUERY, norm(VariableQueries.ORG_TOTALS_COUNT_COMMITS));
		assertEquals(LEGACY_DELIVERABLES_SUBQUERY, norm(VariableQueries.ORG_TOTALS_COUNT_DELIVERABLES));
		// On the raw constants, not norm(): norm strips the trailing line break, after which the
		// parameter would be the last token and the lookahead would match at the end of input.
		for (String text : List.of(VariableQueries.GET_NUMERIC_ANALYTICS_LIVE_FOR_ORG,
				VariableQueries.ORG_TOTALS_COUNT_RELEASES, VariableQueries.ORG_TOTALS_COUNT_ARTIFACTS,
				VariableQueries.ORG_TOTALS_COUNT_COMMITS, VariableQueries.ORG_TOTALS_COUNT_DELIVERABLES)) {
			assertFalse(text.contains("::"), text);
			assertFalse(GLUED_PARAMETER.matcher(text).find(), text);
			Matcher m = NAMED_PARAMETER.matcher(text);
			int found = 0;
			while (m.find()) {
				assertEquals("orgUuidAsString", m.group(1), text);
				found++;
			}
			assertTrue(found > 0, text);
		}
	}

	/** TPF-139 6.8.3: the nightly bounds, read from the repository class where both are public. */
	@Test
	void theNightlyTimeoutsAreFiveMinutesWithTheDriverAfterTheServer() {
		assertEquals("5min", OrgTotalsCountRepository.NIGHTLY_STATEMENT_TIMEOUT);
		assertTrue(OrgTotalsCountRepository.NIGHTLY_CLIENT_TIMEOUT_SECONDS > 300,
				String.valueOf(OrgTotalsCountRepository.NIGHTLY_CLIENT_TIMEOUT_SECONDS));
	}

	/** TPF-139 6.8.4: the four cached tables, their map keys and the counted_at key. */
	@Test
	void theCachedTablesAndTheirMapKeys() {
		assertEquals(List.of(OrgTotalsTable.RELEASES, OrgTotalsTable.ARTIFACTS, OrgTotalsTable.COMMITS,
				OrgTotalsTable.DELIVERABLES), List.of(OrgTotalsTable.values()));
		assertEquals(CACHED_TABLES_KEYS, Arrays.stream(OrgTotalsTable.values()).map(OrgTotalsTable::getMapKey).toList());
		assertEquals("counted_at", OrgTotalsTable.COUNTED_AT_KEY);
	}

	/** TPF-141 6.8.1: no org totals cache migration on the classpath, whatever its version number. */
	@Test
	void noOrgTotalsCacheMigrationIsOnTheClasspath() throws Exception {
		assertEquals(0, new PathMatchingResourcePatternResolver()
				.getResources("classpath*:db/migration/V*__org_totals_cache.sql").length);
	}

	/** TPF-141 6.8.2: no org totals relation and no org totals cache history row. */
	@Test
	void noOrgTotalsRelationAndNoHistoryRow() {
		assertNull(jdbc.queryForObject("SELECT to_regclass('rearm.org_totals')::text", String.class));
		assertEquals(0L, jdbc.queryForObject(
				"SELECT count(*) FROM public.flyway_schema_history WHERE description = 'org totals cache'", Long.class));
	}

	/** TPF-141 6.8.3: no entity and no repository of the dropped table. */
	@Test
	void noOrgTotalsEntityOrRepository() {
		assertThrows(ClassNotFoundException.class, () -> Class.forName("io.reliza.model.OrgTotals"));
		assertThrows(ClassNotFoundException.class, () -> Class.forName("io.reliza.repositories.OrgTotalsRepository"));
	}

	/** TPF-141 6.8.4: the record key of the totals. */
	@Test
	void theTotalsRecordKey() throws Exception {
		assertEquals("totals", CommonVariables.TOTALS_FIELD);
		assertEquals("totals", OrganizationData.class.getDeclaredField("totals").getAnnotation(JsonProperty.class).value());
	}

	/** Every plan node whose {@code Relation Name} is {@code table}. */
	private static void collectScans(JsonNode node, String table, List<JsonNode> into) {
		if (node.isArray()) {
			for (JsonNode child : node) {
				collectScans(child, table, into);
			}
			return;
		}
		if (!node.isObject()) {
			return;
		}
		JsonNode relation = node.get("Relation Name");
		if (null != relation && ("\"" + table + "\"").equals(relation.toString())) {
			into.add(node);
		}
		for (String key : List.of("Plan", "Plans")) {
			JsonNode child = node.get(key);
			if (null != child) {
				collectScans(child, table, into);
			}
		}
	}

	/**
	 * The index condition of a table scan: its own {@code Index Cond} for an Index Scan, or that of
	 * the Bitmap Index Scan under a Bitmap Heap Scan.
	 */
	private static JsonNode indexCond(JsonNode scan) {
		JsonNode cond = scan.get("Index Cond");
		if (null != cond) {
			return cond;
		}
		JsonNode children = scan.get("Plans");
		if (null != children) {
			for (JsonNode child : children) {
				JsonNode childCond = child.get("Index Cond");
				if (null != childCond) {
					return childCond;
				}
			}
		}
		return null;
	}
}
