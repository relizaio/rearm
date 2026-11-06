/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;

import org.hibernate.annotations.Type;
import org.springframework.data.domain.Persistable;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

/**
 * The release SBOM head of one (release, SBOM profile name) (FDAW-1 D1): its uuid is the CycloneDX
 * serialNumber; {@code latestVersion}, {@code artifact} and {@code lastUpdatedDate} move on a bump.
 * {@link #isNew()} answers true until the row is persisted or loaded, so a new head is always an
 * INSERT (a mint never overwrites a head of the same uuid, FDAW-7 F-6) and a bump updates the head
 * that was loaded.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "release_sboms")
public class ReleaseSbom implements Serializable, Persistable<UUID> {
	private static final long serialVersionUID = 20261105L;

	@Id
	private UUID uuid;

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private UUID release;

	@Column(name = "profile_name", nullable = false)
	private String profileName;

	@Column(nullable = false)
	private UUID artifact;

	@Column(name = "latest_version", nullable = false)
	private int latestVersion;

	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	@Column(nullable = false)
	private ZonedDateTime lastUpdatedDate = ZonedDateTime.now();

	@Column(nullable = false)
	private int schemaVersion = 0;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String, Object> recordData = new HashMap<>();

	@Transient
	private transient boolean fresh = true;

	/** For JPA only. */
	protected ReleaseSbom() {}

	/** A new head; saved as an INSERT. */
	public ReleaseSbom(UUID uuid, UUID org, UUID release, String profileName, UUID artifact, int latestVersion) {
		this.uuid = uuid;
		this.org = org;
		this.release = release;
		this.profileName = profileName;
		this.artifact = artifact;
		this.latestVersion = latestVersion;
	}

	@PostLoad
	@PostPersist
	void markStored() {
		this.fresh = false;
	}

	@Override
	public UUID getId() {
		return uuid;
	}

	@Override
	public boolean isNew() {
		return fresh;
	}

	/** A bump of a loaded head: the new latest version and the artifact row that holds it. */
	public void advance(int version, UUID artifactUuid) {
		this.latestVersion = version;
		this.artifact = artifactUuid;
		this.lastUpdatedDate = ZonedDateTime.now();
	}

	public ReleaseSbomData toData() {
		return ReleaseSbomData.dataFromRecord(this);
	}

	public UUID getUuid() {
		return uuid;
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

	public int getLatestVersion() {
		return latestVersion;
	}

	public ZonedDateTime getCreatedDate() {
		return createdDate;
	}

	public ZonedDateTime getLastUpdatedDate() {
		return lastUpdatedDate;
	}

	public int getSchemaVersion() {
		return schemaVersion;
	}

	public Map<String, Object> getRecordData() {
		return recordData;
	}
}
