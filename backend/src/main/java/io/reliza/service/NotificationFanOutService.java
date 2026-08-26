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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
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
import io.reliza.model.dto.notifications.InstanceDeploymentChangedPayload;
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
import io.reliza.util.BackoffPolicy;
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
 * <p><b>Each event runs in its own transaction</b>, entered through a Spring
 * proxy via a self-reference. The batch used to share one transaction, on the
 * reasoning that per-event isolation "would require self-injection through a
 * Spring proxy" and that the broad try/catch around {@code fanOutSingle}
 * mitigated a bad event blocking its peers. That reasoning was wrong: a catch
 * cannot rescue a transaction Spring has already marked rollback-only, so one
 * poison event wedged the entire queue in a 5-second retry loop across all
 * orgs. See {@link #drainBatch} for the full mechanism.
 */
@Service
@Slf4j
public class NotificationFanOutService {

    /**
     * How many times a vuln event may be deferred waiting for its affected
     * releases to become resolvable before a terminal decision is taken.
     */
    static final int MAX_ENRICHMENT_ATTEMPTS = 3;

    /**
     * Hard ceiling on deferrals when the empty result cannot be PROVEN
     * trustworthy (the org's artifact metrics have not been refreshed since the
     * event was emitted). Roughly 15 minutes at the capped backoff, after which
     * the event is delivered rather than suppressed -- see the RESOLVED_EMPTY
     * branch in {@link #fanOutSingle}. Exists so a permanently-stalled metrics
     * pipeline cannot strand events in PENDING forever.
     */
    static final int MAX_ENRICHMENT_ATTEMPTS_UNPROVEN = 10;

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

    @Autowired
    private TeamService teamService;

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

    // T4a -- owner-aware route targeting. Ownership policy (which states are
    // routable, USER vs TEAM) lives in the ownership service; fan-out only
    // decides WHEN to ask.
    @Autowired
    private ComponentOwnershipService componentOwnershipService;

    // Self-injection so drainBatch can call fanOutOneEvent through Spring's
    // proxy and pick up its REQUIRES_NEW propagation. A direct this.* call
    // bypasses AOP entirely. @Lazy per the house pattern for self-injection.
    @Autowired
    @Lazy
    private NotificationFanOutService self;

    /**
     * Drain one batch of pending events. Returns the number of events
     * processed (matched or not — just how many we looked at). Caller
     * holds the advisory lock for the duration; this method is the
     * critical section.
     *
     * <p><b>Transaction semantics.</b> This method is deliberately NOT
     * {@code @Transactional}: each event gets its own transaction via
     * {@link #fanOutOneEvent}, and a failure is recorded in a second,
     * independent one via {@link #markEventFailed}.
     *
     * <p>It used to wrap the whole batch in one transaction, which wedged
     * the queue: when {@code fanOutSingle} threw through a nested
     * {@code @Transactional} call (or hit a raw PG error), Spring marked the
     * SHARED transaction rollback-only before the catch below ran. The catch
     * then flipped the event to {@code FAILED} and saved -- into a doomed
     * transaction. Commit threw {@code UnexpectedRollbackException}, the whole
     * batch rolled back INCLUDING the FAILED mark, the poison event stayed
     * {@code PENDING}, and the next tick replayed it 5 seconds later, forever.
     * Because {@code findPendingBatch} has no org filter, that stalled
     * notification delivery for EVERY org, not just the one with the bad data.
     * Confirmed in production on 26.07.57 and reproduced by fault injection on
     * the sandbox. This is the same trap {@link #markResolvedRequestsRead}
     * documents at length for the mark-read path; the drain loop simply never
     * got the same isolation.
     *
     * <p>Consequences of the per-event boundary worth knowing:
     * <ul>
     *   <li>If {@code fanOutSingle} throws mid-event after writing some
     *       deliveries, those delivery rows roll back with the event's own
     *       transaction -- they no longer survive alongside a FAILED mark, so
     *       partial fan-out is no longer observable. The event is then marked
     *       {@code FAILED} separately, so a replay does not duplicate them.
     *   <li>One poison event costs only itself. The other events in the batch
     *       commit normally instead of being dragged into its rollback.
     *   <li>Up to {@code batchSize} short transactions per tick rather than
     *       one long one. Deliberate: the advisory lock already serialises
     *       drains, so the extra commits cost latency, not contention, and
     *       queue liveness is worth more than batch atomicity here.
     * </ul>
     */
    public int drainBatch(int batchSize) {
        List<NotificationOutboxEvent> batch = outboxRepo.findPendingBatch(batchSize);
        for (NotificationOutboxEvent event : batch) {
            try {
                // Through the proxy, NOT this.fanOutOneEvent(...): a direct
                // self-call bypasses the transaction interceptor entirely and
                // would silently restore the single-transaction behaviour this
                // fix exists to remove.
                self.fanOutOneEvent(event);
            } catch (Exception e) {
                log.error("Fan-out failed for event {} ({}); marking FAILED",
                        event.getUuid(), event.getEventType(), e);
                markEventFailed(event.getUuid());
            }
        }
        return batch.size();
    }

    /**
     * Fan one event out and record its terminal status, atomically.
     *
     * <p>{@code REQUIRES_NEW} rather than the default {@code REQUIRED} so the
     * isolation holds even if some future caller wraps {@link #drainBatch} in a
     * transaction. Today the scheduler calls it with none, so this simply opens
     * one and costs nothing extra. If a caller ever does wrap it, note the
     * trade this repo documents elsewhere: a REQUIRES_NEW boundary takes a
     * second physical connection and cannot see the outer transaction's
     * uncommitted rows (see {@code SourceCodeEntryService} and
     * {@code OssReleaseService} for where that has bitten).
     *
     * <p>Public only so Spring can proxy it -- treat as internal to
     * {@link #drainBatch}.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fanOutOneEvent(NotificationOutboxEvent event) {
        FanOutOutcome outcome = fanOutSingle(event);
        switch (outcome) {
            case PROCESSED -> {
                event.setStatus(NotificationOutboxStatus.FANNED_OUT);
                outboxRepo.save(event);
            }
            case SUPPRESSED -> {
                event.setStatus(NotificationOutboxStatus.SUPPRESSED);
                log.info("Suppressing {} event {} for org {} after {} attempts: it affects no release. "
                        + "A vulnerability notification that names no release is not actionable.",
                        event.getEventType(), event.getUuid(), event.getOrg(),
                        event.getEnrichmentAttemptCount());
                outboxRepo.save(event);
            }
            // Deliberately does NOT fall through to an entity save: the row is
            // re-queued by a targeted update instead, so none of the payload
            // scratch enrichment just wrote is persisted. See deferForEnrichment.
            case DEFER -> deferForEnrichment(event);
            // Enum-constant switch STATEMENTS are not checked for exhaustiveness,
            // so without this a future fourth outcome would silently fall out
            // here leaving the row PENDING and unmodified -- which the drain
            // would re-pick every 5 seconds forever, the exact wedge this class
            // documents at length. Fail loudly instead.
            default -> throw new IllegalStateException(
                    "Unhandled fan-out outcome " + outcome + " for event " + event.getUuid());
        }
    }

    /**
     * Push a still-unresolvable vuln event back onto the queue instead of
     * terminating it. Status stays PENDING; the drain simply cannot see the
     * row again until {@code next_attempt_at} elapses.
     *
     * <p>The backoff is deliberately short (see
     * {@link #ENRICHMENT_BACKOFF_SECONDS}). The gap this covers is bounded:
     * the producer commits its outbox row in {@code ingestOrgBuckets} and the
     * {@code artifacts.metrics} write that answers the query lands in
     * {@code fanOutOrg}, the very next call in the same per-org iteration of
     * SchedulingService's ~1/min synthetic-SBOM tick. So the answer either
     * appears within a couple of ticks or it is genuinely not coming, and a
     * long backoff would only delay a notification that is already late.
     * Reusing {@code BackoffPolicy.dtrackFetchSkipSeconds} (1 to 60 minutes)
     * would be actively wrong here for that reason.
     */
    /**
     * Can an empty affected-release resolve be trusted as a statement about the
     * world for this org, rather than an artefact of the write we are racing?
     *
     * <p>The CVE to release link lives only in {@code artifacts.metrics}, and
     * that column is written by the synthetic-SBOM fan-out pass. So the answer
     * is trustworthy exactly when that pass has run for this org since the
     * event was emitted. Compares the org's most recent scan stamp against the
     * event's {@code occurred_at}.
     *
     * <p>A null stamp -- no scanned artifact anywhere in the org -- counts as
     * current, and deliberately so: it is a definitive answer, not a missing
     * one. Nothing in the org can be carrying the vuln.
     *
     * <p>Fails CLOSED (returns false, so the event is deferred and ultimately
     * delivered rather than suppressed) if the probe itself throws. The whole
     * point of this check is to avoid dropping a notification on insufficient
     * evidence, and a failed probe is the definition of insufficient evidence.
     *
     * <p>Runs only on the terminal pass, not on every deferral, and is served
     * by V74's {@code (org, cast(lastScanned as float))} index.
     */
    private boolean metricsAreCurrentFor(NotificationOutboxEvent event) {
        try {
            Double maxLastScanned = artifactRepo.findMaxLastScannedEpochForOrg(
                    event.getOrg().toString());
            if (maxLastScanned == null) return true;
            ZonedDateTime occurredAt = event.getOccurredAt();
            if (occurredAt == null) return true;
            double occurredEpoch = occurredAt.toInstant().toEpochMilli() / 1000.0;
            return maxLastScanned >= occurredEpoch;
        } catch (RuntimeException e) {
            log.warn("Could not establish whether org {} has current artifact metrics for event {}; "
                    + "treating as not-current so the event is not suppressed on unproven evidence: {}",
                    event.getOrg(), event.getUuid(), e.getMessage());
            return false;
        }
    }

    private void deferForEnrichment(NotificationOutboxEvent event) {
        int nextAttempt = event.getEnrichmentAttemptCount() + 1;
        int delaySeconds = BackoffPolicy.enrichmentDeferSeconds(nextAttempt);
        // Targeted update, NOT an entity save: the delay is stamped from the
        // database clock and the in-memory payload -- which enrichment has just
        // written an empty affectedReleases list into -- is deliberately not
        // persisted. A row that is still PENDING must not carry the answer we
        // have explicitly declined to commit to; "not resolved yet" and
        // "affects nothing" are precisely the two states this change exists to
        // keep apart. It also keeps the next attempt honest, since re-resolution
        // depends on the list still reading as unpopulated.
        outboxRepo.deferForEnrichment(event.getUuid(), nextAttempt, delaySeconds);
        // Keep the detached copy consistent with the row for any caller that
        // inspects it after the drain (tests do).
        event.setEnrichmentAttemptCount(nextAttempt);
        log.debug("Deferring {} event {} for org {} by {}s (attempt {}): affected releases not resolvable yet",
                event.getEventType(), event.getUuid(), event.getOrg(), delaySeconds, nextAttempt);
    }

    /**
     * Record a failed event as {@code FAILED} in a transaction of its own.
     *
     * <p>Re-reads by uuid instead of reusing the passed entity: that instance
     * belonged to the transaction that just rolled back, so it is detached and
     * its {@code @Version} may be stale. Best-effort -- if even this write
     * fails the event stays {@code PENDING} and is retried, which is the old
     * behaviour and still better than throwing out of the drain loop.
     */
    private void markEventFailed(UUID eventUuid) {
        try {
            self.markEventFailedInNewTransaction(eventUuid);
        } catch (Exception e) {
            log.error("Could not mark event {} FAILED; it stays PENDING and will be retried: {}",
                    eventUuid, e.getMessage());
        }
    }

    /** Public only so Spring can proxy it -- see {@link #markEventFailed}. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markEventFailedInNewTransaction(UUID eventUuid) {
        outboxRepo.findById(eventUuid).ifPresent(fresh -> {
            // Only a still-PENDING row may be failed. The throw we are reacting to
            // does not prove this event never fanned out: a second drainer that won
            // a dropped advisory lock could have committed it already (leaving our
            // detached copy stale, which is itself what threw), or something could
            // have escaped an afterCommit synchronization AFTER a successful
            // commit. Overwriting either case would strand committed delivery rows
            // under a FAILED event -- exactly the state this class no longer
            // produces and now documents as impossible.
            if (NotificationOutboxStatus.PENDING != fresh.getStatus()) {
                log.warn("Not marking event {} FAILED: it is already {} -- another drainer"
                        + " or a post-commit path got there first", eventUuid, fresh.getStatus());
                return;
            }
            // PENDING alone stopped being proof that nobody committed anything
            // for this event once deferral existed: a deferred row is committed
            // (its attempt count and due time bumped, @Version incremented) yet
            // stays PENDING by design. So the losing drainer of a dropped-lock
            // race would read PENDING here, having thrown only because the
            // winner's deferral made its own copy stale, and would terminate an
            // event that was merely waiting for its metrics write. Not due yet
            // means somebody deferred it deliberately -- leave it alone and let
            // it come back.
            if (fresh.getNextAttemptAt() != null
                    && fresh.getNextAttemptAt().isAfter(ZonedDateTime.now())) {
                log.warn("Not marking event {} FAILED: another drainer deferred it until {} "
                        + "(attempt {}); it is waiting, not broken",
                        eventUuid, fresh.getNextAttemptAt(), fresh.getEnrichmentAttemptCount());
                return;
            }
            fresh.setStatus(NotificationOutboxStatus.FAILED);
            outboxRepo.save(fresh);
        });
    }

    private FanOutOutcome fanOutSingle(NotificationOutboxEvent event) {
        if (event.getOrg() == null || event.getEventType() == null) {
            log.warn("Skipping malformed outbox event {} (org={}, type={})",
                    event.getUuid(), event.getOrg(), event.getEventType());
            return FanOutOutcome.PROCESSED;
        }

        // Channel-test bypass (Phase 2d). When channel_test_target is
        // set, this event was injected by SyntheticEventService#injectChannelTest
        // and the operator wants a single delivery to that exact channel —
        // not the org's subscription / CEL / severity matrix. Enrich the
        // payload first so the formatter still sees affectedReleases /
        // affectedComponent populated, then write a single delivery row.
        // The affected-release guard deliberately does NOT apply here: a
        // channel test is an operator pressing "send me one", and it must
        // deliver whatever it resolves -- including nothing. Withholding it
        // would break the very affordance being tested.
        if (event.getChannelTestTarget() != null) {
            enrichVulnEventIfNeeded(event, true);
            insertChannelTestDelivery(event);
            return FanOutOutcome.PROCESSED;
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
        if (subs.isEmpty()) return FanOutOutcome.PROCESSED;

        // Nothing in this org subscribes to this event type, so there is
        // nothing to enrich for and nothing to withhold. Checked BEFORE
        // enrichment and before the affected-release guard -- a correctness
        // point as much as a cost one.
        //
        // Cost: an org whose subscriptions cover only release/approval events
        // would otherwise pay the org-wide vulnerabilityDetails scan on all
        // four passes across 3.5 minutes, for an event that could never have
        // produced a delivery row.
        //
        // Correctness: those events would terminate as SUPPRESSED, mixing
        // "we withheld something actionable" into the very count that is meant
        // to measure how often the race fires -- which would make the evidence
        // SUPPRESSED exists to provide unusable.
        //
        // Safe to hoist above enrichment because matchesEventType reads only
        // the subscription's declared eventTypes and never the payload, unlike
        // filterMatches, whose CEL expressions do -- see the ordering invariant
        // documented below.
        boolean anySubWantsThisType = false;
        for (NotificationSubscription sub : subs) {
            NotificationSubscriptionData data = parseSubscription(sub);
            if (data != null && matchesEventType(data, event)) {
                anySubWantsThisType = true;
                break;
            }
        }
        if (!anySubWantsThisType) return FanOutOutcome.PROCESSED;

        // Ordering invariant: enrichment MUST run before extractEventSeverity
        // and the subscription loop. CEL filters in filterMatches read the
        // full payload via EventActivationMapBuilder; a filter referencing
        // event.affectedReleases needs the enriched list to be visible.
        // Severity itself is producer-set (untouched by enrichment), so
        // its read order is moot — but moving extractEventSeverity above
        // enrichment would silently break filter semantics.
        EnrichmentResult enrichment = enrichVulnEventIfNeeded(event, false);

        // Affected-release guard. A vuln event that names no release is not
        // actionable, so it is never delivered -- but "resolved to nothing"
        // and "could not resolve" are different claims and are treated
        // differently. Nothing has been written for this event yet at this
        // point (the two APPROVAL_* writes above cannot coincide with a vuln
        // event type), so both branches leave the transaction clean.
        int attempts = event.getEnrichmentAttemptCount();
        boolean budgetSpent = attempts >= MAX_ENRICHMENT_ATTEMPTS;
        switch (enrichment) {
            case RESOLVED_EMPTY -> {
                if (!budgetSpent) return FanOutOutcome.DEFER;
                // Budget spent -- but elapsed time is NOT evidence that nothing
                // is affected, because nothing bounds the gap being waited on:
                // fanOutOrg can throw and resume a tick later, a large org's
                // pass can run long, and updateArtifactDti's idempotency guard
                // can skip the metrics write outright. So before withholding a
                // vulnerability notification permanently, ask whether the
                // pipeline that writes the CVE -> release link has actually run
                // for this org since the event was emitted. Only then does
                // "we found nothing" justify concluding "there is nothing".
                if (metricsAreCurrentFor(event)) return FanOutOutcome.SUPPRESSED;
                if (attempts < MAX_ENRICHMENT_ATTEMPTS_UNPROVEN) return FanOutOutcome.DEFER;
                // Waited as long as we are willing to and still cannot prove the
                // answer. Deliver rather than suppress, for the same reason
                // UNRESOLVED delivers: an unproven empty is an unknown, and
                // silently dropping a real vulnerability notification is worse
                // than an "affects 0 releases" one that is at least visible.
                // ERROR, not WARN: operator alerting fires on ERROR, and
                // reaching this ceiling means the org's artifact-metrics
                // pipeline has not run for ~15 minutes while vuln records were
                // still being written for it -- a stalled DT sync / synthetic
                // -SBOM fan-out, which is operator-actionable and should not be
                // discovered by reading logs. Rare by construction: a vuln
                // record is only created from an org's own DT findings, so a
                // scan has normally just happened.
                log.error("Delivering {} event {} (org {}) with no affected releases after {} attempts: "
                        + "the org's artifact metrics have still not been refreshed since the event "
                        + "occurred at {}, so an empty result cannot be trusted enough to suppress it. "
                        + "This usually means the DT sync / synthetic-SBOM fan-out is lagging or stalled.",
                        event.getEventType(), event.getUuid(), event.getOrg(), attempts,
                        event.getOccurredAt());
            }
            case UNRESOLVED -> {
                // The resolve THREW, so we know nothing about whether releases
                // are affected. Retry while there is budget, but never convert
                // an unknown into a suppression: silently dropping a real
                // vulnerability notification because a JSONB scan errored would
                // be a worse failure than the one this guard exists to fix.
                // Falling through preserves the pre-existing "enrichment
                // failure is not a fan-out failure" contract.
                if (!budgetSpent) return FanOutOutcome.DEFER;
                log.warn("Enrichment for {} event {} (org {}) never resolved after {} attempts; "
                        + "delivering anyway rather than suppressing on an unknown",
                        event.getEventType(), event.getUuid(), event.getOrg(), MAX_ENRICHMENT_ATTEMPTS);
            }
            case RESOLVED_NON_EMPTY, NOT_APPLICABLE -> {
                // Deliverable: either releases were found, or this is not a
                // vuln event / the producer already populated the list.
            }
        }

        NotificationSeverity eventSeverity = extractEventSeverity(event);

        // Ownership, resolved at most once per event per consumer and only if
        // something asks. TWO suppliers, memoized independently -- see
        // lazyOwnerChannels for why they are deliberately not composed. They
        // share the component reads. Neither runs at all unless a route sets
        // notifyComponentOwner or a subscription carries an ownedByTeam scope,
        // which is to say: not for any subscription that exists today.
        Supplier<List<ComponentData>> affectedComponents = lazyAffectedComponents(event);
        Supplier<Set<UUID>> ownerTeams = lazyOwnerTeams(event, affectedComponents);
        Supplier<List<UUID>> ownerChannels = lazyOwnerChannels(event, affectedComponents);

        for (NotificationSubscription sub : subs) {
            NotificationSubscriptionData data = parseSubscription(sub);
            if (data == null) continue;
            if (!matchesEventType(data, event)) continue;
            if (!filterMatches(data, sub, event)) continue;
            // Ownership scope LAST of the three: event type is a list lookup and
            // the filter is in-memory CEL, while this can hit the database. An
            // unscoped subscription -- every subscription that exists today --
            // never reaches the supplier at all.
            if (!ownedByTeamMatches(data, event, ownerTeams)) continue;
            applyRoutes(data, sub, event, eventSeverity, ownerChannels);
        }
        return FanOutOutcome.PROCESSED;
    }

    /**
     * What {@link #fanOutSingle} decided should happen to the outbox row.
     * Modelled as an enum rather than a boolean because the three cases carry
     * genuinely different meanings and two of them are terminal.
     */
    private enum FanOutOutcome {
        /** Fan-out ran to completion; flip the row to FANNED_OUT. */
        PROCESSED,
        /** Not answerable yet; leave PENDING and look again after a delay. */
        DEFER,
        /** Answerable, and the answer is "affects nothing"; withhold it. */
        SUPPRESSED
    }

    /**
     * Outcome of the vuln-payload enrichment pass.
     *
     * <p>The distinction between {@link #RESOLVED_EMPTY} and
     * {@link #UNRESOLVED} is load-bearing: only the former is a statement
     * about the world ("no release carries this vuln"). The latter means the
     * lookup itself failed, which is not evidence of anything and must never
     * be turned into a silent drop.
     */
    private enum EnrichmentResult {
        /** Not a vuln event, or the payload already carried affected releases. */
        NOT_APPLICABLE,
        /** Resolved, and at least one release is affected. */
        RESOLVED_NON_EMPTY,
        /** Resolved cleanly, and no release is affected. */
        RESOLVED_EMPTY,
        /** The resolve threw; whether any release is affected is unknown. */
        UNRESOLVED
    }

    private boolean matchesEventType(NotificationSubscriptionData data, NotificationOutboxEvent event) {
        return data.eventTypes() != null
                && data.eventTypes().contains(event.getEventType());
    }

    /**
     * Ownership scope: a team-scoped subscription matches only events that
     * affect at least one component ITS team owns.
     *
     * <p>This is the matching half of what {@code notifyComponentOwner} does as
     * a destination, and it exists because the two cannot be the same thing. A
     * subscription built for one team out of the owner-routing flag would
     * deliver that team's events to every OTHER owning team as well; scoping
     * here and targeting the team in the route keeps one team's feed to itself.
     *
     * <p>Fails CLOSED. An event that affects nothing resolvable -- no
     * components on the payload, an unowned component, an owner that is a user,
     * a suggestion, or an archived team -- matches no team-scoped subscription.
     * The alternative (treating "cannot tell" as a match) would send a team
     * events about components it does not own, which is worse than silence and
     * much harder to notice.
     *
     * <p><b>{@code VEX_STATE_CHANGED} can never match a scoped subscription.</b>
     * {@code VexStateChangedPayload} carries a component PURL but no
     * {@code affectedReleases}, and enrichment stamps that key only for the two
     * vuln event types, so {@link #extractAffectedComponentUuids} finds nothing
     * and the scope fails closed forever. Harmless today -- no producer emits
     * that event and the UI does not offer it -- but it is a real hole in any
     * "all event types" promise built on this, and the same blind spot already
     * affects {@code notifyComponentOwner}. Fixing it means stamping affected
     * releases on the VEX payload, not special-casing it here.
     *
     * <p>The no-match line is DEBUG on purpose: for a scoped subscription, not
     * matching is the normal case -- most events in an org are about somebody
     * else's components -- so anything louder would be noise per event.
     *
     * <p>Scoping and {@code notifyComponentOwner} on the same subscription is
     * permitted and does something coherent but surprising: it matches only
     * events touching MY components, then delivers to EVERY owner of those
     * events' components. No team hears about a component it does not own -- the
     * other owners are owners of the same event -- but the result is redundant
     * with those teams' own subscriptions. The materialiser in phase 2 targets
     * the team directly rather than through the owner flag; a hand-built API
     * combination gets what it asked for.
     */
    private boolean ownedByTeamMatches(NotificationSubscriptionData data, NotificationOutboxEvent event,
            Supplier<Set<UUID>> ownerTeams) {
        UUID scope = data.ownedByTeam();
        if (scope == null) return true;
        Set<UUID> owners = ownerTeams.get();
        if (owners.contains(scope)) return true;
        log.debug("Event {} ({}): no affected component is owned by team {}, so its scoped subscription "
                + "does not match ({} owner team(s) resolved)",
                event.getUuid(), event.getEventType(), scope, owners.size());
        return false;
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
            NotificationOutboxEvent event, NotificationSeverity eventSeverity,
            Supplier<List<UUID>> ownerChannels) {
        if (data.routes() == null) return;
        for (RouteConfig route : data.routes()) {
            if (route == null) continue;
            if (!severityGateMatches(route, eventSeverity)) continue;
            if (!perspectiveGateMatches(route, event)) continue;
            // Phase 13b: expand channelGroups, then merge with direct
            // channels; T3 adds team expansion. Dedup is first-seen across the
            // merged list, so a channel referenced directly, via a group AND via
            // a team still produces exactly one delivery row.
            List<UUID> resolvedChannels = mergeRouteChannels(route, event.getOrg(), ownerChannels);
            if (resolvedChannels.isEmpty()) {
                // No direct channels, no resolvable groups, no team channels -- log + skip.
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
     * Merge helper. Returns the deduplicated, first-seen-order union of
     * {@code route.channels}, the channel UUIDs resolved from
     * {@code route.channelGroups} (Phase 13b), and the channels of the teams
     * named in {@code route.teams} (T3). Direct channels are visited
     * first so an operator who explicitly listed a channel sees it
     * preserved even if a group later also contains it. Null entries
     * on either side are silently skipped.
     */
    private List<UUID> mergeRouteChannels(RouteConfig route, UUID eventOrg,
            Supplier<List<UUID>> ownerChannels) {
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
        // T3: a route may target Teams; each contributes its own channels.
        // Resolved late (here, not at save time) so retargeting a team's Slack
        // channel takes effect without touching every subscription that names it.
        if (route.teams() != null && !route.teams().isEmpty()) {
            for (UUID ch : teamService.resolveTeamChannelUuids(route.teams(), eventOrg)) {
                if (ch != null && seen.add(ch)) out.add(ch);
            }
        }
        // T4a: "whoever owns the affected component". Last in the merge so an
        // explicitly named channel or team keeps its first-seen position; the
        // shared `seen` set means a team that is BOTH named on the route and
        // the component's owner still yields exactly one delivery row.
        //
        // Supplied lazily and memoized per EVENT (not per route, not per
        // subscription): resolving ownership reads the org's groups + org record
        // and then every affected component, and one event routinely matches
        // several subscriptions. Routes without the flag never trigger the work.
        if (Boolean.TRUE.equals(route.notifyComponentOwner())) {
            for (UUID ch : ownerChannels.get()) {
                if (ch != null && seen.add(ch)) out.add(ch);
            }
        }
        return out;
    }

    /**
     * Component uuids the event affects, read from the enriched
     * {@code affectedReleases} payload.
     *
     * <p>Handles the same two payload shapes as {@link #perspectiveGateMatches}:
     * a Jackson-round-tripped {@code Map} (the normal post-enrichment path) and a
     * typed {@link AffectedRelease} (synthetic helpers and hand-built test
     * payloads). A producer that skips the round-trip must not silently resolve
     * to "no owner" -- that would look identical to "component has no owner".
     */
    private static Set<UUID> extractAffectedComponentUuids(NotificationOutboxEvent event) {
        Object affected = event.getRecordData() != null
                ? event.getRecordData().get("affectedReleases") : null;
        if (!(affected instanceof List<?> list) || list.isEmpty()) return Set.of();
        Set<UUID> out = new LinkedHashSet<>();
        for (Object item : list) {
            UUID cu = null;
            if (item instanceof AffectedRelease ar) {
                cu = ar.componentUuid();
            } else if (item instanceof Map<?, ?> map) {
                cu = coerceToUuid(map.get("componentUuid"), event.getUuid());
            }
            if (cu != null) out.add(cu);
        }
        return out;
    }

    /**
     * The components this event affects, fetched at most once per event.
     *
     * <p>Shared by both ownership consumers below. They deliberately do not
     * share an ownership RESOLUTION (see {@link #lazyOwnerChannels}), but there
     * was never a reason for them not to share the component READS -- and an
     * event carrying a widely-deployed CVE can name a lot of components, so
     * fetching them twice is the expensive half of the duplication.
     */
    private Supplier<List<ComponentData>> lazyAffectedComponents(NotificationOutboxEvent event) {
        return new Supplier<>() {
            private List<ComponentData> cached;

            @Override
            public List<ComponentData> get() {
                if (cached == null) {
                    List<ComponentData> components = new ArrayList<>();
                    for (UUID cu : extractAffectedComponentUuids(event)) {
                        getComponentService.getComponentData(cu).ifPresent(components::add);
                    }
                    cached = components;
                }
                return cached;
            }
        };
    }

    /**
     * The owner teams of everything this event affects, resolved at most once
     * per event and shared by every team-scoped subscription on it.
     *
     * <p>Resolving once means an org with fifty team-scoped subscriptions pays
     * for one ownership pass, not fifty.
     */
    private Supplier<Set<UUID>> lazyOwnerTeams(NotificationOutboxEvent event,
            Supplier<List<ComponentData>> affectedComponents) {
        return new Supplier<>() {
            private Set<UUID> cached;

            @Override
            public Set<UUID> get() {
                if (cached == null) {
                    cached = componentOwnershipService.resolveOwnerTeams(
                            affectedComponents.get(), event.getOrg());
                }
                return cached;
            }
        };
    }

    /**
     * Owner CHANNELS, for the {@code notifyComponentOwner} route flag.
     *
     * <p>Deliberately still routed through {@code resolveOwnerTeamChannels}
     * rather than composed from {@link #lazyOwnerTeams} above. Composing them
     * would resolve ownership ONCE instead of twice when an event matches both
     * an owner-routed route AND a team-scoped subscription. The cost of doing so
     * is 8 stub/verify sites across {@code NotificationFanOutServiceTest} and
     * {@code NotificationDrainTransactionTest} -- the two classes that guard
     * owner routing through fan-out -- rewritten inside the commit that adds a
     * new predicate to the same method. (The other two classes referencing
     * {@code resolveOwnerTeamChannels} are unaffected: {@code
     * ComponentOwnershipServiceTest} calls the real service, and {@code
     * SyntheticEventServiceTest} stubs a different production caller. An earlier
     * version of this comment cited the raw grep total of 24 and overstated the
     * blast radius threefold.)
     *
     * <p>What remains duplicated is the ownership pass itself: one context load
     * and one rule evaluation per component, twice. The component READS are
     * shared via {@link #lazyAffectedComponents}, which is the expensive half on
     * an event naming many components.
     *
     * <p>Both paths go through the same {@code resolveOwnerTeams} underneath, so
     * they cannot disagree about who owns what -- only about how many times they
     * ask. Unifying belongs in the phase that touches these tests anyway.
     */
    private Supplier<List<UUID>> lazyOwnerChannels(NotificationOutboxEvent event,
            Supplier<List<ComponentData>> affectedComponents) {
        return new Supplier<>() {
            private List<UUID> cached;

            @Override
            public List<UUID> get() {
                if (cached == null) {
                    List<ComponentData> components = affectedComponents.get();
                    cached = componentOwnershipService.resolveOwnerTeamChannels(
                            components, event.getOrg());
                    if (cached.isEmpty()) {
                        // Worth a line: the operator ticked "notify the owner" and
                        // nothing came back. Almost always an unowned component or
                        // an owner team with no channel configured -- both silent
                        // otherwise, and both look like "notifications are broken".
                        //
                        // The zero-component case is called out separately and is
                        // NOT skipped. It used to be: this line was guarded on a
                        // non-empty component set, so an event whose producer never
                        // stamped affectedReleases resolved no owner AND logged
                        // nothing about it. That combination is what made the
                        // approval-event gap undiagnosable from outside the code.
                        //
                        // Re-extracted rather than read off `components`: the two
                        // differ when the payload NAMES components that fail to
                        // load, and that case must not be reported as "the payload
                        // named none" -- distinguishing them is the entire point of
                        // the branch.
                        Set<UUID> componentUuids = extractAffectedComponentUuids(event);
                        if (componentUuids.isEmpty()) {
                            log.debug("Event {} ({}): notifyComponentOwner found no affectedReleases on the "
                                    + "payload, so no component and no owner could be resolved",
                                    event.getUuid(), event.getEventType());
                        } else {
                            log.debug("Event {}: notifyComponentOwner resolved no channels for {} affected component(s)",
                                    event.getUuid(), componentUuids.size());
                        }
                    }
                }
                return cached;
            }
        };
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
     * synchronization rather than executed inside the event's transaction:
     * a throw inside it would mark that transaction rollback-only before our
     * catch ran, losing the event's whole fan-out over a cosmetic mark-read
     * failure. (A plain SQL error would similarly abort the underlying PG
     * transaction.)
     *
     * <p>Since the drain gained a per-event transaction boundary, that blast
     * radius is one event rather than the whole batch -- deferring is still
     * right, but it is no longer all that stands between a mark-read failure
     * and a wedged queue. See {@link #drainBatch}.
     *
     * <p>{@code markRead} MUST be {@code REQUIRES_NEW} for this to work:
     * inside afterCommit the completed transaction context is still bound,
     * so a {@code REQUIRED} call joins it and its writes are silently
     * discarded — the mark-read log fires but no row commits (observed on
     * the sandbox during the Phase 4b smoke). With {@code REQUIRES_NEW},
     * each markRead runs in its own fresh transaction and the targeted
     * rows it references are already durable — including the same-batch
     * case, and now more strongly than before: the REQUESTED event committed
     * in its OWN earlier per-event transaction, so its targeted rows are
     * durable before the RESOLVED event's transaction even opens. If this
     * event's transaction rolls back, the synchronization never fires and the
     * replayed event re-registers it -- no marks lost.
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
     * <p>Costs one PK lookup per delivery row. Each event's fan-out runs in
     * its own transaction ({@link #fanOutOneEvent}), and {@code getChannel}
     * resolves by primary key, so repeated lookups of the same channel within
     * ONE event are served from the Hibernate first-level cache. Across events
     * the persistence context is fresh and the lookup re-hits the DB -- an
     * accepted cost of the per-event boundary, since a batch rarely repeats a
     * channel across events and the alternative was a queue-wide wedge.
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
                // Instance-deployment events carry a computed severity (max over
                // items) so route severity gates can isolate outcomes. Without
                // this case a new type returns null and severityGateMatches fails
                // closed, silently dropping every instance event on any route
                // that sets a minimum severity.
                case INSTANCE_DEPLOYMENT_CHANGED, INSTANCE_DEPLOYMENT_FAILED -> {
                    InstanceDeploymentChangedPayload p = Utils.OM.convertValue(
                            event.getRecordData(), InstanceDeploymentChangedPayload.class);
                    return p != null ? p.severity() : null;
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
     * time those updates have USUALLY committed, so we resolve them via the
     * {@code metrics->vulnerabilityDetails} JSONB index and update the
     * event's recordData in place. CEL filters and channel formatters
     * downstream see the enriched payload. When they have NOT committed yet,
     * the caller defers the event rather than delivering an empty answer --
     * see the affected-release guard in {@link #fanOutSingle}.
     *
     * <p>Failure mode: an enrichment exception logs and proceeds with an
     * empty release set, and the return value distinguishes that
     * ({@code UNRESOLVED}) from a clean empty resolve ({@code RESOLVED_EMPTY}).
     * The event still ships once its deferral budget is spent -- customers see
     * "affects 0 releases" rather than nothing at all -- because a failed
     * lookup is not evidence that nothing is affected, and dropping a real
     * vulnerability notification on a transient DB error would be worse than
     * the empty payload. Only a definitive empty resolve is suppressed.
     *
     * <p>Caveat worth knowing: a throw raised INSIDE a Spring Data repository
     * call (e.g. a statement timeout on the org-wide scan) marks the
     * surrounding transaction rollback-only, so the deferral written by this
     * pass cannot commit and the drain's catch terminates the event as FAILED
     * instead. That trap predates this guard -- it applied equally to the old
     * "log and ship anyway" behaviour -- but it does mean the UNRESOLVED path
     * covers in-memory enrichment faults more reliably than DB-level ones.
     *
     * <p>Mutating {@code event.getRecordData()} in place is the persistence
     * mechanism: the mutated {@code @Type(JsonBinaryType)} column is
     * re-serialized by the {@code outboxRepo.save(event)} call in
     * {@link #fanOutOneEvent}. Do NOT refactor to a defensive copy or the
     * enrichment will vanish on commit. Note the entity arrives DETACHED --
     * {@code drainBatch} reads the batch outside any transaction -- so that
     * {@code save} is a merge rather than dirty-tracking on a managed
     * instance; the outcome is the same, but the mechanism is the merge.
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
    private EnrichmentResult enrichVulnEventIfNeeded(NotificationOutboxEvent event,
            boolean forChannelTest) {
        NotificationEventType type = event.getEventType();
        if (type != NotificationEventType.NEW_VULN_AFFECTS_RELEASES
                && type != NotificationEventType.VULNERABILITY_RECORD_UPDATED) {
            return EnrichmentResult.NOT_APPLICABLE;
        }
        Map<String, Object> recordData = event.getRecordData();
        if (recordData == null) return EnrichmentResult.NOT_APPLICABLE;
        Object idRaw = recordData.get("vulnPrimaryId");
        if (!(idRaw instanceof String vulnPrimaryId) || StringUtils.isBlank(vulnPrimaryId)) {
            return EnrichmentResult.NOT_APPLICABLE;
        }
        EnrichmentResult result;
        // If the producer already populated affectedReleases (synthetic
        // events do; future producers might), don't clobber it.
        Object existing = recordData.get("affectedReleases");
        if (existing instanceof List<?> existingList && !existingList.isEmpty()) {
            // Producer-populated and non-empty: nothing to resolve, and the
            // affected-release guard has no business second-guessing it.
            result = EnrichmentResult.NOT_APPLICABLE;
        } else {
            try {
                List<AffectedRelease> resolved = resolveAffectedReleases(event.getOrg(), vulnPrimaryId);
                recordData.put("affectedReleases", Utils.OM.convertValue(resolved, List.class));
                result = resolved.isEmpty()
                        ? EnrichmentResult.RESOLVED_EMPTY
                        : EnrichmentResult.RESOLVED_NON_EMPTY;
            } catch (RuntimeException e) {
                log.warn("Failed to enrich vuln event {} ({} for {}): {}",
                        event.getUuid(), type, vulnPrimaryId, e.getMessage());
                recordData.putIfAbsent("affectedReleases", Collections.emptyList());
                result = EnrichmentResult.UNRESOLVED;
            }
        }

        // S-3: only the NEW_VULN payload carries affectedComponent; same
        // don't-clobber rule for synthetic/producer-populated events.
        //
        // Skipped on any outcome that is about to defer, which matters for
        // correctness and not just cost. resolveAffectedComponent collapses
        // multiple purls to the lexicographically-first one -- a pick its
        // javadoc calls "deterministic across retries", which was true only
        // while there was exactly one pass. Under deferral the early passes see
        // a PARTIAL purl set (that is what they are waiting on), so resolving
        // then would stamp a package from the incomplete view, and the
        // "== null" don't-clobber rule would freeze it: the delivered payload
        // could name a package belonging to none of the releases it lists.
        // Resolving only on the pass that actually delivers keeps the two
        // halves of the payload drawn from the same snapshot. It also drops an
        // org-wide JSONB scan from every deferred pass.
        boolean willDeliverNow = result != EnrichmentResult.RESOLVED_EMPTY
                && result != EnrichmentResult.UNRESOLVED;
        if (type == NotificationEventType.NEW_VULN_AFFECTS_RELEASES
                && (willDeliverNow || forChannelTest)
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
        return result;
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
