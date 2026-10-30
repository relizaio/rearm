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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import io.reliza.service.TeaPublicationService.TeaPublicationState;

/**
 * The TEA publication head of one release (task TEA-5): mutable, one row per release. Its uuid
 * is the TEA release id and the TEA collection id. The key columns are written from the record on
 * every save and never edited on their own (the {@link TeaProfile} shape); the collection
 * versions live in {@link TeaPublishedCollection}.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "tea_publications")
public class TeaPublication implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 20261003L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private UUID release;

	@Column(nullable = false)
	private UUID component;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private TeaPublicationState state;

	@Column(nullable = false)
	private int latestVersion = 0;

	@Column(nullable = false)
	private UUID profile;

	@Column(nullable = false)
	private int profileRevision = 0;

	/** The product publication that published this release through its cascade; NULL for a direct one. */
	@Column
	private UUID cascadeOf;

	@Column(nullable = false)
	private int revision = 0;

	@Column(nullable = false)
	private int schemaVersion = 0;

	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	@Column(nullable = false)
	private ZonedDateTime lastUpdatedDate = ZonedDateTime.now();

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

	public UUID getOrg() {
		return org;
	}

	public void setOrg(UUID org) {
		this.org = org;
	}

	public UUID getRelease() {
		return release;
	}

	public void setRelease(UUID release) {
		this.release = release;
	}

	public UUID getComponent() {
		return component;
	}

	public void setComponent(UUID component) {
		this.component = component;
	}

	public TeaPublicationState getState() {
		return state;
	}

	public void setState(TeaPublicationState state) {
		this.state = state;
	}

	public int getLatestVersion() {
		return latestVersion;
	}

	public void setLatestVersion(int latestVersion) {
		this.latestVersion = latestVersion;
	}

	public UUID getProfile() {
		return profile;
	}

	public void setProfile(UUID profile) {
		this.profile = profile;
	}

	public int getProfileRevision() {
		return profileRevision;
	}

	public void setProfileRevision(int profileRevision) {
		this.profileRevision = profileRevision;
	}

	public UUID getCascadeOf() {
		return cascadeOf;
	}

	public void setCascadeOf(UUID cascadeOf) {
		this.cascadeOf = cascadeOf;
	}

	@Override
	public int getRevision() {
		return revision;
	}

	public void setRevision(int revision) {
		this.revision = revision;
	}

	@Override
	public int getSchemaVersion() {
		return schemaVersion;
	}

	public void setSchemaVersion(int schemaVersion) {
		this.schemaVersion = schemaVersion;
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

	@Override
	public Map<String, Object> getRecordData() {
		return recordData;
	}

	@Override
	public void setRecordData(Map<String, Object> recordData) {
		this.recordData = recordData;
	}
}
