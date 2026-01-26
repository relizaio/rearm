/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.Artifact;

public interface ArtifactRepository extends CrudRepository<Artifact, UUID> {
	
	@Query(
			value = VariableQueries.LIST_ARTIFACTS_BY_ORG,
			nativeQuery = true)
	List<Artifact> listArtifactsByOrg(String orgUuidAsString);
	
	@Query(
			value = VariableQueries.LIST_INITIAL_ARTIFACTS_PENDING_DEPENDENCY_TRACK,
			nativeQuery = true)
	List<Artifact> listInitialArtifactsPendingOnDependencyTrack();
	
	@Query(
			value = VariableQueries.LIST_ARTIFACTS_PENDING_DTRACK_SUBMISSION,
			nativeQuery = true)
	List<Artifact> listArtifactsPendingDtrackSubmission(String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_ARTIFACTS_BY_STORED_DIGEST,
			nativeQuery = true)
	List<Artifact> findArtifactsByStoredDigest(String orgUuidAsString, String digest);
	
	@Query(
			value = VariableQueries.FIND_ARTIFACTS_BY_DTRACK_PROJECTS,
			nativeQuery = true)
	List<Artifact> findArtifactsByDtrackProjects(List<String> dtrackProjectIds);
	
	@Query(
			value = VariableQueries.FIND_ORPHANED_DTRACK_PROJECTS,
			nativeQuery = true)
	List<String> findOrphanedDtrackProjects(@Param("orgUuidAsString") String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_ARTIFACTS_WITH_VULNERABILITY,
			nativeQuery = true)
	List<Artifact> findArtifactsWithVulnerability(String orgUuidAsString, String location, String findingId);
	
	@Query(
			value = VariableQueries.FIND_ARTIFACTS_WITH_VIOLATION,
			nativeQuery = true)
	List<Artifact> findArtifactsWithViolation(String orgUuidAsString, String location, String findingId);
	
	@Query(
			value = VariableQueries.FIND_ARTIFACTS_WITH_WEAKNESS,
			nativeQuery = true)
	List<Artifact> findArtifactsWithWeakness(String orgUuidAsString, String location, String findingId);
	
	@Query(
			value = VariableQueries.LIST_DISTINCT_DTRACK_PROJECTS_BY_ORG,
			nativeQuery = true)
	List<String> listDistinctDtrackProjectsByOrg(String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_ARTIFACTS_BY_DTRACK_PROJECT_AND_ORG,
			nativeQuery = true)
	List<Artifact> findArtifactsByDtrackProjectAndOrg(String orgUuidAsString, String dtrackProject);
}
