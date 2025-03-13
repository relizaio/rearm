/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.Integration;

public interface IntegrationRepository extends CrudRepository<Integration, UUID> {

	@Query(
			value = VariableQueries.FIND_INTEGRATION_BY_ORG_TYPE_IDENTIFIER,
			nativeQuery = true)
	Optional<Integration> findIntegrationByOrgTypeIdentifier(String orgUuidAsString, String typeAsString, String identifier);

	@Query(
			value = VariableQueries.FIND_INTEGRATION_BY_ORG_IDENTIFIER,
			nativeQuery = true)
	Optional<Integration> findIntegrationByOrgIdentifier(String orgUuidAsString, String identifier);


	@Query(
			value = VariableQueries.LIST_INTEGRATIONS_BY_ORG,
			nativeQuery = true)
	List<Integration> listIntegrationsByOrg(String orgUuidAsString);


}
