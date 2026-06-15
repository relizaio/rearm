/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.reliza.common.Utils;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationDelivery;
import io.reliza.model.NotificationDeliveryOrigin;
import io.reliza.model.NotificationDeliveryStatus;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.util.BackoffPolicy;

/**
 * Covers the delivery worker's outcome handling — success, retriable
 * failure (with and without Retry-After), non-retriable failure, max
 * attempts terminal failure, missing channel/event short-circuit,
 * disabled-channel hold, and unknown channel-type rejection. Pure
 * unit; the SlackChannelDispatcher and repositories are mocked.
 */
class NotificationDeliveryWorkerTest {

    private NotificationDeliveryRepository deliveryRepo;
    private IntegrationRepository integrationRepo;
    private NotificationOutboxEventRepository outboxRepo;
    private SlackChannelDispatcher slackDispatcher;
    private NotificationDeliveryWorker worker;

    @BeforeEach
    void wireMocks() throws Exception {
        deliveryRepo = mock(NotificationDeliveryRepository.class);
        integrationRepo = mock(IntegrationRepository.class);
        outboxRepo = mock(NotificationOutboxEventRepository.class);
        slackDispatcher = mock(SlackChannelDispatcher.class);
        // The worker now indexes dispatchers by supportedType() in its
        // @PostConstruct registry, so the mock must report its type.
        when(slackDispatcher.supportedType()).thenReturn(IntegrationType.SLACK);
        when(deliveryRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        worker = new NotificationDeliveryWorker();
        inject(worker, "deliveryRepo", deliveryRepo);
        inject(worker, "integrationRepo", integrationRepo);
        inject(worker, "outboxRepo", outboxRepo);
        // Inject the dispatcher list the same way Spring would, then run the
        // registry builder (@PostConstruct in production) to index by type.
        inject(worker, "channelDispatchers", List.<ChannelDispatcher>of(slackDispatcher));
        worker.buildDispatcherRegistry();
    }

    @Test
    void emptyBatchReturnsZero() {
        when(deliveryRepo.findReadyForDelivery(any(), anyInt())).thenReturn(List.of());
        assertEquals(0, worker.drainBatch(50));
    }

    @Test
    void successfulDispatchMarksSent() {
        NotificationDelivery delivery = pendingDelivery();
        Integration channel = slackChannel(true);
        NotificationOutboxEvent event = stubEvent();
        wireBatch(delivery, channel, event);

        when(slackDispatcher.dispatch(any(), any())).thenReturn(ChannelDispatchResult.success());

        worker.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        NotificationDelivery saved = captor.getValue();
        assertEquals(NotificationDeliveryStatus.SENT, saved.getStatus());
        assertEquals(1, saved.getAttemptCount());
        assertNotNull(saved.getSentAt());
        assertNull(saved.getLastError());
    }

    @Test
    void retriableFailureKeepsStatusPendingAndBumpsAttemptViaBackoff() {
        NotificationDelivery delivery = pendingDelivery();
        Integration channel = slackChannel(true);
        NotificationOutboxEvent event = stubEvent();
        wireBatch(delivery, channel, event);

        when(slackDispatcher.dispatch(any(), any()))
                .thenReturn(ChannelDispatchResult.retriable("Slack returned 503"));

        worker.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        NotificationDelivery saved = captor.getValue();
        // Status stays PENDING; we just bumped attempts + scheduled next
        assertEquals(NotificationDeliveryStatus.PENDING, saved.getStatus());
        assertEquals(1, saved.getAttemptCount());
        assertEquals("Slack returned 503", saved.getLastError());
        // next_attempt_at should be ~now + BackoffPolicy.dtrackFetchSkipSeconds(1) = 60s
        long expectedDelaySeconds = BackoffPolicy.dtrackFetchSkipSeconds(1);
        assertTrue(saved.getNextAttemptAt().isAfter(ZonedDateTime.now().plusSeconds(expectedDelaySeconds - 5)),
                "next_attempt_at should be ~" + expectedDelaySeconds + "s out");
    }

    @Test
    void retriableFailureWithRetryAfterHonorsHeader() {
        NotificationDelivery delivery = pendingDelivery();
        Integration channel = slackChannel(true);
        NotificationOutboxEvent event = stubEvent();
        wireBatch(delivery, channel, event);

        when(slackDispatcher.dispatch(any(), any()))
                .thenReturn(ChannelDispatchResult.retriableAfter("Slack 429", 30));

        worker.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        NotificationDelivery saved = captor.getValue();
        // next_attempt_at honors the 30s Retry-After, not the BackoffPolicy
        // curve's 60s for attempt 1
        ZonedDateTime expected = ZonedDateTime.now().plusSeconds(30);
        assertTrue(saved.getNextAttemptAt().isBefore(expected.plusSeconds(5))
                        && saved.getNextAttemptAt().isAfter(expected.minusSeconds(5)),
                "Expected next_attempt_at to honor Retry-After of 30s, got " + saved.getNextAttemptAt());
    }

    @Test
    void nonRetriableFailureMarksFailed() {
        NotificationDelivery delivery = pendingDelivery();
        Integration channel = slackChannel(true);
        NotificationOutboxEvent event = stubEvent();
        wireBatch(delivery, channel, event);

        when(slackDispatcher.dispatch(any(), any()))
                .thenReturn(ChannelDispatchResult.nonRetriable("Slack 404: invalid_webhook_url"));

        worker.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        NotificationDelivery saved = captor.getValue();
        assertEquals(NotificationDeliveryStatus.FAILED, saved.getStatus());
        assertTrue(saved.getLastError().contains("invalid_webhook_url"));
    }

    @Test
    void retriableFailureAtMaxAttemptsMarksFailedTerminal() {
        NotificationDelivery delivery = pendingDelivery();
        // Already 6 attempts; the 7th retriable should hit the cap
        delivery.setAttemptCount(NotificationDeliveryWorker.MAX_ATTEMPTS - 1);
        Integration channel = slackChannel(true);
        NotificationOutboxEvent event = stubEvent();
        wireBatch(delivery, channel, event);

        when(slackDispatcher.dispatch(any(), any()))
                .thenReturn(ChannelDispatchResult.retriable("Slack 503"));

        worker.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        NotificationDelivery saved = captor.getValue();
        assertEquals(NotificationDeliveryStatus.FAILED, saved.getStatus());
        assertEquals(NotificationDeliveryWorker.MAX_ATTEMPTS, saved.getAttemptCount());
    }

    @Test
    void missingChannelMarksFailed() {
        NotificationDelivery delivery = pendingDelivery();
        when(deliveryRepo.findReadyForDelivery(any(), anyInt())).thenReturn(List.of(delivery));
        when(integrationRepo.findById(delivery.getChannelUuid())).thenReturn(Optional.empty());

        worker.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        assertEquals(NotificationDeliveryStatus.FAILED, captor.getValue().getStatus());
        // Dispatcher never called
        verify(slackDispatcher, never()).dispatch(any(), any());
    }

    @Test
    void disabledChannelDeferredNotFailed() {
        NotificationDelivery delivery = pendingDelivery();
        Integration channel = slackChannel(false);
        alignChannelOrg(channel, delivery.getOrg());
        when(deliveryRepo.findReadyForDelivery(any(), anyInt())).thenReturn(List.of(delivery));
        when(integrationRepo.findById(delivery.getChannelUuid())).thenReturn(Optional.of(channel));

        worker.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        NotificationDelivery saved = captor.getValue();
        // Treated as retriable transient — stays PENDING, attempts bumped,
        // so a long-disabled channel eventually hits MAX_ATTEMPTS and
        // terminates rather than queueing forever.
        assertEquals(NotificationDeliveryStatus.PENDING, saved.getStatus());
        assertEquals(1, saved.getAttemptCount());
        assertTrue(saved.getLastError().contains("disabled"));
        verify(slackDispatcher, never()).dispatch(any(), any());
    }

    @Test
    void crossTenantDeliveryRejectedByOrgGuard() {
        // S-5 defence-in-depth: a delivery row whose channel's org
        // differs from delivery.org gets terminally FAILED at the
        // worker entry, before the dispatcher is touched. The save-
        // time invariant on the standard path already prevents this
        // shape from existing; the guard exists for the future case
        // of a new delivery-creation path bypassing the validator.
        NotificationDelivery delivery = pendingDelivery();
        Integration channel = slackChannel(true);
        // Channel's org is a fresh UUID from slackChannel; do NOT
        // align it — that's the whole point of this test.
        when(deliveryRepo.findReadyForDelivery(any(), anyInt())).thenReturn(List.of(delivery));
        when(integrationRepo.findById(delivery.getChannelUuid())).thenReturn(Optional.of(channel));

        worker.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        NotificationDelivery saved = captor.getValue();
        assertEquals(NotificationDeliveryStatus.FAILED, saved.getStatus());
        assertTrue(saved.getLastError() != null && saved.getLastError().contains("does not belong to this organization"),
                "Expected org-mismatch error; got: " + saved.getLastError());
        // Forensic: the foreign-org uuid MUST NOT leak into the
        // operator-visible row. Goes to log.warn only.
        IntegrationData chData = IntegrationData.dataFromRecord(channel);
        assertTrue(!saved.getLastError().contains(chData.getOrg().toString()),
                "Foreign org uuid leaked into lastError: " + saved.getLastError());
        // Dispatcher MUST NOT have been called — guard short-circuits
        // before any external HTTP touch.
        verify(slackDispatcher, never()).dispatch(any(), any());
    }

    @Test
    void missingOutboxEventMarksFailed() {
        NotificationDelivery delivery = pendingDelivery();
        Integration channel = slackChannel(true);
        alignChannelOrg(channel, delivery.getOrg());
        when(deliveryRepo.findReadyForDelivery(any(), anyInt())).thenReturn(List.of(delivery));
        when(integrationRepo.findById(delivery.getChannelUuid())).thenReturn(Optional.of(channel));
        when(outboxRepo.findById(delivery.getOutboxEventUuid())).thenReturn(Optional.empty());

        worker.drainBatch(50);

        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo).save(captor.capture());
        assertEquals(NotificationDeliveryStatus.FAILED, captor.getValue().getStatus());
    }

