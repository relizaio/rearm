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

import io.reliza.model.SbomProfile;

/** The SBOM profiles catalog (task TEA-27); every query carries the organization. */
public interface SbomProfileRepository extends CrudRepository<SbomProfile, UUID> {

	/** The organization's own row of a name (scope ORGANIZATION, object NULL). */
	@Query(value = VariableQueries.FIND_SBOM_PROFILE_OF_ORG, nativeQuery = true)
	Optional<SbomProfile> findOrgProfile(@Param("org") UUID org, @Param("name") String name);

	/** A PERSPECTIVE or COMPONENT row of a name; {@code object} is never null here. */
	@Query(value = VariableQueries.FIND_SBOM_PROFILE_BY_SCOPE_OBJECT_NAME, nativeQuery = true)
	Optional<SbomProfile> findByScopeObjectName(@Param("org") UUID org, @Param("scope") String scope,
			@Param("object") UUID object, @Param("name") String name);

	/** The organization scope's rows, by name. */
	@Query(value = VariableQueries.LIST_SBOM_PROFILES_OF_ORG_SCOPE, nativeQuery = true)
	List<SbomProfile> listOfOrganizationScope(@Param("org") UUID org);

	/** A PERSPECTIVE or COMPONENT scope's rows, by name. */
	@Query(value = VariableQueries.LIST_SBOM_PROFILES_OF_SCOPE_OBJECT, nativeQuery = true)
	List<SbomProfile> listByScopeObject(@Param("org") UUID org, @Param("scope") String scope,
			@Param("object") UUID object);

	/** The rows of one name at the given objects of one scope: the perspective step. */
	@Query(value = VariableQueries.LIST_SBOM_PROFILES_OF_SCOPE_OBJECTS_AND_NAME, nativeQuery = true)
	List<SbomProfile> listByScopeObjectsAndName(@Param("org") UUID org, @Param("scope") String scope,
			@Param("objectsAsString") String[] objectsAsString, @Param("name") String name);

	@Query(value = VariableQueries.LIST_SBOM_PROFILES_OF_ORG, nativeQuery = true)
	List<SbomProfile> listByOrg(@Param("org") UUID org);

	/** Every row whose object is the given uuid, whatever its scope and name. */
	@Query(value = VariableQueries.LIST_SBOM_PROFILES_OF_OBJECT, nativeQuery = true)
	List<SbomProfile> listByObject(@Param("org") UUID org, @Param("object") UUID object);
}
