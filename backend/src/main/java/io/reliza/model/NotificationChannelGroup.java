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
 * Named, cross-type collection of notification-destination {@code Integration}s — Phase 13.
 *
 * <p>A subscription route can reference a group instead of (or in
 * addition to) individual channels. At fan-out time the group is
 * expanded to its member channel UUIDs and merged with the route's
 * direct channels list. The group is a *naming* layer; each resolved
 * channel still produces its own delivery row with independent retry,
 * dedup, and audit.
 *
 * <p>Pure-JSONB shape, mirroring {@code Integration}. The
 * {@code @Version revision} field is owned by Hibernate — services
 * must not call {@code setRevision(getRevision()+1)}.
 *
 * <p>See ai-plans/notifications/notifications-framework.md §11.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "notification_channel_groups")
public class NotificationChannelGroup implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 234742L;

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
