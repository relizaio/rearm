/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import org.springframework.stereotype.Component;

import io.reliza.common.Utils;
import io.reliza.model.dto.notifications.ApprovalRequestEntryRef;
import io.reliza.model.dto.notifications.ApprovalRequestedPayload;
import io.reliza.model.dto.notifications.ApprovalResolvedPayload;
import io.reliza.model.dto.notifications.NewVulnAffectsReleasesPayload;
import io.reliza.model.dto.notifications.ReleaseBomDiffPayload;
import io.reliza.model.dto.notifications.ReleaseCreatedPayload;
import io.reliza.model.dto.notifications.ReleaseLifecycleChangedPayload;
import io.reliza.model.dto.notifications.ReleaseRef;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.dto.notifications.VexStateChangedPayload;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload;
import lombok.extern.slf4j.Slf4j;

/**
 * Render an outbox event into a human-readable inbox row — a one-line
 * {@code title} plus a short {@code description}. Read at inbox-fetch
 * time (not produce-time): the channel-specific dispatchers
 * (Slack/Email/Teams/Sentinel) keep their own bespoke rendering for
 * channel-native niceties (Slack emoji, email subject conventions);
 * this formatter is the canonical inbox rendering and lives next to the
 * fetcher that consumes it.
 *
 * <p>One render method per {@link NotificationEventType}. Adding a new
 * event type requires a new case in {@link #format} — the exhaustive
 * switch makes it a compile-time error to forget, so the inbox can't
 * silently ship an "untitled" row.
 *
 * <p>Resilience: every parse path is wrapped — a malformed JSONB
 * payload falls back to an event-type-derived title and a null
 * description rather than 500-ing the inbox query. The fan-out worker
 * already tolerates malformed payloads via the same Utils.OM read; we
 * mirror that here so the operator still sees the row in their inbox
 * (with reduced detail) and can investigate.
 */
@Component
@Slf4j
public class NotificationInboxFormatter {

    private final NotificationLabelProvider labels;

    public NotificationInboxFormatter(NotificationLabelProvider labels) {
        this.labels = labels;
    }

    /**
     * Compact rendered inbox cell — title + short description. Either
     * field can be null when the event's payload doesn't carry enough
     * to populate it (e.g. a VEX state change where the producer
     * didn't capture a component PURL).
     */
    public record InboxRendering(String title, String description) {
        public static final InboxRendering EMPTY = new InboxRendering(null, null);
    }

    public InboxRendering format(NotificationOutboxEvent event) {
        if (event == null || event.getEventType() == null) return InboxRendering.EMPTY;
        try {
            return switch (event.getEventType()) {
                case NEW_VULN_AFFECTS_RELEASES -> renderNewVulnAffectsReleases(event);
                case VULNERABILITY_RECORD_UPDATED -> renderVulnRecordUpdated(event);
                case VEX_STATE_CHANGED -> renderVexStateChanged(event);
                case RELEASE_CREATED -> renderReleaseCreated(event);
                case RELEASE_LIFECYCLE_CHANGED -> renderReleaseLifecycleChanged(event);
                case RELEASE_BOM_DIFF -> renderReleaseBomDiff(event);
                case APPROVAL_REQUESTED -> renderApprovalRequested(event);
                case APPROVAL_RESOLVED -> renderApprovalResolved(event);
            };
        } catch (Exception e) {
            // Defence in depth: never let a single malformed event break
            // the page's inbox render. Log + fall back to a label-only
            // row so the operator at least sees the event type.
            log.warn("Inbox rendering failed for outbox event {} (type={}): {}",
                    event.getUuid(), event.getEventType(), e.getMessage());
            return new InboxRendering(event.getEventType().name(), null);
        }
    }

