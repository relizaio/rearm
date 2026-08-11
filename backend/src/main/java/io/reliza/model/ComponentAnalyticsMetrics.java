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

/**
 * One row per (org, component-or-product, date): finding COUNT rollups only —
 * no detail arrays (drill-downs recompute hot via
 * {@code AnalyticsMetricsService.getFindingsPerDayForComponent}). Backs the
 * most-vulnerable widget and PRODUCT-perspective findings-over-time as table
 * reads. Native columns (no record_data) — the row IS the numeric payload.
 */
@Entity
@Table(schema=ModelProperties.DB_SCHEMA, name="component_analytics_metrics")
public class ComponentAnalyticsMetrics implements Serializable {
	private static final long serialVersionUID = 234791;

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

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false)
	private UUID component;

	@Column(nullable = false)
	private String componentType;

	@Column(nullable = false)
	private String dateKey;

	@Type(JsonBinaryType.class)
	@Column(name = "numeric_metrics", columnDefinition = ModelProperties.JSONB, nullable = false)
	private Map<String, Object> numericMetrics;

	public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }
	public int getRevision() { return revision; }
	public int getSchemaVersion() { return schemaVersion; }
	public ZonedDateTime getCreatedDate() { return createdDate; }
	public ZonedDateTime getLastUpdatedDate() { return lastUpdatedDate; }
	public void setLastUpdatedDate(ZonedDateTime lastUpdatedDate) { this.lastUpdatedDate = lastUpdatedDate; }
	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }
	public UUID getComponent() { return component; }
	public void setComponent(UUID component) { this.component = component; }
	public String getComponentType() { return componentType; }
	public void setComponentType(String componentType) { this.componentType = componentType; }
	public String getDateKey() { return dateKey; }
	public void setDateKey(String dateKey) { this.dateKey = dateKey; }
	public Map<String, Object> getNumericMetrics() { return numericMetrics; }
	public void setNumericMetrics(Map<String, Object> numericMetrics) { this.numericMetrics = numericMetrics; }
}
