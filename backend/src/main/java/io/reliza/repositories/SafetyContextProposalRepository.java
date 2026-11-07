/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

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
}
