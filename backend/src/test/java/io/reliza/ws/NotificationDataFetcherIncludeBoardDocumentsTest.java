/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import io.reliza.common.Utils;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.NotificationSubscriptionStatus;
import io.reliza.model.OrganizationData;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission.Permissions;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RouteConfig;
import io.reliza.model.dto.notifications.NotificationSubscriptionResult;
import io.reliza.repositories.NotificationSubscriptionRepository;
import io.reliza.service.AuthorizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.NotificationSubscriptionService;
import io.reliza.service.OrganizationService;
import io.reliza.service.UserService;

/**
 * TPF-158: {@code includeBoardDocuments} through the subscription GraphQL fetcher. The input maps
 * it onto the seed as given (true, false, or null when the key is absent, also on an update of a
 * row that held true: no keep-if-absent), and both reads project it as stored. Rows 21, 22 and
 * the fetcher half of row 29 of the TPF-158 architecture, on the Mockito rig of
 * {@link NotificationDataFetcherReadTest}.
 */
class NotificationDataFetcherIncludeBoardDocumentsTest {

    private NotificationSubscriptionRepository subscriptionRepo;
    private NotificationSubscriptionService subscriptionService;
    private NotificationDataFetcher fetcher;

    @BeforeEach
    void wireMocks() throws Exception {
        AuthorizationService authorizationService = mock(AuthorizationService.class);
        GetOrganizationService getOrganizationService = mock(GetOrganizationService.class);
        OrganizationService organizationService = mock(OrganizationService.class);
        UserService userService = mock(UserService.class);
        subscriptionRepo = mock(NotificationSubscriptionRepository.class);
        subscriptionService = mock(NotificationSubscriptionService.class);
        when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class)))
                .thenReturn(Optional.of(mock(UserData.class)));
        when(getOrganizationService.getOrganizationData(any()))
                .thenReturn(Optional.of(mock(OrganizationData.class)));
        Permissions noPerms = mock(Permissions.class);
        when(noPerms.getOrgPermissionsAsSet(any())).thenReturn(Set.of());
        when(organizationService.obtainCombinedUserOrgPermissions(any(), any())).thenReturn(noPerms);
        when(subscriptionService.upsertSubscription(any(), any(), any(), any())).thenAnswer(inv -> {
            NotificationSubscriptionData seed = inv.getArgument(2);
            NotificationSubscription saved = new NotificationSubscription();
            saved.setUuid(UUID.randomUUID());
            saved.setRecordData(Utils.OM.convertValue(seed, Map.class));
            return saved;
        });

        fetcher = new NotificationDataFetcher();
        inject("authorizationService", authorizationService);
        inject("getOrganizationService", getOrganizationService);
        inject("organizationService", organizationService);
        inject("userService", userService);
        inject("subscriptionRepo", subscriptionRepo);
        inject("subscriptionService", subscriptionService);

        SecurityContext ctx = SecurityContextHolder.createEmptyContext();
        ctx.setAuthentication(mock(JwtAuthenticationToken.class));
        SecurityContextHolder.setContext(ctx);
    }

    /** Row 21: true, absent and false reach the seed as TRUE, null and FALSE. */
    @Test
    void upsertNotificationSubscriptionPersistsIncludeBoardDocuments() throws Exception {
        UUID org = UUID.randomUUID();

        Map<String, Object> optedIn = input(org);
        optedIn.put("includeBoardDocuments", true);
        assertEquals(Boolean.TRUE, seedOf(optedIn).includeBoardDocuments());

        assertNull(seedOf(input(org)).includeBoardDocuments(), "an absent key reaches the seed as null");

        Map<String, Object> optedOut = input(org);
        optedOut.put("includeBoardDocuments", false);
        assertEquals(Boolean.FALSE, seedOf(optedOut).includeBoardDocuments());
    }

    /** Row 29, fetcher half: an update without the key yields a null seed though the row held true. */
    @Test
    void anUpdateWithoutTheFieldHandsTheServiceANullOptIn() throws Exception {
        UUID org = UUID.randomUUID();
        UUID subUuid = UUID.randomUUID();
        when(subscriptionRepo.findById(subUuid)).thenReturn(Optional.of(stored(subUuid, data(org, Boolean.TRUE))));

        Map<String, Object> update = input(org);
        update.put("uuid", subUuid.toString());

        assertNull(seedOf(update).includeBoardDocuments(), "no keep-if-absent: the replace stores null");
    }

    /** Row 22: the list and the single read project the flag as stored (true, null). */
    @Test
    void listNotificationSubscriptionsProjectsIncludeBoardDocuments() throws Exception {
        UUID org = UUID.randomUUID();
        UUID optedInUuid = UUID.randomUUID();
        UUID legacyUuid = UUID.randomUUID();
        NotificationSubscription optedIn = stored(optedInUuid, data(org, Boolean.TRUE));
        NotificationSubscription legacy = stored(legacyUuid, new NotificationSubscriptionData(
                org, null, "legacy-sub", NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.RELEASE_CREATED), null,
                List.of(new RouteConfig(null, null, null, List.of(UUID.randomUUID()))),
                null, null, null, null));
        when(subscriptionRepo.findByOrg(org)).thenReturn(List.of(optedIn, legacy));
        when(subscriptionRepo.findById(optedInUuid)).thenReturn(Optional.of(optedIn));
        when(subscriptionRepo.findById(legacyUuid)).thenReturn(Optional.of(legacy));

        Map<UUID, NotificationSubscriptionResult> byUuid = new HashMap<>();
        fetcher.listNotificationSubscriptions(org).forEach(r -> byUuid.put(r.uuid(), r));
        assertEquals(Boolean.TRUE, byUuid.get(optedInUuid).includeBoardDocuments());
        assertNull(byUuid.get(legacyUuid).includeBoardDocuments());

        assertEquals(Boolean.TRUE, fetcher.getNotificationSubscription(optedInUuid).includeBoardDocuments());
        assertNull(fetcher.getNotificationSubscription(legacyUuid).includeBoardDocuments());
    }

    // ---------- helpers ----------

    private NotificationSubscriptionData seedOf(Map<String, Object> input) throws Exception {
        clearInvocations(subscriptionService);
        fetcher.upsertNotificationSubscription(input);
        ArgumentCaptor<NotificationSubscriptionData> captor = ArgumentCaptor.forClass(NotificationSubscriptionData.class);
        verify(subscriptionService).upsertSubscription(any(), any(), captor.capture(), any());
        return captor.getValue();
    }

    private static Map<String, Object> input(UUID org) {
        Map<String, Object> route = new HashMap<>();
        route.put("channels", List.of(UUID.randomUUID().toString()));
        Map<String, Object> input = new HashMap<>();
        input.put("org", org.toString());
        input.put("name", "document-opt-in-sub");
        input.put("eventTypes", List.of(NotificationEventType.RELEASE_CREATED.name()));
        input.put("routes", List.of(route));
        return input;
    }

    private static NotificationSubscriptionData data(UUID org, Boolean includeBoardDocuments) {
        return new NotificationSubscriptionData(
                org, null, "document-opt-in-sub", NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.RELEASE_CREATED), null,
                List.of(new RouteConfig(null, null, null, List.of(UUID.randomUUID()))),
                null, null, null, null, includeBoardDocuments);
    }

    @SuppressWarnings("unchecked")
    private static NotificationSubscription stored(UUID uuid, NotificationSubscriptionData data) {
        NotificationSubscription s = new NotificationSubscription();
        s.setUuid(uuid);
        s.setRecordData(Utils.OM.convertValue(data, Map.class));
        return s;
    }

    private void inject(String field, Object value) throws Exception {
        Field f = NotificationDataFetcher.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(fetcher, value);
    }
}
