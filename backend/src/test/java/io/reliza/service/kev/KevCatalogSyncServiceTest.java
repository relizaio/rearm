/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.service.kev;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;

import io.reliza.model.KevAssertionData;
import io.reliza.model.KevSource;
import io.reliza.model.VulnerabilityRecord;
import io.reliza.model.VulnerabilityRecordData;
import io.reliza.repositories.VulnerabilityRecordRepository;
import io.reliza.service.KevAssertionService;
import io.reliza.service.KevAssertionService.KevCatalogApplyOutcome;
import io.reliza.service.VulnAnalysisUpdateService;
import io.reliza.service.VulnerabilityRecordChangeHook;

/**
 * Unit tests for {@link KevCatalogSyncService} orchestration. The connector,
 * assertion service, vuln repo and notification hook are mocked. Pins the
 * fail-closed contract (an empty connector result skips reconcile), the
 * bootstrap event-storm guard, the per-affected-record KEV_ADDED emission,
 * and CE-safety (a null hook no-ops without breaking the read-path sync).
 */
class KevCatalogSyncServiceTest {

	private KevSourceConnector connector;
	private KevAssertionService kevAssertionService;
	private VulnerabilityRecordRepository vulnerabilityRecordRepository;
	private VulnerabilityRecordChangeHook hook;
	private VulnAnalysisUpdateService vulnAnalysisUpdateService;
	private KevCatalogSyncService service;

