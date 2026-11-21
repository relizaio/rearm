/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.reliza.model.SafetyContextProposal;

/** Safety-context proposals; queries in {@link VariableQueries} (org first, index expressions verbatim). */
public interface SafetyContextProposalRepository extends JpaRepository<SafetyContextProposal, UUID> {

	/**
	 * Transaction-scoped advisory lock on one coarse key (org, scope target, purl, scheme).
	 * Two-key form, {@code lockNamespace} = {@code AdvisoryLockKey.SAFETY_CONTEXT}; callers take
	 * the keys of one transaction in sorted order.
	 */
	@Query(value = VariableQueries.SAFETY_CONTEXT_LOCK_COARSE_KEY, nativeQuery = true)
	Integer lockCoarseKey(@Param("lockNamespace") int lockNamespace, @Param("key") String key);

	@Query(value = VariableQueries.FIND_SAFETY_CONTEXT_PROPOSAL_PENDING_AT_IDENTITY, nativeQuery = true)
	Optional<SafetyContextProposal> findPendingAtIdentity(@Param("org") String org,
		@Param("scope") String scope, @Param("scopeUuid") String scopeUuid,
		@Param("componentPurl") String componentPurl, @Param("schemeKey") String schemeKey,
		@Param("blueprintRefId") String blueprintRefId, @Param("assetRefId") String assetRefId,
		@Param("lineageKey") String lineageKey);

	/** PENDING proposals at the identity from any lineage, for the cross-lineage conflict flag. */
	@Query(value = VariableQueries.FIND_SAFETY_CONTEXT_PROPOSALS_PENDING_AT_IDENTITY, nativeQuery = true)
	List<SafetyContextProposal> findAllPendingAtIdentity(@Param("org") String org,
		@Param("scope") String scope, @Param("scopeUuid") String scopeUuid,
		@Param("componentPurl") String componentPurl, @Param("schemeKey") String schemeKey,
		@Param("blueprintRefId") String blueprintRefId, @Param("assetRefId") String assetRefId);

	/** A PENDING or REJECTED proposal of the same lineage with the same assertion hash. */
	@Query(value = VariableQueries.EXISTS_SAFETY_CONTEXT_PROPOSAL_UNDECIDED_OR_REJECTED_WITH_HASH, nativeQuery = true)
	boolean existsUndecidedOrRejectedWithHash(@Param("org") String org,
		@Param("scope") String scope, @Param("scopeUuid") String scopeUuid,
		@Param("componentPurl") String componentPurl, @Param("schemeKey") String schemeKey,
		@Param("blueprintRefId") String blueprintRefId, @Param("assetRefId") String assetRefId,
		@Param("lineageKey") String lineageKey, @Param("sourceAssertionHash") String sourceAssertionHash);

	@Query(value = VariableQueries.COUNT_SAFETY_CONTEXT_PROPOSALS_PENDING_BY_ORG_AND_RELEASE, nativeQuery = true)
	long countPendingByOrgAndRelease(@Param("org") String org, @Param("release") String release);

	@Query(value = VariableQueries.FIND_SAFETY_CONTEXT_PROPOSALS_BY_ORG_AND_STATUS, nativeQuery = true)
	List<SafetyContextProposal> findByOrgAndStatus(@Param("org") String org, @Param("status") String status,
		@Param("limit") int limit, @Param("offset") int offset);

	@Query(value = VariableQueries.FIND_SAFETY_CONTEXT_PROPOSALS_BY_ORG_AND_RELEASE, nativeQuery = true)
	List<SafetyContextProposal> findByOrgAndRelease(@Param("org") String org, @Param("release") String release,
		@Param("limit") int limit, @Param("offset") int offset);

	@Query(value = VariableQueries.FIND_SAFETY_CONTEXT_PROPOSALS_BY_ORG_AND_ARTIFACT, nativeQuery = true)
	List<SafetyContextProposal> findByOrgAndSourceArtifact(@Param("org") String org, @Param("artifact") String artifact,
		@Param("limit") int limit, @Param("offset") int offset);
}
