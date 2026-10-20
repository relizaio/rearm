/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.ReleaseRebom;

public interface ReleaseRebomRepository extends CrudRepository<ReleaseRebom, UUID> {
	@Query(
			value = VariableQueries.FIND_RELEASE_REBOM_BY_RELEASE_AND_ORG,
			nativeQuery = true)
	Optional<ReleaseRebom> findReleaseRebomByOrgAndRelease(String orgUuidAsString,
			String releaseUuidAsString);

	/**
	 * Whether any release's stored merge rows list {@code rebomIdAsString} (canonical uuid text) as a
	 * rebomId, in any organization (SCORE-52 ADR-3 step 2 (b)). Not indexed: release_reboms has no
	 * index, and the query runs only on rule B's copy branch for a serialNumber no artifact records.
	 */
	@Query(
			value = VariableQueries.EXISTS_RELEASE_REBOM_LISTING_REBOM_ID,
			nativeQuery = true)
	boolean existsListingRebomId(@Param("rebomIdAsString") String rebomIdAsString);
}
