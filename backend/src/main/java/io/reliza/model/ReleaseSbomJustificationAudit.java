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
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * One hash justification write of a release SBOM head: the after-image (FDAW-14 3.1 item 2). Insert-only
 * in three layers, the TEA-35 E3 rule: the service only inserts, the repository offers no update or
 * delete, and the V109 trigger refuses both from every other SQL path. {@code assertedDate} is the
 * system's record time of the write, the time every reader of "when was this justified" reads.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "release_sbom_justification_audit")
public class ReleaseSbomJustificationAudit implements Serializable {
	private static final long serialVersionUID = 20261106L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(name = "release_sbom", nullable = false)
	private UUID releaseSbom;

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private int revision;

	@Type(JsonBinaryType.class)
	@Column(name = "record_data", columnDefinition = ModelProperties.JSONB, nullable = false)
	private ReleaseSbomJustificationData recordData;

	@Column(name = "asserted_by")
	private UUID assertedBy;

	@Column(name = "asserted_date", nullable = false)
	private ZonedDateTime assertedDate = ZonedDateTime.now();

	@Column
	private String reason;

	@Column(name = "bulk_act")
	private UUID bulkAct;

	public UUID getUuid() { return uuid; }

	public UUID getReleaseSbom() { return releaseSbom; }
	public void setReleaseSbom(UUID releaseSbom) { this.releaseSbom = releaseSbom; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public int getRevision() { return revision; }
	public void setRevision(int revision) { this.revision = revision; }

	public ReleaseSbomJustificationData getRecordData() { return recordData; }
	public void setRecordData(ReleaseSbomJustificationData recordData) { this.recordData = recordData; }

	public UUID getAssertedBy() { return assertedBy; }
	public void setAssertedBy(UUID assertedBy) { this.assertedBy = assertedBy; }

	public ZonedDateTime getAssertedDate() { return assertedDate; }
	public void setAssertedDate(ZonedDateTime assertedDate) { this.assertedDate = assertedDate; }

	public String getReason() { return reason; }
	public void setReason(String reason) { this.reason = reason; }

	public UUID getBulkAct() { return bulkAct; }
	public void setBulkAct(UUID bulkAct) { this.bulkAct = bulkAct; }
}
