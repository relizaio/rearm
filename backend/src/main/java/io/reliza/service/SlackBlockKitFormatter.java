/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

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
 * Renders a {@link NotificationOutboxEvent} into a Slack Block Kit
 * payload suitable for POSTing to an incoming-webhook URL.
 *
 * <p>The output is the structured-blocks form (not the plain-text
 * {@code {"text":"..."}} form the existing {@code IntegrationService}
 * uses) — Block Kit supports headers, fact lists, and link buttons.
 *
 * <p>Always emits a {@code text} fallback alongside {@code blocks} so
 * Slack mobile + screen readers + push notifications render something
 * useful even when blocks render as a no-op.
 *
 * <p><b>Link-back to ReARM:</b> when {@code relizaprops.baseuri} is
 * configured, per-release lines render as Slack mrkdwn links to the
 * release detail page and an {@code actions} block adds a "View in
 * ReARM" button at the bottom. When the property is empty (e.g. unit
 * tests without a Spring context), links are skipped silently — the
 * card still renders with the same fact set, just without the deep
 * links.
 */
@Component
@Slf4j
public class SlackBlockKitFormatter {

    private final String webBaseUri;
    private final NotificationLabelProvider labels;

    // Single-constructor form: Spring 4.3+ auto-injects without @Autowired.
    // Do NOT add a no-arg constructor here — Spring would pick that one
    // silently and webBaseUri would always be empty, breaking link enrichment.
    // Tests should call `new SlackBlockKitFormatter("", new NotificationLabelProvider())`
    // to construct without a base URI.
    public SlackBlockKitFormatter(@Value("${relizaprops.baseuri:}") String webBaseUri,
            NotificationLabelProvider labels) {
        // Strip trailing slashes so concatenation produces clean URLs.
        this.webBaseUri = StringUtils.defaultString(webBaseUri).replaceAll("/+$", "");
        this.labels = labels;
    }

    /**
     * Build the JSON payload Slack expects. Returns a Map ready to hand
     * to a WebClient {@code .bodyValue(...)} call.
     */
    public Map<String, Object> format(NotificationOutboxEvent event) {
        Map<String, Object> payload = new HashMap<>();
        if (event.getEventType() == null) {
            renderFallback(event, payload);
            return payload;
        }
        switch (event.getEventType()) {
            case NEW_VULN_AFFECTS_RELEASES -> renderNewVulnAffectsReleases(event, payload);
            case VULNERABILITY_RECORD_UPDATED -> renderVulnerabilityRecordUpdated(event, payload);
            case VEX_STATE_CHANGED -> renderVexStateChanged(event, payload);
            case RELEASE_CREATED -> renderReleaseCreated(event, payload);
            case RELEASE_LIFECYCLE_CHANGED -> renderReleaseLifecycleChanged(event, payload);
            case RELEASE_BOM_DIFF -> renderReleaseBomDiff(event, payload);
            case APPROVAL_REQUESTED -> renderApprovalRequested(event, payload);
            case APPROVAL_RESOLVED -> renderApprovalResolved(event, payload);
        }
        return payload;
    }

