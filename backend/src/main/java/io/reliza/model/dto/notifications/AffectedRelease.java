/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.ReleaseData.ReleaseLifecycle;

/**
 * Per-release context attached to vuln-shaped notification events. Lifted
 * to a top-level record (instead of nested under {@link NewVulnAffectsReleasesPayload})
 * so multiple event payloads can reference the same shape without one
 * driving the other.
 *
 * <p>Field set is the public CEL surface for {@code event.affectedReleases[i]}
 * — adding fields is forward-compatible; renames/removals require the
 * dual-emit period from §13.2 of the design doc.
 *
 * <p>{@code perspectives} carries the union of the release's component's
 * perspective UUIDs (Phase 12 — see notifications-framework.md §6.4).
 * Route-level perspective gating intersects against this list, and CEL
 * filters can reference {@code event.affectedReleases[i].perspectives}.
 * Populated lazily during fan-out enrichment; producers may leave it
 * unset and {@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps
 * older payloads tolerant of the new field on read.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AffectedRelease(
        UUID uuid,
        String component,
        String version,
        String branch,
        ReleaseLifecycle lifecycle,
        List<String> deployedEnvs,
        Set<UUID> perspectives) {

    /**
     * Compact constructor — normalizes a null {@code perspectives} to
     * an empty set so downstream code (gate matcher, formatter, CEL
     * evaluator) can treat the field as always-present. This is the
     * shape Jackson's record-binding actually exercises when a legacy
     * JSONB payload arrives missing the {@code perspectives} key —
     * Jackson calls the canonical constructor with {@code null} for
     * the absent field rather than dispatching to a secondary
     * constructor, so the secondary form alone wouldn't survive a
     * round-trip from a pre-Phase-12 row.
     *
     * <p><b>Convention divergence note:</b> the parallel back-compat
     * constructor on {@link NotificationSubscriptionData.RouteConfig}
     * defaults its {@code perspectives} to {@code null}, not an empty
     * list. Different intent: a null route-perspectives list signals
     * "no gate, match anything" — that's the load-bearing sentinel the
     * gate reads. Here, an empty set is a non-sentinel default because
     * an event's perspectives are descriptive, not filter sentinels. Do
     * not normalise the two — see notifications-framework.md §6.4.
     */
    public AffectedRelease {
        if (perspectives == null) perspectives = Set.of();
    }

    /**
     * Backwards-compat secondary constructor for Java callers (tests +
     * older producers) that don't supply {@code perspectives}. Delegates
     * to the canonical constructor; the compact constructor above
     * handles the normalization.
     */
    public AffectedRelease(UUID uuid, String component, String version, String branch,
            ReleaseLifecycle lifecycle, List<String> deployedEnvs) {
        this(uuid, component, version, branch, lifecycle, deployedEnvs, Set.of());
    }
}
