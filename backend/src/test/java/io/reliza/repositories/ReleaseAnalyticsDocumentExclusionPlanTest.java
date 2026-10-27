/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Component;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.Organization;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.ReleaseData.ReleaseStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.ReleaseDto;
import io.reliza.service.BranchService;
import io.reliza.service.ComponentService;
import io.reliza.service.oss.OssReleaseService;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;
import tools.jackson.databind.JsonNode;

/**
 * TPF-75 (architecture round 2, test 4.2): the seven org-wide release analytics statements carry
 * the one {@link VariableQueries#RELEASE_NOT_OF_DOCUMENT_COMPONENT} fragment, and under
 * {@code enable_seqscan = off} (the {@code OrgTotalsIndexTest} shape) each plans it as an anti join
 * whose inner side, the fragment's alias {@code dc}, is a lookup on {@code components_pkey} (an Index
 * Scan or a Bitmap Heap Scan over a Bitmap Index Scan, whichever the statistics favour),
 * with the releases side on {@code releases_org_status_idx} and no SubPlan. Each named parameter
 * is bound from a map of typed literals; the pattern skips {@code ::text} casts, and a name not
 * in the map fails the test.
 */
@SpringBootTest(classes = {App.class})
class ReleaseAnalyticsDocumentExclusionPlanTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	private static final Pattern NAMED_PARAMETER = Pattern.compile("(?<!:):([A-Za-z][A-Za-z0-9]*)");

	private static final Map<String, String> LITERALS = Map.of(
			"tz", "'UTC'",
			"cutOffDate", "now() - interval '30 days'",
			"compType", "'COMPONENT'",
			"maxComponents", "10",
			"maxBranches", "10",
			"orgUuidAsString", "'" + UUID.randomUUID() + "'",
			"organization", "'" + UUID.randomUUID() + "'",
			"perspectiveUuidAsString", "'" + UUID.randomUUID() + "'",
			"componentUuids", "'" + UUID.randomUUID() + "', '" + UUID.randomUUID() + "'");

	private static final Map<String, String> STATEMENTS = statements();

	@Autowired private JdbcTemplate jdbc;
	@Autowired private TestInitializer testInitializer;
	@Autowired private ComponentService componentService;
	@Autowired private BranchService branchService;
	@Autowired private OssReleaseService ossReleaseService;

	private static Map<String, String> statements() {
		Map<String, String> s = new LinkedHashMap<>();
		s.put("COUNT_RELEASES_OF_ORG_BY_DATE", VariableQueries.COUNT_RELEASES_OF_ORG_BY_DATE);
		s.put("ANALYTICS_COMPONENTS_WITH_MOST_RELEASES", VariableQueries.ANALYTICS_COMPONENTS_WITH_MOST_RELEASES);
		s.put("ANALYTICS_BRANCHES_WITH_MOST_RELEASES", VariableQueries.ANALYTICS_BRANCHES_WITH_MOST_RELEASES);
		s.put("ANALYTICS_COMPONENTS_WITH_MOST_RELEASES_BY_PERSPECTIVE",
				VariableQueries.ANALYTICS_COMPONENTS_WITH_MOST_RELEASES_BY_PERSPECTIVE);
		s.put("ANALYTICS_BRANCHES_WITH_MOST_RELEASES_BY_PERSPECTIVE",
				VariableQueries.ANALYTICS_BRANCHES_WITH_MOST_RELEASES_BY_PERSPECTIVE);
		s.put("ANALYTICS_COMPONENTS_WITH_MOST_RELEASES_BY_PRODUCT",
				VariableQueries.ANALYTICS_COMPONENTS_WITH_MOST_RELEASES_BY_PRODUCT);
		s.put("ANALYTICS_BRANCHES_WITH_MOST_RELEASES_BY_PRODUCT",
				VariableQueries.ANALYTICS_BRANCHES_WITH_MOST_RELEASES_BY_PRODUCT);
		return s;
	}

	/**
	 * At least one release row: on a releases table that never held one, the most-active statements
	 * plan their releases side on another index (functional review round 2, F-3).
	 */
	@BeforeEach
	void oneRelease() throws RelizaException {
		Organization org = testInitializer.obtainOrganization();
		Component c = componentService.createComponent("tpf75-plan-" + UUID.randomUUID(), org.getUuid(),
				ComponentType.COMPONENT, "semver", "Branch.Micro", null, WU);
		var branch = branchService.getBaseBranchOfComponent(c.getUuid()).orElseThrow();
		ossReleaseService.createRelease(ReleaseDto.builder().component(c.getUuid()).org(org.getUuid())
				.branch(branch.getUuid()).version("1.0.0").status(ReleaseStatus.ACTIVE)
				.lifecycle(ReleaseLifecycle.ASSEMBLED).build(), WU);
	}

	/** {@code query} with every named parameter replaced by its typed literal; an unmapped name fails. */
	private static String bound(String query) {
		Matcher m = NAMED_PARAMETER.matcher(query);
		StringBuilder sb = new StringBuilder();
		while (m.find()) {
			String literal = LITERALS.get(m.group(1));
			if (null == literal) {
				fail("unmapped parameter :" + m.group(1));
			}
			m.appendReplacement(sb, Matcher.quoteReplacement(literal));
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
	void theFragmentIsWrittenOnceInEachStatement() {
		String fragment = VariableQueries.RELEASE_NOT_OF_DOCUMENT_COMPONENT;
		assertTrue(fragment.contains("NOT EXISTS") && fragment.contains("'BOARD_DOCUMENT'"), fragment);
		STATEMENTS.forEach((name, constant) -> assertTrue(constant.contains(fragment), name));
	}

	@Test
	void eachStatementPlansTheExclusionAsAnAntiJoinOnComponentsPkey() {
		STATEMENTS.forEach((name, constant) -> {
			String plan = plan(constant);
			assertTrue(plan.contains("\"Join Type\": \"Anti\""), name + " has no anti join: " + plan);
			assertTrue(plan.contains("releases_org_status_idx"), name + " misses releases_org_status_idx: " + plan);
			assertFalse(plan.contains("SubPlan"), name + " has a SubPlan: " + plan);
			List<JsonNode> dc = new ArrayList<>();
			collectAlias(Utils.OM.readTree(plan), "dc", dc);
			assertEquals(1, dc.size(), name + " alias dc nodes in " + plan);
			JsonNode node = dc.get(0);
			assertEquals("\"components\"", String.valueOf(node.get("Relation Name")), name + " " + node);
			assertEquals("components_pkey", pkeyLookupIndex(node), name + " " + node);
		});
	}

	/**
	 * The index a single-row primary-key lookup of {@code node} reads: an Index Scan names it itself,
	 * a Bitmap Heap Scan through its one Bitmap Index Scan child. The planner picks either at the same
	 * cost depending on table statistics (main's CI build of #946 planned the bitmap form after the
	 * rest of the suite had filled the tables), so the test pins the index, not the node type.
	 */
	private static String pkeyLookupIndex(JsonNode node) {
		String type = node.path("Node Type").asString();
		if ("Index Scan".equals(type)) {
			return node.path("Index Name").asString();
		}
		if ("Bitmap Heap Scan".equals(type)) {
			JsonNode children = node.path("Plans");
			if (children.size() == 1 && "Bitmap Index Scan".equals(children.get(0).path("Node Type").asString())) {
				return children.get(0).path("Index Name").asString();
			}
		}
		return "unexpected node type " + type;
	}

	/** Every plan node whose {@code Alias} is {@code alias}. */
	private static void collectAlias(JsonNode node, String alias, List<JsonNode> into) {
		if (node.isArray()) {
			for (JsonNode child : node) {
				collectAlias(child, alias, into);
			}
			return;
		}
		if (!node.isObject()) {
			return;
		}
		JsonNode a = node.get("Alias");
		if (null != a && ("\"" + alias + "\"").equals(a.toString())) {
			into.add(node);
		}
		for (String key : List.of("Plan", "Plans")) {
			JsonNode child = node.get(key);
			if (null != child) {
				collectAlias(child, alias, into);
			}
		}
	}
}
