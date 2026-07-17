/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.MetricsAudit;

public interface MetricsAuditRepository extends CrudRepository<MetricsAudit, UUID> {
	@Query(value = "SELECT COALESCE(MAX(metrics_revision), -1) FROM rearm.metrics_audit WHERE entity_type = :entityType AND entity_uuid = :entityUuid", nativeQuery = true)
	int findMaxRevision(@Param("entityType") String entityType, @Param("entityUuid") UUID entityUuid);

	/**
	 * Returns every audit row for the given entities whose {@code revision_created_date} falls within
	 * [from, to], ordered by (entity_uuid, metrics_revision) so the caller can walk each entity's
	 * snapshot timeline in chronological order.
	 *
	 * <p>Filtered by {@code entity_uuid} (not {@code org}) on purpose: pre-V20 rows have a NULL
	 * {@code org} and would be dropped by an org filter. The release UUIDs the caller passes are
	 * already perspective/authorization scoped, so the {@code entity_uuid IN (...)} predicate carries
	 * the same access boundary while still returning the legacy NULL-org rows. Uses the existing
	 * {@code metrics_audit_entity_uuid_idx} index.
	 */
	@Query(value = "SELECT * FROM rearm.metrics_audit "
			+ "WHERE entity_type = :entityType "
			+ "AND entity_uuid IN (:entityUuids) "
			+ "AND revision_created_date >= :from AND revision_created_date <= :to "
			+ "ORDER BY entity_uuid, metrics_revision", nativeQuery = true)
	List<MetricsAudit> findRevisionsInRange(
			@Param("entityType") String entityType,
			@Param("entityUuids") Collection<UUID> entityUuids,
			@Param("from") ZonedDateTime from,
			@Param("to") ZonedDateTime to);

	/**
	 * For each entity, returns the single latest audit row strictly before {@code from} (the snapshot
	 * in effect at the start of the window). Used to seed the snapshot timeline so the first in-window
	 * transition is diffed against the pre-window state rather than against an empty baseline.
	 *
	 * <p>DISTINCT ON (entity_uuid) with the (entity_uuid, metrics_revision DESC) ordering picks the
	 * highest-revision row before the cutoff per entity. Same {@code entity_uuid}-scoped /
	 * NULL-org-tolerant rationale as {@link #findRevisionsInRange}.
	 */
	@Query(value = "SELECT DISTINCT ON (entity_uuid) * FROM rearm.metrics_audit "
			+ "WHERE entity_type = :entityType "
			+ "AND entity_uuid IN (:entityUuids) "
			+ "AND revision_created_date < :from "
			+ "ORDER BY entity_uuid, metrics_revision DESC", nativeQuery = true)
	List<MetricsAudit> findLatestRevisionBeforeDate(
			@Param("entityType") String entityType,
			@Param("entityUuids") Collection<UUID> entityUuids,
			@Param("from") ZonedDateTime from);

	// (The per-entity + batched point-reconstruction helpers that read metrics_audit at a changelog
	// endpoint were removed when the posture-diff read path moved to reverse-replaying
	// finding_change_events onto current metrics; metrics_audit is no longer read on the serving path.)

	// ==================================================================================
	// Backfill support (board task #38, phase 2). The backfill seeds finding_change_events
	// from the existing audit history per release over the FULL range, so these queries are
	// driven by (org, entity_uuid) rather than a changelog window.
	// ==================================================================================

	/**
	 * Distinct organizations that have at least one {@code RELEASE} audit row. Pre-V20 rows carry a
	 * NULL {@code org}; those are surfaced as a NULL element so the backfill driver can still process
	 * their releases (entity_uuid is always populated). The backfill is RELEASE-only -- ARTIFACT audit
	 * rows are never enumerated here.
	 */
	@Query(value = "SELECT DISTINCT org FROM rearm.metrics_audit WHERE entity_type = :entityType", nativeQuery = true)
	List<UUID> findDistinctOrgsWithAudits(@Param("entityType") String entityType);

	/**
	 * Distinct release {@code entity_uuid}s for an org with at least one {@code RELEASE} audit row,
	 * ordered for stable, restartable iteration. Each returned UUID is one backfill unit of work.
	 */
	@Query(value = "SELECT DISTINCT entity_uuid FROM rearm.metrics_audit "
			+ "WHERE entity_type = :entityType AND org = :org "
			+ "ORDER BY entity_uuid", nativeQuery = true)
	List<UUID> findDistinctReleaseUuidsByOrg(@Param("entityType") String entityType, @Param("org") UUID org);

