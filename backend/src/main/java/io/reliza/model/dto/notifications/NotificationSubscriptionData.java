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
        RateLimitConfig rateLimit,
        /*
         * Ownership MATCHING scope. When set, this subscription matches an event
         * only if at least one component the event affects is owned by this team.
         *
         * <p>Deliberately not a route field, and deliberately not the same thing
         * as {@code RouteConfig.notifyComponentOwner}. That flag is a
         * DESTINATION -- "deliver to whoever owns the affected component", every
         * owner -- which is what an org-wide subscription wants. This is a
         * FILTER: "only events about MY components", which is what a single team
         * wants, and building the latter out of the former would deliver one
         * team's events to another. The destination for a team-scoped
         * subscription is the ordinary {@code teams} route target.
         *
         * <p>Resolution matches the owner-routing rules exactly, because it is
         * the same resolver: only OWNED / NON_DURABLE ownership counts, a
         * suggestion is not an owner, and an owner assigned by a T2 rule counts
         * the same as a stored one. An event affecting several components with
         * different owners matches for EACH owning team.
         *
         * <p><b>null is load-bearing</b>, as for the route fields: rows written
         * before this field existed do not carry the key, and null means "not
         * scoped" -- match on event type and filter alone. See
         * {@code ai-plans/team-owned-component-notifications.md} sec. 3.
         */
        UUID ownedByTeam,
        /*
         * Set when this row was MATERIALISED for a team rather than authored by
         * an operator -- the team's "notify me about my components" toggle owns
         * it, and TeamService keeps it in step.
         *
         * <p>Deliberately a separate field from {@code ownedByTeam} rather than
         * inferred from it. Scoping is available to anyone through the API, and
         * a hand-built scoped subscription is an ordinary subscription its
         * author expects to edit; treating every scoped row as team-managed
         * would lock them out of their own object. This field is the one that
         * answers "who is in charge of this row", and the CRUD guards key on it.
         *
         * <p>null = ordinary subscription, which is every row written before
         * this field existed.
         */
        UUID managedByTeam) {

    public static final int DEFAULT_DEDUP_WINDOW_MINUTES = 1440;

    /**
     * Back-compat constructor for callers that pre-date {@code ownedByTeam}.
     * Defaults it and {@code managedByTeam} to null (= unscoped, operator-owned),
     * so an existing subscription keeps matching exactly what it matched before.
     */
    public NotificationSubscriptionData(UUID org, UUID resourceGroup, String name,
            NotificationSubscriptionStatus status, List<NotificationEventType> eventTypes,
            FilterConfig filter, List<RouteConfig> routes, Integer dedupWindowMinutes,
            RateLimitConfig rateLimit) {
        this(org, resourceGroup, name, status, eventTypes, filter, routes,
                dedupWindowMinutes, rateLimit, null, null);
    }

    /**
     * Back-compat constructor for callers that pass {@code ownedByTeam} but
     * pre-date {@code managedByTeam} -- i.e. everything that scopes a
     * subscription by hand rather than through a team's toggle.
     */
    public NotificationSubscriptionData(UUID org, UUID resourceGroup, String name,
            NotificationSubscriptionStatus status, List<NotificationEventType> eventTypes,
            FilterConfig filter, List<RouteConfig> routes, Integer dedupWindowMinutes,
            RateLimitConfig rateLimit, UUID ownedByTeam) {
        this(org, resourceGroup, name, status, eventTypes, filter, routes,
                dedupWindowMinutes, rateLimit, ownedByTeam, null);
    }

    /** True when a team's toggle owns this row, not an operator. */
    public boolean isTeamManaged() {
        return null != managedByTeam;
    }

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
            List<UUID> channelGroups,
            /*
             * T3 -- team targeting. A route may name Teams (UserGroups);
             * at fan-out time each team's own {@code notificationChannels}
             * are resolved and merged into the route's channel set. Lets an
             * operator say "tell the Payments Team" without knowing which
             * Slack channel that is today, and keeps the answer correct when
             * the team later changes channel.
             *
             * <p><b>null is load-bearing</b> exactly as for
             * {@code channelGroups}: pre-T3 JSONB rows do not carry this key,
             * and the back-compat constructors below default it to null so
             * existing routes keep their "channels only" semantics on read.
             * Treat null and empty identically at the consumer site.
             *
             * <p>Scope note: this expands to team CHANNELS only. It does not
             * push rows into members' inboxes -- inbox visibility already has
             * its own component-team / perspective arms, and adding a third
             * targeting path would risk duplicate inbox rows for one event.
             */
            List<UUID> teams,
            /*
             * T4a -- owner-aware targeting. When true, the route additionally
             * delivers to the OWNER TEAM of every component the event affects,
             * resolved at fan-out from {@code ComponentOwnershipService}.
             *
             * <p>This is the piece that makes ownership actionable. {@code teams}
             * above names a FIXED set: an operator must know, and keep knowing,
             * which team owns what, and re-edit every subscription whenever a
             * T2 assignment rule moves a component. This flag says "whoever owns
             * the affected component, right now" -- so assigning owners by rule
             * is enough, and notifications follow automatically.
             *
             * <p>Only {@code OWNED} and {@code NON_DURABLE} ownership delivers.
             * A suggestion is not an owner, and {@code DEGRADED} means the owner
             * team is archived -- routing to either would notify teams that never
             * accepted the component. USER owners contribute no channel (a user
             * is not a team and has none); they are reached through the inbox
             * arms instead.
             *
             * <p><b>null is load-bearing</b> exactly as for {@code channelGroups}
             * and {@code teams}: pre-T4a JSONB rows do not carry this key, and the
             * back-compat constructors below default it to null so existing routes
             * keep their "no owner expansion" semantics on read. Treat null and
             * FALSE identically at the consumer site.
             */
            Boolean notifyComponentOwner) {

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
            this(whenSeverityAtLeast, andEnvIn, andLifecycleIn, channels, null, null, null, null);
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
            this(whenSeverityAtLeast, andEnvIn, andLifecycleIn, channels, perspectives, null, null, null);
        }

        /**
         * Phase 13b back-compat constructor -- for callers that pre-date the
         * T3 {@code teams} field but already pass {@code channelGroups}.
         * Defaults {@code teams} to null (= no team expansion).
         */
        public RouteConfig(NotificationSeverity whenSeverityAtLeast,
                List<String> andEnvIn,
                List<ReleaseLifecycle> andLifecycleIn,
                List<UUID> channels,
                List<UUID> perspectives,
                List<UUID> channelGroups) {
            this(whenSeverityAtLeast, andEnvIn, andLifecycleIn, channels, perspectives, channelGroups, null, null);
        }

        /**
         * T3 back-compat constructor -- for callers that pre-date the T4a
         * {@code notifyComponentOwner} flag but already pass {@code teams}.
         * Defaults the flag to null (= no owner expansion).
         */
        public RouteConfig(NotificationSeverity whenSeverityAtLeast,
                List<String> andEnvIn,
                List<ReleaseLifecycle> andLifecycleIn,
                List<UUID> channels,
                List<UUID> perspectives,
                List<UUID> channelGroups,
                List<UUID> teams) {
            this(whenSeverityAtLeast, andEnvIn, andLifecycleIn, channels, perspectives, channelGroups, teams, null);
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
