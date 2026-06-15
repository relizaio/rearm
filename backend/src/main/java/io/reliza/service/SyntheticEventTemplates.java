/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.util.List;
import java.util.UUID;

import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.notifications.AffectedComponent;
import io.reliza.model.dto.notifications.AffectedRelease;
import io.reliza.model.dto.notifications.NewVulnAffectsReleasesPayload;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.dto.notifications.VexStateChangedPayload;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload.ChangeType;

/**
 * Curated synthetic event payloads used by {@link SyntheticEventService}.
 * Each template exercises one interesting path through the rest of the
 * pipeline — different severities, different KEV/EPSS states, different
 * release counts, etc.
 *
 * <p>Templates live in code (not the DB) so they evolve with the event
 * schema. Adding a new template is a one-line registry update.
 *
 * <p>The {@code org} on the payload's {@link AffectedRelease} fields is
 * left as a randomly-generated UUID — the caller of
 * {@link SyntheticEventService#inject} supplies the *outer* org for the
 * outbox event; release UUIDs in the payload are illustrative and do
 * not need to resolve to real rows for the synthetic flow to work.
 */
public final class SyntheticEventTemplates {

    private SyntheticEventTemplates() {}

    /** Identifier of a template — stable across releases for replay. */
    public enum Template {
        /** Critical CVE affecting one shipped release. Headline use case. */
        CRITICAL_VULN_SINGLE_SHIPPED_RELEASE,
        /**
         * Critical CVE with KEV-listed=true carrying three affectedReleases
         * in the payload. Same number of outbox events / fan-out fan-outs
         * as the single-release template — exercises the channel formatter's
         * list-rendering path, not worker-level fan-out.
         */
        CRITICAL_KEV_VULN_THREE_RELEASES_IN_PAYLOAD,
        /** KEV-listed CVE on a draft release. */
        KEV_LISTED_DRAFT_RELEASE,
        /** Existing vuln whose severity jumped from MEDIUM to CRITICAL. */
        SEVERITY_BUMP_MEDIUM_TO_CRITICAL,
        /** Existing vuln whose CISA KEV listing was added today. */
        KEV_ADDED,
        /** VEX statement flipped from affected to not_affected. */
        VEX_RESOLVED_NOT_AFFECTED;

        // String constants per coding_principles.md — call sites that
        // need the wire form (GraphQL schema mirror, JSONB serialization)
        // import these instead of using .name(). A future rename here
        // breaks the constant's body, which fails the build at the call
        // site — silent drift via .name() is what we're avoiding.
        public static final String CRITICAL_VULN_SINGLE_SHIPPED_RELEASE_VALUE = "CRITICAL_VULN_SINGLE_SHIPPED_RELEASE";
        public static final String CRITICAL_KEV_VULN_THREE_RELEASES_IN_PAYLOAD_VALUE = "CRITICAL_KEV_VULN_THREE_RELEASES_IN_PAYLOAD";
        public static final String KEV_LISTED_DRAFT_RELEASE_VALUE = "KEV_LISTED_DRAFT_RELEASE";
        public static final String SEVERITY_BUMP_MEDIUM_TO_CRITICAL_VALUE = "SEVERITY_BUMP_MEDIUM_TO_CRITICAL";
        public static final String KEV_ADDED_VALUE = "KEV_ADDED";
        public static final String VEX_RESOLVED_NOT_AFFECTED_VALUE = "VEX_RESOLVED_NOT_AFFECTED";

        /**
         * Tolerant boundary parser. The GraphQL fetcher hands us the
         * schema-enum string; map to the matching Template constant
         * with an explicit error message on miss. Keeps parser logic in
         * the enum (not duplicated at call sites) per coding_principles.md.
         */
        public static Template parse(String raw) {
            if (raw == null) return null;
            return switch (raw) {
                case CRITICAL_VULN_SINGLE_SHIPPED_RELEASE_VALUE -> CRITICAL_VULN_SINGLE_SHIPPED_RELEASE;
                case CRITICAL_KEV_VULN_THREE_RELEASES_IN_PAYLOAD_VALUE -> CRITICAL_KEV_VULN_THREE_RELEASES_IN_PAYLOAD;
                case KEV_LISTED_DRAFT_RELEASE_VALUE -> KEV_LISTED_DRAFT_RELEASE;
                case SEVERITY_BUMP_MEDIUM_TO_CRITICAL_VALUE -> SEVERITY_BUMP_MEDIUM_TO_CRITICAL;
                case KEV_ADDED_VALUE -> KEV_ADDED;
                case VEX_RESOLVED_NOT_AFFECTED_VALUE -> VEX_RESOLVED_NOT_AFFECTED;
                default -> throw new IllegalArgumentException("Unknown synthetic event template: " + raw);
            };
        }
    }

