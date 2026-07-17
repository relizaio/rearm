/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.github.packageurl.MalformedPackageURLException;
import com.github.packageurl.PackageURL;

import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.BranchData;
import io.reliza.model.ComponentData;
import io.reliza.model.ReleaseData;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.notifications.AffectedComponent;
import io.reliza.model.dto.notifications.AffectedRelease;
import io.reliza.model.dto.notifications.ApprovalRequestedPayload;
import io.reliza.model.dto.notifications.ApprovalResolvedPayload;
import io.reliza.model.dto.notifications.NewVulnAffectsReleasesPayload;
import io.reliza.model.EmailDigestPolicy;
import io.reliza.model.NotificationDelivery;
import io.reliza.model.NotificationDeliveryOrigin;
import io.reliza.model.NotificationDeliveryStatus;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RouteConfig;
import io.reliza.model.dto.notifications.VulnerabilityRecordUpdatedPayload;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.repositories.NotificationSubscriptionRepository;
import io.reliza.service.BranchService;
import io.reliza.service.GetComponentService;
import io.reliza.service.SharedReleaseService;
import io.reliza.model.dto.notifications.EvaluationMode;
import lombok.extern.slf4j.Slf4j;

/**
 * Fans out one batch of {@link NotificationOutboxEvent}s into per-channel
 * {@link NotificationDelivery} rows. Invoked by {@code SchedulingService}
 * inside the {@code DRAIN_NOTIFICATION_OUTBOX} advisory lock so at most
 * one replica processes a batch at any moment (see §5.2 of the design
 * doc).
 *
 * <p>Per event:
 * <ol>
 *   <li>Find every {@code ACTIVE} subscription for {@code event.org}.</li>
 *   <li>Skip if {@code event.eventType} isn't in the subscription's
 *       {@code eventTypes} list.</li>
 *   <li>Evaluate the subscription's CEL filter against the event. Skip
 *       if it returns false. The filter exception path also skips —
 *       a broken expression doesn't block the rest of the batch.</li>
 *   <li>For each route in the subscription's route table, check the
 *       per-route severity gate. Skip routes that don't match.</li>
 *   <li>For each channel in a matching route, insert a delivery row
 *       with the dedup check from {@link NotificationDeliveryRepository}.</li>
 * </ol>
 *
 * <p>Event status flips to {@link NotificationOutboxStatus#FANNED_OUT}
 * after the loop. If fan-out throws for a single event, that event flips
 * to {@code FAILED} but the rest of the batch is unaffected.
 *
 * <p>The whole batch runs in one transaction — keeps the
 * "outbox-row-and-its-deliveries land together" invariant. Per-event
 * isolation would require self-injection through a Spring proxy; the
 * single-tx approach is simpler and the failure modes (malformed event
 * blocking peers) are mitigated by the broad try/catch around
 * {@code fanOutSingle}.
 */
@Service
@Slf4j
public class NotificationFanOutService {

    @Autowired
    private NotificationOutboxEventRepository outboxRepo;

    @Autowired
    private NotificationSubscriptionRepository subscriptionRepo;

    @Autowired
    private NotificationDeliveryRepository deliveryRepo;

    // Seam: the CEL evaluator implementation lives in saas/ (Pro-only).
    // On CE the bean is absent, so this is optional; filterMatches() falls
    // back to match-all (deliver unfiltered) when it is null.
    @Autowired(required = false)
    private NotificationCelEvaluator celEvaluator;

    // Phase 4a resolve-marks-read: the fan-out marks a resolved request's
    // targeted rows read on behalf of each recipient. Read-state writes
    // stay behind NotificationReadService so the idempotent-upsert +
    // audit-record contract lives in exactly one place.
    @Autowired
    private NotificationReadService readService;

    // Phase 13b — channel-group expansion. At fan-out time the route's
    // {@code channelGroups} list is resolved to its member channel UUIDs
    // via this service, then merged with the route's direct
    // {@code channels} list (dedup, first-seen order preserved). Routes
    // referencing a deleted group silently drop that group from the
    // resolution; see {@code NotificationChannelGroupService} for the
    // tolerate-and-log rationale.
    @Autowired
    private NotificationChannelGroupService channelGroupService;

    // Defence-in-depth org guard (S-5). The save-time invariant
    // (channel.org == subscription.org checked at upsert) already
    // ensures fan-out can't write a cross-tenant delivery via the
    // standard subscription path. This service lookup is the
    // second pair of eyes — see insertDelivery / insertChannelTestDelivery
    // for the actual guard. Costs one PK lookup per insertion.
    @Autowired
    private NotificationChannelService channelService;

    // Fan-out-time enrichment dependencies (Phase 2c). Producer-side
    // hook emits vuln-shaped events without affectedReleases /
    // affectedComponent — the artifact metric updates that connect a
    // vuln to a release happen later in the sync loop, so the producer
    // can't see them. Fan-out queries the JSONB index here, by which
    // point the metric save has committed.
    @Autowired
    private ArtifactRepository artifactRepo;

    @Autowired
    private SharedReleaseService sharedReleaseService;

    @Autowired
    private BranchService branchService;

    @Autowired
    private GetComponentService getComponentService;

