/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.CrudRepository;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.Artifact;

public interface ArtifactRepository extends CrudRepository<Artifact, UUID> {
	
	@Query(
			value = VariableQueries.LIST_ARTIFACTS_BY_ORG,
			nativeQuery = true)
	List<Artifact> listArtifactsByOrg(String orgUuidAsString);
	
	@Query(
			value = VariableQueries.FIND_ARTIFACTS_BY_STORED_DIGEST,
			nativeQuery = true)
	List<Artifact> findArtifactsByStoredDigest(String orgUuidAsString, String digest);
	
	@Query(
			value = VariableQueries.FIND_ARTIFACT_UUIDS_BY_DTRACK_PROJECTS,
			nativeQuery = true)
	List<UUID> findArtifactUuidsByDtrackProjects(List<String> dtrackProjectIds);

	/**
	 * Phase-out candidates: up to {@code lim} distinct legacy per-artifact DTrack
	 * projects, globally across orgs, as {@code [projectId, orgUuid]} pairs. Only
	 * {@code metrics.dependencyTrackProject} (legacy submission writes this; the
	 * synthetic flow never does) and excludes any project that is a synthetic
	 * bucket project, so we never delete our own buckets. Cleared rows drop out
	 * naturally, so the batch self-advances tick over tick.
	 */
	@Query(value = """
			SELECT DISTINCT a.metrics->>'dependencyTrackProject' AS project_id,
			                a.record_data->>'org'               AS org_uuid
			FROM rearm.artifacts a
			WHERE a.metrics->>'dependencyTrackProject' IS NOT NULL
			  AND a.metrics->>'dependencyTrackProject' <> ''
			  AND NOT EXISTS (
			        SELECT 1 FROM rearm.synthetic_dtrack_bucket b
			        WHERE b.dtrack_project_uuid::text = a.metrics->>'dependencyTrackProject')
			LIMIT :lim
			""", nativeQuery = true)
	List<Object[]> listLegacyDtrackProjectsForPhaseOut(@Param("lim") int lim);