    private InboxRendering renderNewVulnAffectsReleases(NotificationOutboxEvent event) {
        NewVulnAffectsReleasesPayload p = Utils.OM.convertValue(
                event.getRecordData(), NewVulnAffectsReleasesPayload.class);
        if (p == null) return new InboxRendering(event.getEventType().name(), null);

        String sev = p.severity() != null ? p.severity().name() : "Unrated";
        String vuln = p.vulnPrimaryId() != null ? p.vulnPrimaryId() : "vulnerability";
        int releaseCount = p.affectedReleases() != null ? p.affectedReleases().size() : 0;
        String releasesPhrase = releaseCount == 0
                ? "affects releases"
                : releaseCount == 1
                        ? "affects 1 release"
                        : "affects " + releaseCount + " releases";
        String title = sev + " " + vuln + " " + releasesPhrase;

        StringBuilder desc = new StringBuilder();
        if (p.cvssScore() != null) appendFact(desc, "CVSS " + formatScore(p.cvssScore()));
        if (p.epssScore() != null) appendFact(desc, "EPSS " + formatScore(p.epssScore()));
        if (Boolean.TRUE.equals(p.kevListed())) appendFact(desc, "CISA KEV listed");
        if (p.fixVersion() != null && !p.fixVersion().isBlank()) {
            appendFact(desc, "Fix: " + p.fixVersion());
        }
        if (p.affectedComponent() != null) {
            String label = affectedComponentLabel(p.affectedComponent());
            if (label != null) appendFact(desc, label);
        }
        return new InboxRendering(title, desc.length() == 0 ? null : desc.toString());
    }

    private InboxRendering renderVulnRecordUpdated(NotificationOutboxEvent event) {
        VulnerabilityRecordUpdatedPayload p = Utils.OM.convertValue(
                event.getRecordData(), VulnerabilityRecordUpdatedPayload.class);
        if (p == null) return new InboxRendering(event.getEventType().name(), null);

        String vuln = p.vulnPrimaryId() != null ? p.vulnPrimaryId() : "vulnerability";
        String changeLabel = labels.humanizeChangeType(p.changeType());
        String title = "Vulnerability " + vuln + " — " + changeLabel;

        StringBuilder desc = new StringBuilder();
        if (p.changeType() == VulnerabilityRecordUpdatedPayload.ChangeType.SEVERITY_BUMPED
                && p.oldSeverity() != null && p.newSeverity() != null) {
            appendFact(desc, p.oldSeverity().name() + " → " + p.newSeverity().name());
        } else if (p.newSeverity() != null) {
            appendFact(desc, "Severity " + p.newSeverity().name());
        }
        if (p.changeType() == VulnerabilityRecordUpdatedPayload.ChangeType.EPSS_SPIKED) {
            if (p.oldEpssScore() != null && p.newEpssScore() != null) {
                appendFact(desc, "EPSS " + formatScore(p.oldEpssScore())
                        + " → " + formatScore(p.newEpssScore()));
            } else if (p.newEpssScore() != null) {
                // One-sided payload (legacy producer or partial enrichment).
                // The event is *about* the new EPSS — surface it even
                // when the prior value isn't known.
                appendFact(desc, "EPSS " + formatScore(p.newEpssScore()));
            }
        }
        if (Boolean.TRUE.equals(p.kevListedNow())) appendFact(desc, "CISA KEV listed");
        if (p.affectedReleases() != null && !p.affectedReleases().isEmpty()) {
            int n = p.affectedReleases().size();
            appendFact(desc, n == 1 ? "1 affected release" : n + " affected releases");
        }
        return new InboxRendering(title, desc.length() == 0 ? null : desc.toString());
    }

