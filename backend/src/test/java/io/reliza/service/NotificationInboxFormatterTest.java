/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.dto.notifications.AffectedComponent;
import io.reliza.model.dto.notifications.AffectedRelease;
import io.reliza.model.dto.notifications.NewVulnAffectsReleasesPayload;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.dto.notifications.VexStateChangedPayload;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload;

/**
 * Covers the canonical inbox rendering: each event type produces a
 * non-null title, a short description, and degrades cleanly when the
 * payload is missing fields or malformed.
 */
class NotificationInboxFormatterTest {

    private final NotificationInboxFormatter formatter = new NotificationInboxFormatter(new NotificationLabelProvider());

    @Test
    void nullEventReturnsEmpty() {
        NotificationInboxFormatter.InboxRendering r = formatter.format(null);
        assertNull(r.title());
        assertNull(r.description());
    }

    @Test
    void eventWithNullTypeReturnsEmpty() {
        NotificationOutboxEvent e = new NotificationOutboxEvent();
        e.setUuid(UUID.randomUUID());
        NotificationInboxFormatter.InboxRendering r = formatter.format(e);
        assertNull(r.title());
        assertNull(r.description());
    }

    @Test
    void newVulnAffectsReleasesRendersTitleAndCvssEpssKev() {
        NewVulnAffectsReleasesPayload p = new NewVulnAffectsReleasesPayload(
                "CVE-2025-12345", List.of("CVE-2025-12345"),
                9.8, "vector", 0.85, true, "1.2.3",
                NotificationSeverity.CRITICAL,
                new AffectedComponent("pkg:maven/foo/bar@1.0.0?type=jar", "bar", "1.0.0"),
                List.of(
                        new AffectedRelease(UUID.randomUUID(), "payments-api", "v2.3", "main", null, List.of()),
                        new AffectedRelease(UUID.randomUUID(), "payments-api", "v2.4", "main", null, List.of())));
        NotificationOutboxEvent e = outboxOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p);