	/**
	 * Distinct release {@code entity_uuid}s for the legacy NULL-org {@code RELEASE} audit rows.
	 * Separated from {@link #findDistinctReleaseUuidsByOrg} because {@code org = NULL} cannot be
	 * matched with {@code =}.
	 */
	@Query(value = "SELECT DISTINCT entity_uuid FROM rearm.metrics_audit "
			+ "WHERE entity_type = :entityType AND org IS NULL "
			+ "ORDER BY entity_uuid", nativeQuery = true)
	List<UUID> findDistinctReleaseUuidsWithNullOrg(@Param("entityType") String entityType);

	/**
	 * All audit rows for a single entity whose {@code revision_created_date} is {@code >= from},
	 * ordered by {@code metrics_revision} so the caller can walk the full snapshot timeline. Used by
	 * the backfill to reconstruct one release's transitions over its entire (or sinceRevisionDate-bounded)
	 * history. {@code entity_uuid}-scoped so legacy NULL-org rows are still returned.
	 */
	@Query(value = "SELECT * FROM rearm.metrics_audit "
			+ "WHERE entity_type = :entityType "
			+ "AND entity_uuid = :entityUuid "
			+ "AND revision_created_date >= :from "
			+ "ORDER BY metrics_revision", nativeQuery = true)
	List<MetricsAudit> findAllRevisionsForEntitySince(
			@Param("entityType") String entityType,
			@Param("entityUuid") UUID entityUuid,
			@Param("from") ZonedDateTime from);

	/**
	 * One PAGE of a release's audit timeline: rows with {@code metrics_revision > :afterRevision},
	 * ordered ascending, at most {@code :pageSize} rows. Lets the full-range backfill walk a large
	 * history in bounded-memory windows instead of loading every ~KB..MB snapshot of a release at once
	 * (observed to destabilize an instance with a multi-GB metrics_audit at boot).
	 */
	@Query(value = "SELECT * FROM rearm.metrics_audit "
			+ "WHERE entity_type = :entityType "
			+ "AND entity_uuid = :entityUuid "
			+ "AND metrics_revision > :afterRevision "
			+ "ORDER BY metrics_revision LIMIT :pageSize", nativeQuery = true)
	List<MetricsAudit> findRevisionsForEntityAfterRevision(
			@Param("entityType") String entityType,
			@Param("entityUuid") UUID entityUuid,
			@Param("afterRevision") int afterRevision,
			@Param("pageSize") int pageSize);

	// ==================================================================================
	// Repair sweep support (board task #38 closing phase). The daily bounded reseed only
	// needs the orgs/releases that were RE-SCANNED inside the lookback window, so its cost
	// scales with scan activity rather than total release count.
	// ==================================================================================

	/**
	 * Distinct organizations with at least one {@code RELEASE} audit row whose
	 * {@code revision_created_date >= :since} (recently re-scanned). NULL-org legacy rows are surfaced
	 * as a NULL element, mirroring {@link #findDistinctOrgsWithAudits}.
	 */
	@Query(value = "SELECT DISTINCT org FROM rearm.metrics_audit "
			+ "WHERE entity_type = :entityType AND revision_created_date >= :since", nativeQuery = true)
	List<UUID> findDistinctOrgsWithAuditsSince(
			@Param("entityType") String entityType,
			@Param("since") ZonedDateTime since);

	/**
	 * Distinct release {@code entity_uuid}s for an org with at least one audit row whose
	 * {@code revision_created_date >= :since}, ordered for stable iteration. The repair sweep's
	 * per-org unit-of-work list.
	 */
	@Query(value = "SELECT DISTINCT entity_uuid FROM rearm.metrics_audit "
			+ "WHERE entity_type = :entityType AND org = :org AND revision_created_date >= :since "
			+ "ORDER BY entity_uuid", nativeQuery = true)
	List<UUID> findDistinctReleaseUuidsByOrgSince(
			@Param("entityType") String entityType,
			@Param("org") UUID org,
			@Param("since") ZonedDateTime since);
}
