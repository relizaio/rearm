/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.service.OrganizationService;
import io.reliza.ws.App;

/**
 * TPF-70 (architecture round 2, test 4.2): the rewritten totals statement counts what the old one
 * counted, for the organization asked about and no other, and the transaction-local planner
 * settings do not outlive the call. The fixture is two fresh organizations, A with every status
 * shape the statement distinguishes and B with one active row per table, inserted with
 * {@link JdbcTemplate} and deleted by uuid in {@code finally}.
 */
@SpringBootTest(classes = {App.class})
class OrgTotalsEquivalenceTest {

	/** The nine count keys of the statement, in its column order. */
	private static final List<String> COUNT_KEYS = List.of("components", "products", "releases", "vcs",
			"artifacts", "commits", "branches", "deliverables", "feature_sets");

	/**
	 * {@code VariableQueries.GET_NUMERIC_ANALYTICS_FOR_ORG} as it was before TPF-70 (rearm-saas
	 * aa364d75, VariableQueries 1355-1378), {@code :orgUuidAsString} replaced by {@code ?}.
	 */
	private static final String LEGACY_TOTALS = """
			select component_count.components, product_count.products, release_count.releases,
			vcs_count.vcs, artifact_count.artifacts, sce_count.commits, branch_count.branches, deliverable_count.deliverables, feature_set_count.feature_sets
			from
			(select count(*) as components from rearm.components where record_data->>'org' = ?
			   and record_data->>'type' = 'COMPONENT' and (record_data->>'status' is null or record_data->>'status' = 'ACTIVE')) as component_count,
			(select count(*) as products from rearm.components where record_data->>'org' = ?
			   and record_data->>'type' = 'PRODUCT' and (record_data->>'status' is null or record_data->>'status' = 'ACTIVE')) as product_count,
			(select count(*) as releases from rearm.releases where record_data->>'org' = ?
			    and (record_data->>'status' is null or record_data->>'status' = 'ACTIVE')) as release_count,
			(select count(*) as vcs from rearm.vcs_repositories as vcs where record_data->>'org' = ?
			    and (record_data->>'status' is null or record_data->>'status' = 'ACTIVE')) as vcs_count,
			(select count(*) as artifacts from rearm.artifacts where record_data->>'org' = ?
				) as artifact_count,
			(select count(*) as commits from rearm.source_code_entries where record_data->>'org' = ?) as sce_count,
			(select count(*) as branches from rearm.branches b where b.record_data->>'org' = ? and
				(b.record_data->>'status' is null or b.record_data->>'status' = 'ACTIVE') and
				'COMPONENT' = (select rc.record_data->>'type' from rearm.components rc where rc.record_data->>'uuid' = b.record_data->>'component')) as branch_count,
			(select count(*) as feature_sets from rearm.branches b where b.record_data->>'org' = ? and
				(b.record_data->>'status' is null or b.record_data->>'status' = 'ACTIVE') and
				'PRODUCT' = (select rc.record_data->>'type' from rearm.components rc where rc.record_data->>'uuid' = b.record_data->>'component')) as feature_set_count,
			(select count(*) as deliverables from rearm.deliverables where record_data->>'org' = ? and
				(record_data->>'status' is null or record_data->>'status' = 'ACTIVE')) as deliverable_count
			""";

	/** Status shapes of a fixture document: an ACTIVE or ARCHIVED value, a JSON null, or no key. */
	private static final String NO_STATUS = "<none>";
	private static final String NULL_STATUS = "<null>";

	@Autowired private JdbcTemplate jdbc;
	@Autowired private OrganizationService organizationService;

	/** Rows inserted by the fixture, as table and uuid, for the cleanup. */
	private final List<String[]> inserted = new ArrayList<>();

	private UUID insert(String table, UUID uuid, UUID org, String status, String extra) {
		StringBuilder doc = new StringBuilder("{\"org\":\"").append(org).append('"');
		if (NULL_STATUS.equals(status)) {
			doc.append(",\"status\":null");
		} else if (!NO_STATUS.equals(status)) {
			doc.append(",\"status\":\"").append(status).append('"');
		}
		if (null != extra) {
			doc.append(',').append(extra);
		}
		doc.append('}');
		jdbc.update("INSERT INTO rearm." + table + " (uuid, record_data) VALUES (?, cast(? as jsonb))", uuid,
				doc.toString());
		inserted.add(new String[] {table, uuid.toString()});
		return uuid;
	}

	private UUID insert(String table, UUID org, String status, String extra) {
		return insert(table, UUID.randomUUID(), org, status, extra);
	}

	private UUID component(UUID org, String type, String status) {
		UUID uuid = UUID.randomUUID();
		return insert("components", uuid, org, status, "\"type\":\"" + type + "\",\"uuid\":\"" + uuid + "\"");
	}

	private void branch(UUID org, UUID component, String status) {
		insert("branches", org, status, "\"component\":\"" + component + "\"");
	}

	private void release(UUID org, String status) {
		insert("releases", org, status, "\"version\":\"" + UUID.randomUUID() + "\"");
	}

	private void vcs(UUID org, String status) {
		insert("vcs_repositories", org, status, "\"uri\":\"github.com/tpf70/" + UUID.randomUUID() + "\"");
	}

