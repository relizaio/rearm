/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.repositories;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.MetricsAudit;
import io.reliza.model.MetricsAudit.MetricsEntityType;
import io.reliza.ws.App;

/**
 * Repository tests for the metrics_audit window/seed finders added for board task #37.
 *
 * <p>Verifies: (1) {@code findRevisionsInRange} returns only in-window rows for the requested
 * entity_uuids, ordered by (entity_uuid, metrics_revision); (2) org=NULL rows (pre-V20) are still
 * returned (filter is by entity_uuid, not org); (3) {@code findLatestRevisionBeforeDate} returns
 * exactly the latest pre-window row per entity (DISTINCT ON).
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
public class MetricsAuditRepositoryTest {

	@Autowired private MetricsAuditRepository repo;

	private static final String RELEASE = MetricsEntityType.RELEASE.name();

	private MetricsAudit save(UUID entityUuid, UUID org, int revision, ZonedDateTime revisionCreatedDate) {
		MetricsAudit a = new MetricsAudit();
		a.setUuid(UUID.randomUUID());
		a.setEntityType(MetricsEntityType.RELEASE);
		a.setEntityUuid(entityUuid);
		a.setOrg(org);
		a.setMetricsRevision(revision);
		a.setRevisionCreatedDate(revisionCreatedDate);
		a.setEntityCreatedDate(revisionCreatedDate);
		Map<String, Object> metrics = new HashMap<>();
		metrics.put("critical", revision);
		a.setMetrics(metrics);
		return repo.save(a);
	}

	@Test
	public void findRevisionsInRange_windowedOrderedAndNullOrgIncluded() {
		UUID relA = UUID.randomUUID();
		UUID relB = UUID.randomUUID();
		UUID otherRelease = UUID.randomUUID(); // not requested -> must be excluded
		ZonedDateTime from = ZonedDateTime.parse("2026-02-01T00:00:00Z");
		ZonedDateTime to = ZonedDateTime.parse("2026-03-01T00:00:00Z");

		// relA: one boundary-before-from row (excluded from window), two in-window rows.
		save(relA, UUID.randomUUID(), 0, ZonedDateTime.parse("2026-01-15T00:00:00Z")); // before from
		MetricsAudit relA1 = save(relA, UUID.randomUUID(), 1, ZonedDateTime.parse("2026-02-05T00:00:00Z"));
		MetricsAudit relA2 = save(relA, UUID.randomUUID(), 2, ZonedDateTime.parse("2026-02-20T00:00:00Z"));
		// relA: one row after to (excluded).
		save(relA, UUID.randomUUID(), 3, ZonedDateTime.parse("2026-03-15T00:00:00Z"));

		// relB: org intentionally NULL (pre-V20 row) -- must still be returned by entity_uuid filter.
		MetricsAudit relB0 = save(relB, null, 0, ZonedDateTime.parse("2026-02-10T00:00:00Z"));

		// otherRelease: in-window but not requested -> excluded.
		save(otherRelease, UUID.randomUUID(), 0, ZonedDateTime.parse("2026-02-12T00:00:00Z"));

		List<MetricsAudit> result = repo.findRevisionsInRange(RELEASE, List.of(relA, relB), from, to);

		Set<UUID> resultUuids = result.stream().map(MetricsAudit::getUuid).collect(Collectors.toSet());
		assertTrue(resultUuids.contains(relA1.getUuid()));
		assertTrue(resultUuids.contains(relA2.getUuid()));
		assertTrue(resultUuids.contains(relB0.getUuid()), "org=NULL row must still be returned");
		assertEquals(3, result.size(), "Only in-window rows for requested entities");

		// Ordering: rows are grouped by entity_uuid and ascending by metrics_revision within entity.
		List<MetricsAudit> relARows = result.stream()
				.filter(r -> r.getEntityUuid().equals(relA))
				.collect(Collectors.toList());
		assertEquals(2, relARows.size());
		assertTrue(relARows.get(0).getMetricsRevision() < relARows.get(1).getMetricsRevision(),
				"Rows must be ordered by metrics_revision ascending within an entity");
	}

	@Test
	public void findLatestRevisionBeforeDate_returnsLatestPreWindowRowPerEntity() {
		UUID relA = UUID.randomUUID();
		UUID relB = UUID.randomUUID();
		ZonedDateTime from = ZonedDateTime.parse("2026-02-01T00:00:00Z");

		// relA: two rows before from; the latest (rev 1) must win.
		save(relA, UUID.randomUUID(), 0, ZonedDateTime.parse("2026-01-10T00:00:00Z"));
		MetricsAudit relALatestBefore = save(relA, UUID.randomUUID(), 1, ZonedDateTime.parse("2026-01-20T00:00:00Z"));
		// relA: one row at/after from -> must NOT be considered the seed.
		save(relA, UUID.randomUUID(), 2, ZonedDateTime.parse("2026-02-05T00:00:00Z"));

		// relB: only a row after from -> no seed for relB.
		save(relB, UUID.randomUUID(), 0, ZonedDateTime.parse("2026-02-10T00:00:00Z"));

		List<MetricsAudit> seeds = repo.findLatestRevisionBeforeDate(RELEASE, List.of(relA, relB), from);

		assertEquals(1, seeds.size(), "Only relA has a pre-window row (one per entity via DISTINCT ON)");
		assertEquals(relALatestBefore.getUuid(), seeds.get(0).getUuid(),
				"Must pick the highest-revision row strictly before the window");
		assertFalse(seeds.stream().anyMatch(s -> s.getEntityUuid().equals(relB)),
				"relB has no pre-window row");
	}
}
