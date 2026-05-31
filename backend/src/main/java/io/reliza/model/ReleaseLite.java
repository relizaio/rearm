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
 * Read-only view of {@link Release} that maps only the light columns needed to
 * build a totals-only {@link ReleaseData}. It deliberately omits the heavy
 * {@code metrics} jsonb (carrying the per-finding detail arrays) as well as
 * {@code approval_events} and {@code update_events}; instead it maps the
 * generated {@code metrics_totals} column (metrics with the detail arrays
 * stripped). Because Hibernate only SELECTs the columns an entity maps, loading
 * a ReleaseLite never reads or deserializes those large arrays.
 *
 * Mapped to the same {@code releases} table as {@link Release}; {@code @Immutable}
 * so Hibernate treats it as strictly read-only (no dirty-checking or writes).
 * Use it via the totals-only read methods; build a ReleaseData with
 * {@link ReleaseData#fromLite(ReleaseLite)}.
 */
@Entity
@Immutable
@Table(schema=ModelProperties.DB_SCHEMA, name="releases")
public class ReleaseLite implements Serializable {
	private static final long serialVersionUID = 234735;

	@Id
	private UUID uuid;

	@Column(nullable = false)
	private int schemaVersion = 0;

	@Column(nullable = false)
	private ZonedDateTime createdDate;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String,Object> recordData;

	@Type(JsonBinaryType.class)
	@Column(name = "metrics_totals", columnDefinition = ModelProperties.JSONB, insertable = false, updatable = false)
	private Map<String,Object> metricsTotals;

	@Type(JsonBinaryType.class)
	@Column(name = "flow_control", columnDefinition = ModelProperties.JSONB)
	private FlowControl flowControl;

	public UUID getUuid() {
		return uuid;
	}

	public int getSchemaVersion() {
		return schemaVersion;
	}

	public ZonedDateTime getCreatedDate() {
		return createdDate;
	}

	public Map<String,Object> getRecordData() {
		return recordData;
	}

	public Map<String,Object> getMetricsTotals() {
		return metricsTotals;
	}

	public FlowControl getFlowControl() {
		return flowControl;
	}
}
