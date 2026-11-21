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

import io.reliza.model.SafetyContextRecord;
import jakarta.persistence.LockModeType;

/**
 * Accepted safety-context records. Every query filters by org first; identity predicates repeat
 * the V106 index expressions verbatim (null scopeUuid / refs are passed as '').
 */
public interface SafetyContextRecordRepository extends JpaRepository<SafetyContextRecord, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT r FROM SafetyContextRecord r WHERE r.uuid = :uuid")
	Optional<SafetyContextRecord> findByIdWriteLocked(@Param("uuid") UUID uuid);

	@Query(value = "SELECT * FROM rearm.safety_context_records WHERE "
		+ SafetyContextProposalRepository.IDENTITY + " AND record_data->>'status' = 'ACTIVE'", nativeQuery = true)
	Optional<SafetyContextRecord> findActiveAtIdentity(@Param("org") String org,
		@Param("scope") String scope, @Param("scopeUuid") String scopeUuid,
		@Param("componentPurl") String componentPurl, @Param("schemeKey") String schemeKey,
		@Param("blueprintRefId") String blueprintRefId, @Param("assetRefId") String assetRefId);

	/** ACTIVE records for one component and scheme across all assets (coarse key). */
	@Query(value = """
		SELECT * FROM rearm.safety_context_records
		WHERE record_data->>'org' = :org AND record_data->>'scope' = :scope
		AND record_data->>'scopeUuid' = :scopeUuid
		AND record_data->>'componentPurl' = :componentPurl AND record_data->>'schemeKey' = :schemeKey
		AND record_data->>'status' = 'ACTIVE'
		""", nativeQuery = true)
	List<SafetyContextRecord> findActiveAtCoarseKey(@Param("org") String org, @Param("scope") String scope,
		@Param("scopeUuid") String scopeUuid, @Param("componentPurl") String componentPurl,
		@Param("schemeKey") String schemeKey);

	@Query(value = """
		SELECT * FROM rearm.safety_context_records
		WHERE record_data->>'org' = :org AND record_data->>'releaseUuid' = :release
		AND (:includeSuperseded OR record_data->>'status' = 'ACTIVE')
		ORDER BY created_date DESC LIMIT :limit OFFSET :offset
		""", nativeQuery = true)
	List<SafetyContextRecord> findByOrgAndRelease(@Param("org") String org, @Param("release") String release,
		@Param("includeSuperseded") boolean includeSuperseded, @Param("limit") int limit, @Param("offset") int offset);

	@Query(value = """
		SELECT * FROM rearm.safety_context_records
		WHERE record_data->>'org' = :org AND record_data->>'scope' = :scope
		AND record_data->>'scopeUuid' = :scopeUuid
		AND (:includeSuperseded OR record_data->>'status' = 'ACTIVE')
		ORDER BY created_date DESC LIMIT :limit OFFSET :offset
		""", nativeQuery = true)
	List<SafetyContextRecord> findByOrgAndScopeTarget(@Param("org") String org, @Param("scope") String scope,
		@Param("scopeUuid") String scopeUuid, @Param("includeSuperseded") boolean includeSuperseded,
		@Param("limit") int limit, @Param("offset") int offset);
}
