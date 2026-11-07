/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;

import io.reliza.model.Integration;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.KevAssertionData;
import io.reliza.model.KevSource;
import io.reliza.model.VulnerabilityRecord;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.VulnerabilityRecordRepository;
import io.reliza.service.EncryptionService;
import io.reliza.service.IntegrationService;
import io.reliza.service.KevAssertionService;
import io.reliza.service.KevAssertionService.KevCatalogApplyOutcome;
import io.reliza.service.VulnAnalysisUpdateService;
import io.reliza.service.VulnerabilityRecordChangeHook;

/**
 * FDAW-2 (B2): after a committed catalog apply the sync stamps the catalog header and its own instant on
 * the integration row through the in-place repository stamp -- never the audited whole-row save; a
 * failed apply or an empty fetch stamps nothing. Design T9. Same mock wiring as
 * {@link KevCatalogSyncServiceTest}.
 */
class KevCatalogSyncStampTest {

	private static final Instant DATE_RELEASED = Instant.parse("2026-11-04T17:58:23.6436Z");

	private KevSourceConnector cisaConnector;
	private KevSourceConnector vulnCheckConnector;
	private KevAssertionService kevAssertionService;
	private IntegrationRepository integrationRepository;
	private EncryptionService encryptionService;
	private VulnerabilityRecordRepository vulnerabilityRecordRepository;
	private VulnAnalysisUpdateService vulnAnalysisUpdateService;
	private KevCatalogSyncService service;

	@BeforeEach
	void wireMocks() {
		cisaConnector = mock(KevSourceConnector.class);
		when(cisaConnector.source()).thenReturn(KevSource.CISA);
		vulnCheckConnector = mock(KevSourceConnector.class);
		when(vulnCheckConnector.source()).thenReturn(KevSource.VULNCHECK);
		kevAssertionService = mock(KevAssertionService.class);
		integrationRepository = mock(IntegrationRepository.class);
		encryptionService = mock(EncryptionService.class);
		vulnerabilityRecordRepository = mock(VulnerabilityRecordRepository.class);
		vulnAnalysisUpdateService = mock(VulnAnalysisUpdateService.class);
		when(vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias(anyString())).thenReturn(List.of());
		when(integrationRepository.listEnabledIntegrationsByType(anyString())).thenReturn(List.of());
		when(kevAssertionService.countRecordsForOrg(any(UUID.class))).thenReturn(1L);

		service = new KevCatalogSyncService();
		ReflectionTestUtils.setField(service, "connectors", List.of(cisaConnector, vulnCheckConnector));
		ReflectionTestUtils.setField(service, "kevAssertionService", kevAssertionService);
		ReflectionTestUtils.setField(service, "integrationRepository", integrationRepository);
		ReflectionTestUtils.setField(service, "encryptionService", encryptionService);
		ReflectionTestUtils.setField(service, "vulnerabilityRecordRepository", vulnerabilityRecordRepository);
		ReflectionTestUtils.setField(service, "vulnerabilityRecordChangeHook", mock(VulnerabilityRecordChangeHook.class));
		ReflectionTestUtils.setField(service, "vulnAnalysisUpdateService", vulnAnalysisUpdateService);
		// Mocked manager makes TransactionTemplate.execute run the callback directly.
		ReflectionTestUtils.setField(service, "transactionManager", mock(PlatformTransactionManager.class));
	}

	private static Integration integrationRow(UUID orgUuid, IntegrationType type, String encryptedSecret) {
		Integration row = new Integration();
		row.setUuid(UUID.randomUUID());
		Map<String, Object> recordData = new LinkedHashMap<>();
		recordData.put("uuid", row.getUuid().toString());
		recordData.put("identifier", "base");
		recordData.put("isEnabled", true);
		recordData.put("org", orgUuid.toString());
		recordData.put("type", type.name());
		if (encryptedSecret != null) recordData.put("secret", encryptedSecret);
		row.setRecordData(recordData);
		return row;
	}

	private static List<KevAssertionData> oneEntry() {
		KevAssertionData kad = new KevAssertionData();
		kad.setCveId("CVE-2026-0001");
		return List.of(kad);
	}

	private Integration cisaRowFor(UUID org) {
		Integration row = integrationRow(org, IntegrationType.CISA_KEV, null);
		when(integrationRepository.listEnabledIntegrationsByType(IntegrationType.CISA_KEV.name())).thenReturn(List.of(row));
		return row;
	}

