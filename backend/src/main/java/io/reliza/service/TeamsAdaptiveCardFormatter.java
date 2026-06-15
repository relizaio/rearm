/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.reliza.common.Utils;
import io.reliza.model.dto.notifications.AffectedRelease;
import io.reliza.model.dto.notifications.ApprovalRequestEntryRef;
import io.reliza.model.dto.notifications.ApprovalRequestedPayload;
import io.reliza.model.dto.notifications.ApprovalResolvedPayload;
import io.reliza.model.dto.notifications.BomComponentChange;
import io.reliza.model.dto.notifications.NewVulnAffectsReleasesPayload;
import io.reliza.model.dto.notifications.ReleaseBomDiffPayload;
import io.reliza.model.dto.notifications.ReleaseCreatedPayload;
import io.reliza.model.dto.notifications.ReleaseLifecycleChangedPayload;
import io.reliza.model.dto.notifications.ReleaseRef;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.dto.notifications.VexStateChangedPayload;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload;
import lombok.extern.slf4j.Slf4j;

/**
 * Renders a {@link NotificationOutboxEvent} into the JSON envelope that
 * MS Teams expects from a Power Automate Workflows webhook (the modern
 * replacement for the deprecated O365 connector path):
 *
 * <pre>
 * {
 *   "type": "message",
 *   "attachments": [{
 *     "contentType": "application/vnd.microsoft.card.adaptive",
 *     "content": { ...AdaptiveCard v1.5... }
 *   }]
 * }
 * </pre>
 *
 * <p>Card shape uses TextBlock + FactSet for the body (rendered with
 * native Teams styling) and Action.OpenUrl for link-backs. Per Teams
 * mobile + web client constraints, the card is built without Markdown
 * formatting in TextBlocks (Teams renders `**bold**` literally in some
 * mobile clients) — emphasis comes from `weight` and `color` attributes.
 *
 * <p><b>Link-back to ReARM:</b> when {@code relizaprops.baseuri} is
 * configured, per-release facts render as Action.OpenUrl entries and a
 * "View in ReARM" action is appended at the bottom. When the property
 * is empty (unit-test contexts without Spring DI), actions are skipped
 * silently — the card still renders with the full fact set.
 */
@Component
@Slf4j
public class TeamsAdaptiveCardFormatter {

    private final String webBaseUri;
    private final NotificationLabelProvider labels;

    public TeamsAdaptiveCardFormatter(@Value("${relizaprops.baseuri:}") String webBaseUri,
            NotificationLabelProvider labels) {
        this.webBaseUri = StringUtils.defaultString(webBaseUri).replaceAll("/+$", "");
        this.labels = labels;
    }

    /**
     * Build the JSON payload Teams Workflows expects. Returns a Map
     * ready to hand to a WebClient {@code .bodyValue(...)} call.
     */
    public Map<String, Object> format(NotificationOutboxEvent event) {
        Map<String, Object> card = buildCardForEvent(event);
        Map<String, Object> attachment = new HashMap<>();
        attachment.put("contentType", "application/vnd.microsoft.card.adaptive");
        attachment.put("contentUrl", null);
        attachment.put("content", card);
        Map<String, Object> envelope = new HashMap<>();
        envelope.put("type", "message");
        envelope.put("attachments", List.of(attachment));
        return envelope;
    }

    private Map<String, Object> buildCardForEvent(NotificationOutboxEvent event) {
        if (event.getEventType() == null) return fallbackCard(event);
        return switch (event.getEventType()) {
            case NEW_VULN_AFFECTS_RELEASES -> renderNewVulnAffectsReleases(event);
            case VULNERABILITY_RECORD_UPDATED -> renderVulnerabilityRecordUpdated(event);
            case VEX_STATE_CHANGED -> renderVexStateChanged(event);
            case RELEASE_CREATED -> renderReleaseCreated(event);
            case RELEASE_LIFECYCLE_CHANGED -> renderReleaseLifecycleChanged(event);
            case RELEASE_BOM_DIFF -> renderReleaseBomDiff(event);
            case APPROVAL_REQUESTED -> renderApprovalRequested(event);
            case APPROVAL_RESOLVED -> renderApprovalResolved(event);
        };
    }

