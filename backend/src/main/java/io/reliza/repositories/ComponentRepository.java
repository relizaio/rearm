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
	 * The component's kind as stored (task RD4-11): empty for a missing row, a null element for a
	 * row that never recorded one, which reads as GENERIC.
	 */
	@Query(value = "SELECT record_data->>'kind' FROM rearm.components WHERE uuid = :uuid", nativeQuery = true)
	List<String> findKindOf(@Param("uuid") UUID uuid);

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

	/** TPF-100: the list projection rows (uuid, name, type, status, org, vcs, kind), every column text. */
	@Query(value = VariableQueries.FIND_COMPONENT_REFS_BY_ORG, nativeQuery = true)
	List<Object[]> findComponentRefsByOrganization(String orgUuidAsString);

	@Query(value = VariableQueries.FIND_COMPONENT_REFS_BY_ORG_BY_TYPE, nativeQuery = true)
	List<Object[]> findComponentRefsByOrganization(String orgUuidAsString, String componentTypeAsString);

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

	@Query(
			value = VariableQueries.FIND_COMPONENTS_BY_PERSPECTIVE_WITH_ARCHIVED,
			nativeQuery = true)
	List<Component> findComponentsByPerspectiveWithArchived(String perspectiveUuidAsString);

	/** The component with this TEA-facing id (task TEA-2); the TEA request path reads through it. */
	@Query(value = VariableQueries.FIND_COMPONENT_BY_TEA_UUID, nativeQuery = true)
	Optional<Component> findByTeaUuid(@Param("teaUuidAsString") String teaUuidAsString);

	/**
	 * Take the transaction lock that serializes the TEA id mint of one component; released when
	 * the transaction ends. Two-key form, {@code lockNamespace} = {@code AdvisoryLockKey.TEA_ID_MINT}.
	 */
	@Query(
		value = """
			SELECT 1 FROM (
			  SELECT pg_advisory_xact_lock(:lockNamespace, hashtext(:componentUuidAsString))
			) AS locked
			""",
		nativeQuery = true)
	Integer lockTeaIdMint(@Param("lockNamespace") int lockNamespace, @Param("componentUuidAsString") String componentUuidAsString);

	/**
	 * The component's TEA id mint (TEA-5 round 4, decision 24): one conditional statement that sets
	 * the one JSON key where it is null and touches no other column (no revision, no last-updated
	 * date, no audit row). Answers the rows updated, 1 or 0.
	 */
	@Modifying
	@Query(value = "UPDATE rearm.components SET record_data = jsonb_set(record_data, '{teaUuid}', "
			+ "to_jsonb(CAST(:teaUuid AS text)), true) WHERE uuid = :uuid AND record_data->>'teaUuid' IS NULL",
			nativeQuery = true)
	int mintTeaUuid(@Param("uuid") UUID uuid, @Param("teaUuid") String teaUuid);

	/**
	 * Record the last declarative apply on a component (ComponentService.stampDeclarativeProvenance) in place: no
	 * revision, no last_updated_date, no who-updated keys and no audit row -- the apply's own bookkeeping (which spec
	 * hash was applied when, from where); the content change an apply makes is audited by its own save, whose audit
	 * copy shows the previous provenance (TPF-71 / TPF-108). One JSON object serialised by Utils.OM, the mapper a
	 * whole save uses. Touches no other key: teaUuid and the TEA wiring keys stay as the row holds them.
	 *
	 * @return the rows updated: 1, or 0 when no row has this uuid
	 */
	@Modifying(flushAutomatically = true)
	@Transactional
	@Query(value = "UPDATE rearm.components SET record_data = jsonb_set(record_data, '{declarative}', cast(:provenance as jsonb)) "
			+ "WHERE uuid = :uuid", nativeQuery = true)
	int stampDeclarativeProvenance(@Param("uuid") UUID uuid, @Param("provenance") String provenance);

	/**
	 * The row's TEA id, or empty, with the row locked FOR UPDATE until the caller's transaction
	 * ends: the read every whole-record save of the component makes before it writes, so that it
	 * writes the stored id back and never erases or introduces one (TEA-5 round 4, T-10 point 4).
	 */
	@Query(value = "SELECT record_data->>'teaUuid' FROM rearm.components WHERE uuid = :uuid FOR UPDATE",
			nativeQuery = true)
	Optional<String> currentTeaUuidForUpdate(@Param("uuid") UUID uuid);

	/**
	 * The row's whole record as JSON text, or empty, with the row locked FOR UPDATE until the
	 * caller's transaction ends (task TEA-7, round 5): the read a wiring save checks against and
	 * the read every whole-record save carries the TEA id and the wiring keys back from, so the
	 * check and the write see one row.
	 */
	@Query(value = "SELECT CAST(record_data AS text) FROM rearm.components WHERE uuid = :uuid FOR UPDATE",
			nativeQuery = true)
	Optional<String> lockRowForUpdate(@Param("uuid") UUID uuid);
}
