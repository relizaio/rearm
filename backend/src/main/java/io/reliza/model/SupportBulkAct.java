/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
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
 * The receipt of one bulk support call (FDAW-1 F-6, WRITEUP 6.6, FDAW-14 3.1 item 3, 3.4): the "recorded
 * once" of a bulk claim. {@code uuid} is minted by the server before the loop and is the {@code bulkAct}
 * of every row the call wrote; a selector sweep's per-row audit rows carry it as {@code batch_id}. Inserted
 * after the loop with the final counts, so it is the receipt of a COMPLETED sweep: rows naming no receipt
 * are the trace of a sweep that did not finish. Insert-only in three layers (service, repository, V109
 * trigger). {@code selector} holds the canonical, percent-encoded namespace the query compared against.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "support_bulk_acts")
public class SupportBulkAct implements Serializable {
	private static final long serialVersionUID = 20261106L;

	@Id
	private UUID uuid;

	@Column(nullable = false)
	private UUID org;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private SupportBulkActKind kind;

	@Column
	private UUID release;

	@Column(name = "release_sbom")
	private UUID releaseSbom;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB, nullable = false)
	private Map<String, Object> selector = new HashMap<>();

	@Column(name = "matched_count", nullable = false)
	private int matchedCount;

	@Column(name = "applied_count", nullable = false)
	private int appliedCount;

	@Column(name = "list_hash", nullable = false)
	private String listHash;

	@Column(name = "asserted_by")
	private UUID assertedBy;

	@Column(name = "asserted_date", nullable = false)
	private ZonedDateTime assertedDate = ZonedDateTime.now();

	@Type(JsonBinaryType.class)
	@Column(name = "record_data", columnDefinition = ModelProperties.JSONB, nullable = false)
	private Map<String, Object> recordData = new HashMap<>();

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public SupportBulkActKind getKind() { return kind; }
	public void setKind(SupportBulkActKind kind) { this.kind = kind; }

	public UUID getRelease() { return release; }
	public void setRelease(UUID release) { this.release = release; }

	public UUID getReleaseSbom() { return releaseSbom; }
	public void setReleaseSbom(UUID releaseSbom) { this.releaseSbom = releaseSbom; }

	public Map<String, Object> getSelector() { return selector; }
	public void setSelector(Map<String, Object> selector) { this.selector = selector; }

	public int getMatchedCount() { return matchedCount; }
	public void setMatchedCount(int matchedCount) { this.matchedCount = matchedCount; }

	public int getAppliedCount() { return appliedCount; }
	public void setAppliedCount(int appliedCount) { this.appliedCount = appliedCount; }

	public String getListHash() { return listHash; }
	public void setListHash(String listHash) { this.listHash = listHash; }

	public UUID getAssertedBy() { return assertedBy; }
	public void setAssertedBy(UUID assertedBy) { this.assertedBy = assertedBy; }

	public ZonedDateTime getAssertedDate() { return assertedDate; }
	public void setAssertedDate(ZonedDateTime assertedDate) { this.assertedDate = assertedDate; }

	public Map<String, Object> getRecordData() { return recordData; }
	public void setRecordData(Map<String, Object> recordData) { this.recordData = recordData; }
}