    /**
     * Drain one batch of pending events. Returns the number of events
     * processed (matched or not — just how many we looked at). Caller
     * holds the advisory lock for the duration; this method is the
     * critical section.
     *
     * <p><b>Transaction semantics.</b> The whole batch runs in one
     * transaction. Two consequences worth knowing:
     * <ul>
     *   <li>If {@code fanOutSingle} throws mid-event after writing some
     *       deliveries, the catch flips the event to {@code FAILED} and
     *       calls {@link NotificationOutboxEventRepository#save}. On
     *       commit, both the partial delivery rows AND the FAILED-marked
     *       event flush together. Channel workers must therefore tolerate
     *       seeing delivery rows whose outbox event is FAILED — treat them
     *       as "best-effort partial fan-out" and process normally; the
     *       FAILED status is operator-facing diagnostics, not a directive
     *       to drop deliveries that already landed.
     *   <li>If the commit itself fails (e.g., OptimisticLockException from
     *       a concurrent write), the whole batch rolls back. The next tick
     *       replays from PENDING events. This is benign with a single
     *       writer (the advisory lock); document it here in case a future
     *       refactor introduces additional writers.
     * </ul>
     */
    @Transactional
    public int drainBatch(int batchSize) {
        List<NotificationOutboxEvent> batch = outboxRepo.findPendingBatch(batchSize);
        for (NotificationOutboxEvent event : batch) {
            try {
                fanOutSingle(event);
                event.setStatus(NotificationOutboxStatus.FANNED_OUT);
            } catch (Exception e) {
                log.error("Fan-out failed for event {} ({}); marking FAILED",
                        event.getUuid(), event.getEventType(), e);
                event.setStatus(NotificationOutboxStatus.FAILED);
            }
            outboxRepo.save(event);
        }
        return batch.size();
    }

    private void fanOutSingle(NotificationOutboxEvent event) {
        if (event.getOrg() == null || event.getEventType() == null) {
            log.warn("Skipping malformed outbox event {} (org={}, type={})",
                    event.getUuid(), event.getOrg(), event.getEventType());
            return;
        }

        // Channel-test bypass (Phase 2d). When channel_test_target is
        // set, this event was injected by SyntheticEventService#injectChannelTest
        // and the operator wants a single delivery to that exact channel —
        // not the org's subscription / CEL / severity matrix. Enrich the
        // payload first so the formatter still sees affectedReleases /
        // affectedComponent populated, then write a single delivery row.
        if (event.getChannelTestTarget() != null) {
            enrichVulnEventIfNeeded(event);
            insertChannelTestDelivery(event);
            return;
        }

        // Per-user targeted rows (Phase 4a). APPROVAL_REQUESTED carries a
        // produce-time recipient snapshot; each target gets a personal
        // inbox row independent of the org's subscription matrix — an org
        // with zero subscriptions still delivers approval requests to the
        // approvers' inboxes. Runs BEFORE the subs.isEmpty() early-return
        // for exactly that reason. Subscription-routed deliveries for the
        // same event still happen below.
        if (event.getEventType() == NotificationEventType.APPROVAL_REQUESTED) {
            insertTargetedDeliveries(event);
        }

        // Resolve-marks-read (Phase 4a): once a request fully resolves,
        // its targeted inbox rows are auto-read for every recipient —
        // "someone needs to act" stops being actionable the moment the
        // request closes. Also independent of the subscription matrix,
        // so it too runs before the subs.isEmpty() early-return.
        if (event.getEventType() == NotificationEventType.APPROVAL_RESOLVED) {
            markResolvedRequestsRead(event);
        }

        List<NotificationSubscription> subs = subscriptionRepo.findActiveByOrg(event.getOrg());
        if (subs.isEmpty()) return;

        // Ordering invariant: enrichment MUST run before extractEventSeverity
        // and the subscription loop. CEL filters in filterMatches read the
        // full payload via EventActivationMapBuilder; a filter referencing
        // event.affectedReleases needs the enriched list to be visible.
        // Severity itself is producer-set (untouched by enrichment), so
        // its read order is moot — but moving extractEventSeverity above
        // enrichment would silently break filter semantics.
        enrichVulnEventIfNeeded(event);

        NotificationSeverity eventSeverity = extractEventSeverity(event);

        for (NotificationSubscription sub : subs) {
            NotificationSubscriptionData data = parseSubscription(sub);
            if (data == null) continue;
            if (!matchesEventType(data, event)) continue;
            if (!filterMatches(data, sub, event)) continue;
            applyRoutes(data, sub, event, eventSeverity);
        }
    }

    private boolean matchesEventType(NotificationSubscriptionData data, NotificationOutboxEvent event) {
        return data.eventTypes() != null
                && data.eventTypes().contains(event.getEventType());
    }

    /**
     * Evaluate the subscription's CEL filter against the event. A blank
     * expression means "match everything." A broken expression logs and
     * returns false so the broken subscription doesn't fire on every
     * event in the org until the operator fixes it.
     */
    private boolean filterMatches(NotificationSubscriptionData data, NotificationSubscription sub,
            NotificationOutboxEvent event) {
        if (data.filter() == null) return true;
        String celExpression = data.filter().celExpression();
        if (StringUtils.isBlank(celExpression)) return true;
        // CE edition: the CEL evaluator impl (saas/) is not on the
        // classpath, so the seam autowires null. Deliver unfiltered
        // (match-all) rather than dropping the subscription — CE has no
        // way to evaluate the filter, and silently never firing would be
        // worse than firing without the filter applied.
        if (celEvaluator == null) {
            log.debug("No CEL evaluator on this edition; treating non-blank filter as match-all "
                    + "for subscription {} on event {}", sub.getUuid(), event.getUuid());
            return true;
        }
        EvaluationMode mode = data.filter().mode() != null ? data.filter().mode() : EvaluationMode.PRESET;
        try {
            return celEvaluator.evaluate(celExpression, mode, event);
        } catch (RelizaException e) {
            log.warn("CEL evaluation failed for subscription {} on event {} — skipping subscription. "
                    + "Expression: {} | Reason: {}",
                    sub.getUuid(), event.getUuid(), celExpression, e.getMessage());
            return false;
        }
    }

