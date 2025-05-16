/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.ReleaseRebom;

public interface ReleaseRebomRepository extends CrudRepository<ReleaseRebom, UUID> {
	@Query(
			value = VariableQueries.FIND_RELEASE_REBOM_BY_RELEASE_AND_ORG,
			nativeQuery = true)
	Optional<ReleaseRebom> findReleaseRebomByOrgAndRelease(String orgUuidAsString,
			String releaseUuidAsString);
}
