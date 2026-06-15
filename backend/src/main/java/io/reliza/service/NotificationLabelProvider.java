/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import org.springframework.stereotype.Component;

import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.notifications.ApprovalResolvedPayload;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload;

/**
 * Single source of truth for the human-readable strings shared
 * across notification surfaces (Slack, Email, Teams, Inbox).
 *
 * <p>Before this hoist, every formatter carried its own copy of
 * {@code humanizeChangeType} (SlackBlockKitFormatter:334-341,
 * EmailContentFormatter:385-392, TeamsAdaptiveCardFormatter:302-309,
 * NotificationInboxFormatter:183-190). All four returned identical
 * strings — drift was inevitable as soon as one surface deviated
 * (e.g. Slack adding emoji, Email tightening for a subject line).
 *
 * <p>Channel-specific embellishments (Slack's leading emoji, Teams'
 * MessageCard color tokens, Email's bracket-prefixed subjects) stay
 * on the per-formatter side — this provider is only for the
 * channel-agnostic core label that those formatters wrap.
 *
 * <p>Adding a new label: prefer adding it here from the start,
 * even if only one formatter consumes it today. The hoist cost is
 * lower than the eventual de-dup once two surfaces converge.
 */
@Component
public class NotificationLabelProvider {

    /**
     * Human-readable label for the {@link VulnerabilityRecordUpdatedPayload.ChangeType}
     * enum. Used as the core verb-phrase in event titles ("Vulnerability
     * CVE-… — &lt;label&gt;"). Returns "updated" for an unmapped/null type
     * so the calling formatter never renders the empty string.
     */
    public String humanizeChangeType(VulnerabilityRecordUpdatedPayload.ChangeType ct) {
        if (ct == null) return "updated";
        return switch (ct) {
            case SEVERITY_BUMPED -> "severity bumped";
            case KEV_ADDED -> "added to CISA KEV";
            case EPSS_SPIKED -> "EPSS spiked";
        };
    }

    /**
     * Whether a release belongs to a product or a plain component, as a
     * noun for prose ("product release assembled" vs "component release
     * assembled"). Mirrors the legacy {@code NotificationService}
     * product/component wording. {@code ANY}/null fall back to the
     * neutral "component".
     */
    public String componentNoun(ComponentType type) {
        return type == ComponentType.PRODUCT ? "product" : "component";
    }

    /**
     * The branch-axis noun paired with {@link #componentNoun}: products
     * group releases by "feature set", components by "branch". Mirrors
     * the legacy wording.
     */
    public String branchNoun(ComponentType type) {
        return type == ComponentType.PRODUCT ? "feature set" : "branch";
    }

    /**
     * Past-tense verb for an {@code APPROVAL_RESOLVED} event title
     * ("Approval &lt;verb&gt; on … release"). Null falls back to the
     * neutral "resolved" so a malformed payload still renders prose.
     */
    public String humanizeResolution(ApprovalResolvedPayload.Resolution resolution) {
        if (resolution == null) return "resolved";
        return switch (resolution) {
            case APPROVED -> "approved";
            case DISAPPROVED -> "disapproved";
        };
    }

    /**
     * Past-tense verb phrase for a release lifecycle transition, used as
     * the core of a {@code RELEASE_LIFECYCLE_CHANGED} title ("Release
     * &lt;verb&gt;"). The producer only emits the four legacy-notified
     * transitions, but this covers every constant so the switch stays
     * exhaustive — any other value renders a generic "moved to &lt;x&gt;".
     */
    public String humanizeLifecycleVerb(ReleaseLifecycle lifecycle) {
        if (lifecycle == null) return "updated";
        return switch (lifecycle) {
            case DRAFT -> "moved to draft";
            case ASSEMBLED -> "assembled";
            case CANCELLED -> "cancelled";
            case REJECTED -> "rejected";
            case PENDING -> "scheduled";
            case READY_TO_SHIP -> "marked ready to ship";
            case GENERAL_AVAILABILITY -> "released";
            case END_OF_MARKETING -> "marked end of marketing";
            case END_OF_DISTRIBUTION -> "marked end of distribution";
            case END_OF_SUPPORT -> "marked end of support";
            case END_OF_LIFE -> "marked end of life";
        };
    }
}
