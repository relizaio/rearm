/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.FindingChangeEventV3;

/**
 * Repository for the BRANCH-GRAIN "events-lite" fact (board task #38 follow-on, fact-row dedup) -- the
 * SOLE finding-change fact store after the per-release v1/v2 tables were dropped in V64. The read grain is
 * the BRANCH (not the release), so the range read is keyed by {@code branch_uuid}. Callers hydrate the
 * returned facts with their
 * {@link io.reliza.model.FindingDim} rows into full {@code FindingChangeEvent}s for the branch-grouped
 * reconstruction engine (Stage 2).
 */
public interface FindingChangeEventV3Repository extends CrudRepository<FindingChangeEventV3, UUID> {

	/**
	 * Time-range read keyed by {@code first_release_uuid} -- the RECONSTRUCTION read. The reverse-replay
	 * engine groups a release's events by release; a v3 fact is stamped with the release that PRODUCED the
	 * transition ({@code first_release_uuid}), so matching the anchor releases against it returns exactly
	 * that release's (deduped) events, and the engine is unchanged (see the v2 {@code findInRange}). This
	 * is the v3 analogue used by {@code FindingDimBackfillService.hydrateInRangeV3}.
	 */
	@Query("SELECT f FROM FindingChangeEventV3 f "
			+ "WHERE f.org = :org AND f.firstReleaseUuid IN (:firstReleaseUuids) "
			+ "AND f.changeDate >= :from AND f.changeDate <= :to "
			+ "ORDER BY f.changeDate")
	List<FindingChangeEventV3> findInRangeByFirstRelease(
			@Param("org") UUID org,
			@Param("firstReleaseUuids") Collection<UUID> firstReleaseUuids,
			@Param("from") ZonedDateTime from,
			@Param("to") ZonedDateTime to);

	/**
	 * ORG-GRAIN time-range read keyed by the event's own {@code component_uuid} (the org "posture over
	 * time" read, board task #39). Unlike {@link #findInRangeByFirstRelease}, this is NOT bounded to a
	 * release set produced in the window -- it returns EVERY branch-transition whose {@code change_date}
	 * falls in {@code [from,to]} for the authorized components, so a re-scan-driven change on a release
	 * produced before the window is surfaced (its {@code change_date} is in-window even though its
	 * {@code first_release_uuid} is not). The {@code component_uuid IN} clause carries the
	 * perspective/authorization boundary (the caller passes only components it has already resolved
	 * through the perspective/org filter), replacing the release-set boundary of the reconstruction read.
	 * Served by {@code finding_change_events_v3_component_date_idx (org, component_uuid, change_date)}.
	 * Ordered NEWEST-first and {@link Pageable}-bounded so the caller can cap the org-wide scan (the read
	 * is not release-set-bounded, so an unbounded window could be large).
	 */
	@Query("SELECT f FROM FindingChangeEventV3 f "
			+ "WHERE f.org = :org AND f.componentUuid IN (:componentUuids) "
			+ "AND f.changeDate >= :from AND f.changeDate <= :to "
			+ "ORDER BY f.changeDate DESC")
	List<FindingChangeEventV3> findInRangeByOrgAndComponents(
			@Param("org") UUID org,
			@Param("componentUuids") Collection<UUID> componentUuids,
			@Param("from") ZonedDateTime from,
			@Param("to") ZonedDateTime to,
			Pageable pageable);

	// NOTE: v3 fact rows are inserted via a JDBC batch (FindingDimBackfillService.V3_FACT_INSERT_SQL,
	// ON CONFLICT DO NOTHING on finding_change_events_v3_dedup_idx) -- one pipelined round-trip instead of
	// a per-row @Modifying insert. There is deliberately no insertIgnoreConflict method here so the insert
	// column list lives in exactly one place.
}
