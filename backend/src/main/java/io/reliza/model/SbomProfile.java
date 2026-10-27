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

/**
 * One SBOM profile row (task TEA-27). The key columns ({@code org}, {@code scope}, {@code object}, {@code name})
 * are written from the record on every save and never edited on their own; they exist so the
 * unique constraint on (org, scope, object, name) holds and the lookups need no jsonb operator.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "sbom_profiles")
public class SbomProfile implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 20261020L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private UUID org;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SettingsScope scope;

	/** NULL for the ORGANIZATION scope, the perspective or component uuid otherwise. */
	@Column(name = "object")
	private UUID objectUuid;

	/** The profile's name: channels name profiles, and a lower scope overrides a higher one by name. */
	@Column(nullable = false)
	private String name;

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

	public SettingsScope getScope() {
		return scope;
	}

	public void setScope(SettingsScope scope) {
		this.scope = scope;
	}

	public UUID getObjectUuid() {
		return objectUuid;
	}

	public void setObjectUuid(UUID objectUuid) {
		this.objectUuid = objectUuid;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
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
