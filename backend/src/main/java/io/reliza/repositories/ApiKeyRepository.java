/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.hibernate.Timeouts;

import io.reliza.model.ApiKey;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface ApiKeyRepository extends CrudRepository<ApiKey, UUID> {

	// The three lock reads below are MANDATORY: the lock belongs to the caller's transaction and lasts until it
	// commits, and a caller without one fails instead of getting a lock released on return.

	/** The key row in any status, locked until the caller's transaction ends. Callers apply ApiKeyService.isLive. */
	@Transactional(propagation = Propagation.MANDATORY)
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "30000"))
	@Query("SELECT k FROM ApiKey k WHERE k.uuid = :uuid")
	Optional<ApiKey> findByIdWriteLocked(@Param("uuid") UUID uuid);

	/**
	 * As findByIdWriteLocked, but never waits: a row locked by another transaction reads as empty (SKIP LOCKED).
	 * Not NOWAIT, whose refusal is an error that marks the reading transaction for rollback.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "" + Timeouts.SKIP_LOCKED_MILLI))
	@Query("SELECT k FROM ApiKey k WHERE k.uuid = :uuid")
	Optional<ApiKey> findByIdWriteLockedSkipLocked(@Param("uuid") UUID uuid);

	/**
	 * Whether the key row is live (FIND_API_KEY_BY_UUID's rule), read from the table under the row lock; empty
	 * when there is no row. COMMIT flush mode: nothing the caller holds in memory is written before the read.
	 * Its wait is PostgreSQL's lock_timeout; the writers that reach it by uuid already hold the lock.
	 */
	@Transactional(propagation = Propagation.MANDATORY)
	@QueryHints(@QueryHint(name = "org.hibernate.flushMode", value = "COMMIT"))
	@Query(value = VariableQueries.LOCK_API_KEY_LIVENESS, nativeQuery = true)
	Optional<Boolean> lockAndReadLiveness(@Param("uuid") UUID uuid);

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

	/** The object's active key that has no key order -- the one a {@code TYPE__objectUuid} key id names. */
	@Query(
			value = VariableQueries.FIND_API_KEY_BY_ID_AND_TYPE_WITHOUT_ORDER,
			nativeQuery = true)
	Optional<ApiKey> findApiKeyByUuidAndTypeWithoutOrder(UUID uuid, String type);

	@Query(
			value = VariableQueries.FIND_USER_API_KEY_BY_USER_ID_AND_ORG,
			nativeQuery = true)
	List<ApiKey> findUserApiKeyByUserUuidAndOrgUuid(UUID userUuid, UUID orgUuid);

	@Query(
			value = VariableQueries.FIND_HELD_API_KEYS_BY_USER_AND_ORG,
			nativeQuery = true)
	List<ApiKey> findHeldApiKeysByUserUuidAndOrgUuid(UUID userUuid, UUID orgUuid);

	@Query(
			value = VariableQueries.FIND_USER_API_KEYS_BY_USER,
			nativeQuery = true)
	List<ApiKey> findUserApiKeysByUserUuid(UUID userUuid);

	@Query(
			value = VariableQueries.FIND_REGISTRY_API_KEY,
			nativeQuery = true)
	List<ApiKey> findRegistryApiKey(UUID objUuid, UUID orgUuid, String type);
	
	@Query(
			value = VariableQueries.FIND_API_KEYS_BY_ORGANIZATION,
			nativeQuery = true)
	List<ApiKey> listKeysByOrg(UUID orgUuid);

	/**
	 * Guarded bump of {@code last_access_date}, called from the scheduled
	 * flush of the in-memory touch accumulator — NOT from the request path.
	 * A per-request UPDATE of this row serialized every concurrent request
	 * using the same key on one row lock; under production load the queue
	 * outlived the global 120s query timeout ("canceling statement due to
	 * user request ... while rechecking updated tuple ... api_keys").
	 *
	 * The WHERE guard keeps the timestamp monotonic across replicas (each
	 * flushes its own accumulator, so out-of-order writes are possible) and
	 * lets a concurrent waiter's EvalPlanQual recheck skip the write
	 * entirely instead of re-updating the row.
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.api_keys SET last_access_date = :ts WHERE uuid = :uuid "
			+ "AND (last_access_date IS NULL OR last_access_date < :ts)", nativeQuery = true)
	void touchLastAccessDateIfNewer(@Param("uuid") UUID uuid, @Param("ts") ZonedDateTime ts);

	/**
	 * Refresh a FEDERATED identity's last-seen triple (ApiKeyService.ensureFederatedIdentity, the per-exchange
	 * path) in place: no revision, no last_updated_date, no who-updated keys, no version bump and no audit row --
	 * the triple is what the latest CI run presented and the next run supersedes it; the audit copy a whole save
	 * wrote carried the previous triple and nothing else (TPF-71 / TPF-107). The three keys are replaced as one
	 * object merge and nulls are stripped, so a null value removes its key exactly as Jackson's NON_NULL omits
	 * it on a whole save, and the stored shape is the same either way. Every other key of federation, and every
	 * other key of record_data, is untouched.
	 *
	 * @return the rows updated: 1, or 0 when no row has this uuid or its federation is not an object
	 */
	@Modifying(flushAutomatically = true)
	@Transactional
	@Query(value = "UPDATE rearm.api_keys SET record_data = jsonb_set(record_data, '{federation}', "
			+ "jsonb_strip_nulls((record_data->'federation') || jsonb_build_object("
			+ "'lastRef', cast(:ref as text), 'lastRunId', cast(:runId as text), 'lastActor', cast(:actor as text)))) "
			+ "WHERE uuid = :uuid AND jsonb_typeof(record_data->'federation') = 'object'", nativeQuery = true)
	int stampFederatedLastSeen(@Param("uuid") UUID uuid, @Param("ref") String ref, @Param("runId") String runId,
			@Param("actor") String actor);
}
