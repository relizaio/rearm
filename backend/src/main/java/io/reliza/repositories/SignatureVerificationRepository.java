/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.SignatureVerification;

public interface SignatureVerificationRepository extends CrudRepository<SignatureVerification, UUID> {

	/**
	 * All verdicts attached to a given subject. The verifier picks the
	 * latest by created_date for the CEL surface; the list is exposed
	 * raw for the UI to display the audit history.
	 */
	@Query(value = "SELECT * FROM rearm.signature_verifications v "
			+ "WHERE v.record_data->>'subjectType' = :subjectType "
			+ "AND v.record_data->>'subjectUuid' = :subjectUuidAsString "
			+ "ORDER BY v.created_date DESC",
			nativeQuery = true)
	List<SignatureVerification> findBySubject(@Param("subjectType") String subjectType,
			@Param("subjectUuidAsString") String subjectUuidAsString);

	/**
	 * Latest verdict for a given subject — the value CEL reads when
	 * evaluating {@code commit.signature.*}.
	 */
	@Query(value = "SELECT * FROM rearm.signature_verifications v "
			+ "WHERE v.record_data->>'subjectType' = :subjectType "
			+ "AND v.record_data->>'subjectUuid' = :subjectUuidAsString "
			+ "ORDER BY v.created_date DESC "
			+ "LIMIT 1",
			nativeQuery = true)
	Optional<SignatureVerification> findLatestBySubject(@Param("subjectType") String subjectType,
			@Param("subjectUuidAsString") String subjectUuidAsString);
}