	/**
	 * Remove the legacy DTrack reference ({@code dependencyTrackProject} +
	 * {@code dependencyTrackFullUri}) from every artifact pointing at one phased-out
	 * project. Leaves findings / lastScanned / firstScanned (now synthetic) intact.
	 */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE rearm.artifacts
			SET metrics = (metrics - 'dependencyTrackProject' - 'dependencyTrackFullUri')
			WHERE record_data->>'org' = :orgUuidAsString
			  AND metrics->>'dependencyTrackProject' = :projectId
			""", nativeQuery = true)
	int clearDtrackProjectRef(
			@Param("orgUuidAsString") String orgUuidAsString,
			@Param("projectId") String projectId);

	@Query(
			value = VariableQueries.FIND_ARTIFACTS_WITH_VULNERABILITY,
			nativeQuery = true)
	List<UUID> findArtifactsWithVulnerability(String orgUuidAsString, String location, String findingId);

	@Query(
			value = VariableQueries.FIND_ARTIFACTS_WITH_VULN_ID,
			nativeQuery = true)
	List<UUID> findArtifactsWithVulnId(String orgUuidAsString, String vulnId);

	@Query(
			value = VariableQueries.FIND_VULN_PURLS_FOR_VULN_ID,
			nativeQuery = true)
	List<String> findVulnPurlsForVulnId(String orgUuidAsString, String vulnId);

	@Query(
			value = VariableQueries.FIND_ARTIFACTS_WITH_VIOLATION,
			nativeQuery = true)
	List<UUID> findArtifactsWithViolation(String orgUuidAsString, String location, String findingId);

	@Query(
			value = VariableQueries.FIND_ARTIFACTS_WITH_WEAKNESS,
			nativeQuery = true)
	List<UUID> findArtifactsWithWeakness(String orgUuidAsString, String location, String findingId);
	
	@Query(
			value = VariableQueries.LIST_ARTIFACT_UUIDS_BY_COMPONENTS,
			nativeQuery = true)
	List<String> listArtifactUuidsByComponents(@Param("componentUuids") Collection<String> componentUuids);

	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.artifacts SET metrics = CAST(:metrics AS jsonb), metrics_revision = metrics_revision + 1 WHERE uuid = :uuid", nativeQuery = true)
	void updateMetrics(@Param("uuid") UUID uuid, @Param("metrics") String metrics);

	/**
	 * Advance ONLY {@code metrics.lastScanned}, bypassing the full
	 * {@code saveArtifactMetrics} path (no touches fired, no metrics audit row,
	 * no release recompute). Written for the fan-out idempotency guard in
	 * {@code SharedArtifactService.updateArtifactDti}: when an already-scanned
	 * artifact's findings are unchanged, the guard used to return WITHOUT
	 * advancing the stamp -- violating the fan-out candidate contract
	 * ("processed implies drops out of the pool"). After any mass bucket
	 * re-ingest that put more than FANOUT_BATCH_LIMIT canonicals back in the
	 * pool, those unchanged artifacts became permanent candidates, the unordered
	 * batch served the same ones every tick, and genuinely-unscanned artifacts
	 * starved indefinitely (prod 2026-07-25: 5,029 looping artifact rows across
	 * ~793 canonicals; 3 new artifacts stuck on "Scan pending" behind them).
	 *
	 * <p>Epoch-numeric form matches what Jackson writes for the DTI field, so
	 * both the SQL float casts and DTI deserialization see the same shape.
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.artifacts "
			+ "SET metrics = jsonb_set(metrics, '{lastScanned}', to_jsonb(extract(epoch from now()))) "
			+ "WHERE uuid = :uuid AND metrics IS NOT NULL", nativeQuery = true)
	void advanceLastScannedOnly(@Param("uuid") UUID uuid);

	/**
	 * Fan-out candidate pool slice: artifacts of the org whose scan stamp is
	 * absent or predates {@code cutoffEpoch} (the newest INGESTED-bucket update,
	 * computed by the caller from the buckets it already iterates).
	 *
	 * <p>This is one half of the two-query candidate resolution that replaced
	 * the single joined query, which timed out on large instances: every joined
	 * formulation (plain, CTE-fenced, LATERAL, LIMIT-bounded) left the planner
	 * at least one estate-sized scan to pick -- measured at 500k artifacts, the
	 * join hashed all of artifact_canonical_map and the EXISTS hashed all of
	 * artifact_sbom_components (99-268ms and estate-shaped either way), while
	 * this split resolves in ~1ms total. No join, no EXISTS: nothing to
	 * misplan. Both arms are written in the cast(... as float) form so the
	 * planner BitmapOrs the V74 org-scoped expression index; in steady state
	 * both ranges are empty regardless of org size.
	 *
	 * <p>Ordering: never-scanned first (the artifacts a user is actively
	 * waiting on), newest upload first within them, then stalest stamp -- the
	 * caller preserves this order when deduplicating to canonicals. Artifacts
	 * with no matchable components (out of the scanning universe, see the #345
	 * stall-counter scoping) also surface here and are skipped un-stamped by
	 * the caller's gate; the created_date tiebreak keeps such legacy debris
	 * behind every fresh upload, so it cannot starve real candidates.
	 */
	@Query(value = """
			SELECT a.uuid
			FROM rearm.artifacts a
			WHERE a.record_data->>'org' = cast(:org as text)
			  AND (cast(a.metrics->>'lastScanned' as float) IS NULL
			       OR cast(a.metrics->>'lastScanned' as float) < :cutoffEpoch)
			ORDER BY cast(a.metrics->>'lastScanned' as float) ASC NULLS FIRST,
			         a.created_date DESC
			LIMIT :lim
			""", nativeQuery = true)
	List<UUID> findFanOutPoolSlice(
			@Param("org") UUID org, @Param("cutoffEpoch") double cutoffEpoch, @Param("lim") int lim);

	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.artifacts SET metrics_revision = metrics_revision + 1 WHERE uuid = :uuid", nativeQuery = true)
	void bumpMetricsRevision(@Param("uuid") UUID uuid);

	/**
	 * Unmapped-BOM sweep driver: BOM-typed artifacts old enough that their
	 * post-upload reconcile should long since have mapped them, yet with no
	 * {@code artifact_canonical_map} row. Oldest-first so long-standing
	 * orphans heal ahead of fresh ones. Driven off the partial
	 * {@code artifacts_bom_type_created_idx} (V67), so the steady-state
	 * empty result is an index-only probe over the BOM subset.
	 */
	@Query(value = "SELECT a.uuid FROM rearm.artifacts a "
			+ "WHERE a.record_data->>'type' = 'BOM' "
			+ "AND (a.record_data->'internalBom'->>'id') IS NOT NULL "
			+ "AND a.created_date < now() - make_interval(mins => :olderThanMinutes) "
			+ "AND NOT EXISTS (SELECT 1 FROM rearm.artifact_canonical_map m WHERE m.artifact_uuid = a.uuid) "
			+ "ORDER BY a.created_date ASC "
			+ "LIMIT :lim", nativeQuery = true)
	List<UUID> findUnmappedBomArtifactUuids(@Param("olderThanMinutes") int olderThanMinutes,
			@Param("lim") int lim);
}
