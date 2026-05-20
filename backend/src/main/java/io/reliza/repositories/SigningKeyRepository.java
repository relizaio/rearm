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

import io.reliza.model.SigningKey;
import jakarta.persistence.LockModeType;

public interface SigningKeyRepository extends CrudRepository<SigningKey, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT k FROM SigningKey k WHERE uuid = :uuid")
	Optional<SigningKey> findByIdWriteLocked(UUID uuid);

	/**
	 * Active enrolment by fingerprint — the verifier's primary lookup.
	 * Returns the key only when not revoked, so policy decisions on
	 * new commits respect revocation. Historical verdicts (already
	 * stored on signature_verifications) reference the row directly
	 * and don't re-walk this lookup.
	 */
	@Query(value = "SELECT * FROM rearm.signing_keys k "
			+ "WHERE k.record_data->>'org' = :orgUuidAsString "
			+ "AND k.record_data->>'fingerprint' = :fingerprint "
			+ "AND k.record_data->>'revokedAt' IS NULL",
			nativeQuery = true)
	Optional<SigningKey> findActiveByOrgAndFingerprint(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("fingerprint") String fingerprint);

	@Query(value = "SELECT * FROM rearm.signing_keys k "
			+ "WHERE k.record_data->>'org' = :orgUuidAsString "
			+ "AND k.record_data->>'ownerType' = :ownerType "
			+ "AND k.record_data->>'ownerUuid' = :ownerUuidAsString "
			+ "ORDER BY k.created_date ASC",
			nativeQuery = true)
	List<SigningKey> findByOwner(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("ownerType") String ownerType,
			@Param("ownerUuidAsString") String ownerUuidAsString);

	@Query(value = "SELECT * FROM rearm.signing_keys k "
			+ "WHERE k.record_data->>'org' = :orgUuidAsString",
			nativeQuery = true)
	List<SigningKey> findByOrg(@Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * Active keys for one owner-type bucket of an org. Used by the
	 * verifier's committer-only scope when the SCE carries no
	 * agentic trailer — AGENT keys are excluded entirely from
	 * non-agentic commits.
	 */
	@Query(value = "SELECT * FROM rearm.signing_keys k "
			+ "WHERE k.record_data->>'org' = :orgUuidAsString "
			+ "AND k.record_data->>'ownerType' = :ownerType "
			+ "AND k.record_data->>'revokedAt' IS NULL",
			nativeQuery = true)
	List<SigningKey> findActiveByOrgAndOwnerType(@Param("orgUuidAsString") String orgUuidAsString,
			@Param("ownerType") String ownerType);
}
