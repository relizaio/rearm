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
import jakarta.persistence.Version;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

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
	private UUID releaseUuid;

	@Column(nullable = false)
	private UUID sbomComponentUuid;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB, nullable = false)
	private List<Map<String, Object>> artifactParticipations;

	/**
	 * In-edges for this component within the release: one entry per
	 * (sourceSbomComponentUuid, relationshipType) — i.e. "what depends on
	 * this component." Reverse of the more common BOM dependsOn direction;
	 * picked because the impact / vulnerability propagation query is the
	 * dominant lookup. See SbomComponentDataFetcher for forward-edge
	 * reconstruction.
	 */
	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB, nullable = false)
	private List<Map<String, Object>> parents;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String, Object> recordData;

	@Override
	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	@Override
	public int getRevision() {
		return revision;
	}

	public void setRevision(int revision) {
		this.revision = revision;
	}

	@Override
	public ZonedDateTime getCreatedDate() {
		return createdDate;
	}

	public void setCreatedDate(ZonedDateTime createdDate) {
		this.createdDate = createdDate;
	}

	@Override
	public ZonedDateTime getLastUpdatedDate() {
		return lastUpdatedDate;
	}

	public void setLastUpdatedDate(ZonedDateTime lastUpdatedDate) {
		this.lastUpdatedDate = lastUpdatedDate;
	}

	public UUID getReleaseUuid() {
		return releaseUuid;
	}

	public void setReleaseUuid(UUID releaseUuid) {
		this.releaseUuid = releaseUuid;
	}

	public UUID getSbomComponentUuid() {
		return sbomComponentUuid;
	}

	public void setSbomComponentUuid(UUID sbomComponentUuid) {
		this.sbomComponentUuid = sbomComponentUuid;
	}

	public List<Map<String, Object>> getArtifactParticipations() {
		return artifactParticipations;
	}

	public void setArtifactParticipations(List<Map<String, Object>> artifactParticipations) {
		this.artifactParticipations = artifactParticipations;
	}

	public List<Map<String, Object>> getParents() {
		return parents;
	}

	public void setParents(List<Map<String, Object>> parents) {
		this.parents = parents;
	}

	@Override
	public Map<String, Object> getRecordData() {
		return recordData;
	}

	@Override
	public void setRecordData(Map<String, Object> recordData) {
		this.recordData = recordData;
	}

	@Override
	public int getSchemaVersion() {
		return schemaVersion;
	}

	public void setSchemaVersion(int schemaVersion) {
		this.schemaVersion = schemaVersion;
	}
}
