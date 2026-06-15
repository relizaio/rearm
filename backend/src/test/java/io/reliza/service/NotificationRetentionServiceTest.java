/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;

import io.reliza.model.OrganizationData;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.repositories.NotificationReadRepository;
import io.reliza.service.OrganizationService;

/**
 * Phase 6c — unit tests for {@link NotificationRetentionService}. Mock-based:
 * cover the per-org retention-window resolution (default, custom, null
 * settings), the reads → deliveries → events delete ordering (read marks
 * join through deliveries, so they must go first), and the summed total.
 * The native delete SQL itself is exercised against a live DB in the
 * integration suite.
 */
class NotificationRetentionServiceTest {

	private OrganizationService organizationService;
	private NotificationOutboxEventRepository outboxRepo;
	private NotificationDeliveryRepository deliveryRepo;
	private NotificationReadRepository readRepo;
	private NotificationRetentionService service;

	@BeforeEach
	void wireMocks() throws Exception {
		organizationService = mock(OrganizationService.class);
		outboxRepo = mock(NotificationOutboxEventRepository.class);
		deliveryRepo = mock(NotificationDeliveryRepository.class);
		readRepo = mock(NotificationReadRepository.class);

		service = new NotificationRetentionService();
		inject("organizationService", organizationService);
		inject("outboxRepo", outboxRepo);
		inject("deliveryRepo", deliveryRepo);
		inject("readRepo", readRepo);
		// Mocked manager makes TransactionTemplate.execute run the callback
		// directly — per-org tx boundaries are a no-op in unit tests.
		inject("transactionManager", mock(PlatformTransactionManager.class));
	}

	private void inject(String field, Object value) throws Exception {
		Field f = NotificationRetentionService.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(service, value);
	}

	private OrganizationData org(UUID uuid, OrganizationData.Settings settings) {
		OrganizationData od = mock(OrganizationData.class);
		when(od.getUuid()).thenReturn(uuid);
		when(od.getSettings()).thenReturn(settings);
		return od;
	}

	@Test
	void orgWithoutSettingsUsesNinetyDayDefault() {
		UUID orgUuid = UUID.randomUUID();
		OrganizationData od = org(orgUuid, null);
		when(organizationService.listAllOrganizationData()).thenReturn(List.of(od));

		ZonedDateTime before = ZonedDateTime.now()
				.minusDays(OrganizationData.Settings.NOTIFICATION_RETENTION_DAYS_DEFAULT);
		service.purgeExpired();
		ZonedDateTime after = ZonedDateTime.now()
				.minusDays(OrganizationData.Settings.NOTIFICATION_RETENTION_DAYS_DEFAULT);

		ArgumentCaptor<ZonedDateTime> cutoff = ArgumentCaptor.forClass(ZonedDateTime.class);
		verify(outboxRepo).deleteByOrgOlderThan(eq(orgUuid), cutoff.capture());
		ZonedDateTime captured = cutoff.getValue();
		assertFalse(captured.isBefore(before));
		assertFalse(captured.isAfter(after));
	}

	@Test
	void settingsWithoutRetentionFieldAlsoUsesDefault() {
		UUID orgUuid = UUID.randomUUID();
		OrganizationData od = org(orgUuid, new OrganizationData.Settings());
		when(organizationService.listAllOrganizationData()).thenReturn(List.of(od));

		ZonedDateTime before = ZonedDateTime.now()
				.minusDays(OrganizationData.Settings.NOTIFICATION_RETENTION_DAYS_DEFAULT);
		service.purgeExpired();
		ZonedDateTime after = ZonedDateTime.now()
				.minusDays(OrganizationData.Settings.NOTIFICATION_RETENTION_DAYS_DEFAULT);

		ArgumentCaptor<ZonedDateTime> cutoff = ArgumentCaptor.forClass(ZonedDateTime.class);
		verify(deliveryRepo).deleteByOrgOlderThan(eq(orgUuid), cutoff.capture());
		assertFalse(cutoff.getValue().isBefore(before));
		assertFalse(cutoff.getValue().isAfter(after));
	}

