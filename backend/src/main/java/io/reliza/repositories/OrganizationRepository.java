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
}
