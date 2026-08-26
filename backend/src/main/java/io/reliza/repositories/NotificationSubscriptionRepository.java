/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
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

	default Optional<NotificationSubscription> findManagedByTeam(UUID org, UUID team) {
		return findManagedByTeamString(org.toString(), team.toString());
	}

	/**
	 * The subscription a team's "notify me about my components" toggle owns.
	 *
	 * <p>Looked up by MARKER rather than by a uuid stored back on the team. A
	 * stored uuid is a second copy of the same fact and can dangle -- delete the
	 * subscription and the team still claims one -- whereas the marker cannot
	 * disagree with the row it is written on.
	 *
	 * <p>Org-scoped like every other query on this table, and no LIMIT: at most
	 * one row per team is enforced by the partial unique index in
	 * V78__managed_subscription_index.sql, so a second row is a broken invariant
	 * that should surface rather than be silently resolved by whichever row the
	 * planner happened to return first.
	 */
	@Query(
		value = "SELECT * FROM rearm.notification_subscriptions "
			+ "WHERE record_data->>'org' = :org "
			+ "  AND record_data->>'managedByTeam' = :team",
		nativeQuery = true)
	Optional<NotificationSubscription> findManagedByTeamString(@Param("org") String org,
			@Param("team") String team);

	default Optional<NotificationSubscription> findManagedByTeamAnyOrg(UUID team) {
		return findManagedByTeamAnyOrgString(team.toString());
	}

	/**
	 * Marker-only lookup, for assertions and diagnostics.
	 *
	 * <p>The org-scoped form above is what production uses -- this one exists so
	 * a test can say "no row exists for this team ANYWHERE" without knowing which
	 * org to look in, which is a stronger statement than the scoped query can
	 * make.
	 */
	@Query(
		value = "SELECT * FROM rearm.notification_subscriptions "
			+ "WHERE record_data->>'managedByTeam' = :team",
		nativeQuery = true)
	Optional<NotificationSubscription> findManagedByTeamAnyOrgString(@Param("team") String team);
}
