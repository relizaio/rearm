/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.Organization;
import jakarta.persistence.LockModeType;

public interface OrganizationRepository extends CrudRepository<Organization, UUID> {
	@Override
	List<Organization> findAll();

	@Query(
			value = VariableQueries.GET_NUMERIC_ANALYTICS_FOR_ORG,
			nativeQuery = true)
	Map<String,BigInteger> getNumericAnalytics(String orgUuidAsString);

	/**
	 * Organizations with NO BRANCH-GRAIN v3 backfill watermark and NO {@code metrics_audit} rows of the
	 * given entity type -- orgs whose releases were never re-scanned, so their finding-change log is
	 * VACUOUSLY complete (no transitions ever happened; the always-on live emit captures everything going
	 * forward). The daily repair sweep certifies these so new orgs flow onto the posture-diff path. Gates
	 * on the V3 watermark only; a stale legacy v1/v2 watermark in an org's settings JSONB (pre-decommission
	 * history) is ignored, so such an org is still certified for v3.
	 */
	@Query(value = "SELECT o.uuid FROM rearm.organizations o "
			+ "WHERE (o.record_data->'settings'->>'findingChangeV3BackfillCompletedAt') IS NULL "
			+ "AND NOT EXISTS (SELECT 1 FROM rearm.metrics_audit ma "
			+ "WHERE ma.entity_type = :entityType AND ma.org = o.uuid)", nativeQuery = true)
	List<UUID> findOrgsEligibleForVacuousFindingChangeV3Certification(@Param("entityType") String entityType);

	/**
	 * Certifies the org's BRANCH-GRAIN {@code finding_change_events_v3} (events-lite) backfill watermark
	 * (board task #38, v3 events-lite): stamps {@code findingChangeV3BackfillCompletedAt} +
	 * {@code findingChangeV3BackfillKeyVersion} via a targeted jsonb_set with a typeof-object guard and a
	 * monotonic key-version floor -- an older-build pod (lower
	 * {@link io.reliza.service.FindingDimKey#KEY_VERSION}) can never regress the version during a rolling
	 * deploy, and a re-key backfill re-certifies at the new version with the completion instant refreshed
	 * each clean run. Targeted jsonb_set on the settings key, NOT a whole-record save, so a concurrent user
	 * settings update can never be clobbered (system metadata, deliberately outside the revision-bump/audit
	 * path).
	 *
	 * <p>NULL-SETTINGS TRAP: a legacy org row can carry {@code "settings": null} -- there
	 * {@code record_data->'settings'} yields JSON {@code 'null'::jsonb}, NOT SQL NULL, so a plain
	 * {@code COALESCE} keeps it and jsonb {@code ||} between a non-object and an object produces an
	 * ARRAY ({@code [null, {...}]}), corrupting the row for every Jackson reader (observed live: a
	 * vacuous-certification sweep broke 3 orgs and the {@code user} query 500'd). The
	 * {@code jsonb_typeof = 'object'} guard makes ANY non-object settings (absent, JSON null, or
	 * anything unexpected) start from {@code '{}'}.
	 *
	 * @param completedAt UTC RFC-3339 instant string (stored as a JSON string, Jackson-compatible)
	 * @return rows updated: 1 = certified, 0 = org missing / would regress the key version
	 */
	@Modifying
	@Query(value = "UPDATE rearm.organizations SET record_data = jsonb_set(record_data, '{settings}', "
			+ "(CASE WHEN jsonb_typeof(record_data->'settings') = 'object' "
			+ "THEN record_data->'settings' ELSE '{}'::jsonb END) || jsonb_build_object("
			+ "'findingChangeV3BackfillCompletedAt', :completedAt, "
			+ "'findingChangeV3BackfillKeyVersion', :keyVersion)) "
			+ "WHERE uuid = :orgUuid "
			+ "AND COALESCE((record_data->'settings'->>'findingChangeV3BackfillKeyVersion')::int, 1) <= :keyVersion",
			nativeQuery = true)
	int certifyFindingChangeV3Backfill(
			@Param("orgUuid") UUID orgUuid,
			@Param("completedAt") String completedAt,
			@Param("keyVersion") int keyVersion);

	/**
	 * The live organization with this TEA-facing id (task TEA-2): an ARCHIVED organization reads as absent, exactly
	 * like an id never minted (task TEA-22). The one resolution of an organization by TEA id for the TEA surface,
	 * the token endpoint and the external key id; the Java ARCHIVED filters in TeaSurfaceService.orgOfBase and
	 * wellKnown are belt and braces, this query is the rule.
	 */
	@Query(value = VariableQueries.FIND_ORGANIZATION_BY_TEA_UUID, nativeQuery = true)
	Optional<Organization> findByTeaUuid(@Param("teaUuidAsString") String teaUuidAsString);

