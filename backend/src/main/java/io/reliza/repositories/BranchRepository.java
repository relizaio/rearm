/**
* Copyright Reliza Incorporated. 2019 - 2025. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.Branch;

public interface BranchRepository extends CrudRepository<Branch, UUID> {
	
	@Query(
			value = VariableQueries.FIND_ALL_BRANCHES_OF_COMPONENT,
			nativeQuery = true)
	List<Branch> findBranchesOfComponent(String compUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_BASE_BRANCH_OF_COMPONENT,
			nativeQuery = true)
	Optional<Branch> findBaseBranch (String compUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_BRANCHES_OF_COMPONENT_BY_STATUS,
			nativeQuery = true)
	List<Branch> findBranchesOfComponentByStatus(String compUuidAsString, String status);

	@Query(
			value = VariableQueries.FIND_BRANCHES_OF_ORG,
			nativeQuery = true)
	List<Branch> findBranchesOfOrg(String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_BRANCHES_OF_ORG_BY_PERSPECTIVE,
			nativeQuery = true)
	List<Branch> findBranchesOfOrgByPerspective(String orgUuidAsString, String perspectiveUuidAsString);
	
	
	/**
	 * This one is to locate products for auto-integration
	 * @param orgUuidAsString
	 * @return
	 */
	@Query(
			value = VariableQueries.FIND_BRANCHES_BY_CHILD_COMPONENT_AND_BRANCH,
			nativeQuery = true)
	List<Branch> findFeatureSetsByChildComponentBranch(String orgUuidAsString, String compUuidAsString, String branchUuidAsString);
	
	/**
	 * This one is to locate feature sets of a project
	 * @param orgUuidAsString
	 * @param projUuidAsString
	 * @return
	 */
	@Query(
			value = VariableQueries.FIND_FEATURE_SETS_BY_CHILD_COMPONENT,
			nativeQuery = true)
	List<Branch> findFeatureSetsByChildComponent(String orgUuidAsString, String compUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_BRANCHES_BY_VCS,
			nativeQuery = true)
	List<Branch> findBranchesByVcs(String vcsUuidAsString);
}
