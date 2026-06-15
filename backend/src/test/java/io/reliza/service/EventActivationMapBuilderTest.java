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
 * Covers the happy path of each event type's activation surface plus the
 * deserialization-failure path. The CEL surface (every field name a
 * customer can reference) is the public API of the builder — these tests
 * pin the names so a typo refactor breaks loudly.
 */
class EventActivationMapBuilderTest {

    private final EventActivationMapBuilder builder = new EventActivationMapBuilder();

    @Test
    void newVulnAffectsReleasesExposesAllPayloadFields() {
        UUID releaseUuid = UUID.randomUUID();
        NewVulnAffectsReleasesPayload payload = new NewVulnAffectsReleasesPayload(
                "CVE-2025-12345",
                List.of("GHSA-aaaa-bbbb-cccc"),
                9.8,
                "CVSS:3.1/AV:N/...",
                0.92,
                Boolean.TRUE,
                "2.17.1",
                NotificationSeverity.CRITICAL,
                new AffectedComponent("pkg:maven/...", "log4j-core", "2.14.1"),
                List.of(new AffectedRelease(
                        releaseUuid, "myapp", "v2.0", "main",
                        ReleaseLifecycle.GENERAL_AVAILABILITY,
                        List.of("prod-us", "prod-eu"))));

        Map<String, Object> activation = builder.buildForEvent(
                eventOf(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, payload));

        Map<String, Object> event = castEvent(activation);
        assertEquals("NEW_VULN_AFFECTS_RELEASES", event.get("type"));
        assertEquals("CVE-2025-12345", event.get("vulnPrimaryId"));
        assertEquals(List.of("GHSA-aaaa-bbbb-cccc"), event.get("aliases"));
        assertEquals(9.8, event.get("cvssScore"));
        assertEquals(0.92, event.get("epssScore"));
        assertEquals(Boolean.TRUE, event.get("kevListed"));
        assertEquals("2.17.1", event.get("fixVersion"));
        assertEquals("CRITICAL", event.get("severity"));

        @SuppressWarnings("unchecked")
        Map<String, Object> component = (Map<String, Object>) event.get("affectedComponent");
        assertEquals("log4j-core", component.get("name"));
        assertEquals("2.14.1", component.get("version"));

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> releases = (List<Map<String, Object>>) event.get("affectedReleases");
        assertEquals(1, releases.size());
        Map<String, Object> r = releases.get(0);
        assertEquals(releaseUuid.toString(), r.get("uuid"));
        assertEquals("myapp", r.get("component"));
        assertEquals("v2.0", r.get("version"));
        assertEquals("GENERAL_AVAILABILITY", r.get("lifecycle"));
        assertEquals(List.of("prod-us", "prod-eu"), r.get("deployedEnvs"));
    }

    @Test
    void vulnerabilityRecordUpdatedSurfacesNewSeverityAsEventSeverity() {
        VulnerabilityRecordUpdatedPayload payload = new VulnerabilityRecordUpdatedPayload(
                "CVE-2025-99999",
                ChangeType.SEVERITY_BUMPED,
                NotificationSeverity.MEDIUM,
                NotificationSeverity.CRITICAL,
                null,
                null,
                Boolean.FALSE,
                List.of());

        Map<String, Object> activation = builder.buildForEvent(
                eventOf(NotificationEventType.VULNERABILITY_RECORD_UPDATED, payload));

        Map<String, Object> event = castEvent(activation);
        assertEquals("SEVERITY_BUMPED", event.get("changeType"));
        assertEquals("MEDIUM", event.get("oldSeverity"));
        assertEquals("CRITICAL", event.get("newSeverity"));
        // Cross-type uniformity: filters reading event.severity see the
        // post-change severity for VULNERABILITY_RECORD_UPDATED.
        assertEquals("CRITICAL", event.get("severity"));
    }

    @Test
    void vexStateChangedExposesPayloadFields() {
        UUID releaseUuid = UUID.randomUUID();
        VexStateChangedPayload payload = new VexStateChangedPayload(
                "CVE-2025-77777",
                releaseUuid,
                "pkg:maven/foo/bar@1.0",
                "affected",
                "not_affected");

        Map<String, Object> activation = builder.buildForEvent(
                eventOf(NotificationEventType.VEX_STATE_CHANGED, payload));

        Map<String, Object> event = castEvent(activation);
        assertEquals("CVE-2025-77777", event.get("vulnPrimaryId"));
        assertEquals(releaseUuid.toString(), event.get("releaseUuid"));
        assertEquals("affected", event.get("oldState"));
        assertEquals("not_affected", event.get("newState"));
    }

    @Test
    void commonFieldsAlwaysPresentRegardlessOfPayload() {
        NotificationOutboxEvent ev = new NotificationOutboxEvent();
        ev.setOrg(UUID.fromString("00000000-0000-0000-0000-000000000001"));
        ev.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        ev.setDedupKey("test-dedup");
        ev.setOccurredAt(ZonedDateTime.parse("2026-05-26T10:00:00Z"));
        ev.setRecordData(null); // no payload

        Map<String, Object> activation = builder.buildForEvent(ev);
        Map<String, Object> event = castEvent(activation);

        // Type-specific keys absent because recordData is null …
        assertFalse(event.containsKey("vulnPrimaryId"),
                "Type-specific keys should be absent when payload is missing");
        // … but the common surface is always populated.
        assertEquals("NEW_VULN_AFFECTS_RELEASES", event.get("type"));
        assertEquals("00000000-0000-0000-0000-000000000001", event.get("org"));
        assertEquals("test-dedup", event.get("dedupKey"));
        assertTrue(((String) event.get("occurredAt")).startsWith("2026-05-26"));
    }

    @Test
    void deserializationFailureLeavesEventMapMinimal() {
        // Payload has an invalid enum value for 'severity'. Jackson can't
        // coerce "NOT_A_SEVERITY_VALUE" into the NotificationSeverity enum,
        // so convertValue throws — builder's deserialize() catches, logs,
        // returns null, and the populator early-returns. The 'event' map
        // gets only the common fields. (Note: Jackson DOES auto-coerce
        // many type mismatches — int → string for example — so we
        // deliberately use an enum-value mismatch as the trigger.)
        Map<String, Object> garbage = new HashMap<>();
        garbage.put("severity", "NOT_A_SEVERITY_VALUE");

        NotificationOutboxEvent ev = new NotificationOutboxEvent();
        ev.setOrg(UUID.randomUUID());
        ev.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        ev.setRecordData(garbage);

        Map<String, Object> activation = builder.buildForEvent(ev);
        Map<String, Object> event = castEvent(activation);

        // Common fields populated …
        assertNotNull(event.get("type"));
        // … type-specific fields absent (deserialize() returned null and
        // the populator early-returned).
        assertFalse(event.containsKey("affectedComponent"));
        assertFalse(event.containsKey("affectedReleases"));
    }

    // ---------- helpers ----------

    @SuppressWarnings("unchecked")
    private NotificationOutboxEvent eventOf(NotificationEventType type, Object payload) {
        NotificationOutboxEvent ev = new NotificationOutboxEvent();
        ev.setOrg(UUID.randomUUID());
        ev.setEventType(type);
        ev.setOccurredAt(ZonedDateTime.now());
        // Round-trip through Map.class — sufficient for our payloads
        // (records / nested records of scalars + lists). Avoids needing
        // a TypeReference and the Jackson-3 (tools.jackson.*) import surface.
        ev.setRecordData(Utils.OM.convertValue(payload, Map.class));
        return ev;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castEvent(Map<String, Object> activation) {
        return (Map<String, Object>) activation.get("event");
    }
}
