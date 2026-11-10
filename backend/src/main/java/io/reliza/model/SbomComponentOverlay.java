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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * The current overlay of one component key of an organization (FDAW-15 3.1). Mutable: {@code revision} is
 * moved by {@code SbomComponentOverlayService} on every change, monotonic per key across removals, and
 * every change files one insert-only {@link SbomComponentOverlayAudit}. No {@code @Version}: every writer
 * holds the per-key advisory lock.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "sbom_component_overlays")
public class SbomComponentOverlay implements Serializable {
	private static final long serialVersionUID = 20261107L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private UUID org;

	@Column(name = "canonical_purl", nullable = false)
	private String canonicalPurl;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OverlayKind kind;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OverlayProvenance provenance = OverlayProvenance.MANUAL;

	@Column(nullable = false)
	private int revision = 0;

	@Column(name = "created_date", nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	@Column(name = "last_updated_date", nullable = false)
	private ZonedDateTime lastUpdatedDate = ZonedDateTime.now();

	@Column(name = "schema_version", nullable = false)
	private int schemaVersion = 0;

	@Type(JsonBinaryType.class)
	@Column(name = "record_data", columnDefinition = ModelProperties.JSONB, nullable = false)
	private SbomComponentOverlayData recordData = SbomComponentOverlayData.empty();

	public UUID getUuid() { return uuid; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public String getCanonicalPurl() { return canonicalPurl; }
	public void setCanonicalPurl(String canonicalPurl) { this.canonicalPurl = canonicalPurl; }

	public OverlayKind getKind() { return kind; }
	public void setKind(OverlayKind kind) { this.kind = kind; }

	public OverlayProvenance getProvenance() { return provenance; }

	public int getRevision() { return revision; }
	public void setRevision(int revision) { this.revision = revision; }

	public ZonedDateTime getCreatedDate() { return createdDate; }

	public ZonedDateTime getLastUpdatedDate() { return lastUpdatedDate; }
	public void setLastUpdatedDate(ZonedDateTime lastUpdatedDate) { this.lastUpdatedDate = lastUpdatedDate; }

	public SbomComponentOverlayData getRecordData() { return recordData; }
	public void setRecordData(SbomComponentOverlayData recordData) { this.recordData = recordData; }
}
