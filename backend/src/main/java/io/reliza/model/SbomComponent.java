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
@Table(schema = ModelProperties.DB_SCHEMA, name = "sbom_components")
public class SbomComponent implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 234736L;

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
	private String canonicalPurl;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String, Object> recordData;

	// Flat {scheme,value} union of identity assertions (purl, cpe, swid, ...)
	// backing the generalized canonical identity. Nullable for pre-migration rows.
	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private List<ComponentIdentity> identities;

	// Declared licenses in the exact CycloneDX `licenses` array shape
	// ([{license:{id|name,...}} | {expression}]), re-emitted into synthetic BOMs
	// submitted to Dependency-Track. Stored as the raw CDX array (not the typed
	// LicenseChoice — see CdxLicenseUtil for why); materialized to a LicenseChoice
	// only at the emit boundary. Raw at parse time; overwritten in place with
	// BEAR-enriched licenses by the enrichment puller (see enrichedAt).
	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private List<Map<String, Object>> licenses;

	// When BEAR-enriched licenses were pulled for this canonical component
	// (null = not yet enriched). The synthetic Dependency-Track gate ships a
	// BEAR-configured org's component only once this is set, so DTrack receives
	// enriched licenses. Stamped once (fill-once) by the enrichment puller.
	@Column
	private ZonedDateTime enrichedAt;

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

	public UUID getOrg() {
		return org;
	}

	public void setOrg(UUID org) {
		this.org = org;
	}

	public String getCanonicalPurl() {
		return canonicalPurl;
	}

	public void setCanonicalPurl(String canonicalPurl) {
		this.canonicalPurl = canonicalPurl;
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

	public List<ComponentIdentity> getIdentities() {
		return identities;
	}

	public void setIdentities(List<ComponentIdentity> identities) {
		this.identities = identities;
	}

	public List<Map<String, Object>> getLicenses() {
		return licenses;
	}

	public void setLicenses(List<Map<String, Object>> licenses) {
		this.licenses = licenses;
	}

	public ZonedDateTime getEnrichedAt() {
		return enrichedAt;
	}

	public void setEnrichedAt(ZonedDateTime enrichedAt) {
		this.enrichedAt = enrichedAt;
	}
}
