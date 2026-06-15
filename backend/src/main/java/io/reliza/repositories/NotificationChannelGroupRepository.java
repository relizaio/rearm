/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.NotificationChannelGroup;

public interface NotificationChannelGroupRepository extends CrudRepository<NotificationChannelGroup, UUID> {

	/**
	 * Pure-JSONB finders, same pattern as {@code NotificationChannelRepository}.
	 * JSONB {@code ->>} returns text, so the RHS is stringified.
	 */
	default List<NotificationChannelGroup> findByOrg(UUID org) {
		return findByOrgString(org.toString());
	}

	@Query(
			value = "SELECT * FROM rearm.notification_channel_groups "
					+ "WHERE record_data->>'org' = :org "
					+ "ORDER BY created_date DESC",
			nativeQuery = true)
	List<NotificationChannelGroup> findByOrgString(@Param("org") String org);
}
