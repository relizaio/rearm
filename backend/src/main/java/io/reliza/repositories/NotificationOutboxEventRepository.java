/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;

public interface NotificationOutboxEventRepository extends CrudRepository<NotificationOutboxEvent, UUID> {

	/**
	 * Outbox worker's hot query. Pulls the next batch of PENDING events
	 * ordered by occurrence time. The worker calls this inside the
	 * advisory-lock window (see notifications-framework.md §5.2).
	 */
	@Query(
		value = "SELECT * FROM rearm.notification_outbox_events "
			+ "WHERE status = '" + NotificationOutboxStatus.PENDING_VALUE + "' "
			+ "ORDER BY occurred_at "
			+ "LIMIT :batchSize",
		nativeQuery = true)
	List<NotificationOutboxEvent> findPendingBatch(@Param("batchSize") int batchSize);

	@Query(
		value = "SELECT * FROM rearm.notification_outbox_events "
			+ "WHERE org = :org "
			+ "ORDER BY occurred_at DESC "
			+ "LIMIT :limit",
		nativeQuery = true)
	List<NotificationOutboxEvent> findRecentByOrg(@Param("org") UUID org, @Param("limit") int limit);

	/**
	 * Retention sweep (Phase 6c): age-based delete regardless of status —
	 * a PENDING event older than the org's retention window is stuck, not
	 * in flight (the fan-out drains every few seconds). Uses the
	 * (org, created_date) index from V50; caller wraps the per-org
	 * reads → deliveries → events deletes in one transaction.
	 */
	@Modifying
	@Transactional
	@Query(
		value = "DELETE FROM rearm.notification_outbox_events "
			+ "WHERE org = :org AND created_date < :cutoff",
		nativeQuery = true)
	int deleteByOrgOlderThan(@Param("org") UUID org, @Param("cutoff") ZonedDateTime cutoff);
}
