/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import io.reliza.model.Integration;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.KevAssertionData;
import io.reliza.model.KevSource;
import io.reliza.model.VulnerabilityRecord;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.VulnerabilityRecordRepository;
import io.reliza.service.EncryptionService;
import io.reliza.service.KevAssertionService;
import io.reliza.service.KevAssertionService.KevCatalogApplyOutcome;
import io.reliza.service.VulnAnalysisUpdateService;
import io.reliza.service.VulnerabilityRecordChangeHook;

/**
 * Unit tests for {@link KevCatalogSyncService} orchestration in the per-org
 * (V54) model. Pins the per-org fan-out shape:
 * <ul>
 *   <li>The orchestrator iterates enabled {@code Integration} rows for
 *       {@code CISA_KEV} / {@code VULNCHECK_KEV} and dispatches per (org,
 *       source), passing the row's decrypted credential to the connector.
 *   <li>Fail-closed: an empty connector result skips that (org, source)
 *       reconcile.
 *   <li>Bootstrap-silent: the first per-org sync (zero existing rows for
 *       that {@code org_id}) applies the catalog but skips the KEV_ADDED
 *       event pass AND the re-gate fan-out.
 *   <li>Incremental: subsequent syncs emit KEV_ADDED for every record in
 *       the same org carrying a newly-KEV CVE, and fire the re-gate fan-out
 *       for that org.
 *   <li>CE-safety: a null hook no-ops the KEV_ADDED pass without breaking
 *       the reconcile.
 *   <li>{@code syncOrgSourceAsync} (configure-flow entry point) looks up
 *       the single integration row, decrypts, and reconciles for that one
 *       (org, source).
 * </ul>
 *
 * <p>{@code IntegrationRepository} and {@code EncryptionService} are mocked.
 * {@code PlatformTransactionManager} is a bare mock — the transaction
 * template's callback executes directly with a null status, which is fine
 * for the mock-driven assertions here. (Live DB transaction behaviour is
 * covered by the integration suite.)
 */
class KevCatalogSyncServiceTest {

	private KevSourceConnector cisaConnector;
	private KevSourceConnector vulnCheckConnector;
	private KevAssertionService kevAssertionService;
	private IntegrationRepository integrationRepository;
	private EncryptionService encryptionService;
	private VulnerabilityRecordRepository vulnerabilityRecordRepository;
	private VulnerabilityRecordChangeHook hook;
	private VulnAnalysisUpdateService vulnAnalysisUpdateService;
	private KevCatalogSyncService service;

	@BeforeEach
	void wireMocks() throws Exception {
		cisaConnector = mock(KevSourceConnector.class);
		when(cisaConnector.source()).thenReturn(KevSource.CISA);
		vulnCheckConnector = mock(KevSourceConnector.class);
		when(vulnCheckConnector.source()).thenReturn(KevSource.VULNCHECK);

		kevAssertionService = mock(KevAssertionService.class);
		integrationRepository = mock(IntegrationRepository.class);
		encryptionService = mock(EncryptionService.class);
		vulnerabilityRecordRepository = mock(VulnerabilityRecordRepository.class);
		hook = mock(VulnerabilityRecordChangeHook.class);
		vulnAnalysisUpdateService = mock(VulnAnalysisUpdateService.class);

		// Default empty integration lists — individual tests override.
		when(integrationRepository.listEnabledIntegrationsByType(anyString()))
				.thenReturn(List.of());

		service = new KevCatalogSyncService();
		inject("connectors", List.of(cisaConnector, vulnCheckConnector));
		inject("kevAssertionService", kevAssertionService);
		inject("integrationRepository", integrationRepository);
		inject("encryptionService", encryptionService);
		inject("vulnerabilityRecordRepository", vulnerabilityRecordRepository);
		inject("vulnerabilityRecordChangeHook", hook);
		inject("vulnAnalysisUpdateService", vulnAnalysisUpdateService);
		// Mocked manager makes TransactionTemplate.execute run the callback directly.
		inject("transactionManager", mock(PlatformTransactionManager.class));
	}

	private void inject(String field, Object value) throws Exception {
		Field f = KevCatalogSyncService.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(service, value);
	}

	// ---------- fixtures ----------

