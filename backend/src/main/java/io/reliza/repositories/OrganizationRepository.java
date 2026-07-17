/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

import io.reliza.model.Organization;

public interface OrganizationRepository extends CrudRepository<Organization, UUID> {
	@Override
	List<Organization> findAll();

	@Query(
			value = VariableQueries.GET_NUMERIC_ANALYTICS_FOR_ORG,
			nativeQuery = true)
	Map<String,BigInteger> getNumericAnalytics(String orgUuidAsString);

	/**
	 * Atomically certifies the org's {@code finding_change_events} backfill watermark (board task #38):
	 * sets {@code settings.findingChangeBackfillCompletedAt} + {@code settings.findingChangeBackfillVocabVersion}
	 * ONLY when the watermark is not already set (the WHERE clause is the idempotency guard -- the original
	 * completion instant is never overwritten). A targeted {@code jsonb_set} on the settings key, NOT a
	 * whole-record save, so a concurrent user settings update can never be clobbered (and vice versa) --
	 * this is system metadata written by the scheduled backfill, deliberately outside the
	 * revision-bump/audit path (mirrors the {@code MAKE_USER_GLOBAL_ADMIN} precedent).
	 *
	 * <p>NULL-SETTINGS TRAP: a legacy org row can carry {@code "settings": null} -- there
	 * {@code record_data->'settings'} yields JSON {@code 'null'::jsonb}, NOT SQL NULL, so a plain
	 * {@code COALESCE} keeps it and jsonb {@code ||} between a non-object and an object produces an
	 * ARRAY ({@code [null, {...}]}), corrupting the row for every Jackson reader (observed live: the
	 * vacuous-certification sweep broke 3 orgs and the {@code user} query 500'd). The
	 * {@code jsonb_typeof = 'object'} guard makes ANY non-object settings (absent, JSON null, or
	 * anything unexpected) start from {@code '{}'}.
	 *
	 * @param completedAt UTC RFC-3339 instant string (stored as a JSON string, Jackson-compatible)
	 * @return rows updated: 1 = certified now, 0 = already certified (or org missing)
	 */
	@Modifying
	@Query(value = "UPDATE rearm.organizations SET record_data = jsonb_set(record_data, '{settings}', "
			+ "(CASE WHEN jsonb_typeof(record_data->'settings') = 'object' "
			+ "THEN record_data->'settings' ELSE '{}'::jsonb END) || jsonb_build_object("
			+ "'findingChangeBackfillCompletedAt', :completedAt, "
			+ "'findingChangeBackfillVocabVersion', :vocabVersion)) "
			+ "WHERE uuid = :orgUuid "
			+ "AND (record_data->'settings'->>'findingChangeBackfillCompletedAt') IS NULL", nativeQuery = true)
	int certifyFindingChangeBackfill(
			@Param("orgUuid") UUID orgUuid,
			@Param("completedAt") String completedAt,
			@Param("vocabVersion") int vocabVersion);

	/**
	 * Bumps an ALREADY-certified org's watermark vocabulary version (after a full-range reseed re-diffed
	 * its history with a widened {@code FindingChangeKind} vocabulary). No-op when not yet certified or
	 * already at/above {@code vocabVersion}. Same targeted-jsonb_set rationale as
	 * {@link #certifyFindingChangeBackfill}.
	 *
	 * @return rows updated: 1 = bumped, 0 = not certified / already current
	 */
	@Modifying
	@Query(value = "UPDATE rearm.organizations SET record_data = jsonb_set(record_data, '{settings}', "
			+ "record_data->'settings' || jsonb_build_object('findingChangeBackfillVocabVersion', :vocabVersion)) "
			+ "WHERE uuid = :orgUuid "
			// completedAt non-null implies settings IS an object here, but keep the same typeof guard as
			// certify so the null-settings jsonb-concat trap can never resurface through this path.
			+ "AND jsonb_typeof(record_data->'settings') = 'object' "
			+ "AND (record_data->'settings'->>'findingChangeBackfillCompletedAt') IS NOT NULL "
			+ "AND COALESCE((record_data->'settings'->>'findingChangeBackfillVocabVersion')::int, 1) < :vocabVersion",
			nativeQuery = true)
	int bumpFindingChangeBackfillVocab(
			@Param("orgUuid") UUID orgUuid,
			@Param("vocabVersion") int vocabVersion);

	/**
	 * Organizations with NO backfill watermark and NO {@code RELEASE} {@code metrics_audit} rows at all --
	 * i.e. orgs whose releases were never re-scanned, so their {@code finding_change_events} log is
	 * VACUOUSLY complete (no transitions ever happened; the always-on live emit captures everything going
	 * forward). The daily repair sweep certifies these, so new orgs flow onto the posture-diff path without
	 * waiting for an instance restart (the boot backfill only visits orgs WITH audit rows).
	 */
	@Query(value = "SELECT o.uuid FROM rearm.organizations o "
			+ "WHERE (o.record_data->'settings'->>'findingChangeBackfillCompletedAt') IS NULL "
			+ "AND NOT EXISTS (SELECT 1 FROM rearm.metrics_audit ma "
			+ "WHERE ma.entity_type = :entityType AND ma.org = o.uuid)", nativeQuery = true)
	List<UUID> findOrgsEligibleForVacuousFindingChangeCertification(@Param("entityType") String entityType);

	/**
	 * The BRANCH-GRAIN v3 analogue of {@link #findOrgsEligibleForVacuousFindingChangeCertification}: orgs
	 * with NO v3 backfill watermark and NO {@code metrics_audit} rows of the given entity type. Gates on the
	 * V3 watermark (not the v1 one) so a never-re-scanned org that already carries a legacy v1/v2 watermark
	 * -- e.g. an OPERATED instance laddering v1 -> ... -> DUAL_V3 -> V3_ONLY -- is still certified for v3 and
	 * flips onto the v3 read path instead of staying stuck on v2 forever.
	 */
	@Query(value = "SELECT o.uuid FROM rearm.organizations o "
			+ "WHERE (o.record_data->'settings'->>'findingChangeV3BackfillCompletedAt') IS NULL "
			+ "AND NOT EXISTS (SELECT 1 FROM rearm.metrics_audit ma "
			+ "WHERE ma.entity_type = :entityType AND ma.org = o.uuid)", nativeQuery = true)
	List<UUID> findOrgsEligibleForVacuousFindingChangeV3Certification(@Param("entityType") String entityType);

	/**
	 * Certifies the org's BRANCH-GRAIN {@code finding_change_events_v3} (events-lite) backfill watermark
	 * (board task #38, v3 events-lite): stamps {@code findingChangeV3BackfillCompletedAt} +
	 * {@code findingChangeV3BackfillKeyVersion} via a targeted jsonb_set with a typeof-object guard (the
	 * null-settings jsonb-concat trap can never resurface) and a monotonic key-version floor -- an
	 * older-build pod (lower {@link io.reliza.service.FindingDimKey#KEY_VERSION}) can never regress the
	 * version during a rolling deploy, and a re-key backfill re-certifies at the new version with the
	 * completion instant refreshed each clean run.
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
}
