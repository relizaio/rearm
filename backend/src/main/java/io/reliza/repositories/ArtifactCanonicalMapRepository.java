/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

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

	/** Projection for the canonical-form sweep pickup — org is needed to scope the component lookup. */
	interface PendingCanonicalForm {
		UUID getOrg();
		UUID getCanonicalArtifactUuid();
	}

	/**
	 * Canonical artifacts whose component mappings have not yet been verified
	 * against canonical-purl form {@code version}. Absent flow_control (every row
	 * predating V72) coalesces to 0, so the whole estate is picked up once and
	 * then drops out permanently — the range scan over
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

	// The joined findCanonicalArtifactsNeedingFanOut query lived here. Replaced
	// by the two-query split (ArtifactRepository.findFanOutPoolSlice + the
	// findByOrgAndArtifactUuidIn lookup below) after every joined formulation
	// left the planner an estate-sized scan to choose on large instances -- see
	// the pool-slice javadoc for the measurements. The caller's gate preserves
	// the EXISTS semantics (zero-matchable artifacts are skipped un-stamped).


	List<ArtifactCanonicalMap> findByOrgAndArtifactUuidIn(UUID org, Collection<UUID> artifactUuids);

	/**
	 * Reverse lookup: every artifact uuid in the org that points to the given
	 * canonical. Useful for surfacing "which uploads share this content"
	 * cross-release analytics.
	 */
	/** Sample for stall diagnostics: canonicals of the oldest never-scanned mapped artifacts. */
	@Query(value = """
			SELECT acm.canonical_artifact_uuid
			FROM rearm.artifact_canonical_map acm
			JOIN rearm.artifacts a ON a.uuid = acm.artifact_uuid
			WHERE acm.org = :org
			  AND a.record_data->>'org' = cast(:org as text)
			  AND a.created_date < :cutoff
			  AND cast(a.metrics->>'lastScanned' as float) IS NULL
			ORDER BY a.created_date ASC
			LIMIT :lim
			""", nativeQuery = true)
	List<UUID> findNeverScannedMappedArtifactCanonicalsOlderThan(
			@Param("org") UUID org, @Param("cutoff") java.time.ZonedDateTime cutoff, @Param("lim") int lim);

	/**
	 * Stall diagnostic for {@code reportFanOutStallIfAny}: mapped artifacts in
	 * the SCANNING UNIVERSE whose {@code metrics.lastScanned} was never stamped,
	 * older than {@code cutoff}. In a healthy org this is zero shortly after
	 * upload; a persistent nonzero with unbucketed=0 and all buckets INGESTED is
	 * the candidate-starvation signature (2026-07-25).
	 *
	 * <p>The matchable-component EXISTS mirrors the restriction in
	 * {@code findCanonicalArtifactsNeedingFanOut}: an artifact with no matchable
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
			@Param("org") UUID org, @Param("cutoff") java.time.ZonedDateTime cutoff);

	List<ArtifactCanonicalMap> findByOrgAndCanonicalArtifactUuid(UUID org, UUID canonicalArtifactUuid);
}
