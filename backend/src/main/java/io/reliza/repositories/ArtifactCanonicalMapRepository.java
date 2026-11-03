/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.model.ArtifactCanonicalMap;

public interface ArtifactCanonicalMapRepository extends CrudRepository<ArtifactCanonicalMap, UUID> {

	Optional<ArtifactCanonicalMap> findByArtifactUuid(UUID artifactUuid);

	/** Projection for the canonical-form sweep pickup -- org is needed to scope the component lookup. */
	interface PendingCanonicalForm {
		UUID getOrg();
		UUID getCanonicalArtifactUuid();
	}

	/**
	 * Canonical artifacts whose component mappings have not yet been verified
	 * against canonical-purl form {@code version}. Absent flow_control (every row
	 * predating V72) coalesces to 0, so the whole estate is picked up once and
	 * then drops out permanently -- the range scan over
	 * {@code artifact_canonical_map_canonical_form_version_idx} returns nothing
	 * once every row sits at the current version, which is what keeps the tick
	 * free when there is no work.
	 *
	 * <p>DISTINCT because several artifacts dedupe onto one canonical; the repair
	 * unit is the canonical, not the artifact.
	 */
	@Query(value = """
			SELECT DISTINCT acm.org AS org, acm.canonical_artifact_uuid AS canonicalArtifactUuid
			FROM rearm.artifact_canonical_map acm
			WHERE coalesce((acm.flow_control->>'canonicalFormVersion')::int, 0) < :version
			LIMIT :batchLimit
			""", nativeQuery = true)
	List<PendingCanonicalForm> findPendingCanonicalForm(
			@Param("version") int version, @Param("batchLimit") int batchLimit);

