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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;

import io.reliza.common.Utils;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationDelivery;
import io.reliza.model.NotificationDeliveryStatus;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.model.NotificationSeverity;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.NotificationSubscriptionStatus;
import io.reliza.model.dto.notifications.AffectedRelease;
import io.reliza.model.dto.notifications.ApprovalRequestEntryRef;
import io.reliza.model.dto.notifications.ApprovalRequestedPayload;
import io.reliza.model.dto.notifications.ApprovalResolvedPayload;
import io.reliza.model.dto.notifications.EvaluationMode;
import io.reliza.model.dto.notifications.NewVulnAffectsReleasesPayload;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.FilterConfig;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RouteConfig;
import io.reliza.model.dto.notifications.ReleaseRef;
import io.reliza.repositories.ArtifactRepository;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.repositories.NotificationSubscriptionRepository;
import io.reliza.service.oss.OssPerspectiveService;

/**
 * TPF-158: the fan-out gate that keeps a board's document rounds (releases of BOARD_DOCUMENT
 * components) out of every subscription that did not opt in with {@code includeBoardDocuments},
 * on every release-shaped event type, ahead of the CEL filter, the ownership scope and the routes;
 * and the per-user approval paths that stay outside it. Rows 9-19 of the TPF-158 architecture,
 * on the same Mockito rig as {@link NotificationFanOutServiceTest} (no Spring).
 */
class NotificationFanOutDocumentGateTest {

    private static final String DOC = "BOARD_DOCUMENT";

    private NotificationOutboxEventRepository outboxRepo;
    private NotificationSubscriptionRepository subscriptionRepo;
    private NotificationDeliveryRepository deliveryRepo;
    private NotificationCelEvaluator celEvaluator;
    private NotificationChannelService channelService;
    private NotificationReadService readService;
    private ComponentOwnershipService componentOwnershipService;
    private Map<UUID, UUID> channelOrgs;
    private NotificationFanOutService fanOut;

    @BeforeEach
    void wireMocks() throws Exception {
        outboxRepo = mock(NotificationOutboxEventRepository.class);
        subscriptionRepo = mock(NotificationSubscriptionRepository.class);
        deliveryRepo = mock(NotificationDeliveryRepository.class);
        celEvaluator = mock(NotificationCelEvaluator.class);
        channelService = mock(NotificationChannelService.class);
        readService = mock(NotificationReadService.class);
        componentOwnershipService = mock(ComponentOwnershipService.class);
        TeamService teamService = mock(TeamService.class);
        NotificationChannelGroupService channelGroupService = mock(NotificationChannelGroupService.class);
        channelOrgs = new HashMap<>();
        when(teamService.resolveTeamChannelUuids(any(), any())).thenReturn(List.of());
        when(channelGroupService.resolveChannelUuids(any())).thenReturn(List.of());
        when(channelService.getChannel(any())).thenAnswer(inv -> {
            UUID uuid = inv.getArgument(0);
            UUID org = channelOrgs.get(uuid);
            return org == null ? Optional.empty() : Optional.of(channelInOrg(uuid, org));
        });
        OssPerspectiveService ossPerspectiveService = mock(OssPerspectiveService.class);
        when(ossPerspectiveService.assignablePerspectiveUuids(any(), any()))
                .thenAnswer(inv -> new ArrayList<UUID>(inv.<Collection<UUID>>getArgument(1)));

        fanOut = new NotificationFanOutService();
        inject("outboxRepo", outboxRepo);
        inject("subscriptionRepo", subscriptionRepo);
        inject("deliveryRepo", deliveryRepo);
        inject("celEvaluator", celEvaluator);
        inject("artifactRepo", mock(ArtifactRepository.class));
        inject("sharedReleaseService", mock(SharedReleaseService.class));
        inject("branchService", mock(BranchService.class));
        inject("getComponentService", mock(GetComponentService.class));
        inject("channelGroupService", channelGroupService);
        inject("channelService", channelService);
        inject("readService", readService);
        inject("teamService", teamService);
        inject("componentOwnershipService", componentOwnershipService);
        inject("vulnerabilityRecordService", mock(VulnerabilityRecordService.class));
        inject("transactionManager", mock(PlatformTransactionManager.class));
        inject("ossPerspectiveService", ossPerspectiveService);
        inject("self", fanOut);
    }

