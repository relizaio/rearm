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
import static org.mockito.Mockito.doThrow;
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
import org.mockito.ArgumentCaptor;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.WhoUpdated;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationChannelGroup;
import io.reliza.model.dto.notifications.NotificationChannelGroupData;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.FilterConfig;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RouteConfig;
import io.reliza.model.NotificationSubscriptionStatus;
import io.reliza.repositories.NotificationChannelGroupRepository;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.NotificationSubscriptionRepository;
import io.reliza.service.AuditService;
import io.reliza.model.dto.notifications.EvaluationMode;

/**
 * Covers the Phase 3 subscription CRUD service. The interesting bits
 * are the validation gates that don't apply to channels:
 * <ul>
 *   <li>CEL filter validation via {@link NotificationCelEvaluator}.</li>
 *   <li>Route channel uuids resolve to existing channels in the same org.</li>
 *   <li>Required-field enforcement (eventTypes / routes non-empty).</li>
 *   <li>Status transitions; audit + delete semantics.</li>
 * </ul>
 */
class NotificationSubscriptionServiceTest {

    private NotificationSubscriptionRepository subRepo;
    private IntegrationRepository integrationRepo;
    private NotificationChannelGroupRepository channelGroupRepo;
    private AuditService auditService;
    private NotificationCelEvaluator celEvaluator;
    private NotificationSubscriptionService service;

    @BeforeEach
    void wireMocks() throws Exception {
        subRepo = mock(NotificationSubscriptionRepository.class);
        integrationRepo = mock(IntegrationRepository.class);
        channelGroupRepo = mock(NotificationChannelGroupRepository.class);
        auditService = mock(AuditService.class);
        celEvaluator = mock(NotificationCelEvaluator.class);
        when(subRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new NotificationSubscriptionService();
        inject("subscriptionRepo", subRepo);
        inject("integrationRepo", integrationRepo);
        inject("channelGroupRepo", channelGroupRepo);
        inject("auditService", auditService);
        inject("celEvaluator", celEvaluator);
    }

    private void inject(String field, Object value) throws Exception {
        Field f = NotificationSubscriptionService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    @Test
    void createValidatesAndPersists() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        stubChannelInOrg(channelUuid, orgUuid);

        NotificationSubscriptionData seed = subscriptionSeed(orgUuid, channelUuid,
                "event.severity == 'CRITICAL'");
        NotificationSubscription saved = service.upsertSubscription(
                null, seed, WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        assertNotNull(saved.getUuid());
        // CEL filter was validated server-side before save
        verify(celEvaluator).validate(eq("event.severity == 'CRITICAL'"),
                eq(EvaluationMode.PRESET));
        // New row → no audit on initial create
        verify(auditService, never()).createAndSaveAuditRecord(any(), any());
    }

    @Test
    void brokenCelExpressionRejectsAtSave() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        stubChannelInOrg(channelUuid, orgUuid);
        doThrow(new RelizaException("CEL compile failed: ..."))
                .when(celEvaluator).validate(any(), any());

        NotificationSubscriptionData seed = subscriptionSeed(orgUuid, channelUuid,
                "garbage CEL");

        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertSubscription(null, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("CEL compile failed"));
        // No save attempted after validation failure
        verify(subRepo, never()).save(any());
    }

    @Test
    void routeChannelMustExist() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID unknownChannelUuid = UUID.randomUUID();
        when(integrationRepo.findById(unknownChannelUuid)).thenReturn(Optional.empty());

        NotificationSubscriptionData seed = subscriptionSeed(orgUuid, unknownChannelUuid, null);
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertSubscription(null, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("unknown channels"),
                "Expected unknown-channel rejection, got: " + e.getMessage());
    }

