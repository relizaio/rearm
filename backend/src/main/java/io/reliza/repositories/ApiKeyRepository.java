/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.ApiKey;

public interface ApiKeyRepository extends CrudRepository<ApiKey, UUID> {
	
	@Query(
			value = VariableQueries.FIND_API_KEY_BY_UUID,
			nativeQuery = true)
	Optional<ApiKey> findByUUID(UUID uuid);

	@Query(
			value = VariableQueries.FIND_API_KEY_BY_ID_AND_TYPE,
			nativeQuery = true)
	List<ApiKey> findApiKeyByUuidAndType(UUID uuid, String type);

	@Query(
			value = VariableQueries.FIND_USER_API_KEY_BY_USER_ID_AND_ORG,
			nativeQuery = true)
	List<ApiKey> findUserApiKeyByUserUuidAndOrgUuid(UUID userUuid, UUID orgUuid);

	@Query(
			value = VariableQueries.FIND_REGISTRY_API_KEY,
			nativeQuery = true)
	List<ApiKey> findRegistryApiKey(UUID objUuid, UUID orgUuid, String type);
	
	@Query(
			value = VariableQueries.FIND_API_KEYS_BY_ORGANIZATION,
			nativeQuery = true)
	List<ApiKey> listKeysByOrg(UUID orgUuid);
}
