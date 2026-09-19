/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.SystemInfo;

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
}
