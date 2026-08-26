/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
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

	/**
	 * Most recent artifact-metrics scan stamp in the org, as epoch seconds, or
	 * null when the org has no scanned artifact at all. Used by the
	 * notifications affected-release guard to decide whether an empty resolve
	 * is trustworthy enough to withhold a vulnerability notification on.
	 */
	@Query(
			value = VariableQueries.FIND_MAX_LAST_SCANNED_EPOCH_FOR_ORG,
			nativeQuery = true)
	Double findMaxLastScannedEpochForOrg(String orgUuidAsString);

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
	 * As {@link #updateMetrics}, but ONLY while the row is still unscanned, and reports whether it
	 * landed. For the findings carry-forward seed, which must never overwrite a real scan result.
	 *
	 * <p>The in-Java guard cannot close this on its own: carry-forward reads the successor, decides it
	 * is unscanned, then writes -- and the synthetic fan-out writes artifact metrics on its own PT1M
	 * tick with no shared lock, so a scan landing between that read and this write was silently
	 * clobbered by the predecessor's findings. In a vulnerability product that shows a fixed CVE as
	 * still open, or hides a newly introduced one, until something happens to re-scan. Re-testing the
	 * condition in the UPDATE's own WHERE makes the database the arbiter, so the scan wins
	 * deterministically rather than by timing.
	 *
	 * @return rows affected: 1 if the seed landed, 0 if a scan got there first
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.artifacts SET metrics = CAST(:metrics AS jsonb), metrics_revision = metrics_revision + 1 "
			+ "WHERE uuid = :uuid AND (metrics->>'firstScanned') IS NULL", nativeQuery = true)
	int updateMetricsIfStillUnscanned(@Param("uuid") UUID uuid, @Param("metrics") String metrics);

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
	 * misplan.
	 *
	 * <p>The scan stamp is compared as {@code coalesce(..., -1)} rather than
	 * {@code IS NULL OR ... < cutoff}, and ordered by that same expression, so
	 * that predicate and ORDER BY are a single range over one index key. The OR
	 * form could not be an index condition, so it stayed a Filter with the sort
	 * on top: the LIMIT got no stop key and every tick paid for the whole org
	 * rather than the slice it takes. That is what timed out in production
	 * (QueryTimeoutException on this statement, 2026-08-14). The two shapes have
	 * to be checked together -- a backlog org fills the LIMIT and exits early
	 * even on a bad plan, so it is the STEADY-STATE org, where nothing
	 * qualifies and the scan runs to the end of the org, that exposes the
	 * missing stop key. On an ordered index the OR form measured 57ms against a
	 * 120k steady-state org and 0.9ms against a 96k backlog org; this form
	 * measures 0.06ms and 0.7ms on the same two. Full numbers in V80.
	 *
	 * <p>{@code -1} is the sentinel and not the {@code 0} used elsewhere for
	 * this field because {@code cutoffEpoch} itself floors at 0 (the caller
	 * seeds it to 0 and only raises it): at {@code cutoff == 0} a 0 sentinel
	 * makes never-scanned artifacts stop qualifying, silently draining nothing
	 * for exactly the orgs this query exists to serve. Any sentinel below the
	 * cutoff floor keeps the rewrite equivalent to the OR form -- verified
	 * across every org at cutoffs {0, 1, now, future} with an empty symmetric
	 * difference, and NULLS FIRST ordering is reproduced because the sentinel
	 * sorts below every real epoch. V80 indexes this exact expression; the
	 * spelling must stay identical to it or the planner loses the match.
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
			  AND coalesce(cast(a.metrics->>'lastScanned' as float), -1) < :cutoffEpoch
			ORDER BY coalesce(cast(a.metrics->>'lastScanned' as float), -1) ASC,
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
	 * orphans heal ahead of fresh ones.
	 *
	 * WINDOWED [fromTs, toTs): the unwindowed predecessor walked the entire
	 * BOM history behind the V67 partial index every tick — in steady state
	 * (everything mapped) the LIMIT never fills, so each tick heap-fetched
	 * and detoasted record_data for every BOM artifact ever created and
	 * anti-join-probed each one, which outgrew the 120s query timeout in
	 * production. The sweep now advances a persisted watermark
	 * (SystemInfoData.unmappedBomSweepWatermark) window by window, so the
	 * steady-state tick probes only the newest window.
	 */
	@Query(value = "SELECT a.uuid FROM rearm.artifacts a "
			+ "WHERE a.record_data->>'type' = 'BOM' "
			+ "AND (a.record_data->'internalBom'->>'id') IS NOT NULL "
			// org-less rows are unhealable by construction (the heal loop
			// requires an org) — returning one would pin the watermark on a
			// row that can never leave the result set.
			+ "AND a.record_data->>'org' IS NOT NULL "
			+ "AND a.created_date >= :fromTs AND a.created_date < :toTs "
			+ "AND NOT EXISTS (SELECT 1 FROM rearm.artifact_canonical_map m WHERE m.artifact_uuid = a.uuid) "
			+ "ORDER BY a.created_date ASC "
			+ "LIMIT :lim", nativeQuery = true)
	List<UUID> findUnmappedBomArtifactUuidsInWindow(@Param("fromTs") ZonedDateTime fromTs,
			@Param("toTs") ZonedDateTime toTs, @Param("lim") int lim);

	/**
	 * Watermark bootstrap: the creation date of the oldest BOM artifact.
	 * First index entry of the V67 partial index — cheap. Instant, not
	 * ZonedDateTime: Hibernate maps a bare native timestamptz aggregate to
	 * Instant.
	 */
	@Query(value = "SELECT min(a.created_date) FROM rearm.artifacts a "
			+ "WHERE a.record_data->>'type' = 'BOM'", nativeQuery = true)
	java.time.Instant findOldestBomArtifactCreatedDate();
}
