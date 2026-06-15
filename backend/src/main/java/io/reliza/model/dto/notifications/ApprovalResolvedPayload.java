/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.NotificationOutboxEvent;

/**
 * Typed accessor for the JSONB payload on {@link NotificationOutboxEvent}
 * when {@code eventType == APPROVAL_RESOLVED}.
 *
 * <p>Emitted once per approval entry that reaches a terminal state in a
 * vote batch: satisfied (every requirement's approval count met) →
 * {@link Resolution#APPROVED}, or terminally disapproved
 * ({@code ApprovalEntryData.isDisapproved}) → {@link Resolution#DISAPPROVED}.
 *
 * <p>{@code resolvedRequestUuids} lists the persisted approval requests
 * (see {@link ApprovalRequestedPayload#requestUuid()}) whose entries are
 * ALL terminal after this vote batch. When a batch resolves several
 * entries at once, every event from that batch carries the same set —
 * the fan-out side's done-marking is idempotent, so the duplication is
 * harmless and avoids arbitrarily attributing the request to one entry.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalResolvedPayload(
        ReleaseRef release,
        UUID approvalEntryUuid,
        String approvalEntryName,
        Resolution resolution,
        UUID resolvedBy,
        String resolvedByName,
        String resolvedByEmail,
        List<UUID> resolvedRequestUuids) {

    public enum Resolution {
        APPROVED,
        DISAPPROVED;
    }
}
