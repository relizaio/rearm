/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.NotificationSubscriptionStatus;

/**
 * Typed accessor for the JSONB payload of a {@link NotificationSubscription}.
 * Mirrors {@link ApprovalPolicyData}'s relationship to {@link ApprovalPolicy}.
 *
 * <p>The subscription's contract with customer-authored expressions is:
 * the {@code filter.celExpression} runs against the activation map
 * produced by {@link io.reliza.service.EventActivationMapBuilder}.
 * PRESET-mode subscriptions are still stored with a CEL expression on
 * the row — the preset UI generates the CEL server-side at save time
 * (see Phase 3 of the design doc). The {@code presetConfig} JSONB
 * payload exists so the UI can re-render the same toggle state without
 * re-parsing the generated expression.
 *
 * <p>{@code routes} is the post-filter channel-selection table. Once the
 * filter evaluates true, each route is checked against the event's
 * severity / env / lifecycle facets and matching channels receive a
 * delivery row.
 *
 * <p>See {@code ai-plans/notifications/notifications-framework.md} §6.1.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationSubscriptionData(
        UUID org,
        UUID resourceGroup,
        String name,
        NotificationSubscriptionStatus status,
        List<NotificationEventType> eventTypes,
        FilterConfig filter,
        List<RouteConfig> routes,
        Integer dedupWindowMinutes,
        RateLimitConfig rateLimit) {

    public static final int DEFAULT_DEDUP_WINDOW_MINUTES = 1440;

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FilterConfig(
            EvaluationMode mode,
            Map<String, Object> presetConfig,
            String celExpression) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RouteConfig(
            NotificationSeverity whenSeverityAtLeast,
            List<String> andEnvIn,
            List<ReleaseLifecycle> andLifecycleIn,
            List<UUID> channels,
            List<UUID> perspectives,
            /*
             * Phase 13b — group-based channel expansion. At fan-out time
             * the gate-passing route's {@code channelGroups} list is
             * resolved to its member channel UUIDs (via
             * {@code NotificationChannelGroupService.resolveChannelUuids})
             * and merged with {@code channels}, dedup preserving
             * first-seen order. Empty / unset = no group expansion;
             * the route delivers only to its direct {@code channels}
             * list.
             *
             * <p><b>null is load-bearing</b> on pre-Phase-13b JSONB
             * rows that don't carry this key — the back-compat
             * constructor below defaults to null so existing routes
             * keep their "match channels only" semantics on read.
             * The {@code [[jackson-record-compact-ctor-pattern]]}
             * memory entry doesn't apply here: a compact constructor
             * normalising null → empty would silently change the
             * pre-13b read semantics. Treat null and empty identically
             * at the consumer site (the fan-out helper does).
             */
            List<UUID> channelGroups) {

        /**
         * Backwards-compat constructor for callers (tests, older
         * JSONB rows) that pre-date the {@code perspectives} field.
         * Defaults to null (= no perspective gate, match anything)
         * rather than empty list so existing routes keep their
         * "match everywhere" semantics on read.
         *
         * <p><b>Convention divergence note:</b> the parallel back-compat
         * constructor on {@link AffectedRelease} defaults its
         * {@code perspectives} to {@code Set.of()}, not null. Different
         * intent: {@code AffectedRelease.perspectives} is descriptive
         * (this release lives in these perspectives — empty set is the
         * honest default), whereas this field is a filter sentinel
         * (null = no gate). Do not normalise the two —
         * see notifications-framework.md §6.4.
         */
        public RouteConfig(NotificationSeverity whenSeverityAtLeast,
                List<String> andEnvIn,
                List<ReleaseLifecycle> andLifecycleIn,
                List<UUID> channels) {
            this(whenSeverityAtLeast, andEnvIn, andLifecycleIn, channels, null, null);
        }

        /**
         * Phase 12 back-compat constructor — for callers that pre-date
         * the Phase 13b {@code channelGroups} field but already pass
         * {@code perspectives}. Delegates to the canonical ctor with
         * {@code channelGroups} defaulted to null (= no group expansion).
         */
        public RouteConfig(NotificationSeverity whenSeverityAtLeast,
                List<String> andEnvIn,
                List<ReleaseLifecycle> andLifecycleIn,
                List<UUID> channels,
                List<UUID> perspectives) {
            this(whenSeverityAtLeast, andEnvIn, andLifecycleIn, channels, perspectives, null);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RateLimitConfig(
            Integer maxPerWindow,
            Integer windowMinutes) {
    }

    /** Effective dedup window (minutes). Falls back to default when unset. */
    public int effectiveDedupWindowMinutes() {
        return dedupWindowMinutes != null ? dedupWindowMinutes : DEFAULT_DEDUP_WINDOW_MINUTES;
    }
}