    // ---------- rows 9-13: default, opted in, explicit false, software, older payload ----------

    @Test
    void documentReleaseSkipsADefaultSubscription() {
        NotificationOutboxEvent event = ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED, DOC);
        NotificationSubscription sub = subscriptionFor(event.getOrg(),
                NotificationEventType.RELEASE_CREATED, "", UUID.randomUUID(), null);

        drain(event, sub);

        verify(deliveryRepo, never()).save(any());
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    @Test
    void documentReleaseDeliversToAnOptedInSubscription() {
        NotificationOutboxEvent event = ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED, DOC);
        UUID channel = UUID.randomUUID();
        NotificationSubscription sub = subscriptionFor(event.getOrg(),
                NotificationEventType.RELEASE_CREATED, "", channel, Boolean.TRUE);

        drain(event, sub);

        List<NotificationDelivery> rows = savedRows(1);
        assertEquals(channel, rows.get(0).getChannelUuid());
        assertEquals(sub.getUuid(), rows.get(0).getSubscriptionUuid());
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    @Test
    void anExplicitFalseIsExcluded() {
        NotificationOutboxEvent event = ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED, DOC);
        NotificationSubscription sub = subscriptionFor(event.getOrg(),
                NotificationEventType.RELEASE_CREATED, "", UUID.randomUUID(), Boolean.FALSE);

        drain(event, sub);

