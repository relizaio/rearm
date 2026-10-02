/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.TeaProfile;

public interface TeaProfileRepository extends CrudRepository<TeaProfile, UUID> {

	/** The organization's own row (scope ORGANIZATION, object NULL). */
	@Query(value = VariableQueries.FIND_TEA_PROFILE_OF_ORG, nativeQuery = true)
	Optional<TeaProfile> findOrgProfile(@Param("org") UUID org);

	/** A PERSPECTIVE or COMPONENT row; {@code object} is never null here. */
	@Query(value = VariableQueries.FIND_TEA_PROFILE_BY_SCOPE_OBJECT, nativeQuery = true)
	Optional<TeaProfile> findByScopeObject(@Param("org") UUID org, @Param("scope") String scope,
			@Param("object") UUID object);

	@Query(value = VariableQueries.LIST_TEA_PROFILES_OF_ORG, nativeQuery = true)
	List<TeaProfile> listByOrg(@Param("org") UUID org);

	@Query(value = VariableQueries.LIST_TEA_PROFILES_OF_SCOPE_OBJECTS, nativeQuery = true)
	List<TeaProfile> listByScopeObjects(@Param("org") UUID org, @Param("scope") String scope,
			@Param("objectsAsString") String[] objectsAsString);

	/** Every row whose object is the given uuid, whatever its scope. */
	@Query(value = VariableQueries.LIST_TEA_PROFILES_OF_OBJECT, nativeQuery = true)
	List<TeaProfile> listByObject(@Param("org") UUID org, @Param("object") UUID object);
}