    // Note: there's no longer a "channel type with no dispatcher" test
    // — all five NotificationChannelType values (SLACK, WEBHOOK, EMAIL,
    // MS_TEAMS, SENTINEL) ship dispatchers in Phase 4/9/10/11. The
    // defense-in-depth gate in processOne() remains as a guard for
    // future enum growth: a new type added without a dispatcher case
    // would fall through to markFailed() at that gate, and the
    // exhaustive switch's default arm would throw if the gate were
    // ever bypassed.

    @Test
    void unexpectedExceptionInDispatchDoesNotKillBatch() {
        // Two deliveries; dispatcher throws on the first.
        NotificationDelivery d1 = pendingDelivery();
        NotificationDelivery d2 = pendingDelivery();
        // Both deliveries share an org so a single shared channel
        // passes the S-5 org guard for both. The org-mismatch case
        // is exercised by its own test (crossTenantDeliveryRejected).
        d2.setOrg(d1.getOrg());
        Integration channel = slackChannel(true);
        alignChannelOrg(channel, d1.getOrg());
        NotificationOutboxEvent event = stubEvent();
        when(deliveryRepo.findReadyForDelivery(any(), anyInt())).thenReturn(List.of(d1, d2));
        when(integrationRepo.findById(any())).thenReturn(Optional.of(channel));
        when(outboxRepo.findById(any())).thenReturn(Optional.of(event));
        when(slackDispatcher.dispatch(any(), any()))
                .thenThrow(new RuntimeException("dispatcher exploded"))
                .thenReturn(ChannelDispatchResult.success());

        worker.drainBatch(50);

        // Both deliveries were processed despite d1 throwing
        verify(slackDispatcher, times(2)).dispatch(any(), any());
        // Only d2's save fires (SENT). d1 threw before any save in processOne
        // so its row stays in whatever state it was in — the outer catch in
        // drainBatch logs and moves on without touching the row. Pinning
        // this exact behaviour catches a future regression where a refactor
        // introduces a "claim" save before the HTTP call.
        ArgumentCaptor<NotificationDelivery> savedCaptor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo, times(1)).save(savedCaptor.capture());
        assertEquals(d2.getUuid(), savedCaptor.getValue().getUuid(),
                "Only d2 should have been saved; d1 threw before reaching any save");
    }

    // ---------- helpers ----------

    private NotificationDelivery pendingDelivery() {
        NotificationDelivery d = new NotificationDelivery();
        d.setUuid(UUID.randomUUID());
        d.setOrg(UUID.randomUUID());
        d.setOutboxEventUuid(UUID.randomUUID());
        d.setSubscriptionUuid(UUID.randomUUID());
        d.setChannelUuid(UUID.randomUUID());
        d.setStatus(NotificationDeliveryStatus.PENDING);
        d.setAttemptCount(0);
        d.setNextAttemptAt(ZonedDateTime.now().minusSeconds(1));
        d.setOrigin(NotificationDeliveryOrigin.REAL);
        d.setRecordData(new HashMap<>());
        return d;
    }

    private Integration slackChannel(boolean enabled) {
        return channelOfType(IntegrationType.SLACK, enabled);
    }

    private Integration channelOfType(IntegrationType type, boolean enabled) {
        IntegrationData data = new IntegrationData();
        data.setUuid(UUID.randomUUID());
        data.setIdentifier(UUID.randomUUID().toString());
        data.setOrg(UUID.randomUUID());
        data.setName("test channel");
        data.setType(type);
        data.setIsEnabled(enabled);
        data.setSecret("encrypted-secret-blob");
        data.setParameters(new HashMap<>());
        Integration channel = new Integration();
        channel.setUuid(UUID.randomUUID());
        channel.setRecordData(Utils.dataToRecord(data));
        return channel;
    }

    private NotificationOutboxEvent stubEvent() {
        NotificationOutboxEvent e = new NotificationOutboxEvent();
        e.setUuid(UUID.randomUUID());
        e.setOrg(UUID.randomUUID());
        e.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        e.setStatus(NotificationOutboxStatus.FANNED_OUT);
        e.setOccurredAt(ZonedDateTime.now());
        e.setOrigin(NotificationDeliveryOrigin.REAL);
        e.setRecordData(new HashMap<>());
        return e;
    }

    private void wireBatch(NotificationDelivery delivery, Integration channel,
            NotificationOutboxEvent event) {
        alignChannelOrg(channel, delivery.getOrg());
        when(deliveryRepo.findReadyForDelivery(any(), anyInt())).thenReturn(List.of(delivery));
        when(integrationRepo.findById(delivery.getChannelUuid())).thenReturn(Optional.of(channel));
        when(outboxRepo.findById(delivery.getOutboxEventUuid())).thenReturn(Optional.of(event));
    }

    /**
     * Re-align the channel's record_data.org to match the supplied
     * delivery org so the S-5 worker-side guard sees a matching pair.
     * The slackChannel / channelOfType helpers assign random orgs;
     * tests that exercise cross-tenant defence call this with a
     * mismatched org explicitly.
     */
    private static void alignChannelOrg(Integration channel, UUID org) {
        IntegrationData existing = IntegrationData.dataFromRecord(channel);
        existing.setOrg(org);
        channel.setRecordData(Utils.dataToRecord(existing));
    }

    private static void inject(Object target, String fieldName, Object value) throws Exception {
        Field f = NotificationDeliveryWorker.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(target, value);
    }
}
