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
        RateLimitInput rateLimit,
        /*
         * Ownership matching scope -- see
         * NotificationSubscriptionData.ownedByTeam. Null = unscoped, which is
         * every subscription written before this field existed.
         */
        UUID ownedByTeam) {

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
             * Phase 12 perspective scoping. UUIDs of perspectives that
             * gate this route's deliveries: an event is delivered only
             * when the affected release touches at least one of these
             * perspectives. Null / empty = no perspective gate (match
             * anything) -- the fan-out gate (perspectiveGateMatches in
             * NotificationFanOutService) treats both shapes identically.
             */
            List<UUID> perspectives,
            /*
             * Phase 13b -- UUIDs of NotificationChannelGroups whose
             * members are merged with {@code channels} at fan-out time.
             * Null / empty = no group expansion (deliver to direct
             * channels only). Validated at save time by
             * NotificationSubscriptionService: every UUID must resolve
             * to a group in the same org as the subscription.
             */
            List<UUID> channelGroups,
            /*
             * T3 -- UUIDs of Teams (user groups) whose own
             * notificationChannels are merged into this route at fan-out
             * time. Null / empty = no team expansion. Resolved late, at
             * fan-out rather than save, so retargeting a team's channel
             * takes effect without editing every subscription naming it.
             */
            List<UUID> teams,
            /*
             * T4a -- when true, this route also delivers to the owner team of
             * every component the event affects, resolved at fan-out from
             * ComponentOwnershipService. Null / false = no owner expansion.
             *
             * Complements {@code teams} rather than replacing it: that names a
             * FIXED set, this follows whatever the T2 assignment rules currently
             * say. Nothing to validate at save time -- the flag references no
             * entity, and ownership is deliberately resolved late so a
             * reassignment takes effect without editing the subscription.
             */
            Boolean notifyComponentOwner) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RateLimitInput(
            Integer maxPerWindow,
            Integer windowMinutes) {
    }
}
