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
 * Per-(user × delivery) mark-as-read state for the inbox MVP view.
 * Unique on (user_uuid, delivery_uuid) so a re-read is a no-op upsert.
 *
 * <p>No FOREIGN KEY constraints per coding_principles.md; service-layer
 * code is responsible for cleaning these up when a user or a delivery
 * is removed.
 *
 * <p>{@code record_data} is intentionally nullable on this junction table —
 * mark-as-read state is fully captured by the flat columns; the JSONB
 * column exists only for {@link RelizaEntity} interface conformance.
 *
 * <p><b>Concurrency:</b> {@link Version} on {@code revision} means Hibernate
 * owns the increment. Services MUST NOT call {@link #setRevision(int)}
 * with {@code getRevision()+1} — that's the legacy idiom for
 * non-{@code @Version} entities. Let JPA flush the bump.
 *
 * See ai-plans/notifications/notifications-framework.md §8.1.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "notification_reads")
public class NotificationRead implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 234744L;

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

	@Column(nullable = false, name = "user_uuid")
	private UUID userUuid;

	@Column(nullable = false, name = "delivery_uuid")
	private UUID deliveryUuid;

	@Column(nullable = false, name = "read_at")
	private ZonedDateTime readAt = ZonedDateTime.now();

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

	public UUID getUserUuid() { return userUuid; }
	public void setUserUuid(UUID userUuid) { this.userUuid = userUuid; }
	public UUID getDeliveryUuid() { return deliveryUuid; }
	public void setDeliveryUuid(UUID deliveryUuid) { this.deliveryUuid = deliveryUuid; }
	public ZonedDateTime getReadAt() { return readAt; }
	public void setReadAt(ZonedDateTime readAt) { this.readAt = readAt; }
}
