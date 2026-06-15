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

import io.reliza.model.NotificationDelivery;
import io.reliza.model.NotificationDeliveryOrigin;
import io.reliza.model.NotificationDeliveryStatus;

public interface NotificationDeliveryRepository extends CrudRepository<NotificationDelivery, UUID> {

	/**
	 * Channel worker's hot query. Picks up PENDING deliveries whose
	 * BackoffPolicy backoff timer has elapsed.
	 */
	@Query(
		value = "SELECT * FROM rearm.notification_deliveries "
			+ "WHERE status = '" + NotificationDeliveryStatus.PENDING_VALUE + "' "
			+ "  AND next_attempt_at <= :now "
			+ "ORDER BY next_attempt_at "
			+ "LIMIT :batchSize",
		nativeQuery = true)
	List<NotificationDelivery> findReadyForDelivery(
		@Param("now") ZonedDateTime now,
		@Param("batchSize") int batchSize);

	/**
	 * Dedup check at fan-out time: was the same (subscription, channel,
	 * dedup_key) already delivered (SENT or ACKED) — or parked in an open
	 * email-digest batch (BATCHED) — within the window?
	 *
	 * <p>Delivered rows anchor on {@code sent_at}, NOT on
	 * {@code created_date}: a delivery row that took days to actually go
	 * out should re-anchor its dedup window from the transmit moment.
	 * BATCHED rows have no {@code sent_at} yet, so they anchor on
	 * {@code created_date} — a same-key row already waiting in the open
	 * batch must suppress re-inserts, or every recurrence inside the
	 * digest window would render as a duplicate line in the digest email
	 * (events are distinct outbox rows, so the flush's by-event-uuid
	 * dedup can't catch them).
	 *
	 * <p>Returns a {@link List} of size 0 or 1 (LIMIT 1) — callers should
	 * use the {@link #existsRecentDelivery} default-method wrapper. We
	 * avoid native-boolean from {@code SELECT EXISTS} because Hibernate's
	 * mapping isn't uniformly portable across JDBC driver versions.
	 */
	@Query(
		value = "SELECT 1 FROM rearm.notification_deliveries "
			+ "WHERE subscription_uuid = :subscription "
			+ "  AND channel_uuid = :channel "
			+ "  AND dedup_key = :dedupKey "
			+ "  AND ((status IN ('" + NotificationDeliveryStatus.SENT_VALUE + "', "
			+ "                   '" + NotificationDeliveryStatus.ACKED_VALUE + "') "
			+ "        AND sent_at IS NOT NULL "
			+ "        AND sent_at >= :since) "
			+ "    OR (status = '" + NotificationDeliveryStatus.BATCHED_VALUE + "' "
			+ "        AND created_date >= :since)) "
			+ "LIMIT 1",
		nativeQuery = true)
	List<Integer> findOneRecentDelivery(
		@Param("subscription") UUID subscription,
		@Param("channel") UUID channel,
		@Param("dedupKey") String dedupKey,
		@Param("since") ZonedDateTime since);

	default boolean existsRecentDelivery(UUID subscription, UUID channel, String dedupKey, ZonedDateTime since) {
		return !findOneRecentDelivery(subscription, channel, dedupKey, since).isEmpty();
	}

	List<NotificationDelivery> findByOutboxEventUuid(UUID outboxEventUuid);

	/**
	 * Per-user targeted rows for one approval request, located by the
	 * propagated event dedup key ({@code approval:requested:<release>:<request>}).
	 * Used by the APPROVAL_RESOLVED fan-out to mark the request's
	 * targeted inbox rows read for every snapshotted recipient once the
	 * request resolves.
	 *
	 * <p>Safe when the REQUESTED and RESOLVED events land in the same
	 * fan-out batch transaction: this is a native query with no declared
	 * query spaces, so Hibernate's AUTO flush mode conservatively flushes
	 * the persistence context before executing it — targeted rows saved
	 * earlier in the same transaction are visible.
	 */
	@Query(
		value = "SELECT * FROM rearm.notification_deliveries "
			+ "WHERE org = :org "
			+ "  AND dedup_key = :dedupKey "
			+ "  AND target_user IS NOT NULL",
		nativeQuery = true)
	List<NotificationDelivery> findTargetedByDedupKey(
		@Param("org") UUID org,
		@Param("dedupKey") String dedupKey);