        NotificationInboxFormatter.InboxRendering r = formatter.format(e);
        assertNotNull(r.title());
        assertTrue(r.title().contains("CRITICAL"),    "title should carry severity; got: " + r.title());
        assertTrue(r.title().contains("CVE-2025-12345"), "title should carry vulnId; got: " + r.title());
        assertTrue(r.title().contains("2 releases"),  "title should pluralize; got: " + r.title());
        assertNotNull(r.description());
        assertTrue(r.description().contains("CVSS 9.8"),     "desc should carry CVSS; got: " + r.description());
        assertTrue(r.description().contains("EPSS 0.85"),    "desc should carry EPSS; got: " + r.description());
        assertTrue(r.description().contains("CISA KEV"),     "desc should flag KEV; got: " + r.description());
        assertTrue(r.description().contains("Fix: 1.2.3"),   "desc should carry fix version; got: " + r.description());
        // Component label: purl truncated past @
        assertTrue(r.description().contains("pkg:maven/foo/bar"), "desc should carry component purl; got: " + r.description());
    }

    @Test
    void newVulnAffectsReleasesRendersSingularReleaseAndOmitsMissingFacts() {
        NewVulnAffectsReleasesPayload p = new NewVulnAffectsReleasesPayload(
                "CVE-2025-99999", null,
                null, null, null, false, null,
                NotificationSeverity.HIGH,
                null,
                List.of(new AffectedRelease(UUID.randomUUID(), "auth-svc", "v1.0", "main", null, List.of())));
        NotificationOutboxEvent e = outboxOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p);

        NotificationInboxFormatter.InboxRendering r = formatter.format(e);
        assertTrue(r.title().contains("HIGH"));
        assertTrue(r.title().contains("CVE-2025-99999"));
        assertTrue(r.title().contains("1 release"), "should NOT pluralize at 1; got: " + r.title());
        assertFalse(r.title().contains("1 releases"));
        // No CVSS / EPSS / KEV / fix / component → description null
        assertNull(r.description(), "description with no facts should be null; got: " + r.description());
    }

    @Test
    void newVulnAffectsReleasesZeroReleasesFallsBackToGenericPhrase() {
        // Producer emits before fan-out enrichment populates the list.
        NewVulnAffectsReleasesPayload p = new NewVulnAffectsReleasesPayload(
                "CVE-2025-11111", null,
                7.5, null, null, null, null,
                NotificationSeverity.HIGH, null, null);
        NotificationOutboxEvent e = outboxOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, p);

        NotificationInboxFormatter.InboxRendering r = formatter.format(e);
        assertTrue(r.title().contains("affects releases"),
                "should use generic phrase when count is zero; got: " + r.title());
    }

    @Test
    void vulnRecordUpdatedSeverityBumpedShowsOldToNew() {
        VulnerabilityRecordUpdatedPayload p = new VulnerabilityRecordUpdatedPayload(
                "CVE-2025-22222",
                VulnerabilityRecordUpdatedPayload.ChangeType.SEVERITY_BUMPED,
                NotificationSeverity.MEDIUM, NotificationSeverity.CRITICAL,
                null, null, null, null);
        NotificationOutboxEvent e = outboxOf(NotificationEventType.VULNERABILITY_RECORD_UPDATED, p);

        NotificationInboxFormatter.InboxRendering r = formatter.format(e);
        assertTrue(r.title().contains("CVE-2025-22222"));
        assertTrue(r.title().contains("severity bumped"),
                "title should label change; got: " + r.title());
        assertNotNull(r.description());
        assertTrue(r.description().contains("MEDIUM → CRITICAL"),
                "desc should carry severity transition; got: " + r.description());
    }

    @Test
    void vulnRecordUpdatedKevAddedHasShortDescription() {
        VulnerabilityRecordUpdatedPayload p = new VulnerabilityRecordUpdatedPayload(
                "CVE-2025-33333",
                VulnerabilityRecordUpdatedPayload.ChangeType.KEV_ADDED,
                null, NotificationSeverity.HIGH, null, null, true,
                List.of(new AffectedRelease(UUID.randomUUID(), "x", "v1", "main", null, List.of())));
        NotificationOutboxEvent e = outboxOf(NotificationEventType.VULNERABILITY_RECORD_UPDATED, p);

        NotificationInboxFormatter.InboxRendering r = formatter.format(e);
        assertTrue(r.title().contains("added to CISA KEV"));
        assertTrue(r.description().contains("Severity HIGH"));
        assertTrue(r.description().contains("CISA KEV listed"));
        assertTrue(r.description().contains("1 affected release"));
    }

    @Test
    void vexStateChangedShowsTransitionAndComponent() {
        UUID releaseUuid = UUID.randomUUID();
        VexStateChangedPayload p = new VexStateChangedPayload(
                "CVE-2025-44444", releaseUuid,
                "pkg:maven/lib/baz@2.0.0",
                "affected", "not_affected");
        NotificationOutboxEvent e = outboxOf(NotificationEventType.VEX_STATE_CHANGED, p);

        NotificationInboxFormatter.InboxRendering r = formatter.format(e);
        assertTrue(r.title().contains("CVE-2025-44444"));
        assertTrue(r.title().contains("affected → not_affected"),
                "title should carry transition; got: " + r.title());
        assertTrue(r.description().contains("pkg:maven/lib/baz@2.0.0"),
                "desc should carry component purl as-is (purl already short for VEX); got: " + r.description());
        assertTrue(r.description().contains(releaseUuid.toString()));
    }

    @Test
    void malformedPayloadDegradesToEventTypeLabel() {
        NotificationOutboxEvent e = new NotificationOutboxEvent();
        e.setUuid(UUID.randomUUID());
        e.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        // Garbage record data — the typed parse will leave required
        // fields null. The formatter MUST still produce a non-null
        // title so the inbox row is openable. Anchors the resilience
        // contract: an event row never becomes invisible because its
        // payload was unparseable.
        e.setRecordData(Map.of("unexpected", "garbage"));

        NotificationInboxFormatter.InboxRendering r = formatter.format(e);
        assertNotNull(r);
        assertNotNull(r.title(), "Title must never be null on a known event type, "
                + "even with a malformed payload — the inbox needs an openable row");
    }

    @Test
    void severityBumpedWithOneSidedSeverityFallsBackCleanly() {
        // Legacy payload path: old producer emits only newSeverity
        // (not oldSeverity). Render must not show "null → CRITICAL".
        VulnerabilityRecordUpdatedPayload p = new VulnerabilityRecordUpdatedPayload(
                "CVE-2025-55555",
                VulnerabilityRecordUpdatedPayload.ChangeType.SEVERITY_BUMPED,
                null /* missing old */, NotificationSeverity.CRITICAL,
                null, null, null, null);
        NotificationOutboxEvent e = outboxOf(NotificationEventType.VULNERABILITY_RECORD_UPDATED, p);

        NotificationInboxFormatter.InboxRendering r = formatter.format(e);
        assertNotNull(r.title());
        assertNotNull(r.description());
        assertFalse(r.description().contains("null"),
                "Description must not leak the word 'null'; got: " + r.description());
        assertTrue(r.description().contains("CRITICAL"),
                "Description must surface the new severity; got: " + r.description());
    }

    @Test
    void epssSpikedWithOnlyNewEpssStillRendersIt() {
        // Without this branch, an EPSS_SPIKED row with one-sided EPSS
        // would render an EPSS-themed title with no EPSS number visible
        // — defeating the whole point of the event.
        VulnerabilityRecordUpdatedPayload p = new VulnerabilityRecordUpdatedPayload(
                "CVE-2025-66666",
                VulnerabilityRecordUpdatedPayload.ChangeType.EPSS_SPIKED,
                null, null,
                null /* missing old */, 0.92,
                null, null);
        NotificationOutboxEvent e = outboxOf(NotificationEventType.VULNERABILITY_RECORD_UPDATED, p);

        NotificationInboxFormatter.InboxRendering r = formatter.format(e);
        assertNotNull(r.title());
        assertTrue(r.title().contains("EPSS spiked"));
        assertNotNull(r.description(), "Description must surface the EPSS even on one-sided payloads");
        assertTrue(r.description().contains("0.92"),
                "Description must carry the newEpssScore even when oldEpssScore is null; got: "
                        + r.description());
    }

    @Test
    void vexStateChangedWithOnlyNewStateUsesSetToPhrasing() {
        // Producer emits only newState (old is null). Render must not
        // say "unknown → fixed" — that wrongly implies a transition the
        // producer didn't observe.
        VexStateChangedPayload p = new VexStateChangedPayload(
                "CVE-2025-77777", UUID.randomUUID(),
                "pkg:maven/lib/qux@3.0.0",
                null /* missing old */, "not_affected");
        NotificationOutboxEvent e = outboxOf(NotificationEventType.VEX_STATE_CHANGED, p);

        NotificationInboxFormatter.InboxRendering r = formatter.format(e);
        assertNotNull(r.title());
        assertTrue(r.title().contains("not_affected"));
        assertFalse(r.title().contains("unknown"),
                "Title must not synthesize an 'unknown' state on one-sided payloads; got: " + r.title());
    }

    @SuppressWarnings("unchecked")
    private static NotificationOutboxEvent outboxOf(NotificationEventType type, Object payload) {
        NotificationOutboxEvent e = new NotificationOutboxEvent();
        e.setUuid(UUID.randomUUID());
        e.setOrg(UUID.randomUUID());
        e.setEventType(type);
        e.setRecordData(Utils.OM.convertValue(payload, Map.class));
        return e;
    }
}