	private static Integration integrationRow(UUID orgUuid, IntegrationType type, String encryptedSecret) {
		Integration row = new Integration();
		row.setUuid(UUID.randomUUID());
		Map<String, Object> recordData = new LinkedHashMap<>();
		recordData.put("uuid", row.getUuid().toString());
		recordData.put("identifier", "base");
		recordData.put("isEnabled", true);
		recordData.put("org", orgUuid.toString());
		recordData.put("type", type.name());
		if (encryptedSecret != null) {
			recordData.put("secret", encryptedSecret);
		}
		row.setRecordData(recordData);
		return row;
	}

	private static List<KevAssertionData> oneEntry() {
		KevAssertionData kad = new KevAssertionData();
		kad.setCveId("CVE-2026-0001");
		return List.of(kad);
	}

	private static VulnerabilityRecord vulnRecord(UUID org, String primaryVulnId) {
		VulnerabilityRecord vr = new VulnerabilityRecord();
		vr.setUuid(UUID.randomUUID());
		vr.setOrg(org);
		vr.setPrimaryVulnId(primaryVulnId);
		Map<String, Object> recordData = new HashMap<>();
		recordData.put("severity", "HIGH");
		vr.setRecordData(recordData);
		return vr;
	}

	// ---------- tests ----------

	@Test
	void emptyConnectorResultSkipsReconcile() {
		UUID orgA = UUID.randomUUID();
		when(integrationRepository.listEnabledIntegrationsByType(IntegrationType.CISA_KEV.name()))
				.thenReturn(List.of(integrationRow(orgA, IntegrationType.CISA_KEV, null)));
		when(cisaConnector.fetchCatalog(isNull())).thenReturn(List.of());
		// Already-bootstrapped org — irrelevant since reconcile is skipped.
		when(kevAssertionService.countRecordsForOrg(orgA)).thenReturn(5L);

		assertEquals(0, service.syncCatalog());

		verify(kevAssertionService, never()).applyCatalog(any(UUID.class), any(KevSource.class), any());
	}

	@Test
	void firstSyncForOrgSkipsKevAddedAndReGate() {
		UUID orgA = UUID.randomUUID();
		when(integrationRepository.listEnabledIntegrationsByType(IntegrationType.CISA_KEV.name()))
				.thenReturn(List.of(integrationRow(orgA, IntegrationType.CISA_KEV, null)));
		when(cisaConnector.fetchCatalog(isNull())).thenReturn(oneEntry());
		when(kevAssertionService.countRecordsForOrg(orgA)).thenReturn(0L);
		when(kevAssertionService.applyCatalog(eq(orgA), eq(KevSource.CISA), any())).thenReturn(
				new KevCatalogApplyOutcome(List.of("CVE-2026-0001"), 0, 0, 0, 1));

		assertEquals(1, service.syncCatalog());

		verify(kevAssertionService).applyCatalog(eq(orgA), eq(KevSource.CISA), any());
		// Bootstrap silence: no event pass, no re-gate fan-out.
		verify(vulnerabilityRecordRepository, never()).findAllOrgsByVulnIdOrAlias(anyString());
		verify(hook, never()).onKevAdded(any(), anyString(), any());
		verify(vulnAnalysisUpdateService, never())
				.recomputeReleasesForNewlyKev(anyString(), any());
	}

	@Test
	void incrementalSyncFiresEventsAndReGateForAffectedOrgRecords() {
		UUID orgA = UUID.randomUUID();
		UUID orgB = UUID.randomUUID();
		when(integrationRepository.listEnabledIntegrationsByType(IntegrationType.CISA_KEV.name()))
				.thenReturn(List.of(integrationRow(orgA, IntegrationType.CISA_KEV, null)));
		when(cisaConnector.fetchCatalog(isNull())).thenReturn(oneEntry());
		when(kevAssertionService.countRecordsForOrg(orgA)).thenReturn(1L); // already-bootstrapped
		when(kevAssertionService.applyCatalog(eq(orgA), eq(KevSource.CISA), any())).thenReturn(
				new KevCatalogApplyOutcome(List.of("CVE-2026-0001"), 0, 0, 0, 1));
		// Two orgs carry the CVE in vuln records; only orgA's records get
		// KEV_ADDED + re-gate (per-org sync isolation).
		when(vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias("CVE-2026-0001"))
				.thenReturn(List.of(
						vulnRecord(orgA, "CVE-2026-0001"),
						vulnRecord(orgB, "GHSA-aaaa-bbbb-cccc")));

		assertEquals(1, service.syncCatalog());

		// KEV_ADDED fires only for the orgA record — orgB isn't this sync's org.
		verify(hook).onKevAdded(eq(orgA), eq("CVE-2026-0001"), any(VulnerabilityRecordData.class));
		verify(hook, never()).onKevAdded(eq(orgB), anyString(), any());
		// Re-gate fan-out fires for orgA's affected releases only.
		verify(vulnAnalysisUpdateService).recomputeReleasesForNewlyKev(
				eq("CVE-2026-0001"),
				argThat((Collection<UUID> orgs) -> orgs.size() == 1 && orgs.contains(orgA)));
	}

