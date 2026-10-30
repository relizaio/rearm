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
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.Branch;
import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;

public interface BranchRepository extends CrudRepository<Branch, UUID> {

	// Bounded wait, as VcsRepositoryRepository does: this lock is taken on the auto-integrate
	// path, whose dispatcher is a bounded executor, and Postgres lock_timeout defaults to
	// "wait forever" -- so an unbounded waiter would park an executor slot indefinitely rather
	// than failing and being retried by the scheduler drain.
	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@QueryHints(@QueryHint(name = "jakarta.persistence.lock.timeout", value = "30000"))
	@Query(value = "SELECT b FROM Branch b where uuid = :uuid")
	Optional<Branch> findByIdWriteLocked(UUID uuid);

	/**
	 * Record the last declarative apply on a branch (BranchService.stampDeclarativeProvenance) in place: no
	 * revision, no last_updated_date, no who-updated keys and no audit row -- the apply's own bookkeeping (which spec
	 * hash was applied when, from where); the content change an apply makes is audited by its own save, whose audit
	 * copy shows the previous provenance (TPF-71 / TPF-108). One JSON object serialised by Utils.OM, the mapper a
	 * whole save uses. Touches no other key.
	 *
	 * @return the rows updated: 1, or 0 when no row has this uuid
	 */
	@Modifying(flushAutomatically = true)
	@Transactional
	@Query(value = "UPDATE rearm.branches SET record_data = jsonb_set(record_data, '{declarative}', cast(:provenance as jsonb)) "
			+ "WHERE uuid = :uuid", nativeQuery = true)
	int stampDeclarativeProvenance(@Param("uuid") UUID uuid, @Param("provenance") String provenance);

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
	
	/**
	 * Find feature sets that have dependency patterns configured and auto-integrate enabled.
	 * Used for pattern-based auto-integrate triggering.
	 * @param orgUuidAsString
	 * @return
	 */
	@Query(
			value = VariableQueries.FIND_FEATURE_SETS_WITH_DEPENDENCY_PATTERNS,
			nativeQuery = true)
	List<Branch> findFeatureSetsWithDependencyPatterns(String orgUuidAsString);
}