    private void applyRoutes(NotificationSubscriptionData data, NotificationSubscription sub,
            NotificationOutboxEvent event, NotificationSeverity eventSeverity) {
        if (data.routes() == null) return;
        for (RouteConfig route : data.routes()) {
            if (route == null) continue;
            if (!severityGateMatches(route, eventSeverity)) continue;
            if (!perspectiveGateMatches(route, event)) continue;
            // Phase 13b: expand channelGroups, then merge with direct
            // channels. Dedup is first-seen across the merged list, so a
            // channel referenced both directly and via a group still
            // produces exactly one delivery row.
            List<UUID> resolvedChannels = mergeRouteChannels(route);
            if (resolvedChannels.isEmpty()) {
                // No direct channels AND no resolvable groups — log + skip.
                // Distinct from "all channels resolved but every one was
                // dedup-suppressed" (that path still fires the insertDelivery
                // dedup check). A zero-channel route is a save-time validation
                // failure; reaching here at fan-out means a group was deleted
                // out from under the route mid-flight.
                log.debug("Skipping route on sub={} event={}: no channels and no resolvable groups",
                        sub.getUuid(), event.getUuid());
                continue;
            }
            for (UUID channelUuid : resolvedChannels) {
                insertDelivery(event, sub, data, channelUuid);
            }
        }
    }

    /**
     * Phase 13b merge helper. Returns the deduplicated, first-seen-order
     * union of {@code route.channels} and the channel UUIDs resolved
     * from {@code route.channelGroups}. Direct channels are visited
     * first so an operator who explicitly listed a channel sees it
     * preserved even if a group later also contains it. Null entries
     * on either side are silently skipped.
     */
    private List<UUID> mergeRouteChannels(RouteConfig route) {
        Set<UUID> seen = new HashSet<>();
        List<UUID> out = new ArrayList<>();
        if (route.channels() != null) {
            for (UUID ch : route.channels()) {
                if (ch != null && seen.add(ch)) out.add(ch);
            }
        }
        if (route.channelGroups() != null && !route.channelGroups().isEmpty()) {
            List<UUID> expanded = channelGroupService.resolveChannelUuids(route.channelGroups());
            for (UUID ch : expanded) {
                if (ch != null && seen.add(ch)) out.add(ch);
            }
        }
        return out;
    }

    private boolean severityGateMatches(RouteConfig route, NotificationSeverity eventSeverity) {
        if (route.whenSeverityAtLeast() == null) return true;
        if (eventSeverity == null) return false;
        return eventSeverity.atLeast(route.whenSeverityAtLeast());
    }

    /**
     * Perspective gate (Phase 12). When the route declares a non-empty
     * perspective list, the event must carry at least one affected
     * release whose component's perspectives intersect the route's list
     * — otherwise the delivery is gated out for that route.
     *
     * <p>Null/empty perspectives on the route = "any perspective" (no
     * filter), preserving the pre-Phase-12 default behavior. Events
     * outside the {@code NEW_VULN_AFFECTS_RELEASES /
     * VULNERABILITY_RECORD_UPDATED} family don't carry affectedReleases
     * and so a perspective-scoped route will gate them out — that's
     * intentional: a route filtered to "perspective Payments" shouldn't
     * fire on a VEX state change with no release context. Authors
     * mixing event types under one subscription should use multiple
     * routes.
     */
    private boolean perspectiveGateMatches(RouteConfig route, NotificationOutboxEvent event) {
        if (route.perspectives() == null || route.perspectives().isEmpty()) return true;
        Set<UUID> routePerspectives = new HashSet<>(route.perspectives());
        Object affected = event.getRecordData() != null
                ? event.getRecordData().get("affectedReleases") : null;
        if (!(affected instanceof List<?> list) || list.isEmpty()) return false;
        for (Object item : list) {
            // Two shapes land here in practice:
            //   - Map<String, Object> after Jackson round-trips the typed
            //     AffectedRelease record through the JSONB column (the
            //     post-enrichment + synthetic path).
            //   - AffectedRelease POJO when a caller writes typed objects
            //     straight into recordData (synthetic-event helpers and
            //     hand-built test payloads). Handle both so a future
            //     producer that skips the Jackson round-trip doesn't
            //     silently slip past the gate.
            Collection<?> itemPerspectives = extractPerspectivesFromItem(item, event.getUuid());
            if (itemPerspectives == null) continue;
            for (Object p : itemPerspectives) {
                UUID pu = coerceToUuid(p, event.getUuid());
                if (pu != null && routePerspectives.contains(pu)) return true;
            }
        }
        return false;
    }

    private static Collection<?> extractPerspectivesFromItem(Object item, UUID eventUuid) {
        if (item instanceof AffectedRelease ar) {
            return ar.perspectives();
        }
        if (item instanceof Map<?, ?> map) {
            Object raw = map.get("perspectives");
            if (raw instanceof Collection<?> coll) return coll;
        }
        return null;
    }

    private static UUID coerceToUuid(Object raw, UUID eventUuid) {
        if (raw == null) return null;
        if (raw instanceof UUID u) return u;
        if (raw instanceof String s) {
            try { return UUID.fromString(s); } catch (IllegalArgumentException e) {
                log.warn("Event {} carried a non-UUID perspective entry: {} — skipping",
                        eventUuid, StringUtils.truncate(s, 64));
                return null;
            }
        }
        log.warn("Event {} carried a perspective entry of unexpected type {} — skipping",
                eventUuid, raw.getClass().getName());
        return null;
    }

