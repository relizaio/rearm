/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.NotificationSubscription;
import io.reliza.model.NotificationSubscriptionStatus;

public interface NotificationSubscriptionRepository extends CrudRepository<NotificationSubscription, UUID> {

	/**
	 * Pure-JSONB shape — org lives in record_data, so finders use JSONB
	 * path comparison. Default methods take UUID and stringify here so
	 * callers don't sprinkle {@code .toString()} everywhere. The JSONB
	 * {@code ->>} extractor returns text, so the right-hand side has to
	 * be text too.
	 */
	default List<NotificationSubscription> findByOrg(UUID org) {
		return findByOrgString(org.toString());
	}

	default List<NotificationSubscription> findActiveByOrg(UUID org) {
		return findActiveByOrgString(org.toString());
	}

	@Query(
		value = "SELECT * FROM rearm.notification_subscriptions WHERE record_data->>'org' = :org",
		nativeQuery = true)
	List<NotificationSubscription> findByOrgString(@Param("org") String org);

	@Query(
		value = "SELECT * FROM rearm.notification_subscriptions "
			+ "WHERE record_data->>'org' = :org "
			+ "  AND record_data->>'status' = '" + NotificationSubscriptionStatus.ACTIVE_VALUE + "'",
		nativeQuery = true)
	List<NotificationSubscription> findActiveByOrgString(@Param("org") String org);
}
