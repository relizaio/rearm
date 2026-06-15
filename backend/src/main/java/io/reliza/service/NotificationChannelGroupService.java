/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.util.ArrayList;
import java.util.Collections;
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
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.NotificationChannelGroupRepository;
import io.reliza.service.AuditService;
import lombok.extern.slf4j.Slf4j;

/**
 * CRUD service for {@link NotificationChannelGroup} — Phase 13.
 *
 * <p>Mirrors {@link NotificationChannelService} structurally:
 * upsert / setStatus-equivalent (n/a here, groups have no status) /
 * delete / list, all with audit emission on mutating ops.
 *
 * <h3>Validation rules</h3>
 * <ul>
 *   <li>{@code org} and non-blank {@code name} are required.</li>
 *   <li>{@code channels} list is required + non-empty. A group with
 *       zero channels is meaningless and would silently no-op every
 *       subscription that referenced it.</li>
 *   <li>No duplicates within the {@code channels} list.</li>
 *   <li>Every channel UUID must resolve to a row in the same
 *       {@code org}. Cross-org membership is rejected.</li>
 *   <li>{@link NotificationChannelService#assertRecordDataSize} cap
 *       applies to the serialized JSONB.</li>
 * </ul>
 */
@Service
@Slf4j
public class NotificationChannelGroupService {

    @Autowired
    private NotificationChannelGroupRepository groupRepo;

    @Autowired
    private IntegrationRepository integrationRepo;

    @Autowired
    private AuditService auditService;

    /**
     * Create-or-update. {@code uuid == null} on create. The persisted
     * row is returned so the caller can surface its uuid / revision
     * through the GraphQL response.
     */
    /** Convenience overload — kept for callers that don't need the optimistic-lock gate. */
    public NotificationChannelGroup upsertGroup(UUID uuid, NotificationChannelGroupData seed,
            WhoUpdated wu) throws RelizaException {
        return upsertGroup(uuid, /*expectedRevision*/ null, seed, wu);
    }

