/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.common.CommonVariables.StatusEnum;
import io.reliza.model.Organization;
import io.reliza.model.OrgTotalsTable;
import io.reliza.model.OrganizationData;
import io.reliza.model.OrganizationData.TeaServing;
import io.reliza.model.OrganizationData.TeaWellKnownHosting;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.OrganizationRepository;
import io.reliza.ws.App;
import io.reliza.ws.oss.TestInitializer;

/**
 * TPF-142, architecture section 4 row 25 (F-2): both whole-record organization funnels carry the stored TEA serving
 * enums forward under the row lock (withStoredTeaServing), so a save from a stale copy cannot carry DOMAIN back;
 * setTeaServing (WRITE_REQUESTED) writes the requested values and still keeps the stored teaUuid, status and totals.
 */
@SpringBootTest(classes = {App.class}, properties = "relizaprops.teaCustomDomains=tea-a.test,tea-b.test,Tea-C.test.")
public class OrganizationTeaServingCarryForwardIntegrationTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();

	@Autowired private TestInitializer testInitializer;
	@Autowired private OrganizationService organizationService;
	@Autowired private OrganizationRepository organizationRepository;
	@Autowired private GetOrganizationService getOrganizationService;
	@Autowired private TeaHostAssignmentService teaHostAssignmentService;
	@Autowired private PlatformTransactionManager transactionManager;
	@Autowired private JdbcTemplate jdbcTemplate;

	private final ExecutorService pool = Executors.newSingleThreadExecutor();

	@BeforeEach
	void clearHostMap() {
		jdbcTemplate.update("UPDATE rearm.system_info SET data = data - 'teaHostAssignments' WHERE id = 1");
	}

	@AfterEach
	void clearHostMapAndPool() {
		pool.shutdownNow();
		jdbcTemplate.update("UPDATE rearm.system_info SET data = data - 'teaHostAssignments' WHERE id = 1");
	}

	private String storedKey(UUID org, String key) {
		return jdbcTemplate.queryForObject("SELECT record_data->>'" + key + "' FROM rearm.organizations WHERE uuid = ?", String.class, org);
	}

	private long revision(UUID org) {
		return jdbcTemplate.queryForObject("SELECT revision FROM rearm.organizations WHERE uuid = ?", Long.class, org);
	}

	/** A committed NONE/NONE in another thread, while the caller's transaction holds a DOMAIN/DOMAIN copy. */
	private void noneElsewhere(UUID org) {
		try {
			pool.submit(() -> organizationService.setTeaServing(org, TeaServing.NONE, TeaWellKnownHosting.NONE, WU)).get(60, TimeUnit.SECONDS);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private UUID domainOrg() throws Exception {
		Organization o = testInitializer.obtainOrganization();
		teaHostAssignmentService.assign("tea-a.test", o.getUuid(), null);
		organizationService.setTeaServing(o.getUuid(), TeaServing.DOMAIN, TeaWellKnownHosting.DOMAIN, WU);
		return o.getUuid();
	}

	@Test
	void withStoredTeaServingPutsThePresentValuesAndRemovesTheAbsentOnes() {
		Map<String, Object> copy = new LinkedHashMap<>();
		copy.put("name", "x");
		copy.put(OrganizationData.TEA_SERVING_FIELD, "DOMAIN");
		copy.put(OrganizationData.TEA_WELL_KNOWN_HOSTING_FIELD, "DOMAIN");
		Map<String, Object> kept = OrganizationData.withStoredTeaServing(copy, Optional.of("NONE"), Optional.of("NONE"));
		assertEquals("NONE", kept.get(OrganizationData.TEA_SERVING_FIELD));
		assertEquals("NONE", kept.get(OrganizationData.TEA_WELL_KNOWN_HOSTING_FIELD));
		Map<String, Object> absent = OrganizationData.withStoredTeaServing(copy, Optional.empty(), Optional.empty());
		assertFalse(absent.containsKey(OrganizationData.TEA_SERVING_FIELD));
		assertFalse(absent.containsKey(OrganizationData.TEA_WELL_KNOWN_HOSTING_FIELD));
		assertEquals("x", absent.get("name"));
		assertEquals("DOMAIN", copy.get(OrganizationData.TEA_SERVING_FIELD), "the input is not modified");
	}

	@Test
	void theThreeArgumentFunnelWritesTheStoredModeBack() throws Exception {
		UUID org = domainOrg();
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		long copyRevision = revision(org);
		tx.executeWithoutResult(s -> {
			assertEquals(TeaServing.DOMAIN, OrganizationData.orgDataFromDbRecord(organizationRepository.findById(org).orElseThrow()).getTeaServing());
			noneElsewhere(org);
			organizationService.updateTerminology(org, "Release Line", WU);
		});
		assertEquals("NONE", storedKey(org, OrganizationData.TEA_SERVING_FIELD));
		assertEquals("NONE", storedKey(org, OrganizationData.TEA_WELL_KNOWN_HOSTING_FIELD));
		// as before TPF-142, the funnel bumps the revision of the copy it is handed
		assertEquals(copyRevision + 1, revision(org), "the save bumps the revision as before");
	}

	@Test
	void theRequestedWriteKeepsTheStoredTeaIdStatusAndTotals() throws Exception {
		Organization o = testInitializer.obtainOrganization();
		UUID tea = organizationService.ensureTeaUuid(o.getUuid(), WU);
		assertEquals(1, organizationRepository.stampTotal(o.getUuid(), OrgTotalsTable.RELEASES.getMapKey(), 42L, Instant.now().toString()));
		organizationService.setTeaServing(o.getUuid(), TeaServing.LEGACY_UUID, TeaWellKnownHosting.NONE, WU);
		OrganizationData od = OrganizationData.orgDataFromDbRecord(organizationRepository.findById(o.getUuid()).orElseThrow());
		assertEquals(TeaServing.LEGACY_UUID, od.getTeaServing());
		assertEquals(TeaWellKnownHosting.NONE, od.getTeaWellKnownHosting());
		assertEquals(tea, od.getTeaUuid());
		assertEquals(Long.valueOf(42L), od.totalsCount(OrgTotalsTable.RELEASES));
		assertTrue(StatusEnum.ARCHIVED != od.getStatus());
	}
}
