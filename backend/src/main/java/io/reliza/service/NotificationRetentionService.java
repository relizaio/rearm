/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.time.ZonedDateTime;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.reliza.model.OrganizationData;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.repositories.NotificationReadRepository;
import io.reliza.service.OrganizationService;
import lombok.extern.slf4j.Slf4j;

/**
 * Daily retention sweep over the notifications tables (Phase 6c of the
 * notifications plan, S-2). Deletes {@code notification_outbox_events},
 * {@code notification_deliveries} and their {@code notification_reads}
 * marks older than each org's retention window — age-based on
 * {@code created_date}, regardless of status or read state. Driven by
 * {@code SaasSchedulingService} inside the {@code PURGE_NOTIFICATION_ROWS}
 * advisory lock.
 *
 * <p>Retention resolves per org from
 * {@code OrganizationData.Settings.notificationRetentionDays}
 * (90-day default). The configured minimum (14 days) keeps clear of the
 * email digest's maximum parking window (P7D) plus the delivery retry
 * curve, so the sweep can never delete a row that is still scheduled to
 * go out.
 *
 * <p>Each org is swept in its own transaction (via
 * {@link TransactionTemplate} — a self-invoked {@code @Transactional}
 * method would bypass the proxy) so one failing org can't roll back or
 * block the others. Delete order inside an org's transaction: read marks
 * (join through their expiring delivery — the table has no org column),
 * then deliveries, then events. In the seconds-wide window where an event
 * falls before the cutoff but its fan-out deliveries fall after, the
 * surviving deliveries render as empty inbox items (the inbox renders
 * from the event's {@code record_data}); the next sweep removes them.
 */
@Service
@Slf4j
public class NotificationRetentionService {

	@Autowired
	private OrganizationService organizationService;

	@Autowired
	private NotificationOutboxEventRepository outboxRepo;

	@Autowired
	private NotificationDeliveryRepository deliveryRepo;

	@Autowired
	private NotificationReadRepository readRepo;

	@Autowired
	private PlatformTransactionManager transactionManager;

	/**
	 * Run the sweep across every org. Returns the total number of rows
	 * deleted (events + deliveries + read marks) for log visibility.
	 */
	public int purgeExpired() {
		TransactionTemplate txTemplate = new TransactionTemplate(transactionManager);
		int total = 0;
		ZonedDateTime now = ZonedDateTime.now();
		for (OrganizationData od : organizationService.listAllOrganizationData()) {
			int days = od.getSettings() != null
					? od.getSettings().getNotificationRetentionDaysOrDefault()
					: OrganizationData.Settings.NOTIFICATION_RETENTION_DAYS_DEFAULT;
			ZonedDateTime cutoff = now.minusDays(days);
			try {
				Integer orgTotal = txTemplate.execute(status -> {
					int reads = readRepo.deleteByOrgDeliveriesOlderThan(od.getUuid(), cutoff);
					int deliveries = deliveryRepo.deleteByOrgOlderThan(od.getUuid(), cutoff);
					int events = outboxRepo.deleteByOrgOlderThan(od.getUuid(), cutoff);
					if (reads + deliveries + events > 0) {
						log.info("notification retention: org {} ({}d window) — deleted {} event(s), "
								+ "{} delivery(ies), {} read mark(s)",
								od.getUuid(), days, events, deliveries, reads);
					}
					return reads + deliveries + events;
				});
				total += orgTotal != null ? orgTotal : 0;
			} catch (RuntimeException e) {
				log.error("notification retention failed for org {}; continuing with remaining orgs",
						od.getUuid(), e);
			}
		}
		return total;
	}
}
