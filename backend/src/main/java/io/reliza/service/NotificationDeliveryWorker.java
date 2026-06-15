/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationDelivery;
import io.reliza.model.NotificationDeliveryStatus;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.util.BackoffPolicy;
import lombok.extern.slf4j.Slf4j;

/**
 * Drains PENDING {@link NotificationDelivery} rows whose
 * {@code next_attempt_at} has elapsed, dispatches each through the
 * appropriate channel-specific dispatcher (Slack only in v1), and
 * updates the row based on the outcome.
 *
 * <p>Outcomes:
 * <ul>
 *   <li>{@link ChannelDispatchResult.Outcome#SUCCESS} → status SENT,
 *       sent_at = now, attempt_count bumped.</li>
 *   <li>{@link ChannelDispatchResult.Outcome#RETRIABLE_FAILURE} with
 *       attempt_count below {@link #MAX_ATTEMPTS} → status stays
 *       PENDING, next_attempt_at scheduled via the upstream
 *       {@code Retry-After} (if present) or
 *       {@link BackoffPolicy#dtrackFetchSkipSeconds}.</li>
 *   <li>{@link ChannelDispatchResult.Outcome#RETRIABLE_FAILURE} with
 *       attempt_count at/above {@link #MAX_ATTEMPTS} → status FAILED.
 *       Operator can "retry" from the DLQ view to reset.</li>
 *   <li>{@link ChannelDispatchResult.Outcome#NON_RETRIABLE_FAILURE} →
 *       status FAILED immediately.</li>
 * </ul>
 *
 * <p>The {@code @Transactional} boundary covers one delivery at a
 * time: this method drains the batch via a loop, each iteration its
 * own delegation to {@link #processOne}, so a single dispatch
 * exception doesn't roll back peers. The worker tick (in
 * {@code SaasSchedulingService}) holds the
 * {@code DRAIN_NOTIFICATION_DELIVERIES} advisory lock around the
 * whole batch.
 *
 * <p><b>Known race (acknowledged, v1 budget).</b> The advisory lock
 * serialises this work across replicas; the per-row save commits in
 * its own short transaction independent of the lock. If the advisory
 * lock dies mid-batch (connection blip, pod eviction, Hikari recycle)
 * a peer that acquires the lock on the next tick can re-read a row
 * whose Slack POST already succeeded but whose status flip hadn't
 * persisted yet, causing a duplicate POST. Mitigations to consider
 * in a follow-up: (a) add {@code FOR UPDATE SKIP LOCKED} on the
 * findReadyForDelivery query plus a per-row tx wrapping the dispatch
 * (costs DB connections held during HTTP); (b) introduce an
 * intermediate SENDING status flipped before the POST + a "reset
 * SENDING older than 30s back to PENDING" cleanup query (costs a new
 * state machine). v1 accepts the rare-failure window — the advisory
 * lock is reliable in practice, and Slack-side duplicate detection
 * gives a soft backstop.
 */
@Service
@Slf4j
public class NotificationDeliveryWorker {

    /**
     * Total attempts before a delivery is marked FAILED. Matches the
     * natural cap of {@code BackoffPolicy.dtrackFetchSkipSeconds} —
     * after 7 failures the backoff curve is flat at 60 minutes.
     */
    public static final int MAX_ATTEMPTS = 7;

    @Autowired
    private NotificationDeliveryRepository deliveryRepo;

    @Autowired
    private IntegrationRepository integrationRepo;

    @Autowired
    private NotificationOutboxEventRepository outboxRepo;

    /**
     * Every {@link ChannelDispatcher} bean on this edition's classpath.
     * Slack/Teams/Webhook are shared (always present); Email/Sentinel are
     * Pro-only (present only when the {@code saas/} beans are mirrored in).
     * Indexed by {@link IntegrationType} in {@link #buildDispatcherRegistry()}.
     * Optional so an edition with zero dispatchers (theoretical) still starts.
     */
    @Autowired(required = false)
    private List<ChannelDispatcher> channelDispatchers = List.of();

    /** Built once at startup from {@link #channelDispatchers}. */
    private final Map<IntegrationType, ChannelDispatcher> dispatcherByType =
            new EnumMap<>(IntegrationType.class);

    @PostConstruct
    void buildDispatcherRegistry() {
        for (ChannelDispatcher dispatcher : channelDispatchers) {
            ChannelDispatcher prior = dispatcherByType.put(dispatcher.supportedType(), dispatcher);
            if (prior != null) {
                // Two beans claiming the same type is a wiring bug — fail loud
                // rather than silently letting one shadow the other.
                throw new IllegalStateException("Multiple ChannelDispatcher beans for type "
                        + dispatcher.supportedType() + ": " + prior.getClass().getName()
                        + " and " + dispatcher.getClass().getName());
            }
        }
        log.info("Notification delivery worker registered {} channel dispatcher(s): {}",
                dispatcherByType.size(), dispatcherByType.keySet());
    }

