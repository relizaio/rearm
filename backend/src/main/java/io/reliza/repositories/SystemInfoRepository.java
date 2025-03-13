/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.SystemInfo;

public interface SystemInfoRepository extends CrudRepository<SystemInfo, Integer> {
    @Query(nativeQuery = true, value = VariableQueries.FIND_SYSTEM_INFO)
    SystemInfo findSystemInfo();

    @Modifying
    @Query(nativeQuery = true, value = VariableQueries.MAKE_USER_GLOBAL_ADMIN)
    void makeUserGlobalAdmin(UUID userId);
}
