/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.ApiKeyAccess;

public interface ApiKeyAccessRepository extends CrudRepository<ApiKeyAccess, UUID> {
	
	@Query(
			value = VariableQueries.FIND_API_KEY_ACCESS_BY_ORGANIZATION,
			nativeQuery = true)
	List<ApiKeyAccess> listKeyAccessByOrg(UUID orgUuid);
	
	@Query(
			value = VariableQueries.FIND_API_KEY_ACCESS_BY_ORGANIZATION_KEY_ID,
			nativeQuery = true)
	Optional<ApiKeyAccess> getKeyAccessByOrgKeyId(UUID orgUuid, UUID keyUuid);

	@Query(
			value = VariableQueries.COUNT_KEY_ACCESS_BY_KEY_ID,
			nativeQuery = true)
	int countKeyAccessByKeyId(UUID keyUuid);
}
