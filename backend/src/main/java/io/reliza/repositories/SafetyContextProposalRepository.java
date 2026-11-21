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

import io.reliza.model.SafetyContextProposal;
import jakarta.persistence.LockModeType;

/**
 * Safety-context proposals. Every query filters by org first, and the identity predicates repeat
 * the expressions of the V106 indexes verbatim (null scopeUuid / refs are passed as '').
 */
public interface SafetyContextProposalRepository extends JpaRepository<SafetyContextProposal, UUID> {

	String IDENTITY = """
		record_data->>'org' = :org
		AND record_data->>'scope' = :scope
		AND COALESCE(record_data->>'scopeUuid', '') = :scopeUuid
		AND record_data->>'componentPurl' = :componentPurl
		AND record_data->>'schemeKey' = :schemeKey
		AND COALESCE(record_data->>'blueprintRefId', '') = :blueprintRefId
		AND COALESCE(record_data->>'assetRefId', '') = :assetRefId
		""";

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT p FROM SafetyContextProposal p WHERE p.uuid = :uuid")
	Optional<SafetyContextProposal> findByIdWriteLocked(@Param("uuid") UUID uuid);

	/**
	 * Transaction-scoped advisory lock on one coarse key (org, scope target, purl, scheme).
	 * Two-key form, {@code lockNamespace} = {@code AdvisoryLockKey.SAFETY_CONTEXT}. Callers take
	 * the keys of one transaction in sorted order.
	 */
	@Query(value = """
		SELECT 1 FROM (SELECT pg_advisory_xact_lock(:lockNamespace, hashtext(:key))) AS locked
		""", nativeQuery = true)
	Integer lockCoarseKey(@Param("lockNamespace") int lockNamespace, @Param("key") String key);

	@Query(value = "SELECT * FROM rearm.safety_context_proposals WHERE " + IDENTITY + """
		AND record_data->>'lineageKey' = :lineageKey
		AND record_data->>'status' = 'PENDING'
		""", nativeQuery = true)
	Optional<SafetyContextProposal> findPendingAtIdentity(@Param("org") String org,
		@Param("scope") String scope, @Param("scopeUuid") String scopeUuid,
		@Param("componentPurl") String componentPurl, @Param("schemeKey") String schemeKey,
		@Param("blueprintRefId") String blueprintRefId, @Param("assetRefId") String assetRefId,
		@Param("lineageKey") String lineageKey);

	/** PENDING proposals at the identity from any lineage, for the cross-lineage conflict flag. */
	@Query(value = "SELECT * FROM rearm.safety_context_proposals WHERE " + IDENTITY + """
		AND record_data->>'status' = 'PENDING'
		""", nativeQuery = true)
	List<SafetyContextProposal> findAllPendingAtIdentity(@Param("org") String org,
		@Param("scope") String scope, @Param("scopeUuid") String scopeUuid,
		@Param("componentPurl") String componentPurl, @Param("schemeKey") String schemeKey,
		@Param("blueprintRefId") String blueprintRefId, @Param("assetRefId") String assetRefId);

	/** A PENDING or REJECTED proposal of the same lineage with the same assertion hash. */
	@Query(value = "SELECT count(*) > 0 FROM rearm.safety_context_proposals WHERE " + IDENTITY + """
		AND record_data->>'lineageKey' = :lineageKey
		AND record_data->>'sourceAssertionHash' = :sourceAssertionHash
		AND record_data->>'status' IN ('PENDING', 'REJECTED')
		""", nativeQuery = true)
	boolean existsUndecidedOrRejectedWithHash(@Param("org") String org,
		@Param("scope") String scope, @Param("scopeUuid") String scopeUuid,
		@Param("componentPurl") String componentPurl, @Param("schemeKey") String schemeKey,
		@Param("blueprintRefId") String blueprintRefId, @Param("assetRefId") String assetRefId,
		@Param("lineageKey") String lineageKey, @Param("sourceAssertionHash") String sourceAssertionHash);

	@Query(value = """
		SELECT count(*) FROM rearm.safety_context_proposals
		WHERE record_data->>'org' = :org AND record_data->>'releaseUuid' = :release
		AND record_data->>'status' = 'PENDING'
		""", nativeQuery = true)
	long countPendingByOrgAndRelease(@Param("org") String org, @Param("release") String release);

	@Query(value = """
		SELECT * FROM rearm.safety_context_proposals
		WHERE record_data->>'org' = :org
		AND (CAST(:status AS text) IS NULL OR record_data->>'status' = CAST(:status AS text))
		ORDER BY created_date DESC LIMIT :limit OFFSET :offset
		""", nativeQuery = true)
	List<SafetyContextProposal> findByOrgAndStatus(@Param("org") String org, @Param("status") String status,
		@Param("limit") int limit, @Param("offset") int offset);

	@Query(value = """
		SELECT * FROM rearm.safety_context_proposals
		WHERE record_data->>'org' = :org AND record_data->>'releaseUuid' = :release
		ORDER BY created_date DESC LIMIT :limit OFFSET :offset
		""", nativeQuery = true)
	List<SafetyContextProposal> findByOrgAndRelease(@Param("org") String org, @Param("release") String release,
		@Param("limit") int limit, @Param("offset") int offset);

	@Query(value = """
		SELECT * FROM rearm.safety_context_proposals
		WHERE record_data->>'org' = :org AND record_data->>'sourceArtifact' = :artifact
		ORDER BY created_date DESC
		""", nativeQuery = true)
	List<SafetyContextProposal> findByOrgAndSourceArtifact(@Param("org") String org, @Param("artifact") String artifact);
}
