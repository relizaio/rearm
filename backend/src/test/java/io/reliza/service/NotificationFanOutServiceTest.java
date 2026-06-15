/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Optional;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.dto.notifications.AffectedComponent;
import io.reliza.model.dto.notifications.AffectedRelease;
import io.reliza.model.dto.notifications.ApprovalRequestEntryRef;
import io.reliza.model.dto.notifications.ApprovalRequestedPayload;
import io.reliza.model.dto.notifications.ApprovalResolvedPayload;
import io.reliza.model.dto.notifications.NewVulnAffectsReleasesPayload;
import io.reliza.model.dto.notifications.ReleaseRef;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationDelivery;
import io.reliza.model.NotificationDeliveryOrigin;
import io.reliza.model.NotificationDeliveryStatus;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.FilterConfig;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RouteConfig;
import io.reliza.model.NotificationSubscriptionStatus;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.repositories.NotificationSubscriptionRepository;
import io.reliza.service.BranchService;
import io.reliza.service.GetComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.model.dto.notifications.EvaluationMode;

/**
 * Covers the fan-out logic without booting Spring: repositories and CEL
 * evaluator are Mockito-injected. Each test sets up a stub outbox event
 * + subscription, runs drainBatch(1), and asserts on the delivery rows
 * that were written + the event-status transition.
 */
class NotificationFanOutServiceTest {

    private NotificationOutboxEventRepository outboxRepo;
    private NotificationSubscriptionRepository subscriptionRepo;
    private NotificationDeliveryRepository deliveryRepo;
    private NotificationCelEvaluator celEvaluator;
    private ArtifactRepository artifactRepo;
    private SharedReleaseService sharedReleaseService;
    private BranchService branchService;
    private GetComponentService getComponentService;
    private NotificationChannelGroupService channelGroupService;
    private NotificationChannelService channelService;
    private NotificationReadService readService;
    private Map<UUID, UUID> channelOrgs;
    private NotificationFanOutService fanOut;

    @BeforeEach
    void wireMocks() throws Exception {
        outboxRepo = mock(NotificationOutboxEventRepository.class);
        subscriptionRepo = mock(NotificationSubscriptionRepository.class);
        deliveryRepo = mock(NotificationDeliveryRepository.class);
        celEvaluator = mock(NotificationCelEvaluator.class);
        artifactRepo = mock(ArtifactRepository.class);
        sharedReleaseService = mock(SharedReleaseService.class);
        branchService = mock(BranchService.class);
        getComponentService = mock(GetComponentService.class);
        channelGroupService = mock(NotificationChannelGroupService.class);
        channelService = mock(NotificationChannelService.class);
        readService = mock(NotificationReadService.class);
        channelOrgs = new HashMap<>();
        // Default group-service behavior: never resolve anything. Tests
        // that exercise channelGroups expansion override per-call.
        when(channelGroupService.resolveChannelUuids(any())).thenReturn(List.of());
        // Default channel-service behavior for the S-5 org guard:
        // look up the channel's org from the per-test channelOrgs map.
        // The subscription-builder helpers (subscriptionWith,
        // subscriptionWithPerspectives, subscriptionWithGroups) register
        // their channelUuids automatically against the sub's org —
        // tests that exercise cross-tenant defence call
        // stubChannelInOrg(channelUuid, mismatchedOrg) to override.
        when(channelService.getChannel(any())).thenAnswer(inv -> {
            UUID uuid = inv.getArgument(0);
            UUID org = channelOrgs.get(uuid);
            return org == null ? Optional.empty() : Optional.of(channelInOrg(uuid, org));
        });
        fanOut = new NotificationFanOutService();
        inject(fanOut, "outboxRepo", outboxRepo);
        inject(fanOut, "subscriptionRepo", subscriptionRepo);
        inject(fanOut, "deliveryRepo", deliveryRepo);
        inject(fanOut, "celEvaluator", celEvaluator);
        inject(fanOut, "artifactRepo", artifactRepo);
        inject(fanOut, "sharedReleaseService", sharedReleaseService);
        inject(fanOut, "branchService", branchService);
        inject(fanOut, "getComponentService", getComponentService);
        inject(fanOut, "channelGroupService", channelGroupService);
        inject(fanOut, "channelService", channelService);
        inject(fanOut, "readService", readService);
    }

    /**
     * Build an Integration channel row whose parsed data carries the given
     * org. Used by the channelService default stub and by tests that
     * exercise cross-tenant defence (which override per-channel).
     */
    private static Integration channelInOrg(UUID channelUuid, UUID org) {
        Integration channel = new Integration();
        channel.setUuid(channelUuid);
        IntegrationData data = new IntegrationData();
        data.setUuid(channelUuid);
        data.setIdentifier(channelUuid.toString());
        data.setOrg(org);
        data.setName("stub-channel");
        data.setType(IntegrationType.SLACK);
        data.setIsEnabled(Boolean.TRUE);
        data.setSecret("ENC:stub");
        data.setParameters(new java.util.HashMap<>());
        channel.setRecordData(Utils.dataToRecord(data));
        return channel;
    }