    private void renderNewVulnAffectsReleases(NotificationOutboxEvent event, Map<String, Object> out) {
        NewVulnAffectsReleasesPayload p = deserialize(event, NewVulnAffectsReleasesPayload.class);
        if (p == null) { renderFallback(event, out); return; }

        NotificationSeverity severity = p.severity();
        String severityLabel = severity != null ? severity.name() : "UNKNOWN";
        String emoji = severityEmoji(severity);
        int releaseCount = p.affectedReleases() != null ? p.affectedReleases().size() : 0;
        String releaseWord = releaseCount == 1 ? "release" : "releases";

        String headerText = emoji + " " + severityLabel + " vulnerability "
                + StringUtils.defaultString(p.vulnPrimaryId(), "(unknown CVE)")
                + " affects " + releaseCount + " " + releaseWord;
        String fallbackText = headerText;

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(headerBlock(headerText));

        // Facts section: severity / CVSS / EPSS / KEV / fix version
        List<String> facts = new ArrayList<>();
        if (p.cvssScore() != null) facts.add("*CVSS:* " + p.cvssScore());
        if (p.epssScore() != null) facts.add("*EPSS:* " + p.epssScore());
        if (Boolean.TRUE.equals(p.kevListed())) facts.add(":rotating_light: *CISA KEV listed*");
        if (StringUtils.isNotBlank(p.fixVersion())) facts.add("*Fix:* " + p.fixVersion());
        if (p.affectedComponent() != null && StringUtils.isNotBlank(p.affectedComponent().name())) {
            facts.add("*Component:* " + p.affectedComponent().name()
                    + " " + StringUtils.defaultString(p.affectedComponent().version()));
        }
        if (!facts.isEmpty()) {
            blocks.add(sectionBlock(String.join("\n", facts)));
        }

        // Per-release lines, truncated to 10 (Block Kit caps at ~50 blocks anyway)
        if (releaseCount > 0) {
            List<String> releaseLines = new ArrayList<>();
            int cap = Math.min(releaseCount, 10);
            for (int i = 0; i < cap; i++) {
                var r = p.affectedReleases().get(i);
                if (r == null) continue;
                String envs = (r.deployedEnvs() != null && !r.deployedEnvs().isEmpty())
                        ? " — deployed: " + String.join(", ", r.deployedEnvs())
                        : "";
                String lifecycle = r.lifecycle() != null ? r.lifecycle().name() : "";
                releaseLines.add("• *" + componentLabel(r)
                        + "* " + StringUtils.defaultString(r.version())
                        + " (" + lifecycle + ")"
                        + envs);
            }
            if (releaseCount > cap) {
                releaseLines.add("_…and " + (releaseCount - cap) + " more_");
            }
            blocks.add(sectionBlock(String.join("\n", releaseLines)));
        }

        addViewInReARMAction(blocks, event, p.affectedReleases());

        out.put("text", capText(fallbackText));
        out.put("blocks", blocks);
    }

    private void renderVulnerabilityRecordUpdated(NotificationOutboxEvent event, Map<String, Object> out) {
        VulnerabilityRecordUpdatedPayload p = deserialize(event, VulnerabilityRecordUpdatedPayload.class);
        if (p == null) { renderFallback(event, out); return; }

        NotificationSeverity severity = p.newSeverity();
        String emoji = severityEmoji(severity);

        String headerText = emoji + " Vulnerability "
                + StringUtils.defaultString(p.vulnPrimaryId(), "(unknown CVE)")
                + " " + labels.humanizeChangeType(p.changeType());

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(headerBlock(headerText));

        List<String> facts = new ArrayList<>();
        if (p.changeType() != null && p.oldSeverity() != null && p.newSeverity() != null
                && !Objects.equals(p.oldSeverity(), p.newSeverity())) {
            facts.add("*Severity:* " + p.oldSeverity() + " → " + p.newSeverity());
        }
        if (p.oldEpssScore() != null && p.newEpssScore() != null
                && !Objects.equals(p.oldEpssScore(), p.newEpssScore())) {
            facts.add("*EPSS:* " + p.oldEpssScore() + " → " + p.newEpssScore());
        }
        if (Boolean.TRUE.equals(p.kevListedNow())) {
            facts.add(":rotating_light: *Now CISA KEV listed*");
        }
        if (!facts.isEmpty()) {
            blocks.add(sectionBlock(String.join("\n", facts)));
        }

        addViewInReARMAction(blocks, event, p.affectedReleases());

        out.put("text", capText(headerText));
        out.put("blocks", blocks);
    }

    private void renderVexStateChanged(NotificationOutboxEvent event, Map<String, Object> out) {
        VexStateChangedPayload p = deserialize(event, VexStateChangedPayload.class);
        if (p == null) { renderFallback(event, out); return; }

        String headerText = ":memo: VEX state changed for "
                + StringUtils.defaultString(p.vulnPrimaryId(), "(unknown CVE)");

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(headerBlock(headerText));

        List<String> facts = new ArrayList<>();
        facts.add("*State:* `" + StringUtils.defaultString(p.oldState())
                + "` → `" + StringUtils.defaultString(p.newState()) + "`");
        if (StringUtils.isNotBlank(p.componentPurl())) {
            facts.add("*Component:* " + p.componentPurl());
        }
        if (p.releaseUuid() != null) {
            facts.add("*Release:* " + p.releaseUuid());
        }
        blocks.add(sectionBlock(String.join("\n", facts)));

        // VEX events are scoped to a (vuln × release) pair; if we have the
        // release UUID, link straight to it. Otherwise fall back to the
        // org-wide vuln analysis page.
        if (p.releaseUuid() != null && hasBaseUri()) {
            blocks.add(actionsBlock("View Release in ReARM",
                    webBaseUri + "/release/show/" + p.releaseUuid()));
        } else {
            addViewInReARMAction(blocks, event, List.of());
        }

        out.put("text", capText(headerText));
        out.put("blocks", blocks);
    }