	@BeforeEach
	void wireMocks() throws Exception {
		connector = mock(KevSourceConnector.class);
		when(connector.source()).thenReturn(KevSource.CISA);
		kevAssertionService = mock(KevAssertionService.class);
		vulnerabilityRecordRepository = mock(VulnerabilityRecordRepository.class);
		hook = mock(VulnerabilityRecordChangeHook.class);
		vulnAnalysisUpdateService = mock(VulnAnalysisUpdateService.class);

		service = new KevCatalogSyncService();
		inject("connectors", List.of(connector));
		inject("kevAssertionService", kevAssertionService);
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

	private static List<KevAssertionData> oneEntry() {
		KevAssertionData kad = new KevAssertionData();
		kad.setCveId("CVE-2026-0001");
		return List.of(kad);
	}

	@Test
	void emptyConnectorResultSkipsReconcile() {
		when(connector.fetchCatalog()).thenReturn(List.of());
		when(kevAssertionService.countRecords()).thenReturn(5L);

		assertEquals(0, service.syncCatalog());

		verify(kevAssertionService, never()).applyCatalog(any(), any());
	}

	@Test
	void bootstrapSkipsEventPass() {
		when(connector.fetchCatalog()).thenReturn(oneEntry());
		when(kevAssertionService.countRecords()).thenReturn(0L);
		when(kevAssertionService.applyCatalog(eq(KevSource.CISA), any())).thenReturn(
				new KevCatalogApplyOutcome(List.of("CVE-2026-0001"), 0, 0, 0, 1));

		assertEquals(1, service.syncCatalog());

		verify(vulnerabilityRecordRepository, never()).findAllOrgsByVulnIdOrAlias(anyString());
		verify(hook, never()).onKevAdded(any(), anyString(), any());
		// Bootstrap must NOT fan out re-gate recomputes (would storm).
		verify(vulnAnalysisUpdateService, never()).recomputeReleasesForNewlyKev(anyString(), any());
	}

	@Test
	void incrementalAdditionFansOutReGateForAffectedOrgs() {
		when(connector.fetchCatalog()).thenReturn(oneEntry());
		when(kevAssertionService.countRecords()).thenReturn(1L);
		when(kevAssertionService.applyCatalog(eq(KevSource.CISA), any())).thenReturn(
				new KevCatalogApplyOutcome(List.of("CVE-2026-0001"), 0, 0, 0, 1));
		UUID orgA = UUID.randomUUID();
		UUID orgB = UUID.randomUUID();
		when(vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias("CVE-2026-0001"))
				.thenReturn(List.of(
						vulnRecord(orgA, "CVE-2026-0001"),
						vulnRecord(orgB, "GHSA-aaaa-bbbb-cccc")));

		assertEquals(1, service.syncCatalog());

		// Re-gate fan-out fires once for the CVE with the distinct affected orgs.
		verify(vulnAnalysisUpdateService).recomputeReleasesForNewlyKev(
				eq("CVE-2026-0001"),
				argThat((Collection<UUID> orgs) ->
						orgs.size() == 2 && Set.copyOf(orgs).equals(Set.of(orgA, orgB))));
	}

	@Test
	void incrementalAdditionWithNoAffectedOrgsSkipsReGate() {
		when(connector.fetchCatalog()).thenReturn(oneEntry());
		when(kevAssertionService.countRecords()).thenReturn(1L);
		when(kevAssertionService.applyCatalog(eq(KevSource.CISA), any())).thenReturn(
				new KevCatalogApplyOutcome(List.of("CVE-2026-0001"), 0, 0, 0, 1));
		when(vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias(anyString())).thenReturn(List.of());

		assertEquals(1, service.syncCatalog());

		verify(vulnAnalysisUpdateService, never()).recomputeReleasesForNewlyKev(anyString(), any());
	}

	@Test
	void incrementalAdditionNotifiesEveryMatchingOrgRecord() {
		when(connector.fetchCatalog()).thenReturn(oneEntry());
		when(kevAssertionService.countRecords()).thenReturn(1L);
		when(kevAssertionService.applyCatalog(eq(KevSource.CISA), any())).thenReturn(
				new KevCatalogApplyOutcome(List.of("CVE-2026-0001"), 0, 0, 0, 1));
		UUID orgA = UUID.randomUUID();
		UUID orgB = UUID.randomUUID();
		when(vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias("CVE-2026-0001"))
				.thenReturn(List.of(
						vulnRecord(orgA, "CVE-2026-0001"),
						// orgB tracks it under a GHSA primary id; CVE in aliases
						vulnRecord(orgB, "GHSA-aaaa-bbbb-cccc")));

		assertEquals(1, service.syncCatalog());

		verify(hook).onKevAdded(eq(orgA), eq("CVE-2026-0001"), any(VulnerabilityRecordData.class));
		verify(hook).onKevAdded(eq(orgB), eq("GHSA-aaaa-bbbb-cccc"), any(VulnerabilityRecordData.class));
	}

	@Test
	void noMatchingRecordsMeansNoEvents() {
		when(connector.fetchCatalog()).thenReturn(oneEntry());
		when(kevAssertionService.countRecords()).thenReturn(1L);
		when(kevAssertionService.applyCatalog(eq(KevSource.CISA), any())).thenReturn(
				new KevCatalogApplyOutcome(List.of("CVE-2026-0001"), 0, 0, 0, 1));
		when(vulnerabilityRecordRepository.findAllOrgsByVulnIdOrAlias(anyString())).thenReturn(List.of());

		assertEquals(1, service.syncCatalog());

		verify(hook, never()).onKevAdded(any(), anyString(), any());
	}

	@Test
	void absentHookNoOpsButStillReconciles() throws Exception {
		inject("vulnerabilityRecordChangeHook", null); // CE: no impl bean
		when(connector.fetchCatalog()).thenReturn(oneEntry());
		when(kevAssertionService.countRecords()).thenReturn(1L);
		when(kevAssertionService.applyCatalog(eq(KevSource.CISA), any())).thenReturn(
				new KevCatalogApplyOutcome(List.of("CVE-2026-0001"), 0, 0, 0, 1));

		assertEquals(1, service.syncCatalog());

		verify(kevAssertionService).applyCatalog(eq(KevSource.CISA), any());
		// A null hook must not break the sync: reconcile still happens and
		// the KEV_ADDED notification never fires. (The KEV re-gate fan-out
		// is independent of the hook and still runs — CE-ready — so we no
		// longer assert on findAllOrgsByVulnIdOrAlias, only on the hook.)
		verify(hook, never()).onKevAdded(any(), anyString(), any());
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
}