    private Map<String, Object> renderNewVulnAffectsReleases(NotificationOutboxEvent event) {
        NewVulnAffectsReleasesPayload p = deserialize(event, NewVulnAffectsReleasesPayload.class);
        if (p == null) return fallbackCard(event);

        NotificationSeverity severity = p.severity();
        String severityLabel = severity != null ? severity.name() : "UNKNOWN";
        int releaseCount = p.affectedReleases() != null ? p.affectedReleases().size() : 0;
        String releaseWord = releaseCount == 1 ? "release" : "releases";
        String cve = StringUtils.defaultString(p.vulnPrimaryId(), "(unknown CVE)");

        List<Map<String, Object>> body = new ArrayList<>();
        body.add(textBlock(severityLabel + " vulnerability " + cve, "Large", "Bolder", severityColor(severity)));
        body.add(textBlock("Affects " + releaseCount + " " + releaseWord, "Medium", "Default", "Default"));

        List<Map<String, String>> facts = new ArrayList<>();
        if (p.cvssScore() != null) facts.add(fact("CVSS", String.valueOf(p.cvssScore())));
        if (p.epssScore() != null) facts.add(fact("EPSS", String.valueOf(p.epssScore())));
        if (Boolean.TRUE.equals(p.kevListed())) facts.add(fact("CISA KEV", "Listed"));
        if (StringUtils.isNotBlank(p.fixVersion())) facts.add(fact("Fix", p.fixVersion()));
        if (p.affectedComponent() != null && StringUtils.isNotBlank(p.affectedComponent().name())) {
            facts.add(fact("Component",
                    p.affectedComponent().name() + " "
                            + StringUtils.defaultString(p.affectedComponent().version())));
        }
        if (!facts.isEmpty()) body.add(factSet(facts));

        // Per-release fact list, truncated to 10 (mirrors Slack/Email formatters).
        if (releaseCount > 0) {
            List<Map<String, String>> releaseFacts = new ArrayList<>();
            int cap = Math.min(releaseCount, 10);
            for (int i = 0; i < cap; i++) {
                AffectedRelease r = p.affectedReleases().get(i);
                if (r == null) continue;
                String envs = (r.deployedEnvs() != null && !r.deployedEnvs().isEmpty())
                        ? " — deployed: " + String.join(", ", r.deployedEnvs())
                        : "";
                String lifecycle = r.lifecycle() != null ? r.lifecycle().name() : "";
                releaseFacts.add(fact(
                        StringUtils.defaultString(r.component()),
                        StringUtils.defaultString(r.version()) + " (" + lifecycle + ")" + envs));
            }
            if (releaseCount > cap) {
                releaseFacts.add(fact("…and", (releaseCount - cap) + " more"));
            }
            body.add(factSet(releaseFacts));
        }

        return adaptiveCard(body, buildViewInReARMAction(event, p.affectedReleases()));
    }

    private Map<String, Object> renderVulnerabilityRecordUpdated(NotificationOutboxEvent event) {
        VulnerabilityRecordUpdatedPayload p = deserialize(event, VulnerabilityRecordUpdatedPayload.class);
        if (p == null) return fallbackCard(event);

        NotificationSeverity severity = p.newSeverity();
        String cve = StringUtils.defaultString(p.vulnPrimaryId(), "(unknown CVE)");
        String change = labels.humanizeChangeType(p.changeType());

        List<Map<String, Object>> body = new ArrayList<>();
        body.add(textBlock(cve + " " + change, "Large", "Bolder", severityColor(severity)));

        List<Map<String, String>> facts = new ArrayList<>();
        if (p.oldSeverity() != null && p.newSeverity() != null
                && p.oldSeverity() != p.newSeverity()) {
            facts.add(fact("Severity", p.oldSeverity().name() + " → " + p.newSeverity().name()));
        }
        if (p.oldEpssScore() != null && p.newEpssScore() != null
                && !p.oldEpssScore().equals(p.newEpssScore())) {
            facts.add(fact("EPSS", p.oldEpssScore() + " → " + p.newEpssScore()));
        }
        if (Boolean.TRUE.equals(p.kevListedNow())) facts.add(fact("Now CISA KEV", "Listed"));
        if (!facts.isEmpty()) body.add(factSet(facts));

        return adaptiveCard(body, buildViewInReARMAction(event, p.affectedReleases()));
    }

