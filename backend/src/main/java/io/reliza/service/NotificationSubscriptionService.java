/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.WhoUpdated;
import io.reliza.model.NotificationChannelGroup;
import io.reliza.model.dto.notifications.NotificationChannelGroupData;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.FilterConfig;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RouteConfig;
import io.reliza.model.NotificationSubscriptionStatus;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.NotificationChannelGroupRepository;
import io.reliza.repositories.NotificationSubscriptionRepository;
import io.reliza.service.AuditService;
import io.reliza.model.dto.notifications.EvaluationMode;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 3 CRUD service for {@link NotificationSubscription}.
 *
 * <p>Subscriptions are the customer-authored "send X to channel Y
 * when conditions Z" rules. They carry the most expressive surface
 * in the framework: typed event-type list, a CEL filter expression,
 * and a per-route channel table with severity / env / lifecycle
 * gates.
 *
 * <h3>Validation surface (save-time)</h3>
 * <ul>
 *   <li><b>CEL filter</b> — round-tripped through
 *       {@link NotificationCelEvaluator#validate} so a typo / oversized
 *       expression fails at save, not at every fan-out tick.</li>
 *   <li><b>Route channel UUIDs</b> — must reference channels that
 *       exist in the same org. A subscription pointing at a deleted
 *       channel would silently produce no deliveries; a dangling
 *       cross-tenant uuid would amplify a tenant-boundary bug.</li>
 *   <li><b>JSONB size cap</b> — caps the serialized record at 256 KB
 *       to bound per-batch deserialization cost on the fan-out worker.
 *       Same ceiling as {@code NotificationChannelService}.</li>
 *   <li><b>Required fields</b> — org / name / status / eventTypes /
 *       routes must all be present and non-empty.</li>
 * </ul>
 *
 * <h3>Upsert semantics</h3>
 * Matches {@link NotificationChannelService}: UUID present → update,
 * UUID null → create. Validation runs before either branch.
 *
 * <h3>Delete semantics</h3>
 * Hard delete. Customers wanting reversible "pause" semantics flip
 * status to DISABLED via {@link #setSubscriptionStatus}; the fan-out
 * worker's active-subscription query
 * ({@code subscriptionRepo.findActiveByOrg}) skips non-ACTIVE rows.
 */
@Service
@Slf4j
public class NotificationSubscriptionService {

    @Autowired
    private NotificationSubscriptionRepository subscriptionRepo;

    @Autowired
    private IntegrationRepository integrationRepo;

    @Autowired
    private NotificationChannelGroupRepository channelGroupRepo;

    @Autowired
    private AuditService auditService;

    // Seam: the CEL evaluator implementation lives in saas/ (Pro-only).
    // On CE the bean is absent, so this is optional; validateFilter()
    // skips CEL validation when it is null (CE cannot evaluate filters,
    // so it stores them unvalidated and fan-out treats them as match-all).
    @Autowired(required = false)
    private NotificationCelEvaluator celEvaluator;

    public Optional<NotificationSubscription> getSubscription(UUID uuid) {
        if (uuid == null) return Optional.empty();
        return subscriptionRepo.findById(uuid);
    }

    /** Convenience overload — kept for callers that don't need the optimistic-lock gate (e.g. tests). */
    public NotificationSubscription upsertSubscription(UUID uuid, NotificationSubscriptionData seed,
            WhoUpdated wu) throws RelizaException {
        return upsertSubscription(uuid, /*expectedRevision*/ null, seed, wu);
    }

    @Transactional
    public NotificationSubscription upsertSubscription(UUID uuid, Integer expectedRevision,
            NotificationSubscriptionData seed, WhoUpdated wu) throws RelizaException {
        if (seed == null) throw new RelizaException("Subscription input is required");
        validateSeed(seed);

        NotificationSubscription target;
        if (uuid != null) {
            // Reject supplied-but-nonexistent uuids per the
            // AgentPolicyService / CommitterService / WebhookService
            // convention — see the matching comment in
            // {@code NotificationChannelService.upsertChannel}.
            Optional<NotificationSubscription> existing = subscriptionRepo.findById(uuid);
            if (existing.isEmpty()) {
                throw new RelizaException("Subscription not found: " + uuid);
            }
            target = existing.get();
            NotificationChannelService.assertExpectedRevision(target.getRevision(),
                    expectedRevision, "Subscription", seed.name());
            auditService.createAndSaveAuditRecord(TableName.NOTIFICATION_SUBSCRIPTION, target);
        } else {
            target = new NotificationSubscription();
            target.setUuid(UUID.randomUUID());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = Utils.OM.convertValue(seed, Map.class);
        NotificationChannelService.assertRecordDataSize(recordData, "subscription");
        target.setRecordData(recordData);
        target = (NotificationSubscription) WhoUpdated.injectWhoUpdatedData(target, wu);
        NotificationSubscription saved;
        try {
            saved = subscriptionRepo.save(target);
        } catch (ObjectOptimisticLockingFailureException e) {
            // See NotificationChannelService.upsertChannel for the
            // pre-check vs. @Version-race split rationale.
            log.info("Optimistic-lock race on subscription upsert (org={}, name={}): {}",
                    seed.org(), seed.name(), e.getMessage());
            throw new RelizaException("Conflict: Subscription \"" + seed.name()
                    + "\" was edited by another user — please reload and retry");
        }
        log.info("Upserted notification_subscription org={} sub={} status={} eventTypes={}",
                seed.org(), saved.getUuid(), seed.status(), seed.eventTypes());
        return saved;
    }

    @Transactional
    public NotificationSubscription setSubscriptionStatus(UUID uuid, NotificationSubscriptionStatus status,
            WhoUpdated wu) throws RelizaException {
        if (uuid == null) throw new RelizaException("uuid is required");
        if (status == null) throw new RelizaException("status is required");
        Optional<NotificationSubscription> oSub = subscriptionRepo.findById(uuid);
        if (oSub.isEmpty()) throw new RelizaException("Subscription not found: " + uuid);
        NotificationSubscription sub = oSub.get();
        NotificationSubscriptionData data = parseRecordData(sub);
        if (data == null) throw new RelizaException("Subscription " + uuid + " has unparseable record_data");
        if (data.status() == status) {
            return sub;
        }
        auditService.createAndSaveAuditRecord(TableName.NOTIFICATION_SUBSCRIPTION, sub);
        NotificationSubscriptionData updated = new NotificationSubscriptionData(
                data.org(), data.resourceGroup(), data.name(), status,
                data.eventTypes(), data.filter(), data.routes(),
                data.dedupWindowMinutes(), data.rateLimit());
        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = Utils.OM.convertValue(updated, Map.class);
        sub.setRecordData(recordData);
        sub = (NotificationSubscription) WhoUpdated.injectWhoUpdatedData(sub, wu);
        return subscriptionRepo.save(sub);
    }

    @Transactional
    public void deleteSubscription(UUID uuid) throws RelizaException {
        if (uuid == null) throw new RelizaException("uuid is required");
        Optional<NotificationSubscription> oSub = subscriptionRepo.findById(uuid);
        if (oSub.isEmpty()) return;
        auditService.createAndSaveAuditRecord(TableName.NOTIFICATION_SUBSCRIPTION, oSub.get());
        subscriptionRepo.deleteById(uuid);
        log.info("Deleted notification_subscription {}", uuid);
    }

    /**
     * Field-by-field validation. Mirrors the {@code AgentPolicyService}
     * convention of catching every failure in the service layer before
     * the create/update branch.
     */
    private void validateSeed(NotificationSubscriptionData seed) throws RelizaException {
        if (seed.org() == null) throw new RelizaException("org is required");
        if (StringUtils.isBlank(seed.name())) throw new RelizaException("name is required");
        if (seed.status() == null) throw new RelizaException("status is required");
        if (seed.eventTypes() == null || seed.eventTypes().isEmpty()) {
            throw new RelizaException("at least one eventType is required");
        }
        if (seed.routes() == null || seed.routes().isEmpty()) {
            throw new RelizaException("at least one route is required");
        }
        validateFilter(seed.filter());
        validateRoutes(seed.org(), seed.routes());
    }

    private void validateFilter(FilterConfig filter) throws RelizaException {
        if (filter == null) return;
        String cel = filter.celExpression();
        if (StringUtils.isBlank(cel)) return;
        // CE edition: no CEL evaluator on the classpath. Store the filter
        // unvalidated — fan-out treats it as match-all anyway.
        if (celEvaluator == null) return;
        EvaluationMode mode = filter.mode() != null ? filter.mode() : EvaluationMode.PRESET;
        // celEvaluator.validate throws RelizaException with a human-
        // readable message on failure. Let it propagate so the caller's
        // exception mapper surfaces the message to the customer.
        celEvaluator.validate(cel, mode);
    }

    /**
     * Validate each route's referenced channels and channel-groups:
     * every uuid must resolve to a row in the same org as the
     * subscription. Without this check a route could silently point at
     * a deleted destination, producing zero deliveries forever; a
     * cross-tenant uuid would amplify a tenant-boundary bug.
     *
     * <p>Phase 13b: a route is valid as long as it carries at least
     * one direct channel OR at least one channelGroup. Both empty is
     * still a save-time error (a route with nothing to deliver to is
     * meaningless). The fan-out's "merged list empty" path handles the
     * runtime case of a referenced group being deleted between save
     * and fan-out (logs + skips that route).
     *
     * <p><b>TOCTOU window (acknowledged).</b> Channel-delete /
     * group-delete and subscription-upsert run in independent
     * {@code @Transactional} boundaries (default READ_COMMITTED on
     * Postgres), so a concurrent delete that commits between this
     * validate call and the upsert's save will leave a route pointing
     * at a deleted destination. The runtime mitigation lives in
     * {@code NotificationDeliveryWorker}'s "channel no longer exists"
     * path (for channels) and the fan-out's merge-and-skip-empty path
     * (for groups). coding_principles.md "Avoid database-level FOREIGN
     * KEY constraints" endorses this pattern: writes validate at save
     * time; runtime workers tolerate-and-log dangling references.
     */
    private void validateRoutes(UUID org, List<RouteConfig> routes) throws RelizaException {
        Set<UUID> referencedChannels = new HashSet<>();
        Set<UUID> referencedGroups = new HashSet<>();
        for (RouteConfig route : routes) {
            if (route == null) continue;
            boolean hasChannels = route.channels() != null && !route.channels().isEmpty();
            boolean hasGroups = route.channelGroups() != null && !route.channelGroups().isEmpty();
            if (!hasChannels && !hasGroups) {
                throw new RelizaException(
                        "route must reference at least one channel or channelGroup");
            }
            if (route.channels() != null) {
                for (UUID channelUuid : route.channels()) {
                    if (channelUuid != null) referencedChannels.add(channelUuid);
                }
            }
            if (route.channelGroups() != null) {
                for (UUID groupUuid : route.channelGroups()) {
                    if (groupUuid != null) referencedGroups.add(groupUuid);
                }
            }
        }
        if (referencedChannels.isEmpty() && referencedGroups.isEmpty()) {
            throw new RelizaException(
                    "subscription routes must reference at least one channel or channelGroup");
        }
        validateReferencedChannels(org, referencedChannels);
        validateReferencedGroups(org, referencedGroups);
    }

    /**
     * Batch resolution — one DB roundtrip per unique channel rather
     * than once per route, since the same channel can appear in
     * multiple routes.
     */
    private void validateReferencedChannels(UUID org, Set<UUID> referenced) throws RelizaException {
        if (referenced.isEmpty()) return;
        List<UUID> notFound = new ArrayList<>();
        List<UUID> wrongOrg = new ArrayList<>();
        for (UUID channelUuid : referenced) {
            Optional<Integration> oChannel = integrationRepo.findById(channelUuid);
            if (oChannel.isEmpty()) {
                notFound.add(channelUuid);
                continue;
            }
            IntegrationData cd = parseChannelData(oChannel.get());
            // Same-org AND really-a-channel discriminator (non-null name +
            // a destination type) — a route can't point at a legacy CI
            // integration row that happens to share the org.
            if (cd == null || cd.getOrg() == null || !cd.getOrg().equals(org)
                    || cd.getName() == null
                    || !IntegrationData.NOTIFICATION_DESTINATION_TYPES.contains(cd.getType())) {
                wrongOrg.add(channelUuid);
            }
        }
        if (!notFound.isEmpty()) {
            throw new RelizaException("Routes reference unknown channels: " + notFound);
        }
        if (!wrongOrg.isEmpty()) {
            throw new RelizaException("Routes reference channels in a different org: " + wrongOrg);
        }
    }

    /**
     * Phase 13b — same shape as {@link #validateReferencedChannels} but
     * for channel-group references. Resolves each group, rejects the
     * save when any group is missing or belongs to a different org.
     */
    private void validateReferencedGroups(UUID org, Set<UUID> referenced) throws RelizaException {
        if (referenced.isEmpty()) return;
        List<UUID> notFound = new ArrayList<>();
        List<UUID> wrongOrg = new ArrayList<>();
        for (UUID groupUuid : referenced) {
            Optional<NotificationChannelGroup> oGroup = channelGroupRepo.findById(groupUuid);
            if (oGroup.isEmpty()) {
                notFound.add(groupUuid);
                continue;
            }
            NotificationChannelGroupData gd = parseGroupData(oGroup.get());
            if (gd == null || gd.org() == null || !gd.org().equals(org)) {
                wrongOrg.add(groupUuid);
            }
        }
        if (!notFound.isEmpty()) {
            throw new RelizaException("Routes reference unknown channelGroups: " + notFound);
        }
        if (!wrongOrg.isEmpty()) {
            throw new RelizaException(
                    "Routes reference channelGroups in a different org: " + wrongOrg);
        }
    }

    private IntegrationData parseChannelData(Integration channel) {
        if (channel.getRecordData() == null) return null;
        try {
            return IntegrationData.dataFromRecord(channel);
        } catch (RuntimeException e) {
            log.warn("Failed to parse channel {} record_data: {}", channel.getUuid(), e.getMessage());
            return null;
        }
    }

    private NotificationChannelGroupData parseGroupData(NotificationChannelGroup group) {
        if (group.getRecordData() == null) return null;
        try {
            return Utils.OM.convertValue(group.getRecordData(), NotificationChannelGroupData.class);
        } catch (RuntimeException e) {
            log.warn("Failed to parse channel group {} record_data: {}",
                    group.getUuid(), e.getMessage());
            return null;
        }
    }

    private NotificationSubscriptionData parseRecordData(NotificationSubscription sub) {
        if (sub == null || sub.getRecordData() == null) return null;
        try {
            return Utils.OM.convertValue(sub.getRecordData(), NotificationSubscriptionData.class);
        } catch (RuntimeException e) {
            log.warn("Failed to parse subscription {} record_data: {}", sub.getUuid(), e.getMessage());
            return null;
        }
    }
}
