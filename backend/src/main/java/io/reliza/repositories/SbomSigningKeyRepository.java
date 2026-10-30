/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.SbomSigningKey;
import jakarta.persistence.LockModeType;

/**
 * SBOM signing keys (SCORE-32). A {@link JpaRepository} for {@code saveAndFlush}: the partial unique
 * index {@code sbom_signing_keys_org_current} must refuse a racing rotation inside the service call,
 * where it is reported, not at commit.
 */
public interface SbomSigningKeyRepository extends JpaRepository<SbomSigningKey, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT k FROM SbomSigningKey k WHERE uuid = :uuid")
	Optional<SbomSigningKey> findByIdWriteLocked(UUID uuid);

	@Query(value = "SELECT * FROM rearm.sbom_signing_keys k "
			+ "WHERE k.record_data->>'org' = :orgUuidAsString "
			+ "AND k.record_data->>'status' = 'CURRENT'",
			nativeQuery = true)
	Optional<SbomSigningKey> findCurrentByOrg(@Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * The CURRENT key of an org, row-locked for the rotation that retires it. A second rotation
	 * waiting on the lock reads no row once the first commits (the row is no longer CURRENT), and its
	 * new CURRENT row then meets the partial unique index.
	 */
	@Query(value = "SELECT * FROM rearm.sbom_signing_keys k "
			+ "WHERE k.record_data->>'org' = :orgUuidAsString "
			+ "AND k.record_data->>'status' = 'CURRENT' FOR UPDATE",
			nativeQuery = true)
	Optional<SbomSigningKey> findCurrentByOrgWriteLocked(@Param("orgUuidAsString") String orgUuidAsString);

	@Query(value = "SELECT * FROM rearm.sbom_signing_keys k "
			+ "WHERE k.record_data->>'org' = :orgUuidAsString "
			+ "ORDER BY k.created_date DESC",
			nativeQuery = true)
	List<SbomSigningKey> findByOrg(@Param("orgUuidAsString") String orgUuidAsString);
}