    private void renderReleaseCreated(NotificationOutboxEvent event, Map<String, Object> out) {
        ReleaseCreatedPayload p = deserialize(event, ReleaseCreatedPayload.class);
        if (p == null || p.release() == null) { renderFallback(event, out); return; }
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        String verb = p.scheduled() ? "scheduled" : "created";
        String headerText = ":rocket: New " + noun + " release " + verb + ": " + releaseTitle(r);

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(headerBlock(headerText));
        List<String> facts = releaseFacts(r);
        if (r.lifecycle() != null) facts.add(0, "*Lifecycle:* " + r.lifecycle().name());
        if (!facts.isEmpty()) blocks.add(sectionBlock(String.join("\n", facts)));
        addReleaseAction(blocks, r);

        out.put("text", capText(headerText));
        out.put("blocks", blocks);
    }

    private void renderReleaseLifecycleChanged(NotificationOutboxEvent event, Map<String, Object> out) {
        ReleaseLifecycleChangedPayload p = deserialize(event, ReleaseLifecycleChangedPayload.class);
        if (p == null || p.release() == null) { renderFallback(event, out); return; }
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        String verb = labels.humanizeLifecycleVerb(p.newLifecycle());
        String headerText = ":arrows_counterclockwise: " + StringUtils.capitalize(noun)
                + " release " + verb + ": " + releaseTitle(r);

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(headerBlock(headerText));
        List<String> facts = new ArrayList<>();
        if (p.oldLifecycle() != null && p.newLifecycle() != null) {
            facts.add("*Lifecycle:* " + p.oldLifecycle().name() + " → " + p.newLifecycle().name());
        }
        facts.addAll(releaseFacts(r));
        if (!facts.isEmpty()) blocks.add(sectionBlock(String.join("\n", facts)));
        addReleaseAction(blocks, r);

        out.put("text", capText(headerText));
        out.put("blocks", blocks);
    }

    private void renderReleaseBomDiff(NotificationOutboxEvent event, Map<String, Object> out) {
        ReleaseBomDiffPayload p = deserialize(event, ReleaseBomDiffPayload.class);
        if (p == null || p.release() == null) { renderFallback(event, out); return; }
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        String headerText = ":mag: BOM diff on " + noun + ": " + releaseTitle(r);

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(headerBlock(headerText));
        List<String> facts = releaseFacts(r);
        if (!facts.isEmpty()) blocks.add(sectionBlock(String.join("\n", facts)));
        String added = bomList(p.added());
        if (added != null) blocks.add(sectionBlock("*Added components:*\n" + added));
        String removed = bomList(p.removed());
        if (removed != null) blocks.add(sectionBlock("*Removed components:*\n" + removed));
        addReleaseAction(blocks, r);

        out.put("text", capText(headerText));
        out.put("blocks", blocks);
    }

    private void renderApprovalRequested(NotificationOutboxEvent event, Map<String, Object> out) {
        ApprovalRequestedPayload p = deserialize(event, ApprovalRequestedPayload.class);
        if (p == null || p.release() == null) { renderFallback(event, out); return; }
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        String headerText = ":bell: Approval requested on " + noun + " release: " + releaseTitle(r);

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(headerBlock(headerText));
        List<String> facts = new ArrayList<>();
        String entryNames = entryNames(p.entries());
        if (entryNames != null) facts.add("*Approvals:* " + entryNames);
        if (StringUtils.isNotBlank(p.requestedByName())) facts.add("*Requested by:* " + p.requestedByName());
        facts.addAll(releaseFacts(r));
        if (!facts.isEmpty()) blocks.add(sectionBlock(String.join("\n", facts)));
        addReleaseAction(blocks, r);

        out.put("text", capText(headerText));
        out.put("blocks", blocks);
    }