	List<NotificationDelivery> findByOrg(UUID org);

	/**
	 * Read-side paginated listing for the Phase 2e history surface. Each
	 * filter is null-tolerant — pass null for any to omit it. Order is
	 * created_date DESC (newest-first), matching the operator-facing
	 * "what just happened" UX. Offset+limit pagination matches the rest
	 * of the rearm read-side; cursor-based isn't worth the implementation
	 * complexity at v1 row counts (events are sparse — single-digit per
	 * org per day in typical traffic).
	 *
	 * <p>The {@code CAST(:x AS uuid) IS NULL} idiom is required for native
	 * queries against JPA-bound {@code UUID} params: Hibernate binds the
	 * param with the postgres UUID type tag, and PostgreSQL needs an
	 * explicit cast in the IS NULL check or it complains about a
	 * "bytea = uuid" comparison.
	 */
	@Query(
		value = "SELECT * FROM rearm.notification_deliveries "
			+ "WHERE org = :org "
			+ "  AND (CAST(:eventUuid AS uuid) IS NULL OR outbox_event_uuid = CAST(:eventUuid AS uuid)) "
			+ "  AND (CAST(:channelUuid AS uuid) IS NULL OR channel_uuid = CAST(:channelUuid AS uuid)) "
			+ "  AND (:status IS NULL OR status = :status) "
			+ "  AND (:origin IS NULL OR origin = :origin) "
			+ "ORDER BY created_date DESC "
			+ "LIMIT :limit OFFSET :offset",
		nativeQuery = true)
	List<NotificationDelivery> findFilteredPage(
		@Param("org") UUID org,
		@Param("eventUuid") UUID eventUuid,
		@Param("channelUuid") UUID channelUuid,
		@Param("status") String status,
		@Param("origin") String origin,
		@Param("limit") int limit,
		@Param("offset") int offset);

	@Query(
		value = "SELECT COUNT(*) FROM rearm.notification_deliveries "
			+ "WHERE org = :org "
			+ "  AND (CAST(:eventUuid AS uuid) IS NULL OR outbox_event_uuid = CAST(:eventUuid AS uuid)) "
			+ "  AND (CAST(:channelUuid AS uuid) IS NULL OR channel_uuid = CAST(:channelUuid AS uuid)) "
			+ "  AND (:status IS NULL OR status = :status) "
			+ "  AND (:origin IS NULL OR origin = :origin)",
		nativeQuery = true)
	long countFiltered(
		@Param("org") UUID org,
		@Param("eventUuid") UUID eventUuid,
		@Param("channelUuid") UUID channelUuid,
		@Param("status") String status,
		@Param("origin") String origin);

	// ---------------------------------------------------------------------
	// Phase 14 — Inbox MVP visibility-filtered listing
	// (notifications-framework.md §8.1).
	//
	// Distinct from findFilteredPage / countFiltered above:
	//   - findFilteredPage is the audit-log query, org-admin scope, returns
	//     every delivery in the org.
	//   - findInboxPage is the personal view, role-driven scope:
	//       * org-admin tier sees all org deliveries
	//       * a non-admin user sees only deliveries whose outbox event's
	//         payload.affectedReleases[*].perspectives intersects with the
	//         user's perspective membership
	//       * a non-admin user with no perspectives sees nothing
	// Kept separate so neither query's index plan or auth contract drifts
	// to accommodate the other.
	//
	// The visibility JOIN walks the JSONB payload on
	// notification_outbox_events.record_data via jsonb_array_elements +
	// jsonb_array_elements_text. The :userPerspectives bind is a single
	// pgsql uuid[] text literal — postgres converts via the
	// CAST(:x AS uuid[]) idiom. Empty array → ANY([]) is always false →
	// the OR collapses to (:isOrgAdmin) and no-perspective non-admins
	// see zero rows, which is the design's intended "no membership = no
	// inbox" semantic.
	//
	// The LEFT JOIN onto notification_reads is what gives us the per-user
	// read-state column AND the :unreadOnly predicate in one pass —
	// pre-filtering to unread is the common inbox use case so the index
	// on notification_reads_user_idx pays off the most when it matters.
	//
	// Per-user targeted rows (target_user IS NOT NULL, written by the
	// approval-request fan-out) are personal copies: visible ONLY to the
	// targeted user, never via the org-admin or perspective arms. One
	// request snapshots N recipients into N rows — letting the admin arm
	// match them would show an org admin N identical inbox items; admins
	// still see the event once via its subscription-routed delivery.
	// ---------------------------------------------------------------------

