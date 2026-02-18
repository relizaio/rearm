/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
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

import io.reliza.model.UserGroup;
import jakarta.persistence.LockModeType;

public interface UserGroupRepository extends JpaRepository<UserGroup, UUID> {
	
	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT ug FROM UserGroup ug where ug.uuid = :uuid")
	public Optional<UserGroup> findByIdWriteLocked(UUID uuid);
	
	@Query(
			value = VariableQueries.FIND_ALL_USER_GROUPS_BY_ORGANIZATION,
			nativeQuery = true)
	List<UserGroup> findAllByOrganization(@Param("orgUuidAsString") String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_USER_GROUPS_BY_USER_AND_ORGANIZATION,
			nativeQuery = true)
	List<UserGroup> findByUserAndOrg(@Param("userUuidAsString") String userUuidAsString, @Param("orgUuidAsString") String orgUuidAsString);
}
