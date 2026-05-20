/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.Committer;
import jakarta.persistence.LockModeType;

public interface CommitterRepository extends CrudRepository<Committer, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT c FROM Committer c WHERE uuid = :uuid")
	Optional<Committer> findByIdWriteLocked(UUID uuid);

	/**
	 * Primary lookup: (org, lower(email)). Used by the verifier to
	 * resolve a commit author header to a committer row, and by the
	 * upsert mutation.
	 */
	@Query(value = "SELECT * FROM rearm.committers c "
			+ "WHERE c.record_data->>'org' = :orgUuidAsString "
			+ "AND lower(c.record_data->>'email') = lower(:email)",
			nativeQuery = true)
	Optional<Committer> findByOrgAndEmail(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("email") String email);

	/**
	 * Alias lookup: emails the committer used historically. ANY()
	 * across the jsonb aliases array so a single index can serve
	 * primary + alias lookups.
	 */
	@Query(value = "SELECT * FROM rearm.committers c "
			+ "WHERE c.record_data->>'org' = :orgUuidAsString "
			+ "AND EXISTS (SELECT 1 FROM jsonb_array_elements_text(COALESCE(c.record_data->'aliases', '[]'::jsonb)) AS a(v) "
			+ "            WHERE lower(a.v) = lower(:email))",
			nativeQuery = true)
	Optional<Committer> findByOrgAndAlias(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("email") String email);

	@Query(value = "SELECT * FROM rearm.committers c "
			+ "WHERE c.record_data->>'org' = :orgUuidAsString "
			+ "ORDER BY c.created_date DESC",
			nativeQuery = true)
	List<Committer> findByOrg(@Param("orgUuidAsString") String orgUuidAsString);
}