	@Test
	void aCommittedApplyStampsTheHeaderAndAUtcSyncInstantOnTheRow() {
		UUID org = UUID.randomUUID();
		Integration row = cisaRowFor(org);
		when(cisaConnector.fetchCatalog(isNull())).thenReturn(new KevCatalogFetch(oneEntry(), "2026.11.04", DATE_RELEASED));
		when(kevAssertionService.applyCatalog(eq(org), eq(KevSource.CISA), any()))
				.thenReturn(new KevCatalogApplyOutcome(List.of(), 0, 0, 0, 1));
		Instant before = Instant.now();

		service.syncCatalog();

		ArgumentCaptor<String> syncedAt = ArgumentCaptor.forClass(String.class);
		verify(integrationRepository, times(1)).stampKevCatalogState(eq(row.getUuid()), eq("2026.11.04"),
				eq("2026-11-04T17:58:23.643600Z"), syncedAt.capture());
		assertFalse(syncedAt.getValue().contains("["), syncedAt.getValue());
		Instant synced = assertDoesNotThrow(() -> Instant.parse(syncedAt.getValue()));
		assertFalse(synced.isBefore(before.minusSeconds(1)), syncedAt.getValue());
	}

	@Test
	void aSourceWithoutAHeaderStampsNullsAndTheSyncInstant() {
		UUID org = UUID.randomUUID();
		Integration row = integrationRow(org, IntegrationType.VULNCHECK_KEV, "enc");
		when(integrationRepository.findIntegrationByOrgTypeIdentifier(org.toString(), IntegrationType.VULNCHECK_KEV.name(),
				"base")).thenReturn(Optional.of(row));
		when(encryptionService.decrypt("enc")).thenReturn("token");
		when(vulnCheckConnector.fetchCatalog(eq("token"))).thenReturn(KevCatalogFetch.entriesOnly(oneEntry()));
		when(kevAssertionService.applyCatalog(eq(org), eq(KevSource.VULNCHECK), any()))
				.thenReturn(new KevCatalogApplyOutcome(List.of(), 0, 0, 0, 1));

		service.syncOrgSourceAsync(org, IntegrationType.VULNCHECK_KEV);

		verify(integrationRepository).stampKevCatalogState(eq(row.getUuid()), isNull(), isNull(), anyString());
	}

	@Test
	void aFailedApplyNeverStamps() {
		UUID org = UUID.randomUUID();
		cisaRowFor(org);
		when(cisaConnector.fetchCatalog(isNull())).thenReturn(new KevCatalogFetch(oneEntry(), "v", DATE_RELEASED));
		when(kevAssertionService.applyCatalog(eq(org), eq(KevSource.CISA), any()))
				.thenThrow(new IllegalStateException("apply failed"));

		service.syncCatalog();

		verify(integrationRepository, never()).stampKevCatalogState(any(), any(), any(), any());
	}

	@Test
	void aFailedStampIsLoggedAndTheReGateStillRuns() {
		UUID org = UUID.randomUUID();
		cisaRowFor(org);
		when(cisaConnector.fetchCatalog(isNull())).thenReturn(new KevCatalogFetch(oneEntry(), "v", DATE_RELEASED));
		when(kevAssertionService.applyCatalog(eq(org), eq(KevSource.CISA), any()))
				.thenReturn(new KevCatalogApplyOutcome(List.of("CVE-2026-0001"), 0, 0, 0, 1));
		VulnerabilityRecord vr = new VulnerabilityRecord();
		vr.setUuid(UUID.randomUUID());
		vr.setOrg(org);
		vr.setPrimaryVulnId("CVE-2026-0001");
		vr.setRecordData(new HashMap<>(Map.of("severity", "HIGH")));
		when(vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias("CVE-2026-0001")).thenReturn(List.of(vr));
		when(integrationRepository.stampKevCatalogState(any(), any(), any(), any()))
				.thenThrow(new IllegalStateException("stamp failed"));

		assertEquals(1, service.syncCatalog());

		verify(vulnAnalysisUpdateService, times(1)).recomputeReleasesForNewlyKev(eq(List.of("CVE-2026-0001")), eq(org));
	}

	@Test
	void anEmptyFetchNeitherAppliesNorStamps() {
		UUID org = UUID.randomUUID();
		cisaRowFor(org);
		when(cisaConnector.fetchCatalog(isNull())).thenReturn(KevCatalogFetch.EMPTY);

		service.syncCatalog();

		verify(kevAssertionService, never()).applyCatalog(any(UUID.class), any(KevSource.class), any());
		verify(integrationRepository, never()).stampKevCatalogState(any(), any(), any(), any());
	}

	@Test
	void theSyncDoesNotDependOnTheAuditedIntegrationSave() {
		for (Field f : KevCatalogSyncService.class.getDeclaredFields()) {
			assertFalse(IntegrationService.class.isAssignableFrom(f.getType()), f.getName());
		}
		assertEquals(0, Arrays.stream(KevCatalogSyncService.class.getDeclaredFields())
				.filter(f -> f.getType().getSimpleName().equals("IntegrationService")).count());
	}
}
