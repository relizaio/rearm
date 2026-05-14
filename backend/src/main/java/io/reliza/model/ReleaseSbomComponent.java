/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;

@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "release_sbom_components")
public class ReleaseSbomComponent implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 234737L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Version
	@Column(nullable = false)
	private int revision = 0;

	@Column(nullable = false)
	private int schemaVersion = 0;

	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	@Column(nullable = false)
	private ZonedDateTime lastUpdatedDate = ZonedDateTime.now();

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private UUID releaseUuid;

	@Column(nullable = false)
	private UUID sbomComponentUuid;

	/**
	 * Per-artifact participations populated at read time from
	 * release_sbom_component_artifacts. Shape preserved for the GraphQL
	 * surface: list of {@code {artifact, exactPurls: [...]}} maps.
	 * Not persisted — the source of truth lives in the child table.
	 */
	@Transient
	private List<Map<String, Object>> artifactParticipations;

	/**
	 * In-edges (parents) populated at read time from release_sbom_edges.
	 * Shape preserved for the GraphQL surface: list of {@code
	 * {sourceSbomComponentUuid, sourceCanonicalPurl, relationshipType,
	 * declaringArtifacts: [...]}} maps. Not persisted.
	 */
	@Transient
	private List<Map<String, Object>> parents;

	@Override
	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	@Override
	public int getRevision() { return revision; }
	public void setRevision(int revision) { this.revision = revision; }

	@Override
	public ZonedDateTime getCreatedDate() { return createdDate; }
	public void setCreatedDate(ZonedDateTime createdDate) { this.createdDate = createdDate; }

	@Override
	public ZonedDateTime getLastUpdatedDate() { return lastUpdatedDate; }
	public void setLastUpdatedDate(ZonedDateTime lastUpdatedDate) { this.lastUpdatedDate = lastUpdatedDate; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public UUID getReleaseUuid() { return releaseUuid; }
	public void setReleaseUuid(UUID releaseUuid) { this.releaseUuid = releaseUuid; }

	public UUID getSbomComponentUuid() { return sbomComponentUuid; }
	public void setSbomComponentUuid(UUID sbomComponentUuid) { this.sbomComponentUuid = sbomComponentUuid; }

	public List<Map<String, Object>> getArtifactParticipations() { return artifactParticipations; }
	public void setArtifactParticipations(List<Map<String, Object>> artifactParticipations) {
		this.artifactParticipations = artifactParticipations;
	}

	public List<Map<String, Object>> getParents() { return parents; }
	public void setParents(List<Map<String, Object>> parents) { this.parents = parents; }

	@Override
	public Map<String, Object> getRecordData() { return null; }

	@Override
	public void setRecordData(Map<String, Object> recordData) { /* no-op: release_sbom_components no longer carries a JSONB record_data column */ }

	@Override
	public int getSchemaVersion() { return schemaVersion; }
	public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
}