    /**
     * Insert a single delivery row, subject to the dedup window.
     * Subscription's dedupWindowMinutes — default 24h — bounds the
     * suppression window. dedupKey comes from the event row, propagated
     * to the delivery so the suppression query has a usable index hit.
     *
     * <p>Synthetic events (origin = SYNTHETIC) bypass the dedup check —
     * per §7.11 of the design doc, "synthetic events are excluded from
     * dedup; we always want to see the rendered output." The injection
     * path also generates a fresh dedup_key per call as belt-and-
     * suspenders, but skipping the dedup probe here handles the
     * hand-crafted-synthetic-event case too.
     */
    /**
     * Channel-test bypass writer. Bypasses subscription matching, CEL,
     * severity gate, AND dedup — every "Test channel" press by an
     * operator must produce a visible delivery so the customer gets
     * feedback. Origin is preserved (SYNTHETIC) so downstream history
     * filters / analytics can keep the test rows distinct from real
     * traffic.
     *
     * <p>{@code subscriptionUuid} is left null: channel-test rows have
     * no originating subscription row, and a sentinel value
     * (e.g. self-linking to the event uuid) would silently match
     * subscription-aware queries downstream. The V40 column was made
     * nullable for exactly this case; the channel_test_target column
     * on the outbox event is the authoritative "this is a test"
     * marker for history views.
     */
    private void insertChannelTestDelivery(NotificationOutboxEvent event) {
        UUID channelUuid = event.getChannelTestTarget();
        if (!channelEligibleForDelivery(channelUuid, event.getOrg())) {
            // Only a confirmed cross-org channel reaches here (missing /
            // unparseable channels now flow through to the worker for a
            // FAILED History row). The save-time invariant on channel-test
            // injection (operator must have org-admin perm on event.org
            // and the chosen channel) already guarantees same-org; this
            // refusal catches any future path that skips that check.
            log.warn("Refusing channel-test delivery: channel {} not in event org {}",
                    channelUuid, event.getOrg());
            return;
        }
        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setOrg(event.getOrg());
        delivery.setOutboxEventUuid(event.getUuid());
        delivery.setSubscriptionUuid(null);
        delivery.setChannelUuid(channelUuid);
        delivery.setStatus(NotificationDeliveryStatus.PENDING);
        delivery.setDedupKey(event.getDedupKey());
        delivery.setOrigin(event.getOrigin() != null ? event.getOrigin() : NotificationDeliveryOrigin.SYNTHETIC);
        delivery.setNextAttemptAt(ZonedDateTime.now());
        delivery.setRecordData(new HashMap<>());
        deliveryRepo.save(delivery);
        log.info("Channel-test delivery queued for outbox event {} -> channel {}",
                event.getUuid(), channelUuid);
    }

    /**
     * Phase 4a targeted writer. One row per snapshotted recipient in the
     * APPROVAL_REQUESTED payload's {@code targetUsers}. Rows are born
     * SENT with {@code sentAt} stamped: there is no channel to transmit
     * to, so the channel worker (which only polls PENDING) never touches
     * them — they exist purely as personal inbox entries, visible via
     * the {@code target_user} arm of the inbox visibility predicate.
     *
     * <p>No dedup probe: the event's dedup key embeds the request uuid
     * (unique per request) and fan-out runs at most once per event under
     * the advisory lock, so a duplicate row would require a replayed
     * event uuid — which the PENDING→FANNED_OUT flip prevents. The one
     * exception is a manual FAILED→PENDING requeue of an APPROVAL_REQUESTED
     * outbox row: that re-runs this writer and duplicates the targeted rows,
     * so delete the rows for the event's dedup key first (or don't requeue
     * REQUESTED events).
     *
     * <p>{@code dedupKey} is propagated so the APPROVAL_RESOLVED flow
     * can find these rows for resolve-marks-read semantics.
     */
    private void insertTargetedDeliveries(NotificationOutboxEvent event) {
        ApprovalRequestedPayload payload;
        try {
            payload = Utils.OM.convertValue(event.getRecordData(), ApprovalRequestedPayload.class);
        } catch (Exception e) {
            log.warn("Unparseable APPROVAL_REQUESTED payload on event {}; skipping targeted deliveries: {}",
                    event.getUuid(), e.getMessage());
            return;
        }
        if (payload == null || payload.targetUsers() == null || payload.targetUsers().isEmpty()) {
            return;
        }
        ZonedDateTime now = ZonedDateTime.now();
        Set<UUID> seen = new HashSet<>();
        for (UUID targetUser : payload.targetUsers()) {
            if (targetUser == null || !seen.add(targetUser)) continue;
            NotificationDelivery delivery = new NotificationDelivery();
            delivery.setOrg(event.getOrg());
            delivery.setOutboxEventUuid(event.getUuid());
            delivery.setSubscriptionUuid(null);
            delivery.setChannelUuid(null);
            delivery.setTargetUser(targetUser);
            delivery.setStatus(NotificationDeliveryStatus.SENT);
            delivery.setSentAt(now);
            delivery.setDedupKey(event.getDedupKey());
            delivery.setOrigin(event.getOrigin() != null ? event.getOrigin() : NotificationDeliveryOrigin.REAL);
            delivery.setNextAttemptAt(now);
            delivery.setRecordData(new HashMap<>());
            deliveryRepo.save(delivery);
        }
        log.info("Wrote {} targeted approval-request deliveries for outbox event {}",
                seen.size(), event.getUuid());
    }

