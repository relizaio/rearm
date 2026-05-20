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

import io.reliza.model.ModelOntology;
import jakarta.persistence.LockModeType;

public interface ModelOntologyRepository extends CrudRepository<ModelOntology, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT m FROM ModelOntology m WHERE uuid = :uuid")
	Optional<ModelOntology> findByIdWriteLocked(UUID uuid);

	/**
	 * Lookup by (org, lower(name), version) — the natural-identity
	 * boundary the V38 unique index enforces. Used by the auto-upsert
	 * path during session initialize.
	 */
	@Query(value = "SELECT * FROM rearm.model_ontologies m "
			+ "WHERE m.record_data->>'org' = :orgUuidAsString "
			+ "AND lower(m.record_data->>'name') = lower(:name) "
			+ "AND m.record_data->>'version' = :version",
			nativeQuery = true)
	Optional<ModelOntology> findByOrgNameVersion(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("name") String name,
			@Param("version") String version);

	@Query(value = "SELECT * FROM rearm.model_ontologies m "
			+ "WHERE m.record_data->>'org' = :orgUuidAsString "
			+ "ORDER BY m.created_date DESC",
			nativeQuery = true)
	List<ModelOntology> findByOrg(@Param("orgUuidAsString") String orgUuidAsString);
}
