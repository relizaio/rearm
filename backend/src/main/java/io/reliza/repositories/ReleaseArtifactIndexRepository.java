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

import io.reliza.model.ReleaseArtifactIndex;

public interface ReleaseArtifactIndexRepository extends CrudRepository<ReleaseArtifactIndex, UUID> {

	List<ReleaseArtifactIndex> findByOrgAndReleaseUuid(UUID org, UUID releaseUuid);

	List<ReleaseArtifactIndex> findByOrgAndReleaseUuidIn(UUID org, Collection<UUID> releaseUuids);

	boolean existsByOrgAndReleaseUuid(UUID org, UUID releaseUuid);

	@Modifying
	@Transactional
	@Query("DELETE FROM ReleaseArtifactIndex i WHERE i.org = :org AND i.releaseUuid = :releaseUuid")
	int deleteAllByOrgAndReleaseUuid(UUID org, UUID releaseUuid);
}
