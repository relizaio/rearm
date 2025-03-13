/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.Component;

public interface ComponentRepository extends CrudRepository<Component, UUID> {
	
	@Override
	List<Component> findAll();
	
	@Query(
			value = VariableQueries.FIND_COMPONENTS_BY_ORG_BY_TYPE,
			nativeQuery = true)
	List<Component> findComponentsByOrganization(String orgUuidAsString, String componentTypeAsString);
	
	@Query(
			value = VariableQueries.FIND_COMPONENT_BY_ORG_NAME_TYPE,
			nativeQuery = true)
	Optional<Component> findComponentByOrgNameType(String orgUuidAsString, String componentName, String componentType);
	
	@Query(
			value = VariableQueries.FIND_COMPONENT_BY_PARENT,
			nativeQuery = true)
	Optional<Component> findComponentByParent(String parentUuidAsString, String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_COMPONENTS_BY_ORG,
			nativeQuery = true)
	List<Component> findComponentsByOrganization(String orgUuidAsString);

	/**
	 * This returns components by branch, including parent components
	 * @param branchUuidAsString
	 * @return
	 */
	@Query(
			value = VariableQueries.FIND_COMPONENTS_BY_BRANCH,
			nativeQuery = true)
	List<Component> findComponentsByBranch(String branchUuidAsString);
	
	/**
	 * This returns components by SCE, including parent components
	 * @param sourceCodeEntryUuidAsString
	 * @return
	 */
	@Query(
			value = VariableQueries.FIND_COMPONENTS_BY_SOURCE_CODE_ENTRY,
			nativeQuery = true)
	List<Component> findComponentsBySce(String sourceCodeEntryUuidAsString);

	@Query(
			value = VariableQueries.FIND_COMPONENTS_BY_APPROVAL_POLICY,
			nativeQuery = true)
	List<Component> findComponentsByApprovalPolicy(String approvalPolicyUuid);
}
