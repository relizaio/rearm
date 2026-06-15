/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
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
import jakarta.persistence.Version;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import io.reliza.model.ModelProperties;
import io.reliza.model.RelizaEntity;

/**
 * Per-org rule that says "for events matching X, deliver to channels Y."
 * Pure-JSONB shape — mirrors ApprovalPolicy. Actual fields (org, status,
 * eventTypes, filter, routes, dedup/rate-limit config) live on
 * NotificationSubscriptionData; this class is the JPA shell.
 *
 * <p><b>Concurrency:</b> {@link Version} on {@code revision} means Hibernate
 * owns the increment. Services MUST NOT call {@link #setRevision(int)}
 * with {@code getRevision()+1} — that's the legacy idiom for
 * non-{@code @Version} entities. Let JPA flush the bump.
 *
 * See ai-plans/notifications/notifications-framework.md §6.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "notification_subscriptions")
public class NotificationSubscription implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 234740L;

	@Id
	private UUID uuid = UUID.randomUUID();

	@Version
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

	@Override public UUID getUuid() { return uuid; }
	public void setUuid(UUID uuid) { this.uuid = uuid; }
	@Override public int getRevision() { return revision; }
	public void setRevision(int revision) { this.revision = revision; }
	@Override public ZonedDateTime getCreatedDate() { return createdDate; }
	public void setCreatedDate(ZonedDateTime createdDate) { this.createdDate = createdDate; }
	@Override public ZonedDateTime getLastUpdatedDate() { return lastUpdatedDate; }
	public void setLastUpdatedDate(ZonedDateTime lastUpdatedDate) { this.lastUpdatedDate = lastUpdatedDate; }
	@Override public Map<String, Object> getRecordData() { return recordData; }
	@Override public void setRecordData(Map<String, Object> recordData) { this.recordData = recordData; }
	@Override public int getSchemaVersion() { return schemaVersion; }
	public void setSchemaVersion(int schemaVersion) { this.schemaVersion = schemaVersion; }
}
