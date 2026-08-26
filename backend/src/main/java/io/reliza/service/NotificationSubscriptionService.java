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
import org.springframework.context.annotation.Lazy;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.model.TeamData;
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
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
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
    @Lazy
    private TeamService teamService;

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
        // Nobody gets to declare their own row team-managed through the public
        // path. The marker is what the CRUD guards trust, so a caller who could
        // set it could make a subscription that only a team can edit -- and no
        // team would agree it owns it.
        if (null != seed.managedByTeam()) {
            throw new RelizaException("managedByTeam is set by the team's own notification"
                    + " setting and cannot be assigned here");
        }
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
            // Guard the STORED row, not the incoming seed: a caller can simply
            // omit managedByTeam, and the question is who owns the row being
            // overwritten, not what the writer claims about it.
            requireNotTeamManaged(target, "edited");
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
        // A status flip IS an edit, and a team-managed row is edited on its team.
        // Without this an admin could disable a team's subscription from the
        // list, leaving the team's toggle claiming something untrue.
        requireNotTeamManaged(sub, "disabled or enabled");
        auditService.createAndSaveAuditRecord(TableName.NOTIFICATION_SUBSCRIPTION, sub);
        // CANONICAL constructor, not one of the back-compat overloads. Rebuilding
        // a stored row field by field through a shortened ctor is precisely how a
        // field gets silently dropped: the previous cut of this carried
        // ownedByTeam and lost managedByTeam, which would have orphaned the row
        // from its team and let the next team save materialise a SECOND one.
        NotificationSubscriptionData updated = new NotificationSubscriptionData(
                data.org(), data.resourceGroup(), data.name(), status,
                data.eventTypes(), data.filter(), data.routes(),
                data.dedupWindowMinutes(), data.rateLimit(),
                data.ownedByTeam(), data.managedByTeam());
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
        requireNotTeamManaged(oSub.get(), "deleted");
        auditService.createAndSaveAuditRecord(TableName.NOTIFICATION_SUBSCRIPTION, oSub.get());
        subscriptionRepo.deleteById(uuid);
        log.info("Deleted notification_subscription {}", uuid);
    }

    /**
     * A team-managed row is not editable or deletable through the ordinary CRUD
     * path -- the team's toggle owns it.
     *
     * <p>Refusing is the honest answer rather than a courtesy. Letting an
     * operator edit it leaves the team's setting describing something that is no
     * longer true, with nothing on the team to show the divergence; letting them
     * delete it leaves the toggle claiming a subscription that does not exist.
     * Either way the fix is on the team, so the message says so.
     */
    private void requireNotTeamManaged(NotificationSubscription sub, String verb) throws RelizaException {
        NotificationSubscriptionData data;
        try {
            data = Utils.OM.convertValue(sub.getRecordData(), NotificationSubscriptionData.class);
        } catch (RuntimeException e) {
            // Refuse rather than assume. The tolerant-read convention applies to
            // READS, where skipping a bad row costs only that row; this is a
            // write guard, and answering "unmanaged" to a question it could not
            // read is how a managed row gets deleted after an enum rename makes
            // it unparseable. Matches setSubscriptionStatus, which already
            // rejects an unparseable record_data outright.
            log.warn("Could not read subscription {} while checking team ownership: {}",
                    sub.getUuid(), e.getMessage());
            throw new RelizaException("Cannot verify ownership of subscription " + sub.getUuid()
                    + ": its stored data is unreadable");
        }
        if (null != data && data.isTeamManaged()) {
            throw new RelizaException("This subscription is managed by a team and cannot be "
                    + verb + " here. Change it on the team that owns it ("
                    + data.managedByTeam() + ").");
        }
    }

    /**
     * Create or update the subscription a team's owned-component toggle owns.
     *
     * <p>Separate entry point from {@link #upsertSubscription} because the two
     * have opposite guards: that one refuses to touch a managed row, this one
     * exists only to write it. Package-private on purpose -- {@code TeamService}
     * is the only legitimate caller, and a managed row that anything else can
     * write is a managed row in name only.
     *
     * <p>Runs in the caller's transaction, so a team save and its subscription
     * commit together or not at all. That is deliberate: the alternative is a
     * team whose toggle says "on" while no subscription exists, which no screen
     * would ever reveal. No {@code @Transactional} of its own: Spring's default
     * attribute source only sees public methods, so one here would be inert and
     * would suggest a boundary that does not exist. The real constraint on who
     * may call this is the managedByTeam check below, not the visibility --
     * there are over a hundred classes in this package.
     */
    NotificationSubscription upsertManagedSubscription(UUID existingUuid,
            NotificationSubscriptionData seed, WhoUpdated wu) throws RelizaException {
        if (null == seed || null == seed.managedByTeam()) {
            throw new RelizaException("A managed subscription must name the team that manages it");
        }
        validateSeed(seed);
        NotificationSubscription target;
        if (null != existingUuid) {
            Optional<NotificationSubscription> existing = subscriptionRepo.findById(existingUuid);
            if (existing.isEmpty()) {
                throw new RelizaException("Managed subscription not found: " + existingUuid);
            }
            target = existing.get();
            auditService.createAndSaveAuditRecord(TableName.NOTIFICATION_SUBSCRIPTION, target);
        } else {
            target = new NotificationSubscription();
            target.setUuid(UUID.randomUUID());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = Utils.OM.convertValue(seed, Map.class);
        // Same JSONB ceiling the operator path enforces. A generated seed is
        // unlikely to reach it, but "this caller is trusted" is how a size guard
        // stops being a guard.
        NotificationChannelService.assertRecordDataSize(recordData, "subscription");
        target.setRecordData(recordData);
        target = (NotificationSubscription) WhoUpdated.injectWhoUpdatedData(target, wu);
        return subscriptionRepo.save(target);
    }

    /**
     * The subscription a team's owned-component toggle owns, if any.
     *
     * <p>Lives here rather than on TeamService so the org predicate and the
     * parse stay with the aggregate that owns this table -- the team service
     * reaching into this repository directly is what let the first cut ship a
     * cross-tenant-unscoped query.
     */
    Optional<NotificationSubscription> findManagedFor(UUID org, UUID team) {
        if (null == org || null == team) return Optional.empty();
        return subscriptionRepo.findManagedByTeam(org, team);
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
        dryRunFilterAgainstEventTypes(seed.org(), seed.filter(), seed.eventTypes());
        validateRoutes(seed.org(), seed.routes());
        validateOwnedByTeam(seed.org(), seed.ownedByTeam());
    }

    /**
     * The ownership scope must resolve to a team in the SAME org.
     *
     * <p>Same reasoning as the route references: no foreign key backs the uuid,
     * so this is the integrity gate. It matters more here than for a route
     * target, because the failure is invisible rather than merely quiet -- a
     * scope naming a team that does not exist matches nothing, forever, and
     * looks exactly like "no events have happened yet". A cross-org uuid would
     * be worse: it would silently never match while appearing configured.
     *
     * <p>Archived teams are ACCEPTED here, deliberately. Scoping is a matching
     * rule, not a destination, and an archived team's ownership already reports
     * DEGRADED and so resolves to nothing at fan-out. Rejecting it at save time
     * would block an operator from editing an unrelated field on a subscription
     * whose team was archived after it was written.
     */
    private void validateOwnedByTeam(UUID org, UUID ownedByTeam) throws RelizaException {
        if (ownedByTeam == null) return;
        // Readable, not plain: TeamService is @Transactional at class level, so
        // an unreadable row escaping it would poison this save's transaction
        // before the message below could be produced.
        Optional<TeamData> otd = teamService.getReadableTeamData(ownedByTeam);
        if (otd.isEmpty()) {
            throw new RelizaException("ownedByTeam does not resolve to a team: " + ownedByTeam);
        }
        if (!org.equals(otd.get().getOrg())) {
            throw new RelizaException("ownedByTeam belongs to a different organization: " + ownedByTeam);
        }
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
     * Dry-run the filter against a representative sample of EACH selected event
     * type, refusing the save if it cannot be evaluated for one of them.
     *
     * <p>Closes the gap where an expression valid for one payload shape throws on
     * another -- e.g. referencing {@code event.affectedReleases} on an approval or
     * release event that carries none. At fan-out that throw is caught and the
     * event is skipped with NO delivery row (see {@link NotificationCelEvaluator}),
     * so the operator sees nothing -- indistinguishable from "no event matched".
     * Failing at save time turns an invisible mis-scope into an actionable error
     * that names the offending event type.
     *
     * <p>Scope and limits:
     * <ul>
     *   <li>ADVANCED (user-authored) expressions only. PRESET expressions are
     *       generated by the UI and are type-scoped there; a preset that failed on
     *       a selected type is a UI/generator concern, not something the operator
     *       can fix by editing the expression, so refusing their save would be a
     *       dead-end. Blank expressions and CE (no evaluator) are also no-ops.</li>
     *   <li>Deterministic evaluation errors only -- it cannot reproduce a genuine
     *       50ms wall-clock timeout under load.</li>
     *   <li>The sample carries a populated payload, so a reference to a field a
     *       type never has (e.g. {@code event.affectedReleases} on an approval
     *       event) is caught, but unguarded access to an OPTIONAL nested field
     *       that this type sometimes-but-not-always carries at runtime (e.g.
     *       {@code event.affectedComponent} on a component-less CVE) is not -- use
     *       a {@code has()} guard for those. Syntactic validity is covered by
     *       {@link #validateFilter}.</li>
     * </ul>
     */
    private void dryRunFilterAgainstEventTypes(UUID org, FilterConfig filter,
            List<NotificationEventType> eventTypes) throws RelizaException {
        if (filter == null || StringUtils.isBlank(filter.celExpression()) || celEvaluator == null) return;
        if (eventTypes == null || eventTypes.isEmpty()) return;
        EvaluationMode mode = filter.mode() != null ? filter.mode() : EvaluationMode.PRESET;
        if (mode != EvaluationMode.ADVANCED) return;
        String cel = filter.celExpression();
        for (NotificationEventType et : eventTypes) {
            NotificationOutboxEvent sample = SyntheticEventTemplates.sampleEventForType(org, et);
            try {
                celEvaluator.evaluate(cel, mode, sample);
            } catch (RelizaException | RuntimeException e) {
                // RelizaException is the evaluator's normal failure path; a raw
                // RuntimeException can still escape its final rethrow -- either way
                // this is an unevaluatable filter, not a 500.
                throw new RelizaException("The filter cannot be evaluated against " + et.name()
                        + " events: " + e.getMessage()
                        + ". Adjust the filter or remove " + et.name()
                        + " from this subscription's event types.");
            }
        }
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
        Set<UUID> referencedTeams = new HashSet<>();
        // T4a: an owner-routed route contributes no referenced UUID at all --
        // its target is resolved at fan-out. Tracked separately so the
        // subscription-level gate below sees it as a real target.
        boolean anyOwnerRouting = false;
        for (RouteConfig route : routes) {
            if (route == null) continue;
            // T3: a teams-only route is legitimate -- naming a team INSTEAD of a
            // channel is the whole point (the team's channel is resolved at
            // fan-out). Omitting teams from this gate made the Teams target
            // unusable without also naming a channel, defeating the feature.
            boolean hasChannels = route.channels() != null && !route.channels().isEmpty();
            boolean hasGroups = route.channelGroups() != null && !route.channelGroups().isEmpty();
            boolean hasTeams = route.teams() != null && !route.teams().isEmpty();
            // T4a: same reasoning one step further. An owner-routed route names
            // NO target at save time by design -- the target is whatever owns the
            // affected component when the event fires. Leaving it out of this gate
            // would make the feature unsaveable without also naming a channel,
            // which is exactly the dead end T3 hit.
            boolean hasOwnerTarget = Boolean.TRUE.equals(route.notifyComponentOwner());
            if (hasOwnerTarget) anyOwnerRouting = true;
            if (!hasChannels && !hasGroups && !hasTeams && !hasOwnerTarget) {
                throw new RelizaException(
                        "route must reference at least one channel, channelGroup, team,"
                        + " or the component owner");
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
            if (route.teams() != null) {
                for (UUID teamUuid : route.teams()) {
                    if (teamUuid != null) referencedTeams.add(teamUuid);
                }
            }
        }
        if (referencedChannels.isEmpty() && referencedGroups.isEmpty() && referencedTeams.isEmpty()
                && !anyOwnerRouting) {
            throw new RelizaException(
                    "subscription routes must reference at least one channel, channelGroup, team,"
                    + " or the component owner");
        }
        validateReferencedChannels(org, referencedChannels);
        validateReferencedGroups(org, referencedGroups);
        validateReferencedTeams(org, referencedTeams);
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
     * T3 -- same contract as {@link #validateReferencedGroups}: a route may only
     * name teams that exist and belong to this org. Without this the fan-out's
     * cross-org channel guard was the SOLE control, and that guard is conditional
     * (a channel with a null org passes it) -- so this restores the two-layer
     * property channels and groups already have.
     *
     * <p><b>Deliberately does NOT reject a DEACTIVATED team.</b> Writes are
     * whole-list replace, so rejecting one would lock the operator out of the
     * subscription entirely: they could neither keep the team (rejected here) nor
     * drop it (an emptied route is rejected above), with no error hinting at the
     * only escape. A deactivated team is already harmless --
     * {@code TeamService.resolveTeamChannelUuids} skips non-ACTIVE teams so
     * nothing is delivered, and the route editor labels it "(deactivated)" so it
     * can be seen and removed. Verified live: the rejection made
     * deactivate-then-clean-up impossible.
     */
    private void validateReferencedTeams(UUID org, Set<UUID> referenced) throws RelizaException {
        if (referenced.isEmpty()) return;
        List<UUID> notFound = new ArrayList<>();
        List<UUID> wrongOrg = new ArrayList<>();
        for (UUID teamUuid : referenced) {
            // Readable, not plain: TeamService is @Transactional at class level,
            // so an unreadable row escaping it would poison this save's
            // transaction before the message below could be produced.
            Optional<TeamData> otd = teamService.getReadableTeamData(teamUuid);
            if (otd.isEmpty()) {
                notFound.add(teamUuid);
                continue;
            }
            TeamData td = otd.get();
            if (td.getOrg() == null || !td.getOrg().equals(org)) {
                wrongOrg.add(teamUuid);
            }
        }
        if (!notFound.isEmpty()) {
            throw new RelizaException("Routes reference unknown teams: " + notFound);
        }
        if (!wrongOrg.isEmpty()) {
            throw new RelizaException("Routes reference teams in a different org: " + wrongOrg);
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
