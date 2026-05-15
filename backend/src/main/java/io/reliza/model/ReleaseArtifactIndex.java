/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Many-to-many reverse index from a release to the canonical artifacts it
 * references. Rebuilt by {@code SbomComponentService.reconcileReleaseSbomComponents}
 * per release. Lets impact-analysis ("which releases contain this
 * sbom_component?") stay a 1-join read against {@code artifact_sbom_components}
 * instead of walking JSONB in {@code deliverables} / {@code source_code_entries}
 * / {@code releases}.
 *
 * <p>Internal data row — no audit columns or interface participation
 * beyond {@code Serializable}.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "release_artifact_index")
public class ReleaseArtifactIndex implements Serializable {
	private static final long serialVersionUID = 234743L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private UUID releaseUuid;

	@Column(nullable = false)
	private UUID canonicalArtifactUuid;

	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public UUID getReleaseUuid() { return releaseUuid; }
	public void setReleaseUuid(UUID releaseUuid) { this.releaseUuid = releaseUuid; }

	public UUID getCanonicalArtifactUuid() { return canonicalArtifactUuid; }
	public void setCanonicalArtifactUuid(UUID canonicalArtifactUuid) { this.canonicalArtifactUuid = canonicalArtifactUuid; }

	public ZonedDateTime getCreatedDate() { return createdDate; }
	public void setCreatedDate(ZonedDateTime createdDate) { this.createdDate = createdDate; }
}
