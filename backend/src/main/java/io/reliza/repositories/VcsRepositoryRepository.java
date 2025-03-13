/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.VcsRepository;

public interface VcsRepositoryRepository extends CrudRepository<VcsRepository, UUID> {
	@Query(
			value = VariableQueries.FIND_VCS_REPOS_BY_ORG,
			nativeQuery = true)
	List<VcsRepository> findVcsReposByOrganization(String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_VCS_REPO_BY_URI,
			nativeQuery = true)
	Optional<VcsRepository> findByUri(String uri);
	
	@Query(
			value = VariableQueries.FIND_VCS_REPO_BY_ORG_AND_URI,
			nativeQuery = true)
	Optional<VcsRepository> findByOrgAndUri(String orgUuidAsString, String uri);
}
