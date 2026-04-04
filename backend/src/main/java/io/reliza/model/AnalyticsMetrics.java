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
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

@Entity
@Table(schema=ModelProperties.DB_SCHEMA, name="analytics_metrics")
public class AnalyticsMetrics implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 234734;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Column(nullable = false)
	private int revision=0;

	@Column(nullable = false)
	private int schemaVersion=0;

	@Column(nullable = false)
	private ZonedDateTime createdDate = ZonedDateTime.now();

	@Column(nullable = false)
	private ZonedDateTime lastUpdatedDate = ZonedDateTime.now();

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String,Object> recordData;

	@Column(nullable = false)
	private UUID org;

	@Column(name = "date_key", nullable = false)
	private String dateKey;

	@Column(nullable = true)
	private UUID perspective;

	@Type(JsonBinaryType.class)
	@Column(name = "numeric_metrics", columnDefinition = ModelProperties.JSONB)
	private Map<String, Object> numericMetrics;

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

	public UUID getOrg() {
		return org;
	}

	public void setOrg(UUID org) {
		this.org = org;
	}

	public String getDateKey() {
		return dateKey;
	}

	public void setDateKey(String dateKey) {
		this.dateKey = dateKey;
	}

	public UUID getPerspective() {
		return perspective;
	}

	public void setPerspective(UUID perspective) {
		this.perspective = perspective;
	}

	public Map<String, Object> getNumericMetrics() {
		return numericMetrics;
	}

	public void setNumericMetrics(Map<String, Object> numericMetrics) {
		this.numericMetrics = numericMetrics;
	}
}
