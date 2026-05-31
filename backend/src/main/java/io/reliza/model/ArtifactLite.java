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

import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

/**
 * Read-only view of {@link Artifact} that maps only the light columns needed to
 * build a totals-only {@link ArtifactData}. It omits the heavy {@code metrics}
 * jsonb (the per-finding detail arrays) and maps the generated
 * {@code metrics_totals} column (metrics with the detail arrays stripped)
 * instead, so loading an ArtifactLite never reads or deserializes those arrays.
 *
 * Mapped to the same {@code artifacts} table as {@link Artifact}; {@code @Immutable}
 * so Hibernate treats it as strictly read-only. Build an ArtifactData with
 * {@link ArtifactData#fromLite(ArtifactLite)}.
 */
@Entity
@Immutable
@Table(schema=ModelProperties.DB_SCHEMA, name="artifacts")
public class ArtifactLite implements Serializable {
	private static final long serialVersionUID = 234736;

	@Id
	private UUID uuid;

	@Column(nullable = false)
	private int schemaVersion = 0;

	@Column(nullable = false)
	private ZonedDateTime createdDate;

	@Column(nullable = false)
	private ZonedDateTime lastUpdatedDate;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String,Object> recordData;

	@Type(JsonBinaryType.class)
	@Column(name = "metrics_totals", columnDefinition = ModelProperties.JSONB, insertable = false, updatable = false)
	private Map<String,Object> metricsTotals;

	public UUID getUuid() {
		return uuid;
	}

	public int getSchemaVersion() {
		return schemaVersion;
	}

	public ZonedDateTime getCreatedDate() {
		return createdDate;
	}

	public ZonedDateTime getLastUpdatedDate() {
		return lastUpdatedDate;
	}

	public Map<String,Object> getRecordData() {
		return recordData;
	}

	public Map<String,Object> getMetricsTotals() {
		return metricsTotals;
	}
}
