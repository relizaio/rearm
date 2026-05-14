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
 * One row per in-edge between canonical components inside a single release.
 * Replaces the prior {@code release_sbom_components.parents} JSONB array.
 *
 * <p>Both ends are canonical {@code sbom_components} UUIDs (not
 * {@code release_sbom_components.uuid}). That keeps product-release merging
 * a UNION over many {@code release_uuid}s without any per-release UUID
 * remapping — the in-memory dedup key is naturally (source canonical,
 * target canonical, relationship, declaring artifact, exact purls).
 *
 * <p>Internal child row — no audit timestamps. Lifetime managed by the
 * parent release_sbom_components reconcile cycle.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "release_sbom_edges")
public class ReleaseSbomEdge implements Serializable {
	private static final long serialVersionUID = 234740L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private UUID releaseUuid;

	@Column(nullable = false)
	private UUID targetSbomComponentUuid;

	@Column(nullable = false)
	private UUID sourceSbomComponentUuid;

	@Column(nullable = false)
	private String relationshipType;

	/**
	 * Always a CANONICAL artifact UUID, resolved through
	 * {@code artifact_canonical_map} at reconcile time. Two uploads of the
	 * same BOM (content-identical, same REARM-scope digest, same org) both
	 * land here as the single canonical UUID, so cross-release "which
	 * uploads declared this edge" is a join on
	 * {@code artifact_canonical_map.canonical_artifact_uuid} — never a
	 * digest comparison. No code path in
	 * {@link io.reliza.service.SbomComponentService} writes a
	 * non-canonical UUID into this column.
	 */
	@Column(nullable = false)
	private UUID declaringArtifactUuid;

	/** NULL means source exact PURL equals source canonical PURL. */
	@Column
	private UUID sourceExactPurlUuid;

	/** NULL means target exact PURL equals target canonical PURL. */
	@Column
	private UUID targetExactPurlUuid;

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public UUID getReleaseUuid() { return releaseUuid; }
	public void setReleaseUuid(UUID releaseUuid) { this.releaseUuid = releaseUuid; }

	public UUID getTargetSbomComponentUuid() { return targetSbomComponentUuid; }
	public void setTargetSbomComponentUuid(UUID targetSbomComponentUuid) {
		this.targetSbomComponentUuid = targetSbomComponentUuid;
	}

	public UUID getSourceSbomComponentUuid() { return sourceSbomComponentUuid; }
	public void setSourceSbomComponentUuid(UUID sourceSbomComponentUuid) {
		this.sourceSbomComponentUuid = sourceSbomComponentUuid;
	}

	public String getRelationshipType() { return relationshipType; }
	public void setRelationshipType(String relationshipType) { this.relationshipType = relationshipType; }

	public UUID getDeclaringArtifactUuid() { return declaringArtifactUuid; }
	public void setDeclaringArtifactUuid(UUID declaringArtifactUuid) {
		this.declaringArtifactUuid = declaringArtifactUuid;
	}

	public UUID getSourceExactPurlUuid() { return sourceExactPurlUuid; }
	public void setSourceExactPurlUuid(UUID sourceExactPurlUuid) {
		this.sourceExactPurlUuid = sourceExactPurlUuid;
	}

	public UUID getTargetExactPurlUuid() { return targetExactPurlUuid; }
	public void setTargetExactPurlUuid(UUID targetExactPurlUuid) {
		this.targetExactPurlUuid = targetExactPurlUuid;
	}
}
