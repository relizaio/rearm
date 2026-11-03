/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.SystemInfo;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.transaction.annotation.Transactional;
import jakarta.persistence.LockModeType;

public interface SystemInfoRepository extends CrudRepository<SystemInfo, Integer> {
    @Query(nativeQuery = true, value = VariableQueries.FIND_SYSTEM_INFO)
    SystemInfo findSystemInfo();

    @Modifying
    @Query(nativeQuery = true, value = VariableQueries.MAKE_USER_GLOBAL_ADMIN)
    void makeUserGlobalAdmin(UUID userId);

    /**
     * Sets the API-token pepper only if there is not one yet; returns rows written.
     *
     * <p>Flushes first because the caller may have just created the system_info row through JPA
     * and this statement would otherwise update nothing, and clears after because the session's
     * copy of that row is stale the moment this writes to it.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(nativeQuery = true, value = VariableQueries.SET_API_TOKEN_PEPPER_IF_ABSENT)
    int setApiTokenPepperIfAbsent(@Param("pepper") String pepper);

	/**
	 * Stamp one org's fan-out drained-at watermark in place (TPF-114); returns rows written (0
	 * when the system_info row does not exist yet; the service creates it and retries once).
	 * Flushes first and clears after, for the reason setApiTokenPepperIfAbsent gives. Writes no
	 * revision and no audit row (scheduler bookkeeping).
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(nativeQuery = true, value = VariableQueries.STAMP_FANOUT_DRAINED_AT)
	int stampFanOutDrainedAt(@Param("org") String org, @Param("at") String at);

	/**
	 * Stamp one org's fan-out walk state in place (TPF-137): the {cutoff, cursor} object under the
	 * org's key of fanOutWalk; returns rows written (0 when the system_info row does not exist yet;
	 * the service creates it and retries once). Writes no revision and no audit row (scheduler
	 * bookkeeping).
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(nativeQuery = true, value = VariableQueries.STAMP_FANOUT_WALK)
	int stampFanOutWalk(@Param("org") String org, @Param("cutoff") String cutoff, @Param("cursor") String cursor);

	/**
	 * Remove one org's fan-out walk state in place (TPF-137); {@code path} is the text array
	 * literal {fanOutWalk,<org>}. A no-op when absent. Writes no revision and no audit row.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(nativeQuery = true, value = VariableQueries.CLEAR_FANOUT_WALK)
	int clearFanOutWalk(@Param("path") String path);

	/**
	 * Assign one TEA custom domain to an organization in place (TPF-142): sets or replaces the host's
	 * key of teaHostAssignments; returns rows written (0 when the system_info row does not exist yet;
	 * the service creates it and retries once). Flushes first and clears after, for the reason
	 * setApiTokenPepperIfAbsent gives. Writes no revision and no audit row (system_info has neither);
	 * the service logs the assignment.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(nativeQuery = true, value = VariableQueries.ASSIGN_TEA_HOST)
	int assignTeaHost(@Param("host") String host, @Param("org") String org);

	/**
	 * Remove one TEA custom domain from teaHostAssignments in place (TPF-142); the host is bound as a
	 * parameter inside the array path, never spliced into a text-array literal. A no-op when absent.
	 */
	@Modifying(flushAutomatically = true, clearAutomatically = true)
	@Transactional
	@Query(nativeQuery = true, value = VariableQueries.UNASSIGN_TEA_HOST)
	int unassignTeaHost(@Param("host") String host);

	/**
	 * The committed teaHostAssignments map as JSON text, or empty when the key is absent, JSON null or
	 * the row does not exist, with the row locked FOR UPDATE until the caller's transaction ends
	 * (TPF-142): the lock of assign, unassign and setTeaServing, and the read every whole-record save
	 * makes before it writes (SystemInfoService.saveSystemInfo). Native, so it answers the committed
	 * row and never a copy the persistence context holds. Transactional (read-write) so that a caller
	 * without a transaction runs it in a short one of its own rather than in a read-only one, where
	 * FOR UPDATE is an error.
	 */
	@Transactional
	@Query(nativeQuery = true, value = VariableQueries.CURRENT_TEA_HOST_ASSIGNMENTS_FOR_UPDATE)
	Optional<String> currentTeaHostAssignmentsForUpdate();

	/**
	 * Row-locked read for the write-once flags. Two pods claiming a one-time sweep at the same
	 * moment must serialise here, or both would run it.
	 */
	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT s FROM SystemInfo s")
	SystemInfo findSystemInfoWriteLocked();
}
