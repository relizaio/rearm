/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
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

import io.reliza.model.ApiKeyGroup;
import jakarta.persistence.LockModeType;

/** API key groups (TPF-91). */
public interface ApiKeyGroupRepository extends JpaRepository<ApiKeyGroup, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT akg FROM ApiKeyGroup akg where akg.uuid = :uuid")
	public Optional<ApiKeyGroup> findByIdWriteLocked(UUID uuid);

	/** Every group of the organization, active or not. */
	@Query(
			value = VariableQueries.FIND_ALL_API_KEY_GROUPS_BY_ORGANIZATION,
			nativeQuery = true)
	List<ApiKeyGroup> findAllByOrganization(@Param("orgUuidAsString") String orgUuidAsString);

	/** The ACTIVE groups of the organization holding the key. */
	@Query(
			value = VariableQueries.FIND_API_KEY_GROUPS_BY_KEY_AND_ORGANIZATION,
			nativeQuery = true)
	List<ApiKeyGroup> findByApiKeyAndOrg(@Param("keyUuidAsString") String keyUuidAsString, @Param("orgUuidAsString") String orgUuidAsString);
}
