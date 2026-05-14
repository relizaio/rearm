/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.ReleaseSbomEdge;

public interface ReleaseSbomEdgeRepository extends CrudRepository<ReleaseSbomEdge, UUID> {

	List<ReleaseSbomEdge> findByOrgAndReleaseUuid(UUID org, UUID releaseUuid);

	List<ReleaseSbomEdge> findByOrgAndReleaseUuidIn(UUID org, Collection<UUID> releaseUuids);

	@Modifying
	@Transactional
	@Query("DELETE FROM ReleaseSbomEdge e WHERE e.org = :org AND e.releaseUuid = :releaseUuid")
	int deleteAllByOrgAndReleaseUuid(UUID org, UUID releaseUuid);
}
