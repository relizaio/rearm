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

public interface SafetyContextProposalRepository extends JpaRepository<SafetyContextProposal, UUID> {

    @Transactional
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT p FROM SafetyContextProposal p WHERE p.uuid = :uuid")
    Optional<SafetyContextProposal> findByIdForUpdate(@Param("uuid") UUID uuid);

    @Query(value = """
        SELECT * FROM rearm.safety_context_proposals
        WHERE record_data->>'org' = :orgUuidAsString
        AND record_data->>'sourceArtifact' = :sourceArtifactAsString
        AND record_data->>'sourceAssertionHash' = :sourceAssertionHash
        AND record_data->>'scope' = :scope
        AND record_data->>'scopeUuid' = :scopeUuidAsString
        """, nativeQuery = true)
    List<SafetyContextProposal> findDedupeCandidates(
        @Param("orgUuidAsString") String orgUuidAsString,
        @Param("sourceArtifactAsString") String sourceArtifactAsString,
        @Param("sourceAssertionHash") String sourceAssertionHash,
        @Param("scope") String scope,
        @Param("scopeUuidAsString") String scopeUuidAsString);
}
