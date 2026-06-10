/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.repositories;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
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
}
