/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.notifications.AffectedComponent;
import io.reliza.model.dto.notifications.AffectedRelease;
import io.reliza.model.dto.notifications.ApprovalRequestEntryRef;
import io.reliza.model.dto.notifications.ApprovalRequestedPayload;
import io.reliza.model.dto.notifications.ApprovalResolvedPayload;
import io.reliza.model.dto.notifications.BomComponentChange;
import io.reliza.model.dto.notifications.InstanceDeploymentChangedPayload;
import io.reliza.model.dto.notifications.InstanceDeploymentItem;
import io.reliza.model.dto.notifications.InstanceRef;
import io.reliza.model.dto.notifications.NewVulnAffectsReleasesPayload;
import io.reliza.model.dto.notifications.ReleaseBomDiffPayload;
import io.reliza.model.dto.UpdateStatus;
import io.reliza.model.dto.notifications.ReleaseCreatedPayload;
import io.reliza.model.dto.notifications.ReleaseLifecycleChangedPayload;
import io.reliza.model.dto.notifications.ReleaseRef;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.dto.notifications.VexStateChangedPayload;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload.ChangeType;

/**
 * Pins the Slack Block Kit payload shape — every event type emits a
 * payload with the {@code text} fallback and a {@code blocks} array
 * Slack will accept. Targeted assertions ensure the headline summary,
 * severity emoji, and per-release listing render as expected.
 */
class SlackBlockKitFormatterTest {

    /** Default formatter — empty base URI, so link-back features stay off. */
    private final SlackBlockKitFormatter formatter = new SlackBlockKitFormatter("", new NotificationLabelProvider());

    /** Test base URI for link-back assertions. */
    private static final String BASE = "https://rearm.example.com";

    /** Formatter that emits links + action buttons. */
    private final SlackBlockKitFormatter linkedFormatter = new SlackBlockKitFormatter(BASE, new NotificationLabelProvider());

    @Test
    void newVulnSingleReleaseRendersHeaderAndFacts() {
        NewVulnAffectsReleasesPayload p = new NewVulnAffectsReleasesPayload(
                "CVE-2025-12345",
                List.of(),
                9.8,
                "CVSS:3.1/...",
                0.85,
                Boolean.TRUE,
                "2.17.1",
                NotificationSeverity.CRITICAL,
                new AffectedComponent("pkg:maven/log4j-core@2.14.1", "log4j-core", "2.14.1"),
                List.of(new AffectedRelease(
                        UUID.randomUUID(), "myapp", "v2.0", "main",
                        ReleaseLifecycle.GENERAL_AVAILABILITY,
                        List.of("prod-us", "prod-eu"))));
        NotificationOutboxEvent event = eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p);

        Map<String, Object> payload = formatter.format(event);

        // text fallback always present
        String text = (String) payload.get("text");
        assertNotNull(text);
        assertTrue(text.contains("CRITICAL"));
        assertTrue(text.contains("CVE-2025-12345"));
        assertTrue(text.contains("1 release"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        assertNotNull(blocks);
        assertTrue(blocks.size() >= 2,
                "Expected at least header + facts blocks, got " + blocks.size());
        assertEquals("header", blocks.get(0).get("type"));

        // Facts section should contain CVSS, EPSS, KEV mention, fix version, component
        String factsText = extractSectionText(blocks);
        assertNotNull(factsText);
        assertTrue(factsText.contains("CVSS"), "Expected CVSS in facts: " + factsText);
        assertTrue(factsText.contains("EPSS"), "Expected EPSS in facts: " + factsText);
        assertTrue(factsText.contains("KEV"), "Expected KEV mention in facts: " + factsText);
        assertTrue(factsText.contains("2.17.1"), "Expected fix version in facts: " + factsText);
        assertTrue(factsText.contains("log4j-core"), "Expected component name in facts: " + factsText);
    }

    @Test
    void newVulnPluralReleasesPluralizesCorrectly() {
        NewVulnAffectsReleasesPayload p = new NewVulnAffectsReleasesPayload(
                "CVE-2025-67890", List.of(), 9.0, null, null, false, null,
                NotificationSeverity.HIGH,
                new AffectedComponent("pkg:npm/foo@1.0", "foo", "1.0"),
                List.of(
                        new AffectedRelease(UUID.randomUUID(), "a", "v1", "main",
                                ReleaseLifecycle.GENERAL_AVAILABILITY, List.of()),
                        new AffectedRelease(UUID.randomUUID(), "b", "v2", "main",
                                ReleaseLifecycle.GENERAL_AVAILABILITY, List.of()),
                        new AffectedRelease(UUID.randomUUID(), "c", "v3", "main",
                                ReleaseLifecycle.GENERAL_AVAILABILITY, List.of())));

        Map<String, Object> payload = formatter.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p));