        verify(deliveryRepo, never()).save(any());
    }

    @Test
    void softwareReleasesAreUnaffected() {
        NotificationOutboxEvent event = ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED, "GENERIC");
        UUID defaultChannel = UUID.randomUUID();
        UUID optedInChannel = UUID.randomUUID();
        NotificationSubscription byDefault = subscriptionFor(event.getOrg(),
                NotificationEventType.RELEASE_CREATED, "", defaultChannel, null);
        NotificationSubscription optedIn = subscriptionFor(event.getOrg(),
                NotificationEventType.RELEASE_CREATED, "", optedInChannel, Boolean.TRUE);

        drain(event, byDefault, optedIn);

        List<NotificationDelivery> rows = savedRows(2);
        assertEquals(Set.of(defaultChannel, optedInChannel),
                Set.of(rows.get(0).getChannelUuid(), rows.get(1).getChannelUuid()));
    }

    @Test
    void anOlderPayloadWithoutComponentKindIsNotADocument() {
        NotificationOutboxEvent event = ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED,
                (String) null);
        @SuppressWarnings("unchecked")
        Map<String, Object> release = (Map<String, Object>) event.getRecordData().get("release");
        assertNotNull(release);
        if (release.containsKey("componentKind")) {
            // Jackson writes the null component as an explicit null; an older row has no key at all.
            release.remove("componentKind");
        }
        assertFalse(release.containsKey("componentKind"), "the payload carries no componentKind key");
        UUID channel = UUID.randomUUID();
        NotificationSubscription sub = subscriptionFor(event.getOrg(),
                NotificationEventType.RELEASE_CREATED, "", channel, null);

        drain(event, sub);

        assertEquals(channel, savedRows(1).get(0).getChannelUuid());
    }

    // ---------- row 14: the gate sits before the CEL filter ----------

    @Test
    void theGateRunsBeforeTheCelFilter() throws Exception {
        NotificationOutboxEvent event = ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED, DOC);
        UUID channelA = UUID.randomUUID();
        UUID channelB = UUID.randomUUID();
        NotificationSubscription byDefault = subscriptionFor(event.getOrg(),
                NotificationEventType.RELEASE_CREATED, "event.version == 'v1'", channelA, null);
        NotificationSubscription optedIn = subscriptionFor(event.getOrg(),
                NotificationEventType.RELEASE_CREATED, "event.version == 'v2'", channelB, Boolean.TRUE);
        when(celEvaluator.evaluate(eq("event.version == 'v2'"), any(), eq(event))).thenReturn(true);

        drain(event, byDefault, optedIn);

        verify(celEvaluator, never()).evaluate(eq("event.version == 'v1'"), any(), any());
        verify(celEvaluator, times(1)).evaluate(eq("event.version == 'v2'"), any(), eq(event));
        assertEquals(channelB, savedRows(1).get(0).getChannelUuid());
    }

    // ---------- row 15: every release-shaped type is gated ----------

    @Test
    void everyReleaseShapedTypeIsGated() {
        for (NotificationEventType type : ReleaseShapedEvents.TYPES) {
            NotificationOutboxEvent skipped = ReleaseShapedEvents.event(type, DOC);
            drain(skipped, subscriptionFor(skipped.getOrg(), type, "", UUID.randomUUID(), null));
            verify(deliveryRepo, never().description("a default subscription skips " + type)).save(any());
            assertEquals(NotificationOutboxStatus.FANNED_OUT, skipped.getStatus(), type.name());

            clearInvocations(deliveryRepo, outboxRepo, subscriptionRepo);
            NotificationOutboxEvent delivered = ReleaseShapedEvents.event(type, DOC);
            UUID channel = UUID.randomUUID();
            drain(delivered, subscriptionFor(delivered.getOrg(), type, "", channel, Boolean.TRUE));
            ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
            verify(deliveryRepo, times(1).description("an opted-in subscription receives " + type))
                    .save(captor.capture());
            assertEquals(channel, captor.getValue().getChannelUuid(), type.name());
            clearInvocations(deliveryRepo, outboxRepo, subscriptionRepo);
        }
    }

    // ---------- row 16: events without a release block are not gated ----------

    @Test
    void aVulnEventIsNotGated() {
        NotificationOutboxEvent event = vulnEvent();
        UUID channel = UUID.randomUUID();
        NotificationSubscription sub = subscriptionFor(event.getOrg(),
                NotificationEventType.NEW_VULN_AFFECTS_RELEASES, "", channel, Boolean.FALSE);

        drain(event, sub);

        assertEquals(channel, savedRows(1).get(0).getChannelUuid());
    }

    // ---------- rows 17-18: the per-user approval paths stay outside the gate ----------

    @Test
    void approvalRequestOnADocumentRoundStillReachesItsApprovers() {
        UUID approver = UUID.randomUUID();

        NotificationOutboxEvent alone = approvalRequestedOnADocumentRound(approver);
        drain(alone);
        NotificationDelivery targeted = savedRows(1).get(0);
        assertEquals(approver, targeted.getTargetUser());
        assertNull(targeted.getChannelUuid());
        assertNull(targeted.getSubscriptionUuid());
        assertEquals(NotificationDeliveryStatus.SENT, targeted.getStatus());
        clearInvocations(deliveryRepo, outboxRepo, subscriptionRepo);

        NotificationOutboxEvent withDefault = approvalRequestedOnADocumentRound(approver);
        drain(withDefault, subscriptionFor(withDefault.getOrg(), NotificationEventType.APPROVAL_REQUESTED,
                "", UUID.randomUUID(), null));
        List<NotificationDelivery> onlyTargeted = savedRows(1);
        assertEquals(approver, onlyTargeted.get(0).getTargetUser());
        assertNull(onlyTargeted.get(0).getChannelUuid(), "no channel row for a default subscription");
        clearInvocations(deliveryRepo, outboxRepo, subscriptionRepo);

        NotificationOutboxEvent withOptIn = approvalRequestedOnADocumentRound(approver);
        UUID channel = UUID.randomUUID();
        drain(withOptIn, subscriptionFor(withOptIn.getOrg(), NotificationEventType.APPROVAL_REQUESTED,
                "", channel, Boolean.TRUE));
        List<NotificationDelivery> both = savedRows(2);
        assertTrue(both.stream().anyMatch(r -> approver.equals(r.getTargetUser()) && r.getChannelUuid() == null),
                "the targeted row");
        assertTrue(both.stream().anyMatch(r -> channel.equals(r.getChannelUuid())), "the channel row");
    }

    @Test
    void approvalResolvedOnADocumentRoundStillMarksRead() throws Exception {
        UUID releaseUuid = UUID.randomUUID();
        UUID requestUuid = UUID.randomUUID();
        NotificationOutboxEvent event = approvalResolvedOnADocumentRound(releaseUuid, requestUuid);
        UUID approver = UUID.randomUUID();
        NotificationDelivery requested = new NotificationDelivery();
        requested.setUuid(UUID.randomUUID());
        requested.setOrg(event.getOrg());
        requested.setTargetUser(approver);
        requested.setStatus(NotificationDeliveryStatus.SENT);
        when(deliveryRepo.findTargetedByDedupKey(event.getOrg(),
                "approval:requested:" + releaseUuid + ":" + requestUuid)).thenReturn(List.of(requested));

        drain(event, subscriptionFor(event.getOrg(), NotificationEventType.APPROVAL_RESOLVED,
                "", UUID.randomUUID(), null));

        verify(readService).markRead(eq(approver), eq(requested.getUuid()), any());
        verify(deliveryRepo, never()).save(any());
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    // ---------- row 19: the loop gate skips before the ownership scope ----------

    @Test
    void aScopedDefaultSubscriptionSkipsADocumentRound() {
        NotificationOutboxEvent event = ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED, DOC);
        NotificationSubscription plain = subscriptionFor(event.getOrg(),
                NotificationEventType.RELEASE_CREATED, "", UUID.randomUUID(), null);
        NotificationSubscription scoped = subscriptionScopedToTeam(event.getOrg(), UUID.randomUUID(),
                UUID.randomUUID(), NotificationEventType.RELEASE_CREATED);

        drain(event, plain, scoped);

        verify(deliveryRepo, never()).save(any());
        verifyNoInteractions(componentOwnershipService);
        assertEquals(NotificationOutboxStatus.FANNED_OUT, event.getStatus());
    }

    // ---------- isBoardDocumentRelease, the raw-map read ----------

    @Test
    void isBoardDocumentReleaseReadsOnlyTheExactKindUnderRelease() {
        assertTrue(NotificationFanOutService.isBoardDocumentRelease(
                ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED, DOC)));
        assertFalse(NotificationFanOutService.isBoardDocumentRelease(
                ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED, "GENERIC")));
        assertFalse(NotificationFanOutService.isBoardDocumentRelease(
                ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED, "HELM")));
        assertFalse(NotificationFanOutService.isBoardDocumentRelease(
                ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED, (String) null)));

        NotificationOutboxEvent topLevel = vulnEvent();
        topLevel.getRecordData().put("componentKind", DOC);
        assertFalse(NotificationFanOutService.isBoardDocumentRelease(topLevel),
                "only release.componentKind counts, not a top-level key");

        NotificationOutboxEvent nonString = ReleaseShapedEvents.event(NotificationEventType.RELEASE_CREATED, DOC);
        @SuppressWarnings("unchecked")
        Map<String, Object> release = (Map<String, Object>) nonString.getRecordData().get("release");
        release.put("componentKind", List.of(DOC));
        assertFalse(NotificationFanOutService.isBoardDocumentRelease(nonString));

        NotificationOutboxEvent empty = new NotificationOutboxEvent();
        empty.setRecordData(null);
        assertFalse(NotificationFanOutService.isBoardDocumentRelease(empty));
    }

    // ---------- helpers ----------

    private void drain(NotificationOutboxEvent event, NotificationSubscription... subs) {
        when(outboxRepo.findPendingBatch(50)).thenReturn(List.of(event));
        when(subscriptionRepo.findActiveByOrg(event.getOrg())).thenReturn(List.of(subs));
        fanOut.drainBatch(50);
    }

    private List<NotificationDelivery> savedRows(int expected) {
        ArgumentCaptor<NotificationDelivery> captor = ArgumentCaptor.forClass(NotificationDelivery.class);
        verify(deliveryRepo, times(expected)).save(captor.capture());
        return captor.getAllValues();
    }

    /**
     * An ACTIVE subscription on one event type with one route to {@code channelUuid}, built with
     * the canonical constructor. The route has no severity gate: release-shaped events carry no
     * severity, so a LOW gate would drop every one of them before the document gate is visible.
     */
    @SuppressWarnings("unchecked")
    private NotificationSubscription subscriptionFor(UUID org, NotificationEventType eventType,
            String celExpression, UUID channelUuid, Boolean includeBoardDocuments) {
        NotificationSubscriptionData data = new NotificationSubscriptionData(
                org, null, "test sub", NotificationSubscriptionStatus.ACTIVE,
                List.of(eventType),
                new FilterConfig(EvaluationMode.PRESET, null, celExpression),
                List.of(new RouteConfig(null, null, null, List.of(channelUuid))),
                null, null, null, null, includeBoardDocuments);
        return subscriptionOf(data, org, channelUuid);
    }

    /** A default (not opted-in) subscription on {@code eventType} scoped to {@code ownedByTeam}. */
    @SuppressWarnings("unchecked")
    private NotificationSubscription subscriptionScopedToTeam(UUID org, UUID ownedByTeam, UUID channelUuid,
            NotificationEventType eventType) {
        NotificationSubscriptionData data = new NotificationSubscriptionData(
                org, null, "team-scoped sub", NotificationSubscriptionStatus.ACTIVE,
                List.of(eventType),
                new FilterConfig(EvaluationMode.PRESET, null, null),
                List.of(new RouteConfig(null, null, null, List.of(channelUuid))),
                null, null, ownedByTeam);
        return subscriptionOf(data, org, channelUuid);
    }

    @SuppressWarnings("unchecked")
    private NotificationSubscription subscriptionOf(NotificationSubscriptionData data, UUID org, UUID channelUuid) {
        NotificationSubscription sub = new NotificationSubscription();
        sub.setUuid(UUID.randomUUID());
        sub.setRecordData(Utils.OM.convertValue(data, Map.class));
        channelOrgs.put(channelUuid, org);
        return sub;
    }

    @SuppressWarnings("unchecked")
    private static NotificationOutboxEvent approvalRequestedOnADocumentRound(UUID approver) {
        ReleaseRef ref = ReleaseShapedEvents.ref(DOC);
        UUID requestUuid = UUID.randomUUID();
        ApprovalRequestedPayload payload = new ApprovalRequestedPayload(
                ref, requestUuid, UUID.randomUUID(), "Alice", "alice@example.com",
                List.of(new ApprovalRequestEntryRef(UUID.randomUUID(), "QA sign-off")), List.of(approver));
        NotificationOutboxEvent event = ReleaseShapedEvents.event(NotificationEventType.APPROVAL_REQUESTED, ref);
        event.setDedupKey("approval:requested:" + ref.releaseUuid() + ":" + requestUuid);
        event.setRecordData(Utils.OM.convertValue(payload, Map.class));
        return event;
    }

    @SuppressWarnings("unchecked")
    private static NotificationOutboxEvent approvalResolvedOnADocumentRound(UUID releaseUuid, UUID requestUuid) {
        ReleaseRef base = ReleaseShapedEvents.ref(DOC);
        ReleaseRef ref = new ReleaseRef(releaseUuid, base.version(), base.componentUuid(), base.componentName(),
                base.componentType(), base.branchUuid(), base.branchName(), base.lifecycle(),
                null, null, null, null, null, DOC);
        UUID entryUuid = UUID.randomUUID();
        ApprovalResolvedPayload payload = new ApprovalResolvedPayload(ref, entryUuid, "QA sign-off",
                ApprovalResolvedPayload.Resolution.APPROVED, UUID.randomUUID(), "Bob", "bob@example.com",
                List.of(requestUuid));
        NotificationOutboxEvent event = ReleaseShapedEvents.event(NotificationEventType.APPROVAL_RESOLVED, ref);
        event.setDedupKey("approval:resolved:" + releaseUuid + ":" + entryUuid);
        event.setRecordData(Utils.OM.convertValue(payload, Map.class));
        return event;
    }

    @SuppressWarnings("unchecked")
    private static NotificationOutboxEvent vulnEvent() {
        NewVulnAffectsReleasesPayload payload = new NewVulnAffectsReleasesPayload(
                "CVE-2025-12345", List.of(), 9.8, "vec", 0.5, false, "fix",
                NotificationSeverity.CRITICAL, null, List.of(new AffectedRelease(
                        UUID.randomUUID(), "myapp", "v1.0", "main", null, List.of())));
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(UUID.randomUUID());
        event.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOccurredAt(ZonedDateTime.now());
        event.setRecordData(new HashMap<>(Utils.OM.convertValue(payload, Map.class)));
        return event;
    }

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
        data.setParameters(new HashMap<>());
        channel.setRecordData(Utils.dataToRecord(data));
        return channel;
    }

    private void inject(String fieldName, Object value) throws Exception {
        Field f = NotificationFanOutService.class.getDeclaredField(fieldName);
        f.setAccessible(true);
        f.set(fanOut, value);
    }
}
