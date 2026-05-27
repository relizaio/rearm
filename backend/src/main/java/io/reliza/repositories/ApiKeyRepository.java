/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.ApiKey;

public interface ApiKeyRepository extends CrudRepository<ApiKey, UUID> {
	
	@Query(
			value = VariableQueries.FIND_API_KEY_BY_UUID,
			nativeQuery = true)
	Optional<ApiKey> findByUUID(UUID uuid);

	@Query(
			value = VariableQueries.FIND_API_KEY_BY_ID_AND_TYPE,
			nativeQuery = true)
	List<ApiKey> findApiKeyByUuidAndType(UUID uuid, String type, UUID org);

	/**
	 * Variant of {@link #findApiKeyByUuidAndType} that also returns
	 * revoked rows (api_key IS NULL). Used exclusively from
	 * {@code ApiKeyService.setObjectApiKey} so the mint path can
	 * take over an existing tombstone in place rather than INSERT
	 * and collide on the (object_uuid, object_type, org, key_order)
	 * unique index. Don't use this from auth, listing, or archive
	 * code — those should not see revoked keys.
	 */
	@Query(
			value = VariableQueries.FIND_API_KEY_INCLUDING_REVOKED_BY_ID_AND_TYPE,
			nativeQuery = true)
	List<ApiKey> findApiKeyIncludingRevokedByUuidAndType(UUID uuid, String type, UUID org);

	@Query(
			value = VariableQueries.FIND_API_KEY_ORG_BY_ID_AND_TYPE,
			nativeQuery = true)
	Optional<ApiKey> findApiKeyByUuidAndTypeOnly(UUID uuid, String type, String keyOrder);

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

	/**
	 * Atomic bump of {@code last_access_date} called from the audit-write
	 * path. Direct SQL UPDATE rather than load-modify-save so the hot path
	 * doesn't pay for a row materialise + Hibernate dirty-tracking flush on
	 * every authenticated request.
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.api_keys SET last_access_date = now() WHERE uuid = :uuid", nativeQuery = true)
	void touchLastAccessDate(@Param("uuid") UUID uuid);
}
