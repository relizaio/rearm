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
			value = VariableQueries.FIND_ARTIFACTS_BY_STORED_DIGEST,
			nativeQuery = true)
	List<Artifact> findArtifactsByStoredDigest(String orgUuidAsString, String digest);
	
	@Query(
			value = VariableQueries.FIND_ARTIFACT_UUIDS_BY_DTRACK_PROJECTS,
			nativeQuery = true)
	List<UUID> findArtifactUuidsByDtrackProjects(List<String> dtrackProjectIds);

	/**
	 * Phase-out candidates: up to {@code lim} distinct legacy per-artifact DTrack
	 * projects, globally across orgs, as {@code [projectId, orgUuid]} pairs. Only
	 * {@code metrics.dependencyTrackProject} (legacy submission writes this; the
	 * synthetic flow never does) and excludes any project that is a synthetic
	 * bucket project, so we never delete our own buckets. Cleared rows drop out
	 * naturally, so the batch self-advances tick over tick.
	 */
	@Query(value = """
			SELECT DISTINCT a.metrics->>'dependencyTrackProject' AS project_id,
			                a.record_data->>'org'               AS org_uuid
			FROM rearm.artifacts a
			WHERE a.metrics->>'dependencyTrackProject' IS NOT NULL
			  AND a.metrics->>'dependencyTrackProject' <> ''
			  AND NOT EXISTS (
			        SELECT 1 FROM rearm.synthetic_dtrack_bucket b
			        WHERE b.dtrack_project_uuid::text = a.metrics->>'dependencyTrackProject')
			LIMIT :lim
			""", nativeQuery = true)
	List<Object[]> listLegacyDtrackProjectsForPhaseOut(@Param("lim") int lim);

	/**
	 * Remove the legacy DTrack reference ({@code dependencyTrackProject} +
	 * {@code dependencyTrackFullUri}) from every artifact pointing at one phased-out
	 * project. Leaves findings / lastScanned / firstScanned (now synthetic) intact.
	 */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE rearm.artifacts
			SET metrics = (metrics - 'dependencyTrackProject' - 'dependencyTrackFullUri')
			WHERE record_data->>'org' = :orgUuidAsString
			  AND metrics->>'dependencyTrackProject' = :projectId
			""", nativeQuery = true)
	int clearDtrackProjectRef(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("projectId") String projectId);

	@Query(
			value = VariableQueries.FIND_ARTIFACTS_WITH_VULNERABILITY,
			nativeQuery = true)
	List<UUID> findArtifactsWithVulnerability(String orgUuidAsString, String location, String findingId);

	@Query(
			value = VariableQueries.FIND_ARTIFACTS_WITH_VIOLATION,
			nativeQuery = true)
	List<UUID> findArtifactsWithViolation(String orgUuidAsString, String location, String findingId);

	@Query(
			value = VariableQueries.FIND_ARTIFACTS_WITH_WEAKNESS,
			nativeQuery = true)
	List<UUID> findArtifactsWithWeakness(String orgUuidAsString, String location, String findingId);
	
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
