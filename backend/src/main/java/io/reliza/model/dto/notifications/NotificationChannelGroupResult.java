/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * GraphQL output shape for the Phase 13b
 * {@code notificationChannelGroup} / {@code notificationChannelGroups}
 * queries and the {@code upsertNotificationChannelGroup} mutation.
 *
 * <p>Pure-naming projection — no secrets, no per-type config, no
 * status. {@code channels} is the ordered list of member channel
 * UUIDs; the consumer (UI) issues a follow-up
 * {@code notificationChannels(orgUuid)} query to render each member's
 * name / type. revision / createdDate / lastUpdatedDate ride along so
 * the UI can show "last edited by whom, when" without a second
 * round-trip.
 *
 * <p>createdDate / lastUpdatedDate are stringified ISO-8601 so the
 * GraphQL surface stays consistent with the other notification-side
 * result types (NotificationDeliveryResult etc.) that render
 * timestamps as String rather than the schema's typed DateTime scalar.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationChannelGroupResult(
        UUID uuid,
        UUID org,
        UUID resourceGroup,
        String name,
        List<UUID> channels,
        Integer revision,
        String createdDate,
        String lastUpdatedDate) {
}
