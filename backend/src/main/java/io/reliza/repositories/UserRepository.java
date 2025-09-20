/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.User;
import jakarta.persistence.LockModeType;

public interface UserRepository extends CrudRepository<User, UUID> {
	
	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT u FROM User u where u.uuid = :uuid")
	public Optional<User> findByIdWriteLocked(UUID uuid);
	
	@Query(
			value = VariableQueries.FIND_USERS_BY_ORGANIZATION,
			nativeQuery = true)
	List<User> findUsersByOrg(String orgUuidAsString);

	@Query(
			value = VariableQueries.FIND_USER_BY_ID_WITH_ORGANIZATION,
			nativeQuery = true)
	Optional<User> findUserByIdWithOrganization(UUID userUuid, String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_USER_BY_EMAIL,
			nativeQuery = true)
	Optional<User> findUserByEmail(String email);
	
	@Query(
			value = VariableQueries.FIND_ANY_USER_BY_EMAIL,
			nativeQuery = true)
	List<User> findAnyUserByEmail(String email);
	
	@Query(
			value = VariableQueries.FIND_USER_BY_GITHUB_ID,
			nativeQuery = true)
	Optional<User> findUserByGithubId(String githubId);
	
	@Query(
			value = VariableQueries.FIND_USER_BY_OAUTH_ID_AND_TYPE,
			nativeQuery = true)
	Optional<User> findUserByOauthIdAndType(String oauthType, String oauthId);

	@Query(
			value = VariableQueries.FIND_ALL_USERS_CREATED_AFTER_DATE,
			nativeQuery = true)
	List<User> findUsersCreatedAfterDate(String cutOffDate);

	@Query(
			value = VariableQueries.FIND_USERS_BY_PERMISSION_OBJECT,
			nativeQuery = true)
	List<User> findUsersByPermissionObject(String objectId);
}