    private Map<String, Object> renderVexStateChanged(NotificationOutboxEvent event) {
        VexStateChangedPayload p = deserialize(event, VexStateChangedPayload.class);
        if (p == null) return fallbackCard(event);

        String cve = StringUtils.defaultString(p.vulnPrimaryId(), "(unknown CVE)");
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(textBlock("VEX state changed for " + cve, "Large", "Bolder", "Default"));

        List<Map<String, String>> facts = new ArrayList<>();
        facts.add(fact("State",
                StringUtils.defaultString(p.oldState()) + " → "
                        + StringUtils.defaultString(p.newState())));
        if (StringUtils.isNotBlank(p.componentPurl())) facts.add(fact("Component", p.componentPurl()));
        if (p.releaseUuid() != null) facts.add(fact("Release", p.releaseUuid().toString()));
        body.add(factSet(facts));

        List<Map<String, Object>> actions = new ArrayList<>();
        if (p.releaseUuid() != null && hasBaseUri()) {
            actions.add(openUrlAction("View Release in ReARM",
                    webBaseUri + "/release/show/" + p.releaseUuid()));
        } else {
            String fallback = orgVulnAnalysisUrl(event);
            if (fallback != null) actions.add(openUrlAction("View in ReARM", fallback));
        }
        return adaptiveCard(body, actions);
    }

    private Map<String, Object> renderReleaseCreated(NotificationOutboxEvent event) {
        ReleaseCreatedPayload p = deserialize(event, ReleaseCreatedPayload.class);
        if (p == null || p.release() == null) return fallbackCard(event);
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        String verb = p.scheduled() ? "scheduled" : "created";

        List<Map<String, Object>> body = new ArrayList<>();
        body.add(textBlock("New " + noun + " release " + verb, "Large", "Bolder", "Good"));
        body.add(textBlock(releaseTitle(r), "Medium", "Default", "Default"));
        List<Map<String, String>> facts = releaseFacts(r);
        if (r.lifecycle() != null) facts.add(0, fact("Lifecycle", r.lifecycle().name()));
        if (!facts.isEmpty()) body.add(factSet(facts));

        return adaptiveCard(body, releaseAction(r));
    }

    private Map<String, Object> renderReleaseLifecycleChanged(NotificationOutboxEvent event) {
        ReleaseLifecycleChangedPayload p = deserialize(event, ReleaseLifecycleChangedPayload.class);
        if (p == null || p.release() == null) return fallbackCard(event);
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        String verb = labels.humanizeLifecycleVerb(p.newLifecycle());

        List<Map<String, Object>> body = new ArrayList<>();
        body.add(textBlock(StringUtils.capitalize(noun) + " release " + verb, "Large", "Bolder", "Accent"));
        body.add(textBlock(releaseTitle(r), "Medium", "Default", "Default"));
        List<Map<String, String>> facts = new ArrayList<>();
        if (p.oldLifecycle() != null && p.newLifecycle() != null) {
            facts.add(fact("Lifecycle", p.oldLifecycle().name() + " → " + p.newLifecycle().name()));
        }
        facts.addAll(releaseFacts(r));
        if (!facts.isEmpty()) body.add(factSet(facts));

        return adaptiveCard(body, releaseAction(r));
    }

    private Map<String, Object> renderReleaseBomDiff(NotificationOutboxEvent event) {
        ReleaseBomDiffPayload p = deserialize(event, ReleaseBomDiffPayload.class);
        if (p == null || p.release() == null) return fallbackCard(event);
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(textBlock("BOM diff on " + noun, "Large", "Bolder", "Default"));
        body.add(textBlock(releaseTitle(r), "Medium", "Default", "Default"));
        List<Map<String, String>> facts = releaseFacts(r);
        if (!facts.isEmpty()) body.add(factSet(facts));
        appendBomFacts(body, "Added components", p.added());
        appendBomFacts(body, "Removed components", p.removed());

        return adaptiveCard(body, releaseAction(r));
    }

    private Map<String, Object> renderApprovalRequested(NotificationOutboxEvent event) {
        ApprovalRequestedPayload p = deserialize(event, ApprovalRequestedPayload.class);
        if (p == null || p.release() == null) return fallbackCard(event);
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(textBlock("Approval requested on " + noun + " release", "Large", "Bolder", "Accent"));
        body.add(textBlock(releaseTitle(r), "Medium", "Default", "Default"));
        List<Map<String, String>> facts = new ArrayList<>();
        String entryNames = entryNames(p.entries());
        if (entryNames != null) facts.add(fact("Approvals", entryNames));
        if (StringUtils.isNotBlank(p.requestedByName())) facts.add(fact("Requested by", p.requestedByName()));
        facts.addAll(releaseFacts(r));
        if (!facts.isEmpty()) body.add(factSet(facts));

        return adaptiveCard(body, releaseAction(r));
    }