	@Query(
		value = "SELECT d.* FROM rearm.notification_deliveries d "
			+ "JOIN rearm.notification_outbox_events e ON e.uuid = d.outbox_event_uuid "
			+ "LEFT JOIN rearm.notification_reads r "
			+ "       ON r.delivery_uuid = d.uuid AND r.user_uuid = CAST(:userUuid AS uuid) "
			+ "WHERE d.org = :org "
			+ "  AND ( ( d.target_user IS NOT NULL AND d.target_user = CAST(:userUuid AS uuid) ) "
			+ "        OR ( d.target_user IS NULL "
			+ "             AND ( :isOrgAdmin = TRUE "
			+ "                   OR EXISTS ( "
			+ "                        SELECT 1 "
			+ "                        FROM jsonb_array_elements(e.record_data->'affectedReleases') AS rel, "
			+ "                             jsonb_array_elements_text(rel->'perspectives') AS pp "
			+ "                        WHERE pp::uuid = ANY(CAST(:userPerspectives AS uuid[])) "
			+ "                   )))) "
			+ "  AND ( :unreadOnly = FALSE OR r.uuid IS NULL ) "
			+ "  AND (:status IS NULL OR d.status = :status) "
			+ "  AND (:eventType IS NULL OR e.event_type = :eventType) "
			+ "ORDER BY d.created_date DESC "
			+ "LIMIT :limit OFFSET :offset",
		nativeQuery = true)
	List<NotificationDelivery> findInboxPage(
		@Param("org") UUID org,
		@Param("userUuid") UUID userUuid,
		@Param("isOrgAdmin") boolean isOrgAdmin,
		@Param("userPerspectives") String userPerspectives,
		@Param("unreadOnly") boolean unreadOnly,
		@Param("status") String status,
		@Param("eventType") String eventType,
		@Param("limit") int limit,
		@Param("offset") int offset);

	@Query(
		value = "SELECT COUNT(*) FROM rearm.notification_deliveries d "
			+ "JOIN rearm.notification_outbox_events e ON e.uuid = d.outbox_event_uuid "
			+ "LEFT JOIN rearm.notification_reads r "
			+ "       ON r.delivery_uuid = d.uuid AND r.user_uuid = CAST(:userUuid AS uuid) "
			+ "WHERE d.org = :org "
			+ "  AND ( ( d.target_user IS NOT NULL AND d.target_user = CAST(:userUuid AS uuid) ) "
			+ "        OR ( d.target_user IS NULL "
			+ "             AND ( :isOrgAdmin = TRUE "
			+ "                   OR EXISTS ( "
			+ "                        SELECT 1 "
			+ "                        FROM jsonb_array_elements(e.record_data->'affectedReleases') AS rel, "
			+ "                             jsonb_array_elements_text(rel->'perspectives') AS pp "
			+ "                        WHERE pp::uuid = ANY(CAST(:userPerspectives AS uuid[])) "
			+ "                   )))) "
			+ "  AND ( :unreadOnly = FALSE OR r.uuid IS NULL ) "
			+ "  AND (:status IS NULL OR d.status = :status) "
			+ "  AND (:eventType IS NULL OR e.event_type = :eventType)",
		nativeQuery = true)
	long countInbox(
		@Param("org") UUID org,
		@Param("userUuid") UUID userUuid,
		@Param("isOrgAdmin") boolean isOrgAdmin,
		@Param("userPerspectives") String userPerspectives,
		@Param("unreadOnly") boolean unreadOnly,
		@Param("status") String status,
		@Param("eventType") String eventType);