	@Test
	void customRetentionSettingShiftsCutoff() {
		UUID orgUuid = UUID.randomUUID();
		OrganizationData.Settings settings = new OrganizationData.Settings();
		settings.setNotificationRetentionDays(30);
		OrganizationData od = org(orgUuid, settings);
		when(organizationService.listAllOrganizationData()).thenReturn(List.of(od));

		ZonedDateTime before = ZonedDateTime.now().minusDays(30);
		service.purgeExpired();
		ZonedDateTime after = ZonedDateTime.now().minusDays(30);

		ArgumentCaptor<ZonedDateTime> cutoff = ArgumentCaptor.forClass(ZonedDateTime.class);
		verify(outboxRepo).deleteByOrgOlderThan(eq(orgUuid), cutoff.capture());
		ZonedDateTime captured = cutoff.getValue();
		assertFalse(captured.isBefore(before));
		assertFalse(captured.isAfter(after));
	}

	@Test
	void deletesReadsThenDeliveriesThenEvents() {
		UUID orgUuid = UUID.randomUUID();
		OrganizationData od = org(orgUuid, null);
		when(organizationService.listAllOrganizationData()).thenReturn(List.of(od));

		service.purgeExpired();

		InOrder order = inOrder(readRepo, deliveryRepo, outboxRepo);
		order.verify(readRepo).deleteByOrgDeliveriesOlderThan(eq(orgUuid), any());
		order.verify(deliveryRepo).deleteByOrgOlderThan(eq(orgUuid), any());
		order.verify(outboxRepo).deleteByOrgOlderThan(eq(orgUuid), any());
	}

	@Test
	void totalsSummedAcrossOrgs() {
		UUID orgA = UUID.randomUUID();
		UUID orgB = UUID.randomUUID();
		OrganizationData odA = org(orgA, null);
		OrganizationData odB = org(orgB, null);
		when(organizationService.listAllOrganizationData()).thenReturn(List.of(odA, odB));
		when(readRepo.deleteByOrgDeliveriesOlderThan(eq(orgA), any())).thenReturn(1);
		when(deliveryRepo.deleteByOrgOlderThan(eq(orgA), any())).thenReturn(2);
		when(outboxRepo.deleteByOrgOlderThan(eq(orgA), any())).thenReturn(3);
		when(readRepo.deleteByOrgDeliveriesOlderThan(eq(orgB), any())).thenReturn(0);
		when(deliveryRepo.deleteByOrgOlderThan(eq(orgB), any())).thenReturn(0);
		when(outboxRepo.deleteByOrgOlderThan(eq(orgB), any())).thenReturn(5);

		assertEquals(11, service.purgeExpired());
	}

	@Test
	void failingOrgDoesNotBlockRemainingOrgs() {
		UUID orgA = UUID.randomUUID();
		UUID orgB = UUID.randomUUID();
		OrganizationData odA = org(orgA, null);
		OrganizationData odB = org(orgB, null);
		when(organizationService.listAllOrganizationData()).thenReturn(List.of(odA, odB));
		when(readRepo.deleteByOrgDeliveriesOlderThan(eq(orgA), any()))
				.thenThrow(new RuntimeException("db blip"));
		when(readRepo.deleteByOrgDeliveriesOlderThan(eq(orgB), any())).thenReturn(2);
		when(deliveryRepo.deleteByOrgOlderThan(eq(orgB), any())).thenReturn(3);
		when(outboxRepo.deleteByOrgOlderThan(eq(orgB), any())).thenReturn(4);

		assertEquals(9, service.purgeExpired());
		verify(outboxRepo).deleteByOrgOlderThan(eq(orgB), any());
	}

	@Test
	void noOrgsMeansZeroDeleted() {
		when(organizationService.listAllOrganizationData()).thenReturn(List.of());
		assertEquals(0, service.purgeExpired());
	}
}