    @Test
    void emptyBatchProcessesZero() {
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of());
        int processed = fanOut.drainBatch(50);
        assertEquals(0, processed);
        verify(deliveryRepo, never()).save(any());
    }

    @Test
    void subscriptionThatMatchesWritesDeliveryAndFlipsEventStatus() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        NotificationSubscription sub = subscriptionWith(
                event.getOrg(),
                "event.severity == 'CRITICAL'",
                NotificationSeverity.HIGH,
                channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));
        when(celEvaluator.evaluate(eq("event.severity == 'CRITICAL'"), eq(EvaluationMode.PRESET), eq(event)))
                .thenReturn(true);
        when(deliveryRepo.existsRecentDelivery(any(), any(), anyString(), any())).thenReturn(false);

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(deliveryCaptor.capture());
        NotificationDelivery delivery = deliveryCaptor.getValue();
        assertEquals(event.getOrg(), delivery.getOrg());
        assertEquals(event.getUuid(), delivery.getOutboxEventUuid());
        assertEquals(sub.getUuid(), delivery.getSubscriptionUuid());
        assertEquals(channelUuid, delivery.getChannelUuid());
        assertEquals(NotificationDeliveryOrigin.REAL, delivery.getOrigin());

        // Event row marked FANNED_OUT
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
        verify(outboxRepo).save(event);
    }

    @Test
    void severityGateBelowThresholdSuppressesDelivery() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.MEDIUM);
        // Route requires HIGH minimum, event is MEDIUM → no delivery
        NotificationSubscription sub = subscriptionWith(
                event.getOrg(), null, NotificationSeverity.HIGH, channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));
        // Filter blank — auto-matches; only the severity gate decides
        fanOut.drainBatch(50);

        verify(deliveryRepo, never()).save(any());
        // Event still flips to FANNED_OUT (we DID process it; no match is a valid outcome)
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    @Test
    void filterEvaluatesFalseSuppressesDelivery() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        NotificationSubscription sub = subscriptionWith(
                event.getOrg(),
                "event.severity == 'HIGH'",
                NotificationSeverity.LOW,
                channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));
        when(celEvaluator.evaluate(eq("event.severity == 'HIGH'"), any(), eq(event))).thenReturn(false);

        fanOut.drainBatch(50);

        verify(deliveryRepo, never()).save(any());
    }

    @Test
    void brokenCelExpressionLogsSkipsDoesNotKillBatch() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        NotificationSubscription sub = subscriptionWith(
                event.getOrg(),
                "totally invalid CEL ???",
                NotificationSeverity.LOW,
                channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));
        when(celEvaluator.evaluate(anyString(), any(), eq(event)))
                .thenThrow(new RelizaException("compile failed"));

        fanOut.drainBatch(50);

        // Broken expression → subscription skipped, NO delivery written.
        verify(deliveryRepo, never()).save(any());
        // Event still flips FANNED_OUT (it WAS processed; the broken sub
        // just contributed nothing).
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    @Test
    void dedupSuppressesSecondDeliveryWithinWindow() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        event.setDedupKey("org:foo|vuln:CVE-X|release:bar");
        NotificationSubscription sub = subscriptionWith(
                event.getOrg(), null, NotificationSeverity.LOW, channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));
        when(deliveryRepo.existsRecentDelivery(
                eq(sub.getUuid()), eq(channelUuid), eq(event.getDedupKey()), any()))
                .thenReturn(true);

        fanOut.drainBatch(50);

        verify(deliveryRepo, never()).save(any());
    }

    @Test
    void eventTypeMismatchSkipsSubscription() throws RelizaException {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        // Subscription only listens for VEX_STATE_CHANGED, not for our event
        NotificationSubscription sub = subscriptionFor(
                event.getOrg(),
                List.of(NotificationEventType.VEX_STATE_CHANGED),
                null, NotificationSeverity.LOW, channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        verify(deliveryRepo, never()).save(any());
        // celEvaluator NEVER called — type filter is the first short-circuit
        verify(celEvaluator, never()).evaluate(anyString(), any(), any());
    }

    @Test
    void malformedEventOrgAndTypeNullMarksFannedOutWithoutDispatch() {
        NotificationOutboxEvent broken = new NotificationOutboxEvent();
        // org and eventType deliberately null
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(broken));

        fanOut.drainBatch(50);

        verify(deliveryRepo, never()).save(any());
        verify(subscriptionRepo, never()).findActiveByOrg(any());
        // Status still flips so the worker doesn't keep retrying the broken row
        assertEquals(NotificationOutboxStatus.FANNED_OUT, broken.getStatus());
        verify(outboxRepo).save(broken);
    }

    @Test
    void unparseableSubscriptionIsSkippedNotFatal() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);

        // First sub is unparseable garbage; second is a valid match-all.
        NotificationSubscription brokenSub = new NotificationSubscription();
        brokenSub.setUuid(UUID.randomUUID());
        Map<String, Object> bad = new HashMap<>();
        bad.put("status", "NOT_A_REAL_STATUS_ENUM_VALUE");
        brokenSub.setRecordData(bad);

        NotificationSubscription goodSub = subscriptionWith(
                event.getOrg(), null, NotificationSeverity.LOW, channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg()))
                .thenReturn(List.of(brokenSub, goodSub));

        fanOut.drainBatch(50);

        // Good sub still produced a delivery — broken one didn't kill the batch
        verify(deliveryRepo, times(1)).save(any());
    }

    @Test
    void vexEventWithSeverityGateSkipsRoute() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent vex = new NotificationOutboxEvent();
        vex.setOrg(UUID.randomUUID());
        vex.setUuid(UUID.randomUUID());
        vex.setEventType(NotificationEventType.VEX_STATE_CHANGED);
        vex.setStatus(NotificationOutboxStatus.PENDING);
        vex.setOccurredAt(ZonedDateTime.now());
        vex.setRecordData(new HashMap<>()); // no severity field

        NotificationSubscription sub = subscriptionFor(
                vex.getOrg(),
                List.of(NotificationEventType.VEX_STATE_CHANGED),
                null,
                NotificationSeverity.HIGH, // route requires HIGH+, but VEX has no severity
                channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(vex));
        when(subscriptionRepo.findActiveByOrg(vex.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        verify(deliveryRepo, never()).save(any());
    }

    @Test
    void routeWithoutSeverityGateAcceptsVexEvent() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent vex = new NotificationOutboxEvent();
        vex.setOrg(UUID.randomUUID());
        vex.setUuid(UUID.randomUUID());
        vex.setEventType(NotificationEventType.VEX_STATE_CHANGED);
        vex.setStatus(NotificationOutboxStatus.PENDING);
        vex.setOccurredAt(ZonedDateTime.now());
        vex.setRecordData(new HashMap<>());

        NotificationSubscription sub = subscriptionFor(
                vex.getOrg(),
                List.of(NotificationEventType.VEX_STATE_CHANGED),
                null,
                null, // no severity gate → accepts events with no severity
                channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(vex));
        when(subscriptionRepo.findActiveByOrg(vex.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        verify(deliveryRepo, atLeastOnce()).save(any());
    }

    @Test
    void perEventFailureContainedOneSucceedsOneFails() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        UUID orgUuid = UUID.randomUUID();

        NotificationOutboxEvent eventA = vulnEvent(NotificationSeverity.CRITICAL);
        eventA.setOrg(orgUuid);
        NotificationOutboxEvent eventB = vulnEvent(NotificationSeverity.CRITICAL);
        eventB.setOrg(orgUuid);

        NotificationSubscription sub = subscriptionWith(
                orgUuid, "event.severity == 'CRITICAL'", NotificationSeverity.LOW, channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(eventA, eventB));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));
        // A passes; B throws an unchecked from CEL (simulates an unexpected
        // evaluator error that ISN'T a clean RelizaException)
        when(celEvaluator.evaluate(anyString(), any(), eq(eventA))).thenReturn(true);
        when(celEvaluator.evaluate(anyString(), any(), eq(eventB)))
                .thenThrow(new RuntimeException("unexpected"));

        fanOut.drainBatch(50);

        // A produced a delivery and is FANNED_OUT
        verify(deliveryRepo, times(1)).save(any());
        assertEquals(NotificationOutboxStatus.FANNED_OUT, eventA.getStatus());
        // B is FAILED (the per-event catch contained the throw)
        assertEquals(NotificationOutboxStatus.FAILED, eventB.getStatus());
        // Both events were saved by the outer loop
        verify(outboxRepo).save(eventA);
        verify(outboxRepo).save(eventB);
    }

    @Test
    void multiRouteSubscriptionFansOutOneDeliveryPerMatchingRoute() throws Exception {
        UUID channelCritical = UUID.randomUUID();
        UUID channelHigh = UUID.randomUUID();
        UUID channelLow = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.HIGH);

        // Three routes with different severity gates. HIGH event matches the
        // HIGH and LOW gates; the CRITICAL gate is rejected.
        @SuppressWarnings("unchecked")
        NotificationSubscriptionData data = new NotificationSubscriptionData(
                event.getOrg(), null, "multi-route",
                NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES),
                new FilterConfig(EvaluationMode.PRESET, null, null),
                List.of(
                    new RouteConfig(NotificationSeverity.CRITICAL, null, null, List.of(channelCritical)),
                    new RouteConfig(NotificationSeverity.HIGH, null, null, List.of(channelHigh)),
                    new RouteConfig(NotificationSeverity.LOW, null, null, List.of(channelLow))),
                null, null);
        NotificationSubscription sub = new NotificationSubscription();
        sub.setUuid(UUID.randomUUID());
        sub.setRecordData(Utils.OM.convertValue(data, Map.class));
        stubChannelInOrg(channelCritical, event.getOrg());
        stubChannelInOrg(channelHigh, event.getOrg());
        stubChannelInOrg(channelLow, event.getOrg());

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        // Exactly two deliveries — for channelHigh and channelLow. None for
        // channelCritical (event severity below that route's threshold).
        ArgumentCaptor<NotificationDelivery> deliveries = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo, times(2)).save(deliveries.capture());
        List<UUID> targetedChannels = deliveries.getAllValues().stream()
                .map(NotificationDelivery::getChannelUuid).toList();
        assertTrue(targetedChannels.contains(channelHigh));
        assertTrue(targetedChannels.contains(channelLow));
        assertFalse(targetedChannels.contains(channelCritical));
    }

    @Test
    void syntheticOriginPropagatesFromEventToDelivery() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        event.setOrigin(NotificationDeliveryOrigin.SYNTHETIC);

        NotificationSubscription sub = subscriptionWith(
                event.getOrg(), null, NotificationSeverity.LOW, channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(deliveryCaptor.capture());
        // Origin tag survives fan-out so downstream (channel formatters,
        // history filters, analytics) can treat synthetic deliveries
        // separately.
        assertEquals(NotificationDeliveryOrigin.SYNTHETIC, deliveryCaptor.getValue().getOrigin());
    }

    @Test
    void nullEventOriginIsTreatedAsRealAndAppliesDedup() throws Exception {
        // Defensive guard: if a row somehow lacks origin (legacy data,
        // hand-constructed test event), fan-out treats it as REAL —
        // applies dedup, delivery gets origin=REAL by default.
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        event.setOrigin(null); // simulate legacy row / malformed test input
        event.setDedupKey("dedupable-key");

        NotificationSubscription sub = subscriptionWith(
                event.getOrg(), null, NotificationSeverity.LOW, channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));
        when(deliveryRepo.existsRecentDelivery(any(), any(), anyString(), any())).thenReturn(false);

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> deliveryCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(deliveryCaptor.capture());
        // Null event-origin defaults to REAL on the delivery
        assertEquals(NotificationDeliveryOrigin.REAL, deliveryCaptor.getValue().getOrigin());
        // And dedup was checked (proves the null path didn't accidentally enter the bypass branch)
        verify(deliveryRepo, times(1)).existsRecentDelivery(any(), any(), anyString(), any());
    }

    @Test
    void syntheticEventBypassesDedupCheck() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        event.setOrigin(NotificationDeliveryOrigin.SYNTHETIC);
        event.setDedupKey("shared-key-that-would-normally-suppress");

        NotificationSubscription sub = subscriptionWith(
                event.getOrg(), null, NotificationSeverity.LOW, channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));
        // Even if the dedup check WOULD say "already delivered", synthetic
        // events skip the check entirely — design doc §7.11 says synthetic
        // events must always produce a visible delivery.
        when(deliveryRepo.existsRecentDelivery(any(), any(), anyString(), any())).thenReturn(true);

        fanOut.drainBatch(50);

        // Despite existsRecentDelivery returning true, delivery still got
        // written because synthetic events bypass dedup.
        verify(deliveryRepo, times(1)).save(any());
        // And we never even consulted the dedup check for the synthetic event
        verify(deliveryRepo, never()).existsRecentDelivery(any(), any(), anyString(), any());
    }

    @Test
    void channelTestEventBypassesSubscriptionAndDelivers() throws Exception {
        // Phase 2d channel-test path: event has channel_test_target set,
        // fan-out skips subscription/CEL/severity entirely and writes
        // exactly one delivery to the target channel.
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.LOW);
        event.setChannelTestTarget(channelUuid);
        event.setOrigin(NotificationDeliveryOrigin.SYNTHETIC);
        // S-5 guard: register channel under event's org (the operator-
        // facing channel-test mutation enforces this at the auth layer).
        stubChannelInOrg(channelUuid, event.getOrg());

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        // Org has zero subscriptions — channel test should still deliver
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of());

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo, times(1)).save(captor.capture());
        NotificationDelivery delivery = captor.getValue();
        assertEquals(channelUuid, delivery.getChannelUuid(),
                "Delivery should target the channel-test-target channel");
        assertEquals(event.getUuid(), delivery.getOutboxEventUuid());
        // subscription_uuid stays null on channel-test rows — bypassing
        // subscription matching means there's no subscription to point at.
        org.junit.jupiter.api.Assertions.assertNull(delivery.getSubscriptionUuid(),
                "Channel-test deliveries should have null subscription_uuid (no originating subscription)");
        assertEquals(NotificationDeliveryOrigin.SYNTHETIC, delivery.getOrigin());
        // Dedup was NEVER consulted — channel test always delivers
        verify(deliveryRepo, never()).existsRecentDelivery(any(), any(), anyString(), any());
        // No subscription lookup either (early return path)
        verify(subscriptionRepo, never()).findActiveByOrg(any());
        // Event still flips to FANNED_OUT
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    @Test
    void channelTestEventIgnoresSeverityGateAndCelFilter() throws Exception {
        // Even if the org has subscriptions configured with severity
        // gates that would normally reject a LOW event, the channel-test
        // bypass goes around all of that. The customer pressing "Test"
        // expects a delivery regardless of their subscription matrix.
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.LOW);
        event.setChannelTestTarget(channelUuid);
        stubChannelInOrg(channelUuid, event.getOrg());

        // Subscription wired for CRITICAL only — a regular LOW event
        // would be rejected by the severity gate. Channel test ignores
        // this entirely.
        NotificationSubscription unrelatedSub = subscriptionWith(
                event.getOrg(), "event.severity == 'CRITICAL'",
                NotificationSeverity.CRITICAL, UUID.randomUUID());

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(unrelatedSub));

        fanOut.drainBatch(50);

        verify(deliveryRepo, times(1)).save(any());
        // CEL was never invoked because we early-returned before subscription loop
        verify(celEvaluator, never()).evaluate(anyString(), any(), any());
    }

    @Test
    void severityEqualToThresholdMatches() throws Exception {
        // Pin the >=, not >, semantic of NotificationSeverity.atLeast.
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.MEDIUM);
        NotificationSubscription sub = subscriptionWith(
                event.getOrg(), null, NotificationSeverity.MEDIUM, channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        // MEDIUM event with route gate "at least MEDIUM" → matches.
        verify(deliveryRepo, times(1)).save(any());
    }

    @Test
    void newVulnEventWithEmptyReleasesGetsEnrichedAtFanOut() throws Exception {
        // Producer emits a NEW_VULN_AFFECTS_RELEASES with affectedReleases
        // null (Phase 2c contract: producer can't see release linkage yet,
        // fan-out resolves via the JSONB index).
        UUID orgUuid = UUID.randomUUID();
        UUID releaseUuid = UUID.randomUUID();
        UUID branchUuid = UUID.randomUUID();
        UUID componentUuid = UUID.randomUUID();
        UUID artifactUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();

        NewVulnAffectsReleasesPayload bare = new NewVulnAffectsReleasesPayload(
                "CVE-2025-99999",
                List.of("CVE-2025-99999"),
                9.8, "vec", 0.5, false, null,
                NotificationSeverity.CRITICAL,
                null, null /* no releases — producer-side */);
        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = Utils.OM.convertValue(bare, Map.class);
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(orgUuid);
        event.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOccurredAt(ZonedDateTime.now());
        event.setRecordData(recordData);

        // Wire the enrichment chain
        when(artifactRepo.findArtifactsWithVulnId(orgUuid.toString(), "CVE-2025-99999"))
                .thenReturn(List.of(artifactUuid));
        ReleaseData rd = buildReleaseData(releaseUuid, orgUuid, branchUuid, componentUuid,
                "v3.1.4", ReleaseLifecycle.GENERAL_AVAILABILITY);
        when(sharedReleaseService.gatherReleasesForArtifact(artifactUuid, orgUuid))
                .thenReturn(List.of(rd));
        BranchData branchData = buildBranchData("main");
        when(branchService.getBranchData(branchUuid)).thenReturn(Optional.of(branchData));
        ComponentData componentData = buildComponentData("payments-api");
        when(getComponentService.getComponentData(componentUuid)).thenReturn(Optional.of(componentData));

        NotificationSubscription sub = subscriptionWith(
                orgUuid, null, NotificationSeverity.LOW, channelUuid);
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        // Enrichment populated affectedReleases on the event's recordData.
        Object enriched = event.getRecordData().get("affectedReleases");
        assertTrue(enriched instanceof List, "Expected enriched List, got: " + enriched);
        List<?> enrichedList = (List<?>) enriched;
        assertEquals(1, enrichedList.size());
        // Round-trip back to AffectedRelease to assert structure
        @SuppressWarnings("unchecked")
        Map<String, Object> ar = (Map<String, Object>) enrichedList.get(0);
        assertEquals("payments-api", ar.get("component"));
        assertEquals("main", ar.get("branch"));
        assertEquals("v3.1.4", ar.get("version"));
        // Delivery still fanned out
        verify(deliveryRepo, times(1)).save(any());
    }

    @Test
    void existingAffectedReleasesIsNotClobberedByEnrichment() throws Exception {
        // Synthetic events (and future hand-built test payloads) ship with
        // affectedReleases already populated. Fan-out enrichment must NOT
        // clobber a pre-populated list — that would corrupt the synthetic
        // event preview the operator is supposed to see.
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        // vulnEvent() helper already sets a single AffectedRelease — that's
        // the "pre-populated" payload. The artifactRepo mock returns
        // something different so we can detect clobbering.
        NotificationSubscription sub = subscriptionWith(
                event.getOrg(), null, NotificationSeverity.LOW, channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        // artifactRepo was never even queried — the early return on
        // non-empty affectedReleases short-circuited enrichment.
        verify(artifactRepo, never()).findArtifactsWithVulnId(anyString(), anyString());
        // The original single-release list survived the fan-out tx.
        Object releases = event.getRecordData().get("affectedReleases");
        assertTrue(releases instanceof List);
        assertEquals(1, ((List<?>) releases).size());
    }

    @Test
    void nonVulnEventTypeSkipsEnrichment() throws Exception {
        // VEX_STATE_CHANGED is the third event type but has no affectedReleases
        // semantics — enrichment must not touch it.
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent vex = new NotificationOutboxEvent();
        vex.setOrg(UUID.randomUUID());
        vex.setUuid(UUID.randomUUID());
        vex.setEventType(NotificationEventType.VEX_STATE_CHANGED);
        vex.setStatus(NotificationOutboxStatus.PENDING);
        vex.setOccurredAt(ZonedDateTime.now());
        Map<String, Object> rd = new HashMap<>();
        rd.put("vulnPrimaryId", "CVE-2025-VEX");
        vex.setRecordData(rd);

        NotificationSubscription sub = subscriptionFor(
                vex.getOrg(),
                List.of(NotificationEventType.VEX_STATE_CHANGED),
                null,
                null,
                channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(vex));
        when(subscriptionRepo.findActiveByOrg(vex.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        verify(artifactRepo, never()).findArtifactsWithVulnId(anyString(), anyString());
        // affectedReleases was never set
        assertFalse(vex.getRecordData().containsKey("affectedReleases"));
    }

    @Test
    void enrichmentFailureDoesNotKillEventStillShipsEmptyReleaseList() throws Exception {
        // Defense-in-depth: if the JSONB scan throws, we still want the
        // event delivered (operators see "affects 0 releases" rather than
        // nothing). The producer-side severity is enough for routing.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();

        NewVulnAffectsReleasesPayload bare = new NewVulnAffectsReleasesPayload(
                "CVE-2025-FAIL",
                List.of(),
                9.8, "vec", 0.5, false, null,
                NotificationSeverity.CRITICAL,
                null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = Utils.OM.convertValue(bare, Map.class);
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(orgUuid);
        event.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOccurredAt(ZonedDateTime.now());
        event.setRecordData(recordData);

        when(artifactRepo.findArtifactsWithVulnId(anyString(), anyString()))
                .thenThrow(new RuntimeException("postgres connection blip"));
        NotificationSubscription sub = subscriptionWith(
                orgUuid, null, NotificationSeverity.LOW, channelUuid);
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        // Empty list landed (not null — the post-failure putIfAbsent ensures
        // the key exists so the formatter doesn't NPE on get).
        Object releases = event.getRecordData().get("affectedReleases");
        assertTrue(releases instanceof List);
        assertEquals(0, ((List<?>) releases).size());
        // Delivery still went out
        verify(deliveryRepo, times(1)).save(any());
        // Event marked FANNED_OUT (enrichment failure is not a fan-out failure)
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    // ---------- S-3: affectedComponent enrichment ----------

    @Test
    void newVulnEventGetsAffectedComponentEnrichedFromArtifactPurls() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = bareVulnEvent(orgUuid, "CVE-2025-S3-1");

        when(artifactRepo.findVulnPurlsForVulnId(orgUuid.toString(), "CVE-2025-S3-1"))
                .thenReturn(List.of("pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1"));

        NotificationSubscription sub = subscriptionWith(
                orgUuid, null, NotificationSeverity.LOW, channelUuid);
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        @SuppressWarnings("unchecked")
        Map<String, Object> ac = (Map<String, Object>) event.getRecordData().get("affectedComponent");
        assertNotNull(ac, "Expected affectedComponent to be enriched");
        assertEquals("pkg:maven/org.apache.logging.log4j/log4j-core@2.14.1", ac.get("purl"));
        assertEquals("log4j-core", ac.get("name"));
        assertEquals("2.14.1", ac.get("version"));
        verify(deliveryRepo, times(1)).save(any());
    }

    @Test
    void prePopulatedAffectedComponentIsNotClobbered() throws Exception {
        // Synthetic events ship with affectedComponent already set — the
        // same don't-clobber rule as affectedReleases applies.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        NewVulnAffectsReleasesPayload payload = new NewVulnAffectsReleasesPayload(
                "CVE-2025-S3-2", List.of(), 9.8, "vec", 0.5, false, null,
                NotificationSeverity.CRITICAL,
                new AffectedComponent("pkg:npm/synthetic@1.0.0", "synthetic", "1.0.0"),
                List.of(new AffectedRelease(
                        UUID.randomUUID(), "myapp", "v1.0", "main", null, List.of())));
        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = Utils.OM.convertValue(payload, Map.class);
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(orgUuid);
        event.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOccurredAt(ZonedDateTime.now());
        event.setRecordData(recordData);

        NotificationSubscription sub = subscriptionWith(
                orgUuid, null, NotificationSeverity.LOW, channelUuid);
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        verify(artifactRepo, never()).findVulnPurlsForVulnId(anyString(), anyString());
        @SuppressWarnings("unchecked")
        Map<String, Object> ac = (Map<String, Object>) event.getRecordData().get("affectedComponent");
        assertEquals("pkg:npm/synthetic@1.0.0", ac.get("purl"));
    }

    @Test
    void malformedPurlStillShipsRawAffectedComponent() throws Exception {
        // A purl that doesn't parse ships with the raw purl doubling as
        // the name — most channel formatters render only when name is
        // non-blank, so a null name would hide the component everywhere
        // except the inbox.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = bareVulnEvent(orgUuid, "CVE-2025-S3-3");

        when(artifactRepo.findVulnPurlsForVulnId(orgUuid.toString(), "CVE-2025-S3-3"))
                .thenReturn(List.of("not-a-valid-purl"));

        NotificationSubscription sub = subscriptionWith(
                orgUuid, null, NotificationSeverity.LOW, channelUuid);
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        @SuppressWarnings("unchecked")
        Map<String, Object> ac = (Map<String, Object>) event.getRecordData().get("affectedComponent");
        assertNotNull(ac);
        assertEquals("not-a-valid-purl", ac.get("purl"));
        assertEquals("not-a-valid-purl", ac.get("name"));
        assertNull(ac.get("version"));
    }

    @Test
    void prePopulatedReleasesDoNotBlockComponentEnrichment() throws Exception {
        // The restructure's new branch combination: before S-3, a
        // populated affectedReleases early-returned the whole method;
        // now component enrichment must still run for it.
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        // vulnEvent() pre-populates one AffectedRelease, affectedComponent null
        UUID orgUuid = event.getOrg();

        when(artifactRepo.findVulnPurlsForVulnId(orgUuid.toString(), "CVE-2025-12345"))
                .thenReturn(List.of("pkg:npm/lodash@4.17.20"));

        NotificationSubscription sub = subscriptionWith(
                orgUuid, null, NotificationSeverity.LOW, channelUuid);
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        // Release enrichment skipped (pre-populated)…
        verify(artifactRepo, never()).findArtifactsWithVulnId(anyString(), anyString());
        // …but component enrichment ran.
        @SuppressWarnings("unchecked")
        Map<String, Object> ac = (Map<String, Object>) event.getRecordData().get("affectedComponent");
        assertNotNull(ac, "Component enrichment must run even when releases are pre-populated");
        assertEquals("lodash", ac.get("name"));
        assertEquals("4.17.20", ac.get("version"));
    }

    @Test
    void noPurlsMeansNoAffectedComponentKey() throws Exception {
        // CVE not present in any artifact's vulnerabilityDetails (or purls
        // all null) → leave affectedComponent absent; formatters degrade.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = bareVulnEvent(orgUuid, "CVE-2025-S3-4");

        when(artifactRepo.findVulnPurlsForVulnId(orgUuid.toString(), "CVE-2025-S3-4"))
                .thenReturn(List.of());

        NotificationSubscription sub = subscriptionWith(
                orgUuid, null, NotificationSeverity.LOW, channelUuid);
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        assertNull(event.getRecordData().get("affectedComponent"));
        // Still ships — component is a nice-to-have, not a gate.
        verify(deliveryRepo, times(1)).save(any());
    }

    @Test
    void vulnRecordUpdatedEventSkipsComponentEnrichment() throws Exception {
        // Only the NEW_VULN payload carries affectedComponent; the updated
        // event still gets affectedReleases enrichment but no component.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(orgUuid);
        event.setEventType(NotificationEventType.VULNERABILITY_RECORD_UPDATED);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOccurredAt(ZonedDateTime.now());
        Map<String, Object> rd = new HashMap<>();
        rd.put("vulnPrimaryId", "CVE-2025-S3-5");
        event.setRecordData(rd);

        NotificationSubscription sub = subscriptionFor(
                orgUuid,
                List.of(NotificationEventType.VULNERABILITY_RECORD_UPDATED),
                null, null, channelUuid);
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        verify(artifactRepo, never()).findVulnPurlsForVulnId(anyString(), anyString());
        assertFalse(event.getRecordData().containsKey("affectedComponent"));
        // affectedReleases enrichment still ran for this type
        verify(artifactRepo, times(1)).findArtifactsWithVulnId(orgUuid.toString(), "CVE-2025-S3-5");
    }

    @Test
    void multiPurlVulnPicksLexicographicallyFirst() throws Exception {
        // One CVE hitting several packages collapses deterministically to
        // the first purl (the query ORDER BYs, the service trusts order).
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = bareVulnEvent(orgUuid, "CVE-2025-S3-6");

        when(artifactRepo.findVulnPurlsForVulnId(orgUuid.toString(), "CVE-2025-S3-6"))
                .thenReturn(List.of(
                        "pkg:maven/com.example/alpha@1.0.0",
                        "pkg:maven/com.example/beta@2.0.0"));

        NotificationSubscription sub = subscriptionWith(
                orgUuid, null, NotificationSeverity.LOW, channelUuid);
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        @SuppressWarnings("unchecked")
        Map<String, Object> ac = (Map<String, Object>) event.getRecordData().get("affectedComponent");
        assertEquals("pkg:maven/com.example/alpha@1.0.0", ac.get("purl"));
        assertEquals("alpha", ac.get("name"));
    }

    @Test
    void componentEnrichmentFailureStillShipsEvent() throws Exception {
        // Same defense-in-depth contract as affectedReleases: a purl-scan
        // blip must not kill the event.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = bareVulnEvent(orgUuid, "CVE-2025-S3-7");

        when(artifactRepo.findVulnPurlsForVulnId(anyString(), anyString()))
                .thenThrow(new RuntimeException("postgres connection blip"));

        NotificationSubscription sub = subscriptionWith(
                orgUuid, null, NotificationSeverity.LOW, channelUuid);
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        assertNull(event.getRecordData().get("affectedComponent"));
        verify(deliveryRepo, times(1)).save(any());
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    // ---------- Phase 12: perspective-scoped routes ----------

    @Test
    void enrichmentPopulatesPerspectivesFromComponent() throws Exception {
        // Producer emits a bare event; fan-out should populate
        // AffectedRelease.perspectives from the resolved component's
        // perspectives Set.
        UUID orgUuid = UUID.randomUUID();
        UUID releaseUuid = UUID.randomUUID();
        UUID branchUuid = UUID.randomUUID();
        UUID componentUuid = UUID.randomUUID();
        UUID artifactUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        UUID perspectiveA = UUID.randomUUID();
        UUID perspectiveB = UUID.randomUUID();

        NotificationOutboxEvent event = bareVulnEvent(orgUuid, "CVE-2025-PERSP-1");
        wireEnrichmentChain(orgUuid, "CVE-2025-PERSP-1", artifactUuid, releaseUuid,
                branchUuid, componentUuid,
                buildComponentData("payments-api", java.util.Set.of(perspectiveA, perspectiveB)));

        NotificationSubscription sub = subscriptionWith(orgUuid, null,
                NotificationSeverity.LOW, channelUuid);
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        @SuppressWarnings("unchecked")
        Map<String, Object> ar = (Map<String, Object>) ((List<?>) event.getRecordData()
                .get("affectedReleases")).get(0);
        @SuppressWarnings("unchecked")
        java.util.Collection<String> ps = (java.util.Collection<String>) ar.get("perspectives");
        assertEquals(2, ps.size(),
                "AffectedRelease.perspectives should mirror component.perspectives");
        assertTrue(ps.contains(perspectiveA.toString()), "Missing perspectiveA in enriched output");
        assertTrue(ps.contains(perspectiveB.toString()), "Missing perspectiveB in enriched output");
    }

    @Test
    void routeWithPerspectiveGateFiresWhenEventIntersects() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID componentUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        UUID perspective = UUID.randomUUID();
        UUID otherPerspective = UUID.randomUUID();

        // Event whose only release touches BOTH perspective + otherPerspective.
        NotificationOutboxEvent event = bareVulnEvent(orgUuid, "CVE-2025-PERSP-2");
        wireEnrichmentChain(orgUuid, "CVE-2025-PERSP-2", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), componentUuid,
                buildComponentData("payments-api",
                        java.util.Set.of(perspective, otherPerspective)));

        // Route gates to `perspective` — intersection non-empty → fire.
        NotificationSubscription sub = subscriptionWithPerspectives(
                orgUuid, channelUuid, List.of(perspective));
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        verify(deliveryRepo, times(1)).save(any());
    }

    @Test
    void routeWithPerspectiveGateGatesOutNonMatchingEvent() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID componentUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        UUID routePerspective = UUID.randomUUID();
        UUID releasePerspective = UUID.randomUUID(); // different from route's

        NotificationOutboxEvent event = bareVulnEvent(orgUuid, "CVE-2025-PERSP-3");
        wireEnrichmentChain(orgUuid, "CVE-2025-PERSP-3", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), componentUuid,
                buildComponentData("payments-api", java.util.Set.of(releasePerspective)));

        NotificationSubscription sub = subscriptionWithPerspectives(
                orgUuid, channelUuid, List.of(routePerspective));
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        // Route gated out — no delivery row written despite the
        // subscription matching the event type + severity.
        verify(deliveryRepo, never()).save(any());
    }

    @Test
    void emptyPerspectiveListMeansNoGate() throws Exception {
        // Backward-compat: a RouteConfig built via the 4-arg constructor
        // (or with perspectives=null/empty) must not gate anything out.
        UUID orgUuid = UUID.randomUUID();
        UUID componentUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();

        NotificationOutboxEvent event = bareVulnEvent(orgUuid, "CVE-2025-PERSP-4");
        wireEnrichmentChain(orgUuid, "CVE-2025-PERSP-4", UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), componentUuid,
                buildComponentData("payments-api", java.util.Set.of(UUID.randomUUID())));

        // Subscription uses the 4-arg RouteConfig → perspectives null → no gate.
        NotificationSubscription sub = subscriptionWith(orgUuid, null,
                NotificationSeverity.LOW, channelUuid);
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        verify(deliveryRepo, times(1)).save(any());
    }

    @Test
    void routePerspectiveGateFiresWhenOneOfManyReleasesIntersects() throws Exception {
        // Multi-release event, one matching release: should fire (any-match
        // semantics, not all-match). Defensive against a future "must
        // match all" misreading of the gate.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        UUID routePerspective = UUID.randomUUID();
        UUID otherPerspective = UUID.randomUUID();

        // Build two artifacts → two distinct components → two distinct
        // releases. Only one component has the route's perspective; the
        // other has an unrelated perspective.
        UUID artifactA = UUID.randomUUID();
        UUID artifactB = UUID.randomUUID();
        UUID releaseA = UUID.randomUUID();
        UUID releaseB = UUID.randomUUID();
        UUID componentA = UUID.randomUUID();
        UUID componentB = UUID.randomUUID();
        UUID branchA = UUID.randomUUID();
        UUID branchB = UUID.randomUUID();

        NotificationOutboxEvent event = bareVulnEvent(orgUuid, "CVE-2025-PERSP-MULTI");
        when(artifactRepo.findArtifactsWithVulnId(orgUuid.toString(), "CVE-2025-PERSP-MULTI"))
                .thenReturn(List.of(artifactA, artifactB));
        ReleaseData rdA = buildReleaseData(releaseA, orgUuid, branchA, componentA,
                "v1.0", ReleaseLifecycle.GENERAL_AVAILABILITY);
        ReleaseData rdB = buildReleaseData(releaseB, orgUuid, branchB, componentB,
                "v2.0", ReleaseLifecycle.GENERAL_AVAILABILITY);
        when(sharedReleaseService.gatherReleasesForArtifact(artifactA, orgUuid))
                .thenReturn(List.of(rdA));
        when(sharedReleaseService.gatherReleasesForArtifact(artifactB, orgUuid))
                .thenReturn(List.of(rdB));
        when(branchService.getBranchData(branchA)).thenReturn(Optional.of(buildBranchData("main-a")));
        when(branchService.getBranchData(branchB)).thenReturn(Optional.of(buildBranchData("main-b")));
        when(getComponentService.getComponentData(componentA))
                .thenReturn(Optional.of(buildComponentData("matches", java.util.Set.of(routePerspective))));
        when(getComponentService.getComponentData(componentB))
                .thenReturn(Optional.of(buildComponentData("does-not-match", java.util.Set.of(otherPerspective))));

        NotificationSubscription sub = subscriptionWithPerspectives(orgUuid, channelUuid,
                List.of(routePerspective));
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        // One delivery row written — any-match semantics confirmed.
        verify(deliveryRepo, times(1)).save(any());
    }

    @Test
    void perspectiveGateGatesOutVexStateChangedEvent() throws Exception {
        // VEX events bypass enrichment entirely (extractEventSeverity returns
        // null too, but that's a separate gate). A perspective-scoped route
        // on a subscription that includes VEX_STATE_CHANGED must NOT fire on
        // the VEX event — affectedReleases is absent, so the gate has nothing
        // to intersect against. Pin the documented behavior.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        UUID routePerspective = UUID.randomUUID();

        NotificationOutboxEvent vex = new NotificationOutboxEvent();
        vex.setUuid(UUID.randomUUID());
        vex.setOrg(orgUuid);
        vex.setEventType(NotificationEventType.VEX_STATE_CHANGED);
        vex.setStatus(NotificationOutboxStatus.PENDING);
        vex.setOccurredAt(ZonedDateTime.now());
        Map<String, Object> rd = new HashMap<>();
        rd.put("vulnPrimaryId", "CVE-2025-VEX");
        rd.put("oldState", "affected");
        rd.put("newState", "not_affected");
        vex.setRecordData(rd);

        // Subscription includes VEX as an event type AND has a perspective-scoped route.
        @SuppressWarnings("unchecked")
        NotificationSubscriptionData data = new NotificationSubscriptionData(
                orgUuid, null, "vex-with-perspective-gate",
                NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.VEX_STATE_CHANGED),
                new FilterConfig(EvaluationMode.PRESET, null, null),
                List.of(new RouteConfig(NotificationSeverity.LOW, null, null,
                        List.of(channelUuid), List.of(routePerspective))),
                null, null);
        NotificationSubscription sub = new NotificationSubscription();
        sub.setUuid(UUID.randomUUID());
        sub.setRecordData(Utils.OM.convertValue(data, Map.class));

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(vex));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        // Route gated out — VEX events carry no affectedReleases.
        verify(deliveryRepo, never()).save(any());
    }

    @Test
    void perspectiveGateOnEventWithoutAffectedReleasesGatesOut() throws Exception {
        // A perspective-scoped route should NOT fire on an event that
        // carries no affectedReleases (e.g. enrichment failed → empty
        // list). Without this short-circuit, an empty list would still
        // pass the "any intersection" check vacuously since the loop
        // never runs.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        UUID routePerspective = UUID.randomUUID();

        NewVulnAffectsReleasesPayload bare = new NewVulnAffectsReleasesPayload(
                "CVE-2025-PERSP-5", List.of(), 9.8, "vec", 0.5, false, null,
                NotificationSeverity.CRITICAL, null, null);
        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = Utils.OM.convertValue(bare, Map.class);
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(orgUuid);
        event.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOccurredAt(ZonedDateTime.now());
        event.setRecordData(recordData);
        // Force enrichment to return zero releases (mirrors the
        // enrichmentFailure-handler shape).
        when(artifactRepo.findArtifactsWithVulnId(orgUuid.toString(), "CVE-2025-PERSP-5"))
                .thenReturn(List.of());

        NotificationSubscription sub = subscriptionWithPerspectives(
                orgUuid, channelUuid, List.of(routePerspective));
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        verify(deliveryRepo, never()).save(any());
    }

    @Test
    void perspectiveGateAcceptsTypedAffectedReleasePojoNotJustMap() throws Exception {
        // Producers that write typed AffectedRelease POJOs directly into
        // recordData (rather than going through Jackson's convertValue
        // first) must NOT have their items silently skipped by the gate.
        // Regression guard for the convention-check finding that a future
        // SyntheticEventService path could write typed POJOs.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        UUID routePerspective = UUID.randomUUID();

        AffectedRelease typed = new AffectedRelease(UUID.randomUUID(), "typed-comp", "v1", "main",
                ReleaseLifecycle.GENERAL_AVAILABILITY, List.of(),
                java.util.Set.of(routePerspective));
        NewVulnAffectsReleasesPayload p = new NewVulnAffectsReleasesPayload(
                "CVE-TYPED", List.of(), 9.8, "vec", 0.5, false, null,
                NotificationSeverity.CRITICAL, null, List.of(typed));
        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = new HashMap<>(Utils.OM.convertValue(p, Map.class));
        // Replace the Jackson-flattened affectedReleases with the typed POJO list directly.
        recordData.put("affectedReleases", List.of(typed));
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(orgUuid);
        event.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOccurredAt(ZonedDateTime.now());
        event.setRecordData(recordData);

        NotificationSubscription sub = subscriptionWithPerspectives(orgUuid, channelUuid,
                List.of(routePerspective));
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        verify(deliveryRepo, times(1)).save(any());
    }

    @Test
    void affectedReleaseDeserializesBackCompatPayloadWithoutPerspectivesKey() throws Exception {
        // Pin Jackson record-binding behavior: a JSON payload missing
        // the `perspectives` key should deserialize cleanly via the
        // 6-arg back-compat constructor, leaving perspectives as the
        // empty-set default. Guards against a Jackson upgrade silently
        // changing the multi-ctor resolution path.
        Map<String, Object> legacyJson = new HashMap<>();
        legacyJson.put("uuid", UUID.randomUUID().toString());
        legacyJson.put("component", "legacy-comp");
        legacyJson.put("version", "v1.0");
        legacyJson.put("branch", "main");
        legacyJson.put("lifecycle", "GENERAL_AVAILABILITY");
        legacyJson.put("deployedEnvs", List.of());
        // No "perspectives" key — pre-Phase-12 row shape.

        AffectedRelease ar = Utils.OM.convertValue(legacyJson, AffectedRelease.class);

        assertNotNull(ar);
        assertNotNull(ar.perspectives(), "Legacy payload should yield non-null perspectives (empty set)");
        assertTrue(ar.perspectives().isEmpty(), "Legacy payload should yield empty perspective set");
    }

    @Test
    void routeConfigDeserializesBackCompatPayloadWithoutPerspectivesKey() throws Exception {
        // Same Jackson round-trip pin for RouteConfig. The route's
        // back-compat constructor defaults perspectives to null (the
        // "no gate" sentinel) — not empty list. Diverges from
        // AffectedRelease intentionally; see Javadoc on both records.
        Map<String, Object> legacyJson = new HashMap<>();
        legacyJson.put("whenSeverityAtLeast", "HIGH");
        legacyJson.put("andEnvIn", null);
        legacyJson.put("andLifecycleIn", null);
        legacyJson.put("channels", List.of(UUID.randomUUID().toString()));
        // No "perspectives" key — pre-Phase-12 row shape.

        RouteConfig rc = Utils.OM.convertValue(legacyJson, RouteConfig.class);

        assertNotNull(rc);
        assertEquals(NotificationSeverity.HIGH, rc.whenSeverityAtLeast());
        // perspectives null = "no gate" sentinel.
        assertTrue(rc.perspectives() == null,
                "Legacy payload should yield null perspectives (the 'no gate' sentinel)");
    }

    @Test
    void perspectiveGateSilentlyDropsCorruptedNonUuidEntriesWithoutCrashing() throws Exception {
        // A corrupted JSONB row whose `perspectives` list contains a
        // non-UUID-coercible value (e.g. "garbage") must not explode the
        // batch. The entry is logged and skipped; valid sibling entries
        // are still considered.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        UUID validRoutePerspective = UUID.randomUUID();

        // Build recordData by hand so we control exactly what shape the
        // gate sees, including the mixed valid/garbage perspectives list.
        Map<String, Object> affectedRelease = new HashMap<>();
        affectedRelease.put("uuid", UUID.randomUUID().toString());
        affectedRelease.put("component", "myapp");
        affectedRelease.put("version", "v1");
        affectedRelease.put("branch", "main");
        affectedRelease.put("lifecycle", "GENERAL_AVAILABILITY");
        affectedRelease.put("deployedEnvs", List.of());
        affectedRelease.put("perspectives",
                List.of(validRoutePerspective.toString(), "not-a-uuid-at-all"));

        Map<String, Object> recordData = new HashMap<>();
        recordData.put("vulnPrimaryId", "CVE-CORRUPT");
        recordData.put("severity", "CRITICAL");
        recordData.put("affectedReleases", new java.util.ArrayList<>(List.of(affectedRelease)));

        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(orgUuid);
        event.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOccurredAt(ZonedDateTime.now());
        event.setRecordData(recordData);

        NotificationSubscription sub = subscriptionWithPerspectives(orgUuid, channelUuid,
                List.of(validRoutePerspective));
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(orgUuid)).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        // The valid UUID still matches the route's perspective; the
        // garbage entry was silently logged and skipped. Delivery fires.
        verify(deliveryRepo, times(1)).save(any());
    }

    @Test
    void crossTenantChannelOnRouteSkipsDeliveryAtFanOut() throws Exception {
        // S-5 defence-in-depth fan-out side: even if the subscription
        // somehow references a channel belonging to a different org
        // (only possible via a future code path that bypasses the
        // upsert validator), the fan-out service refuses to write the
        // delivery row. The standard subscription-upsert path can't
        // produce this shape — this guard catches future regressions.
        UUID channelInWrongOrg = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        // Subscription helper registers channelInWrongOrg → event.org;
        // override to point it at a different org so the guard refuses.
        NotificationSubscription sub = subscriptionWith(
                event.getOrg(), null, NotificationSeverity.LOW, channelInWrongOrg);
        stubChannelInOrg(channelInWrongOrg, UUID.randomUUID() /* different org */);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        // No delivery row written — guard short-circuits before save.
        verify(deliveryRepo, never()).save(any());
        // Outbox event still flips to FANNED_OUT (the fan-out completed,
        // just produced zero deliveries; the warn-log is the trace).
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    @Test
    void routeMixingGoodAndCrossOrgChannelDeliversOnlyToGood() throws Exception {
        // Phase-2 follow-up: a single route can carry several channels.
        // The cross-org guard is per-channel, so one poisoned channel
        // must not suppress its well-formed siblings. Route =
        // [goodChannel(event.org), badChannel(other org)] — exactly one
        // delivery row (to good) is written; bad is refused in isolation.
        UUID goodChannel = UUID.randomUUID();
        UUID badChannel = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        NotificationSubscription sub = subscriptionWithChannels(
                event.getOrg(), NotificationSeverity.LOW, List.of(goodChannel, badChannel));
        stubChannelInOrg(goodChannel, event.getOrg());
        stubChannelInOrg(badChannel, UUID.randomUUID() /* different org */);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo, times(1)).save(captor.capture());
        assertEquals(goodChannel, captor.getValue().getChannelUuid(),
                "Only the same-org channel should receive a delivery row");
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    @Test
    void missingChannelOnRouteWritesDeliveryForWorkerForensics() throws Exception {
        // Phase-2 follow-up: a channel that no longer exists (or whose
        // record_data is unparseable) is no longer silently suppressed at
        // fan-out. channelEligibleForDelivery defers it to the worker so a
        // terminal FAILED Delivery History row is produced for forensics
        // — only a *confirmed* cross-org channel is refused outright.
        UUID missingChannel = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        NotificationSubscription sub = subscriptionWithChannels(
                event.getOrg(), NotificationSeverity.LOW, List.of(missingChannel));
        // Deliberately do NOT register missingChannel — getChannel returns
        // empty, simulating a deleted channel row.
        channelOrgs.remove(missingChannel);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo, times(1)).save(captor.capture());
        assertEquals(missingChannel, captor.getValue().getChannelUuid(),
                "Missing channel should still get a delivery row for the worker to fail terminally");
    }

    /**
     * Phase-2 follow-up helper. Builds a subscription whose single route
     * carries the given channel list (severity gate set low so it doesn't
     * interfere with multi-channel eligibility assertions). Unlike the
     * other helpers this does NOT auto-register the channels — callers
     * stub each channel's org explicitly so cross-org/missing cases are
     * exercised deliberately.
     */
    @SuppressWarnings("unchecked")
    private NotificationSubscription subscriptionWithChannels(UUID org,
            NotificationSeverity routeMinSeverity, List<UUID> channels) {
        NotificationSubscriptionData data = new NotificationSubscriptionData(
                org, null, "multi-channel sub",
                NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES),
                new FilterConfig(EvaluationMode.PRESET, null, null),
                List.of(new RouteConfig(routeMinSeverity, null, null, channels)),
                null, null);
        NotificationSubscription sub = new NotificationSubscription();
        sub.setUuid(UUID.randomUUID());
        sub.setRecordData(Utils.OM.convertValue(data, Map.class));
        return sub;
    }

    // ---------- Phase 13b: channel-group expansion ----------

    @Test
    void channelGroupExpansionProducesOneDeliveryPerMemberChannel() throws Exception {
        // A route with channelGroups=[g1] where g1 resolves to [ch1, ch2]
        // produces exactly two deliveries — one per member channel.
        // Pins the group-resolves-via-service path on its own.
        UUID groupUuid = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);

        when(channelGroupService.resolveChannelUuids(eq(List.of(groupUuid))))
                .thenReturn(List.of(ch1, ch2));

        NotificationSubscription sub = subscriptionWithGroups(
                event.getOrg(), List.of(), List.of(groupUuid));
        stubChannelInOrg(ch1, event.getOrg());
        stubChannelInOrg(ch2, event.getOrg());
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo, times(2)).save(captor.capture());
        List<UUID> delivered = captor.getAllValues().stream()
                .map(NotificationDelivery::getChannelUuid).toList();
        assertTrue(delivered.contains(ch1), "Group member ch1 should be delivered to: " + delivered);
        assertTrue(delivered.contains(ch2), "Group member ch2 should be delivered to: " + delivered);
    }

    @Test
    void channelGroupAndDirectChannelMergedDedupCollapsesOverlap() throws Exception {
        // A route with channels=[ch1] AND channelGroups=[g1] where g1
        // resolves to [ch1, ch2] yields exactly two deliveries — ch1
        // once (dedup collapses the duplicate) and ch2 once. First-seen
        // order means direct ch1 takes precedence over the group's
        // restated ch1.
        UUID groupUuid = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);

        when(channelGroupService.resolveChannelUuids(eq(List.of(groupUuid))))
                .thenReturn(List.of(ch1, ch2));

        NotificationSubscription sub = subscriptionWithGroups(
                event.getOrg(), List.of(ch1), List.of(groupUuid));
        stubChannelInOrg(ch2, event.getOrg());
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        // Exactly 2 saves — overlapping ch1 collapsed by the merge
        // helper, not by the delivery-row dedup window. (Dedup window
        // would still suppress duplicates within attempt count, but the
        // merge step prevents the duplicate save in the first place so
        // we don't write-then-suppress.)
        verify(deliveryRepo, times(2)).save(captor.capture());
        List<UUID> delivered = captor.getAllValues().stream()
                .map(NotificationDelivery::getChannelUuid).toList();
        assertEquals(2, delivered.size());
        assertTrue(delivered.contains(ch1));
        assertTrue(delivered.contains(ch2));
    }

    @Test
    void routeWithNoChannelsAndNoResolvableGroupsLogsAndSkips() throws Exception {
        // A route whose channelGroups list resolves to empty (every group
        // missing, or empty groups) AND has no direct channels produces
        // zero deliveries — and importantly doesn't crash the batch.
        UUID groupUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);

        when(channelGroupService.resolveChannelUuids(eq(List.of(groupUuid))))
                .thenReturn(List.of()); // group resolves to nothing

        NotificationSubscription sub = subscriptionWithGroups(
                event.getOrg(), List.of(), List.of(groupUuid));
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        verify(deliveryRepo, never()).save(any());
        // Event still flips to FANNED_OUT — empty-route is a valid no-op,
        // not a per-event failure.
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    @Test
    void routeWithChannelGroupsNullDoesNotCallGroupService() throws Exception {
        // Pre-Phase-13b row shape: channelGroups field absent on JSONB,
        // back-compat ctor lands it as null. The fan-out merge helper
        // skips the group-resolve call entirely on null to avoid a
        // wasted service round-trip on every route.
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        NotificationSubscription sub = subscriptionWith(
                event.getOrg(), null, NotificationSeverity.LOW, channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        verify(deliveryRepo, times(1)).save(any());
        // No group-resolve call for a route that never declared groups.
        verify(channelGroupService, never()).resolveChannelUuids(any());
    }

    /**
     * Phase 13b helper. Builds a subscription whose single route has the
     * given direct channels + channel-group references. Severity gate
     * set to LOW so it doesn't interfere with the group-expansion
     * assertions.
     */
    @SuppressWarnings("unchecked")
    private NotificationSubscription subscriptionWithGroups(UUID org,
            List<UUID> channels, List<UUID> channelGroups) {
        NotificationSubscriptionData data = new NotificationSubscriptionData(
                org, null, "group-route sub",
                NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES),
                new FilterConfig(EvaluationMode.PRESET, null, null),
                List.of(new RouteConfig(NotificationSeverity.LOW, null, null,
                        channels, null, channelGroups)),
                null, null);
        NotificationSubscription sub = new NotificationSubscription();
        sub.setUuid(UUID.randomUUID());
        sub.setRecordData(Utils.OM.convertValue(data, Map.class));
        if (channels != null) for (UUID c : channels) stubChannelInOrg(c, org);
        return sub;
    }

    /**
     * Register channelUuid → org for the S-5 guard's lookup. Auto-called
     * from the subscription helpers so happy-path tests don't have to
     * think about the guard. Cross-tenant defence tests override
     * after the helper call.
     */
    private void stubChannelInOrg(UUID channelUuid, UUID org) {
        channelOrgs.put(channelUuid, org);
    }

    // ---------- Phase 4a: targeted approval deliveries ----------

    @Test
    void approvalRequestedWritesTargetedRowsEvenWithoutSubscriptions() {
        UUID t1 = UUID.randomUUID();
        UUID t2 = UUID.randomUUID();
        NotificationOutboxEvent event = approvalRequestedEvent(java.util.Arrays.asList(t1, t2));

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of());

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo, times(2)).save(captor.capture());
        List<NotificationDelivery> rows = captor.getAllValues();
        assertEquals(List.of(t1, t2), rows.stream().map(NotificationDelivery::getTargetUser).toList());
        for (NotificationDelivery row : rows) {
            assertEquals(event.getOrg(), row.getOrg());
            assertEquals(event.getUuid(), row.getOutboxEventUuid());
            assertEquals(null, row.getSubscriptionUuid());
            assertEquals(null, row.getChannelUuid());
            assertEquals(NotificationDeliveryStatus.SENT, row.getStatus());
            assertNotNull(row.getSentAt(), "Targeted rows are born SENT with sentAt stamped");
            assertEquals(event.getDedupKey(), row.getDedupKey());
            assertEquals(NotificationDeliveryOrigin.REAL, row.getOrigin());
        }
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    @Test
    void approvalRequestedDedupsAndSkipsNullTargets() {
        UUID t1 = UUID.randomUUID();
        NotificationOutboxEvent event = approvalRequestedEvent(java.util.Arrays.asList(t1, null, t1));

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of());

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo, times(1)).save(captor.capture());
        assertEquals(t1, captor.getValue().getTargetUser());
    }

    @Test
    void approvalRequestedWithoutTargetsWritesNothing() {
        NotificationOutboxEvent event = approvalRequestedEvent(List.of());

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of());

        fanOut.drainBatch(50);

        verify(deliveryRepo, never()).save(any());
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    @Test
    void approvalResolvedMarksTargetedRowsReadForTheirUsers() throws Exception {
        UUID releaseUuid = UUID.randomUUID();
        UUID requestUuid = UUID.randomUUID();
        NotificationOutboxEvent event = approvalResolvedEvent(releaseUuid, List.of(requestUuid));
        String requestedKey = "approval:requested:" + releaseUuid + ":" + requestUuid;

        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        NotificationDelivery d1 = targetedDelivery(event.getOrg(), u1);
        NotificationDelivery d2 = targetedDelivery(event.getOrg(), u2);
        when(deliveryRepo.findTargetedByDedupKey(event.getOrg(), requestedKey))
                .thenReturn(List.of(d1, d2));
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of());

        fanOut.drainBatch(50);

        verify(readService).markRead(eq(u1), eq(d1.getUuid()), any());
        verify(readService).markRead(eq(u2), eq(d2.getUuid()), any());
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    @Test
    void approvalResolvedWithoutResolvedRequestsTouchesNothing() throws Exception {
        NotificationOutboxEvent event = approvalResolvedEvent(UUID.randomUUID(), List.of());

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of());

        fanOut.drainBatch(50);

        verify(deliveryRepo, never()).findTargetedByDedupKey(any(), anyString());
        verify(readService, never()).markRead(any(), any(), any());
    }

    @Test
    void approvalResolvedMarkReadFailureDoesNotFailEvent() throws Exception {
        UUID releaseUuid = UUID.randomUUID();
        UUID requestUuid = UUID.randomUUID();
        NotificationOutboxEvent event = approvalResolvedEvent(releaseUuid, List.of(requestUuid));
        NotificationDelivery d1 = targetedDelivery(event.getOrg(), UUID.randomUUID());
        when(deliveryRepo.findTargetedByDedupKey(eq(event.getOrg()), anyString()))
                .thenReturn(List.of(d1));
        when(readService.markRead(any(), any(), any())).thenThrow(new RelizaException("reads down"));
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of());

        fanOut.drainBatch(50);

        // Stale-unread is cosmetic; the event itself still fans out fine.
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    @SuppressWarnings("unchecked")
    private NotificationOutboxEvent approvalRequestedEvent(List<UUID> targetUsers) {
        UUID releaseUuid = UUID.randomUUID();
        UUID requestUuid = UUID.randomUUID();
        ApprovalRequestedPayload payload = new ApprovalRequestedPayload(
                approvalReleaseRef(releaseUuid), requestUuid, UUID.randomUUID(),
                "Alice", "alice@example.com",
                List.of(new ApprovalRequestEntryRef(UUID.randomUUID(), "QA sign-off")),
                targetUsers);
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(UUID.randomUUID());
        event.setEventType(NotificationEventType.APPROVAL_REQUESTED);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOccurredAt(ZonedDateTime.now());
        event.setDedupKey("approval:requested:" + releaseUuid + ":" + requestUuid);
        event.setRecordData(Utils.OM.convertValue(payload, Map.class));
        return event;
    }

    @SuppressWarnings("unchecked")
    private NotificationOutboxEvent approvalResolvedEvent(UUID releaseUuid, List<UUID> resolvedRequestUuids) {
        UUID entryUuid = UUID.randomUUID();
        ApprovalResolvedPayload payload = new ApprovalResolvedPayload(
                approvalReleaseRef(releaseUuid), entryUuid, "QA sign-off",
                ApprovalResolvedPayload.Resolution.APPROVED,
                UUID.randomUUID(), "Bob", "bob@example.com", resolvedRequestUuids);
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(UUID.randomUUID());
        event.setEventType(NotificationEventType.APPROVAL_RESOLVED);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOccurredAt(ZonedDateTime.now());
        event.setDedupKey("approval:resolved:" + releaseUuid + ":" + entryUuid);
        event.setRecordData(Utils.OM.convertValue(payload, Map.class));
        return event;
    }

    private static ReleaseRef approvalReleaseRef(UUID releaseUuid) {
        return new ReleaseRef(releaseUuid, "v1.0", UUID.randomUUID(), "myapp", null,
                null, "main", ReleaseLifecycle.ASSEMBLED, null, null, null, null, null);
    }

    private static NotificationDelivery targetedDelivery(UUID org, UUID targetUser) {
        NotificationDelivery d = new NotificationDelivery();
        d.setOrg(org);
        d.setTargetUser(targetUser);
        d.setStatus(NotificationDeliveryStatus.SENT);
        d.setSentAt(ZonedDateTime.now());
        return d;
    }

    // ---------- Phase 5: email rolling-cap digest decision ----------

    @Test
    void emailRollingChannelJoinsOpenBatch() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        NotificationSubscription sub = subscriptionWith(event.getOrg(), null,
                NotificationSeverity.LOW, channelUuid);
        stubEmailChannel(channelUuid, event.getOrg(), Map.of());

        ZonedDateTime batchDeadline = ZonedDateTime.now().plusHours(3);
        when(deliveryRepo.findOpenBatchHead(channelUuid))
                .thenReturn(List.of(batchedRow(batchDeadline)));
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        assertEquals(NotificationDeliveryStatus.BATCHED, captor.getValue().getStatus());
        assertEquals(batchDeadline, captor.getValue().getNextAttemptAt());
        // joining an open batch must not probe the last-send history
        verify(deliveryRepo, never()).findLastCountedEmailSend(any(), any());
    }

    @Test
    void emailRollingChannelWithinIntervalOpensNewBatch() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        NotificationSubscription sub = subscriptionWith(event.getOrg(), null,
                NotificationSeverity.LOW, channelUuid);
        // custom interval proves the deadline comes from channel params
        stubEmailChannel(channelUuid, event.getOrg(),
                Map.of(io.reliza.model.EmailDigestPolicy.DIGEST_INTERVAL_KEY, "PT4H"));

        ZonedDateTime lastSentAt = ZonedDateTime.now().minusHours(1);
        NotificationDelivery lastSend = new NotificationDelivery();
        lastSend.setStatus(NotificationDeliveryStatus.SENT);
        lastSend.setSentAt(lastSentAt);
        when(deliveryRepo.findLastCountedEmailSend(eq(channelUuid), any()))
                .thenReturn(List.of(lastSend));
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        assertEquals(NotificationDeliveryStatus.BATCHED, captor.getValue().getStatus());
        assertEquals(lastSentAt.plusHours(4), captor.getValue().getNextAttemptAt());
    }

    @Test
    void emailRollingChannelExpiredIntervalSendsImmediately() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        NotificationSubscription sub = subscriptionWith(event.getOrg(), null,
                NotificationSeverity.LOW, channelUuid);
        stubEmailChannel(channelUuid, event.getOrg(), Map.of());

        NotificationDelivery lastSend = new NotificationDelivery();
        lastSend.setStatus(NotificationDeliveryStatus.SENT);
        lastSend.setSentAt(ZonedDateTime.now().minusHours(25)); // beyond default 24h
        when(deliveryRepo.findLastCountedEmailSend(eq(channelUuid), any()))
                .thenReturn(List.of(lastSend));
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        assertEquals(NotificationDeliveryStatus.PENDING, captor.getValue().getStatus());
    }

    @Test
    void emailRollingChannelQuietChannelSendsImmediately() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        NotificationSubscription sub = subscriptionWith(event.getOrg(), null,
                NotificationSeverity.LOW, channelUuid);
        stubEmailChannel(channelUuid, event.getOrg(), Map.of());
        // no stubs: Mockito's default empty lists = no open batch, no prior send

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        assertEquals(NotificationDeliveryStatus.PENDING, captor.getValue().getStatus());
    }

    @Test
    void emailImmediatePolicyNeverBatches() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        NotificationSubscription sub = subscriptionWith(event.getOrg(), null,
                NotificationSeverity.LOW, channelUuid);
        stubEmailChannel(channelUuid, event.getOrg(),
                Map.of(io.reliza.model.EmailDigestPolicy.DIGEST_MODE_KEY, "IMMEDIATE"));

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        assertEquals(NotificationDeliveryStatus.PENDING, captor.getValue().getStatus());
        verify(deliveryRepo, never()).findOpenBatchHead(any());
        verify(deliveryRepo, never()).findLastCountedEmailSend(any(), any());
    }

    @Test
    void syntheticEventBypassesDigest() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        event.setOrigin(NotificationDeliveryOrigin.SYNTHETIC);
        NotificationSubscription sub = subscriptionWith(event.getOrg(), null,
                NotificationSeverity.LOW, channelUuid);
        stubEmailChannel(channelUuid, event.getOrg(), Map.of());
        when(deliveryRepo.findOpenBatchHead(channelUuid))
                .thenReturn(List.of(batchedRow(ZonedDateTime.now().plusHours(3))));

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        assertEquals(NotificationDeliveryStatus.PENDING, captor.getValue().getStatus());
    }

    @Test
    void actionableEventBypassesDigest() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        // empty target list so only the subscription-routed row is written
        NotificationOutboxEvent event = approvalRequestedEvent(List.of());
        NotificationSubscription sub = subscriptionFor(event.getOrg(),
                List.of(NotificationEventType.APPROVAL_REQUESTED), null, null, channelUuid);
        stubEmailChannel(channelUuid, event.getOrg(), Map.of());
        when(deliveryRepo.findOpenBatchHead(channelUuid))
                .thenReturn(List.of(batchedRow(ZonedDateTime.now().plusHours(3))));

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        assertEquals(NotificationDeliveryStatus.PENDING, captor.getValue().getStatus());
        verify(deliveryRepo, never()).findOpenBatchHead(any());
    }

    @Test
    void nonEmailChannelNeverConsultsDigestQueries() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        NotificationOutboxEvent event = vulnEvent(NotificationSeverity.CRITICAL);
        // default subscriptionWith stub registers a SLACK channel
        NotificationSubscription sub = subscriptionWith(event.getOrg(), null,
                NotificationSeverity.LOW, channelUuid);

        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(sub));

        fanOut.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        assertEquals(NotificationDeliveryStatus.PENDING, captor.getValue().getStatus());
        verify(deliveryRepo, never()).findOpenBatchHead(any());
        verify(deliveryRepo, never()).findLastCountedEmailSend(any(), any());
    }

    /**
     * Override the default SLACK channel stub with an EMAIL channel
     * carrying the given digest parameters (empty map = defaults =
     * ROLLING / 24h per the locked Phase 5 design).
     */
    private void stubEmailChannel(UUID channelUuid, UUID org, Map<String, Object> parameters) {
        Integration channel = new Integration();
        channel.setUuid(channelUuid);
        IntegrationData data = new IntegrationData();
        data.setUuid(channelUuid);
        data.setIdentifier(channelUuid.toString());
        data.setOrg(org);
        data.setName("email-channel");
        data.setType(IntegrationType.EMAIL);
        data.setIsEnabled(Boolean.TRUE);
        data.setParameters(new HashMap<>(parameters));
        channel.setRecordData(Utils.dataToRecord(data));
        doReturn(Optional.of(channel))
                .when(channelService).getChannel(channelUuid);
    }

    private static NotificationDelivery batchedRow(ZonedDateTime deadline) {
        NotificationDelivery d = new NotificationDelivery();
        d.setStatus(NotificationDeliveryStatus.BATCHED);
        d.setNextAttemptAt(deadline);
        return d;
    }

    // ---------- helpers ----------

    private NotificationOutboxEvent vulnEvent(NotificationSeverity severity) {
        NewVulnAffectsReleasesPayload payload = new NewVulnAffectsReleasesPayload(
                "CVE-2025-12345", List.of(), 9.8, "vec", 0.5, false, "fix",
                severity, null, List.of(new AffectedRelease(
                        UUID.randomUUID(), "myapp", "v1.0", "main", null, List.of())));
        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = Utils.OM.convertValue(payload, Map.class);

        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(UUID.randomUUID());
        event.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOccurredAt(ZonedDateTime.now());
        event.setRecordData(recordData);
        return event;
    }

    private NotificationSubscription subscriptionWith(UUID org, String celExpression,
            NotificationSeverity routeMinSeverity, UUID channelUuid) {
        return subscriptionFor(
                org,
                List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES),
                celExpression,
                routeMinSeverity,
                channelUuid);
    }

    /**
     * Phase 12 helper. Builds a subscription whose single route is gated
     * by the given perspective list (severity gate set to LOW so it
     * doesn't interfere with the perspective assertion).
     */
    @SuppressWarnings("unchecked")
    private NotificationSubscription subscriptionWithPerspectives(UUID org, UUID channelUuid,
            List<UUID> routePerspectives) {
        NotificationSubscriptionData data = new NotificationSubscriptionData(
                org, null, "perspective-scoped sub",
                NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES),
                new FilterConfig(EvaluationMode.PRESET, null, null),
                List.of(new RouteConfig(NotificationSeverity.LOW, null, null,
                        List.of(channelUuid), routePerspectives)),
                null, null);
        NotificationSubscription sub = new NotificationSubscription();
        sub.setUuid(UUID.randomUUID());
        sub.setRecordData(Utils.OM.convertValue(data, Map.class));
        stubChannelInOrg(channelUuid, org);
        return sub;
    }

    /** Phase 12 helper. Builds a producer-shape event with no affectedReleases set. */
    @SuppressWarnings("unchecked")
    private NotificationOutboxEvent bareVulnEvent(UUID orgUuid, String cve) {
        NewVulnAffectsReleasesPayload bare = new NewVulnAffectsReleasesPayload(
                cve, List.of(cve), 9.8, "vec", 0.5, false, null,
                NotificationSeverity.CRITICAL, null, null);
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(orgUuid);
        event.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOccurredAt(ZonedDateTime.now());
        event.setRecordData(Utils.OM.convertValue(bare, Map.class));
        return event;
    }

    /**
     * Phase 12 helper. Wires the full enrichment dependency chain so a
     * test only has to think about the inputs that matter for the
     * assertion (the component's perspectives, mostly).
     */
    private void wireEnrichmentChain(UUID orgUuid, String cve, UUID artifactUuid,
            UUID releaseUuid, UUID branchUuid, UUID componentUuid,
            ComponentData component) {
        when(artifactRepo.findArtifactsWithVulnId(orgUuid.toString(), cve))
                .thenReturn(List.of(artifactUuid));
        ReleaseData rd = buildReleaseData(releaseUuid, orgUuid, branchUuid, componentUuid,
                "v1.0", ReleaseLifecycle.GENERAL_AVAILABILITY);
        when(sharedReleaseService.gatherReleasesForArtifact(artifactUuid, orgUuid))
                .thenReturn(List.of(rd));
        when(branchService.getBranchData(branchUuid))
                .thenReturn(Optional.of(buildBranchData("main")));
        when(getComponentService.getComponentData(componentUuid))
                .thenReturn(Optional.of(component));
    }

    @SuppressWarnings("unchecked")
    private NotificationSubscription subscriptionFor(UUID org,
            List<NotificationEventType> eventTypes, String celExpression,
            NotificationSeverity routeMinSeverity, UUID channelUuid) {
        NotificationSubscriptionData data = new NotificationSubscriptionData(
                org,
                null,
                "test sub",
                NotificationSubscriptionStatus.ACTIVE,
                eventTypes,
                new FilterConfig(EvaluationMode.PRESET, null, celExpression),
                List.of(new RouteConfig(routeMinSeverity, null, null, List.of(channelUuid))),
                null,
                null);
        NotificationSubscription sub = new NotificationSubscription();
        sub.setUuid(UUID.randomUUID());
        sub.setRecordData(Utils.OM.convertValue(data, Map.class));
        stubChannelInOrg(channelUuid, org);
        return sub;
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = NotificationFanOutService.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }

    /**
     * ReleaseData has package-private constructors and private setters
     * for uuid/org/branch/component (audit-controlled in production).
     * Route the test fixture through Jackson so the deserializer can
     * land those fields without the visibility restriction.
     */
    private static ReleaseData buildReleaseData(UUID uuid, UUID org, UUID branch, UUID component,
            String version, ReleaseLifecycle lifecycle) {
        Map<String, Object> m = new HashMap<>();
        m.put("uuid", uuid.toString());
        m.put("org", org.toString());
        m.put("branch", branch.toString());
        m.put("component", component.toString());
        m.put("version", version);
        m.put("lifecycle", lifecycle.name());
        return Utils.OM.convertValue(m, ReleaseData.class);
    }

    private static BranchData buildBranchData(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        return Utils.OM.convertValue(m, BranchData.class);
    }

    private static ComponentData buildComponentData(String name) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        return Utils.OM.convertValue(m, ComponentData.class);
    }

    private static ComponentData buildComponentData(String name, java.util.Set<UUID> perspectives) {
        Map<String, Object> m = new HashMap<>();
        m.put("name", name);
        if (perspectives != null) {
            m.put("perspectives", perspectives.stream().map(UUID::toString).toList());
        }
        return Utils.OM.convertValue(m, ComponentData.class);
    }
}
