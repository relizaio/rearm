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
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.Type;

import io.hypersistence.utils.hibernate.type.json.JsonBinaryType;
import io.reliza.model.ModelProperties;
import io.reliza.model.RelizaEntity;

/**
 * One row per (outbox event × channel) attempt. Channel workers poll
 *   WHERE status='PENDING' AND next_attempt_at <= NOW()
 * picking up new deliveries and re-attempting failed ones whose
 * BackoffPolicy curve says it's time. After 7 failed attempts the row
 * moves to status=FAILED and stays there until an operator clicks
 * "retry" in the DLQ view.
 *
 * <p>Hybrid shape: queue-relevant columns explicit; payload digest,
 * delivery headers, and rendered diagnostic data live in record_data.
 *
 * <p>acked_at / snoozed_until are reserved here for the v2 inbox per §8.1
 * of the design doc and stay nullable in v1.
 *
 * <p><b>Concurrency:</b> {@link Version} on {@code revision} means Hibernate
 * owns the increment. Services MUST NOT call {@link #setRevision(int)}
 * with {@code getRevision()+1} — that's the legacy idiom for
 * non-{@code @Version} entities like {@code IntegrationService:367} or
 * {@code WebhookService:261}. Let JPA flush the bump.
 *
 * See ai-plans/notifications/notifications-framework.md §5 and §5.6.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "notification_deliveries")
public class NotificationDelivery implements Serializable, RelizaEntity {
	private static final long serialVersionUID = 234743L;

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

	@Column(nullable = false)
	private UUID org;

	@Column(nullable = false, name = "outbox_event_uuid")
	private UUID outboxEventUuid;

	/**
	 * Nullable for the Phase 2d channel-test path: a "Test channel"
	 * press bypasses subscription matching entirely, so the resulting
	 * delivery row has no subscription to point at. Real fan-out
	 * always writes a non-null value (every delivery on the real path
	 * is derived from a matching subscription's route table).
	 */
	@Column(name = "subscription_uuid")
	private UUID subscriptionUuid;

	/**
	 * Nullable for per-user targeted rows (approval-request events):
	 * those are written by the fan-out directly in SENT status with no
	 * channel routing, so there is no channel to point at. Channel
	 * workers never see them (they only poll PENDING rows).
	 */
	@Column(name = "channel_uuid")
	private UUID channelUuid;

	/**
	 * Set only on per-user targeted rows: the single user whose inbox
	 * this delivery is visible in, bypassing the perspective/org-admin
	 * visibility predicate. Null on subscription-routed deliveries.
	 */
	@Column(name = "target_user")
	private UUID targetUser;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private NotificationDeliveryStatus status = NotificationDeliveryStatus.PENDING;

	@Column(name = "dedup_key")
	private String dedupKey;

	@Column(nullable = false, name = "attempt_count")
	private int attemptCount = 0;

	@Column(nullable = false, name = "next_attempt_at")
	private ZonedDateTime nextAttemptAt = ZonedDateTime.now();

	/**
	 * Set when status transitions to SENT or ACKED. Dedup window anchors
	 * here, not on created_date — a delivery created day-0 but only
	 * successfully SENT on day-5 should anchor the next dedup probe at
	 * day-5.
	 */
	@Column(name = "sent_at")
	private ZonedDateTime sentAt;

	@Column(name = "last_error")
	private String lastError;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private NotificationDeliveryOrigin origin = NotificationDeliveryOrigin.REAL;

	@Column(name = "acked_at")
	private ZonedDateTime ackedAt;

	@Column(name = "snoozed_until")
	private ZonedDateTime snoozedUntil;

	@Type(JsonBinaryType.class)
	@Column(columnDefinition = ModelProperties.JSONB, nullable = false)
	private Map<String, Object> recordData = new java.util.HashMap<>();

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

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }
	public UUID getOutboxEventUuid() { return outboxEventUuid; }
	public void setOutboxEventUuid(UUID outboxEventUuid) { this.outboxEventUuid = outboxEventUuid; }
	public UUID getSubscriptionUuid() { return subscriptionUuid; }
	public void setSubscriptionUuid(UUID subscriptionUuid) { this.subscriptionUuid = subscriptionUuid; }
	public UUID getChannelUuid() { return channelUuid; }
	public void setChannelUuid(UUID channelUuid) { this.channelUuid = channelUuid; }
	public UUID getTargetUser() { return targetUser; }
	public void setTargetUser(UUID targetUser) { this.targetUser = targetUser; }
	public NotificationDeliveryStatus getStatus() { return status; }
	public void setStatus(NotificationDeliveryStatus status) { this.status = status; }
	public String getDedupKey() { return dedupKey; }
	public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
	public int getAttemptCount() { return attemptCount; }
	public void setAttemptCount(int attemptCount) { this.attemptCount = attemptCount; }
	public ZonedDateTime getNextAttemptAt() { return nextAttemptAt; }
	public void setNextAttemptAt(ZonedDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
	public ZonedDateTime getSentAt() { return sentAt; }
	public void setSentAt(ZonedDateTime sentAt) { this.sentAt = sentAt; }
	public String getLastError() { return lastError; }
	public void setLastError(String lastError) { this.lastError = lastError; }
	public NotificationDeliveryOrigin getOrigin() { return origin; }
	public void setOrigin(NotificationDeliveryOrigin origin) { this.origin = origin; }
	public ZonedDateTime getAckedAt() { return ackedAt; }
	public void setAckedAt(ZonedDateTime ackedAt) { this.ackedAt = ackedAt; }
	public ZonedDateTime getSnoozedUntil() { return snoozedUntil; }
	public void setSnoozedUntil(ZonedDateTime snoozedUntil) { this.snoozedUntil = snoozedUntil; }
}
