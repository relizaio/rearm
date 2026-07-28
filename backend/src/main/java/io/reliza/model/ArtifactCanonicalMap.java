/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.UUID;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Per-org pointer from a BOM-bearing {@code artifacts} row to its canonical
 * (content-identical) counterpart. Populated <em>lazily</em> during SBOM
 * reconcile — non-BOM artifacts never appear here.
 *
 * <p>An artifact that is the first occurrence of its content within the org
 * maps to itself ({@code canonical_artifact_uuid = artifact_uuid}); all
 * subsequent uploads with the same digest map to that first row. The
 * mapping is the single authoritative source for "which artifact UUID does
 * the SBOM aggregation use" — there's no implicit "missing row means
 * canonical" rule.
 *
 * <p>No foreign keys on the table by design. If the referenced canonical
 * artifact is later deleted, the SBOM service surfaces a "no data found"
 * outcome and log.warn's the dangling reference instead of cascading.
 * Keeps the map orthogonal to the artifacts table lifecycle.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "artifact_canonical_map")
public class ArtifactCanonicalMap implements Serializable {
	private static final long serialVersionUID = 234741L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private UUID artifactUuid;

	@Column(nullable = false)
	private UUID canonicalArtifactUuid;

	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	/**
	 * Per-artifact queue / sweep state. See {@link CanonicalMapFlowControl};
	 * the artifact-level counterpart to {@code releases.flow_control}.
	 */
	@Type(JsonBinaryType.class)
	@Column(name = "flow_control", columnDefinition = ModelProperties.JSONB)
	private CanonicalMapFlowControl flowControl;

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public UUID getArtifactUuid() { return artifactUuid; }
	public void setArtifactUuid(UUID artifactUuid) { this.artifactUuid = artifactUuid; }

	public CanonicalMapFlowControl getFlowControl() { return flowControl; }
	public void setFlowControl(CanonicalMapFlowControl flowControl) { this.flowControl = flowControl; }

	public UUID getCanonicalArtifactUuid() { return canonicalArtifactUuid; }
	public void setCanonicalArtifactUuid(UUID canonicalArtifactUuid) {
		this.canonicalArtifactUuid = canonicalArtifactUuid;
	}

	public ZonedDateTime getCreatedDate() { return createdDate; }
	public void setCreatedDate(ZonedDateTime createdDate) { this.createdDate = createdDate; }
}
