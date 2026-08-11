/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.data.repository.CrudRepository;

import io.reliza.model.ArtifactSbomComponent;

public interface ArtifactSbomComponentRepository extends CrudRepository<ArtifactSbomComponent, UUID> {

	List<ArtifactSbomComponent> findByOrgAndCanonicalArtifactUuid(UUID org, UUID canonicalArtifactUuid);

	List<ArtifactSbomComponent> findByOrgAndCanonicalArtifactUuidIn(UUID org, Collection<UUID> canonicalArtifactUuids);

	boolean existsByCanonicalArtifactUuid(UUID canonicalArtifactUuid);

	/**
	 * A representative artifact row for a canonical sbom_component — used by the
	 * enrichment puller to resolve a component back to one of the BOMs that
	 * declares it, so it can probe/pull that BOM's enrichment.
	 */
	Optional<ArtifactSbomComponent> findFirstByOrgAndSbomComponentUuid(UUID org, UUID sbomComponentUuid);

	/**
	 * Impact analysis: distinct release UUIDs that reference any canonical
	 * artifact containing any of the given sbom_components, via
	 * {@code release_artifact_index}.
	 */
	@Query("SELECT DISTINCT i.releaseUuid "
			+ "FROM ReleaseArtifactIndex i "
			+ "WHERE i.org = :org "
			+ "  AND i.canonicalArtifactUuid IN ("
			+ "    SELECT DISTINCT a.canonicalArtifactUuid "
			+ "    FROM ArtifactSbomComponent a "
			+ "    WHERE a.org = :org AND a.sbomComponentUuid IN :sbomComponentUuids)")
	List<UUID> findDistinctReleaseUuidsByOrgAndSbomComponentUuidIn(
			UUID org, Collection<UUID> sbomComponentUuids);

	/**
	 * Fan-out: distinct canonical artifacts that contain any of the given
	 * sbom_components. Maps back to the actual artifacts (via
	 * artifact_canonical_map) whose metrics get refreshed with the synthetic
	 * findings.
	 */
	@Query("SELECT DISTINCT a.canonicalArtifactUuid "
			+ "FROM ArtifactSbomComponent a "
			+ "WHERE a.org = :org AND a.sbomComponentUuid IN :sbomComponentUuids")
	List<UUID> findDistinctCanonicalArtifactUuidsByOrgAndSbomComponentUuidIn(
			UUID org, Collection<UUID> sbomComponentUuids);

	/**
	 * Fan-out candidate selection, server-side: distinct canonical artifacts
	 * containing any component whose sticky bucket is INGESTED. Replaces the
	 * two-step in-memory flow (covered purls → components → giant JDBC
	 * {@code IN (?,?,...)} of every covered component uuid in the org), whose
	 * bind-parameter explosion grew with org size and timed out on large
	 * instances, deterministically stalling that org's fan-out every tick.
	 * Bucket-membership granularity is a superset of the refMap purl set only
	 * in the sub-minute window between a membership change and its
	 * resubmission — and the per-artifact {@code containsAll} gate in
	 * fanOutOrg (still driven by the in-memory refMap purls) filters any such
	 * candidate, so semantics are unchanged. Driven by the V69 partial index
	 * on (org, synthetic_bucket_index).
	 */
	@Query(value = """
			SELECT DISTINCT ascx.canonical_artifact_uuid
			FROM rearm.synthetic_dtrack_bucket b
			JOIN rearm.sbom_components sc
			  ON sc.org = b.org AND sc.synthetic_bucket_index = b.bucket_index
			JOIN rearm.artifact_sbom_components ascx
			  ON ascx.sbom_component_uuid = sc.uuid
			WHERE b.org = :org
			  AND b.ingest_state = 'INGESTED'
			  AND ascx.org = :org
			""", nativeQuery = true)
	List<UUID> findDistinctCanonicalArtifactUuidsCoveredByIngestedBuckets(@Param("org") UUID org);
}
