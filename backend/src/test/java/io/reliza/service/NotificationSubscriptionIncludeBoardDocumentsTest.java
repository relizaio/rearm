/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.reliza.common.Utils;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.NotificationSubscriptionStatus;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RouteConfig;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.NotificationChannelGroupRepository;
import io.reliza.repositories.NotificationSubscriptionRepository;

/**
 * TPF-158: the board-document opt-in ({@code includeBoardDocuments}) on the subscription row.
 * It persists in the row's jsonb, survives a status flip (the canonical-constructor rebuild), and
 * an upsert without it stores null: the mutation replaces the row, there is no keep-if-absent.
 * Rows 20, 25 and 29 of the TPF-158 architecture, on the Mockito rig of
 * {@link NotificationSubscriptionServiceTest}.
 */
class NotificationSubscriptionIncludeBoardDocumentsTest {

    private NotificationSubscriptionRepository subRepo;
    private IntegrationRepository integrationRepo;
    private NotificationSubscriptionService service;

    @BeforeEach
    void wireMocks() throws Exception {
        subRepo = mock(NotificationSubscriptionRepository.class);
        integrationRepo = mock(IntegrationRepository.class);
        when(subRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service = new NotificationSubscriptionService();
        inject("subscriptionRepo", subRepo);
        inject("integrationRepo", integrationRepo);
        inject("channelGroupRepo", mock(NotificationChannelGroupRepository.class));
        inject("auditService", mock(AuditService.class));
        inject("celEvaluator", mock(NotificationCelEvaluator.class));
        inject("teamService", mock(TeamService.class));
    }

    /** Row 20: true persists; a seed through the eleven-argument constructor stores null. */
    @Test
    void createPersistsIncludeBoardDocuments() throws Exception {
        UUID org = UUID.randomUUID();
        UUID channel = UUID.randomUUID();
        stubChannelInOrg(channel, org);

        NotificationSubscription saved = service.upsertSubscription(null, seed(org, channel, Boolean.TRUE), who());
        assertEquals(Boolean.TRUE, stored(saved).includeBoardDocuments());

        NotificationSubscriptionData legacy = new NotificationSubscriptionData(
                org, null, "legacy-sub", NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.RELEASE_CREATED), null,
                List.of(new RouteConfig(null, null, null, List.of(channel))),
                null, null, null, null);
        NotificationSubscription savedLegacy = service.upsertSubscription(null, legacy, who());
        assertNull(stored(savedLegacy).includeBoardDocuments());
        assertNull(savedLegacy.getRecordData().get("includeBoardDocuments"));
    }

    /** Row 25: disabling a subscription keeps its opt-in (the canonical constructor). */
    @Test
    void aStatusFlipPreservesIncludeBoardDocuments() throws Exception {
        UUID org = UUID.randomUUID();
        UUID channel = UUID.randomUUID();
        stubChannelInOrg(channel, org);
        NotificationSubscription saved = service.upsertSubscription(null, seed(org, channel, Boolean.TRUE), who());
        when(subRepo.findById(saved.getUuid())).thenReturn(Optional.of(saved));
        clearInvocations(subRepo);

        service.setSubscriptionStatus(saved.getUuid(), NotificationSubscriptionStatus.DISABLED, who());

        NotificationSubscriptionData after = stored(lastSaved());
        assertEquals(NotificationSubscriptionStatus.DISABLED, after.status());
        assertEquals(Boolean.TRUE, after.includeBoardDocuments(), "the status flip dropped the opt-in");
    }

    /** Row 29: an upsert whose seed omits the flag clears a stored true (no keep-if-absent). */
    @Test
    void anUpsertWithoutTheFieldClearsAStoredOptIn() throws Exception {
        UUID org = UUID.randomUUID();
        UUID channel = UUID.randomUUID();
        stubChannelInOrg(channel, org);
        NotificationSubscription saved = service.upsertSubscription(null, seed(org, channel, Boolean.TRUE), who());
        assertEquals(Boolean.TRUE, stored(saved).includeBoardDocuments());
        when(subRepo.findById(saved.getUuid())).thenReturn(Optional.of(saved));
        clearInvocations(subRepo);

        NotificationSubscriptionData withoutTheField = new NotificationSubscriptionData(
                org, null, "test-sub", NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.RELEASE_CREATED), null,
                List.of(new RouteConfig(null, null, null, List.of(channel))),
                null, null, null, null);
        service.upsertSubscription(saved.getUuid(), withoutTheField, who());

        assertNull(stored(lastSaved()).includeBoardDocuments(),
                "absent = null = excluded: the replace clears the stored opt-in");
    }

    // ---------- helpers ----------

    private static NotificationSubscriptionData seed(UUID org, UUID channel, Boolean includeBoardDocuments) {
        return new NotificationSubscriptionData(
                org, null, "test-sub", NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.RELEASE_CREATED), null,
                List.of(new RouteConfig(null, null, null, List.of(channel))),
                null, null, null, null, includeBoardDocuments);
    }

    private static NotificationSubscriptionData stored(NotificationSubscription sub) {
        return Utils.OM.convertValue(sub.getRecordData(), NotificationSubscriptionData.class);
    }

    private NotificationSubscription lastSaved() {
        ArgumentCaptor<NotificationSubscription> captor = ArgumentCaptor.forClass(NotificationSubscription.class);
        verify(subRepo, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private static WhoUpdated who() {
        return WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test");
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
        data.setParameters(new HashMap<>());
        channel.setRecordData(Utils.dataToRecord(data));
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(channel));
    }

    private void inject(String field, Object value) throws Exception {
        Field f = NotificationSubscriptionService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }
}