    @Transactional
    public NotificationChannelGroup upsertGroup(UUID uuid, Integer expectedRevision,
            NotificationChannelGroupData seed, WhoUpdated wu) throws RelizaException {
        if (seed == null) throw new RelizaException("Group input is required");
        validateSeed(seed);

        NotificationChannelGroup target;
        if (uuid != null) {
            Optional<NotificationChannelGroup> existing = groupRepo.findById(uuid);
            if (existing.isEmpty()) {
                throw new RelizaException("Channel group not found: " + uuid);
            }
            target = existing.get();
            // Cross-org tamper guard: an existing row's org must match
            // the seed's. Lets a caller rename a group but not reparent
            // it across orgs.
            NotificationChannelGroupData existingData = parseRecordData(target);
            if (existingData != null && existingData.org() != null
                    && !existingData.org().equals(seed.org())) {
                throw new RelizaException("Channel group " + uuid
                        + " does not belong to org " + seed.org());
            }
            NotificationChannelService.assertExpectedRevision(target.getRevision(),
                    expectedRevision, "Channel group", seed.name());
            auditService.createAndSaveAuditRecord(TableName.NOTIFICATION_CHANNEL_GROUP, target);
        } else {
            target = new NotificationChannelGroup();
            target.setUuid(UUID.randomUUID());
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> recordData = Utils.OM.convertValue(seed, Map.class);
        NotificationChannelService.assertRecordDataSize(recordData, "channel group");
        target.setRecordData(recordData);
        target = (NotificationChannelGroup) WhoUpdated.injectWhoUpdatedData(target, wu);
        NotificationChannelGroup saved;
        try {
            saved = groupRepo.save(target);
        } catch (ObjectOptimisticLockingFailureException e) {
            // See NotificationChannelService.upsertChannel for the
            // pre-check vs. @Version-race split rationale.
            log.info("Optimistic-lock race on channel-group upsert (org={}, name={}): {}",
                    seed.org(), seed.name(), e.getMessage());
            throw new RelizaException("Conflict: Channel group \"" + seed.name()
                    + "\" was edited by another user — please reload and retry");
        }
        log.info("Upserted notification_channel_group org={} group={} name={} channelCount={}",
                seed.org(), saved.getUuid(), seed.name(), seed.channels().size());
        return saved;
    }

    /**
     * Hard delete. Audit emitted before deletion so the row's last
     * state is preserved. Subscriptions that referenced the group keep
     * their reference UUID; the fan-out treats a missing group as
     * "no members" and silently no-ops that group from the route's
     * resolution. (No cascade — operators may want to recreate a group
     * with the same name and the existing references should not re-link
     * automatically.)
     */
    @Transactional
    public void deleteGroup(UUID uuid) throws RelizaException {
        if (uuid == null) throw new RelizaException("uuid is required");
        Optional<NotificationChannelGroup> existing = groupRepo.findById(uuid);
        if (existing.isEmpty()) return;
        auditService.createAndSaveAuditRecord(TableName.NOTIFICATION_CHANNEL_GROUP, existing.get());
        groupRepo.deleteById(uuid);
        log.info("Deleted notification_channel_group {}", uuid);
    }

    public Optional<NotificationChannelGroup> getGroup(UUID uuid) {
        if (uuid == null) return Optional.empty();
        return groupRepo.findById(uuid);
    }

    public List<NotificationChannelGroup> listByOrg(UUID orgUuid) {
        if (orgUuid == null) return Collections.emptyList();
        return groupRepo.findByOrg(orgUuid);
    }

    /**
     * Resolve a list of group UUIDs to their member channel UUIDs.
     * Missing groups (deleted mid-fan-out, or never existed) are
     * silently skipped — see {@link #deleteGroup} for the rationale.
     * Returns a deduplicated list preserving first-seen order, so the
     * caller can merge with the route's direct {@code channels} list
     * without producing duplicate delivery rows.
     */
    public List<UUID> resolveChannelUuids(Iterable<UUID> groupUuids) {
        if (groupUuids == null) return Collections.emptyList();
        Set<UUID> seen = new HashSet<>();
        List<UUID> out = new ArrayList<>();
        for (UUID groupUuid : groupUuids) {
            if (groupUuid == null) continue;
            Optional<NotificationChannelGroup> g = groupRepo.findById(groupUuid);
            if (g.isEmpty()) continue;
            NotificationChannelGroupData data = parseRecordData(g.get());
            if (data == null || data.channels() == null) continue;
            for (UUID ch : data.channels()) {
                if (ch != null && seen.add(ch)) out.add(ch);
            }
        }
        return out;
    }

    /** Pre-save validation. Throws on any rule violation; never silently corrects. */
    private void validateSeed(NotificationChannelGroupData seed) throws RelizaException {
        if (seed.org() == null) throw new RelizaException("org is required");
        if (StringUtils.isBlank(seed.name())) throw new RelizaException("name is required");
        if (seed.channels() == null || seed.channels().isEmpty()) {
            throw new RelizaException("Channel group must contain at least one channel");
        }
        Set<UUID> seenChannels = new HashSet<>();
        for (UUID channelUuid : seed.channels()) {
            if (channelUuid == null) {
                throw new RelizaException("Channel group cannot contain null channel UUIDs");
            }
            if (!seenChannels.add(channelUuid)) {
                throw new RelizaException(
                        "Channel group has duplicate channel UUID: " + channelUuid);
            }
            // Resolve every channel + verify same-org membership and that
            // it really is a notification-channel integration (non-null
            // name AND a destination type). A group that points at a
            // deleted, cross-org, or non-channel integration is a
            // save-time error, not a fan-out-time surprise.
            Optional<Integration> channel = integrationRepo.findById(channelUuid);
            if (channel.isEmpty()) {
                throw new RelizaException("Channel " + channelUuid + " not found");
            }
            IntegrationData channelData = parseChannelData(channel.get());
            if (channelData == null || channelData.getOrg() == null
                    || !channelData.getOrg().equals(seed.org())
                    || channelData.getName() == null
                    || !IntegrationData.NOTIFICATION_DESTINATION_TYPES.contains(channelData.getType())) {
                throw new RelizaException("Channel " + channelUuid
                        + " does not belong to org " + seed.org());
            }
        }
    }

    private NotificationChannelGroupData parseRecordData(NotificationChannelGroup group) {
        if (group == null || group.getRecordData() == null) return null;
        try {
            return Utils.OM.convertValue(group.getRecordData(), NotificationChannelGroupData.class);
        } catch (RuntimeException e) {
            log.warn("Failed to parse channel group {} record_data: {}",
                    group.getUuid(), e.getMessage());
            return null;
        }
    }

    private IntegrationData parseChannelData(Integration channel) {
        if (channel == null || channel.getRecordData() == null) return null;
        try {
            return IntegrationData.dataFromRecord(channel);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