	@Test
	void incrementalSyncWithNoAffectedRecordsInOrgSkipsReGate() {
		UUID orgA = UUID.randomUUID();
		when(integrationRepository.listEnabledIntegrationsByType(IntegrationType.CISA_KEV.name()))
				.thenReturn(List.of(integrationRow(orgA, IntegrationType.CISA_KEV, null)));
		when(cisaConnector.fetchCatalog(isNull())).thenReturn(oneEntry());
		when(kevAssertionService.countRecordsForOrg(orgA)).thenReturn(1L);
		when(kevAssertionService.applyCatalog(eq(orgA), eq(KevSource.CISA), any())).thenReturn(
				new KevCatalogApplyOutcome(List.of("CVE-2026-0001"), 0, 0, 0, 1));
		when(vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias(anyString())).thenReturn(List.of());

		assertEquals(1, service.syncCatalog());

		verify(vulnAnalysisUpdateService, never()).recomputeReleasesForNewlyKev(anyString(), any());
		verify(hook, never()).onKevAdded(any(), anyString(), any());
	}

	@Test
	void absentHookNoOpsButStillReconcilesAndReGates() throws Exception {
		inject("vulnerabilityRecordChangeHook", null); // CE: no impl bean
		UUID orgA = UUID.randomUUID();
		when(integrationRepository.listEnabledIntegrationsByType(IntegrationType.CISA_KEV.name()))
				.thenReturn(List.of(integrationRow(orgA, IntegrationType.CISA_KEV, null)));
		when(cisaConnector.fetchCatalog(isNull())).thenReturn(oneEntry());
		when(kevAssertionService.countRecordsForOrg(orgA)).thenReturn(1L);
		when(kevAssertionService.applyCatalog(eq(orgA), eq(KevSource.CISA), any())).thenReturn(
				new KevCatalogApplyOutcome(List.of("CVE-2026-0001"), 0, 0, 0, 1));
		when(vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias("CVE-2026-0001"))
				.thenReturn(List.of(vulnRecord(orgA, "CVE-2026-0001")));

		assertEquals(1, service.syncCatalog());

		verify(kevAssertionService).applyCatalog(eq(orgA), eq(KevSource.CISA), any());
		// Hook is null in CE — KEV_ADDED no-ops cleanly. Re-gate still runs.
		verify(hook, never()).onKevAdded(any(), anyString(), any());
		verify(vulnAnalysisUpdateService).recomputeReleasesForNewlyKev(eq("CVE-2026-0001"), any());
	}

	@Test
	void vulnCheckRowWithBlankSecretYieldsEmptyConnectorResultAndSkipsReconcile() {
		UUID orgA = UUID.randomUUID();
		// Blank-secret row: decryptCredential returns null; connector treats
		// missing credential as a failed/empty fetch and returns an empty list.
		when(integrationRepository.listEnabledIntegrationsByType(IntegrationType.VULNCHECK_KEV.name()))
				.thenReturn(List.of(integrationRow(orgA, IntegrationType.VULNCHECK_KEV, null)));
		when(vulnCheckConnector.fetchCatalog(isNull())).thenReturn(List.of());

		assertEquals(0, service.syncCatalog());

		verify(kevAssertionService, never()).applyCatalog(eq(orgA), eq(KevSource.VULNCHECK), any());
	}