    @Test
    void routeChannelMustBeInSameOrg() throws Exception {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID channelInOrgB = UUID.randomUUID();
        stubChannelInOrg(channelInOrgB, orgB);

        // Subscription belongs to org A but references a channel in org B
        NotificationSubscriptionData seed = subscriptionSeed(orgA, channelInOrgB, null);
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertSubscription(null, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("different org"),
                "Expected cross-org rejection, got: " + e.getMessage());
    }

    @Test
    void missingRequiredFieldsRejected() {
        UUID orgUuid = UUID.randomUUID();
        // Empty eventTypes
        NotificationSubscriptionData seed = new NotificationSubscriptionData(
                orgUuid, null, "test", NotificationSubscriptionStatus.ACTIVE,
                List.of() /* empty eventTypes */,
                null, List.of(new RouteConfig(null, null, null, List.of(UUID.randomUUID()))),
                null, null);

        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertSubscription(null, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("eventType"));
    }

    @Test
    void emptyRoutesRejected() {
        UUID orgUuid = UUID.randomUUID();
        NotificationSubscriptionData seed = new NotificationSubscriptionData(
                orgUuid, null, "test", NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES),
                null, List.of() /* empty routes */,
                null, null);

        assertThrows(RelizaException.class, () ->
                service.upsertSubscription(null, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
    }

    @Test
    void updateEmitsAuditBeforeSave() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID subUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        stubChannelInOrg(channelUuid, orgUuid);

        NotificationSubscription existing = stubSubscription(subUuid, orgUuid, channelUuid,
                NotificationSubscriptionStatus.ACTIVE);
        when(subRepo.findById(subUuid)).thenReturn(Optional.of(existing));

        NotificationSubscriptionData seed = subscriptionSeed(orgUuid, channelUuid, null);
        service.upsertSubscription(subUuid, seed,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        verify(auditService).createAndSaveAuditRecord(
                eq(TableName.NOTIFICATION_SUBSCRIPTION), eq(existing));
    }

    @Test
    void setStatusToCurrentValueIsNoOp() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID subUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        NotificationSubscription existing = stubSubscription(subUuid, orgUuid, channelUuid,
                NotificationSubscriptionStatus.ACTIVE);
        when(subRepo.findById(subUuid)).thenReturn(Optional.of(existing));

        NotificationSubscription saved = service.setSubscriptionStatus(subUuid,
                NotificationSubscriptionStatus.ACTIVE,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        verify(auditService, never()).createAndSaveAuditRecord(any(), any());
        verify(subRepo, never()).save(any());
        assertEquals(existing, saved);
    }

    @Test
    void setStatusToDifferentValueAuditsAndSaves() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID subUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        NotificationSubscription existing = stubSubscription(subUuid, orgUuid, channelUuid,
                NotificationSubscriptionStatus.ACTIVE);
        when(subRepo.findById(subUuid)).thenReturn(Optional.of(existing));

        service.setSubscriptionStatus(subUuid, NotificationSubscriptionStatus.DISABLED,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        verify(auditService).createAndSaveAuditRecord(
                eq(TableName.NOTIFICATION_SUBSCRIPTION), eq(existing));
        ArgumentCaptor<NotificationSubscription> captor =
                ArgumentCaptor.forClass(NotificationSubscription.class);
        verify(subRepo).save(captor.capture());
        NotificationSubscriptionData persisted = Utils.OM.convertValue(
                captor.getValue().getRecordData(), NotificationSubscriptionData.class);
        assertEquals(NotificationSubscriptionStatus.DISABLED, persisted.status());
    }

    @Test
    void deleteSubscriptionEmitsAuditBeforeDeletion() throws Exception {
        UUID subUuid = UUID.randomUUID();
        NotificationSubscription existing = stubSubscription(subUuid, UUID.randomUUID(),
                UUID.randomUUID(), NotificationSubscriptionStatus.ACTIVE);
        when(subRepo.findById(subUuid)).thenReturn(Optional.of(existing));

        service.deleteSubscription(subUuid);

        verify(auditService).createAndSaveAuditRecord(
                eq(TableName.NOTIFICATION_SUBSCRIPTION), eq(existing));
        verify(subRepo).deleteById(subUuid);
    }

    @Test
    void upsertWithSuppliedButNonexistentUuidIsRejected() throws Exception {
        // Layer-2 BLOCKER fix: matches AgentPolicyService / CommitterService /
        // WebhookService convention.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        UUID claimedUuid = UUID.randomUUID();
        stubChannelInOrg(channelUuid, orgUuid);
        when(subRepo.findById(claimedUuid)).thenReturn(Optional.empty());

        NotificationSubscriptionData seed = subscriptionSeed(orgUuid, channelUuid, null);
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertSubscription(claimedUuid, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("Subscription not found"),
                "Expected not-found rejection, got: " + e.getMessage());
        verify(subRepo, never()).save(any());
    }

    @Test
    void blankCelExpressionSkipsValidation() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        stubChannelInOrg(channelUuid, orgUuid);

        // No CEL filter — match-everything subscription
        NotificationSubscriptionData seed = new NotificationSubscriptionData(
                orgUuid, null, "match-all", NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES),
                new FilterConfig(EvaluationMode.PRESET, null, null /* blank CEL */),
                List.of(new RouteConfig(null, null, null, List.of(channelUuid))),
                null, null);

