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
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.notifications.AffectedComponent;
import io.reliza.model.dto.notifications.AffectedRelease;
import io.reliza.model.dto.notifications.NewVulnAffectsReleasesPayload;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.dto.notifications.VexStateChangedPayload;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload.ChangeType;

/**
 * Pins the Teams Adaptive Card envelope shape across event types.
 * Mirrors {@code SlackBlockKitFormatterTest} so the same regression
 * surface is covered for both channels.
 */
class TeamsAdaptiveCardFormatterTest {

    private static final String BASE = "https://rearm.example.com";

    /** No base URI — link-back actions stay off. */
    private final TeamsAdaptiveCardFormatter formatter = new TeamsAdaptiveCardFormatter("", new NotificationLabelProvider());

    /** With base URI — actions render. */
    private final TeamsAdaptiveCardFormatter linkedFormatter = new TeamsAdaptiveCardFormatter(BASE, new NotificationLabelProvider());

    @Test
    void singleConstructorAvoidsSpringMultiCtorAmbiguity() {
        // Same regression guard as the Slack + Email formatters. A
        // second public constructor would let Spring silently pick the
        // no-arg form and disable link-back.
        long publicCtors = java.util.Arrays.stream(TeamsAdaptiveCardFormatter.class.getDeclaredConstructors())
                .filter(c -> java.lang.reflect.Modifier.isPublic(c.getModifiers()))
                .count();
        assertEquals(1L, publicCtors,
                "Adding a second public constructor would break Spring DI auto-selection");
    }