	@Test
	void twoOrgsBothWithCisaCallConnectorTwiceAndApplyPerOrg() {
		UUID orgA = UUID.randomUUID();
		UUID orgB = UUID.randomUUID();
		when(integrationRepository.listEnabledIntegrationsByType(IntegrationType.CISA_KEV.name()))
				.thenReturn(List.of(
						integrationRow(orgA, IntegrationType.CISA_KEV, null),
						integrationRow(orgB, IntegrationType.CISA_KEV, null)));
		when(cisaConnector.fetchCatalog(isNull())).thenReturn(oneEntry());
		// Both already bootstrapped — keep the test focused on the fan-out.
		when(kevAssertionService.countRecordsForOrg(any(UUID.class))).thenReturn(1L);
		when(kevAssertionService.applyCatalog(any(UUID.class), eq(KevSource.CISA), any())).thenReturn(
				new KevCatalogApplyOutcome(List.of(), 0, 0, 0, 1));

		assertEquals(0, service.syncCatalog());

		verify(cisaConnector, times(2)).fetchCatalog(isNull());
		verify(kevAssertionService).applyCatalog(eq(orgA), eq(KevSource.CISA), any());
		verify(kevAssertionService).applyCatalog(eq(orgB), eq(KevSource.CISA), any());
	}

	@Test
	void orgWithBothCisaAndVulnCheckEnabledSyncsBothSources() {
		UUID orgA = UUID.randomUUID();
		when(integrationRepository.listEnabledIntegrationsByType(IntegrationType.CISA_KEV.name()))
				.thenReturn(List.of(integrationRow(orgA, IntegrationType.CISA_KEV, null)));
		when(integrationRepository.listEnabledIntegrationsByType(IntegrationType.VULNCHECK_KEV.name()))
				.thenReturn(List.of(integrationRow(orgA, IntegrationType.VULNCHECK_KEV, "enc-token")));
		when(encryptionService.decrypt("enc-token")).thenReturn("plain-token");
		when(cisaConnector.fetchCatalog(isNull())).thenReturn(oneEntry());
		when(vulnCheckConnector.fetchCatalog(eq("plain-token"))).thenReturn(oneEntry());
		when(kevAssertionService.countRecordsForOrg(orgA)).thenReturn(1L);
		when(kevAssertionService.applyCatalog(eq(orgA), any(KevSource.class), any())).thenReturn(
				new KevCatalogApplyOutcome(List.of(), 0, 0, 0, 1));

		service.syncCatalog();

		verify(kevAssertionService).applyCatalog(eq(orgA), eq(KevSource.CISA), any());
		verify(kevAssertionService).applyCatalog(eq(orgA), eq(KevSource.VULNCHECK), any());
		verify(encryptionService).decrypt("enc-token");
	}

	@Test
	void syncOrgSourceAsyncLooksUpRowDecryptsAndApplies() {
		UUID orgA = UUID.randomUUID();
		Integration row = integrationRow(orgA, IntegrationType.VULNCHECK_KEV, "enc-token");
		when(integrationRepository.findIntegrationByOrgTypeIdentifier(
				orgA.toString(), IntegrationType.VULNCHECK_KEV.name(), "base"))
				.thenReturn(Optional.of(row));
		when(encryptionService.decrypt("enc-token")).thenReturn("plain-token");
		when(vulnCheckConnector.fetchCatalog(eq("plain-token"))).thenReturn(oneEntry());
		when(kevAssertionService.countRecordsForOrg(orgA)).thenReturn(0L); // first sync
		when(kevAssertionService.applyCatalog(eq(orgA), eq(KevSource.VULNCHECK), any())).thenReturn(
				new KevCatalogApplyOutcome(List.of("CVE-2026-0001"), 0, 0, 0, 1));

		service.syncOrgSourceAsync(orgA, IntegrationType.VULNCHECK_KEV);

		verify(encryptionService).decrypt("enc-token");
		verify(vulnCheckConnector).fetchCatalog(eq("plain-token"));
		verify(kevAssertionService).applyCatalog(eq(orgA), eq(KevSource.VULNCHECK), any());
		// First sync — KEV_ADDED + re-gate skipped.
		verify(hook, never()).onKevAdded(any(), anyString(), any());
		verify(vulnAnalysisUpdateService, never()).recomputeReleasesForNewlyKev(anyString(), any());
	}

	@Test
	void syncOrgSourceAsyncMissingRowIsNoOp() {
		UUID orgA = UUID.randomUUID();
		when(integrationRepository.findIntegrationByOrgTypeIdentifier(
				eq(orgA.toString()), anyString(), eq("base")))
				.thenReturn(Optional.empty());

		service.syncOrgSourceAsync(orgA, IntegrationType.VULNCHECK_KEV);

		verify(vulnCheckConnector, never()).fetchCatalog(anyString());
		verify(kevAssertionService, never()).applyCatalog(any(UUID.class), any(KevSource.class), any());
	}
}