	/**
	 * Single-uuid visibility check. Returns true when the delivery exists
	 * AND the user can see it under the same rule {@link #findInboxPage}
	 * uses. Drives the mark-read auth path so users can't toggle the
	 * read-state of deliveries they're not permitted to see.
	 */
	@Query(
		value = "SELECT COUNT(*) FROM rearm.notification_deliveries d "
			+ "JOIN rearm.notification_outbox_events e ON e.uuid = d.outbox_event_uuid "
			+ "WHERE d.uuid = CAST(:deliveryUuid AS uuid) "
			+ "  AND d.org = :org "
			+ "  AND ( ( d.target_user IS NOT NULL AND d.target_user = CAST(:userUuid AS uuid) ) "
			+ "        OR ( d.target_user IS NULL "
			+ "             AND ( :isOrgAdmin = TRUE "
			+ "                   OR EXISTS ( "
			+ "                        SELECT 1 "
			+ "                        FROM jsonb_array_elements(e.record_data->'affectedReleases') AS rel, "
			+ "                             jsonb_array_elements_text(rel->'perspectives') AS pp "
			+ "                        WHERE pp::uuid = ANY(CAST(:userPerspectives AS uuid[])) "
			+ "                   ))))",
		nativeQuery = true)
	long countDeliveryVisibleToUser(
		@Param("org") UUID org,
		@Param("deliveryUuid") UUID deliveryUuid,
		@Param("userUuid") UUID userUuid,
		@Param("isOrgAdmin") boolean isOrgAdmin,
		@Param("userPerspectives") String userPerspectives);

	/**
	 * Boolean wrapper around {@link #countDeliveryVisibleToUser} matching
	 * the existing {@code existsRecentDelivery} idiom (line 64) — keeps
	 * the auth check at the fetcher layer readable as "is this visible?"
	 * rather than "is the count zero?".
	 */
	default boolean existsDeliveryVisibleToUser(UUID org, UUID deliveryUuid,
			UUID userUuid, boolean isOrgAdmin, String userPerspectives) {
		return countDeliveryVisibleToUser(org, deliveryUuid, userUuid, isOrgAdmin, userPerspectives) > 0;
	}

	// ---------------------------------------------------------------------
	// Phase 5 — email digest (rolling-cap batching). BATCHED rows reuse
	// this table as the digest queue: invisible to findReadyForDelivery
	// (which only picks PENDING), visible in the inbox immediately
	// (findInboxPage has no default status filter), flushed per-channel
	// by EmailDigestFlushService when next_attempt_at (= the digest
	// window deadline) elapses.
	// ---------------------------------------------------------------------

	/**
	 * The channel's open digest batch, if any: its most recent BATCHED
	 * row. All rows of an open batch share one deadline (joiners adopt
	 * it), so LIMIT 1 is enough for the fan-out's "join the open batch"
	 * decision. DESC on next_attempt_at so a config change mid-window
	 * (rows with differing deadlines) converges on the latest deadline.
	 */
	@Query(
		value = "SELECT * FROM rearm.notification_deliveries "
			+ "WHERE channel_uuid = :channel "
			+ "  AND status = '" + NotificationDeliveryStatus.BATCHED_VALUE + "' "
			+ "ORDER BY next_attempt_at DESC "
			+ "LIMIT 1",
		nativeQuery = true)
	List<NotificationDelivery> findOpenBatchHead(@Param("channel") UUID channel);

	/**
	 * The channel's last "counted" email send for the rolling cap:
	 * most recent SENT/ACKED delivery of a REAL, non-actionable event.
	 * Actionable events (approval requested / resolved) bypass the
	 * digest and must not consume the channel's send budget, hence the
	 * event-type exclusion via the outbox JOIN. SYNTHETIC origins
	 * (channel tests, Quick Start verifies) don't count either —
	 * consistent with the dedup bypass for synthetic traffic.
	 *
	 * <p>{@code :actionableTypes} must be non-empty — {@code NOT IN ()}
	 * is invalid SQL. The caller derives it from
	 * {@code NotificationEventType.isActionable()}; if actionable types
	 * ever go away, drop this exclusion rather than passing an empty
	 * list.
	 */
	@Query(
		value = "SELECT d.* FROM rearm.notification_deliveries d "
			+ "JOIN rearm.notification_outbox_events e ON e.uuid = d.outbox_event_uuid "
			+ "WHERE d.channel_uuid = :channel "
			+ "  AND d.status IN ('" + NotificationDeliveryStatus.SENT_VALUE + "', "
			+ "                   '" + NotificationDeliveryStatus.ACKED_VALUE + "') "
			+ "  AND d.sent_at IS NOT NULL "
			+ "  AND d.origin = '" + NotificationDeliveryOrigin.REAL_VALUE + "' "
			+ "  AND e.event_type NOT IN (:actionableTypes) "
			+ "ORDER BY d.sent_at DESC "
			+ "LIMIT 1",
		nativeQuery = true)
	List<NotificationDelivery> findLastCountedEmailSend(
		@Param("channel") UUID channel,
		@Param("actionableTypes") List<String> actionableTypes);

