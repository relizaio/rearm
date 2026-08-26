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
	 * Outbox worker's hot query. Pulls the next batch of PENDING events that
	 * are due, ordered by occurrence time. The worker calls this inside the
	 * advisory-lock window (see notifications-framework.md §5.2).
	 *
	 * <p>{@code next_attempt_at} filters but deliberately does NOT order
	 * (V76). An event fan-out chose to defer -- today only a vuln event whose
	 * affected-release set has not materialised yet -- becomes invisible here
	 * until its delay elapses, then rejoins the queue in its original
	 * {@code occurred_at} position. Ordering stays on {@code occurred_at}
	 * because {@code NotificationFanOutService.markResolvedRequestsRead}
	 * depends on that FIFO to guarantee an APPROVAL_REQUESTED event fans out
	 * before the APPROVAL_RESOLVED that marks its rows read, even within one
	 * batch. Ordering by {@code next_attempt_at} instead -- as the
	 * {@code notification_deliveries} counterpart does -- would put a deferred
	 * event behind events that occurred after it.
	 *
	 * <p>Due-ness is evaluated against the DATABASE clock ({@code now()}), not a
	 * timestamp passed in by the caller. ReARM runs multiple backend replicas --
	 * that is why the drain takes an advisory lock at all -- and
	 * {@code next_attempt_at} is written by whichever replica produced or
	 * deferred the row. Comparing one replica's clock against another's would
	 * make delivery latency a function of fleet clock skew: a producer running
	 * N seconds fast would stamp every new event N seconds into the future and
	 * hide it from the drain for that long, silently, for every event type.
	 * One clock owns the comparison.
	 */
	@Query(
		value = "SELECT * FROM rearm.notification_outbox_events "
			+ "WHERE status = '" + NotificationOutboxStatus.PENDING_VALUE + "' "
			+ "  AND next_attempt_at <= now() "
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
	 * Re-queue an event whose affected releases are not resolvable yet: bump the
	 * enrichment attempt count and push {@code next_attempt_at} forward.
	 *
	 * <p>A targeted UPDATE rather than an entity save, for three reasons. It
	 * stamps the delay against the DATABASE clock, so the deferral window is a
	 * real 30/60/120 seconds rather than one that shrinks or grows with the
	 * deferring replica's clock skew -- and since a spent budget ends in a
	 * permanent suppression, a collapsed window means a dropped notification.
	 * {@code findPendingBatch} reads due-ness from that same clock, so both
	 * halves of the comparison now agree. It also leaves {@code record_data}
	 * untouched, so the empty affected-release list enrichment just wrote into
	 * the in-memory payload is never persisted onto a row that is still
	 * PENDING. And it does not touch {@code @Version}, so re-queueing cannot
	 * lose a merge race against a drainer holding a stale copy.
	 */
	@Modifying
	@Transactional
	@Query(
		value = "UPDATE rearm.notification_outbox_events "
			+ "SET enrichment_attempt_count = :attemptCount, "
			+ "    next_attempt_at = now() + make_interval(secs => :delaySeconds), "
			+ "    last_updated_date = now() "
			+ "WHERE uuid = :uuid",
		nativeQuery = true)
	int deferForEnrichment(@Param("uuid") UUID uuid,
			@Param("attemptCount") int attemptCount,
			@Param("delaySeconds") int delaySeconds);

	/**
	 * Count events per status for an org since a cutoff. Backs the measurement
	 * the SUPPRESSED status exists for -- "how often are we withholding
	 * vulnerability notifications, and is that number big enough to justify
	 * moving the emit to the artifact-metrics write?" Without a read path that
	 * justification would be an unfalsifiable claim.
	 */
	@Query(
		value = "SELECT status, count(*) FROM rearm.notification_outbox_events "
			+ "WHERE org = :org AND created_date >= :since "
			+ "GROUP BY status",
		nativeQuery = true)
	List<Object[]> countByStatusSince(@Param("org") UUID org, @Param("since") ZonedDateTime since);

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
