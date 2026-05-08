/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import java.io.Serializable;
import java.time.ZonedDateTime;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

@Entity
// @DynamicUpdate so flushes only touch dirty columns. Same rationale as
// Release.java: the PR aggregator appends to event-array columns via
// @Modifying SQL inside the same transaction as record_data updates,
// and an all-columns flush from the in-memory snapshot would silently
// undo the SQL append.
@DynamicUpdate
@Table(schema = ModelProperties.DB_SCHEMA, name = "pull_requests")
public class PullRequest implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 234735;

	@Id
	private UUID uuid = UUID.randomUUID();

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

	/**
	 * Outbound validation events — one entry per dispatch the aggregator
	 * sent toward the SCM (e.g. GitHub check-run write). Keyed against
	 * the head SCE so reads can filter by current head. Initialised to
	 * an empty list so Hibernate's INSERT carries [] rather than NULL —
	 * the column is NOT NULL with a server-side default of '[]', but
	 * Hibernate explicitly writes whatever the field holds, which means
	 * a null field overrides the default.
	 */
	@Type(JsonBinaryType.class)
	@Column(name = "pr_validation_events", columnDefinition = ModelProperties.JSONB)
	private List<Map<String, Object>> prValidationEvents = new LinkedList<>();

	/**
	 * Inbound validation events — one entry per release attributed to
	 * this PR whose validation outcome (success/failure) the aggregator
	 * needs to fold into the next dispatch.
	 */
	@Type(JsonBinaryType.class)
	@Column(name = "release_validation_events", columnDefinition = ModelProperties.JSONB)
	private List<Map<String, Object>> releaseValidationEvents = new LinkedList<>();

	@Type(JsonBinaryType.class)
	@Column(name = "update_events", columnDefinition = ModelProperties.JSONB)
	private List<Map<String, Object>> updateEvents = new LinkedList<>();

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

	public List<Map<String, Object>> getPrValidationEvents() {
		return prValidationEvents;
	}

	public void setPrValidationEvents(List<Map<String, Object>> prValidationEvents) {
		this.prValidationEvents = prValidationEvents;
	}

	public List<Map<String, Object>> getReleaseValidationEvents() {
		return releaseValidationEvents;
	}

	public void setReleaseValidationEvents(List<Map<String, Object>> releaseValidationEvents) {
		this.releaseValidationEvents = releaseValidationEvents;
	}

	public List<Map<String, Object>> getUpdateEvents() {
		return updateEvents;
	}

	public void setUpdateEvents(List<Map<String, Object>> updateEvents) {
		this.updateEvents = updateEvents;
	}
}
