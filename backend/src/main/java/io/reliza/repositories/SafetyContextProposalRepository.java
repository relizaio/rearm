/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.repositories;

import java.util.Collection;
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

public interface SafetyContextProposalRepository extends JpaRepository<SafetyContextProposal, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT p FROM SafetyContextProposal p WHERE p.uuid = :uuid")
	Optional<SafetyContextProposal> findByIdWriteLocked(@Param("uuid") UUID uuid);

	@Query(value = VariableQueries.FIND_VEX_PROPOSAL_BY_ORG_AND_STATUS, nativeQuery = true)
	List<SafetyContextProposal> findByOrgAndStatus(
		@Param("orgUuidAsString") String orgUuidAsString,
		@Param("status") String status);

	@Query(value = VariableQueries.FIND_VEX_PROPOSAL_BY_ORG, nativeQuery = true)
	List<SafetyContextProposal> findByOrg(@Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * Find the prior proposal for the same (org, artifact, statementHash, scope, scopeUuid)
	 * tuple — i.e. the previous per-target row this re-upload should supersede. Returns
	 * Optional because at most one row matches the full 5-key tuple. See
	 * {@link VariableQueries#FIND_VEX_PROPOSAL_DEDUPE} for why scope + scopeUuid must be
	 * part of the key (one statement → many targets → one row each).
	 */
	@Query(value = VariableQueries.FIND_VEX_PROPOSAL_DEDUPE, nativeQuery = true)
	Optional<SafetyContextProposal> findForDedupe(
		@Param("orgUuidAsString") String orgUuidAsString,
		@Param("sourceArtifactAsString") String sourceArtifactAsString,
		@Param("sourceStatementHash") String sourceStatementHash,
		@Param("scope") String scope,
		@Param("scopeUuidAsString") String scopeUuidAsString);

	@Query(value = VariableQueries.FIND_VEX_PROPOSAL_BY_ARTIFACT, nativeQuery = true)
	List<SafetyContextProposal> findBySourceArtifact(@Param("sourceArtifactAsString") String sourceArtifactAsString);

	@Query(value = VariableQueries.FIND_VEX_PROPOSAL_BY_SOURCE_ARTIFACT_IN, nativeQuery = true)
	List<SafetyContextProposal> findByOrgAndSourceArtifactIn(
		@Param("orgUuidAsString") String orgUuidAsString,
		@Param("sourceArtifactsAsStrings") Collection<String> sourceArtifactsAsStrings);
}