    @Test
    void teamsEnvelopeHasMessageTypeAndOneAttachment() {
        NewVulnAffectsReleasesPayload p = singleReleasePayload(UUID.randomUUID());
        Map<String, Object> envelope = formatter.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p, UUID.randomUUID()));

        assertEquals("message", envelope.get("type"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) envelope.get("attachments");
        assertNotNull(attachments);
        assertEquals(1, attachments.size());
        assertEquals("application/vnd.microsoft.card.adaptive", attachments.get(0).get("contentType"));
        @SuppressWarnings("unchecked")
        Map<String, Object> card = (Map<String, Object>) attachments.get(0).get("content");
        assertNotNull(card);
        assertEquals("AdaptiveCard", card.get("type"));
        assertEquals("1.5", card.get("version"));
    }

    @Test
    void newVulnRendersHeaderFactsAndReleaseList() {
        UUID releaseUuid = UUID.randomUUID();
        NewVulnAffectsReleasesPayload p = singleReleasePayload(releaseUuid);

        Map<String, Object> envelope = formatter.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p, UUID.randomUUID()));

        String allText = stringifyCard(envelope);
        assertTrue(allText.contains("CRITICAL vulnerability"), allText);
        assertTrue(allText.contains("CVE-2025-12345"), allText);
        assertTrue(allText.contains("CVSS"), allText);
        assertTrue(allText.contains("EPSS"), allText);
        assertTrue(allText.contains("log4j-core"), allText);
        assertTrue(allText.contains("myapp"), allText);
    }

    @Test
    void pluralReleaseLineWordingMatchesCount() {
        NewVulnAffectsReleasesPayload p = new NewVulnAffectsReleasesPayload(
                "CVE-X", List.of(), 9.0, null, null, false, null,
                NotificationSeverity.HIGH,
                new AffectedComponent("pkg:npm/foo@1.0", "foo", "1.0"),
                List.of(
                        new AffectedRelease(UUID.randomUUID(), "a", "v1", "main",
                                ReleaseLifecycle.GENERAL_AVAILABILITY, List.of()),
                        new AffectedRelease(UUID.randomUUID(), "b", "v2", "main",
                                ReleaseLifecycle.GENERAL_AVAILABILITY, List.of())));

        String allText = stringifyCard(formatter.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p, UUID.randomUUID())));
        assertTrue(allText.contains("2 releases"), allText);
    }

    @Test
    void manyReleasesTruncatedAtTen() {
        java.util.List<AffectedRelease> many = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) {
            many.add(new AffectedRelease(UUID.randomUUID(), "comp" + i, "v1", "main",
                    ReleaseLifecycle.GENERAL_AVAILABILITY, List.of()));
        }
        NewVulnAffectsReleasesPayload p = new NewVulnAffectsReleasesPayload(
                "CVE-Y", List.of(), 9.0, null, null, false, null,
                NotificationSeverity.HIGH,
                new AffectedComponent("pkg:npm/foo@1.0", "foo", "1.0"),
                many);

        String allText = stringifyCard(formatter.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p, UUID.randomUUID())));
        assertTrue(allText.contains("2 more"), allText);
        assertTrue(allText.contains("comp9"), allText);
    }

    @Test
    void vulnerabilityRecordUpdatedShowsTransitionFacts() {
        VulnerabilityRecordUpdatedPayload p = new VulnerabilityRecordUpdatedPayload(
                "CVE-2025-22222", ChangeType.SEVERITY_BUMPED,
                NotificationSeverity.MEDIUM, NotificationSeverity.CRITICAL,
                null, null, false, List.of());

        String allText = stringifyCard(formatter.format(
                eventOf(NotificationEventType.VULNERABILITY_RECORD_UPDATED, p, UUID.randomUUID())));
        assertTrue(allText.contains("severity bumped"), allText);
        assertTrue(allText.contains("MEDIUM"), allText);
        assertTrue(allText.contains("CRITICAL"), allText);
    }

    @Test
    void vexEventRendersStateTransition() {
        VexStateChangedPayload p = new VexStateChangedPayload(
                "CVE-2025-44444", UUID.randomUUID(),
                "pkg:maven/foo@1.0", "affected", "not_affected");

        String allText = stringifyCard(formatter.format(
                eventOf(NotificationEventType.VEX_STATE_CHANGED, p, UUID.randomUUID())));
        assertTrue(allText.contains("VEX state changed"), allText);
        assertTrue(allText.contains("affected"), allText);
        assertTrue(allText.contains("not_affected"), allText);
    }

    @Test
    void linkedFormatterEmbedsViewInReARMAction() {
        UUID rel = UUID.randomUUID();
        Map<String, Object> envelope = linkedFormatter.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES,
                        singleReleasePayload(rel), UUID.randomUUID()));

        List<Map<String, Object>> actions = extractActions(envelope);
        assertNotNull(actions, "Expected actions array when baseUri set");
        assertEquals(1, actions.size());
        assertEquals("Action.OpenUrl", actions.get(0).get("type"));
        assertEquals(BASE + "/release/show/" + rel, actions.get(0).get("url"));
    }

    @Test
    void noActionBlockWhenBaseUriEmpty() {
        Map<String, Object> envelope = formatter.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES,
                        singleReleasePayload(UUID.randomUUID()), UUID.randomUUID()));

        List<Map<String, Object>> actions = extractActions(envelope);
        assertTrue(actions == null || actions.isEmpty(),
                "Expected no actions block when baseUri is empty");
    }

    @Test
    void multiReleaseLinkPathHitsOrgVulnAnalysis() {
        UUID org = UUID.randomUUID();
        NewVulnAffectsReleasesPayload p = new NewVulnAffectsReleasesPayload(
                "CVE-Z", List.of(), 7.0, null, null, false, null,
                NotificationSeverity.HIGH,
                new AffectedComponent("pkg:npm/foo@1", "foo", "1"),
                List.of(
                        new AffectedRelease(UUID.randomUUID(), "a", "v1", "main",
                                ReleaseLifecycle.GENERAL_AVAILABILITY, List.of()),
                        new AffectedRelease(UUID.randomUUID(), "b", "v2", "main",
                                ReleaseLifecycle.GENERAL_AVAILABILITY, List.of())));

        Map<String, Object> envelope = linkedFormatter.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p, org));
        List<Map<String, Object>> actions = extractActions(envelope);
        assertNotNull(actions);
        assertEquals(BASE + "/vulnerabilityAnalysis/" + org, actions.get(0).get("url"));
    }

    @Test
    void vexEventWithReleaseUuidLinksToReleasePage() {
        UUID rel = UUID.randomUUID();
        VexStateChangedPayload p = new VexStateChangedPayload(
                "CVE-V", rel, "pkg:maven/foo@1.0", "affected", "not_affected");

        Map<String, Object> envelope = linkedFormatter.format(
                eventOf(NotificationEventType.VEX_STATE_CHANGED, p, UUID.randomUUID()));
        List<Map<String, Object>> actions = extractActions(envelope);
        assertNotNull(actions);
        assertEquals(BASE + "/release/show/" + rel, actions.get(0).get("url"));
    }

    @Test
    void baseUriTrailingSlashesStrippedSoLinksDontDoubleSlash() {
        TeamsAdaptiveCardFormatter slashy = new TeamsAdaptiveCardFormatter("https://x.example.com///", new NotificationLabelProvider());
        UUID rel = UUID.randomUUID();
        Map<String, Object> envelope = slashy.format(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES,
                        singleReleasePayload(rel), UUID.randomUUID()));
        List<Map<String, Object>> actions = extractActions(envelope);
        assertEquals("https://x.example.com/release/show/" + rel, actions.get(0).get("url"));
    }

    @Test
    void unknownEventTypeFallsBackGracefully() {
        NotificationOutboxEvent ev = new NotificationOutboxEvent();
        ev.setUuid(UUID.randomUUID());
        ev.setEventType(null);
        ev.setDedupKey("some-key");
        ev.setRecordData(new HashMap<>());

        Map<String, Object> envelope = formatter.format(ev);
        assertNotNull(envelope);
        String allText = stringifyCard(envelope);
        assertTrue(allText.contains("ReARM notification"), allText);
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private NotificationOutboxEvent eventOf(NotificationEventType type, Object payload, UUID org) {
        NotificationOutboxEvent ev = new NotificationOutboxEvent();
        ev.setUuid(UUID.randomUUID());
        ev.setEventType(type);
        ev.setOccurredAt(ZonedDateTime.now());
        ev.setRecordData(Utils.OM.convertValue(payload, Map.class));
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

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> extractActions(Map<String, Object> envelope) {
        List<Map<String, Object>> attachments = (List<Map<String, Object>>) envelope.get("attachments");
        Map<String, Object> card = (Map<String, Object>) attachments.get(0).get("content");
        return (List<Map<String, Object>>) card.get("actions");
    }

    @SuppressWarnings("unchecked")
    private static String stringifyCard(Map<String, Object> envelope) {
        // Serialize via Jackson so all text in nested blocks / FactSet
        // shows up in the result string. Lets the tests assert via
        // contains() without traversing the card structure.
        try {
            return Utils.OM.writeValueAsString(envelope);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
