/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.OrganizationData;
import io.reliza.model.dto.notifications.NewVulnAffectsReleasesPayload;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationDeliveryOrigin;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.model.dto.notifications.VexStateChangedPayload;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.SyntheticEventTemplates.Template;

/**
 * Pins the contract of {@link SyntheticEventService#inject}: every
 * template produces a well-formed outbox row tagged
 * {@code origin = SYNTHETIC}, with the right event type, a payload
 * that round-trips through Jackson, and a unique dedup key.
 */
class SyntheticEventServiceTest {

    private NotificationOutboxEventRepository outboxRepo;
    private GetOrganizationService getOrganizationService;
    private IntegrationRepository integrationRepo;
    private SyntheticEventService service;

    @BeforeEach
    void wireMocks() throws Exception {
        outboxRepo = mock(NotificationOutboxEventRepository.class);
        getOrganizationService = mock(GetOrganizationService.class);
        integrationRepo = mock(IntegrationRepository.class);
        // The repo.save() returns whatever was passed in (typical Spring
        // Data behaviour for inserts).
        when(outboxRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // Default: org exists. Individual tests can override.
        when(getOrganizationService.getOrganizationData(any())).thenReturn(Optional.of(mock(OrganizationData.class)));
        service = new SyntheticEventService();
        inject(service, "outboxRepo", outboxRepo);
        inject(service, "getOrganizationService", getOrganizationService);
        inject(service, "integrationRepo", integrationRepo);
    }

    private static void inject(Object target, String field, Object value) throws Exception {
        Field f = SyntheticEventService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void injectingCriticalVulnSingleReleaseProducesOutboxRow() throws Exception {
        UUID org = UUID.randomUUID();
        NotificationOutboxEvent saved = service.inject(
                org, Template.CRITICAL_VULN_SINGLE_SHIPPED_RELEASE);

        assertEquals(org, saved.getOrg());
        assertEquals(NotificationEventType.NEW_VULN_AFFECTS_RELEASES, saved.getEventType());
        assertEquals(NotificationOutboxStatus.PENDING, saved.getStatus());
        assertEquals(NotificationDeliveryOrigin.SYNTHETIC, saved.getOrigin());
        assertNotNull(saved.getRecordData());
        // Payload-shape spot-check
        assertEquals("CVE-2025-12345", saved.getRecordData().get("vulnPrimaryId"));
        assertEquals("CRITICAL", saved.getRecordData().get("severity"));
    }

    @Test
    void everyTemplateRoundTripsThroughJackson() throws Exception {
        UUID org = UUID.randomUUID();
        // Pins that every template's payload serializes cleanly. A new
        // template that introduces an un-Jackson-able field would fail
        // here before reaching any customer org.
        for (Template t : Template.values()) {
            NotificationOutboxEvent saved = service.inject(org, t);
            assertNotNull(saved.getRecordData(), "Template " + t + " produced null recordData");
            assertEquals(SyntheticEventTemplates.eventTypeOf(t), saved.getEventType(),
                    "Template " + t + " mismatched event type");
            assertEquals(NotificationDeliveryOrigin.SYNTHETIC, saved.getOrigin(),
                    "Template " + t + " not tagged SYNTHETIC");
        }
    }

    @Test
    void dedupKeyIsUniquePerInjection() throws Exception {
        UUID org = UUID.randomUUID();
        NotificationOutboxEvent first = service.inject(org, Template.CRITICAL_VULN_SINGLE_SHIPPED_RELEASE);
        NotificationOutboxEvent second = service.inject(org, Template.CRITICAL_VULN_SINGLE_SHIPPED_RELEASE);
        // Same template twice → different dedup_key. Stops the fan-out
        // dedup window from silently suppressing a second test injection.
        assertNotNull(first.getDedupKey());
        assertNotNull(second.getDedupKey());
        assertTrue(!first.getDedupKey().equals(second.getDedupKey()),
                "Synthetic injections of the same template should yield distinct dedup keys");
        // And both should be namespaced for findability
        assertTrue(first.getDedupKey().startsWith("synthetic:"),
                "Dedup key should be namespaced: " + first.getDedupKey());
    }

    @Test
    void nullOrgThrows() {
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.inject(null, Template.CRITICAL_VULN_SINGLE_SHIPPED_RELEASE));
        assertTrue(e.getMessage().contains("null org"));
    }

    @Test
    void nullTemplateThrows() {
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.inject(UUID.randomUUID(), null));
        assertTrue(e.getMessage().contains("null template"));
    }

    @Test
    void vexTemplateProducesVexEvent() throws Exception {
        UUID org = UUID.randomUUID();
        NotificationOutboxEvent saved = service.inject(
                org, Template.VEX_RESOLVED_NOT_AFFECTED);

        assertEquals(NotificationEventType.VEX_STATE_CHANGED, saved.getEventType());
        assertEquals("not_affected", saved.getRecordData().get("newState"));
    }

    @Test
    void unknownOrgThrows() {
        UUID unknownOrg = UUID.randomUUID();
        when(getOrganizationService.getOrganizationData(unknownOrg)).thenReturn(Optional.empty());

        RelizaException e = assertThrows(RelizaException.class, () ->
                service.inject(unknownOrg, Template.CRITICAL_VULN_SINGLE_SHIPPED_RELEASE));
        assertTrue(e.getMessage().contains("unknown org"),
                "Expected unknown-org rejection, got: " + e.getMessage());
    }

    @Test
    void injectChannelTestStampsChannelTestTarget() throws Exception {
        UUID org = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration channel = stubChannel(channelUuid, org);
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(channel));

