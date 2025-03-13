/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.ResourceGroup;

public interface ResourceGroupRepository extends CrudRepository<ResourceGroup, UUID> {
	@Override
	List<ResourceGroup> findAll();

	@Query(
			value = VariableQueries.FIND_RESOURCE_GROUP_BY_ID_ORG,
			nativeQuery = true)
	Optional<ResourceGroup> findResourceGroupByIdOrgOrganization(String uuid, String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_RESOURCE_GROUP_BY_ORG,
			nativeQuery = true)
	List<ResourceGroup> findResourceGroupsByOrg(String orgUuidAsString);
	
	
}
