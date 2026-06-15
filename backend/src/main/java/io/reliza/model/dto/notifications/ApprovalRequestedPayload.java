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
 * when {@code eventType == APPROVAL_REQUESTED}.
 *
 * <p>{@code requestUuid} correlates with the {@code approvalRequests}
 * entry persisted on the release's record data; the matching
 * {@code APPROVAL_RESOLVED} event lists it in
 * {@link ApprovalResolvedPayload#resolvedRequestUuids()} once every
 * requested entry reaches a terminal state.
 *
 * <p>{@code targetUsers} is the produce-time snapshot of users who can
 * approve at least one of the requested entries (same coverage semantics
 * as the {@code releasesNeedingMyApproval} view). Fan-out writes one
 * per-user inbox delivery row per target in addition to any
 * subscription-matched channel deliveries. Point-in-time by design:
 * users granted the role later still see the release via the live
 * pending-approvals view, they just don't get this past event.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalRequestedPayload(
        ReleaseRef release,
        UUID requestUuid,
        UUID requestedBy,
        String requestedByName,
        String requestedByEmail,
        List<ApprovalRequestEntryRef> entries,
        List<UUID> targetUsers) {
}
