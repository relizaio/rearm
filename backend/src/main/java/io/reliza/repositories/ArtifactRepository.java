/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
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
			value = VariableQueries.FIND_ARTIFACTS_BY_STORED_DIGEST,
			nativeQuery = true)
	List<Artifact> findArtifactsByStoredDigest(String orgUuidAsString, String digest);
	
	@Query(
			value = VariableQueries.FIND_ARTIFACTS_BY_DTRACK_PROJECTS,
			nativeQuery = true)
	List<Artifact> findArtifactsByDtrackProjects(List<String> dtrackProjectIds);
}