    /**
     * Drain one batch. Returns count of deliveries processed (success,
     * retriable, or terminal — anything we touched). Caller holds the
     * advisory lock for the duration.
     */
    public int drainBatch(int batchSize) {
        // TODO (Phase 4+): When Email/Teams/Sentinel channel dispatchers
        // ship, consider filtering findReadyForDelivery by channel type
        // — or splitting this worker into per-type workers each holding
        // a type-specific advisory lock. Today Slack + Webhook share
        // one dispatcher pool, which is fine at v1 scale; per-type
        // queues become valuable if one channel type's tail latency
        // starts head-of-line-blocking the others.
        List<NotificationDelivery> batch = deliveryRepo.findReadyForDelivery(
                ZonedDateTime.now(), batchSize);
        for (NotificationDelivery delivery : batch) {
            try {
                processOne(delivery);
            } catch (Exception e) {
                log.error("Unexpected error processing delivery {}; will retry on next tick",
                        delivery.getUuid(), e);
                // Don't poison the batch — recordTransientFailure handles
                // the row's bookkeeping in its own transaction context.
            }
        }
        return batch.size();
    }

    /**
     * Process one delivery row. Public for testability; called via
     * {@link #drainBatch} which is the production entry point.
     *
     * <p>No {@code @Transactional} on this method: it's invoked via
     * a self-call from {@link #drainBatch}, which Spring AOP doesn't
     * intercept, so the annotation would be silently inert. Each
     * branch ends in a single {@code deliveryRepo.save(...)} call,
     * which the Spring Data repository wraps in its own short-lived
     * transaction — that's the right granularity here (we want each
     * delivery's status update to commit independently so a failure
     * downstream doesn't roll back earlier successes in the batch).
     */
    public void processOne(NotificationDelivery delivery) {
        Optional<Integration> oChannel = integrationRepo.findById(delivery.getChannelUuid());
        if (oChannel.isEmpty()) {
            markFailed(delivery, "Channel " + delivery.getChannelUuid() + " no longer exists");
            return;
        }
        Integration channel = oChannel.get();

        // Channel-level DISABLED short-circuit: don't burn HTTP attempts
        // against a disabled destination. The delivery stays PENDING but
        // re-checks next tick — operator can re-enable to drain backlog.
        // Cap with MAX_ATTEMPTS so a channel disabled-forever doesn't
        // build an unbounded queue.
        IntegrationData channelData = parseChannelData(channel);
        if (channelData == null) {
            // record_data is null or failed to deserialize. Fail with a
            // diagnostic that names the actual cause instead of letting
            // the flow fall through to "Channel type null has no
            // dispatcher", which misdirects ops toward a type/config
            // problem when the row is really empty or corrupt.
            markFailed(delivery,
                    "Channel " + delivery.getChannelUuid()
                            + " record data is missing or unparseable");
            return;
        }
        // Defence-in-depth org guard (S-5). The fan-out service's
        // channelEligibleForDelivery already prevents cross-tenant rows
        // from landing in the deliveries table; this is the second
        // checkpoint: even if a delivery row somehow exists with a
        // mismatched channel-org pair, refuse to dispatch. Doing the
        // check here covers any future delivery-creation path that
        // bypasses NotificationFanOutService entirely (manual sandbox
        // inserts, future API surfaces, integration-test backdoors).
        if (channelData.getOrg() != null
                && !channelData.getOrg().equals(delivery.getOrg())) {
            // Keep the structured detail in the log (operator forensic
            // value), but never put the channel's org uuid into the
            // delivery row's lastError — the row is visible to the
            // delivery-org operator, and there's no benefit to surfacing
            // a foreign-org identifier even if uuids aren't sensitive
            // on their own. Channel uuid is sufficient to disambiguate.
            log.warn("Channel-org guard rejected delivery {}: channel {} belongs to org {}, delivery is in org {}",
                    delivery.getUuid(), delivery.getChannelUuid(),
                    channelData.getOrg(), delivery.getOrg());
            markFailed(delivery,
                    "Channel " + delivery.getChannelUuid()
                            + " does not belong to this organization");
            return;
        }
        // DISABLED short-circuit. A folded-in channel integration with
        // isEnabled=false is the disabled state; treat null isEnabled
        // (legacy / unset) as enabled.
        if (Boolean.FALSE.equals(channelData.getIsEnabled())) {
            recordTransientFailure(delivery, "channel disabled", null);
            return;
        }
        IntegrationType channelType = channelData.getType();

        // Phase 4/9/10/11 supports all five channel destination types.
        // The gate is kept as a defense-in-depth check against a
        // non-destination integration type (DEPENDENCYTRACK / GITHUB / …)
        // landing in the deliveries table — it'll fall through to the
        // switch's default arm which throws otherwise.
        if (!IntegrationData.NOTIFICATION_DESTINATION_TYPES.contains(channelType)) {
            markFailed(delivery,
                    "Channel type " + channelType + " has no dispatcher (v1 supports SLACK, WEBHOOK, EMAIL, MSTEAMS, SENTINEL)");
            return;
        }

        Optional<NotificationOutboxEvent> oEvent = outboxRepo.findById(delivery.getOutboxEventUuid());
        if (oEvent.isEmpty()) {
            markFailed(delivery, "Outbox event " + delivery.getOutboxEventUuid() + " no longer exists");
            return;
        }
        NotificationOutboxEvent event = oEvent.get();

        // Edition-aware dispatch: look the type up in the registry built
        // from the ChannelDispatcher beans present on this classpath. On CE
        // the Email/Sentinel beans are absent, so a delivery targeting one of
        // those types finds no dispatcher and is marked failed with a clear,
        // edition-aware message rather than throwing. (The gate above already
        // rejected non-destination types; this also covers any future
        // destination type that ships without a dispatcher.)
        ChannelDispatcher dispatcher = dispatcherByType.get(channelType);
        if (dispatcher == null) {
            markFailed(delivery, "no dispatcher for " + channelType + " on this edition");
            return;
        }
        ChannelDispatchResult result = dispatcher.dispatch(event, channel);

        switch (result.outcome()) {
            case SUCCESS -> markSent(delivery);
            case RETRIABLE_FAILURE -> recordTransientFailure(
                    delivery, result.errorMessage(), result.retryAfterSeconds());
            case NON_RETRIABLE_FAILURE -> markFailed(delivery, result.errorMessage());
        }
    }

