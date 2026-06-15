/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.NotificationRead;

public interface NotificationReadRepository extends CrudRepository<NotificationRead, UUID> {

	Optional<NotificationRead> findByUserUuidAndDeliveryUuid(UUID userUuid, UUID deliveryUuid);

	List<NotificationRead> findByUserUuid(UUID userUuid);

	/**
	 * Bulk projector: every read row a user has for any of the supplied
	 * deliveries. Single round-trip vs N indexed lookups; used by the
	 * inbox-page render to set {@code readAt} on each item without an
	 * N+1 against {@code notification_reads}.
	 */
	List<NotificationRead> findByUserUuidAndDeliveryUuidIn(UUID userUuid, java.util.Collection<UUID> deliveryUuids);

	void deleteByDeliveryUuid(UUID deliveryUuid);

	/**
	 * Retention sweep (Phase 6c): read marks for the org's expired
	 * deliveries. {@code notification_reads} has no org column, so the
	 * delete joins through the delivery being purged. Must run BEFORE
	 * {@code NotificationDeliveryRepository.deleteByOrgOlderThan} in the
	 * same transaction or the join finds nothing and the marks orphan.
	 */
	@Modifying
	@Transactional
	@Query(
		value = "DELETE FROM rearm.notification_reads r "
			+ "USING rearm.notification_deliveries d "
			+ "WHERE r.delivery_uuid = d.uuid "
			+ "  AND d.org = :org AND d.created_date < :cutoff",
		nativeQuery = true)
	int deleteByOrgDeliveriesOlderThan(@Param("org") UUID org, @Param("cutoff") ZonedDateTime cutoff);
}
