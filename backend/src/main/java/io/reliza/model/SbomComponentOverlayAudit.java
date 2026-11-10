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
 * One overlay write: the after-image, who, why and under which bulk act (FDAW-15 3.1). Insert-only in three
 * layers: the service only inserts, the repository offers no update or delete, and the V116 trigger refuses
 * both from every other SQL path. {@code removed} marks the row of a removed description, which holds the
 * row's last after-image.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "sbom_component_overlay_audit")
public class SbomComponentOverlayAudit implements Serializable {
	private static final long serialVersionUID = 20261107L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private UUID overlay;

	@Column(nullable = false)
	private UUID org;

	@Column(name = "canonical_purl", nullable = false)
	private String canonicalPurl;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private OverlayKind kind;

	@Column(nullable = false)
	private int revision;

	@Type(JsonBinaryType.class)
	@Column(name = "record_data", columnDefinition = ModelProperties.JSONB, nullable = false)
	private SbomComponentOverlayData recordData;

	@Column(name = "asserted_by")
	private UUID assertedBy;

	@Column(name = "asserted_date", nullable = false)
	private ZonedDateTime assertedDate = ZonedDateTime.now();

	@Column
	private String reason;

	@Column(name = "bulk_act")
	private UUID bulkAct;

	@Column(nullable = false)
	private boolean removed = false;

	public UUID getUuid() { return uuid; }

	public UUID getOverlay() { return overlay; }
	public void setOverlay(UUID overlay) { this.overlay = overlay; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public String getCanonicalPurl() { return canonicalPurl; }
	public void setCanonicalPurl(String canonicalPurl) { this.canonicalPurl = canonicalPurl; }

	public OverlayKind getKind() { return kind; }
	public void setKind(OverlayKind kind) { this.kind = kind; }

	public int getRevision() { return revision; }
	public void setRevision(int revision) { this.revision = revision; }

	public SbomComponentOverlayData getRecordData() { return recordData; }
	public void setRecordData(SbomComponentOverlayData recordData) { this.recordData = recordData; }

	public UUID getAssertedBy() { return assertedBy; }
	public void setAssertedBy(UUID assertedBy) { this.assertedBy = assertedBy; }

	public ZonedDateTime getAssertedDate() { return assertedDate; }

	public String getReason() { return reason; }
	public void setReason(String reason) { this.reason = reason; }

	public UUID getBulkAct() { return bulkAct; }
	public void setBulkAct(UUID bulkAct) { this.bulkAct = bulkAct; }

	public boolean isRemoved() { return removed; }
	public void setRemoved(boolean removed) { this.removed = removed; }
}
