/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

/**
 * Submission-state bookkeeping for the synthetic Dependency-Track flow: one row
 * per (org, bucket_index). Bucket membership is the set of matchable
 * sbom_components whose sticky {@code syntheticBucketIndex} equals this row's
 * {@code bucketIndex} — this row only records the submission state, not the
 * components themselves.
 *
 * contentHash is sha256 over the sorted set of canonical_purls in the bucket;
 * a change means a component was added to (or removed from) THIS bucket and it
 * must be re-submitted. Because assignment is sticky, a change to one bucket
 * never perturbs another.
 * refMap translates generated bom-refs (c0,c1,... — many-to-one when CPE
 * companions are emitted) back to canonical_purl. findings holds the
 * last-ingested vuln/violation payload keyed by canonical_purl, stored as
 * plain JSON (never DTO records — JsonBinaryType cannot serialize those).
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "synthetic_dtrack_bucket")
public class SyntheticDtrackBucket implements Serializable {
	private static final long serialVersionUID = 234737L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Version
	@Column(nullable = false)
	private int revision = 0;

	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	@Column(nullable = false)
	private ZonedDateTime lastUpdatedDate = ZonedDateTime.now();

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private int bucketIndex;

	@Column
	private UUID dtrackProjectUuid;

	@Column
	private String contentHash;

	/** Submission/ingest lifecycle of a synthetic bucket. Persisted as text. */
	public enum IngestState {
		PENDING, SUBMITTED, INGESTED, FAILED;
	}

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private IngestState ingestState = IngestState.PENDING;

	// Initialized non-null: the DB columns are NOT NULL DEFAULT '{}', and Hibernate
	// inserts the field value explicitly (a null would violate the constraint and
	// lose the row — e.g. on the submit failure path before refMap is populated).
	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String, Object> refMap = new LinkedHashMap<>();

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String, Object> findings = new LinkedHashMap<>();

	@Column
	private ZonedDateTime lastSubmitted;

	@Column
	private ZonedDateTime lastIngested;

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }

	public int getRevision() { return revision; }
	public void setRevision(int revision) { this.revision = revision; }

	public ZonedDateTime getCreatedDate() { return createdDate; }
	public void setCreatedDate(ZonedDateTime createdDate) { this.createdDate = createdDate; }

	public ZonedDateTime getLastUpdatedDate() { return lastUpdatedDate; }
	public void setLastUpdatedDate(ZonedDateTime lastUpdatedDate) { this.lastUpdatedDate = lastUpdatedDate; }

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }

	public int getBucketIndex() { return bucketIndex; }
	public void setBucketIndex(int bucketIndex) { this.bucketIndex = bucketIndex; }

	public UUID getDtrackProjectUuid() { return dtrackProjectUuid; }
	public void setDtrackProjectUuid(UUID dtrackProjectUuid) { this.dtrackProjectUuid = dtrackProjectUuid; }

	public String getContentHash() { return contentHash; }
	public void setContentHash(String contentHash) { this.contentHash = contentHash; }

	public IngestState getIngestState() { return ingestState; }
	public void setIngestState(IngestState ingestState) { this.ingestState = ingestState; }

	public Map<String, Object> getRefMap() { return refMap; }
	public void setRefMap(Map<String, Object> refMap) { this.refMap = refMap; }

	public Map<String, Object> getFindings() { return findings; }
	public void setFindings(Map<String, Object> findings) { this.findings = findings; }

	public ZonedDateTime getLastSubmitted() { return lastSubmitted; }
	public void setLastSubmitted(ZonedDateTime lastSubmitted) { this.lastSubmitted = lastSubmitted; }

	public ZonedDateTime getLastIngested() { return lastIngested; }
	public void setLastIngested(ZonedDateTime lastIngested) { this.lastIngested = lastIngested; }
}
