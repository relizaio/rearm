/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One row per (release, canonical component, declaring artifact, exact PURL).
 * Replaces the prior {@code release_sbom_components.artifact_participations}
 * JSONB array. Keyed by canonical {@code sbom_component_uuid} (not by the
 * {@code release_sbom_components.uuid} anchor row) so that PRODUCT-release
 * read-time merging collapses to a single
 * {@code WHERE release_uuid IN (...) GROUP BY sbom_component_uuid} query.
 *
 * <p>Internal child row — no audit timestamps, no {@code RelizaEntity}
 * interface participation. Lifetime is fully managed by the parent
 * release_sbom_components row's reconcile cycle.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "release_sbom_component_artifacts")
public class ReleaseSbomComponentArtifact implements Serializable {
	private static final long serialVersionUID = 234739L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private UUID releaseUuid;

	@Column(nullable = false)
	private UUID sbomComponentUuid;

	/**
	 * Always a CANONICAL artifact UUID, resolved through
	 * {@code artifact_canonical_map} at reconcile time. Two uploads of the
	 * same BOM (content-identical, same REARM-scope digest, same org) both
	 * land here as the single canonical UUID. No code path in
	 * {@link io.reliza.service.SbomComponentService} writes a
	 * non-canonical UUID into this column.
	 */
	@Column(nullable = false)
	private UUID artifactUuid;

	/** NULL means exact PURL equals canonical PURL on the referenced sbom_components row. */
	@Column
	private UUID exactPurlUuid;

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public UUID getReleaseUuid() { return releaseUuid; }
	public void setReleaseUuid(UUID releaseUuid) { this.releaseUuid = releaseUuid; }

	public UUID getSbomComponentUuid() { return sbomComponentUuid; }
	public void setSbomComponentUuid(UUID sbomComponentUuid) { this.sbomComponentUuid = sbomComponentUuid; }

	public UUID getArtifactUuid() { return artifactUuid; }
	public void setArtifactUuid(UUID artifactUuid) { this.artifactUuid = artifactUuid; }

	public UUID getExactPurlUuid() { return exactPurlUuid; }
	public void setExactPurlUuid(UUID exactPurlUuid) { this.exactPurlUuid = exactPurlUuid; }
}
