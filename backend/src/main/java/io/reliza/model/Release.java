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

import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.List;
import com.fasterxml.jackson.core.JsonProcessingException;

import io.reliza.common.Utils;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Entity
// @DynamicUpdate so flushes only touch dirty columns. Without it, every
// release UPDATE rewrites every column from the in-memory entity snapshot —
// which silently overwrites flow_control whenever an artifact mutation calls
// markSbomReconcileRequested via @Modifying SQL inside the same transaction
// as ossReleaseService.saveRelease (the in-memory flow_control is still
// NULL from when the entity was loaded, and the all-columns flush wins
// over the SQL UPDATE).
@DynamicUpdate
@Table(schema=ModelProperties.DB_SCHEMA, name="releases")
public class Release implements Serializable, RelizaEntity {
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

	@Column(name = "metrics_revision", nullable = false)
	private int metricsRevision = 0;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB)
	private Map<String,Object> metrics;

	@Type(JsonBinaryType.class)
	@Column(name = "approval_events", columnDefinition = ModelProperties.JSONB)
	private List<Map<String,Object>> approvalEvents;

	@Type(JsonBinaryType.class)
	@Column(name = "update_events", columnDefinition = ModelProperties.JSONB)
	private List<Map<String,Object>> updateEvents;

	/**
	 * Per-release scheduling state for async flows. Today's only consumer is
	 * the SBOM-component reconcile queue. See {@link FlowControl}.
	 */
	@Type(JsonBinaryType.class)
	@Column(name = "flow_control", columnDefinition = ModelProperties.JSONB)
	private FlowControl flowControl;

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

	public int getMetricsRevision() {
		return metricsRevision;
	}

	public void setMetricsRevision(int metricsRevision) {
		this.metricsRevision = metricsRevision;
	}

	public Map<String,Object> getMetrics() {
		return metrics;
	}

	public void setMetrics(Map<String,Object> metrics) {
		this.metrics = metrics;
	}

	public List<Map<String,Object>> getApprovalEvents() {
		return approvalEvents;
	}

	public void setApprovalEvents(List<Map<String,Object>> approvalEvents) {
		this.approvalEvents = approvalEvents;
	}

	public List<Map<String,Object>> getUpdateEvents() {
		return updateEvents;
	}

	public void setUpdateEvents(List<Map<String,Object>> updateEvents) {
		this.updateEvents = updateEvents;
	}

	public FlowControl getFlowControl() {
		return flowControl;
	}

	public void setFlowControl(FlowControl flowControl) {
		this.flowControl = flowControl;
	}

	@Override
	public int getSchemaVersion() {
		return schemaVersion;
	}

	public void setSchemaVersion(int schemaVersion) {
		this.schemaVersion = schemaVersion;
	}
	
	@JsonIgnore
	public String getRawJson() {
		try {
			return Utils.OM.writeValueAsString(this.recordData);
		} catch (JsonProcessingException e) {
			log.error("Error parsing record data for release into string", e);
			return null;
		}
	}
	
	
}
