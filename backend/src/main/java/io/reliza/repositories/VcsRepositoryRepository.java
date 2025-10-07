/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.VcsRepository;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface VcsRepositoryRepository extends CrudRepository<VcsRepository, UUID> {
	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "30000"))
	@Query(value = "SELECT v FROM VcsRepository v where uuid = :uuid")
	public Optional<VcsRepository> findByIdWriteLocked(UUID uuid);
	
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