    private InboxRendering renderVexStateChanged(NotificationOutboxEvent event) {
        VexStateChangedPayload p = Utils.OM.convertValue(
                event.getRecordData(), VexStateChangedPayload.class);
        if (p == null) return new InboxRendering(event.getEventType().name(), null);

        String vuln = p.vulnPrimaryId() != null ? p.vulnPrimaryId() : "vulnerability";
        // Distinguish a real transition (both states known) from a
        // one-sided observation (producer only captured the new state)
        // so the title doesn't synthesize a phantom "unknown" prior
        // state. "set to X" is more honest than "unknown → X".
        String title;
        if (p.oldState() != null && p.newState() != null) {
            title = "VEX state changed for " + vuln + ": "
                    + p.oldState() + " → " + p.newState();
        } else if (p.newState() != null) {
            title = "VEX state for " + vuln + " set to " + p.newState();
        } else if (p.oldState() != null) {
            title = "VEX state changed for " + vuln + " (was " + p.oldState() + ")";
        } else {
            title = "VEX state changed for " + vuln;
        }

        StringBuilder desc = new StringBuilder();
        if (p.componentPurl() != null && !p.componentPurl().isBlank()) {
            appendFact(desc, "Component: " + p.componentPurl());
        }
        if (p.releaseUuid() != null) appendFact(desc, "Release: " + p.releaseUuid());
        return new InboxRendering(title, desc.length() == 0 ? null : desc.toString());
    }

    private InboxRendering renderReleaseCreated(NotificationOutboxEvent event) {
        ReleaseCreatedPayload p = Utils.OM.convertValue(event.getRecordData(), ReleaseCreatedPayload.class);
        if (p == null || p.release() == null) return new InboxRendering(event.getEventType().name(), null);
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        String verb = p.scheduled() ? "scheduled" : "created";
        String title = "New " + noun + " release " + verb + ": " + releaseTitle(r);

        StringBuilder desc = new StringBuilder();
        if (r.lifecycle() != null) appendFact(desc, r.lifecycle().name());
        appendReleaseFacts(desc, r);
        return new InboxRendering(title, desc.length() == 0 ? null : desc.toString());
    }

    private InboxRendering renderReleaseLifecycleChanged(NotificationOutboxEvent event) {
        ReleaseLifecycleChangedPayload p = Utils.OM.convertValue(
                event.getRecordData(), ReleaseLifecycleChangedPayload.class);
        if (p == null || p.release() == null) return new InboxRendering(event.getEventType().name(), null);
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        String verb = labels.humanizeLifecycleVerb(p.newLifecycle());
        String title = capitalize(noun) + " release " + verb + ": " + releaseTitle(r);

        StringBuilder desc = new StringBuilder();
        if (p.oldLifecycle() != null && p.newLifecycle() != null) {
            appendFact(desc, p.oldLifecycle().name() + " → " + p.newLifecycle().name());
        }
        appendReleaseFacts(desc, r);
        return new InboxRendering(title, desc.length() == 0 ? null : desc.toString());
    }

    private InboxRendering renderReleaseBomDiff(NotificationOutboxEvent event) {
        ReleaseBomDiffPayload p = Utils.OM.convertValue(event.getRecordData(), ReleaseBomDiffPayload.class);
        if (p == null || p.release() == null) return new InboxRendering(event.getEventType().name(), null);
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        String title = "BOM diff on " + noun + ": " + releaseTitle(r);

        StringBuilder desc = new StringBuilder();
        int added = p.added() != null ? p.added().size() : 0;
        int removed = p.removed() != null ? p.removed().size() : 0;
        appendFact(desc, added + " added");
        appendFact(desc, removed + " removed");
        return new InboxRendering(title, desc.toString());
    }

    private InboxRendering renderApprovalRequested(NotificationOutboxEvent event) {
        ApprovalRequestedPayload p = Utils.OM.convertValue(event.getRecordData(), ApprovalRequestedPayload.class);
        if (p == null || p.release() == null) return new InboxRendering(event.getEventType().name(), null);
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        String title = "Approval requested on " + noun + " release: " + releaseTitle(r);

        StringBuilder desc = new StringBuilder();
        String entryNames = entryNames(p.entries());
        if (entryNames != null) appendFact(desc, "Approvals: " + entryNames);
        if (p.requestedByName() != null && !p.requestedByName().isBlank()) {
            appendFact(desc, "Requested by " + p.requestedByName());
        }
        appendReleaseFacts(desc, r);
        return new InboxRendering(title, desc.length() == 0 ? null : desc.toString());
    }

