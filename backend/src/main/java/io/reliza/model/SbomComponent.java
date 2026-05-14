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
import jakarta.persistence.Version;

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

	/** CycloneDX component type (library, application, framework, …). */
	@Column(name = "purl_type")
	private String purlType;

	/** Package group / namespace (e.g. Maven groupId, npm scope). */
	@Column(name = "pkg_group")
	private String pkgGroup;

	@Column
	private String name;

	@Column
	private String version;

	/**
	 * True when at least one uploaded BOM declared this component as its
	 * metadata.component (the "subject" of the BOM). Flips on once observed
	 * as root and stays on for the lifetime of the row.
	 */
	@Column(name = "is_root", nullable = false)
	private boolean isRoot = false;

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

	public String getCanonicalPurl() { return canonicalPurl; }
	public void setCanonicalPurl(String canonicalPurl) { this.canonicalPurl = canonicalPurl; }

	public String getPurlType() { return purlType; }
	public void setPurlType(String purlType) { this.purlType = purlType; }

	public String getPkgGroup() { return pkgGroup; }
	public void setPkgGroup(String pkgGroup) { this.pkgGroup = pkgGroup; }

	public String getName() { return name; }
	public void setName(String name) { this.name = name; }

	public String getVersion() { return version; }
	public void setVersion(String version) { this.version = version; }

	public boolean isRoot() { return isRoot; }
	public void setRoot(boolean root) { this.isRoot = root; }

	@Override
	public java.util.Map<String, Object> getRecordData() { return null; }

	@Override
	public void setRecordData(java.util.Map<String, Object> recordData) { /* no-op: sbom_components no longer carries a JSONB record_data column */ }

	@Override
	public int getSchemaVersion() { return schemaVersion; }
	public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
}
