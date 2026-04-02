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

@Entity
@Table(schema=ModelProperties.DB_SCHEMA, name="metrics_audit")
public class MetricsAudit implements Serializable {
	private static final long serialVersionUID = 234735;

	public enum MetricsEntityType {
		RELEASE, ARTIFACT
	}

	@Id
	private UUID uuid = UUID.randomUUID();

	@Enumerated(EnumType.STRING)
	@Column(name = "entity_type", nullable = false)
	private MetricsEntityType entityType;

	@Column(name = "entity_uuid", nullable = false)
	private UUID entityUuid;

	@Column(name = "org", nullable = false)
	private UUID org;

	@Column(name = "metrics_revision", nullable = false)
	private int metricsRevision = 0;

	@Column(name = "revision_created_date", nullable = false)
	private ZonedDateTime revisionCreatedDate = ZonedDateTime.now();

	@Column(name = "entity_created_date", nullable = false)
	private ZonedDateTime entityCreatedDate;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String, Object> metrics;

	public UUID getUuid() {
		return uuid;
	}

	public void setUuid(UUID uuid) {
		this.uuid = uuid;
	}

	public MetricsEntityType getEntityType() {
		return entityType;
	}

	public void setEntityType(MetricsEntityType entityType) {
		this.entityType = entityType;
	}

	public UUID getEntityUuid() {
		return entityUuid;
	}

	public void setEntityUuid(UUID entityUuid) {
		this.entityUuid = entityUuid;
	}

	public UUID getOrg() {
		return org;
	}

	public void setOrg(UUID org) {
		this.org = org;
	}

	public int getMetricsRevision() {
		return metricsRevision;
	}

	public void setMetricsRevision(int metricsRevision) {
		this.metricsRevision = metricsRevision;
	}

	public ZonedDateTime getRevisionCreatedDate() {
		return revisionCreatedDate;
	}

	public void setRevisionCreatedDate(ZonedDateTime revisionCreatedDate) {
		this.revisionCreatedDate = revisionCreatedDate;
	}

	public ZonedDateTime getEntityCreatedDate() {
		return entityCreatedDate;
	}

	public void setEntityCreatedDate(ZonedDateTime entityCreatedDate) {
		this.entityCreatedDate = entityCreatedDate;
	}

	public Map<String, Object> getMetrics() {
		return metrics;
	}

	public void setMetrics(Map<String, Object> metrics) {
		this.metrics = metrics;
	}
}