    private Map<String, Object> renderApprovalResolved(NotificationOutboxEvent event) {
        ApprovalResolvedPayload p = deserialize(event, ApprovalResolvedPayload.class);
        if (p == null || p.release() == null) return fallbackCard(event);
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        String verb = labels.humanizeResolution(p.resolution());
        String color = p.resolution() == ApprovalResolvedPayload.Resolution.DISAPPROVED
                ? "Attention" : "Good";
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(textBlock("Approval " + verb + " on " + noun + " release", "Large", "Bolder", color));
        body.add(textBlock(releaseTitle(r), "Medium", "Default", "Default"));
        List<Map<String, String>> facts = new ArrayList<>();
        if (StringUtils.isNotBlank(p.approvalEntryName())) facts.add(fact("Approval", p.approvalEntryName()));
        if (StringUtils.isNotBlank(p.resolvedByName())) facts.add(fact("Resolved by", p.resolvedByName()));
        facts.addAll(releaseFacts(r));
        if (!facts.isEmpty()) body.add(factSet(facts));

        return adaptiveCard(body, releaseAction(r));
    }

    /** Comma-joined approval entry names (capped at 10); null when none are usable. */
    private static String entryNames(List<ApprovalRequestEntryRef> entries) {
        if (entries == null || entries.isEmpty()) return null;
        List<String> names = new ArrayList<>();
        int cap = Math.min(entries.size(), 10);
        for (int i = 0; i < cap; i++) {
            ApprovalRequestEntryRef e = entries.get(i);
            if (e == null || StringUtils.isBlank(e.approvalEntryName())) continue;
            names.add(e.approvalEntryName());
        }
        if (names.isEmpty()) return null;
        String joined = String.join(", ", names);
        if (entries.size() > cap) joined += " …and " + (entries.size() - cap) + " more";
        return joined;
    }

    private static String releaseTitle(ReleaseRef r) {
        return StringUtils.defaultString(r.componentName()) + " " + StringUtils.defaultString(r.version());
    }

    /** Branch / commit / author facts shared by all three release cards (lifecycle handled per-card). */
    private List<Map<String, String>> releaseFacts(ReleaseRef r) {
        List<Map<String, String>> facts = new ArrayList<>();
        if (StringUtils.isNotBlank(r.branchName())) {
            facts.add(fact(StringUtils.capitalize(labels.branchNoun(r.componentType())), r.branchName()));
        }
        if (StringUtils.isNotBlank(r.commitHash())) {
            String commit = StringUtils.isNotBlank(r.commitMessage())
                    ? r.commitHash() + " — " + StringUtils.truncate(r.commitMessage(), 200)
                    : r.commitHash();
            facts.add(fact("Commit", commit));
        }
        if (StringUtils.isNotBlank(r.updatedByName())) facts.add(fact("By", r.updatedByName()));
        return facts;
    }

    private void appendBomFacts(List<Map<String, Object>> body, String heading, List<BomComponentChange> changes) {
        if (changes == null || changes.isEmpty()) return;
        body.add(textBlock(heading, "Medium", "Bolder", "Default"));
        List<Map<String, String>> facts = new ArrayList<>();
        int cap = Math.min(changes.size(), 10);
        for (int i = 0; i < cap; i++) {
            BomComponentChange c = changes.get(i);
            if (c == null) continue;
            facts.add(fact(StringUtils.defaultString(c.purl()), StringUtils.defaultString(c.version())));
        }
        if (changes.size() > cap) facts.add(fact("…and", (changes.size() - cap) + " more"));
        body.add(factSet(facts));
    }

    private List<Map<String, Object>> releaseAction(ReleaseRef r) {
        if (hasBaseUri() && r.releaseUuid() != null) {
            return List.of(openUrlAction("View Release in ReARM",
                    webBaseUri + "/release/show/" + r.releaseUuid()));
        }
        return List.of();
    }

