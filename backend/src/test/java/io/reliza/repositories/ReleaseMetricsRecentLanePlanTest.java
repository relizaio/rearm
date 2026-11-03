/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.common.Utils;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;
import tools.jackson.databind.JsonNode;

/**
 * TPF-138 (architecture round 2, rows 6.3.1-6.3.8 and 6.4.1-6.4.2): the recent-lane finder's plan, the
 * plans of the three existing statements over the shared predicate, the V105 migration and the text
 * pins of the statements. Row 6.4.3 reads package-private SchedulingService constants and lives in
 * {@code io.reliza.service.MetricsRecentLaneTickTest}.
 *
 * <p>Seed: 2,000 settled releases of the test's own organization (lastScanned ten years ahead, so not
 * pending), 300 pending releases created 30 minutes ago plus g seconds and 300 created three hours ago
 * plus g seconds, all with distinct last_updated_date; then ANALYZE. Plans are read with EXPLAIN
 * (FORMAT JSON), as MetricsVulnerabilityGinPlanTest reads them; the migration is located by pattern,
 * never by number, because it may be renumbered at merge.
 */
@SpringBootTest(classes = {App.class})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ReleaseMetricsRecentLanePlanTest {

	private static final Pattern NAMED_PARAMETER = Pattern.compile("(?<!:):([A-Za-z][A-Za-z0-9]*)");

	private static final String RECENT_IDX = "releases_metrics_pending_recent_idx";
	private static final String PENDING_IDX = "releases_metrics_pending_idx";
	private static final String MIGRATION_DESCRIPTION = "releases metrics pending recent partial index";
	private static final String MIGRATION_PATTERN = "classpath:db/migration/V*__releases_metrics_pending_recent_partial_index.sql";
	private static final String V73 = "db/migration/V73__releases_metrics_pending_partial_index.sql";

	@Autowired private JdbcTemplate jdbc;
	@Autowired private TestInitializer testInitializer;

	private UUID org;
	private Map<String, String> literals;

	@BeforeAll
	void seed() {
		org = testInitializer.obtainOrganization().getUuid();
		String scanned = "extract(epoch from now() + interval '10 years')";
		jdbc.update("INSERT INTO rearm.releases (uuid, record_data, metrics) SELECT gen_random_uuid(),"
				+ " jsonb_build_object('org', ?::text, 'version', 'settled-' || g),"
				+ " jsonb_build_object('lastScanned', " + scanned + ") FROM generate_series(1, 2000) g",
				org.toString());
		for (String createdAgo : List.of("30 minutes", "3 hours")) {
			jdbc.update("INSERT INTO rearm.releases (uuid, created_date, last_updated_date, record_data, metrics)"
					+ " SELECT gen_random_uuid(), now() - ?::interval + (g || ' seconds')::interval,"
					+ " now() - interval '20 minutes' + (g || ' seconds')::interval,"
					+ " jsonb_build_object('org', ?::text, 'artifacts', '[]'::jsonb, 'parentReleases', '[]'::jsonb),"
					+ " '{}'::jsonb FROM generate_series(1, 300) g",
					createdAgo, org.toString());
		}
		jdbc.execute("ANALYZE rearm.releases");
		UUID one = jdbc.queryForObject("SELECT uuid FROM rearm.releases WHERE record_data->>'org' = ? LIMIT 1",
				UUID.class, org.toString());
		literals = new HashMap<>();
		literals.put("recentWindowMinutes", "60");
		literals.put("limit", "51");
		literals.put("uuid", "'" + one + "'::uuid");
	}

	@AfterAll
	void cleanUp() {
		jdbc.update("DELETE FROM rearm.releases WHERE record_data->>'org' = ?", org.toString());
	}

	/** Row 6.3.1: the recent finder takes the V105 index, in order, with an early stop. */
	@Test
	void theRecentFinderTakesTheRecentIndexInOrderWithAnEarlyStop() {
		String strong = plan(VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_RECENT, true);
		List<JsonNode> nodes = nodes(strong);
		assertEquals("Limit", nodes.get(0).get("Node Type").asString(), strong);
		List<JsonNode> readers = readersOfReleases(nodes);
		assertEquals(1, readers.size(), strong);
		JsonNode scan = readers.get(0);
		assertEquals("Index Scan", scan.get("Node Type").asString(), strong);
		assertEquals(RECENT_IDX, scan.get("Index Name").asString(), strong);
		assertEquals("Forward", scan.get("Scan Direction").asString(), strong);
		assertTrue(scan.get("Index Cond").asString().contains("created_date"), strong);
		assertTrue(scan.get("Filter").asString().contains("metricsComputeSkipUntil"), strong);
		assertNoSort(nodes, strong);

		String free = plan(VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_RECENT, false);
		List<JsonNode> freeNodes = nodes(free);
		for (JsonNode n : readersOfReleases(freeNodes)) {
			assertFalse("Seq Scan".equals(n.get("Node Type").asString()), free);
		}
		assertNoSort(freeNodes, free);
	}

	/** Row 6.3.2: the existing finder still takes V73's index. */
	@Test
	void theByUpdateFinderStillTakesThePendingIndex() {
		String p = plan(VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_BY_UPDATE, true);
		List<JsonNode> nodes = nodes(p);
		List<JsonNode> readers = readersOfReleases(nodes);
		assertEquals(1, readers.size(), p);
		assertEquals("Index Scan", readers.get(0).get("Node Type").asString(), p);
		assertEquals(PENDING_IDX, readers.get(0).get("Index Name").asString(), p);
		assertNoSort(nodes, p);
	}

	/** Row 6.3.3: the count statement takes one of the two partial indexes. */
	@Test
	void theCountTakesAPartialIndex() {
		String p = plan(VariableQueries.COUNT_RELEASES_ELIGIBLE_FOR_METRICS_COMPUTE_BY_UPDATE, true);
		List<JsonNode> nodes = nodes(p);
		boolean partial = false;
		for (JsonNode n : nodes) {
			String type = n.get("Node Type").asString();
			if ("Seq Scan".equals(type) && n.has("Relation Name") && "releases".equals(n.get("Relation Name").asString())) {
				fail("Seq Scan on releases: " + p);
			}
			if (Set.of("Index Scan", "Index Only Scan", "Bitmap Index Scan").contains(type)) {
				assertTrue(Set.of(PENDING_IDX, RECENT_IDX).contains(n.get("Index Name").asString()), p);
				partial = true;
			}
		}
		assertTrue(partial, p);
	}

	/** Row 6.3.4: the per-release check is a primary-key lookup. */
	@Test
	void thePerReleaseCheckTakesThePrimaryKey() {
		String p = plan(VariableQueries.IS_RELEASE_ELIGIBLE_FOR_METRICS_COMPUTE_BY_UPDATE, false);
		assertTrue(p.contains("\"releases_pkey\""), p);
	}

	/** Row 6.3.5: the migration applied once (the test database is migrated from empty by the build). */
	@Test
	void theMigrationAppliedOnce() {
		List<Map<String, Object>> history = history();
		assertEquals(1, history.size(), "exactly one history row: " + history);
		assertEquals(Boolean.TRUE, history.get(0).get("success"));
	}

	/** Row 6.3.6: the index is the V73 shape on created_date: the server-normalised predicates are equal. */
	@Test
	void theIndexIsTheV73ShapeOnCreatedDate() {
		List<String> recent = indexdef(RECENT_IDX);
		assertEquals(1, recent.size(), recent.toString());
		assertTrue(recent.get(0).contains("USING btree (created_date)"), recent.get(0));
		List<String> pending = indexdef(PENDING_IDX);
		assertEquals(1, pending.size(), pending.toString());
		assertEquals(afterWhere(pending.get(0)), afterWhere(recent.get(0)));
	}

	/** Row 6.3.7: the predicate is spelled byte-for-byte in the file, in V73 and in the constant. */
	@Test
	void thePredicateIsSpelledByteForByte() throws Exception {
		Resource migration = migration();
		String statement = onlyStatement(migration.getContentAsString(StandardCharsets.UTF_8));
		assertTrue(statement.startsWith("CREATE INDEX IF NOT EXISTS " + RECENT_IDX), statement);
		String firstCondition = VariableQueries.METRICS_COMPUTE_BY_UPDATE_ELIGIBLE.strip().split("\n")[0].strip();
		assertEquals(firstCondition, predicateOf(statement));
		String v73 = onlyStatement(new ClassPathResource(V73).getContentAsString(StandardCharsets.UTF_8));
		assertEquals(predicateOf(v73), predicateOf(statement));
		assertFalse(new ClassPathResource("db/migration/" + migration.getFilename() + ".conf").exists(),
				"no .conf: the migration runs in Flyway's transaction");
	}

	/** Row 6.3.8: a second run is a no-op (IF NOT EXISTS). */
	@Test
	void theMigrationIsIdempotent() throws Exception {
		String statement = onlyStatement(migration().getContentAsString(StandardCharsets.UTF_8));
		jdbc.execute(statement);
		assertEquals(1, indexdef(RECENT_IDX).size());
		assertEquals(1, history().size());
	}

	/** Row 6.4.1: the three existing statements over the shared predicate are unchanged. */
	@Test
	void theExistingStatementsComposeTheSharedPredicate() {
		String eligible = VariableQueries.METRICS_COMPUTE_BY_UPDATE_ELIGIBLE;
		assertEquals("SELECT count(*) FROM rearm.releases WHERE " + eligible,
				VariableQueries.COUNT_RELEASES_ELIGIBLE_FOR_METRICS_COMPUTE_BY_UPDATE);
		assertEquals("SELECT EXISTS (SELECT 1 FROM rearm.releases WHERE uuid = :uuid AND (" + eligible + "))",
				VariableQueries.IS_RELEASE_ELIGIBLE_FOR_METRICS_COMPUTE_BY_UPDATE);
		assertTrue(VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_BY_UPDATE.endsWith(" ORDER BY last_updated_date ASC LIMIT :limit"));
		assertTrue(VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_BY_UPDATE.contains(eligible));
	}

	/** Row 6.4.2: the recent finder's text: shared predicate, bound window, oldest-created first. */
	@Test
	void theRecentFinderTextIsPinned() {
		String q = VariableQueries.FIND_RELEASES_FOR_METRICS_COMPUTE_RECENT;
		assertTrue(q.contains(VariableQueries.METRICS_COMPUTE_BY_UPDATE_ELIGIBLE));
		assertTrue(q.contains(" AND created_date > now() - (:recentWindowMinutes || ' minutes')::interval"), q);
		assertTrue(q.endsWith(" ORDER BY created_date ASC LIMIT :limit"), q);
		String between = q.substring(q.indexOf("::interval", q.indexOf("created_date >")), q.indexOf("ORDER BY"));
		assertFalse(between.matches("(?s).*[0-9].*"), "no literal window: " + between);
		assertFalse(q.contains("created_date DESC"), "never newest-first");
	}

	// ---- helpers ----

	private List<Map<String, Object>> history() {
		return jdbc.queryForList("SELECT version, success FROM public.flyway_schema_history WHERE description = ?",
				MIGRATION_DESCRIPTION);
	}

	private List<String> indexdef(String name) {
		return jdbc.queryForList("SELECT indexdef FROM pg_indexes WHERE schemaname = 'rearm' AND indexname = ?",
				String.class, name);
	}

	private static String afterWhere(String indexdef) {
		return indexdef.substring(indexdef.indexOf(" WHERE ") + " WHERE ".length());
	}

	private Resource migration() throws Exception {
		Resource[] found = new PathMatchingResourcePatternResolver().getResources(MIGRATION_PATTERN);
		assertEquals(1, found.length, "exactly one migration matches " + MIGRATION_PATTERN);
		return found[0];
	}

	/** The script's only statement, comment lines dropped, lines joined by a space, the final ; kept. */
	private static String onlyStatement(String script) {
		List<String> statements = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		for (String line : script.split("\n")) {
			String l = line.strip();
			if (l.isEmpty() || l.startsWith("--")) continue;
			current.append(l).append(' ');
			if (l.endsWith(";")) {
				statements.add(current.toString().strip());
				current.setLength(0);
			}
		}
		assertTrue(current.toString().isBlank(), "trailing text without ;: " + current);
		assertEquals(1, statements.size(), "exactly one statement: " + statements);
		return statements.get(0);
	}

	/** The text between WHERE and the final ;, stripped. */
	private static String predicateOf(String statement) {
		return statement.substring(statement.indexOf("WHERE ") + "WHERE ".length(), statement.lastIndexOf(';')).strip();
	}

	private String bound(String query) {
		Matcher m = NAMED_PARAMETER.matcher(query);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String literal = literals.get(m.group(1));
			if (null == literal) {
				fail("unmapped parameter :" + m.group(1));
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(literal));
		}
		m.appendTail(sb);
		return sb.toString();
	}

	private String plan(String query, boolean seqscanOff) {
		String sql = bound(query);
		return jdbc.execute((ConnectionCallback<String>) con -> {
			try (Statement st = con.createStatement()) {
				if (seqscanOff) st.execute("SET enable_seqscan = off");
				try (ResultSet rs = st.executeQuery("EXPLAIN (FORMAT JSON) " + sql)) {
					rs.next();
					return rs.getString(1);
				} finally {
					if (seqscanOff) st.execute("RESET enable_seqscan");
				}
			}
		});
	}

	private static void nodes(JsonNode node, List<JsonNode> into) {
		if (node.isArray()) {
			for (JsonNode child : node) nodes(child, into);
			return;
		}
		if (!node.isObject()) return;
		if (node.has("Node Type")) into.add(node);
		for (String key : List.of("Plan", "Plans")) {
			JsonNode child = node.get(key);
			if (null != child) nodes(child, into);
		}
	}

	/** Every plan node, depth first; the first is the root. */
	private static List<JsonNode> nodes(String plan) {
		List<JsonNode> out = new ArrayList<>();
		nodes(Utils.OM.readTree(plan), out);
		return out;
	}

	private static List<JsonNode> readersOfReleases(List<JsonNode> nodes) {
		List<JsonNode> out = new ArrayList<>();
		for (JsonNode n : nodes) {
			if (n.has("Relation Name") && "releases".equals(n.get("Relation Name").asString())) out.add(n);
		}
		return out;
	}

	private static void assertNoSort(List<JsonNode> nodes, String plan) {
		for (JsonNode n : nodes) {
			assertFalse(n.get("Node Type").asString().contains("Sort"), "no Sort node: " + plan);
		}
	}
}