	/** Organization A of the 4.2 table (rows 1-7) and B (row 8). */
	private void fixture(UUID a, UUID b) {
		UUID cB = component(b, "COMPONENT", "ACTIVE");
		UUID c1 = component(a, "COMPONENT", "ACTIVE");
		UUID c2 = component(a, "COMPONENT", NO_STATUS);
		component(a, "COMPONENT", "ARCHIVED");
		UUID p1 = component(a, "PRODUCT", "ACTIVE");
		UUID p2 = component(a, "PRODUCT", NULL_STATUS);

		branch(a, c1, "ACTIVE");
		branch(a, c2, NO_STATUS);
		branch(a, c1, "ARCHIVED");
		branch(a, p1, "ACTIVE");
		branch(a, p2, "ACTIVE");
		branch(a, UUID.randomUUID(), "ACTIVE");
		branch(a, cB, "ACTIVE");
		branch(b, cB, "ACTIVE");

		for (String status : List.of("ACTIVE", "ACTIVE", "ACTIVE", NO_STATUS, "ARCHIVED", "ARCHIVED")) {
			release(a, status);
		}
		release(b, "ACTIVE");

		vcs(a, "ACTIVE");
		vcs(a, "ACTIVE");
		vcs(a, "ARCHIVED");
		vcs(b, "ACTIVE");

		insert("artifacts", a, "ACTIVE", null);
		insert("artifacts", a, "ARCHIVED", null);
		insert("artifacts", a, NO_STATUS, null);
		insert("artifacts", b, "ACTIVE", null);

		insert("source_code_entries", a, NO_STATUS, null);
		insert("source_code_entries", a, NO_STATUS, null);
		insert("source_code_entries", b, "ACTIVE", null);

		for (String status : List.of("ACTIVE", "ACTIVE", NO_STATUS, "ARCHIVED")) {
			insert("deliverables", a, status, null);
		}
		insert("deliverables", b, "ACTIVE", null);
	}

	private void cleanup() {
		for (String[] row : inserted) {
			jdbc.update("DELETE FROM rearm." + row[0] + " WHERE uuid = cast(? as uuid)", row[1]);
		}
		inserted.clear();
	}

	private static Map<String, Long> expected(long... counts) {
		Map<String, Long> m = new LinkedHashMap<>();
		for (int i = 0; i < COUNT_KEYS.size(); i++) {
			m.put(COUNT_KEYS.get(i), counts[i]);
		}
		return m;
	}

	/** The nine counts of {@code m}, compared numerically: the runtime values are Long, not BigInteger. */
	private static Map<String, Long> counts(Map<String, ?> m) {
		Map<String, Long> c = new LinkedHashMap<>();
		for (String key : COUNT_KEYS) {
			c.put(key, ((Number) m.get(key)).longValue());
		}
		return c;
	}

	private Map<String, Long> legacy(UUID org) {
		Object[] args = new Object[COUNT_KEYS.size()];
		Arrays.fill(args, org.toString());
		return counts(jdbc.queryForMap(LEGACY_TOTALS, args));
	}

	@Test
	void theServiceCountsOrganizationAAndTheLegacyStatementAgreesForBoth() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		try {
			fixture(a, b);
			// components, products, releases, vcs, artifacts, commits, branches, deliverables, feature_sets
			Map<String, Long> expectedA = expected(2, 2, 4, 2, 3, 2, 3, 3, 2);
			Map<String, Long> expectedB = expected(1, 0, 1, 1, 1, 1, 1, 1, 0);

			Map<String, ?> m = organizationService.getNumericAnalytics(a);
			assertEquals(expectedA, counts(m), m.toString());
			assertEquals(2L, ((Number) m.get("components")).longValue());
			assertEquals(BigInteger.ZERO, m.get("admin_and_write_users"));
			assertEquals(BigInteger.ZERO, m.get("read_only_users"));
			assertEquals(COUNT_KEYS.size() + 2, m.size(), m.toString());

			Map<String, ?> mB = organizationService.getNumericAnalytics(b);
			assertEquals(expectedB, counts(mB), mB.toString());

			assertEquals(expectedA, legacy(a));
			assertEquals(expectedB, legacy(b));
		} finally {
			cleanup();
		}
	}

	@Test
	void everyComponentDocumentCarriesItsRowUuid() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		try {
			fixture(a, b);
			Long mismatched = jdbc.queryForObject("select count(*) from rearm.components"
					+ " where record_data->>'uuid' is distinct from uuid::text", Long.class);
			assertEquals(0L, mismatched);
		} finally {
			cleanup();
		}
	}

	@Test
	void thePlannerSettingsDoNotOutliveTheCall() {
		UUID a = UUID.randomUUID();
		UUID b = UUID.randomUUID();
		try {
			fixture(a, b);
			organizationService.getNumericAnalytics(a);
			for (int i = 0; i < 5; i++) {
				assertEquals("on", jdbc.queryForObject("SHOW enable_seqscan", String.class));
				assertEquals("on", jdbc.queryForObject("SHOW jit", String.class));
				assertEquals("0", jdbc.queryForObject("SHOW statement_timeout", String.class));
			}
		} finally {
			cleanup();
		}
	}
}
