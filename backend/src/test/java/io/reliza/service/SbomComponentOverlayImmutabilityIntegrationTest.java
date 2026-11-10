/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;

import io.reliza.model.SbomComponent;
import io.reliza.repositories.SbomComponentOverlayAuditRepository;

/** FDAW-15 row 1 (REQ-1): the overlay audit is insert-only from every SQL path, and the overlay key is unique. */
class SbomComponentOverlayImmutabilityIntegrationTest extends SbomComponentOverlayTestBase {

	private static final String MIGRATION = "db/migration/V116__sbom_component_overlays.sql";
	private static final String ROW = "SELECT *, record_data::text AS d FROM rearm.sbom_component_overlay_audit WHERE uuid = ?";

	private void refused(String sql, UUID row, String operation) {
		DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
				() -> jdbc.update(sql, row), sql);
		assertTrue(e.getMessage().contains("sbom_component_overlay_audit is immutable: " + operation + " of " + row
				+ " refused"), e.getMessage());
	}

	private UUID auditRow() throws Exception {
		SbomComponent sc = components(org, null, List.of("pkg:npm/immutable-" + random() + "@1"), false).get(0);
		overlayService.setComponentOverlay(sc.getUuid(), supplier("Acme"), "first", admin().getUuid());
		return auditOf(sc.getCanonicalPurl()).get(0).getUuid();
	}

	@Test
	void anAuditRowIsRefusedEveryUpdateAndDelete() throws Exception {
		UUID row = auditRow();
		Map<String, Object> before = jdbc.queryForMap(ROW, row);
		for (String set : List.of("overlay = gen_random_uuid()", "org = gen_random_uuid()", "canonical_purl = 'x'",
				"kind = 'VENDORED'", "revision = 9", "record_data = '{}'::jsonb", "asserted_by = gen_random_uuid()",
				"asserted_date = asserted_date - interval '1 day'", "reason = 'edited'", "bulk_act = gen_random_uuid()",
				"removed = true")) {
			refused("UPDATE rearm.sbom_component_overlay_audit SET " + set + " WHERE uuid = ?", row, "update");
		}
		refused("DELETE FROM rearm.sbom_component_overlay_audit WHERE uuid = ?", row, "delete");
		assertEquals(before, jdbc.queryForMap(ROW, row));
	}

	@Test
	void theAuditRepositoryOffersNoUpdateAndNoDelete() throws Exception {
		assertTrue(Repository.class.isAssignableFrom(SbomComponentOverlayAuditRepository.class));
		assertFalse(CrudRepository.class.isAssignableFrom(SbomComponentOverlayAuditRepository.class));
		assertEquals(List.of("countByOrgAndCanonicalPurlInAndAssertedDateAfter",
				"findByOrgAndCanonicalPurlOrderByRevisionAsc", "maxRevision", "save"),
				Arrays.stream(SbomComponentOverlayAuditRepository.class.getMethods()).map(Method::getName).sorted().toList());
	}

	@Test
	void reRunningTheMigrationLeavesTheTriggerInForceAndChangesNothing() throws Exception {
		UUID row = auditRow();
		Map<String, Object> before = jdbc.queryForMap(ROW, row);
		jdbc.execute(new ClassPathResource(MIGRATION).getContentAsString(StandardCharsets.UTF_8));
		assertEquals(before, jdbc.queryForMap(ROW, row));
		refused("DELETE FROM rearm.sbom_component_overlay_audit WHERE uuid = ?", row, "delete");
	}

	@Test
	void aSecondRowOfOneKeyAndProvenanceIsRefused() throws Exception {
		String insert = "INSERT INTO rearm.sbom_component_overlays (org, canonical_purl, kind) VALUES (?, ?, 'COMPONENT')";
		String key = "pkg:npm/unique-" + random() + "@1";
		jdbc.update(insert, org.getUuid(), key);
		assertThrows(DataIntegrityViolationException.class, () -> jdbc.update(insert, org.getUuid(), key));
		assertEquals(1, count("sbom_component_overlays", "org = ? AND canonical_purl = ?", org.getUuid(), key));
	}
}
