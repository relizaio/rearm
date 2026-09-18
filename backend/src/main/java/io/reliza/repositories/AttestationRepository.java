/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import io.reliza.model.Attestation;

public interface AttestationRepository extends JpaRepository<Attestation, UUID> {

	@Query(value = VariableQueries.FIND_INTEGRITY_ATTESTATIONS_BY_SUBJECT, nativeQuery = true)
	List<Attestation> findBySubject(
		@Param("orgUuidAsString") String orgUuidAsString,
		@Param("subjectType") String subjectType,
		@Param("subjectUuidAsString") String subjectUuidAsString);

	@Query(value = VariableQueries.FIND_INTEGRITY_ATTESTATIONS_BY_SUBJECTS, nativeQuery = true)
	List<Attestation> findBySubjects(
		@Param("orgUuidAsString") String orgUuidAsString,
		@Param("subjectType") String subjectType,
		@Param("subjectUuidsAsString") String[] subjectUuidsAsString);

	@Query(value = VariableQueries.FIND_INTEGRITY_ATTESTATIONS_BY_ORG_AND_TYPE, nativeQuery = true)
	List<Attestation> findByOrgAndType(
		@Param("orgUuidAsString") String orgUuidAsString,
		@Param("type") String type,
		@Param("status") String status);
}
