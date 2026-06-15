/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.VersionAssignment;
import jakarta.persistence.LockModeType;

public interface VersionAssignmentRepository extends CrudRepository<VersionAssignment, UUID> {
	@Query(
			value = VariableQueries.LATEST_VERSION_ASSIGNMENTS_WITH_LIMIT,
			nativeQuery = true)
	List<VersionAssignment> findLatestVersionAssignmentsWithLimit(UUID branchUuid, String versionType, int limit);

	@Query(
			value = VariableQueries.LATEST_VERSION_ASSIGNMENTS_WITH_COMPONENT_SCHEMA_AND_LIMIT_BY_BRANCH,
			nativeQuery = true)
	List<VersionAssignment> findLatestVersionAssignmentsWithComponentSchemaAndLimitByBranch(UUID branchUuid, int limit, String schema, String versionType);
	
	@Query(
			value = VariableQueries.LATEST_VERSION_ASSIGNMENTS_WITH_BRANCH_SCHEMA_AND_LIMIT_BY_BRANCH,
			nativeQuery = true)
	List<VersionAssignment> findLatestVersionAssignmentsWithBranchSchemaAndLimitByBranch(UUID branchUuid, int limit, String schema, String versionType);

	@Query(
			value = VariableQueries.LATEST_VERSION_ASSIGNMENTS_WITH_SCHEMA_AND_LIMIT_BY_COMPONENT,
			nativeQuery = true)
	List<VersionAssignment> findLatestVersionAssignmentsWithSchemaAndLimitByComponent(UUID componentUuid, int limit, String schema, String versionType);
	
	@Query(
			value = VariableQueries.FIND_VERSION_ASSIGNMENT_BY_COMPONENT_AND_VERSION,
			nativeQuery = true)
	Optional<VersionAssignment> findVersionAssignmentByComponentAndVersion(UUID componentUuid, String version, String versionType);

	@Query(
			value = VariableQueries.FIND_VERSION_ASSIGNMENTS_BY_COMPONENT_AND_VERSION_LIKE,
			nativeQuery = true)
	List<VersionAssignment> findVersionAssignmentsByComponentAndVersionLike(UUID componentUuid, String versionType, String versionLike);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT va from VersionAssignment va WHERE branch = :branchUuid AND assignmentType = 'OPEN' AND versionType = :versionType")
	Optional<VersionAssignment> findOPENVersionAssignmentByBranch(UUID branchUuid, String versionType);

	/**
	 * Lookup for the commit-keyed dedup at getversion time. Returns the
	 * existing version assignment for {@code (component, branch, commit)}
	 * if any — caller decides whether to surface it (rebuild) or fail
	 * with a duplicate error.
	 */
	@Query(value = "SELECT va FROM VersionAssignment va "
			+ "WHERE component = :componentUuid AND branch = :branchUuid AND commit = :commit")
	Optional<VersionAssignment> findByComponentAndBranchAndCommit(UUID componentUuid, UUID branchUuid, String commit);

	@Query(
		value = VariableQueries.FIND_ALL_VERSION_ASSIGNMENTS_BY_ORG,
		nativeQuery = true)
	List<VersionAssignment> findByOrg(UUID orgUuid);

}