	/**
	 * Channels with a due digest batch — drives the flush job's outer
	 * loop, longest-overdue channel first (mirrors
	 * {@code findReadyForDelivery}'s next_attempt_at ordering so a
	 * sustained backlog can't starve any one channel). LIMIT caps
	 * per-tick work; leftover channels are picked up on the next tick
	 * (fixedDelay scheduling, no overlap).
	 */
	@Query(
		value = "SELECT channel_uuid FROM rearm.notification_deliveries "
			+ "WHERE status = '" + NotificationDeliveryStatus.BATCHED_VALUE + "' "
			+ "  AND next_attempt_at <= :now "
			+ "GROUP BY channel_uuid "
			+ "ORDER BY MIN(next_attempt_at) "
			+ "LIMIT :batchSize",
		nativeQuery = true)
	List<UUID> findChannelsWithDueBatches(
		@Param("now") ZonedDateTime now,
		@Param("batchSize") int batchSize);

	/**
	 * Release a channel's parked digest rows to the immediate lane:
	 * flips every BATCHED row to PENDING with {@code next_attempt_at =
	 * :now} so the delivery worker drains them as individual emails.
	 * Invoked when a channel's digestMode is (re)set to IMMEDIATE —
	 * rows parked under the old ROLLING policy must not stay invisible
	 * until a deadline written before the operator turned batching off
	 * (up to {@code EmailDigestPolicy.MAX_INTERVAL} away).
	 */
	@Modifying
	@Query(
		value = "UPDATE rearm.notification_deliveries "
			+ "SET status = '" + NotificationDeliveryStatus.PENDING_VALUE + "', "
			+ "    next_attempt_at = :now "
			+ "WHERE channel_uuid = :channel "
			+ "  AND status = '" + NotificationDeliveryStatus.BATCHED_VALUE + "'",
		nativeQuery = true)
	int releaseBatchedForChannel(
		@Param("channel") UUID channel,
		@Param("now") ZonedDateTime now);

	/**
	 * The due BATCHED rows of one channel's digest batch, oldest first
	 * (digest email renders events in arrival order).
	 */
	@Query(
		value = "SELECT * FROM rearm.notification_deliveries "
			+ "WHERE channel_uuid = :channel "
			+ "  AND status = '" + NotificationDeliveryStatus.BATCHED_VALUE + "' "
			+ "  AND next_attempt_at <= :now "
			+ "ORDER BY created_date",
		nativeQuery = true)
	List<NotificationDelivery> findDueBatchedByChannel(
		@Param("channel") UUID channel,
		@Param("now") ZonedDateTime now);

	/**
	 * Retention sweep (Phase 6c): age-based delete regardless of status or
	 * read state. The minimum retention bound (14 days, see
	 * {@code OrganizationData.Settings}) keeps clear of the digest's
	 * maximum parking window and the retry curve, so no row this old is
	 * still scheduled to go out. Uses the (org, created_date) index from
	 * V50; caller deletes the matching {@code notification_reads} rows
	 * first in the same transaction.
	 */
	@Modifying
	@Transactional
	@Query(
		value = "DELETE FROM rearm.notification_deliveries "
			+ "WHERE org = :org AND created_date < :cutoff",
		nativeQuery = true)
	int deleteByOrgOlderThan(@Param("org") UUID org, @Param("cutoff") ZonedDateTime cutoff);
}