	/**
	 * Stamp every map row of a canonical as verified against {@code version}.
	 * Merges into flow_control rather than replacing it so unrelated per-artifact
	 * keys added later survive the sweep.
	 */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE rearm.artifact_canonical_map
			SET flow_control = coalesce(flow_control, '{}'::jsonb)
			                   || jsonb_build_object('canonicalFormVersion', cast(:version as int))
			WHERE canonical_artifact_uuid = :canonicalArtifactUuid
			""", nativeQuery = true)
	void markCanonicalFormVerified(
			@Param("canonicalArtifactUuid") UUID canonicalArtifactUuid, @Param("version") int version);

	/**
	 * Fan-out candidates that no pass has stamped yet (TPF-114): map rows of the org without
	 * flow_control.fanOutAt, newest row first, so a fresh upload and the catch-up after deploy
	 * come before everything else. Several rows may name one canonical; the caller deduplicates
	 * in this order. Served by the partial index artifact_canonical_map_fanout_pending_idx
	 * (V102), whose predicate this WHERE repeats verbatim; the index is empty once every row is
	 * stamped, so the steady-state cost is an empty index scan. A requeue (requeueFanOutFor*)
	 * removes the key, so a requeued row is in this set again, at its created_date position.
	 */
	@Query(value = """
			SELECT acm.canonical_artifact_uuid
			FROM rearm.artifact_canonical_map acm
			WHERE acm.org = :org
			  AND (acm.flow_control->>'fanOutAt') IS NULL
			ORDER BY acm.created_date DESC
			LIMIT :lim
			""", nativeQuery = true)
	List<UUID> findNeverFannedOutCanonicals(@Param("org") UUID org, @Param("lim") int lim);

	/**
	 * Record a fan-out pass over a canonical (TPF-114): every map row of the canonical, in the org,
	 * gets flow_control.fanOutAt = the pass cutoff (the newest INGESTED-bucket update the pass saw,
	 * ISO-8601 UTC instant). Merge-write so canonicalFormVersion and later keys survive; org in the
	 * WHERE so a stamp can never land on another org's rows. Rows requeued (requeueFanOutFor*,
	 * fanOutRequeuedAt) at or after passStartedAt are left alone: the pass evaluated the gate
	 * against inputs that changed since, and the row must stay pending for the next pass. One
	 * statement per canonical per pass, run after that canonical's artifact writes, in its own
	 * transaction. Writes no revision and no audit row (the table has neither; it is scheduler
	 * bookkeeping, coding principle "bookkeeping writes are in-place updates").
	 *
	 * @return rows updated; 0 means the canonical has no map rows left, or every row was requeued
	 *         during this pass (logged by the caller at INFO; both leave nothing stale)
	 */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE rearm.artifact_canonical_map
			SET flow_control = coalesce(flow_control, '{}'::jsonb)
			                   || jsonb_build_object('fanOutAt', cast(:fanOutAt as text))
			WHERE org = :org AND canonical_artifact_uuid = :canonicalArtifactUuid
			  AND coalesce((flow_control->>'fanOutRequeuedAt')::timestamptz,
			               '-infinity'::timestamptz) < :passStartedAt
			""", nativeQuery = true)
	int stampFanOutPass(@Param("org") UUID org,
			@Param("canonicalArtifactUuid") UUID canonicalArtifactUuid,
			@Param("fanOutAt") String fanOutAt,
			@Param("passStartedAt") ZonedDateTime passStartedAt);

	/**
	 * Requeue for fan-out the canonicals holding any of the given components (TPF-114 F-1): a
	 * writer that changes a canonical's coverage gate or findings without moving any bucket date
	 * (markEnrichmentTerminal on an unbucketed component) has no change signal of its own, so it
	 * removes flow_control.fanOutAt from the map rows and query (A) takes the canonical on the next
	 * tick. Records the database instant as fanOutRequeuedAt so a pass already running cannot
	 * stamp over it (stampFanOutPass's precondition). Org on the map rows and inside the component
	 * subquery; the subquery runs on artifact_sbom_components_component_idx (sbom_component_uuid),
	 * the rows it names on artifact_canonical_map_canonical_idx. Every map row reached is written,
	 * pending rows included (TPF-114 F-4): a canonical a running pass has already taken from the
	 * never-stamped query, or from the bucket query before its stamp, has no fanOutAt yet, and only
	 * the fanOutRequeuedAt written here makes that pass's stamp fail its precondition; a requeue
	 * that skipped pending rows was stamped over. A repeat rewrites one key, which is harmless.
	 * flow_control is coalesced because resolveCanonicalArtifact inserts map rows with it NULL and
	 * NULL - 'fanOutAt' || ... is NULL. Bookkeeping write: no revision, no audit row. Called after
	 * the gate-changing write committed, in its own transaction. Bounded by the caller: at most
	 * ENRICHMENT_CANDIDATE_WINDOW component uuids. The caller skips the call on an empty
	 * collection.
	 *
	 * @return map rows requeued
	 */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE rearm.artifact_canonical_map acm
			SET flow_control = (coalesce(acm.flow_control, '{}'::jsonb) - 'fanOutAt')
			                   || jsonb_build_object('fanOutRequeuedAt',
			                        to_char(clock_timestamp() AT TIME ZONE 'UTC',
			                                'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
			WHERE acm.org = :org
			  AND acm.canonical_artifact_uuid IN (
			        SELECT ascx.canonical_artifact_uuid
			        FROM rearm.artifact_sbom_components ascx
			        WHERE ascx.org = :org AND ascx.sbom_component_uuid IN (:componentUuids))
			""", nativeQuery = true)
	int requeueFanOutForComponents(@Param("org") UUID org,
			@Param("componentUuids") Collection<UUID> componentUuids);

	/** Same for one canonical (the repoint and the parse writers); same javadoc contract: every
	 *  map row of the canonical is written, pending rows included, flow_control coalesced. */
	@Modifying
	@Transactional
	@Query(value = """
			UPDATE rearm.artifact_canonical_map
			SET flow_control = (coalesce(flow_control, '{}'::jsonb) - 'fanOutAt')
			                   || jsonb_build_object('fanOutRequeuedAt',
			                        to_char(clock_timestamp() AT TIME ZONE 'UTC',
			                                'YYYY-MM-DD"T"HH24:MI:SS.US"Z"'))
			WHERE org = :org AND canonical_artifact_uuid = :canonicalArtifactUuid
			""", nativeQuery = true)
	int requeueFanOutForCanonical(@Param("org") UUID org,
			@Param("canonicalArtifactUuid") UUID canonicalArtifactUuid);

	/**
	 * The database clock (TPF-114), read once per fan-out pass as the precondition instant of its
	 * stamps. Declared as Instant because a native scalar timestamptz comes back as one and Spring
	 * Data does not convert it to ZonedDateTime (TPF-114 functional review F-6: a ZonedDateTime
	 * return type throws ClassCastException at runtime).
	 */
	@Query(value = "SELECT clock_timestamp()", nativeQuery = true)
	Instant dbClockInstant();

	/** {@link #dbClockInstant()} as a UTC ZonedDateTime, the type stampFanOutPass binds. */
	default ZonedDateTime dbClockTimestamp() {
		return dbClockInstant().atZone(ZoneOffset.UTC);
	}

	/** Map rows older than cutoff that no fan-out pass has stamped (TPF-114); served by the V102 partial index. */
	@Query(value = """
			SELECT count(*) FROM rearm.artifact_canonical_map acm
			WHERE acm.org = :org AND (acm.flow_control->>'fanOutAt') IS NULL
			  AND acm.created_date < :cutoff
			""", nativeQuery = true)
	long countFanOutPendingOlderThan(@Param("org") UUID org, @Param("cutoff") ZonedDateTime cutoff);

	/**
	 * Map rows of the org that a fan-out pass has stamped (flow_control.fanOutAt present), TPF-136:
	 * zero means every row is in the never-stamped set of findNeverFannedOutCanonicals, which is
	 * when fanOutOrg may skip the bucket walk on a cold start. Served by
	 * artifact_canonical_map_org_idx (V37) with a heap filter on the key: bounded by the org's map
	 * rows (one per mapped artifact), never by the containment table. A requeue clears the key, so
	 * a requeued row does not count.
	 */
	@Query(value = """
			SELECT count(*) FROM rearm.artifact_canonical_map acm
			WHERE acm.org = :org AND (acm.flow_control->>'fanOutAt') IS NOT NULL
			""", nativeQuery = true)
	long countFanOutStamped(@Param("org") UUID org);

	/**
	 * Of the given canonicals, those with a map row of the org that no pass has stamped or whose
	 * fanOutAt is before changedBefore (TPF-137): the per-row stamp test of the retired bucket
	 * statement, run on a walk batch after its LIMIT instead of inside the enumeration. Served by
	 * artifact_canonical_map_canonical_idx (one probe per uuid of the list) with the jsonb test on
	 * the rows found; bounded by the batch (at most FANOUT_BATCH_LIMIT uuids), never by the
	 * containment table. The result is a filter, not an order. The caller skips the call on an
	 * empty list.
	 */
	@Query(value = """
			SELECT DISTINCT acm.canonical_artifact_uuid
			FROM rearm.artifact_canonical_map acm
			WHERE acm.org = :org
			  AND acm.canonical_artifact_uuid IN (:canonicals)
			  AND ((acm.flow_control->>'fanOutAt') IS NULL
			       OR (acm.flow_control->>'fanOutAt')::timestamptz < :changedBefore)
			""", nativeQuery = true)
	List<UUID> findCanonicalsStampedBefore(@Param("org") UUID org,
			@Param("canonicals") Collection<UUID> canonicals, @Param("changedBefore") ZonedDateTime changedBefore);

	/**
	 * Reverse lookup: every artifact uuid in the org that points to the given
	 * canonical. Useful for surfacing "which uploads share this content"
	 * cross-release analytics.
	 */
	/**
	 * Sample for stall diagnostics: canonicals of the oldest never-scanned mapped artifacts.
	 * Scoped to BOM-typed artifacts (TPF-114), the spelling of V67's partial index.
	 *
	 * <p>Same universe as the counter ({@link #countNeverScannedMappedArtifactsOlderThan}): the
	 * counter's matchable-component EXISTS verbatim (TPF-125), so an artifact the coverage gate has
	 * nothing to scan for (marked not scannable) is never sampled, while a stale not-scannable marker
	 * on a canonical that has since gained a matchable component still is.
	 */
	@Query(value = """
			SELECT acm.canonical_artifact_uuid
			FROM rearm.artifact_canonical_map acm
			JOIN rearm.artifacts a ON a.uuid = acm.artifact_uuid
			WHERE acm.org = :org
			  AND a.record_data->>'org' = cast(:org as text)
			  AND a.record_data->>'type' = 'BOM'
			  AND a.created_date < :cutoff
			  AND cast(a.metrics->>'lastScanned' as float) IS NULL
			  AND EXISTS (
			        SELECT 1 FROM rearm.artifact_sbom_components ascx
			        JOIN rearm.sbom_components sc ON sc.uuid = ascx.sbom_component_uuid
			        WHERE ascx.canonical_artifact_uuid = acm.canonical_artifact_uuid
			          AND ascx.org = acm.org
			          AND (sc.canonical_purl LIKE 'pkg:%' OR sc.canonical_purl LIKE 'cpe:%')
			          AND (sc.record_data->>'isRoot') IS DISTINCT FROM 'true'
			          AND sc.flow_control->>'enrichmentTerminalAt' IS NULL)
			ORDER BY a.created_date ASC
			LIMIT :lim
			""", nativeQuery = true)
	List<UUID> findNeverScannedMappedArtifactCanonicalsOlderThan(
			@Param("org") UUID org, @Param("cutoff") ZonedDateTime cutoff, @Param("lim") int lim);

	/**
	 * Stall diagnostic for {@code reportFanOutStallIfAny}: mapped artifacts in
	 * the SCANNING UNIVERSE whose {@code metrics.lastScanned} was never stamped,
	 * older than {@code cutoff}. In a healthy org this is zero shortly after
	 * upload; a persistent nonzero with unbucketed=0 and all buckets INGESTED is
	 * the candidate-starvation signature (2026-07-25).
	 *
	 * <p>Scoped to BOM-typed artifacts (TPF-114, the spelling of V67's partial index): the map
	 * already holds only internalBom-bearing artifacts, so this is the explicit scope, not a
	 * change of population on a healthy instance.
	 *
	 * <p>The matchable-component EXISTS keeps the universe of the coverage gate in
	 * {@code SyntheticSbomService.fanOutOrg} (the per-canonical loop): an artifact with no matchable
	 * (non-root pkg:/cpe:) components -- a metadata-only or empty-parse BOM -- is
	 * deliberately never a fan-out candidate and never stamped, so counting it
	 * here reports a permanent false stall. Seen immediately on the first
	 * deployment: four 2025-04-era artifacts with ZERO component rows re-logged
	 * as starved every interval. The two queries must keep the same universe or
	 * the detector diverges from what fan-out can actually do.
	 */
	@Query(value = """
			SELECT count(*)
			FROM rearm.artifact_canonical_map acm
			JOIN rearm.artifacts a ON a.uuid = acm.artifact_uuid
			WHERE acm.org = :org
			  AND a.record_data->>'org' = cast(:org as text)
			  AND a.record_data->>'type' = 'BOM'
			  AND a.created_date < :cutoff
			  AND cast(a.metrics->>'lastScanned' as float) IS NULL
			  AND EXISTS (
			        SELECT 1 FROM rearm.artifact_sbom_components ascx
			        JOIN rearm.sbom_components sc ON sc.uuid = ascx.sbom_component_uuid
			        WHERE ascx.canonical_artifact_uuid = acm.canonical_artifact_uuid
			          AND ascx.org = acm.org
			          AND (sc.canonical_purl LIKE 'pkg:%' OR sc.canonical_purl LIKE 'cpe:%')
			          AND (sc.record_data->>'isRoot') IS DISTINCT FROM 'true'
			          AND sc.flow_control->>'enrichmentTerminalAt' IS NULL)
			""", nativeQuery = true)
	long countNeverScannedMappedArtifactsOlderThan(
			@Param("org") UUID org, @Param("cutoff") ZonedDateTime cutoff);

	List<ArtifactCanonicalMap> findByOrgAndCanonicalArtifactUuid(UUID org, UUID canonicalArtifactUuid);
}
