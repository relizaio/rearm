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

import io.reliza.model.SafetyContextRecord;

/** Accepted safety-context records; queries in {@link VariableQueries} (org first, index expressions verbatim). */
public interface SafetyContextRecordRepository extends JpaRepository<SafetyContextRecord, UUID> {

	@Query(value = VariableQueries.FIND_SAFETY_CONTEXT_RECORD_ACTIVE_AT_IDENTITY, nativeQuery = true)
	Optional<SafetyContextRecord> findActiveAtIdentity(@Param("org") String org,
		@Param("scope") String scope, @Param("scopeUuid") String scopeUuid,
		@Param("componentPurl") String componentPurl, @Param("schemeKey") String schemeKey,
		@Param("blueprintRefId") String blueprintRefId, @Param("assetRefId") String assetRefId);

	/** ACTIVE records for one component and scheme across all assets (coarse key). */
	@Query(value = VariableQueries.FIND_SAFETY_CONTEXT_RECORDS_ACTIVE_AT_COARSE_KEY, nativeQuery = true)
	List<SafetyContextRecord> findActiveAtCoarseKey(@Param("org") String org, @Param("scope") String scope,
		@Param("scopeUuid") String scopeUuid, @Param("componentPurl") String componentPurl,
		@Param("schemeKey") String schemeKey);

	@Query(value = VariableQueries.FIND_SAFETY_CONTEXT_RECORDS_AT_RELEASE_TARGETS, nativeQuery = true)
	List<SafetyContextRecord> findAtReleaseTargets(@Param("org") String org, @Param("release") String release,
		@Param("branch") String branch, @Param("component") String component,
		@Param("purls") String purls, @Param("includeSuperseded") boolean includeSuperseded, @Param("cap") int cap);

	@Query(value = VariableQueries.FIND_SAFETY_CONTEXT_RECORDS_BY_ORG_AND_SCOPE_TARGET, nativeQuery = true)
	List<SafetyContextRecord> findByOrgAndScopeTarget(@Param("org") String org, @Param("scope") String scope,
		@Param("scopeUuid") String scopeUuid, @Param("includeSuperseded") boolean includeSuperseded,
		@Param("limit") int limit, @Param("offset") int offset);
}
