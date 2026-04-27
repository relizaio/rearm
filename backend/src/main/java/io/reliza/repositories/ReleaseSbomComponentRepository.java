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

	List<ReleaseSbomComponent> findByReleaseUuid(UUID releaseUuid);

	List<ReleaseSbomComponent> findByReleaseUuidIn(Collection<UUID> releaseUuids);

	Optional<ReleaseSbomComponent> findByReleaseUuidAndSbomComponentUuid(UUID releaseUuid, UUID sbomComponentUuid);

	@Query("SELECT DISTINCT r.releaseUuid FROM ReleaseSbomComponent r WHERE r.sbomComponentUuid IN :sbomComponentUuids")
	List<UUID> findDistinctReleaseUuidsBySbomComponentUuidIn(Collection<UUID> sbomComponentUuids);

	@Modifying
	@Transactional
	@Query("DELETE FROM ReleaseSbomComponent r WHERE r.releaseUuid = :releaseUuid AND r.sbomComponentUuid NOT IN :keepComponentUuids")
	int deleteByReleaseUuidAndSbomComponentUuidNotIn(UUID releaseUuid, Collection<UUID> keepComponentUuids);

	@Modifying
	@Transactional
	@Query("DELETE FROM ReleaseSbomComponent r WHERE r.releaseUuid = :releaseUuid")
	int deleteAllByReleaseUuid(UUID releaseUuid);
}