    private InboxRendering renderApprovalResolved(NotificationOutboxEvent event) {
        ApprovalResolvedPayload p = Utils.OM.convertValue(event.getRecordData(), ApprovalResolvedPayload.class);
        if (p == null || p.release() == null) return new InboxRendering(event.getEventType().name(), null);
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        String verb = labels.humanizeResolution(p.resolution());
        String title = "Approval " + verb + " on " + noun + " release: " + releaseTitle(r);

        StringBuilder desc = new StringBuilder();
        if (p.approvalEntryName() != null && !p.approvalEntryName().isBlank()) {
            appendFact(desc, "Approval: " + p.approvalEntryName());
        }
        if (p.resolvedByName() != null && !p.resolvedByName().isBlank()) {
            appendFact(desc, "Resolved by " + p.resolvedByName());
        }
        return new InboxRendering(title, desc.length() == 0 ? null : desc.toString());
    }

    /** Comma-joined approval entry names (capped at 10); null when none are usable. */
    private static String entryNames(java.util.List<ApprovalRequestEntryRef> entries) {
        if (entries == null || entries.isEmpty()) return null;
        java.util.List<String> names = new java.util.ArrayList<>();
        int cap = Math.min(entries.size(), 10);
        for (int i = 0; i < cap; i++) {
            ApprovalRequestEntryRef e = entries.get(i);
            if (e == null || e.approvalEntryName() == null || e.approvalEntryName().isBlank()) continue;
            names.add(e.approvalEntryName());
        }
        if (names.isEmpty()) return null;
        String joined = String.join(", ", names);
        if (entries.size() > cap) joined += " …and " + (entries.size() - cap) + " more";
        return joined;
    }

    private static String releaseTitle(ReleaseRef r) {
        return defaultString(r.componentName()) + " " + defaultString(r.version());
    }

    private void appendReleaseFacts(StringBuilder desc, ReleaseRef r) {
        if (r.branchName() != null && !r.branchName().isBlank()) {
            appendFact(desc, capitalize(labels.branchNoun(r.componentType())) + ": " + r.branchName());
        }
        if (r.commitHash() != null && !r.commitHash().isBlank()) {
            appendFact(desc, "Commit " + r.commitHash());
        }
        if (r.updatedByName() != null && !r.updatedByName().isBlank()) {
            appendFact(desc, "by " + r.updatedByName());
        }
    }

    private static String defaultString(String s) {
        return s == null ? "" : s;
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static void appendFact(StringBuilder sb, String fact) {
        if (sb.length() > 0) sb.append(" · ");
        sb.append(fact);
    }

    /** CVSS and EPSS share the same 1-2 decimal formatting. */
    private static String formatScore(double v) {
        if (v == Math.floor(v) && !Double.isInfinite(v)) return String.valueOf((int) v);
        // Avoid locale surprises — explicit %.2f
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }

    /**
     * Short, human-friendly component label. Prefers the canonical purl
     * (truncated past the version segment); falls back to the
     * component's name when no purl is present.
     *
     * <p>CPE-only canonical components landed in main's synthetic-DTrack
     * work (PR #185) but the {@link io.reliza.model.dto.notifications.AffectedComponent}
     * record doesn't yet carry a {@code cpe} field — that's a separate
     * data-model change (BD-3 / phase 2 of the notifications roadmap).
     * When it lands, add a branch here ahead of the name fallback.
     */
    private static String affectedComponentLabel(io.reliza.model.dto.notifications.AffectedComponent ac) {
        if (ac == null) return null;
        if (ac.purl() != null && !ac.purl().isBlank()) {
            return "Component: " + truncatePurl(ac.purl());
        }
        if (ac.name() != null && !ac.name().isBlank()) {
            return "Component: " + ac.name();
        }
        return null;
    }

    /** Trim a purl past the first {@code @} so {@code pkg:maven/foo/bar@1.2.3?type=jar} reads as {@code pkg:maven/foo/bar}. */
    private static String truncatePurl(String purl) {
        int at = purl.indexOf('@');
        return at > 0 ? purl.substring(0, at) : purl;
    }
}
