/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.ReleaseSbomComponent;

public interface ReleaseSbomComponentRepository extends CrudRepository<ReleaseSbomComponent, UUID> {

	List<ReleaseSbomComponent> findByOrgAndReleaseUuid(UUID org, UUID releaseUuid);

	boolean existsByOrgAndReleaseUuid(UUID org, UUID releaseUuid);

	List<ReleaseSbomComponent> findByOrgAndReleaseUuidIn(UUID org, Collection<UUID> releaseUuids);

	Optional<ReleaseSbomComponent> findByOrgAndReleaseUuidAndSbomComponentUuid(
			UUID org, UUID releaseUuid, UUID sbomComponentUuid);

	/**
	 * Org-scoped impact lookup: distinct release UUIDs (within {@code org})
	 * whose release_sbom_components reference any of the supplied canonical
	 * sbom_components. The org filter is a direct column match — no join.
	 */
	@Query("SELECT DISTINCT r.releaseUuid FROM ReleaseSbomComponent r "
			+ "WHERE r.org = :org AND r.sbomComponentUuid IN :sbomComponentUuids")
	List<UUID> findDistinctReleaseUuidsByOrgAndSbomComponentUuidIn(
			UUID org, Collection<UUID> sbomComponentUuids);

	@Modifying
	@Transactional
	@Query("DELETE FROM ReleaseSbomComponent r "
			+ "WHERE r.org = :org AND r.releaseUuid = :releaseUuid "
			+ "AND r.sbomComponentUuid NOT IN :keepComponentUuids")
	int deleteByOrgAndReleaseUuidAndSbomComponentUuidNotIn(
			UUID org, UUID releaseUuid, Collection<UUID> keepComponentUuids);

	@Modifying
	@Transactional
	@Query("DELETE FROM ReleaseSbomComponent r WHERE r.org = :org AND r.releaseUuid = :releaseUuid")
	int deleteAllByOrgAndReleaseUuid(UUID org, UUID releaseUuid);
}
