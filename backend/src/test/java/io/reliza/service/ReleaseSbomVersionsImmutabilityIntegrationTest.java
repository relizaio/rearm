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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;

import io.reliza.model.ReleaseSbomRef;
import io.reliza.model.ReleaseSbomVersion;
import io.reliza.model.ReleaseSbomVersionData;
import io.reliza.model.ReleaseSbomWriter;
import io.reliza.repositories.ReleaseSbomVersionRepository;

/**
 * FDAW-7 row 1: the V108 row trigger refuses every UPDATE and DELETE on release_sbom_versions from any
 * SQL path, the entity and repository are insert-only, the migration reruns, and the head row accepts
 * its update.
 */
class ReleaseSbomVersionsImmutabilityIntegrationTest extends ReleaseSbomIntegrationTestBase {

	@Autowired ReleaseSbomVersionRepository versionRepository;

	private static final List<String> COLUMNS = List.of(
			"uuid = gen_random_uuid()",
			"version = version + 100",
			"org = gen_random_uuid()",
			"release = gen_random_uuid()",
			"profile_name = profile_name || 'x'",
			"artifact = gen_random_uuid()",
			"sha256 = repeat('0', 64)",
			"content_digest = repeat('0', 64)",
			"created_date = created_date + interval '1 day'",
			"created_by = gen_random_uuid()",
			"schema_version = schema_version + 1",
			"record_data = record_data || '{\"x\":1}'::jsonb");

	private Map<String, Object> row(UUID serial) {
		return jdbc.queryForMap("SELECT *, record_data::text AS record FROM rearm.release_sbom_versions WHERE uuid = ? "
				+ "AND version = 1", serial);
	}

	private void refused(String sql, UUID serial, String operation) {
		DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
				() -> jdbc.update(sql, serial), sql);
		assertTrue(e.getMessage().contains("release_sbom_versions is immutable: " + operation + " of " + serial
				+ " version 1 refused"), e.getMessage());
	}

	@Test
	void everyUpdateAndDeleteIsRefusedTheRowIsInsertOnlyAndTheMigrationReruns() throws Exception {
		World w = componentRelease("pkg:generic/pump@1.0.0");
		ReleaseSbomRef pin = ensure(w.release(), doc("pkg:generic/pump@1.0.0"), ReleaseSbomWriter.pkg(null)).pin();
		Map<String, Object> before = row(pin.serial());

		for (String change : COLUMNS) {
			refused("UPDATE rearm.release_sbom_versions SET " + change + " WHERE uuid = ? AND version = 1", pin.serial(),
					"update");
			assertEquals(before, row(pin.serial()), change);
		}
		refused("DELETE FROM rearm.release_sbom_versions WHERE uuid = ?", pin.serial(), "delete");
		assertEquals(before, row(pin.serial()));

		// insert-only through JPA too: a second save of the key is an INSERT and fails on the primary key
		ReleaseSbomVersionData copy = versionRepository.findVersion(pin.serial(), 1).orElseThrow().toData();
		copy.setSha256("1".repeat(64));
		assertThrows(DataIntegrityViolationException.class, () -> versionRepository.save(new ReleaseSbomVersion(copy)));
		assertEquals(before, row(pin.serial()));

		// the repository is Repository-typed and declares no update or delete
		assertFalse(CrudRepository.class.isAssignableFrom(ReleaseSbomVersionRepository.class));
		assertTrue(Repository.class.isAssignableFrom(ReleaseSbomVersionRepository.class));
		for (Method m : ReleaseSbomVersionRepository.class.getMethods()) {
			String n = m.getName().toLowerCase();
			assertFalse(n.contains("delete") || n.contains("update") || n.contains("remove"), m.getName());
		}

		// the migration reruns on a migrated database and the trigger is still in force
		String sql = new String(new ClassPathResource("db/migration/V108__release_sboms.sql").getInputStream()
				.readAllBytes(), StandardCharsets.UTF_8);
		jdbc.execute(sql);
		refused("UPDATE rearm.release_sbom_versions SET sha256 = repeat('0', 64) WHERE uuid = ?", pin.serial(), "update");
		assertEquals(before, row(pin.serial()), "rerunnable, and still in force");

		// the head row accepts its update (a bump moves it)
		assertEquals(1, jdbc.update("UPDATE rearm.release_sboms SET latest_version = latest_version WHERE uuid = ?",
				pin.serial()));
		assertEquals(1, versionRows(pin.serial()).size());
	}
}
