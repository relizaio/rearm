/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.User;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

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
			value = VariableQueries.FIND_USERS_BY_ORGANIZATION_INCLUDING_INACTIVE,
			nativeQuery = true)
	List<User> findUsersByOrgIncludingInactive(String orgUuidAsString);

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

	/**
	 * The instance-wide license seat lock (TPF-167, TPF-165 D4): a transaction-level advisory lock,
	 * released when the caller's transaction ends, so the seat check and the write it guards commit
	 * together. Two-key form, the second key a constant.
	 */
	@Query(
		value = """
			SELECT 1 FROM (
			  SELECT pg_advisory_xact_lock(:lockNamespace, hashtext('license-seats'))
			) AS locked
			""",
		nativeQuery = true)
	Integer lockLicenseSeats(@Param("lockNamespace") int lockNamespace);

	/**
	 * The license seat census rows of every ACTIVE user (TPF-167 D1): uuid, created_date and record_data as
	 * text, a projection, so the census reads the database and never an entity the caller's persistence
	 * context already holds (which would hide another transaction's commit). COMMIT flush mode: inside a
	 * granting transaction the read neither flushes nor dirty-checks the context (T-4); the seat check
	 * flushes once itself before its after census.
	 */
	@QueryHints(@QueryHint(name = "org.hibernate.flushMode", value = "COMMIT"))
	@Query(
			value = VariableQueries.CENSUS_ACTIVE_USERS,
			nativeQuery = true)
	List<Object[]> censusActiveUsers();

	/** Every active global administrator, the census projection (TPF-167: each holds a write seat). */
	@QueryHints(@QueryHint(name = "org.hibernate.flushMode", value = "COMMIT"))
	@Query(
			value = VariableQueries.CENSUS_ACTIVE_GLOBAL_ADMINS,
			nativeQuery = true)
	List<Object[]> censusActiveGlobalAdmins();

	/**
	 * Stamps the user's last sign-in in place (TPF-167, TPF-165 D11): one jsonb key, an ISO-8601 UTC
	 * instant. Bookkeeping: writes no revision and no audit row.
	 */
	@Modifying
	@Query(
			value = VariableQueries.STAMP_USER_LAST_LOGIN,
			nativeQuery = true)
	int stampLastLogin(@Param("uuid") UUID uuid, @Param("at") String at);
}
