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
import org.springframework.transaction.annotation.Transactional;

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

	/**
	 * Dedupe check for {@code recordApiKeyAccess}. Returns true if the given
	 * (api_key_uuid, ip_address) tuple already has at least one access row
	 * within the last hour. Uses the existing
	 * {@code (api_key_uuid, access_date DESC)} index to narrow to a tiny
	 * candidate set; the {@code ip_address} predicate then filters those few
	 * rows. Drops insert volume for high-frequency CI/CD pollers from
	 * thousands per key/day to ≤24.
	 */
	@Query(value = "SELECT EXISTS ("
			+ "SELECT 1 FROM rearm.api_key_access "
			+ "WHERE api_key_uuid = :apiKeyUuid "
			+ "  AND ip_address = :ipAddress "
			+ "  AND access_date > now() - interval '1 hour')",
			nativeQuery = true)
	boolean existsRecentAccess(UUID apiKeyUuid, String ipAddress);

	/**
	 * Daily retention sweep. Deletes audit rows older than 90 days. With
	 * {@code existsRecentAccess} dedupe in place the steady-state table
	 * size is small, but the historical tail is unbounded without this.
	 * Runs in a single transaction; on a hot table this can hold row
	 * locks briefly, so the caller schedules it during a low-traffic
	 * window.
	 */
	@Modifying
	@Transactional
	@Query(value = "DELETE FROM rearm.api_key_access "
			+ "WHERE access_date < now() - interval '90 days'",
			nativeQuery = true)
	int deleteAccessRowsOlderThan90Days();
}
