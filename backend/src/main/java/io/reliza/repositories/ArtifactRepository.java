/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

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
			value = VariableQueries.LIST_ACTIVE_RELEASE_ARTIFACT_UUIDS,
			nativeQuery = true)
	List<String> listActiveReleaseArtifactUuids(@Param("orgUuidAsString") String orgUuidAsString);
	
	@Query(
			value = VariableQueries.LIST_ACTIVE_SCE_ARTIFACT_UUIDS_VIA_SOURCE_CODE_ENTRY,
			nativeQuery = true)
	List<String> listActiveSceArtifactUuidsViaSourceCodeEntry(@Param("orgUuidAsString") String orgUuidAsString);
	
	@Query(
			value = VariableQueries.LIST_ACTIVE_SCE_ARTIFACT_UUIDS_VIA_COMMITS,
			nativeQuery = true)
	List<String> listActiveSceArtifactUuidsViaCommits(@Param("orgUuidAsString") String orgUuidAsString);
	
	@Query(
			value = VariableQueries.EXISTS_ACTIVE_DELIVERABLE_FOR_ARTIFACT,
			nativeQuery = true)
	List<Integer> existsActiveDeliverableForArtifactRaw(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("artifactUuid") String artifactUuid);

	default boolean existsActiveDeliverableForArtifact(String orgUuidAsString, String artifactUuid) {
		return !existsActiveDeliverableForArtifactRaw(orgUuidAsString, artifactUuid).isEmpty();
	}
	
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
			value = VariableQueries.LIST_DISTINCT_DELETED_DTRACK_PROJECTS_BY_ORG,
			nativeQuery = true)
	List<String> listDistinctDeletedDtrackProjectsByOrg(String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_ARTIFACTS_BY_DTRACK_PROJECT_AND_ORG,
			nativeQuery = true)
	List<Artifact> findArtifactsByDtrackProjectAndOrg(String orgUuidAsString, String dtrackProject);

	@Query(
			value = VariableQueries.LIST_ARTIFACT_UUIDS_BY_COMPONENTS,
			nativeQuery = true)
	List<String> listArtifactUuidsByComponents(@Param("componentUuids") Collection<String> componentUuids);

	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.artifacts SET metrics = CAST(:metrics AS jsonb), metrics_revision = metrics_revision + 1 WHERE uuid = :uuid", nativeQuery = true)
	void updateMetrics(@Param("uuid") UUID uuid, @Param("metrics") String metrics);

	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.artifacts SET metrics_revision = metrics_revision + 1 WHERE uuid = :uuid", nativeQuery = true)
	void bumpMetricsRevision(@Param("uuid") UUID uuid);
}
