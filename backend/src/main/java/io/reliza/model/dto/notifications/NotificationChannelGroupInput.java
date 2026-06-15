/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Typed write-input shape for {@code upsertNotificationChannelGroup}.
 * Mirrors the {@link NotificationChannelInput} precedent: the GraphQL
 * fetcher deserializes the incoming JSON map directly into this
 * record via {@code Utils.OM.convertValue}.
 *
 * <p>{@code channels} is the ordered list of member channel UUIDs.
 * Every UUID must reference an existing channel in the same org;
 * cross-org membership is rejected at save time. Duplicates within
 * a single {@code channels} list are also rejected — a user who
 * wants the same destination listed twice is almost certainly
 * confused; better to fail loud than silently dedup.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationChannelGroupInput(
        UUID uuid,
        // Optimistic-locking gate. See NotificationChannelInput.expectedRevision.
        Integer expectedRevision,
        UUID org,
        UUID resourceGroup,
        String name,
        List<UUID> channels) {
}
