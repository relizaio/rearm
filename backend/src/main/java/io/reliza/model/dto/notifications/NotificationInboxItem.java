/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Phase 14 Inbox MVP GraphQL output shape — see
 * notifications-framework.md §8.1.
 *
 * <p>Sibling to {@link NotificationDeliveryResult}, NOT an extension. The
 * History (audit-log) projection stays on
 * {@code NotificationDeliveryResult}; this type carries the same
 * delivery core fields PLUS the inbox-specific projections:
 * <ul>
 *   <li>{@code readAt} — null when unread for the calling user, an
 *       ISO-8601 timestamp otherwise. Anchors to FIRST mark-as-read so
 *       the inbox can show "you saw this 3 days ago" without drifting
 *       on every re-render.</li>
 *   <li>{@code eventType} — lifted from the originating outbox event so
 *       a row can render the event kind without a separate lookup.</li>
 *   <li>{@code severity} — rolled up from the event payload. Null when
 *       the event type doesn't carry a severity (e.g. some VEX state
 *       changes).</li>
 *   <li>{@code title} / {@code description} — canonical human-rendered
 *       inbox text built by {@code NotificationInboxFormatter}. Mirrors
 *       what a user would see at the top of their Slack / email
 *       notification but stays channel-agnostic. Either can be null
 *       when the event payload is malformed.</li>
 *   <li>{@code payloadJson} — the originating outbox event's
 *       {@code recordData} serialized as JSON. Lets the UI deep-link
 *       into the affected release / vulnerability without an extra
 *       round-trip. Pre-serialized rather than nested-shape so the
 *       schema doesn't have to enumerate every event type's structured
 *       payload — UI parses on demand.</li>
 * </ul>
 *
 * <p>The split keeps two access patterns cleanly separate:
 * History is org-admin scoped audit; Inbox is the per-user
 * visibility-filtered triage queue. A future phase can promote shared
 * fields to a common interface if other inbox flavours show up.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationInboxItem(
        UUID uuid,
        UUID org,
        UUID outboxEventUuid,
        UUID subscriptionUuid,
        UUID channelUuid,
        String status,
        String origin,
        String dedupKey,
        Integer attemptCount,
        String nextAttemptAt,
        String sentAt,
        String lastError,
        String createdDate,
        String readAt,
        String eventType,
        String severity,
        String title,
        String description,
        String payloadJson) {
}
