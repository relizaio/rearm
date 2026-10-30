/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
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
 * TPF-70 (mutation round): the totals statement counts only ACTIVE rows, or rows with no status
 * (the old {@code status is null or status = 'ACTIVE'}), and nothing else. The equivalence fixture
 * of {@link OrgTotalsEquivalenceTest} has only ACTIVE, ARCHIVED, missing and null statuses, so a
 * statement that excluded just ARCHIVED ({@code <> 'ARCHIVED'}) counted the same there. This
 * fixture gives every status-filtered table a row in a third and a fourth status (DRAFT and
 * COMPLETE, both {@code StatusEnum} values) that must not be counted.
 */
@SpringBootTest(classes = {App.class})
class OrgTotalsStatusShapeTest {

	/** The nine count keys of the statement, in its column order. */
	private static final List<String> COUNT_KEYS = List.of("components", "products", "releases", "vcs",
			"artifacts", "commits", "branches", "deliverables", "feature_sets");

	@Autowired private JdbcTemplate jdbc;
	@Autowired private OrganizationService organizationService;

	/** Rows inserted by the fixture, as table and uuid, for the cleanup. */
	private final List<String[]> inserted = new ArrayList<>();

	private UUID insert(String table, UUID uuid, UUID org, String status, String extra) {
		StringBuilder doc = new StringBuilder("{\"org\":\"").append(org).append("\",\"status\":\"")
				.append(status).append('"');
		if (null != extra) {
			doc.append(',').append(extra);
		}
		doc.append('}');
		jdbc.update("INSERT INTO rearm." + table + " (uuid, record_data) VALUES (?, cast(? as jsonb))", uuid,
				doc.toString());
		inserted.add(new String[] {table, uuid.toString()});
		return uuid;
	}

	private UUID component(UUID org, String type, String status) {
		UUID uuid = UUID.randomUUID();
		return insert("components", uuid, org, status, "\"type\":\"" + type + "\",\"uuid\":\"" + uuid + "\"");
	}

	private void branch(UUID org, UUID component, String status) {
		insert("branches", UUID.randomUUID(), org, status, "\"component\":\"" + component + "\"");
	}

	private void cleanup() {
		for (String[] row : inserted) {
			jdbc.update("DELETE FROM rearm." + row[0] + " WHERE uuid = cast(? as uuid)", row[1]);
		}
		inserted.clear();
	}

	@Test
	void onlyActiveRowsAreCountedWhateverTheOtherStatuses() {
		UUID org = UUID.randomUUID();
		try {
			UUID c1 = component(org, "COMPONENT", "ACTIVE");
			component(org, "COMPONENT", "DRAFT");
			component(org, "COMPONENT", "COMPLETE");
			UUID p1 = component(org, "PRODUCT", "ACTIVE");
			component(org, "PRODUCT", "DRAFT");
			component(org, "PRODUCT", "COMPLETE");

			branch(org, c1, "ACTIVE");
			branch(org, c1, "DRAFT");
			branch(org, c1, "COMPLETE");
			branch(org, p1, "ACTIVE");
			branch(org, p1, "DRAFT");
			branch(org, p1, "COMPLETE");

			for (String status : List.of("ACTIVE", "DRAFT", "COMPLETE")) {
				insert("releases", UUID.randomUUID(), org, status, "\"version\":\"" + UUID.randomUUID() + "\"");
				insert("vcs_repositories", UUID.randomUUID(), org, status,
						"\"uri\":\"github.com/tpf70/" + UUID.randomUUID() + "\"");
				insert("deliverables", UUID.randomUUID(), org, status, null);
			}

			Map<String, Long> expected = new LinkedHashMap<>();
			for (String key : COUNT_KEYS) {
				expected.put(key, "artifacts".equals(key) || "commits".equals(key) ? 0L : 1L);
			}

			Map<String, ?> m = organizationService.getNumericAnalytics(org);
			Map<String, Long> actual = new LinkedHashMap<>();
			for (String key : COUNT_KEYS) {
				actual.put(key, ((Number) m.get(key)).longValue());
			}
			assertEquals(expected, actual, m.toString());
		} finally {
			cleanup();
		}
	}
}