    private void markSent(NotificationDelivery delivery) {
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setStatus(NotificationDeliveryStatus.SENT);
        delivery.setSentAt(ZonedDateTime.now());
        delivery.setLastError(null);
        deliveryRepo.save(delivery);
    }

    private void markFailed(NotificationDelivery delivery, String errorMessage) {
        delivery.setAttemptCount(delivery.getAttemptCount() + 1);
        delivery.setStatus(NotificationDeliveryStatus.FAILED);
        delivery.setLastError(StringUtils.truncate(errorMessage, 1024));
        // Pin the invariant "sent_at IS NOT NULL iff status = SENT". When
        // an operator retries a FAILED row from the DLQ view (later phase),
        // a stale sent_at would otherwise mis-trigger the existsRecentDelivery
        // dedup probe (which predicates on sent_at IS NOT NULL AND status IN
        // (SENT, ACKED)).
        delivery.setSentAt(null);
        deliveryRepo.save(delivery);
        log.warn("Delivery {} marked FAILED on attempt {}: {}",
                delivery.getUuid(), delivery.getAttemptCount(), errorMessage);
    }

    /**
     * Retriable failure path. If we're at the max-attempts cap, this
     * becomes terminal; otherwise we schedule the next attempt via
     * {@code retryAfterSeconds} (when the upstream sent {@code Retry-After})
     * or the {@link BackoffPolicy} curve.
     */
    private void recordTransientFailure(NotificationDelivery delivery, String errorMessage, Integer retryAfterSeconds) {
        int newAttempt = delivery.getAttemptCount() + 1;
        delivery.setAttemptCount(newAttempt);
        delivery.setLastError(StringUtils.truncate(errorMessage, 1024));

        if (newAttempt >= MAX_ATTEMPTS) {
            delivery.setStatus(NotificationDeliveryStatus.FAILED);
            delivery.setSentAt(null); // preserve "sent_at NOT NULL iff status=SENT" invariant
            log.warn("Delivery {} hit MAX_ATTEMPTS ({}); marking FAILED. Last error: {}",
                    delivery.getUuid(), MAX_ATTEMPTS, errorMessage);
        } else {
            long backoffSec = retryAfterSeconds != null && retryAfterSeconds > 0
                    ? retryAfterSeconds
                    : BackoffPolicy.dtrackFetchSkipSeconds(newAttempt);
            delivery.setNextAttemptAt(ZonedDateTime.now().plusSeconds(backoffSec));
            // status stays PENDING
            log.debug("Delivery {} retriable failure attempt {}; next attempt in {}s: {}",
                    delivery.getUuid(), newAttempt, backoffSec, errorMessage);
        }
        deliveryRepo.save(delivery);
    }

    private IntegrationData parseChannelData(Integration channel) {
        if (channel.getRecordData() == null) return null;
        try {
            return IntegrationData.dataFromRecord(channel);
        } catch (Exception e) {
            log.warn("Failed to parse channel {} record_data: {}", channel.getUuid(), e.getMessage());
            return null;
        }
    }
}