        service.upsertSubscription(null, seed,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        // Validator never called for a blank expression — match-everything path
        verify(celEvaluator, never()).validate(any(), any());
    }

    // ---------------- Phase 13b: channel-group route validation ----------------

    @Test
    void routeChannelGroupMustExist() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        UUID missingGroupUuid = UUID.randomUUID();
        stubChannelInOrg(channelUuid, orgUuid);
        when(channelGroupRepo.findById(missingGroupUuid)).thenReturn(Optional.empty());

        NotificationSubscriptionData seed = subscriptionSeedWithGroups(orgUuid,
                List.of(channelUuid), List.of(missingGroupUuid));
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertSubscription(null, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("unknown channelGroups"),
                "Expected unknown-channelGroup rejection, got: " + e.getMessage());
        verify(subRepo, never()).save(any());
    }

    @Test
    void routeChannelGroupMustBeInSameOrg() throws Exception {
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID channelInOrgA = UUID.randomUUID();
        UUID groupInOrgB = UUID.randomUUID();
        stubChannelInOrg(channelInOrgA, orgA);
        stubGroupInOrg(groupInOrgB, orgB);

        NotificationSubscriptionData seed = subscriptionSeedWithGroups(orgA,
                List.of(channelInOrgA), List.of(groupInOrgB));
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertSubscription(null, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("channelGroups in a different org"),
                "Expected cross-org rejection, got: " + e.getMessage());
        verify(subRepo, never()).save(any());
    }

    @Test
    void routeWithOnlyChannelGroupsIsAccepted() throws Exception {
        // A route may omit direct channels entirely as long as it carries
        // at least one channelGroup. The save-time gate accepts; fan-out
        // expands at runtime.
        UUID orgUuid = UUID.randomUUID();
        UUID groupUuid = UUID.randomUUID();
        stubGroupInOrg(groupUuid, orgUuid);

        NotificationSubscriptionData seed = subscriptionSeedWithGroups(orgUuid,
                List.of(), List.of(groupUuid));
        NotificationSubscription saved = service.upsertSubscription(null, seed,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));
        assertNotNull(saved.getUuid());
    }

    @Test
    void routeWithNoChannelsAndNoGroupsRejected() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        NotificationSubscriptionData seed = subscriptionSeedWithGroups(orgUuid,
                List.of(), List.of());
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertSubscription(null, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().toLowerCase().contains("at least one")
                        && e.getMessage().contains("channelGroup"),
                "Expected at-least-one-channel-or-channelGroup rejection, got: " + e.getMessage());
    }

    @Test
    void updateWithMismatchedExpectedRevisionThrowsConflict() throws Exception {
        // Sibling test to NotificationChannelServiceTest's mismatched-revision
        // coverage — same Conflict: prefix is the load-bearing UI contract.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        stubChannelInOrg(channelUuid, orgUuid);
        UUID subUuid = UUID.randomUUID();
        NotificationSubscription existing = stubSubscription(subUuid, orgUuid, channelUuid,
                NotificationSubscriptionStatus.ACTIVE);
        existing.setRevision(11);
        when(subRepo.findById(subUuid)).thenReturn(Optional.of(existing));

        NotificationSubscriptionData seed = subscriptionSeed(orgUuid, channelUuid, null);

        RelizaException ex = assertThrows(RelizaException.class, () ->
                service.upsertSubscription(subUuid, /*expectedRevision*/ 9, seed,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));

        assertTrue(ex.getMessage().startsWith("Conflict:"),
                "Expected Conflict: prefix, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("Subscription"),
                "Expected Subscription entity label, got: " + ex.getMessage());
        verify(subRepo, never()).save(any());
    }

    // ---------------- helpers ----------------

    private static NotificationSubscriptionData subscriptionSeed(UUID org, UUID channelUuid,
            String celExpression) {
        FilterConfig filter = celExpression != null
                ? new FilterConfig(EvaluationMode.PRESET, null, celExpression)
                : null;
        return new NotificationSubscriptionData(
                org, null, "test-sub", NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES),
                filter,
                List.of(new RouteConfig(null, null, null, List.of(channelUuid))),
                null, null);
    }

    private void stubChannelInOrg(UUID channelUuid, UUID orgUuid) {
        Integration channel = new Integration();
        channel.setUuid(channelUuid);
        IntegrationData data = new IntegrationData();
        data.setUuid(channelUuid);
        data.setIdentifier(channelUuid.toString());
        data.setOrg(orgUuid);
        data.setName("ch");
        data.setType(IntegrationType.SLACK);
        data.setIsEnabled(Boolean.TRUE);
        data.setSecret("ENC:secret");
        data.setParameters(new java.util.HashMap<>());
        channel.setRecordData(Utils.dataToRecord(data));
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(channel));
    }

    /**
     * Phase 13b helper. Builds a subscription seed whose single route
     * has the supplied direct channel list + channel-group reference
     * list. Same severity-gate-null shape as {@link #subscriptionSeed}.
     */
    private static NotificationSubscriptionData subscriptionSeedWithGroups(UUID org,
            List<UUID> channels, List<UUID> channelGroups) {
        return new NotificationSubscriptionData(
                org, null, "test-sub", NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES),
                null,
                List.of(new RouteConfig(null, null, null, channels, null, channelGroups)),
                null, null);
    }

    @SuppressWarnings("unchecked")
    private void stubGroupInOrg(UUID groupUuid, UUID orgUuid) {
        NotificationChannelGroup group = new NotificationChannelGroup();
        group.setUuid(groupUuid);
        NotificationChannelGroupData data = new NotificationChannelGroupData(
                orgUuid, null, "g", List.of(UUID.randomUUID()));
        group.setRecordData(Utils.OM.convertValue(data, Map.class));
        when(channelGroupRepo.findById(groupUuid)).thenReturn(Optional.of(group));
    }

    @SuppressWarnings("unchecked")
    private static NotificationSubscription stubSubscription(UUID subUuid, UUID org, UUID channelUuid,
            NotificationSubscriptionStatus status) {
        NotificationSubscription sub = new NotificationSubscription();
        sub.setUuid(subUuid);
        NotificationSubscriptionData data = new NotificationSubscriptionData(
                org, null, "test-sub", status,
                List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES),
                null,
                List.of(new RouteConfig(null, null, null, List.of(channelUuid))),
                null, null);
        sub.setRecordData(Utils.OM.convertValue(data, Map.class));
        return sub;
    }
}
