/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
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

import io.reliza.common.Utils;
import io.reliza.ws.App;
import tools.jackson.databind.JsonNode;

/**
 * TPF-70 (architecture round 2, test 4.1): the org totals indexes exist as designed and the
 * Statistics totals statement ({@link VariableQueries#GET_NUMERIC_ANALYTICS_FOR_ORG}) takes them
 * under {@code enable_seqscan = off}, the setting its production transaction runs with. The plan
 * check EXPLAINs the constant itself (named parameters replaced by quoted random uuids, the
 * {@code SbomUpdateGuardIndexTest} idiom), so the test follows the query text. The migration is
 * asserted by description and index name, never by number.
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

	@Test
	void theTotalsStatementTakesTheIndexPaths() {
		String plan = plan(VariableQueries.GET_NUMERIC_ANALYTICS_FOR_ORG);
		for (String name : List.of("releases_org_status_idx", "deliverables_org_status_idx",
				"source_code_entries_org_idx", "components_org_idx", "branches_org_idx", "components_pkey",
				"vcs_repositories_org_uri_unique", "artifacts_org_")) {
			assertTrue(plan.contains(name), name + " missing from " + plan);
		}
		assertFalse(plan.contains("SubPlan"), plan);
		assertFalse(plan.contains("\"Node Type\": \"Seq Scan\""), plan);
		JsonNode root = Utils.OM.readTree(plan);
		for (String table : List.of("releases", "deliverables")) {
			List<JsonNode> scans = new ArrayList<>();
			collectScans(root, table, scans);
			assertEquals(1, scans.size(), table + " scans in " + plan);
			JsonNode scan = scans.get(0);
			JsonNode filter = scan.get("Filter");
			assertTrue(null == filter || !filter.toString().contains("status"), table + " status Filter in " + plan);
			JsonNode cond = indexCond(scan);
			assertNotNull(cond, table + " has no Index Cond in " + plan);
			assertTrue(cond.toString().contains("COALESCE"), table + " Index Cond without COALESCE in " + plan);
		}
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
