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
 * One row per emitted notification event waiting to be fanned-out by the
 * outbox worker. Producer inserts a row in the same transaction as the
 * business state change that triggered the event (see §4.3); the worker
 * scans WHERE status='PENDING' ORDER BY occurred_at LIMIT N under a
 * Postgres advisory lock.
 *
 * <p>Hybrid shape: status/org/event_type/dedup_key/occurred_at are columns
 * for the hot-path query; full event payload lives in record_data.
 *
 * <p><b>Concurrency:</b> {@link Version} on {@code revision} means Hibernate
 * owns the increment. Services MUST NOT call {@link #setRevision(int)}
 * with {@code getRevision()+1} — that's the legacy idiom for
 * non-{@code @Version} entities like {@code IntegrationService:367} or
 * {@code WebhookService:261}. Let JPA flush the bump.
 *
 * See ai-plans/notifications/notifications-framework.md §5.
 */
@Entity
@Table(schema = ModelProperties.DB_SCHEMA, name = "notification_outbox_events")
public class NotificationOutboxEvent implements Serializable, RelizaEntity {
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

	@Column(nullable = false)
	private UUID org;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, name = "event_type")
	private NotificationEventType eventType;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private NotificationOutboxStatus status = NotificationOutboxStatus.PENDING;

	@Column(name = "dedup_key")
	private String dedupKey;

	@Column(nullable = false, name = "occurred_at")
	private ZonedDateTime occurredAt = ZonedDateTime.now();

	@Enumerated(EnumType.STRING)
	@Column(nullable = false)
	private NotificationDeliveryOrigin origin = NotificationDeliveryOrigin.REAL;

	/**
	 * Phase 2d channel-test bypass marker. When non-null, the fan-out
	 * worker skips subscription / CEL / severity-gate evaluation and
	 * writes a single delivery row addressed at this channel uuid. NULL
	 * on every non-test event — set only by
	 * {@link io.reliza.service.SyntheticEventService#injectChannelTest}.
	 */
	@Column(name = "channel_test_target")
	private UUID channelTestTarget;

	/**
	 * How many times fan-out has looked at this event, found its affected
	 * releases still unresolvable, and deliberately put it back.
	 *
	 * <p>Named for enrichment specifically, NOT "attemptCount", even though the
	 * sibling {@code notification_deliveries.attempt_count} is spelled that way.
	 * The two mean opposite things: the delivery column counts FAILURES, while
	 * this one counts deliberate deferrals -- a throw out of fan-out marks the
	 * event FAILED and never reaches here. Sharing the generic name would invite
	 * a future transient-retry path to increment the same counter on a DB blip,
	 * after which the affected-release guard would read a number it did not
	 * write, believe its budget was spent, and suppress a real vulnerability
	 * event on the first look. One counter, one policy.
	 *
	 * <p>Bounded by {@code NotificationFanOutService.MAX_ENRICHMENT_ATTEMPTS}.
	 */
	@Column(nullable = false, name = "enrichment_attempt_count")
	private int enrichmentAttemptCount = 0;

	/**
	 * Eligibility gate for the drain: {@code findPendingBatch} skips rows whose
	 * {@code next_attempt_at} is still in the future. Defaults to now, so an
	 * ordinary event is eligible the instant it commits and the deferral
	 * machinery costs undeferred events nothing.
	 *
	 * <p>Unlike its {@code notification_deliveries} counterpart, this does NOT
	 * drive the drain's ordering -- the outbox keeps {@code ORDER BY
	 * occurred_at} because the approvals path depends on that FIFO. See the
	 * V76 migration comment.
	 */
	@Column(nullable = false, name = "next_attempt_at")
	private ZonedDateTime nextAttemptAt = ZonedDateTime.now();

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

	public UUID getOrg() { return org; }
	public void setOrg(UUID org) { this.org = org; }
	public NotificationEventType getEventType() { return eventType; }
	public void setEventType(NotificationEventType eventType) { this.eventType = eventType; }
	public NotificationOutboxStatus getStatus() { return status; }
	public void setStatus(NotificationOutboxStatus status) { this.status = status; }
	public String getDedupKey() { return dedupKey; }
	public void setDedupKey(String dedupKey) { this.dedupKey = dedupKey; }
	public ZonedDateTime getOccurredAt() { return occurredAt; }
	public void setOccurredAt(ZonedDateTime occurredAt) { this.occurredAt = occurredAt; }
	public NotificationDeliveryOrigin getOrigin() { return origin; }
	public void setOrigin(NotificationDeliveryOrigin origin) { this.origin = origin; }
	public UUID getChannelTestTarget() { return channelTestTarget; }
	public void setChannelTestTarget(UUID channelTestTarget) { this.channelTestTarget = channelTestTarget; }
	public int getEnrichmentAttemptCount() { return enrichmentAttemptCount; }
	public void setEnrichmentAttemptCount(int enrichmentAttemptCount) {
		this.enrichmentAttemptCount = enrichmentAttemptCount;
	}
	public ZonedDateTime getNextAttemptAt() { return nextAttemptAt; }
	public void setNextAttemptAt(ZonedDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }
}
