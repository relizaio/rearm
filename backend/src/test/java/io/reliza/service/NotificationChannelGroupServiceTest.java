/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.WhoUpdated;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationChannelGroup;
import io.reliza.model.dto.notifications.NotificationChannelGroupData;
import io.reliza.repositories.NotificationChannelGroupRepository;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.service.AuditService;

/**
 * Phase 13a — service-layer scaffolding for {@link NotificationChannelGroup}.
 * Exercises validation, audit emission, and the resolve-to-channel-uuids
 * fan-out helper. GraphQL/fetcher/fan-out integration land in Phase 13b
 * and are tested there.
 */
class NotificationChannelGroupServiceTest {

    private NotificationChannelGroupRepository groupRepo;
    private IntegrationRepository integrationRepo;
    private AuditService auditService;
    private NotificationChannelGroupService service;

    @BeforeEach
    void wireMocks() throws Exception {
        groupRepo = mock(NotificationChannelGroupRepository.class);
        integrationRepo = mock(IntegrationRepository.class);
        auditService = mock(AuditService.class);
        when(groupRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new NotificationChannelGroupService();
        inject("groupRepo", groupRepo);
        inject("integrationRepo", integrationRepo);
        inject("auditService", auditService);
    }

    private void inject(String field, Object value) throws Exception {
        Field f = NotificationChannelGroupService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    private Integration stubChannel(UUID channelUuid, UUID orgUuid) {
        Integration ch = new Integration();
        ch.setUuid(channelUuid);
        IntegrationData data = new IntegrationData();
        data.setUuid(channelUuid);
        data.setIdentifier(channelUuid.toString());
        data.setOrg(orgUuid);
        data.setName("ch-" + channelUuid);
        data.setType(IntegrationType.SLACK);
        data.setIsEnabled(Boolean.TRUE);
        data.setSecret("ENC:secret");
        data.setParameters(new java.util.HashMap<>());
        ch.setRecordData(Utils.dataToRecord(data));
        return ch;
    }

    @Test
    void createSucceedsWithSameOrgChannels() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        when(integrationRepo.findById(ch1)).thenReturn(Optional.of(stubChannel(ch1, orgUuid)));
        when(integrationRepo.findById(ch2)).thenReturn(Optional.of(stubChannel(ch2, orgUuid)));

        NotificationChannelGroupData seed = new NotificationChannelGroupData(
                orgUuid, null, "security-oncall", List.of(ch1, ch2));
        NotificationChannelGroup saved = service.upsertGroup(
                null, seed, WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        assertNotNull(saved.getUuid());
        NotificationChannelGroupData persisted = Utils.OM.convertValue(
                saved.getRecordData(), NotificationChannelGroupData.class);
        assertEquals("security-oncall", persisted.name());
        assertEquals(2, persisted.channels().size());
        // No prior row = no audit on create
        verify(auditService, never()).createAndSaveAuditRecord(any(), any());
    }

    @Test
    void createRejectsNullSeed() {
        RelizaException ex = assertThrows(RelizaException.class,
                () -> service.upsertGroup(null, null,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(ex.getMessage().toLowerCase().contains("input is required"));
    }

    @Test
    void createRejectsBlankName() {
        UUID orgUuid = UUID.randomUUID();
        NotificationChannelGroupData seed = new NotificationChannelGroupData(
                orgUuid, null, "  ", List.of(UUID.randomUUID()));
        RelizaException ex = assertThrows(RelizaException.class,
                () -> service.upsertGroup(null, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(ex.getMessage().toLowerCase().contains("name"));
    }

    @Test
    void createRejectsEmptyChannelsList() {
        UUID orgUuid = UUID.randomUUID();
        NotificationChannelGroupData seed = new NotificationChannelGroupData(
                orgUuid, null, "empty", List.of());
        RelizaException ex = assertThrows(RelizaException.class,
                () -> service.upsertGroup(null, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(ex.getMessage().toLowerCase().contains("at least one"));
    }

    @Test
    void createRejectsDuplicateChannelUuid() {
        UUID orgUuid = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        when(integrationRepo.findById(ch1)).thenReturn(Optional.of(stubChannel(ch1, orgUuid)));

        NotificationChannelGroupData seed = new NotificationChannelGroupData(
                orgUuid, null, "dupes", List.of(ch1, ch1));
        RelizaException ex = assertThrows(RelizaException.class,
                () -> service.upsertGroup(null, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(ex.getMessage().toLowerCase().contains("duplicate"));
    }

    @Test
    void createRejectsMissingChannel() {
        UUID orgUuid = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        when(integrationRepo.findById(ch1)).thenReturn(Optional.empty());

        NotificationChannelGroupData seed = new NotificationChannelGroupData(
                orgUuid, null, "missing", List.of(ch1));
        RelizaException ex = assertThrows(RelizaException.class,
                () -> service.upsertGroup(null, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(ex.getMessage().toLowerCase().contains("not found"));
    }

    @Test
    void createRejectsCrossOrgChannel() {
        UUID orgUuid = UUID.randomUUID();
        UUID otherOrgUuid = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        when(integrationRepo.findById(ch1)).thenReturn(Optional.of(stubChannel(ch1, otherOrgUuid)));

        NotificationChannelGroupData seed = new NotificationChannelGroupData(
                orgUuid, null, "cross-org", List.of(ch1));
        RelizaException ex = assertThrows(RelizaException.class,
                () -> service.upsertGroup(null, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(ex.getMessage().toLowerCase().contains("does not belong"));
    }

    @Test
    void updateEmitsAuditAndRejectsCrossOrgReparent() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID otherOrgUuid = UUID.randomUUID();
        UUID groupUuid = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        when(integrationRepo.findById(ch1)).thenReturn(Optional.of(stubChannel(ch1, orgUuid)));

        NotificationChannelGroup existing = new NotificationChannelGroup();
        existing.setUuid(groupUuid);
        @SuppressWarnings("unchecked")
        Map<String, Object> existingData = Utils.OM.convertValue(
                new NotificationChannelGroupData(orgUuid, null, "orig", List.of(ch1)),
                Map.class);
        existing.setRecordData(existingData);
        when(groupRepo.findById(groupUuid)).thenReturn(Optional.of(existing));

        // Same org, rename allowed
        NotificationChannelGroupData renamed = new NotificationChannelGroupData(
                orgUuid, null, "renamed", List.of(ch1));
        service.upsertGroup(groupUuid, renamed,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));
        verify(auditService).createAndSaveAuditRecord(
                eq(TableName.NOTIFICATION_CHANNEL_GROUP), eq(existing));

        // Cross-org reparent rejected
        NotificationChannelGroupData reparent = new NotificationChannelGroupData(
                otherOrgUuid, null, "stolen", List.of(ch1));
        RelizaException ex = assertThrows(RelizaException.class,
                () -> service.upsertGroup(groupUuid, reparent,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(ex.getMessage().toLowerCase().contains("does not belong"));
    }

    @Test
    void updateOfMissingGroupThrows() {
        UUID groupUuid = UUID.randomUUID();
        UUID orgUuid = UUID.randomUUID();
        when(groupRepo.findById(groupUuid)).thenReturn(Optional.empty());

        NotificationChannelGroupData seed = new NotificationChannelGroupData(
                orgUuid, null, "n", List.of(UUID.randomUUID()));
        RelizaException ex = assertThrows(RelizaException.class,
                () -> service.upsertGroup(groupUuid, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(ex.getMessage().toLowerCase().contains("not found"));
    }

    @Test
    void deleteEmitsAuditAndDeletes() throws Exception {
        UUID groupUuid = UUID.randomUUID();
        NotificationChannelGroup existing = new NotificationChannelGroup();
        existing.setUuid(groupUuid);
        when(groupRepo.findById(groupUuid)).thenReturn(Optional.of(existing));

        service.deleteGroup(groupUuid);

        verify(auditService).createAndSaveAuditRecord(
                eq(TableName.NOTIFICATION_CHANNEL_GROUP), eq(existing));
        verify(groupRepo).deleteById(groupUuid);
    }

    @Test
    void deleteOfMissingGroupIsNoOp() throws Exception {
        UUID groupUuid = UUID.randomUUID();
        when(groupRepo.findById(groupUuid)).thenReturn(Optional.empty());

        service.deleteGroup(groupUuid);

        verify(auditService, never()).createAndSaveAuditRecord(any(), any());
        verify(groupRepo, never()).deleteById(any());
    }

    @Test
    void deleteRejectsNullUuid() {
        RelizaException ex = assertThrows(RelizaException.class,
                () -> service.deleteGroup(null));
        assertTrue(ex.getMessage().toLowerCase().contains("uuid"));
    }

    @Test
    void resolveChannelUuidsExpandsAndDedupsAcrossGroups() {
        UUID orgUuid = UUID.randomUUID();
        UUID group1 = UUID.randomUUID();
        UUID group2 = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        UUID ch3 = UUID.randomUUID();

        NotificationChannelGroup g1 = new NotificationChannelGroup();
        g1.setUuid(group1);
        @SuppressWarnings("unchecked")
        Map<String, Object> g1Data = Utils.OM.convertValue(
                new NotificationChannelGroupData(orgUuid, null, "g1", List.of(ch1, ch2)),
                Map.class);
        g1.setRecordData(g1Data);

        NotificationChannelGroup g2 = new NotificationChannelGroup();
        g2.setUuid(group2);
        @SuppressWarnings("unchecked")
        Map<String, Object> g2Data = Utils.OM.convertValue(
                // ch2 is shared with g1 — dedup should collapse it
                new NotificationChannelGroupData(orgUuid, null, "g2", List.of(ch2, ch3)),
                Map.class);
        g2.setRecordData(g2Data);

        when(groupRepo.findById(group1)).thenReturn(Optional.of(g1));
        when(groupRepo.findById(group2)).thenReturn(Optional.of(g2));

        List<UUID> resolved = service.resolveChannelUuids(List.of(group1, group2));

        assertEquals(List.of(ch1, ch2, ch3), resolved,
                "First-seen order preserved; ch2 dedup collapses the duplicate");
    }

    @Test
    void resolveChannelUuidsSilentlySkipsMissingGroup() {
        UUID liveGroup = UUID.randomUUID();
        UUID deadGroup = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        UUID orgUuid = UUID.randomUUID();

        NotificationChannelGroup live = new NotificationChannelGroup();
        live.setUuid(liveGroup);
        @SuppressWarnings("unchecked")
        Map<String, Object> liveData = Utils.OM.convertValue(
                new NotificationChannelGroupData(orgUuid, null, "live", List.of(ch1)),
                Map.class);
        live.setRecordData(liveData);

        when(groupRepo.findById(liveGroup)).thenReturn(Optional.of(live));
        when(groupRepo.findById(deadGroup)).thenReturn(Optional.empty());

        List<UUID> resolved = service.resolveChannelUuids(List.of(liveGroup, deadGroup));
        assertEquals(List.of(ch1), resolved);
    }

    @Test
    void resolveChannelUuidsHandlesNullAndEmpty() {
        assertTrue(service.resolveChannelUuids(null).isEmpty());
        assertTrue(service.resolveChannelUuids(List.of()).isEmpty());
    }

    @Test
    void updateWithMismatchedExpectedRevisionThrowsConflict() throws Exception {
        // Sibling test to the channel + subscription mismatch tests —
        // the Conflict: prefix is the load-bearing UI contract.
        UUID orgUuid = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        when(integrationRepo.findById(ch1)).thenReturn(Optional.of(stubChannel(ch1, orgUuid)));

        UUID groupUuid = UUID.randomUUID();
        NotificationChannelGroup existing = new NotificationChannelGroup();
        existing.setUuid(groupUuid);
        @SuppressWarnings("unchecked")
        Map<String, Object> existingData = Utils.OM.convertValue(
                new NotificationChannelGroupData(orgUuid, null, "g", List.of(ch1)),
                Map.class);
        existing.setRecordData(existingData);
        existing.setRevision(5);
        when(groupRepo.findById(groupUuid)).thenReturn(Optional.of(existing));

        NotificationChannelGroupData seed = new NotificationChannelGroupData(
                orgUuid, null, "g-renamed", List.of(ch1));

        RelizaException ex = assertThrows(RelizaException.class, () ->
                service.upsertGroup(groupUuid, /*expectedRevision*/ 3, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));

        assertTrue(ex.getMessage().startsWith("Conflict:"),
                "Expected Conflict: prefix, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Channel group"),
                "Expected Channel group entity label, got: " + ex.getMessage());
        verify(groupRepo, never()).save(any());
    }
}