    /** Returns the v1 event-type each template emits. */
    public static NotificationEventType eventTypeOf(Template template) {
        return switch (template) {
            case CRITICAL_VULN_SINGLE_SHIPPED_RELEASE,
                    CRITICAL_KEV_VULN_THREE_RELEASES_IN_PAYLOAD,
                    KEV_LISTED_DRAFT_RELEASE -> NotificationEventType.NEW_VULN_AFFECTS_RELEASES;
            case SEVERITY_BUMP_MEDIUM_TO_CRITICAL,
                    KEV_ADDED -> NotificationEventType.VULNERABILITY_RECORD_UPDATED;
            case VEX_RESOLVED_NOT_AFFECTED -> NotificationEventType.VEX_STATE_CHANGED;
        };
    }

    /**
     * Build the typed payload for a template. Caller serializes to JSONB
     * before storing on the outbox row.
     */
    public static Object payloadOf(Template template) {
        return switch (template) {
            case CRITICAL_VULN_SINGLE_SHIPPED_RELEASE -> new NewVulnAffectsReleasesPayload(
                    "CVE-2025-12345",
                    List.of("GHSA-aaaa-bbbb-cccc"),
                    9.8,
                    "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:H/I:H/A:H",
                    0.85,
                    false,
                    "2.17.1",
                    NotificationSeverity.CRITICAL,
                    new AffectedComponent(
                            "pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1",
                            "log4j-core",
                            "2.14.1"),
                    List.of(new AffectedRelease(
                            UUID.randomUUID(), "myapp", "v2.0", "main",
                            ReleaseLifecycle.GENERAL_AVAILABILITY,
                            List.of("prod-us"))));

            case CRITICAL_KEV_VULN_THREE_RELEASES_IN_PAYLOAD -> new NewVulnAffectsReleasesPayload(
                    "CVE-2025-67890",
                    List.of("GHSA-dddd-eeee-ffff"),
                    9.6,
                    "CVSS:3.1/AV:N/AC:L/PR:L/UI:N/S:U/C:H/I:H/A:H",
                    0.55,
                    true, // KEV-listed
                    "1.0.5",
                    NotificationSeverity.CRITICAL,
                    new AffectedComponent(
                            "pkg:npm/lodash@4.17.20",
                            "lodash",
                            "4.17.20"),
                    List.of(
                            new AffectedRelease(UUID.randomUUID(), "frontend",
                                    "v3.1", "main", ReleaseLifecycle.GENERAL_AVAILABILITY,
                                    List.of("prod-us", "prod-eu")),
                            new AffectedRelease(UUID.randomUUID(), "frontend",
                                    "v3.0", "main", ReleaseLifecycle.GENERAL_AVAILABILITY,
                                    List.of("prod-us")),
                            new AffectedRelease(UUID.randomUUID(), "admin-tools",
                                    "v0.9", "main", ReleaseLifecycle.READY_TO_SHIP,
                                    List.of())));

            case KEV_LISTED_DRAFT_RELEASE -> new NewVulnAffectsReleasesPayload(
                    "CVE-2025-11111",
                    List.of(),
                    7.5,
                    "CVSS:3.1/AV:N/AC:L/PR:N/UI:N/S:U/C:N/I:N/A:H",
                    0.95,
                    true, // KEV-listed
                    null,
                    NotificationSeverity.HIGH,
                    new AffectedComponent(
                            "pkg:pypi/requests@2.28.0",
                            "requests",
                            "2.28.0"),
                    List.of(new AffectedRelease(
                            UUID.randomUUID(), "data-pipeline", "v0.2", "feature/refactor",
                            ReleaseLifecycle.DRAFT,
                            List.of())));

            case SEVERITY_BUMP_MEDIUM_TO_CRITICAL -> new VulnerabilityRecordUpdatedPayload(
                    "CVE-2025-22222",
                    ChangeType.SEVERITY_BUMPED,
                    NotificationSeverity.MEDIUM,
                    NotificationSeverity.CRITICAL,
                    0.10,
                    0.10,
                    false,
                    List.of(new AffectedRelease(
                            UUID.randomUUID(), "backend", "v4.5", "main",
                            ReleaseLifecycle.GENERAL_AVAILABILITY,
                            List.of("prod-us"))));

            case KEV_ADDED -> new VulnerabilityRecordUpdatedPayload(
                    "CVE-2025-33333",
                    ChangeType.KEV_ADDED,
                    NotificationSeverity.HIGH,
                    NotificationSeverity.HIGH,
                    0.20,
                    0.20,
                    true,
                    List.of(new AffectedRelease(
                            UUID.randomUUID(), "api-gateway", "v1.2", "main",
                            ReleaseLifecycle.GENERAL_AVAILABILITY,
                            List.of("prod-us", "prod-eu"))));

            case VEX_RESOLVED_NOT_AFFECTED -> new VexStateChangedPayload(
                    "CVE-2025-44444",
                    UUID.randomUUID(),
                    "pkg:maven/com.fasterxml.jackson.core/jackson-databind@2.13.0",
                    "affected",
                    "not_affected");
        };
    }
}
