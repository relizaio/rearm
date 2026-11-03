/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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
	 * <p>And not while the row carries the not-scannable marker (TPF-125): a verdict of the fan-out is
	 * as authoritative as a scan, and a seed landing under it would show findings on an artifact
	 * nothing will ever scan.
	 *
	 * @return rows affected: 1 if the seed landed, 0 if a scan or a not-scannable verdict got there first
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.artifacts SET metrics = CAST(:metrics AS jsonb), metrics_revision = metrics_revision + 1 "
			+ "WHERE uuid = :uuid AND (metrics->>'firstScanned') IS NULL "
			+ "AND jsonb_typeof(metrics->'notScannable') IS DISTINCT FROM 'object'", nativeQuery = true)
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
	 *
	 * <p>The stamp comes from the caller, not the database's {@code now()}: the
	 * fan-out cutoff is taken, to the fraction of a second, from bucket updates
	 * dated by the application clock, and a database clock a few milliseconds
	 * behind would leave a just-processed artifact under that cutoff.
	 *
	 * @param scannedAtEpoch the scan time as fractional epoch seconds
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.artifacts "
			+ "SET metrics = jsonb_set(metrics, '{lastScanned}', to_jsonb(cast(:scannedAtEpoch as float8))) "
			+ "WHERE uuid = :uuid AND metrics IS NOT NULL", nativeQuery = true)
	void advanceLastScannedOnly(@Param("uuid") UUID uuid, @Param("scannedAtEpoch") double scannedAtEpoch);

	/**
	 * Mark an artifact not scannable (TPF-125): metrics.notScannable = {reason, components, root,
	 * terminal, unmatchable, at}, written in place. Only when the stored marker's content (everything
	 * but at) differs, so a pass over an unchanged canonical writes nothing and keeps the stored at;
	 * a changed reason or count re-stamps at. Tolerates a NULL column and a JSON null at the key
	 * (jsonb_typeof). No revision bump, no audit row: no content changed, a scheduler derived a
	 * state. The caller touches the releases carrying the artifact when this returns 1, because the
	 * release metrics compute reads the marker (ReleaseMetricsComputeService.isScanInput).
	 *
	 * @param reason the NoMatchableComponentsReason name
	 * @param atEpoch the pass instant as fractional epoch seconds (the form Jackson writes for the field)
	 * @return 1 when the marker landed or changed, 0 when it was already stored
	 */
	@Transactional
	@Modifying
	@Query(value = """
			UPDATE rearm.artifacts
			SET metrics = coalesce(metrics, '{}'::jsonb) || jsonb_build_object('notScannable',
			        jsonb_build_object('reason', cast(:reason as text),
			            'components', cast(:components as int), 'root', cast(:root as int),
			            'terminal', cast(:terminal as int), 'unmatchable', cast(:unmatchable as int),
			            'at', cast(:atEpoch as float8)))
			WHERE uuid = :uuid
			  AND (CASE WHEN jsonb_typeof(metrics->'notScannable') = 'object'
			            THEN (metrics->'notScannable') - 'at' END)
			      IS DISTINCT FROM jsonb_build_object('reason', cast(:reason as text),
			            'components', cast(:components as int), 'root', cast(:root as int),
			            'terminal', cast(:terminal as int), 'unmatchable', cast(:unmatchable as int))
			""", nativeQuery = true)
	int stampNotScannable(@Param("uuid") UUID uuid, @Param("reason") String reason,
			@Param("components") int components, @Param("root") int root,
			@Param("terminal") int terminal, @Param("unmatchable") int unmatchable,
			@Param("atEpoch") double atEpoch);

	/**
	 * Remove the not-scannable marker (TPF-125): the artifact's canonical has a matchable component
	 * again (a gate skip, or a scan after a parse, a repoint or a re-reconcile). In place, no
	 * revision, no audit row; a no-op on a row without the key. The caller
	 * (SharedArtifactService.clearArtifactNotScannable) touches the releases carrying the artifact
	 * when this returns 1 on an artifact without firstScanned, because the release metrics compute
	 * reads the marker (ReleaseMetricsComputeService.isScanInput). The predicate is written as
	 * "-> IS NOT NULL" (true for a JSON null too, which it also removes) rather than the jsonb "?"
	 * operator, which Spring Data would read as a positional parameter.
	 *
	 * @return rows updated, 1 or 0
	 */
	@Transactional
	@Modifying
	@Query(value = "UPDATE rearm.artifacts SET metrics = metrics - 'notScannable' "
			+ "WHERE uuid = :uuid AND metrics->'notScannable' IS NOT NULL", nativeQuery = true)
	int clearNotScannable(@Param("uuid") UUID uuid);

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
	 * BOM history behind the V67 partial index every tick -- in steady state
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
			// requires an org) -- returning one would pin the watermark on a
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
	 * First index entry of the V67 partial index -- cheap. Instant, not
	 * ZonedDateTime: Hibernate maps a bare native timestamptz aggregate to
	 * Instant.
	 */
	@Query(value = "SELECT min(a.created_date) FROM rearm.artifacts a "
			+ "WHERE a.record_data->>'type' = 'BOM'", nativeQuery = true)
	java.time.Instant findOldestBomArtifactCreatedDate();

	/**
	 * The artifact row carrying this TEA artifact id (task TEA-5; the part 5 lookup the partial
	 * unique index of V97 exists for).
	 */
	@Query(value = VariableQueries.FIND_ARTIFACT_BY_TEA_UUID, nativeQuery = true)
	java.util.Optional<Artifact> findByTeaUuid(@Param("teaUuidAsString") String teaUuidAsString);

	/**
	 * Transaction-scoped advisory lock around the one-time mint of an artifact's TEA id (task
	 * TEA-5), the twin of ComponentRepository.lockTeaIdMint: two releases publishing the same
	 * ride-along row at once must not mint two ids. Two-key form, {@code lockNamespace} =
	 * {@code AdvisoryLockKey.TEA_ID_MINT}.
	 */
	@Query(
		value = """
			SELECT 1 FROM (
			  SELECT pg_advisory_xact_lock(:lockNamespace, hashtext(:artifactUuidAsString))
			) AS locked
			""",
		nativeQuery = true)
	Integer lockTeaIdMint(@Param("lockNamespace") int lockNamespace, @Param("artifactUuidAsString") String artifactUuidAsString);

	/**
	 * The artifact's TEA id mint (TEA-5 round 4, decision 24): one conditional statement that sets
	 * the one JSON key where it is null and touches no other column (no revision, no last-updated
	 * date, no audit row, no @Version and no version). Answers the rows updated, 1 or 0.
	 */
	@Modifying
	@Query(value = "UPDATE rearm.artifacts SET record_data = jsonb_set(record_data, '{teaUuid}', "
			+ "to_jsonb(CAST(:teaUuid AS text)), true) WHERE uuid = :uuid AND record_data->>'teaUuid' IS NULL",
			nativeQuery = true)
	int mintTeaUuid(@Param("uuid") UUID uuid, @Param("teaUuid") String teaUuid);

	/**
	 * The row's TEA id, or empty, with the row locked FOR UPDATE until the caller's transaction
	 * ends: the read every whole-record save of the artifact makes before it writes, so that it
	 * writes the stored id back and never erases or introduces one (TEA-5 round 4, T-10 point 4).
	 */
	@Query(value = "SELECT record_data->>'teaUuid' FROM rearm.artifacts WHERE uuid = :uuid FOR UPDATE",
			nativeQuery = true)
	Optional<String> currentTeaUuidForUpdate(@Param("uuid") UUID uuid);

	/**
	 * The BOM artifacts of an organization carrying the serialNumber {@code serialAsString}
	 * (canonical lowercase uuid text, the form internalBom.id is stored in): the rows rule B reads
	 * the holders of (SCORE-52 ADR-3). Served by the V95 expression index
	 * idx_artifacts_org_internal_bom, whose expressions this predicate repeats verbatim.
	 */
	@Query(value = VariableQueries.FIND_BOM_ARTIFACTS_BY_ORG_AND_INTERNAL_BOM, nativeQuery = true)
	List<Artifact> findBomArtifactsByOrgAndInternalBom(@Param("org") String orgUuidAsString,
			@Param("serial") String serialAsString);

	/**
	 * Every artifact whose own {@code artifacts} list names {@code artUuidAsString}, in any
	 * organization (SCORE-52 ADR-8: a container of another organization must be seen, so that it
	 * fails closed). Served by the jsonb_path_ops GIN index idx_artifacts_record_data_gin.
	 */
	@Query(value = VariableQueries.FIND_ARTIFACTS_CONTAINING_ARTIFACT, nativeQuery = true)
	List<Artifact> findContainingArtifacts(@Param("artUuidAsString") String artUuidAsString);
}