    private void renderApprovalResolved(NotificationOutboxEvent event, Map<String, Object> out) {
        ApprovalResolvedPayload p = deserialize(event, ApprovalResolvedPayload.class);
        if (p == null || p.release() == null) { renderFallback(event, out); return; }
        ReleaseRef r = p.release();

        String noun = labels.componentNoun(r.componentType());
        String verb = labels.humanizeResolution(p.resolution());
        String emoji = p.resolution() == ApprovalResolvedPayload.Resolution.DISAPPROVED
                ? ":x:" : ":white_check_mark:";
        String headerText = emoji + " Approval " + verb + " on " + noun + " release: " + releaseTitle(r);

        List<Map<String, Object>> blocks = new ArrayList<>();
        blocks.add(headerBlock(headerText));
        List<String> facts = new ArrayList<>();
        if (StringUtils.isNotBlank(p.approvalEntryName())) facts.add("*Approval:* " + p.approvalEntryName());
        if (StringUtils.isNotBlank(p.resolvedByName())) facts.add("*Resolved by:* " + p.resolvedByName());
        facts.addAll(releaseFacts(r));
        if (!facts.isEmpty()) blocks.add(sectionBlock(String.join("\n", facts)));
        addReleaseAction(blocks, r);

        out.put("text", capText(headerText));
        out.put("blocks", blocks);
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
    private List<String> releaseFacts(ReleaseRef r) {
        List<String> facts = new ArrayList<>();
        if (StringUtils.isNotBlank(r.branchName())) {
            facts.add("*" + StringUtils.capitalize(labels.branchNoun(r.componentType())) + ":* " + r.branchName());
        }
        if (StringUtils.isNotBlank(r.commitHash())) {
            String commit = StringUtils.isNotBlank(r.commitUri())
                    ? "<" + r.commitUri() + "|" + r.commitHash() + ">"
                    : r.commitHash();
            if (StringUtils.isNotBlank(r.commitMessage())) {
                commit += " " + StringUtils.truncate(r.commitMessage(), 200);
            }
            facts.add("*Commit:* " + commit);
        }
        if (StringUtils.isNotBlank(r.updatedByName())) facts.add("*By:* " + r.updatedByName());
        return facts;
    }

    /** Renders up to 10 BOM-change lines; null when the list is empty. */
    private static String bomList(List<BomComponentChange> changes) {
        if (changes == null || changes.isEmpty()) return null;
        StringBuilder sb = new StringBuilder();
        int cap = Math.min(changes.size(), 10);
        for (int i = 0; i < cap; i++) {
            BomComponentChange c = changes.get(i);
            if (c == null) continue;
            sb.append("• ").append(StringUtils.defaultString(c.purl()));
            if (StringUtils.isNotBlank(c.version())) sb.append(" : ").append(c.version());
            sb.append("\n");
        }
        if (changes.size() > cap) sb.append("…and ").append(changes.size() - cap).append(" more");
        return sb.toString().trim();
    }

    private void addReleaseAction(List<Map<String, Object>> blocks, ReleaseRef r) {
        if (hasBaseUri() && r.releaseUuid() != null) {
            blocks.add(actionsBlock("View Release in ReARM", webBaseUri + "/release/show/" + r.releaseUuid()));
        }
    }

    /**
     * Generic fallback. Used when the typed payload can't be deserialized
     * or the event type isn't yet special-cased — channel worker still
     * delivers something so we have a paper trail.
     */
    private void renderFallback(NotificationOutboxEvent event, Map<String, Object> out) {
        String text = "ReARM notification: "
                + (event.getEventType() != null ? event.getEventType().name() : "(unknown event type)")
                + " — " + StringUtils.defaultString(event.getDedupKey(), event.getUuid().toString());
        out.put("text", capText(text));
    }

    /**
     * Cap the top-level {@code text} fallback. Slack rejects payloads
     * over 40,000 chars; we cap well below that as a defensive bound
     * against pathological event input.
     */
    private static String capText(String text) {
        return StringUtils.truncate(text, 3000);
    }

    private Map<String, Object> headerBlock(String text) {
        Map<String, Object> block = new HashMap<>();
        block.put("type", "header");
        Map<String, Object> textObj = new HashMap<>();
        textObj.put("type", "plain_text");
        // Slack header blocks accept max 150 chars; truncate aggressively
        // so a long-CVE-id event doesn't get rejected outright.
        textObj.put("text", StringUtils.truncate(text, 150));
        textObj.put("emoji", true);
        block.put("text", textObj);
        return block;
    }

    /**
     * Wraps a release's component name as a Slack mrkdwn link to its
     * release detail page when a base URI is configured. Returns the
     * plain component name otherwise — link-skipping is silent so
     * unit-test contexts (no Spring DI) still render valid cards.
     */
    private String componentLabel(AffectedRelease r) {
        String name = StringUtils.defaultString(r.component());
        if (hasBaseUri() && r.uuid() != null) {
            return "<" + webBaseUri + "/release/show/" + r.uuid() + "|" + name + ">";
        }
        return name;
    }

    /**
     * Appends a single-button {@code actions} block linking back to the
     * relevant ReARM page. Heuristic: if there's exactly one release with
     * a UUID, link directly to that release; otherwise link to the org's
     * vulnerability analysis page so the user can pivot to any affected
     * release. No-op when {@code relizaprops.baseuri} is empty.
     */
    private void addViewInReARMAction(List<Map<String, Object>> blocks,
                                       NotificationOutboxEvent event,
                                       List<AffectedRelease> releases) {
        if (!hasBaseUri()) return;
        UUID singleRelease = singleReleaseUuid(releases);
        if (singleRelease != null) {
            blocks.add(actionsBlock("View Release in ReARM",
                    webBaseUri + "/release/show/" + singleRelease));
        } else if (event.getOrg() != null) {
            blocks.add(actionsBlock("View in ReARM",
                    webBaseUri + "/vulnerabilityAnalysis/" + event.getOrg()));
        }
    }

    private static UUID singleReleaseUuid(List<AffectedRelease> releases) {
        if (releases == null || releases.size() != 1) return null;
        AffectedRelease only = releases.get(0);
        return only != null ? only.uuid() : null;
    }

    private boolean hasBaseUri() {
        return StringUtils.isNotBlank(webBaseUri);
    }

    private Map<String, Object> actionsBlock(String label, String url) {
        Map<String, Object> block = new HashMap<>();
        block.put("type", "actions");
        Map<String, Object> button = new HashMap<>();
        button.put("type", "button");
        Map<String, Object> textObj = new HashMap<>();
        textObj.put("type", "plain_text");
        // Slack button labels max 75 chars
        textObj.put("text", StringUtils.truncate(label, 75));
        textObj.put("emoji", true);
        button.put("text", textObj);
        button.put("url", url);
        block.put("elements", List.of(button));
        return block;
    }

    private Map<String, Object> sectionBlock(String mrkdwn) {
        Map<String, Object> block = new HashMap<>();
        block.put("type", "section");
        Map<String, Object> textObj = new HashMap<>();
        textObj.put("type", "mrkdwn");
        // Slack section blocks accept max 3000 chars
        textObj.put("text", StringUtils.truncate(mrkdwn, 3000));
        block.put("text", textObj);
        return block;
    }

    /**
     * Switches on the {@link NotificationSeverity} enum directly so a
     * future rename of an enum value compiles-errors instead of
     * silently falling through to the info-emoji default. Convention
     * from coding_principles.md §1.
     */
    private String severityEmoji(NotificationSeverity severity) {
        if (severity == null) return ":information_source:";
        return switch (severity) {
            case CRITICAL -> ":rotating_light:";
            case HIGH -> ":warning:";
            case MEDIUM -> ":large_orange_diamond:";
            case LOW -> ":large_yellow_circle:";
            case INFO, NONE -> ":information_source:";
        };
    }

    private <T> T deserialize(NotificationOutboxEvent event, Class<T> klass) {
        if (event.getRecordData() == null) return null;
        try {
            return Utils.OM.convertValue(event.getRecordData(), klass);
        } catch (Exception e) {
            log.warn("Slack formatter failed to deserialize {} payload for event {}: {}",
                    klass.getSimpleName(), event.getUuid(), e.getMessage());
            return null;
        }
    }
}