    /**
     * Phase 4a resolve-marks-read. For each request the APPROVAL_RESOLVED
     * payload reports as fully resolved, rebuild that request's
     * APPROVAL_REQUESTED dedup key, find the targeted rows it produced,
     * and mark each read for its own target user. Idempotent via
     * {@link NotificationReadService#markRead}'s upsert contract — a
     * recipient who already read the row keeps their original read_at.
     *
     * <p>Outbox FIFO (occurred_at order in {@code findPendingBatch})
     * guarantees the REQUESTED event fanned out — and wrote its targeted
     * rows — before its RESOLVED event is processed, even within one batch:
     * the request must exist before any vote can resolve it.
     *
     * <p>Failure mode: best-effort per request. A mark-read failure logs
     * and continues — stale-unread is a cosmetic defect, not worth
     * failing the event's whole fan-out over. To make "continues" actually
     * hold, the {@code markRead} calls are deferred to an afterCommit
     * synchronization rather than executed inside the batch transaction:
     * a throw inside the batch tx would mark the shared transaction
     * rollback-only before our catch ran — every event in the batch would
     * replay as PENDING and wedge the queue on the same failure forever.
     * (A plain SQL error would similarly abort the underlying PG
     * transaction.)
     *
     * <p>{@code markRead} MUST be {@code REQUIRES_NEW} for this to work:
     * inside afterCommit the completed transaction context is still bound,
     * so a {@code REQUIRED} call joins it and its writes are silently
     * discarded — the mark-read log fires but no row commits (observed on
     * the sandbox during the Phase 4b smoke). With {@code REQUIRES_NEW},
     * each markRead runs in its own fresh transaction and the targeted
     * rows it references are already durable — including the same-batch
     * case, since the REQUESTED rows commit with the batch. If the batch
     * rolls back for an unrelated reason, the synchronization never fires
     * and the replayed event re-registers it — no marks lost.
     */
    private void markResolvedRequestsRead(NotificationOutboxEvent event) {
        ApprovalResolvedPayload payload;
        try {
            payload = Utils.OM.convertValue(event.getRecordData(), ApprovalResolvedPayload.class);
        } catch (Exception e) {
            log.warn("Unparseable APPROVAL_RESOLVED payload on event {}; skipping resolve-marks-read: {}",
                    event.getUuid(), e.getMessage());
            return;
        }
        if (payload == null || payload.resolvedRequestUuids() == null
                || payload.resolvedRequestUuids().isEmpty()
                || payload.release() == null || payload.release().releaseUuid() == null) {
            return;
        }
        List<NotificationDelivery> toMark = new ArrayList<>();
        for (UUID requestUuid : payload.resolvedRequestUuids()) {
            if (requestUuid == null) continue;
            String requestedKey = NotificationDedupKeys.approvalRequested(payload.release().releaseUuid(), requestUuid);
            List<NotificationDelivery> targeted = deliveryRepo.findTargetedByDedupKey(event.getOrg(), requestedKey);
            for (NotificationDelivery d : targeted) {
                if (d.getTargetUser() == null) continue;
                toMark.add(d);
            }
        }
        if (toMark.isEmpty()) return;
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    markTargetedDeliveriesRead(toMark);
                }
            });
        } else {
            // No ambient transaction (direct unit-test invocation) —
            // run inline; there is no shared tx to poison.
            markTargetedDeliveriesRead(toMark);
        }
    }

    private void markTargetedDeliveriesRead(List<NotificationDelivery> targeted) {
        for (NotificationDelivery d : targeted) {
            try {
                readService.markRead(d.getTargetUser(), d.getUuid(), WhoUpdated.getAutoWhoUpdated());
            } catch (Exception e) {
                log.warn("Resolve-marks-read failed for delivery {} user {}: {}",
                        d.getUuid(), d.getTargetUser(), e.getMessage());
            }
        }
    }

    private void insertDelivery(NotificationOutboxEvent event, NotificationSubscription sub,
            NotificationSubscriptionData data, UUID channelUuid) {
        if (!channelEligibleForDelivery(channelUuid, event.getOrg())) {
            // Defence-in-depth (S-5): only a confirmed cross-org channel
            // reaches here. The subscription-upsert validator already
            // enforces channel.org == subscription.org, and
            // findActiveByOrg(event.org) only returns subscriptions in
            // event.org, so in the current code path this is never
            // expected to trip. It exists for a future delivery-creation
            // path added without the save-time invariant — refusing the
            // row here closes the loop. (Missing/unparseable channels are
            // NOT refused here — they flow through to the worker for a
            // FAILED History row.)
            log.warn("Refusing fan-out delivery: channel {} not in event org {} (sub={})",
                    channelUuid, event.getOrg(), sub.getUuid());
            return;
        }

        String dedupKey = event.getDedupKey();
        boolean dedupApplies = event.getOrigin() != NotificationDeliveryOrigin.SYNTHETIC;
        if (dedupApplies && StringUtils.isNotBlank(dedupKey)) {
            ZonedDateTime since = ZonedDateTime.now()
                    .minusMinutes(data.effectiveDedupWindowMinutes());
            if (deliveryRepo.existsRecentDelivery(sub.getUuid(), channelUuid, dedupKey, since)) {
                log.debug("Dedup-suppressing delivery for sub={} channel={} key={}",
                        sub.getUuid(), channelUuid, dedupKey);
                return;
            }
        }

        NotificationDelivery delivery = new NotificationDelivery();
        delivery.setOrg(event.getOrg());
        delivery.setOutboxEventUuid(event.getUuid());
        delivery.setSubscriptionUuid(sub.getUuid());
        delivery.setChannelUuid(channelUuid);
        delivery.setDedupKey(dedupKey);
        // Propagate the outbox event's origin to the delivery so synthetic
        // events stay tagged through fan-out (channel test, Quick Start
        // verify, integration tests all rely on this).
        delivery.setOrigin(event.getOrigin() != null ? event.getOrigin() : NotificationDeliveryOrigin.REAL);
        // Email digest (Phase 5): a non-null deadline parks the row as
        // BATCHED with next_attempt_at = the digest window's expiry —
        // invisible to the PENDING-only worker, flushed as one digest
        // email by EmailDigestFlushService. Null = immediate as before.
        ZonedDateTime digestDeadline = computeDigestDeadline(event, channelUuid);
        if (digestDeadline != null) {
            delivery.setStatus(NotificationDeliveryStatus.BATCHED);
            delivery.setNextAttemptAt(digestDeadline);
        } else {
            delivery.setStatus(NotificationDeliveryStatus.PENDING);
            delivery.setNextAttemptAt(ZonedDateTime.now());
        }
        delivery.setRecordData(new HashMap<>());
        deliveryRepo.save(delivery);
    }

    /** Event-type names excluded from the rolling cap (see {@link NotificationEventType#isActionable()}). */
    private static final List<String> ACTIONABLE_TYPE_NAMES =
            Arrays.stream(NotificationEventType.values())
                    .filter(NotificationEventType::isActionable)
                    .map(Enum::name)
                    .toList();

    /**
     * Rolling-cap digest decision (Phase 5, BD-7) for one prospective
     * delivery row. Returns the digest-window deadline the row should be
     * parked under, or null for immediate (PENDING) delivery.
     *
     * <p>Only REAL, non-actionable events on enabled-for-digest EMAIL
     * channels batch — synthetic traffic (channel tests, Quick Start
     * verifies) and actionable approval events always go out
     * immediately. Decision order:
     * <ol>
     *   <li>Channel has an open batch (any BATCHED row) → join it, same
     *       deadline.</li>
     *   <li>Last counted send (SENT/ACKED, REAL, non-actionable) within
     *       the interval → open a new batch expiring at
     *       {@code lastSend + interval}.</li>
     *   <li>Otherwise → immediate; the send (once the worker transmits)
     *       anchors the next window.</li>
     * </ol>
     *
     * <p>Known benign race: an event arriving after an immediate row was
     * inserted but before the worker transmits it sees neither an open
     * batch nor a counted send and also goes immediate. The window is
     * the worker's drain cadence (seconds); the cap is a courtesy
     * throttle, not a hard guarantee.
     */
    private ZonedDateTime computeDigestDeadline(NotificationOutboxEvent event, UUID channelUuid) {
        if (event.getOrigin() == NotificationDeliveryOrigin.SYNTHETIC) return null;
        if (event.getEventType() != null && event.getEventType().isActionable()) return null;
        Optional<Integration> oChannel = channelService.getChannel(channelUuid);
        if (oChannel.isEmpty()) return null;
        IntegrationData data;
        try {
            data = IntegrationData.dataFromRecord(oChannel.get());
        } catch (RuntimeException e) {
            return null; // unparseable channel → immediate; worker surfaces the parse failure
        }
        if (data == null || data.getType() != IntegrationData.IntegrationType.EMAIL) return null;
        EmailDigestPolicy policy = EmailDigestPolicy.fromParameters(data.getParameters());
        if (policy.mode() != EmailDigestPolicy.EmailDigestMode.ROLLING) return null;
        List<NotificationDelivery> openBatch = deliveryRepo.findOpenBatchHead(channelUuid);
        if (!openBatch.isEmpty()) {
            return openBatch.get(0).getNextAttemptAt();
        }
        List<NotificationDelivery> lastSend = deliveryRepo.findLastCountedEmailSend(
                channelUuid, ACTIONABLE_TYPE_NAMES);
        if (!lastSend.isEmpty() && lastSend.get(0).getSentAt() != null) {
            ZonedDateTime deadline = lastSend.get(0).getSentAt().plus(policy.interval());
            if (deadline.isAfter(ZonedDateTime.now())) {
                return deadline;
            }
        }
        return null;
    }

    /**
     * S-5 org-isolation guard, reframed as "may a delivery row be written
     * for this channel?". The ONLY case that refuses the row is a channel
     * that resolves to a DIFFERENT org than the event — a cross-tenant
     * reference that must never produce a delivery (no row, no History
     * leak).
     *
     * <p>Every other "can't deliver" case is allowed THROUGH to the worker
     * on purpose, so the failure shows up in Delivery History instead of
     * vanishing:
     * <ul>
     *   <li>Missing/deleted channel — the worker marks the row FAILED
     *       "Channel &lt;uuid&gt; no longer exists". This restores the
     *       pre-S-5 forensic row the org-guard had been swallowing
     *       silently (the regression behind the #194 follow-up): a deleted
     *       channel used to surface in History, then started disappearing.</li>
     *   <li>Unparseable record_data or an unset channel org — the worker's
     *       parse path marks the row FAILED with a record-data diagnostic.</li>
     * </ul>
     *
     * <p>Costs one PK lookup per delivery row, but {@code drainBatch} is
     * {@code @Transactional} and {@code getChannel} resolves by primary
     * key, so repeated lookups of the same channel within a batch are
     * served from the Hibernate first-level cache rather than re-hitting
     * the DB — no explicit per-fan-out memo needed.
     */
    private boolean channelEligibleForDelivery(UUID channelUuid, UUID eventOrg) {
        if (channelUuid == null || eventOrg == null) return false;
        Optional<Integration> oChannel = channelService.getChannel(channelUuid);
        if (oChannel.isEmpty()) {
            // Deleted channel: DON'T suppress — let the worker write the
            // "no longer exists" FAILED row so the operator sees it in
            // Delivery History.
            log.warn("Channel {} not found at fan-out; deferring to worker for a FAILED History row", channelUuid);
            return true;
        }
        IntegrationData data;
        try {
            data = IntegrationData.dataFromRecord(oChannel.get());
        } catch (RuntimeException e) {
            // Unparseable record_data — worker's parse path surfaces it.
            log.warn("Channel {} record_data unparseable at fan-out; deferring to worker", channelUuid);
            return true;
        }
        // Confirmed cross-org is the one hard refusal (security, no row).
        if (data != null && data.getOrg() != null && !data.getOrg().equals(eventOrg)) {
            return false;
        }
        // Disabled channel: suppress the delivery entirely (no History row). A
        // disabled channel is off -- creating a row per event only to fail it
        // at dispatch is the "flood" an auto-disabled misconfigured channel
        // would otherwise produce. Manual kill-switch disables benefit too.
        if (data != null && Boolean.FALSE.equals(data.getIsEnabled())) {
            return false;
        }
        return true;
    }

    /**
     * Extract event-level severity from the typed payload. Used for
     * per-route severity gating in {@link #severityGateMatches}.
     * VEX_STATE_CHANGED has no canonical severity, so it returns null
     * and routes with a severity gate skip it.
     */
    private NotificationSeverity extractEventSeverity(NotificationOutboxEvent event) {
        if (event.getRecordData() == null) return null;
        try {
            switch (event.getEventType()) {
                case NEW_VULN_AFFECTS_RELEASES -> {
                    NewVulnAffectsReleasesPayload p = Utils.OM.convertValue(
                            event.getRecordData(), NewVulnAffectsReleasesPayload.class);
                    return p != null ? p.severity() : null;
                }
                case VULNERABILITY_RECORD_UPDATED -> {
                    VulnerabilityRecordUpdatedPayload p = Utils.OM.convertValue(
                            event.getRecordData(), VulnerabilityRecordUpdatedPayload.class);
                    return p != null ? p.newSeverity() : null;
                }
                case VEX_STATE_CHANGED -> {
                    return null;
                }
                // Release events carry no canonical severity; routes with a
                // severity gate skip them (same as VEX).
                case RELEASE_CREATED, RELEASE_LIFECYCLE_CHANGED, RELEASE_BOM_DIFF -> {
                    return null;
                }
                // Approval events likewise carry no severity.
                case APPROVAL_REQUESTED, APPROVAL_RESOLVED -> {
                    return null;
                }
            }
        } catch (Exception e) {
            log.warn("Full-payload severity extraction failed for event {}; "
                    + "falling back to raw map: {}", event.getUuid(), e.getMessage());
            // Defensive fallback: a partial deserialization failure (e.g.
            // a corrupted affectedReleases entry) shouldn't lose severity-
            // based routing entirely. Read the severity field straight
            // from the JSONB map.
            NotificationSeverity raw = readSeverityFromRawMap(event);
            if (raw != null) return raw;
        }
        return null;
    }

    private static NotificationSeverity readSeverityFromRawMap(NotificationOutboxEvent event) {
        Map<String, Object> recordData = event.getRecordData();
        if (recordData == null) return null;
        Object rawSev = event.getEventType() == NotificationEventType.VULNERABILITY_RECORD_UPDATED
                ? recordData.get("newSeverity")
                : recordData.get("severity");
        if (rawSev instanceof String s) {
            try { return NotificationSeverity.valueOf(s); }
            catch (IllegalArgumentException ignored) { /* fall through */ }
        }
        return null;
    }

    /**
     * Tolerant subscription deserialization. A subscription row whose
     * record_data we can't parse is skipped (not fatal for the batch) —
     * matches the same forward-compat principle as the event payload
     * deserialization on {@link EventActivationMapBuilder}.
     *
     * <p>Per-row size is bounded at save time: the Phase 3 CRUD layer
     * ({@code NotificationSubscriptionService.upsertSubscription}) runs
     * every upsert through
     * {@code NotificationChannelService.assertRecordDataSize} (256 KB
     * cap), so a buggy customer client can't write a multi-MB JSONB
     * that this per-event deserialization would amplify across the
     * batch. ({@code setSubscriptionStatus} re-saves record_data without
     * re-asserting, but it only flips the status enum on an
     * already-capped record, so the bound holds.)
     */
    private NotificationSubscriptionData parseSubscription(NotificationSubscription sub) {
        Map<String, Object> recordData = sub.getRecordData();
        if (recordData == null) return null;
        try {
            return Utils.OM.convertValue(recordData, NotificationSubscriptionData.class);
        } catch (Exception e) {
            log.warn("Failed to parse subscription {} record_data: {}", sub.getUuid(), e.getMessage());
            return null;
        }
    }

    /**
     * Phase 2c enrichment: producer-side hooks emit vuln-shaped events
     * without {@code affectedReleases} (and {@code affectedComponent} for
     * NEW_VULN events) because the artifact-metric updates that connect
     * a CVE to a release happen later in the DT-sync loop. At fan-out
     * time those updates have committed, so we resolve them via the
     * {@code metrics->vulnerabilityDetails} JSONB index and update the
     * event's recordData in place. CEL filters and channel formatters
     * downstream see the enriched payload.
     *
     * <p>Failure mode: an enrichment exception logs and proceeds with an
     * empty release set — the event still ships, customers just see
     * "affects 0 releases" rather than nothing at all. Better operator
     * signal than swallowing the event entirely.
     *
     * <p>Mutating {@code event.getRecordData()} in place is the persistence
     * mechanism: Hibernate's dirty-tracking on the {@code @Type(JsonBinaryType)}
     * column re-serializes the JSONB on the {@code outboxRepo.save(event)}
     * call in {@link #drainBatch}. Do NOT refactor to a defensive copy or
     * the enrichment will vanish on commit.
     *
     * <p><b>v1 perf note (known limitation):</b> the loop in
     * {@link #resolveAffectedReleases} calls
     * {@link SharedReleaseService#gatherReleasesForArtifact} per artifact,
     * which is N+1 over the artifacts that carry the vuln. A widely-
     * deployed CVE (log4shell class) attached to hundreds of artifacts
     * in one org will fan out hundreds of DB round-trips inside the
     * advisory-lock-held tx. The S-3 component fill adds a second org-wide
     * {@code vulnerabilityDetails} scan per NEW_VULN event on top of that.
     * Acceptable in v1 because vuln events are sparse and the advisory
     * lock lets us finish the batch before the next tick. A batched query
     * that joins {@code metrics->vulnerabilityDetails} straight to
     * releases (and carries the purls along) is the follow-up if customer
     * scale starts showing tail-latency pain.
     */
    private void enrichVulnEventIfNeeded(NotificationOutboxEvent event) {
        NotificationEventType type = event.getEventType();
        if (type != NotificationEventType.NEW_VULN_AFFECTS_RELEASES
                && type != NotificationEventType.VULNERABILITY_RECORD_UPDATED) {
            return;
        }
        Map<String, Object> recordData = event.getRecordData();
        if (recordData == null) return;
        Object idRaw = recordData.get("vulnPrimaryId");
        if (!(idRaw instanceof String vulnPrimaryId) || StringUtils.isBlank(vulnPrimaryId)) {
            return;
        }
        // If the producer already populated affectedReleases (synthetic
        // events do; future producers might), don't clobber it.
        Object existing = recordData.get("affectedReleases");
        if (!(existing instanceof List<?> existingList && !existingList.isEmpty())) {
            try {
                List<AffectedRelease> resolved = resolveAffectedReleases(event.getOrg(), vulnPrimaryId);
                recordData.put("affectedReleases", Utils.OM.convertValue(resolved, List.class));
            } catch (RuntimeException e) {
                log.warn("Failed to enrich vuln event {} ({} for {}): {}",
                        event.getUuid(), type, vulnPrimaryId, e.getMessage());
                recordData.putIfAbsent("affectedReleases", Collections.emptyList());
            }
        }

        // S-3: only the NEW_VULN payload carries affectedComponent; same
        // don't-clobber rule for synthetic/producer-populated events.
        if (type == NotificationEventType.NEW_VULN_AFFECTS_RELEASES
                && recordData.get("affectedComponent") == null) {
            try {
                AffectedComponent ac = resolveAffectedComponent(event.getOrg(), vulnPrimaryId);
                if (ac != null) {
                    recordData.put("affectedComponent", Utils.OM.convertValue(ac, Map.class));
                }
            } catch (RuntimeException e) {
                log.warn("Failed to enrich vuln event {} with affectedComponent for {}: {}",
                        event.getUuid(), vulnPrimaryId, e.getMessage());
            }
        }
    }

    /**
     * Resolve the package a vuln landed against from the same
     * {@code metrics->vulnerabilityDetails} rows that drive
     * {@link #resolveAffectedReleases}. One CVE can hit multiple packages
     * in an org; {@code AffectedComponent} is single-valued, so we take
     * the lexicographically-first purl (deterministic across retries) and
     * log the collapse. Name/version are parsed from the purl; a purl that
     * doesn't parse ships with the raw purl doubling as the name — most
     * channel formatters (Slack/Teams/email/Sentinel) render only when
     * {@code name} is non-blank, so a null name would hide the component
     * everywhere except the inbox.
     */
    private AffectedComponent resolveAffectedComponent(UUID orgUuid, String vulnPrimaryId) {
        List<String> purls = artifactRepo.findVulnPurlsForVulnId(orgUuid.toString(), vulnPrimaryId);
        if (purls == null || purls.isEmpty()) return null;
        String purl = purls.get(0);
        if (purls.size() > 1) {
            log.debug("Vuln {} affects {} distinct packages in org {}; affectedComponent uses {}",
                    vulnPrimaryId, purls.size(), orgUuid, purl);
        }
        try {
            PackageURL parsed = new PackageURL(purl);
            return new AffectedComponent(purl, parsed.getName(), parsed.getVersion());
        } catch (MalformedPackageURLException e) {
            return new AffectedComponent(purl, purl, null);
        }
    }

    /**
     * Build the {@link AffectedRelease} list for a vuln by walking every
     * artifact in the org whose {@code metrics.vulnerabilityDetails}
     * array carries the id, then collecting all releases that point at
     * those artifacts. Branch + component names are looked up via the
     * usual services and memoized inside this call so a CVE in 50
     * artifacts of the same component doesn't issue 50 component
     * lookups.
     */
    private List<AffectedRelease> resolveAffectedReleases(UUID orgUuid, String vulnPrimaryId) {
        List<UUID> artifactUuids = artifactRepo.findArtifactsWithVulnId(
                orgUuid.toString(), vulnPrimaryId);
        if (artifactUuids == null || artifactUuids.isEmpty()) {
            return Collections.emptyList();
        }
        Map<UUID, String> branchNames = new HashMap<>();
        Map<UUID, Optional<ComponentData>> componentCache = new HashMap<>();
        // Per-component perspective copy cache: a single CVE in 50 artifacts
        // of the same component yields 50 AffectedRelease entries, all sharing
        // the same perspective set — copy once per component, share the
        // immutable Set across releases.
        Map<UUID, Set<UUID>> perspectiveCache = new HashMap<>();
        Set<UUID> seenReleaseUuids = new HashSet<>();
        List<AffectedRelease> out = new ArrayList<>();
        for (UUID artifactUuid : artifactUuids) {
            // gatherReleasesForArtifact resolves both directly-attached releases
            // and product releases that pull this artifact transitively through
            // a deliverable — matches the "affects N releases" UX intent.
            List<ReleaseData> releases = sharedReleaseService.gatherReleasesForArtifact(artifactUuid, orgUuid);
            if (releases == null) continue;
            for (ReleaseData rd : releases) {
                if (rd == null || !seenReleaseUuids.add(rd.getUuid())) continue;
                String branchName = resolveBranchName(rd.getBranch(), branchNames);
                ComponentData component = resolveComponent(rd.getComponent(), componentCache);
                String componentName = component != null ? component.getName() : null;
                Set<UUID> perspectives = resolveComponentPerspectives(
                        rd.getComponent(), component, perspectiveCache);
                out.add(new AffectedRelease(
                        rd.getUuid(),
                        rd.getComponent(),
                        componentName,
                        rd.getVersion(),
                        branchName,
                        rd.getLifecycle(),
                        // deployedEnvs not on ReleaseData — left empty for v1;
                        // a future enhancement can join through environments.
                        Collections.emptyList(),
                        perspectives));
            }
        }
        return out;
    }

    private Set<UUID> resolveComponentPerspectives(UUID componentUuid, ComponentData component,
            Map<UUID, Set<UUID>> cache) {
        if (componentUuid == null) return Set.of();
        return cache.computeIfAbsent(componentUuid, k ->
                (component != null && component.getPerspectives() != null)
                        ? Set.copyOf(component.getPerspectives())
                        : Set.of());
    }

    private String resolveBranchName(UUID branchUuid, Map<UUID, String> cache) {
        if (branchUuid == null) return null;
        return cache.computeIfAbsent(branchUuid, uuid -> {
            Optional<BranchData> bd = branchService.getBranchData(uuid);
            return bd.map(BranchData::getName).orElse(null);
        });
    }

    /**
     * Memoized component-data lookup. Replaces the prior name-only cache
     * so the perspective walk (Phase 12) reads from the same cached row
     * — a CVE in 50 artifacts of the same component still issues exactly
     * one component lookup.
     *
     * <p>Wraps the result in {@link Optional} so {@code computeIfAbsent}
     * disambiguates "looked up, deleted component → empty" from "not yet
     * looked up → key absent". Returns the underlying {@link ComponentData}
     * or null. Matches the {@code resolveBranchName} idiom on this class.
     */
    private ComponentData resolveComponent(UUID componentUuid, Map<UUID, Optional<ComponentData>> cache) {
        if (componentUuid == null) return null;
        return cache.computeIfAbsent(componentUuid,
                uuid -> getComponentService.getComponentData(uuid)).orElse(null);
    }
}