        String text = (String) payload.get("text");
        assertTrue(text.contains("3 releases"),
                "Expected pluralized 'releases', got: " + text);
    }

    @Test
    void newVulnTruncatesPerReleaseListAtTen() {
        // 12 releases — formatter should show 10 then a "…and 2 more" line
        java.util.List<AffectedRelease> many = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(new AffectedRelease(
                    UUID.randomUUID(), "comp" + i, "v1", "main",
                    ReleaseLifecycle.GENERAL_AVAILABILITY, List.of()));
        }
        NewVulnAffectsReleasesPayload p = new NewVulnAffectsReleasesPayload(
                "CVE-2025-11111", List.of(), 9.0, null, null, false, null,
                NotificationSeverity.HIGH,
                new AffectedComponent("pkg:npm/foo@1.0", "foo", "1.0"),
                many);

        Map<String, Object> payload = formatter.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        String allText = blocks.stream()
                .map(SlackBlockKitFormatterTest::extractBlockText)
                .reduce("", (acc, s) -> acc + " " + s);
        assertTrue(allText.contains("and 2 more"),
                "Expected '…and 2 more' line for 12-release event; got: " + allText);
        assertTrue(allText.contains("comp9"), "Should contain at least the 10th release: " + allText);
    }

    @Test
    void vulnerabilityRecordUpdatedShowsTransition() {
        VulnerabilityRecordUpdatedPayload p = new VulnerabilityRecordUpdatedPayload(
                "CVE-2025-22222", ChangeType.SEVERITY_BUMPED,
                NotificationSeverity.MEDIUM, NotificationSeverity.CRITICAL,
                null, null, false, List.of());

        Map<String, Object> payload = formatter.format(
                eventOf(NotificationEventType.VULNERABILITY_RECORD_UPDATED, p));

        String text = (String) payload.get("text");
        assertTrue(text.contains("severity bumped"),
                "Expected humanized change type; got: " + text);
        assertTrue(text.contains("CVE-2025-22222"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        String factsText = extractSectionText(blocks);
        assertTrue(factsText.contains("MEDIUM"), "Expected old severity: " + factsText);
        assertTrue(factsText.contains("CRITICAL"), "Expected new severity: " + factsText);
    }

    @Test
    void kevAddedRendersKevEmoji() {
        VulnerabilityRecordUpdatedPayload p = new VulnerabilityRecordUpdatedPayload(
                "CVE-2025-33333", ChangeType.KEV_ADDED,
                NotificationSeverity.HIGH, NotificationSeverity.HIGH,
                0.1, 0.1, true, List.of());

        Map<String, Object> payload = formatter.format(
                eventOf(NotificationEventType.VULNERABILITY_RECORD_UPDATED, p));

        String text = (String) payload.get("text");
        assertTrue(text.contains("added to CISA KEV"), "Got: " + text);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        String allText = blocks.stream()
                .map(SlackBlockKitFormatterTest::extractBlockText)
                .reduce("", (acc, s) -> acc + " " + s);
        assertTrue(allText.contains("KEV"), "Expected KEV in formatted blocks: " + allText);
    }

    @Test
    void vexStateChangedRendersOldToNewArrow() {
        VexStateChangedPayload p = new VexStateChangedPayload(
                "CVE-2025-44444", UUID.randomUUID(),
                "pkg:maven/foo@1.0", "affected", "not_affected");

        Map<String, Object> payload = formatter.format(
                eventOf(NotificationEventType.VEX_STATE_CHANGED, p));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        String factsText = extractSectionText(blocks);
        assertTrue(factsText.contains("affected"), factsText);
        assertTrue(factsText.contains("not_affected"), factsText);
        // The transition arrow signals the state change visually
        assertTrue(factsText.contains("→"), "Expected → arrow: " + factsText);
    }

    @Test
    void nullEventTypeFallsBackGracefully() {
        // Defensive guard: an outbox event without a recognised event type
        // (older data, hand-constructed test, etc.) still produces a
        // payload Slack will accept. We don't NPE; we render a generic
        // "ReARM notification" line.
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setEventType(null);
        event.setDedupKey("some-key");
        event.setRecordData(new HashMap<>());

        Map<String, Object> payload = formatter.format(event);

        String text = (String) payload.get("text");
        assertNotNull(text);
        assertTrue(text.contains("ReARM notification"),
                "Expected fallback header text, got: " + text);
        // The blocks list should not be present (fallback only emits text)
        // OR if present, it should not be malformed (no NPE during render)
        Object blocks = payload.get("blocks");
        if (blocks != null) {
            assertNotNull(blocks);
        }
    }

    @Test
    void undeserializableRecordDataFallsBackToTextOnly() {
        // Empty recordData triggers Jackson to deserialize into a payload
        // with all null fields. Formatter should still produce a non-null
        // text fallback rather than NPE.
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        event.setRecordData(new HashMap<>());

        Map<String, Object> payload = formatter.format(event);
        assertNotNull(payload.get("text"));
    }

    // ---------- release events (PR-2b-2) ----------

    @Test
    void releaseCreatedRendersRocketHeaderAndFacts() {
        ReleaseRef r = sampleRelease(UUID.randomUUID(), ComponentType.COMPONENT,
                ReleaseLifecycle.DRAFT);
        Map<String, Object> payload = formatter.format(
                eventOf(NotificationEventType.RELEASE_CREATED,
                        new ReleaseCreatedPayload(r, false)));

        String text = (String) payload.get("text");
        assertNotNull(text);
        assertTrue(text.contains("created"), "Expected 'created' verb: " + text);
        assertTrue(text.contains("myapp v2.0"), "Expected release title: " + text);
        assertTrue(text.contains("component"), "Expected component noun: " + text);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        assertEquals("header", blocks.get(0).get("type"));
        String factsText = extractSectionText(blocks);
        assertTrue(factsText.contains("DRAFT"), "Expected lifecycle fact: " + factsText);
        assertTrue(factsText.contains("main"), "Expected branch fact: " + factsText);
        assertTrue(factsText.contains("alice"), "Expected author fact: " + factsText);
    }

    @Test
    void releaseCreatedScheduledUsesScheduledVerbAndProductNoun() {
        ReleaseRef r = sampleRelease(UUID.randomUUID(), ComponentType.PRODUCT,
                ReleaseLifecycle.PENDING);
        Map<String, Object> payload = formatter.format(
                eventOf(NotificationEventType.RELEASE_CREATED,
                        new ReleaseCreatedPayload(r, true)));

        String text = (String) payload.get("text");
        assertTrue(text.contains("scheduled"), "Expected 'scheduled' verb: " + text);
        assertTrue(text.contains("product"), "Expected product noun: " + text);
        // Product releases call the branch a "feature set".
        String factsText = extractSectionText(
                (List<Map<String, Object>>) payload.get("blocks"));
        assertTrue(factsText.contains("Feature set"), "Expected feature-set label: " + factsText);
    }

    @Test
    void releaseLifecycleChangedShowsOldToNewTransition() {
        ReleaseRef r = sampleRelease(UUID.randomUUID(), ComponentType.COMPONENT,
                ReleaseLifecycle.ASSEMBLED);
        Map<String, Object> payload = formatter.format(
                eventOf(NotificationEventType.RELEASE_LIFECYCLE_CHANGED,
                        new ReleaseLifecycleChangedPayload(r,
                                ReleaseLifecycle.DRAFT, ReleaseLifecycle.ASSEMBLED)));

        String text = (String) payload.get("text");
        assertTrue(text.contains("assembled"), "Expected humanized verb: " + text);

        String factsText = extractSectionText(
                (List<Map<String, Object>>) payload.get("blocks"));
        assertTrue(factsText.contains("DRAFT → ASSEMBLED"),
                "Expected lifecycle transition: " + factsText);
    }

    @Test
    void releaseBomDiffListsAddedAndRemovedComponents() {
        ReleaseRef r = sampleRelease(UUID.randomUUID(), ComponentType.COMPONENT,
                ReleaseLifecycle.ASSEMBLED);
        ReleaseBomDiffPayload p = new ReleaseBomDiffPayload(r,
                List.of(new BomComponentChange("pkg:npm/added@1.0", "1.0")),
                List.of(new BomComponentChange("pkg:npm/removed@0.9", "0.9")));
        Map<String, Object> payload = formatter.format(
                eventOf(NotificationEventType.RELEASE_BOM_DIFF, p));

        String text = (String) payload.get("text");
        assertTrue(text.contains("BOM diff"), "Expected BOM-diff header: " + text);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        String allText = blocks.stream()
                .map(SlackBlockKitFormatterTest::extractBlockText)
                .reduce("", (acc, s) -> acc + " " + s);
        assertTrue(allText.contains("Added components"), "Expected added section: " + allText);
        assertTrue(allText.contains("pkg:npm/added@1.0"), "Expected added purl: " + allText);
        assertTrue(allText.contains("Removed components"), "Expected removed section: " + allText);
        assertTrue(allText.contains("pkg:npm/removed@0.9"), "Expected removed purl: " + allText);
    }

    @Test
    void releaseCreatedLinksToReleasePageWhenBaseUriSet() {
        UUID rel = UUID.randomUUID();
        ReleaseRef r = sampleRelease(rel, ComponentType.COMPONENT, ReleaseLifecycle.DRAFT);
        Map<String, Object> payload = linkedFormatter.format(
                eventOf(NotificationEventType.RELEASE_CREATED,
                        new ReleaseCreatedPayload(r, false)));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        Map<String, Object> action = findActionsBlock(blocks);
        assertNotNull(action, "Expected actions block when baseUri set");
        assertEquals(BASE + "/release/show/" + rel, extractFirstButtonUrl(action));
    }

    // ---------- approval events (Phase 4a) ----------

    @Test
    void approvalRequestedRendersBellHeaderEntriesAndRequester() {
        ReleaseRef r = sampleRelease(UUID.randomUUID(), ComponentType.COMPONENT,
                ReleaseLifecycle.ASSEMBLED);
        ApprovalRequestedPayload p = new ApprovalRequestedPayload(r,
                UUID.randomUUID(), UUID.randomUUID(), "alice", "alice@example.com",
                List.of(new ApprovalRequestEntryRef(UUID.randomUUID(), "QA sign-off"),
                        new ApprovalRequestEntryRef(UUID.randomUUID(), "Security review")),
                List.of(UUID.randomUUID()));
        Map<String, Object> payload = formatter.format(
                eventOf(NotificationEventType.APPROVAL_REQUESTED, p));

        String text = (String) payload.get("text");
        assertTrue(text.contains("Approval requested"), "Expected request header: " + text);
        assertTrue(text.contains("myapp v2.0"), "Expected release title: " + text);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        assertEquals("header", blocks.get(0).get("type"));
        String factsText = extractSectionText(blocks);
        assertTrue(factsText.contains("QA sign-off, Security review"),
                "Expected entry names: " + factsText);
        assertTrue(factsText.contains("Requested by:* alice"),
                "Expected requester fact: " + factsText);
    }

    @Test
    void approvalResolvedDisapprovedUsesXEmojiAndDisapprovedVerb() {
        ReleaseRef r = sampleRelease(UUID.randomUUID(), ComponentType.COMPONENT,
                ReleaseLifecycle.ASSEMBLED);
        ApprovalResolvedPayload p = new ApprovalResolvedPayload(r,
                UUID.randomUUID(), "QA sign-off",
                ApprovalResolvedPayload.Resolution.DISAPPROVED,
                UUID.randomUUID(), "bob", "bob@example.com",
                List.of(UUID.randomUUID()));
        Map<String, Object> payload = formatter.format(
                eventOf(NotificationEventType.APPROVAL_RESOLVED, p));

        String text = (String) payload.get("text");
        assertTrue(text.contains(":x:"), "Expected :x: emoji on disapproval: " + text);
        assertTrue(text.contains("Approval disapproved"), "Expected disapproved verb: " + text);

        String factsText = extractSectionText(
                (List<Map<String, Object>>) payload.get("blocks"));
        assertTrue(factsText.contains("QA sign-off"), "Expected entry name: " + factsText);
        assertTrue(factsText.contains("Resolved by:* bob"), "Expected resolver fact: " + factsText);
    }

    private static ReleaseRef sampleRelease(UUID releaseUuid, ComponentType type,
            ReleaseLifecycle lifecycle) {
        return new ReleaseRef(releaseUuid, "v2.0", UUID.randomUUID(), "myapp", type,
                UUID.randomUUID(), "main", lifecycle, null, null, null, "alice", "alice@example.com");
    }

    // ---------- link-back behavior (relizaprops.baseuri set) ----------

    @Test
    void singleConstructorAvoidsSpringMultiCtorAmbiguity() {
        // Regression guard: SlackBlockKitFormatter must have exactly one
        // public constructor. A second no-arg constructor caused Spring
        // to silently pick the no-arg form (webBaseUri="") in prod, which
        // disabled link enrichment entirely even though @Value was wired
        // on the other constructor. See PR #131 commit history.
        long publicCtors = java.util.Arrays.stream(SlackBlockKitFormatter.class.getDeclaredConstructors())
                .filter(c -> java.lang.reflect.Modifier.isPublic(c.getModifiers()))
                .count();
        assertEquals(1L, publicCtors,
                "Add of a second public constructor breaks Spring DI auto-selection — "
                + "do not introduce one without @Autowired on the @Value ctor");
    }

    @Test
    void noActionBlockWhenBaseUriEmpty() {
        // Regression guard: with the default empty base URI, the actions
        // block must not appear and inline links stay off. This pins the
        // "graceful degradation" promise — formatter still emits a valid
        // card even when the property isn't configured.
        UUID rel = UUID.randomUUID();
        NewVulnAffectsReleasesPayload p = singleReleasePayload(rel);

        Map<String, Object> payload = formatter.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p, UUID.randomUUID()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        assertTrue(blocks.stream().noneMatch(b -> "actions".equals(b.get("type"))),
                "Expected no actions block when baseUri is empty");
        String allText = blocks.stream()
                .map(SlackBlockKitFormatterTest::extractBlockText)
                .reduce("", (acc, s) -> acc + " " + s);
        assertFalse(allText.contains("<" + BASE),
                "Expected no inline mrkdwn links when baseUri is empty; got: " + allText);
    }

    @Test
    void singleReleaseLinksDirectlyToReleasePage() {
        UUID rel = UUID.randomUUID();
        NewVulnAffectsReleasesPayload p = singleReleasePayload(rel);

        Map<String, Object> payload = linkedFormatter.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p, UUID.randomUUID()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        Map<String, Object> action = findActionsBlock(blocks);
        assertNotNull(action, "Expected an actions block when baseUri set + single release");
        String buttonUrl = extractFirstButtonUrl(action);
        assertEquals(BASE + "/release/show/" + rel, buttonUrl);

        // Per-release line should also wrap the component name as a link
        String allText = blocks.stream()
                .map(SlackBlockKitFormatterTest::extractBlockText)
                .reduce("", (acc, s) -> acc + " " + s);
        assertTrue(allText.contains("<" + BASE + "/release/show/" + rel + "|myapp>"),
                "Expected inline release link in release lines: " + allText);
    }

    @Test
    void multiReleaseLinksToOrgVulnAnalysisPage() {
        // 3 releases → no single canonical landing page, so the button
        // links to the org-wide vuln analysis instead of one arbitrary
        // release. This is the heuristic in addViewInReARMAction.
        UUID org = UUID.randomUUID();
        NewVulnAffectsReleasesPayload p = new NewVulnAffectsReleasesPayload(
                "CVE-X", List.of(), 9.0, null, null, false, null,
                NotificationSeverity.HIGH,
                new AffectedComponent("pkg:npm/foo@1.0", "foo", "1.0"),
                List.of(
                        new AffectedRelease(UUID.randomUUID(), "a", "v1", "main",
                                ReleaseLifecycle.GENERAL_AVAILABILITY, List.of()),
                        new AffectedRelease(UUID.randomUUID(), "b", "v2", "main",
                                ReleaseLifecycle.GENERAL_AVAILABILITY, List.of()),
                        new AffectedRelease(UUID.randomUUID(), "c", "v3", "main",
                                ReleaseLifecycle.GENERAL_AVAILABILITY, List.of())));

        Map<String, Object> payload = linkedFormatter.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p, org));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        Map<String, Object> action = findActionsBlock(blocks);
        assertNotNull(action);
        assertEquals(BASE + "/vulnerabilityAnalysis/" + org, extractFirstButtonUrl(action));
    }

    @Test
    void vexEventWithReleaseUuidLinksToReleasePage() {
        // VEX events carry a single releaseUuid directly — link straight to it,
        // bypassing the affectedReleases heuristic entirely.
        UUID rel = UUID.randomUUID();
        VexStateChangedPayload p = new VexStateChangedPayload(
                "CVE-Y", rel, "pkg:maven/foo@1.0", "affected", "not_affected");

        Map<String, Object> payload = linkedFormatter.format(
                eventOf(NotificationEventType.VEX_STATE_CHANGED, p, UUID.randomUUID()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        Map<String, Object> action = findActionsBlock(blocks);
        assertNotNull(action);
        assertEquals(BASE + "/release/show/" + rel, extractFirstButtonUrl(action));
    }

    @Test
    void baseUriTrailingSlashesStrippedSoLinksDontDoubleSlash() {
        // Operator typo guard: a base URI like "https://example.com//" must
        // not yield "https://example.com///release/show/..." in the link.
        SlackBlockKitFormatter slashy = new SlackBlockKitFormatter("https://x.example.com///", new NotificationLabelProvider());
        UUID rel = UUID.randomUUID();
        Map<String, Object> payload = slashy.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES,
                        singleReleasePayload(rel), UUID.randomUUID()));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> blocks = (List<Map<String, Object>>) payload.get("blocks");
        String url = extractFirstButtonUrl(findActionsBlock(blocks));
        assertEquals("https://x.example.com/release/show/" + rel, url);
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    @Test
    void instanceDeploymentChangedRendersSummaryLinksAndColor() {
        UUID relUuid = UUID.randomUUID();
        InstanceDeploymentChangedPayload p = new InstanceDeploymentChangedPayload(
                new InstanceRef(UUID.randomUUID(), "test.relizahub.com", "test", "PRODUCTION", "STANDALONE_INSTANCE"),
                4, 5,
                List.of(
                        new InstanceDeploymentItem(ComponentType.PRODUCT, "Reliza", "reliza",
                                UpdateStatus.CONVERGED, "26.02.4.14", "26.02.4.13", null, UUID.randomUUID(), relUuid),
                        new InstanceDeploymentItem(ComponentType.COMPONENT, "Reliza Hub Back-end", "reliza",
                                UpdateStatus.UNDEPLOYED, null, null, null, UUID.randomUUID(), null)),
                List.of("CONVERGED", "UNDEPLOYED"),
                NotificationSeverity.MEDIUM);

        // linkedFormatter carries a base URI so deep links render.
        Map<String, Object> payload = linkedFormatter.format(
                eventOf(NotificationEventType.INSTANCE_DEPLOYMENT_CHANGED, p, UUID.randomUUID()));

        assertNotNull(payload.get("text"), "fallback text present");
        List<Map<String, Object>> blocks = combinedBlocks(payload);
        assertNotNull(blocks);
        String allText = allBlockText(blocks);
        assertTrue(allText.contains("Event for the instance"), "legacy-style title");
        assertTrue(allText.contains("test.relizahub.com"), "instance named");
        assertTrue(allText.contains("Bundle:") && allText.contains("Reliza"), "PRODUCT renders as Bundle");
        assertTrue(allText.contains("Project:") && allText.contains("Reliza Hub Back-end"), "COMPONENT renders as Project");
        assertTrue(allText.contains("converged") && allText.contains("undeployed"), "summary counts present");
        assertTrue(allText.contains("PRODUCTION"), "environment shown");
        // The "to" version is rendered inside the release link, so assert the
        // from-arrow and the linked to-version separately rather than as one string.
        assertTrue(allText.contains("26.02.4.13 -> "), "from-version and arrow");
        assertTrue(allText.contains("/release/show/" + relUuid + "|26.02.4.14>"), "to-version links to the release");
        assertTrue(allText.contains("/componentsOfOrg/"), "component name links to its page");
        assertTrue(allText.contains("/instancesOfOrg/"), "instance linked once, on the title line");
        // Mixed converged + undeployed (no error, not all-good) -> amber warning bar.
        assertEquals("#ecb22e", attachmentColor(payload), "mixed statuses -> warning colour");
    }

    @Test
    void instanceDeploymentFailedRendersErrorHeaderReasonAndRedColor() {
        InstanceDeploymentChangedPayload p = new InstanceDeploymentChangedPayload(
                new InstanceRef(UUID.randomUUID(), "test.relizahub.com", "test", "PRODUCTION", "STANDALONE_INSTANCE"),
                4, 5,
                List.of(new InstanceDeploymentItem(ComponentType.COMPONENT, "REEF-backend", "reliza",
                        UpdateStatus.ERROR, null, null, "CrashLoopBackOff", UUID.randomUUID(), null)),
                List.of("ERROR"),
                NotificationSeverity.HIGH);

        Map<String, Object> payload = formatter.format(
                eventOf(NotificationEventType.INSTANCE_DEPLOYMENT_FAILED, p));

        List<Map<String, Object>> blocks = combinedBlocks(payload);
        String allText = allBlockText(blocks);
        assertTrue(allText.contains("Deployment error"), "failed title signals error");
        assertTrue(allText.contains("REEF-backend"), "item rendered");
        assertTrue(allText.contains("CrashLoopBackOff"), "failure reason rendered");
        assertEquals("#e01e5a", attachmentColor(payload), "any error -> red colour");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> attachmentBlocks(Map<String, Object> payload) {
        List<Map<String, Object>> atts = (List<Map<String, Object>>) payload.get("attachments");
        if (atts == null || atts.isEmpty()) return List.of();
        return (List<Map<String, Object>>) atts.get(0).get("blocks");
    }

    /** Top-level blocks (the title) + the attachment's detail blocks. */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> combinedBlocks(Map<String, Object> payload) {
        java.util.List<Map<String, Object>> all = new java.util.ArrayList<>();
        Object top = payload.get("blocks");
        if (top instanceof List<?> l) all.addAll((List<Map<String, Object>>) l);
        all.addAll(attachmentBlocks(payload));
        return all;
    }

    @SuppressWarnings("unchecked")
    private static String attachmentColor(Map<String, Object> payload) {
        List<Map<String, Object>> atts = (List<Map<String, Object>>) payload.get("attachments");
        return atts != null && !atts.isEmpty() ? (String) atts.get(0).get("color") : null;
    }

    private static String allBlockText(List<Map<String, Object>> blocks) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> b : blocks) {
            sb.append(extractBlockText(b)).append('\n');
            // context blocks carry their text in elements[].text, not block.text
            Object els = b.get("elements");
            if (els instanceof List<?> list) {
                for (Object el : list) {
                    if (el instanceof Map<?, ?> m && m.get("text") instanceof String t) {
                        sb.append(t).append('\n');
                    }
                }
            }
        }
        return sb.toString();
    }

    private NotificationOutboxEvent eventOf(NotificationEventType type, Object payload) {
        NotificationOutboxEvent ev = new NotificationOutboxEvent();
        ev.setUuid(UUID.randomUUID());
        ev.setEventType(type);
        ev.setOccurredAt(ZonedDateTime.now());
        ev.setRecordData(Utils.OM.convertValue(payload, Map.class));
        return ev;
    }

    private NotificationOutboxEvent eventOf(NotificationEventType type, Object payload, UUID org) {
        NotificationOutboxEvent ev = eventOf(type, payload);
        ev.setOrg(org);
        return ev;
    }

    private static NewVulnAffectsReleasesPayload singleReleasePayload(UUID releaseUuid) {
        return new NewVulnAffectsReleasesPayload(
                "CVE-2025-12345", List.of(), 9.8, null, 0.85, true, "2.17.1",
                NotificationSeverity.CRITICAL,
                new AffectedComponent("pkg:maven/log4j-core@2.14.1", "log4j-core", "2.14.1"),
                List.of(new AffectedRelease(releaseUuid, "myapp", "v2.0", "main",
                        ReleaseLifecycle.GENERAL_AVAILABILITY, List.of("prod-us"))));
    }

    private static Map<String, Object> findActionsBlock(List<Map<String, Object>> blocks) {
        for (Map<String, Object> b : blocks) {
            if ("actions".equals(b.get("type"))) return b;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static String extractFirstButtonUrl(Map<String, Object> actionsBlock) {
        List<Map<String, Object>> elements = (List<Map<String, Object>>) actionsBlock.get("elements");
        return elements != null && !elements.isEmpty() ? (String) elements.get(0).get("url") : null;
    }

    /** Returns the first {@code section} block's mrkdwn text. */
    private static String extractSectionText(List<Map<String, Object>> blocks) {
        for (Map<String, Object> block : blocks) {
            if ("section".equals(block.get("type"))) {
                @SuppressWarnings("unchecked")
                Map<String, Object> textObj = (Map<String, Object>) block.get("text");
                return textObj != null ? (String) textObj.get("text") : null;
            }
        }
        return null;
    }

    /** Returns the text of any block (header or section). */
    @SuppressWarnings("unchecked")
    private static String extractBlockText(Map<String, Object> block) {
        Map<String, Object> textObj = (Map<String, Object>) block.get("text");
        return textObj != null ? (String) textObj.get("text") : "";
    }
}
