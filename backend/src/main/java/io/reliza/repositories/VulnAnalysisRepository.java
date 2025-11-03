/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
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

import io.reliza.model.VulnAnalysis;
import jakarta.persistence.LockModeType;

public interface VulnAnalysisRepository extends JpaRepository<VulnAnalysis, UUID> {
	
	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT va FROM VulnAnalysis va where va.uuid = :uuid")
	Optional<VulnAnalysis> findByIdWriteLocked(@Param("uuid") UUID uuid);
	
	@Query(
			value = VariableQueries.FIND_VULN_ANALYSIS_BY_ORG_LOCATION_FINDING_SCOPE,
			nativeQuery = true)
	Optional<VulnAnalysis> findByOrgAndLocationAndFindingIdAndScope(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("location") String location,
			@Param("findingId") String findingId,
			@Param("scope") String scope);
	
	@Query(
			value = VariableQueries.FIND_VULN_ANALYSIS_BY_ORG_LOCATION_FINDING_SCOPE_TYPE,
			nativeQuery = true)
	Optional<VulnAnalysis> findByOrgAndLocationAndFindingIdAndScopeAndType(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("location") String location,
			@Param("findingId") String findingId,
			@Param("scope") String scope,
			@Param("scopeId") String scopeId,
			@Param("findingType") String findingType);
	
	@Query(
			value = VariableQueries.FIND_VULN_ANALYSIS_BY_ORG_SCOPE_SCOPEUUID,
			nativeQuery = true)
	List<VulnAnalysis> findByOrgAndScopeAndScopeUuid(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("scope") String scope,
			@Param("scopeUuidAsString") String scopeUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_VULN_ANALYSIS_BY_ORG_LOCATION,
			nativeQuery = true)
	List<VulnAnalysis> findByOrgAndLocation(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("location") String location);
	
	@Query(
			value = VariableQueries.FIND_VULN_ANALYSIS_BY_ORG_FINDING_ID,
			nativeQuery = true)
	List<VulnAnalysis> findByOrgAndFindingId(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("findingId") String findingId);
	
	@Query(
			value = VariableQueries.FIND_VULN_ANALYSIS_BY_ORG_LOCATION_FINDING_TYPE,
			nativeQuery = true)
	List<VulnAnalysis> findByOrgAndLocationAndFindingIdAndType(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("location") String location,
			@Param("findingId") String findingId,
			@Param("findingType") String findingType);
}