    private Map<String, Object> fallbackCard(NotificationOutboxEvent event) {
        String text = "ReARM notification: "
                + (event.getEventType() != null ? event.getEventType().name() : "(unknown event type)")
                + " — " + StringUtils.defaultString(event.getDedupKey(),
                        event.getUuid() != null ? event.getUuid().toString() : "");
        List<Map<String, Object>> body = new ArrayList<>();
        body.add(textBlock(text, "Medium", "Default", "Default"));
        return adaptiveCard(body, List.of());
    }

    // ---------- card primitives ----------

    private Map<String, Object> adaptiveCard(List<Map<String, Object>> body,
            List<Map<String, Object>> actions) {
        Map<String, Object> card = new HashMap<>();
        card.put("type", "AdaptiveCard");
        card.put("$schema", "http://adaptivecards.io/schemas/adaptive-card.json");
        card.put("version", "1.5");
        card.put("body", body);
        if (actions != null && !actions.isEmpty()) card.put("actions", actions);
        return card;
    }

    private Map<String, Object> textBlock(String text, String size, String weight, String color) {
        Map<String, Object> block = new HashMap<>();
        block.put("type", "TextBlock");
        block.put("text", text);
        block.put("size", size);
        block.put("weight", weight);
        block.put("color", color);
        block.put("wrap", true);
        return block;
    }

    private Map<String, String> fact(String title, String value) {
        Map<String, String> m = new HashMap<>();
        m.put("title", title);
        m.put("value", value);
        return m;
    }

    private Map<String, Object> factSet(List<Map<String, String>> facts) {
        Map<String, Object> block = new HashMap<>();
        block.put("type", "FactSet");
        block.put("facts", facts);
        return block;
    }

    private Map<String, Object> openUrlAction(String title, String url) {
        Map<String, Object> action = new HashMap<>();
        action.put("type", "Action.OpenUrl");
        action.put("title", title);
        action.put("url", url);
        return action;
    }

    private List<Map<String, Object>> buildViewInReARMAction(NotificationOutboxEvent event,
            List<AffectedRelease> releases) {
        if (!hasBaseUri()) return List.of();
        java.util.Optional<java.util.UUID> single = singleReleaseUuid(releases);
        if (single.isPresent()) {
            return List.of(openUrlAction("View Release in ReARM",
                    webBaseUri + "/release/show/" + single.get()));
        }
        String orgUrl = orgVulnAnalysisUrl(event);
        return orgUrl != null
                ? List.of(openUrlAction("View in ReARM", orgUrl))
                : List.of();
    }

    private static java.util.Optional<java.util.UUID> singleReleaseUuid(List<AffectedRelease> releases) {
        if (releases == null || releases.size() != 1) return java.util.Optional.empty();
        AffectedRelease only = releases.get(0);
        return only != null && only.uuid() != null
                ? java.util.Optional.of(only.uuid())
                : java.util.Optional.empty();
    }

    private String orgVulnAnalysisUrl(NotificationOutboxEvent event) {
        if (!hasBaseUri() || event.getOrg() == null) return null;
        return webBaseUri + "/vulnerabilityAnalysis/" + event.getOrg();
    }

    private boolean hasBaseUri() {
        return StringUtils.isNotBlank(webBaseUri);
    }

    /**
     * Maps a severity to an Adaptive Card "color" attribute value.
     * Teams renders these as semantic colors keyed to the host theme
     * (e.g. "Attention" = red on light theme, lighter red on dark).
     * Doesn't hex-code colors — that would defeat the theme machinery.
     *
     * <p>Switches on the {@link NotificationSeverity} enum directly so a
     * future rename of an enum value compiles-errors instead of silently
     * flipping to the {@code "Default"} fallback — convention from
     * coding_principles.md §1.
     */
    private static String severityColor(NotificationSeverity severity) {
        if (severity == null) return "Default";
        return switch (severity) {
            case CRITICAL, HIGH -> "Attention";
            case MEDIUM -> "Warning";
            case LOW -> "Good";
            case INFO, NONE -> "Default";
        };
    }

    private <T> T deserialize(NotificationOutboxEvent event, Class<T> klass) {
        if (event.getRecordData() == null) return null;
        try {
            return Utils.OM.convertValue(event.getRecordData(), klass);
        } catch (Exception e) {
            log.warn("Teams formatter failed to deserialize {} payload for event {}: {}",
                    klass.getSimpleName(), event.getUuid(), e.getMessage());
            return null;
        }
    }
}
