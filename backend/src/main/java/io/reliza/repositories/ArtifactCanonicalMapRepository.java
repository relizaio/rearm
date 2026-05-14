/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.CrudRepository;

import io.reliza.model.ArtifactCanonicalMap;

public interface ArtifactCanonicalMapRepository extends CrudRepository<ArtifactCanonicalMap, UUID> {

	Optional<ArtifactCanonicalMap> findByArtifactUuid(UUID artifactUuid);

	List<ArtifactCanonicalMap> findByOrgAndArtifactUuidIn(UUID org, Collection<UUID> artifactUuids);

	/**
	 * Reverse lookup: every artifact uuid in the org that points to the given
	 * canonical. Useful for surfacing "which uploads share this content"
	 * cross-release analytics.
	 */
	List<ArtifactCanonicalMap> findByOrgAndCanonicalArtifactUuid(UUID org, UUID canonicalArtifactUuid);
}