        NotificationOutboxEvent saved = service.injectChannelTest(org, channelUuid);

        assertEquals(channelUuid, saved.getChannelTestTarget(),
                "channel_test_target marker should land on the outbox row");
        assertEquals(NotificationDeliveryOrigin.SYNTHETIC, saved.getOrigin());
        assertEquals(org, saved.getOrg());
        // Dedup key namespaced for findability — operators querying
        // "what test deliveries fired today" can grep by prefix.
        assertTrue(saved.getDedupKey().startsWith("channel-test:" + channelUuid + ":"),
                "Expected channel-test-namespaced dedup key, got: " + saved.getDedupKey());
    }

    @Test
    void injectChannelTestRejectsUnknownChannel() {
        UUID org = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.empty());

        RelizaException e = assertThrows(RelizaException.class, () ->
                service.injectChannelTest(org, channelUuid));
        assertTrue(e.getMessage().contains("unknown channel"),
                "Expected unknown-channel rejection, got: " + e.getMessage());
    }

    @Test
    void injectChannelTestRejectsCrossOrgChannel() throws Exception {
        // Tenant isolation: org admin in org A can't test a channel in org B
        // even if they know the UUID. The mutation layer authorizes against
        // the channel's org first, but the service-layer check is the
        // belt-and-suspenders against a forgetful future caller.
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration channelInOrgB = stubChannel(channelUuid, orgB);
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(channelInOrgB));

        RelizaException e = assertThrows(RelizaException.class, () ->
                service.injectChannelTest(orgA, channelUuid));
        assertTrue(e.getMessage().contains("does not belong"),
                "Expected cross-org rejection, got: " + e.getMessage());
    }

    @Test
    void injectChannelTestRejectsDisabledChannel() throws Exception {
        // Disabled channel test would silently fail at the delivery
        // worker (DISABLED short-circuit -> attempt bump, no POST). Reject
        // up front so the operator sees an actionable error.
        UUID org = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration disabled = stubChannelWithStatus(
                channelUuid, org, false /* disabled */);
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(disabled));

        RelizaException e = assertThrows(RelizaException.class, () ->
                service.injectChannelTest(org, channelUuid));
        assertTrue(e.getMessage().contains("DISABLED"),
                "Expected DISABLED rejection, got: " + e.getMessage());
    }

    @Test
    void injectChannelTestRejectsNullArgs() {
        assertThrows(RelizaException.class, () ->
                service.injectChannelTest(null, UUID.randomUUID()));
        assertThrows(RelizaException.class, () ->
                service.injectChannelTest(UUID.randomUUID(), null));
    }

    @Test
    void injectChannelTestRejectsUnknownOrg() {
        UUID unknownOrg = UUID.randomUUID();
        when(getOrganizationService.getOrganizationData(unknownOrg)).thenReturn(Optional.empty());

        RelizaException e = assertThrows(RelizaException.class, () ->
                service.injectChannelTest(unknownOrg, UUID.randomUUID()));
        assertTrue(e.getMessage().contains("unknown org"),
                "Expected unknown-org rejection, got: " + e.getMessage());
    }

    private static Integration stubChannel(UUID channelUuid, UUID org) {
        return stubChannelWithStatus(channelUuid, org, true /* enabled */);
    }

    private static Integration stubChannelWithStatus(UUID channelUuid, UUID org,
            boolean enabled) {
        Integration channel = new Integration();
        channel.setUuid(channelUuid);
        IntegrationData data = new IntegrationData();
        data.setUuid(channelUuid);
        data.setIdentifier(channelUuid.toString());
        data.setOrg(org);
        data.setName("test-channel");
        data.setType(IntegrationType.SLACK);
        data.setIsEnabled(enabled);
        data.setSecret("encrypted-secret-placeholder");
        data.setParameters(new java.util.HashMap<>());
        channel.setRecordData(Utils.dataToRecord(data));
        return channel;
    }

    @Test
    void everyTemplatePayloadRoundTripsBackToItsRecord() throws Exception {
        // Pins that each template's serialized recordData can be
        // deserialized back into its typed payload. Downstream code
        // (EventActivationMapBuilder, channel formatters,
        // NotificationFanOutService.extractEventSeverity) does this
        // round-trip; if a field added to a template doesn't survive,
        // it fails silently — this test catches that loudly.
        UUID org = UUID.randomUUID();
        for (Template t : Template.values()) {
            NotificationOutboxEvent saved = service.inject(org, t);
            Class<?> payloadClass = switch (SyntheticEventTemplates.eventTypeOf(t)) {
                case NEW_VULN_AFFECTS_RELEASES -> NewVulnAffectsReleasesPayload.class;
                case VULNERABILITY_RECORD_UPDATED -> VulnerabilityRecordUpdatedPayload.class;
                case VEX_STATE_CHANGED -> VexStateChangedPayload.class;
                // No synthetic template emits a release or approval event type
                // yet; these arms keep the switch exhaustive and would fire only
                // if such a template were added without a payload mapping here.
                case RELEASE_CREATED, RELEASE_LIFECYCLE_CHANGED, RELEASE_BOM_DIFF,
                        APPROVAL_REQUESTED, APPROVAL_RESOLVED ->
                        throw new IllegalStateException(
                                "No synthetic template maps " + SyntheticEventTemplates.eventTypeOf(t));
            };
            Object roundTripped = Utils.OM.convertValue(saved.getRecordData(), payloadClass);
            assertNotNull(roundTripped,
                    "Template " + t + " did not round-trip back to " + payloadClass.getSimpleName());
        }
    }
}
