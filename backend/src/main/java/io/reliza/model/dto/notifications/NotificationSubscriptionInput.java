/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.NotificationSubscriptionStatus;

/**
 * Typed write-input shape for {@code upsertNotificationSubscription}.
 * Mirrors {@link NotificationSubscriptionData} 1:1 — the same
 * {@code FilterConfig} / {@code RouteConfig} / {@code RateLimitConfig}
 * shapes flow through, just declared on a separate "Input" record so
 * the GraphQL input + database-persisted shapes can evolve independently.
 *
 * <p>{@code presetConfigJson} is a JSON-stringified blob (rather than
 * a typed nested object) so the preset UI can iterate on its toggle
 * structure without forcing a schema bump per added field. The service
 * deserializes it back to a {@code Map<String, Object>} for the
 * {@link NotificationSubscriptionData.FilterConfig#presetConfig} slot.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationSubscriptionInput(
        UUID uuid,
        // Optimistic-locking gate. See NotificationChannelInput.expectedRevision.
        Integer expectedRevision,
        UUID org,
        UUID resourceGroup,
        String name,
        NotificationSubscriptionStatus status,
        List<NotificationEventType> eventTypes,
        FilterInput filter,
        List<RouteInput> routes,
        Integer dedupWindowMinutes,
        RateLimitInput rateLimit) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FilterInput(
            EvaluationMode mode,
            String presetConfigJson,
            String celExpression) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RouteInput(
            NotificationSeverity whenSeverityAtLeast,
            List<String> andEnvIn,
            List<ReleaseLifecycle> andLifecycleIn,
            List<UUID> channels,
            /*
             * Phase 13b — UUIDs of NotificationChannelGroups whose
             * members are merged with {@code channels} at fan-out time.
             * Null / empty = no group expansion (deliver to direct
             * channels only). Validated at save time by
             * NotificationSubscriptionService: every UUID must resolve
             * to a group in the same org as the subscription.
             */
            List<UUID> channelGroups) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RateLimitInput(
            Integer maxPerWindow,
            Integer windowMinutes) {
    }
}
