/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
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

import io.reliza.model.PermissionTemplate;
import jakarta.persistence.LockModeType;

/** Permission templates (TPF-87, TPF-112): owner-keyed reads only. */
public interface PermissionTemplateRepository extends JpaRepository<PermissionTemplate, UUID> {

	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT pt FROM PermissionTemplate pt where pt.uuid = :uuid")
	public Optional<PermissionTemplate> findByIdWriteLocked(UUID uuid);

	/** The user's templates in the organization, by name. */
	@Query(
			value = VariableQueries.FIND_PERMISSION_TEMPLATES_BY_USER_AND_ORGANIZATION,
			nativeQuery = true)
	List<PermissionTemplate> findByUserAndOrg(@Param("userUuidAsString") String userUuidAsString,
			@Param("orgUuidAsString") String orgUuidAsString);

	/** The user's templates in every organization, by name. */
	@Query(
			value = VariableQueries.FIND_PERMISSION_TEMPLATES_BY_USER,
			nativeQuery = true)
	List<PermissionTemplate> findByUser(@Param("userUuidAsString") String userUuidAsString);
}
