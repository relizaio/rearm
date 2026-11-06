/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.Type;
import org.springframework.data.domain.Persistable;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

/**
 * One immutable version of a release SBOM (FDAW-1 D1): the serial, the version, the artifact row and
 * the AS_UPLOADED sha256 of its bytes, and the content digest the next writer compares with. Built
 * through one constructor and never changed: {@link #isNew()} answers true until the row is
 * persisted or loaded, so a save always inserts and a second save of one (uuid, version) fails on the
 * primary key. The V108 trigger refuses every UPDATE and DELETE.
 */
@Entity
@IdClass(ReleaseSbomVersionId.class)
@Table(schema = ModelProperties.DB_SCHEMA, name = "release_sbom_versions")
public class ReleaseSbomVersion implements Serializable, Persistable<ReleaseSbomVersionId> {
	private static final long serialVersionUID = 20261105L;

	@Id
	private UUID uuid;

	@Id
	private int version;

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private UUID release;

	@Column(name = "profile_name", nullable = false)
	private String profileName;

	@Column(nullable = false)
	private UUID artifact;

	@Column(nullable = false)
	private String sha256;

	@Column(name = "content_digest", nullable = false)
	private String contentDigest;

	@Column(nullable = false)
	private ZonedDateTime createdDate;

	@Column
	private UUID createdBy;

	@Column(nullable = false)
	private int schemaVersion = 0;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String, Object> recordData;

	@Transient
	private transient boolean fresh = true;

	/** For JPA only. */
	protected ReleaseSbomVersion() {}

	/** The one way to build a version: every column from {@code d}, the JSONB record from its writer and recipe. */
	public ReleaseSbomVersion(ReleaseSbomVersionData d) {
		this.uuid = d.getUuid();
		this.version = d.getVersion();
		this.org = d.getOrg();
		this.release = d.getRelease();
		this.profileName = d.getProfileName();
		this.artifact = d.getArtifact();
		this.sha256 = d.getSha256();
		this.contentDigest = d.getContentDigest();
		this.createdDate = null == d.getCreatedDate() ? ZonedDateTime.now() : d.getCreatedDate();
		this.createdBy = d.getCreatedBy();
		this.recordData = d.toRecordData();
	}

	@PostLoad
	@PostPersist
	void markStored() {
		this.fresh = false;
	}

	@Override
	public ReleaseSbomVersionId getId() {
		return new ReleaseSbomVersionId(uuid, version);
	}

	@Override
	public boolean isNew() {
		return fresh;
	}

	public ReleaseSbomVersionData toData() {
		return ReleaseSbomVersionData.dataFromRecord(this);
	}

	public UUID getUuid() {
		return uuid;
	}

	public int getVersion() {
		return version;
	}

	public UUID getOrg() {
		return org;
	}

	public UUID getRelease() {
		return release;
	}

	public String getProfileName() {
		return profileName;
	}

	public UUID getArtifact() {
		return artifact;
	}

	public String getSha256() {
		return sha256;
	}

	public String getContentDigest() {
		return contentDigest;
	}

	public ZonedDateTime getCreatedDate() {
		return createdDate;
	}

	public UUID getCreatedBy() {
		return createdBy;
	}

	public int getSchemaVersion() {
		return schemaVersion;
	}

	public Map<String, Object> getRecordData() {
		return recordData;
	}
}
