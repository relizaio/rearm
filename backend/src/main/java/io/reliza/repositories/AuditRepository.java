/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.math.BigInteger;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.Audit;

public interface AuditRepository extends CrudRepository<Audit, UUID> {
	@Query(
			value = VariableQueries.RETRIEVE_AUDIT_RECORDS_FOR_ENTITY,
			nativeQuery = true)
	List<Audit> retrieveAuditRecordsForEntity(String tableName, String entUuidAsStr, String limitAsStr, String offsetAsStr);
	
	@Query(
			value = VariableQueries.RETRIEVE_ENTITY_REVISION,
			nativeQuery = true)
	Optional<Audit> retrieveEntityRevision(String tableName, String entUuidAsStr, String revisionAsStr);

	@Query(
			value = VariableQueries.RETRIEVE_ALL_AUDIT_REVISIONS,
			nativeQuery = true)
	List<Audit> getAllAuditRevisions(String orgUuid, String tableName, String cutOffDate);

	@Query(
			value = VariableQueries.RETRIEVE_ALL_AUDIT_REVISIONS_BY_ORG,
			nativeQuery = true)
	List<Audit> getAllAuditRevisions(String orgUuid);

	@Query(
			value = VariableQueries.RETRIEVE_AUDIT_RECORDS_FOR_ENTITY_BY_DATES,
			nativeQuery = true)
	List<Audit> retrieveAuditRecordsForEntityByDates(String orgUuid, String tableName, String dateFrom, String dateTo, String limitAsStr);

	@Query(
			value = VariableQueries.RETRIEVE_AUDIT_RECORDS_FOR_SINGLE_ENTITY_BY_DATES,
			nativeQuery = true)
	List<Audit> retrieveAuditRecordsForSingleEntityWithDates(String tableName, String entUuidAsStr, BigInteger limit,
			BigInteger offset, String dateFrom, String dateTo);
}