	/** Every organization with a TEA id: the instance /.well-known/tea document (task TEA-6). */
	@Query(value = VariableQueries.LIST_ORGANIZATIONS_WITH_TEA_UUID, nativeQuery = true)
	List<Organization> listWithTeaUuid();

	/**
	 * Take the transaction lock that serializes the TEA id mint of one organization; released when
	 * the transaction ends. Two-key form, {@code lockNamespace} = {@code AdvisoryLockKey.TEA_ID_MINT}.
	 */
	@Query(
		value = """
			SELECT 1 FROM (
			  SELECT pg_advisory_xact_lock(:lockNamespace, hashtext(:orgUuidAsString))
			) AS locked
			""",
		nativeQuery = true)
	Integer lockTeaIdMint(@Param("lockNamespace") int lockNamespace, @Param("orgUuidAsString") String orgUuidAsString);

	/**
	 * The organization's TEA id mint (TEA-5 round 4, decision 24): one conditional statement that sets
	 * the one JSON key where it is null and touches no other column (no revision, no last-updated
	 * date, no audit row). Answers the rows updated, 1 or 0.
	 */
	@Modifying
	@Query(value = "UPDATE rearm.organizations SET record_data = jsonb_set(record_data, '{teaUuid}', "
			+ "to_jsonb(CAST(:teaUuid AS text)), true) WHERE uuid = :uuid AND record_data->>'teaUuid' IS NULL",
			nativeQuery = true)
	int mintTeaUuid(@Param("uuid") UUID uuid, @Param("teaUuid") String teaUuid);

	/**
	 * The row's TEA id, or empty, with the row locked FOR UPDATE until the caller's transaction
	 * ends: the read every whole-record save of the organization makes before it writes, so that it
	 * writes the stored id back and never erases or introduces one (TEA-5 round 4, T-10 point 4).
	 */
	@Query(value = "SELECT record_data->>'teaUuid' FROM rearm.organizations WHERE uuid = :uuid FOR UPDATE",
			nativeQuery = true)
	Optional<String> currentTeaUuidForUpdate(@Param("uuid") UUID uuid);

	/**
	 * Record one task-key prefix claim (board-documents.md D9, TPF-35): one statement on the one
	 * {@code agentTaskPrefixes} key, NOT a whole-record save, so a concurrent organization save keeps
	 * its fields and a committed claim is never erased. Put-if-absent: jsonb {@code ||} takes the
	 * right operand's value for a key both objects hold, so an entry already present is never
	 * replaced. The {@code jsonb_typeof = 'object'} guard is the one {@link #certifyFindingChangeV3Backfill}
	 * documents: an absent key or {@code "agentTaskPrefixes": null} starts from {@code '{}'}, where a
	 * plain {@code COALESCE} would keep the JSON null and {@code ||} would build an array. No revision,
	 * no last-updated date, no audit row. The caller holds the org's AGENT_TASK_PREFIXES lock.
	 *
	 * @param claim the {@code TaskPrefixClaim} rendered by {@code Utils.OM}, the whole-record mapper
	 * @return rows updated: 1, or 0 when the organization does not exist
	 */
	@Modifying
	@Query(value = "UPDATE rearm.organizations SET record_data = jsonb_set(record_data, '{agentTaskPrefixes}', "
			+ "jsonb_build_object(:prefix, CAST(:claim AS jsonb)) "
			+ "|| (CASE WHEN jsonb_typeof(record_data->'agentTaskPrefixes') = 'object' "
			+ "THEN record_data->'agentTaskPrefixes' ELSE '{}'::jsonb END)) "
			+ "WHERE uuid = :orgUuid",
			nativeQuery = true)
	int recordTaskPrefixClaim(
			@Param("orgUuid") UUID orgUuid,
			@Param("prefix") String prefix,
			@Param("claim") String claim);

	/** The organization row locked FOR UPDATE (TPF-48); the caller refreshes the entity it returns,
	 *  since open-in-view can hand back a copy loaded earlier in the request. */
	@Transactional
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query(value = "SELECT o FROM Organization o where o.uuid = :uuid")
	Optional<Organization> findByIdWriteLocked(@Param("uuid") UUID uuid);

	/** The stored status, the row locked FOR UPDATE until the caller's transaction ends (TPF-48): every
	 *  whole-record save reads it so that none writes an archived organization back as live. */
	@Query(value = "SELECT record_data->>'status' FROM rearm.organizations WHERE uuid = :uuid FOR UPDATE", nativeQuery = true)
	Optional<String> currentStatusForUpdate(@Param("uuid") UUID uuid);
}
