/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.Repository;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

import io.reliza.model.SbomComponent;
import io.reliza.model.SupportAttestationRequest;
import io.reliza.repositories.SbomComponentRepository;
import io.reliza.repositories.SbomComponentSupportAuditRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * FDAW-8 T-2 (FDAW-1 F-8 item 1): the V106 row trigger makes rearm.sbom_component_support_audit
 * insert-only from every SQL path, and its repository offers the insert and the two reads only.
 * Shared: the CE mirror runs it too.
 */
@ExtendWith(SpringExtension.class)
@SpringBootTest(classes = {App.class})
class SbomComponentSupportAuditImmutabilityIntegrationTest {

	@Autowired private SbomComponentService sbomComponentService;
	@Autowired private SbomComponentRepository sbomComponentRepository;
	@Autowired private TestInitializer testInitializer;
	@Autowired private JdbcTemplate jdbc;

	private void refused(String sql, UUID row, String operation) {
		DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
				() -> jdbc.update(sql, row), sql);
		assertTrue(e.getMessage().contains("sbom_component_support_audit is immutable: " + operation + " of " + row
				+ " refused"), e.getMessage());
	}

	@Test
	void anAttestationsAuditRowIsRefusedEveryUpdateAndDelete() throws Exception {
		UUID org = testInitializer.obtainOrganization().getUuid();
		SbomComponent sc = new SbomComponent();
		sc.setOrg(org);
		sc.setCanonicalPurl("pkg:maven/org.example/fdaw8@1.0.0?t=" + UUID.randomUUID());
		sc = sbomComponentRepository.save(sc);
		sbomComponentService.setSbomComponentSupport(sc.getUuid(), new SupportAttestationRequest(null, null, null, null,
				null, null, LocalDate.now().plusYears(1), null, "vendor notice", Set.of(), null), UUID.randomUUID());
		UUID row = jdbc.queryForObject("SELECT uuid FROM rearm.sbom_component_support_audit WHERE sbom_component_uuid = ?",
				UUID.class, sc.getUuid());
		Map<String, Object> before = jdbc.queryForMap("SELECT *, support_data::text AS d FROM "
				+ "rearm.sbom_component_support_audit WHERE uuid = ?", row);

		for (String set : List.of("support_data = '{}'::jsonb", "reason = 'edited'", "asserted_by = gen_random_uuid()",
				"asserted_date = asserted_date - interval '1 day'", "org = gen_random_uuid()")) {
			refused("UPDATE rearm.sbom_component_support_audit SET " + set + " WHERE uuid = ?", row, "update");
		}
		refused("DELETE FROM rearm.sbom_component_support_audit WHERE uuid = ?", row, "delete");
		assertEquals(before, jdbc.queryForMap("SELECT *, support_data::text AS d FROM "
				+ "rearm.sbom_component_support_audit WHERE uuid = ?", row));
	}

	@Test
	void theRepositoryOffersTheInsertAndTheTwoReadsOnly() {
		assertTrue(Repository.class.isAssignableFrom(SbomComponentSupportAuditRepository.class));
		assertFalse(CrudRepository.class.isAssignableFrom(SbomComponentSupportAuditRepository.class));
		assertEquals(List.of("findByBatchIdAndOrgOrderByAssertedDateAsc", "findBySbomComponentUuidOrderByAssertedDateDesc",
				"save"), Arrays.stream(SbomComponentSupportAuditRepository.class.getMethods()).map(Method::getName)
						.sorted().toList());
	}
}
