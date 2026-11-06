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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;

import io.reliza.model.JustifiedUnknownReason;
import io.reliza.model.SupportBulkActKind;
import io.reliza.repositories.ReleaseSbomJustificationAuditRepository;
import io.reliza.repositories.SupportBulkActRepository;
import io.reliza.service.ReleaseSbomJustificationService.Overwrite;

/** FDAW-14 row 1 (REQ-1): the justification audit and the bulk act receipts are insert-only. */
class ReleaseSbomJustificationImmutabilityIntegrationTest extends ReleaseSbomJustificationTestBase {

	private static final List<String> AUDIT_COLUMNS = List.of(
			"uuid = gen_random_uuid()",
			"release_sbom = gen_random_uuid()",
			"org = gen_random_uuid()",
			"revision = revision + 1",
			"record_data = record_data || '{\"x\":1}'::jsonb",
			"asserted_by = gen_random_uuid()",
			"asserted_date = asserted_date + interval '1 day'",
			"reason = 'changed'",
			"bulk_act = gen_random_uuid()");

	private static final List<String> ACT_COLUMNS = List.of(
			"uuid = gen_random_uuid()",
			"org = gen_random_uuid()",
			"kind = 'LEVEL_OF_SUPPORT'",
			"release = gen_random_uuid()",
			"release_sbom = gen_random_uuid()",
			"selector = '{}'::jsonb",
			"matched_count = matched_count + 1",
			"applied_count = applied_count + 1",
			"list_hash = 'x'",
			"asserted_by = gen_random_uuid()",
			"asserted_date = asserted_date + interval '1 day'",
			"record_data = '{}'::jsonb");

	private void refused(String sql, Object id, String table, String operation) {
		DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
				() -> jdbc.update(sql, id), sql);
		assertTrue(e.getMessage().contains(table + " is immutable: " + operation + " of " + id + " refused"),
				e.getMessage());
	}

	private Map<String, Object> auditRow(UUID uuid) {
		return jdbc.queryForMap("SELECT *, record_data::text AS record FROM rearm.release_sbom_justification_audit "
				+ "WHERE uuid = ?", uuid);
	}

	private Map<String, Object> actRow(UUID uuid) {
		return jdbc.queryForMap("SELECT *, record_data::text AS record, selector::text AS sel FROM "
				+ "rearm.support_bulk_acts WHERE uuid = ?", uuid);
	}

	@Test
	void everyUpdateAndDeleteIsRefusedTheRepositoriesAreInsertOnlyAndTheMigrationReruns() throws Exception {
		World w = componentRelease(null);
		String key = keyOf("pkg:deb/debian/curl@7.88");
		UUID serial = headOf(w.release(), PROFILE, List.of(hashless("pkg:deb/debian/curl@7.88")));
		UUID caller = admin().getUuid();
		justificationService.set(head(serial), List.of(key), JustifiedUnknownReason.DISTRIBUTION_PUBLISHES_NO_HASH, null,
				"r", caller, null, Overwrite.REPLACE);
		UUID audit = jdbc.queryForObject("SELECT uuid FROM rearm.release_sbom_justification_audit WHERE release_sbom = ?",
				UUID.class, serial);
		UUID act = UUID.randomUUID();
		supportBulkActService.record(new SupportBulkActService.Receipt(act, org.getUuid(),
				SupportBulkActKind.HASH_JUSTIFICATION, w.release(), serial, Map.of("form", "TYPE", "type", "deb"),
				List.of(key), 1, caller, Map.of("code", "DISTRIBUTION_PUBLISHES_NO_HASH")));

		Map<String, Object> auditBefore = auditRow(audit);
		for (String change : AUDIT_COLUMNS) {
			refused("UPDATE rearm.release_sbom_justification_audit SET " + change + " WHERE uuid = ?", audit,
					"release_sbom_justification_audit", "update");
			assertEquals(auditBefore, auditRow(audit), change);
		}
		refused("DELETE FROM rearm.release_sbom_justification_audit WHERE uuid = ?", audit,
				"release_sbom_justification_audit", "delete");
		Map<String, Object> actBefore = actRow(act);
		assertEquals(1, actBefore.get("matched_count"));
		assertEquals(SupportBulkActService.listHash(List.of(key)), actBefore.get("list_hash"));
		for (String change : ACT_COLUMNS) {
			refused("UPDATE rearm.support_bulk_acts SET " + change + " WHERE uuid = ?", act, "support_bulk_acts",
					"update");
			assertEquals(actBefore, actRow(act), change);
		}
		refused("DELETE FROM rearm.support_bulk_acts WHERE uuid = ?", act, "support_bulk_acts", "delete");

		for (Class<?> repo : List.of(ReleaseSbomJustificationAuditRepository.class, SupportBulkActRepository.class)) {
			assertFalse(CrudRepository.class.isAssignableFrom(repo), repo.getName());
			assertTrue(Repository.class.isAssignableFrom(repo), repo.getName());
			for (Method m : repo.getMethods()) {
				String n = m.getName().toLowerCase();
				assertFalse(n.contains("delete") || n.contains("update") || n.contains("remove"), m.getName());
			}
		}

		String sql = new String(new ClassPathResource("db/migration/V109__release_sbom_justifications_bulk_acts.sql")
				.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
		jdbc.execute(sql);
		refused("DELETE FROM rearm.release_sbom_justification_audit WHERE uuid = ?", audit,
				"release_sbom_justification_audit", "delete");
		refused("DELETE FROM rearm.support_bulk_acts WHERE uuid = ?", act, "support_bulk_acts", "delete");
		assertEquals(auditBefore, auditRow(audit), "rerunnable, and still in force");
		assertEquals(actBefore, actRow(act));
	}
}
