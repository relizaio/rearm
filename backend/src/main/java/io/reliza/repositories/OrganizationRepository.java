/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.Organization;

public interface OrganizationRepository extends CrudRepository<Organization, UUID> {
	@Override
	List<Organization> findAll();
	
	@Query(
			value = VariableQueries.GET_NUMERIC_ANALYTICS_FOR_ORG,
			nativeQuery = true)
	Map<String,BigInteger> getNumericAnalytics(String orgUuidAsString);
	
}
