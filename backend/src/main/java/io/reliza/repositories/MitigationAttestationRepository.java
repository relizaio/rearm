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

import io.reliza.model.MitigationAttestation;
import jakarta.persistence.LockModeType;

public interface MitigationAttestationRepository extends JpaRepository<MitigationAttestation, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT m FROM MitigationAttestation m WHERE m.uuid = :uuid")
	Optional<MitigationAttestation> findByIdWriteLocked(@Param("uuid") UUID uuid);

	@Query(value = VariableQueries.FIND_ATTESTATION_BY_ORG_AND_STATUS, nativeQuery = true)
	List<MitigationAttestation> findByOrgAndStatus(
		@Param("orgUuidAsString") String orgUuidAsString,
		@Param("status") String status);

	@Query(value = VariableQueries.FIND_ATTESTATION_BY_PROPOSAL, nativeQuery = true)
	Optional<MitigationAttestation> findByProposal(@Param("proposalUuidAsString") String proposalUuidAsString);

	@Query(value = VariableQueries.FIND_ATTESTATION_BY_ASSIGNEE, nativeQuery = true)
	List<MitigationAttestation> findByAssignee(
		@Param("assigneeUuidAsString") String assigneeUuidAsString,
		@Param("status") String status);
}
