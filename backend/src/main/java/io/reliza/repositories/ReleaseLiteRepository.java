/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.ReleaseLite;

/**
 * Read-only repository over {@link ReleaseLite} — the light view of releases
 * that excludes the heavy metrics detail arrays (and approval/update events).
 * Use these methods on read paths that only need release fields + totals, so
 * the large arrays are never loaded. {@code findById} / {@code findAllById}
 * are inherited from {@link CrudRepository}.
 */
public interface ReleaseLiteRepository extends CrudRepository<ReleaseLite, UUID> {

	List<ReleaseLite> findByUuidIn(Collection<UUID> uuids);

	/**
	 * Light counterpart to ReleaseRepository.findReleasesOfBranch — same branch
	 * filter / created-date ordering / limit / offset, but selects only the
	 * columns ReleaseLite maps (no metrics column), so the heavy detail arrays
	 * are never read. Explicit column list is required so the native result maps
	 * onto ReleaseLite (and excludes metrics).
	 */
	@Query(value = "SELECT r.uuid, r.schema_version, r.created_date, r.record_data, r.metrics_totals, r.flow_control "
			+ "FROM rearm.releases r WHERE r.record_data->>'branch' = :branch "
			+ "ORDER BY r.created_date desc LIMIT cast(:limit as bigint) OFFSET cast(:offset as bigint)",
			nativeQuery = true)
	List<ReleaseLite> findReleasesOfBranchLite(@Param("branch") String branch, @Param("limit") String limit, @Param("offset") String offset);
}
