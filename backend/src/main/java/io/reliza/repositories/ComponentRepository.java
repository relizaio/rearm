/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.CrudRepository;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import io.reliza.model.Component;

public interface ComponentRepository extends CrudRepository<Component, UUID> {
	
	@Override
	List<Component> findAll();

	/**
	 * Row-locked read, for the read-modify-write paths on the component's JSONB lists.
	 *
	 * <p>Added for locks: two concurrent releases of the same lock would otherwise both pass the
	 * active check and each write a LOCK_RELEASE attestation.
	 *
	 * <p>Bounded wait, like BranchRepository's: Postgres waits forever by default, so one stuck
	 * holder would block every component-scope lock mutation indefinitely rather than failing and
	 * being retried.
	 */
	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "30000"))
	@Query(value = "SELECT c FROM Component c WHERE c.uuid = :uuid")
	Optional<Component> findByIdWriteLocked(@Param("uuid") UUID uuid);
	
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
	
	@Query(
			value = VariableQueries.FIND_COMPONENT_BY_VCS_AND_PATH,
			nativeQuery = true)
	List<Component> findAllComponentsByVcsAndPath(String vcsUuidAsString, String orgUuidAsString, String repoPath);

	@Query(
			value = VariableQueries.FIND_LIVE_VCS_COMPONENTS_OF_ORG,
			nativeQuery = true)
	List<Component> findLiveVcsComponentsOfOrg(String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_COMPONENTS_BY_VCS,
			nativeQuery = true)
	List<Component> findComponentsByVcs(String vcsUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_COMPONENTS_BY_PERSPECTIVE,
			nativeQuery = true)
	List<Component> findComponentsByPerspective(String perspectiveUuidAsString);
}
