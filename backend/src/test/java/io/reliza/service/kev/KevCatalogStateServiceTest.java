/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import static io.reliza.testsupport.StampAssertions.assertJsonType;
import static io.reliza.testsupport.StampAssertions.assertStampedOnly;
import static io.reliza.testsupport.StampAssertions.snapshot;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.KevSource;
import io.reliza.model.WhoUpdated;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.service.IntegrationService;
import io.reliza.service.kev.KevCatalogStateService.KevCatalogState;
import io.reliza.testsupport.StampAssertions.RowSnapshot;
import io.reliza.ws.App;

/**
 * FDAW-2 (B2), design T10, against the test database: which KEV catalog an organisation's VDR names,
 * and the sync's in-place stamp of the catalog header on the integration row -- no revision, no
 * last_updated_date, no audit row, secret and isEnabled untouched, and kept by a later admin save.
 * Each case runs on an organisation uuid of its own, so no other test's integration rows (or the
 * CISA_KEV row an organisation is created with) are read.
 */
@SpringBootTest(classes = {App.class})
public class KevCatalogStateServiceTest {

	private static final WhoUpdated WU = WhoUpdated.getTestWhoUpdated();
	private static final String CATALOG_DATE = "2026-11-04T17:58:23.643600Z";
	private static final String SYNCED_AT = "2026-11-05T01:00:00Z";
	private static final Set<String> STAMPED = Set.of("kevCatalogVersion", "kevCatalogDateReleased", "kevSyncedAt");

	@Autowired private KevCatalogStateService kevCatalogStateService;
	@Autowired private IntegrationService integrationService;
	@Autowired private IntegrationRepository integrationRepository;
	@Autowired private JdbcTemplate jdbc;

	private UUID row(UUID org, IntegrationType type, boolean enabled, String token) {
		return integrationService.upsertKevIntegration(org, type, enabled, token, WU).getUuid();
	}

	private IntegrationData data(UUID row) {
		return IntegrationData.dataFromRecord(integrationRepository.findById(row).orElseThrow());
	}

	@Test
	void anEnabledSyncedCisaRowNamesCisaItsDateAndItsSync() {
		UUID org = UUID.randomUUID();
		UUID cisa = row(org, IntegrationType.CISA_KEV, true, null);
		integrationRepository.stampKevCatalogState(cisa, "2026.11.04", CATALOG_DATE, SYNCED_AT);
		assertEquals(Optional.of(new KevCatalogState(KevSource.CISA, Instant.parse(CATALOG_DATE),
				Instant.parse(SYNCED_AT))), kevCatalogStateService.forOrg(org));
	}

	@Test
	void onlyVulnCheckNamesVulnCheck_withoutACatalogDate() {
		UUID org = UUID.randomUUID();
		UUID vc = row(org, IntegrationType.VULNCHECK_KEV, true, "tok-" + UUID.randomUUID());
		integrationRepository.stampKevCatalogState(vc, null, null, SYNCED_AT);
		assertEquals(Optional.of(new KevCatalogState(KevSource.VULNCHECK, null, Instant.parse(SYNCED_AT))),
				kevCatalogStateService.forOrg(org));
	}

	@Test
	void noRowOrADisabledRowIsNoSource() {
		UUID org = UUID.randomUUID();
		assertTrue(kevCatalogStateService.forOrg(org).isEmpty());
		row(org, IntegrationType.CISA_KEV, false, null);
		assertTrue(kevCatalogStateService.forOrg(org).isEmpty());
		assertTrue(kevCatalogStateService.forOrg(null).isEmpty());
	}

	@Test
	void bothEnabledNamesCisa_andAnUnsyncedRowHasNoDates() {
		UUID org = UUID.randomUUID();
		row(org, IntegrationType.VULNCHECK_KEV, true, "tok-" + UUID.randomUUID());
		row(org, IntegrationType.CISA_KEV, true, null);
		assertEquals(Optional.of(new KevCatalogState(KevSource.CISA, null, null)), kevCatalogStateService.forOrg(org));
	}

	@Test
	void theStampWritesTheThreeKeysOnly_noRevisionNoAuditRow_secretAndEnabledUntouched() {
		UUID org = UUID.randomUUID();
		UUID vc = row(org, IntegrationType.VULNCHECK_KEV, true, "tok-" + UUID.randomUUID());
		IntegrationData before = data(vc);
		RowSnapshot snapBefore = snapshot(jdbc, TableName.INTEGRATIONS, vc);

		assertEquals(1, integrationRepository.stampKevCatalogState(vc, "2026.11.04", CATALOG_DATE, SYNCED_AT));

		assertStampedOnly(snapBefore, snapshot(jdbc, TableName.INTEGRATIONS, vc), STAMPED);
		IntegrationData after = data(vc);
		assertEquals("2026.11.04", after.getKevCatalogVersion());
		assertEquals(CATALOG_DATE, after.getKevCatalogDateReleased());
		assertEquals(SYNCED_AT, after.getKevSyncedAt());
		assertEquals(before.getSecret(), after.getSecret());
		assertEquals(before.getIsEnabled(), after.getIsEnabled());
		assertEquals(0, integrationRepository.stampKevCatalogState(UUID.randomUUID(), "x", null, SYNCED_AT));
	}

	@Test
	void aNullHeaderIsAJsonNullThatReadsAsNull() {
		UUID org = UUID.randomUUID();
		UUID cisa = row(org, IntegrationType.CISA_KEV, true, null);
		integrationRepository.stampKevCatalogState(cisa, "v1", CATALOG_DATE, SYNCED_AT);
		integrationRepository.stampKevCatalogState(cisa, null, null, SYNCED_AT);
		assertJsonType(jdbc, TableName.INTEGRATIONS, cisa, "kevCatalogVersion", "null");
		assertJsonType(jdbc, TableName.INTEGRATIONS, cisa, "kevCatalogDateReleased", "null");
		assertNull(data(cisa).getKevCatalogVersion());
		assertNull(data(cisa).getKevCatalogDateReleased());
		assertEquals(Optional.of(new KevCatalogState(KevSource.CISA, null, Instant.parse(SYNCED_AT))),
				kevCatalogStateService.forOrg(org));
	}

	@Test
	void aLaterAdminSaveKeepsTheStamp() {
		UUID org = UUID.randomUUID();
		UUID vc = row(org, IntegrationType.VULNCHECK_KEV, true, "tok-" + UUID.randomUUID());
		integrationRepository.stampKevCatalogState(vc, null, null, SYNCED_AT);

		integrationService.upsertKevIntegration(org, IntegrationType.VULNCHECK_KEV, false, "tok-" + UUID.randomUUID(), WU);

		IntegrationData after = data(vc);
		assertEquals(SYNCED_AT, after.getKevSyncedAt());
		assertEquals(Boolean.FALSE, after.getIsEnabled());
	}
}
