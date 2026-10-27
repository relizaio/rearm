/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

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

	/**
	 * Empties the stored merged rows of the seed releases and of every release of the organization
	 * above them, and moves the revision of each of those records, already empty or not (SCORE-71
	 * ADR-1). The seed list is one text[] parameter. Joins the caller's transaction.
	 *
	 * @return the number of records touched
	 */
	@Transactional
	@Modifying
	@Query(value = VariableQueries.INVALIDATE_RELEASE_REBOMS_OF_RELEASES_AND_ABOVE, nativeQuery = true)
	int invalidateRowsOfReleasesAndProductsAbove(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("seedReleaseUuidsAsStrings") String[] seedReleaseUuidsAsStrings);

	/**
	 * The component-seeded form of {@link #invalidateRowsOfReleasesAndProductsAbove}: every release of
	 * the component is a seed (SCORE-71 ADR-9).
	 *
	 * @return the number of records touched
	 */
	@Transactional
	@Modifying
	@Query(value = VariableQueries.INVALIDATE_RELEASE_REBOMS_OF_COMPONENT_RELEASES_AND_ABOVE, nativeQuery = true)
	int invalidateRowsOfComponentReleasesAndProductsAbove(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("componentUuidAsString") String componentUuidAsString);

	/**
	 * The record of a release as scalars: uuid text, revision, created date as epoch millis, record
	 * data as text (SCORE-71 ADR-11). Reads the row as it is now, whatever the persistence context holds.
	 */
	@Query(value = VariableQueries.READ_RELEASE_REBOM_STAMP, nativeQuery = true)
	List<Object[]> readStamp(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("releaseUuidAsString") String releaseUuidAsString);

	/**
	 * Rewrites the record only when its revision is still {@code revision} (SCORE-71 ADR-11).
	 *
	 * @return 1 when written, 0 when the record moved
	 */
	@Transactional
	@Modifying
	@Query(value = VariableQueries.UPDATE_RELEASE_REBOM_IF_REVISION, nativeQuery = true)
	int updateRecordIfRevision(@Param("uuid") UUID uuid, @Param("recordData") String recordData,
			@Param("revision") int revision);

	/**
	 * Inserts the record of a release only when it has none (SCORE-71 ADR-11).
	 *
	 * @return 1 when inserted, 0 when a record appeared
	 */
	@Transactional
	@Modifying
	@Query(value = VariableQueries.INSERT_RELEASE_REBOM_IF_ABSENT, nativeQuery = true)
	int insertRecordIfAbsent(@Param("uuid") UUID uuid, @Param("recordData") String recordData,
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("releaseUuidAsString") String releaseUuidAsString);
}
