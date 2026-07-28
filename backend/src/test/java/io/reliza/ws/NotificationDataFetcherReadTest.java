/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.ws;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.ZonedDateTime;
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

import io.reliza.common.CommonVariables.AuthorizationStatus;
import io.reliza.common.CommonVariables.CallType;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.UserData;
import io.reliza.model.UserPermission;
import io.reliza.model.UserPermission.PermissionScope;
import io.reliza.model.UserPermission.PermissionType;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationChannelGroup;
import io.reliza.model.dto.notifications.NotificationChannelGroupData;
import io.reliza.model.dto.notifications.NotificationChannelGroupResult;
import io.reliza.model.dto.notifications.NotificationChannelResult;
import io.reliza.model.dto.notifications.NotificationDeliveriesPage;
import io.reliza.model.NotificationDelivery;
import io.reliza.model.NotificationDeliveryOrigin;
import io.reliza.model.dto.notifications.NotificationDeliveryResult;
import io.reliza.model.NotificationDeliveryStatus;
import io.reliza.model.NotificationInboxScope;
import io.reliza.model.OrganizationData;
import io.reliza.model.dto.notifications.NotificationInboxItem;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.dto.notifications.NotificationOutboxEventResult;
import io.reliza.model.NotificationOutboxStatus;
import io.reliza.model.NotificationSubscription;
import io.reliza.model.dto.notifications.NotificationSubscriptionData;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.FilterConfig;
import io.reliza.model.dto.notifications.NotificationSubscriptionData.RouteConfig;
import io.reliza.model.dto.notifications.NotificationSubscriptionResult;
import io.reliza.model.NotificationSubscriptionStatus;
import io.reliza.repositories.NotificationChannelGroupRepository;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.repositories.NotificationOutboxEventRepository;
import io.reliza.repositories.NotificationSubscriptionRepository;
import io.reliza.model.UserPermission.Permissions;
import io.reliza.service.AuthorizationService;
import io.reliza.service.OrganizationService;
import io.reliza.service.GetOrganizationService;
import io.reliza.service.UserService;
import io.reliza.model.dto.notifications.EvaluationMode;
import io.reliza.model.NotificationRead;
import io.reliza.service.NotificationChannelGroupService;
import io.reliza.service.NotificationSubscriptionService;
import io.reliza.service.NotificationInboxFormatter;
import io.reliza.service.NotificationInboxFormatter.InboxRendering;
import io.reliza.service.NotificationReadService;

/**
 * Covers the Phase 2e read-side query methods on
 * {@link NotificationDataFetcher}. Pins:
 * <ul>
 *   <li>org-admin authorization gating on every query — calls that
 *       resolve a uuid first re-derive org from the entity before
 *       authorizing.</li>
 *   <li>encryptedSecret stripping on channel reads.</li>
 *   <li>page size clamping (default + max).</li>
 *   <li>filter null-tolerance + total-count plumbing on the
 *       deliveries listing.</li>
 *   <li>graceful skip on subscriptions / channels whose record_data
 *       fails to parse — one corrupt row should not poison the list.</li>
 * </ul>
 */
class NotificationDataFetcherReadTest {

    private AuthorizationService authorizationService;
    private GetOrganizationService getOrganizationService;
    private OrganizationService organizationService;
    private UserService userService;
    private IntegrationRepository integrationRepo;
    private NotificationDeliveryRepository deliveryRepo;
    private NotificationOutboxEventRepository outboxRepo;
    private NotificationSubscriptionRepository subscriptionRepo;
    private NotificationChannelGroupRepository channelGroupRepo;
    private NotificationChannelGroupService channelGroupService;
    private NotificationSubscriptionService subscriptionService;
    private NotificationInboxFormatter inboxFormatter;
    private NotificationReadService readService;
    private NotificationDataFetcher fetcher;

    @BeforeEach
    void wireMocks() throws Exception {
        authorizationService = mock(AuthorizationService.class);
        getOrganizationService = mock(GetOrganizationService.class);
        organizationService = mock(OrganizationService.class);
        userService = mock(UserService.class);
        integrationRepo = mock(IntegrationRepository.class);
        deliveryRepo = mock(NotificationDeliveryRepository.class);
        outboxRepo = mock(NotificationOutboxEventRepository.class);
        subscriptionRepo = mock(NotificationSubscriptionRepository.class);
        channelGroupRepo = mock(NotificationChannelGroupRepository.class);
        channelGroupService = mock(NotificationChannelGroupService.class);
        subscriptionService = mock(NotificationSubscriptionService.class);
        inboxFormatter = mock(NotificationInboxFormatter.class);
        readService = mock(NotificationReadService.class);

        // Default: any user fetch returns a stub. Tests that exercise
        // the unauthorized path override.
        when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class)))
                .thenReturn(Optional.of(mock(UserData.class)));
        // Authorization helper requires a non-empty OrganizationData lookup
        // because List.of(ro) NPEs on a null element — pre-existing pattern
        // shared with WebhookDataFetcher. Stub a default; tests checking
        // missing-org behaviour can override.
        when(getOrganizationService.getOrganizationData(any()))
                .thenReturn(Optional.of(mock(io.reliza.model.OrganizationData.class)));
        // Default: combined org permissions (own record + UserGroups) resolve
        // to empty, so tests that don't call stubUser keep the prior
        // "no perms" behavior; stubUser overrides per test.
        Permissions defaultPerms = mock(Permissions.class);
        when(defaultPerms.getOrgPermissionsAsSet(any())).thenReturn(Set.of());
        when(organizationService.obtainCombinedUserOrgPermissions(any(), any()))
                .thenReturn(defaultPerms);

        fetcher = new NotificationDataFetcher();
        inject("authorizationService", authorizationService);
        inject("getOrganizationService", getOrganizationService);
        inject("organizationService", organizationService);
        inject("userService", userService);
        inject("integrationRepo", integrationRepo);
        inject("deliveryRepo", deliveryRepo);
        inject("outboxRepo", outboxRepo);
        inject("subscriptionRepo", subscriptionRepo);
        inject("channelGroupRepo", channelGroupRepo);
        inject("channelGroupService", channelGroupService);
        inject("subscriptionService", subscriptionService);
        inject("inboxFormatter", inboxFormatter);
        inject("readService", readService);

        // SecurityContextHolder needs a populated context for the
        // authorizeOrgAdmin helper to extract a JwtAuthenticationToken.
        SecurityContext ctx = mock(SecurityContext.class);
        when(ctx.getAuthentication()).thenReturn(mock(JwtAuthenticationToken.class));
        SecurityContextHolder.setContext(ctx);
    }

    private void inject(String field, Object value) throws Exception {
        Field f = NotificationDataFetcher.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(fetcher, value);
    }

    // ---------------- notificationDeliveries payload enrich (BUG 3) ----------------

    @Test
    void notificationDeliveriesEnrichPayloadFromOutboxEvent() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID eventUuid = UUID.randomUUID();
        NotificationDelivery d = newDelivery(UUID.randomUUID(), orgUuid);
        d.setOutboxEventUuid(eventUuid);
        NotificationOutboxEvent event = newOutboxEvent(eventUuid, orgUuid);
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", "CVE-2025-1 affects 2 releases");
        event.setRecordData(payload);

        when(deliveryRepo.findFilteredPage(eq(orgUuid), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(d));
        when(deliveryRepo.countFiltered(eq(orgUuid), any(), any(), any(), any(), any())).thenReturn(1L);
        when(outboxRepo.findAllById(any())).thenReturn(List.of(event));

        NotificationDeliveriesPage page = fetcher.getNotificationDeliveries(
                orgUuid, null, null, null, null, null, null, null);

        assertEquals(1, page.items().size());
        NotificationDeliveryResult row = page.items().get(0);
        assertNotNull(row.payloadJson(), "history row should carry the outbox payload");
        assertTrue(row.payloadJson().contains("CVE-2025-1"));
        // Locks the no-N+1 contract: the page enriches via ONE batch fetch.
        verify(outboxRepo, times(1)).findAllById(any());
    }

    @Test
    void notificationDeliverySingleEnrichesPayloadFromEvent() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID deliveryUuid = UUID.randomUUID();
        UUID eventUuid = UUID.randomUUID();
        NotificationDelivery d = newDelivery(deliveryUuid, orgUuid);
        d.setOutboxEventUuid(eventUuid);
        when(deliveryRepo.findById(deliveryUuid)).thenReturn(Optional.of(d));
        NotificationOutboxEvent event = newOutboxEvent(eventUuid, orgUuid);
        Map<String, Object> payload = new HashMap<>();
        payload.put("title", "single-row-payload");
        event.setRecordData(payload);
        when(outboxRepo.findById(eventUuid)).thenReturn(Optional.of(event));

        NotificationDeliveryResult row = fetcher.getNotificationDelivery(deliveryUuid);

        assertNotNull(row);
        assertNotNull(row.payloadJson(), "single delivery should carry the outbox payload");
        assertTrue(row.payloadJson().contains("single-row-payload"));
    }

    @Test
    void notificationDeliveriesNullPayloadWhenEventMissing() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        NotificationDelivery d = newDelivery(UUID.randomUUID(), orgUuid);
        when(deliveryRepo.findFilteredPage(eq(orgUuid), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(d));
        when(deliveryRepo.countFiltered(eq(orgUuid), any(), any(), any(), any(), any())).thenReturn(1L);
        // Event not returned (deleted / not found) -> null payload, no NPE.
        when(outboxRepo.findAllById(any())).thenReturn(List.of());

        NotificationDeliveriesPage page = fetcher.getNotificationDeliveries(
                orgUuid, null, null, null, null, null, null, null);

        assertEquals(1, page.items().size());
        assertNull(page.items().get(0).payloadJson());
    }

    // ---------------- notificationOutboxEvent ----------------

    @Test
    void getNotificationOutboxEventReturnsNullOnMiss() throws Exception {
        UUID uuid = UUID.randomUUID();
        when(outboxRepo.findById(uuid)).thenReturn(Optional.empty());
        assertNull(fetcher.getNotificationOutboxEvent(uuid));
        // Never authorized — there's no org to authorize against
        verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void getNotificationOutboxEventAuthorizesAgainstEventOrg() throws Exception {
        UUID eventUuid = UUID.randomUUID();
        UUID orgUuid = UUID.randomUUID();
        NotificationOutboxEvent event = newOutboxEvent(eventUuid, orgUuid);
        when(outboxRepo.findById(eventUuid)).thenReturn(Optional.of(event));

        NotificationOutboxEventResult result = fetcher.getNotificationOutboxEvent(eventUuid);

        assertNotNull(result);
        assertEquals(eventUuid, result.uuid());
        assertEquals(orgUuid, result.org());
        // Authorization was invoked with the event's org
        ArgumentCaptor<UUID> orgCaptor = ArgumentCaptor.forClass(UUID.class);
        verify(authorizationService).isUserAuthorizedForObjectGraphQL(
                any(), any(), any(), orgCaptor.capture(), any(), any());
        assertEquals(orgUuid, orgCaptor.getValue(),
                "Authorization should bind to the event's org, not whatever org the caller claims");
    }

    // ---------------- notificationDelivery ----------------

    @Test
    void getNotificationDeliveryAuthorizesAgainstDeliveryOrg() throws Exception {
        UUID deliveryUuid = UUID.randomUUID();
        UUID orgUuid = UUID.randomUUID();
        NotificationDelivery d = newDelivery(deliveryUuid, orgUuid);
        when(deliveryRepo.findById(deliveryUuid)).thenReturn(Optional.of(d));

        NotificationDeliveryResult result = fetcher.getNotificationDelivery(deliveryUuid);
        assertNotNull(result);
        assertEquals(deliveryUuid, result.uuid());
        assertEquals(orgUuid, result.org());
        verify(authorizationService).isUserAuthorizedForObjectGraphQL(
                any(), any(), any(), eq(orgUuid), any(), any());
    }

    // ---------------- notificationDeliveries (listing) ----------------

    @Test
    void notificationDeliveriesAppliesDefaultPageSizeAndPipesTotalCount() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        NotificationDelivery d = newDelivery(UUID.randomUUID(), orgUuid);
        when(deliveryRepo.findFilteredPage(eq(orgUuid), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of(d));
        when(deliveryRepo.countFiltered(eq(orgUuid), any(), any(), any(), any(), any())).thenReturn(742L);

        NotificationDeliveriesPage page = fetcher.getNotificationDeliveries(
                orgUuid, null, null, null, null, null, null, null);

        assertEquals(1, page.items().size());
        assertEquals(742L, page.totalCount());
        assertEquals(50, page.limit(), "Default page size should be 50");
        assertEquals(0, page.offset());
        // Caller passed null limit/offset — repo got the resolved values
        verify(deliveryRepo).findFilteredPage(eq(orgUuid), any(), any(), any(), any(), any(), eq(50), eq(0));
    }

    @Test
    void notificationDeliveriesClampsLimitAtMax() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        when(deliveryRepo.findFilteredPage(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(deliveryRepo.countFiltered(any(), any(), any(), any(), any(), any())).thenReturn(0L);

        NotificationDeliveriesPage page = fetcher.getNotificationDeliveries(
                orgUuid, null, null, null, null, null, 9999, 0);

        // 9999 limit clamped to MAX_PAGE_SIZE (500)
        assertEquals(500, page.limit());
        verify(deliveryRepo).findFilteredPage(eq(orgUuid), any(), any(), any(), any(), any(), eq(500), eq(0));
    }

    @Test
    void notificationDeliveriesPropagatesFiltersToRepo() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID eventUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        UUID subscriptionUuid = UUID.randomUUID();
        when(deliveryRepo.findFilteredPage(any(), any(), any(), any(), any(), any(), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(deliveryRepo.countFiltered(any(), any(), any(), any(), any(), any())).thenReturn(0L);

        fetcher.getNotificationDeliveries(orgUuid, eventUuid, channelUuid, subscriptionUuid,
                "PENDING", "SYNTHETIC", 25, 100);

        verify(deliveryRepo).findFilteredPage(
                eq(orgUuid), eq(eventUuid), eq(channelUuid), eq(subscriptionUuid),
                eq("PENDING"), eq("SYNTHETIC"), eq(25), eq(100));
        verify(deliveryRepo).countFiltered(
                eq(orgUuid), eq(eventUuid), eq(channelUuid), eq(subscriptionUuid),
                eq("PENDING"), eq("SYNTHETIC"));
    }

    @Test
    void notificationDeliveriesRejectsNullOrg() {
        RelizaException e = assertThrows(RelizaException.class, () ->
                fetcher.getNotificationDeliveries(null, null, null, null, null, null, 10, 0));
        assertTrue(e.getMessage().contains("orgUuid is required"));
    }

    // ---------------- notificationChannels / secret stripping ----------------

    @Test
    void listNotificationChannelsStripsBothSecretAndConfigData() throws Exception {
        // Phase 2e layer-2 fix: configData is also intentionally omitted
        // from the read surface alongside encryptedSecret. configData can
        // hold webhook URLs that are themselves auth credentials; surfacing
        // it through any org-admin read is a credential leak.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Map<String, Object> sensitiveConfig = Map.of(
                "webhook_url", "https://hooks.slack.com/services/T00/B00/secretWebhookTokenXYZ",
                "team_id", "T00ABCDEF");
        Integration channel = newChannel(channelUuid, orgUuid, "slack-prod",
                IntegrationType.SLACK, Boolean.TRUE,
                "encrypted-blob-customers-should-never-see", sensitiveConfig);
        when(integrationRepo.listIntegrationsByOrg(orgUuid.toString())).thenReturn(List.of(channel));

        List<NotificationChannelResult> results = fetcher.listNotificationChannels(orgUuid);

        assertEquals(1, results.size());
        NotificationChannelResult r = results.get(0);
        String rendered = Utils.OM.writeValueAsString(r);
        // Neither the encrypted secret nor the configData payload reach
        // the GraphQL surface. The record has no fields for either; if
        // either ever gets added, this test forces explicit thought.
        assertTrue(!rendered.contains("encrypted-blob"),
                "Encrypted secret leaked into the read surface: " + rendered);
        assertTrue(!rendered.contains("secretWebhookTokenXYZ"),
                "Webhook URL token leaked through configData: " + rendered);
        assertTrue(!rendered.contains("hooks.slack.com"),
                "Webhook URL leaked through configData: " + rendered);
        assertEquals("slack-prod", r.name());
        assertEquals("SLACK", r.type());
    }

    @Test
    void notificationDeliveriesRejectsUnknownStatusFilter() {
        UUID orgUuid = UUID.randomUUID();
        // GraphQL parser rejects typos for typed schema enums before the
        // fetcher fires — but the server-side validation is the belt-and-
        // suspenders for non-schema callers (raw HTTP, programmatic agents)
        // and prevents a silent zero-row response.
        RelizaException e = assertThrows(RelizaException.class, () ->
                fetcher.getNotificationDeliveries(
                        orgUuid, null, null, null, "PEDNING" /* typo */, null, null, null));
        assertTrue(e.getMessage().contains("Unknown delivery status filter"),
                "Expected status-typo rejection, got: " + e.getMessage());
    }

    @Test
    void notificationDeliveriesRejectsUnknownOriginFilter() {
        UUID orgUuid = UUID.randomUUID();
        RelizaException e = assertThrows(RelizaException.class, () ->
                fetcher.getNotificationDeliveries(
                        orgUuid, null, null, null, null, "SYNTETIC" /* typo */, null, null));
        assertTrue(e.getMessage().contains("Unknown delivery origin filter"),
                "Expected origin-typo rejection, got: " + e.getMessage());
    }

    @Test
    void listNotificationChannelsSkipsCorruptRow() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        // Valid channel
        Integration ok = newChannel(UUID.randomUUID(), orgUuid, "ok",
                IntegrationType.SLACK, Boolean.TRUE, "secret", Map.of());
        // Corrupt: record_data missing
        Integration broken = new Integration();
        broken.setUuid(UUID.randomUUID());
        broken.setRecordData(null);

        when(integrationRepo.listIntegrationsByOrg(orgUuid.toString())).thenReturn(List.of(ok, broken));

        List<NotificationChannelResult> results = fetcher.listNotificationChannels(orgUuid);
        // Broken row drops out; valid one survives — one bad row must not
        // poison the entire list rendering.
        assertEquals(1, results.size());
        assertEquals("ok", results.get(0).name());
    }

    @Test
    void listNotificationChannelsProjectsEmailDigestAndRecipients() throws Exception {
        // Phase 5: EMAIL channels expose the effective digest policy and
        // the recipient list (display data, not credentials). Non-EMAIL
        // channels keep all three null — the configData embargo holds.
        UUID orgUuid = UUID.randomUUID();
        Integration email = newChannel(UUID.randomUUID(), orgUuid, "email-digest",
                IntegrationType.EMAIL, Boolean.TRUE, null,
                Map.of("recipients", List.of("a@example.com", "b@example.com"),
                        "digestMode", "ROLLING", "digestInterval", "PT1H"));
        Integration slack = newChannel(UUID.randomUUID(), orgUuid, "slack-prod",
                IntegrationType.SLACK, Boolean.TRUE, "secret",
                Map.of("recipients", List.of("should-never-surface@example.com")));
        when(integrationRepo.listIntegrationsByOrg(orgUuid.toString())).thenReturn(List.of(email, slack));

        List<NotificationChannelResult> results = fetcher.listNotificationChannels(orgUuid);

        assertEquals(2, results.size());
        NotificationChannelResult e = results.stream().filter(r -> "EMAIL".equals(r.type())).findFirst().orElseThrow();
        assertEquals("ROLLING", e.digestMode());
        assertEquals("PT1H", e.digestInterval());
        assertEquals(List.of("a@example.com", "b@example.com"), e.emailRecipients());
        NotificationChannelResult s = results.stream().filter(r -> "SLACK".equals(r.type())).findFirst().orElseThrow();
        assertEquals(null, s.digestMode());
        assertEquals(null, s.emailRecipients());
    }

    // ---------------- Phase 2b-1: channel/CI cross-surface isolation ----------------

    @Test
    void getNotificationChannelTreatsCiIntegrationAsNotFound() throws Exception {
        // Post-2b-1 channels share the integrations table with CI rows.
        // findById now resolves a GITHUB integration, but the channel read
        // surface must still treat it as absent (pre-2b-1 it lived in a
        // different table and was unreachable).
        UUID uuid = UUID.randomUUID();
        Integration ci = newCiIntegration(uuid, UUID.randomUUID(), IntegrationType.GITHUB);
        when(integrationRepo.findById(uuid)).thenReturn(Optional.of(ci));

        assertNull(fetcher.getNotificationChannel(uuid));
        // Never authorized — a CI uuid is "not found" before any org bind
        verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void deleteNotificationChannelReturnsFalseForCiIntegration() throws Exception {
        UUID uuid = UUID.randomUUID();
        Integration ci = newCiIntegration(uuid, UUID.randomUUID(), IntegrationType.DEPENDENCYTRACK);
        when(integrationRepo.findById(uuid)).thenReturn(Optional.of(ci));

        assertEquals(Boolean.FALSE, fetcher.deleteNotificationChannel(uuid));
        verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void setNotificationChannelStatusRejectsCiIntegration() throws Exception {
        UUID uuid = UUID.randomUUID();
        Integration ci = newCiIntegration(uuid, UUID.randomUUID(), IntegrationType.GITHUB);
        when(integrationRepo.findById(uuid)).thenReturn(Optional.of(ci));

        RelizaException e = assertThrows(RelizaException.class, () ->
                fetcher.setNotificationChannelStatus(uuid, "DISABLED"));
        assertTrue(e.getMessage().contains("Channel not found"),
                "Expected channel-not-found for a CI uuid, got: " + e.getMessage());
    }

    // ---------------- notificationSubscriptions ----------------

    @Test
    void listNotificationSubscriptionsRendersComplexFieldsAsJson() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID subUuid = UUID.randomUUID();
        NotificationSubscriptionData data = new NotificationSubscriptionData(
                orgUuid, null, "my-sub",
                NotificationSubscriptionStatus.ACTIVE,
                List.of(NotificationEventType.NEW_VULN_AFFECTS_RELEASES),
                new FilterConfig(EvaluationMode.PRESET, null, "event.severity == 'CRITICAL'"),
                List.of(new RouteConfig(null, null, null, List.of(UUID.randomUUID()))),
                1440, null);
        NotificationSubscription sub = newSubscription(subUuid, data);
        when(subscriptionRepo.findByOrg(orgUuid)).thenReturn(List.of(sub));

        List<NotificationSubscriptionResult> results = fetcher.listNotificationSubscriptions(orgUuid);
        assertEquals(1, results.size());
        NotificationSubscriptionResult r = results.get(0);
        assertEquals(subUuid, r.uuid());
        assertEquals("my-sub", r.name());
        assertEquals("ACTIVE", r.status());
        assertEquals(1, r.eventTypes().size());
        assertEquals("NEW_VULN_AFFECTS_RELEASES", r.eventTypes().get(0));
        assertNotNull(r.filter(), "Filter should be JSON-stringified");
        assertTrue(r.filter().contains("CRITICAL"),
                "Filter JSON should round-trip the CEL expression: " + r.filter());
        assertNotNull(r.routes(), "Routes should be JSON-stringified");
        assertEquals(1440, r.dedupWindowMinutes());
        assertNull(r.rateLimit(), "Null rate limit should pass through as null");
    }

    // ---------------- Phase 13b: channel-group CRUD ----------------

    @Test
    void upsertNotificationChannelGroupHappyPath() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID memberCh = UUID.randomUUID();
        UUID groupUuid = UUID.randomUUID();

        // Service-layer mock returns whatever the fetcher constructed.
        // New 4-arg signature (uuid, expectedRevision, seed, wu) — the
        // expectedRevision was threaded through to gate concurrent
        // multi-admin edits. The seed lives at arg position 2 now.
        when(channelGroupService.upsertGroup(any(), any(), any(), any())).thenAnswer(inv -> {
            NotificationChannelGroupData seed = inv.getArgument(2);
            NotificationChannelGroup saved = new NotificationChannelGroup();
            saved.setUuid(groupUuid);
            saved.setRecordData(Utils.OM.convertValue(seed, Map.class));
            return saved;
        });

        Map<String, Object> input = Map.of(
                "org", orgUuid.toString(),
                "name", "security-oncall",
                "channels", List.of(memberCh.toString()));
        NotificationChannelGroupResult result = fetcher.upsertNotificationChannelGroup(input);

        assertNotNull(result);
        assertEquals(groupUuid, result.uuid());
        assertEquals(orgUuid, result.org());
        assertEquals("security-oncall", result.name());
        assertEquals(List.of(memberCh), result.channels());
        // Authorize against the seed's org
        verify(authorizationService).isUserAuthorizedForObjectGraphQL(
                any(), any(), any(), eq(orgUuid), any(), any());
    }

    @Test
    void upsertNotificationChannelGroupRejectsCrossTenantUuid() throws Exception {
        // Caller claims org A but supplies a uuid for a group that
        // belongs to org B. Cross-tenant probe must be rejected before
        // the service-layer write happens.
        UUID orgA = UUID.randomUUID();
        UUID orgB = UUID.randomUUID();
        UUID groupUuid = UUID.randomUUID();

        NotificationChannelGroup existing = new NotificationChannelGroup();
        existing.setUuid(groupUuid);
        NotificationChannelGroupData existingData = new NotificationChannelGroupData(
                orgB, null, "victim-group", List.of(UUID.randomUUID()));
        existing.setRecordData(Utils.OM.convertValue(existingData, Map.class));
        when(channelGroupRepo.findById(groupUuid)).thenReturn(Optional.of(existing));

        Map<String, Object> input = Map.of(
                "uuid", groupUuid.toString(),
                "org", orgA.toString(),
                "name", "stolen",
                "channels", List.of(UUID.randomUUID().toString()));
        RelizaException e = assertThrows(RelizaException.class, () ->
                fetcher.upsertNotificationChannelGroup(input));
        assertTrue(e.getMessage().contains("does not belong to org"),
                "Expected cross-tenant rejection, got: " + e.getMessage());
        // Service layer never invoked — fetcher guard short-circuited first
        verify(channelGroupService, never()).upsertGroup(any(), any(), any());
    }

    // ---------------- Phase 12: route perspectives round-trip ----------------

    @Test
    void upsertNotificationSubscriptionPersistsRoutePerspectives() throws Exception {
        // Regression for board #8: a route's perspectives delivery filter
        // is declared in the GraphQL input (NotificationRouteInput.perspectives)
        // and read by fan-out (perspectiveGateMatches), but the input DTO
        // dropped it on write so it persisted as null. This proves the
        // UUID survives the upsert -> inputToSubscriptionData -> toRouteConfigs
        // mapping that the production mutation runs.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        UUID perspectiveUuid = UUID.randomUUID();

        // Capture the seed the fetcher builds and hands to the service.
        ArgumentCaptor<NotificationSubscriptionData> seedCaptor =
                ArgumentCaptor.forClass(NotificationSubscriptionData.class);
        when(subscriptionService.upsertSubscription(any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    NotificationSubscriptionData seed = inv.getArgument(2);
                    NotificationSubscription saved = new NotificationSubscription();
                    saved.setUuid(UUID.randomUUID());
                    saved.setRecordData(Utils.OM.convertValue(seed, Map.class));
                    return saved;
                });

        Map<String, Object> route = new HashMap<>();
        route.put("channels", List.of(channelUuid.toString()));
        route.put("perspectives", List.of(perspectiveUuid.toString()));

        Map<String, Object> input = Map.of(
                "org", orgUuid.toString(),
                "name", "perspective-scoped-sub",
                "eventTypes", List.of(NotificationEventType.RELEASE_CREATED.name()),
                "routes", List.of(route));

        fetcher.upsertNotificationSubscription(input);

        verify(subscriptionService).upsertSubscription(any(), any(), seedCaptor.capture(), any());
        NotificationSubscriptionData seed = seedCaptor.getValue();
        assertNotNull(seed.routes());
        assertEquals(1, seed.routes().size());
        RouteConfig persistedRoute = seed.routes().get(0);
        assertNotNull(persistedRoute.perspectives(),
                "route perspectives must survive the input mapping, not be dropped to null");
        assertEquals(List.of(perspectiveUuid), persistedRoute.perspectives());
    }

    @Test
    void deleteNotificationChannelGroupReturnsFalseWhenMissing() throws Exception {
        UUID groupUuid = UUID.randomUUID();
        when(channelGroupRepo.findById(groupUuid)).thenReturn(Optional.empty());

        Boolean result = fetcher.deleteNotificationChannelGroup(groupUuid);
        assertEquals(Boolean.FALSE, result);
        // No authorize call either — there's no org to bind to on a miss
        verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(
                any(), any(), any(), any(), any(), any());
        verify(channelGroupService, never()).deleteGroup(any());
    }

    @Test
    void deleteNotificationChannelGroupAuthorizesAndDeletesOnHit() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID groupUuid = UUID.randomUUID();
        NotificationChannelGroup existing = new NotificationChannelGroup();
        existing.setUuid(groupUuid);
        NotificationChannelGroupData data = new NotificationChannelGroupData(
                orgUuid, null, "g", List.of(UUID.randomUUID()));
        existing.setRecordData(Utils.OM.convertValue(data, Map.class));
        when(channelGroupRepo.findById(groupUuid)).thenReturn(Optional.of(existing));

        Boolean result = fetcher.deleteNotificationChannelGroup(groupUuid);
        assertEquals(Boolean.TRUE, result);
        verify(authorizationService).isUserAuthorizedForObjectGraphQL(
                any(), any(), any(), eq(orgUuid), any(), any());
        verify(channelGroupService).deleteGroup(groupUuid);
    }

    @Test
    void getNotificationChannelGroupReturnsNullOnMiss() throws Exception {
        UUID groupUuid = UUID.randomUUID();
        when(channelGroupRepo.findById(groupUuid)).thenReturn(Optional.empty());
        assertNull(fetcher.getNotificationChannelGroup(groupUuid));
        verify(authorizationService, never()).isUserAuthorizedForObjectGraphQL(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void listNotificationChannelGroupsAuthorizesAgainstOrgAndProjectsChannels() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID groupUuid = UUID.randomUUID();
        UUID ch1 = UUID.randomUUID();
        UUID ch2 = UUID.randomUUID();
        NotificationChannelGroup g = new NotificationChannelGroup();
        g.setUuid(groupUuid);
        NotificationChannelGroupData data = new NotificationChannelGroupData(
                orgUuid, null, "ops", List.of(ch1, ch2));
        g.setRecordData(Utils.OM.convertValue(data, Map.class));
        when(channelGroupService.listByOrg(orgUuid)).thenReturn(List.of(g));

        List<NotificationChannelGroupResult> results =
                fetcher.listNotificationChannelGroups(orgUuid);
        assertEquals(1, results.size());
        assertEquals("ops", results.get(0).name());
        assertEquals(List.of(ch1, ch2), results.get(0).channels());
        verify(authorizationService).isUserAuthorizedForObjectGraphQL(
                any(), any(), any(), eq(orgUuid), any(), any());
    }

    // ---------------- Phase-2 follow-up: inbox projection ----------------

    @Test
    void toInboxItemProjectsFormatterTextSeverityAndPayloadJson() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        NotificationDelivery d = newDelivery(UUID.randomUUID(), orgUuid);
        NotificationOutboxEvent event = newOutboxEvent(UUID.randomUUID(), orgUuid);
        // Payload carries a typed severity + a deep-link field the UI reads.
        Map<String, Object> payload = new HashMap<>();
        payload.put("severity", "CRITICAL");
        payload.put("cve", "CVE-2025-99999");
        event.setRecordData(payload);
        when(inboxFormatter.format(event))
                .thenReturn(new InboxRendering("New critical vuln", "CVE-2025-99999 affects 1 release"));

        NotificationInboxItem item = invokeToInboxItem(d, event, null);

        assertNotNull(item);
        assertEquals("New critical vuln", item.title());
        assertEquals("CVE-2025-99999 affects 1 release", item.description());
        assertEquals("CRITICAL", item.severity());
        assertEquals("NEW_VULN_AFFECTS_RELEASES", item.eventType());
        assertNotNull(item.payloadJson(), "payloadJson should serialize the event recordData");
        assertTrue(item.payloadJson().contains("CVE-2025-99999"),
                "payloadJson should round-trip the deep-link field: " + item.payloadJson());
        assertNull(item.readAt(), "Unread row projects null readAt, not a createdDate sentinel");
    }

    @Test
    void toInboxItemReturnsTruncationSentinelForOversizePayload() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        NotificationDelivery d = newDelivery(UUID.randomUUID(), orgUuid);
        NotificationOutboxEvent event = newOutboxEvent(UUID.randomUUID(), orgUuid);
        // A payload whose serialized JSON exceeds the 64KB cap.
        Map<String, Object> payload = new HashMap<>();
        payload.put("blob", "x".repeat(70 * 1024));
        event.setRecordData(payload);
        when(inboxFormatter.format(event)).thenReturn(InboxRendering.EMPTY);

        NotificationInboxItem item = invokeToInboxItem(d, event, null);

        assertNotNull(item);
        assertTrue(item.payloadJson().contains("\"_truncated\":true"),
                "Oversize payload should collapse to the truncation sentinel: " + item.payloadJson());
        assertTrue(!item.payloadJson().contains("xxxxxxxx"),
                "Sentinel must not carry the oversize blob");
    }

    @Test
    void toInboxItemCarriesResolvedChannelName() throws Exception {
        // The caller resolves the channel display name (server-side, no
        // admin gate) and hands it in; toInboxItem must surface it on the
        // item so the non-admin bell never needs the admin channel-list.
        UUID orgUuid = UUID.randomUUID();
        NotificationDelivery d = newDelivery(UUID.randomUUID(), orgUuid);
        NotificationOutboxEvent event = newOutboxEvent(UUID.randomUUID(), orgUuid);
        when(inboxFormatter.format(event)).thenReturn(InboxRendering.EMPTY);

        NotificationInboxItem item = invokeToInboxItem(d, event, null, "slack-prod");

        assertNotNull(item);
        assertEquals("slack-prod", item.channelName(),
                "channelName must carry the server-resolved display name");
    }

    @Test
    void markNotificationReadResolvesChannelNameWithoutAdminGate() throws Exception {
        // markNotificationRead resolves the one channel name via a plain
        // integrationRepo read (no authorizeOrgAdmin). Proves the resolved
        // IntegrationData.name lands on channelName.
        UUID orgUuid = UUID.randomUUID();
        UUID deliveryUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        NotificationDelivery d = newDelivery(deliveryUuid, orgUuid);
        d.setChannelUuid(channelUuid);
        // Org-admin member passes the authorizeOrgMember gate; the
        // channel-name resolution below still runs without the admin
        // channel-list query.
        stubUser(orgUuid, perm(orgUuid, PermissionScope.ORGANIZATION, null, PermissionType.ADMIN));
        when(deliveryRepo.findById(deliveryUuid)).thenReturn(Optional.of(d));
        when(deliveryRepo.existsDeliveryVisibleToUser(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
                .thenReturn(true);
        NotificationRead read = mock(NotificationRead.class);
        when(read.getReadAt()).thenReturn(ZonedDateTime.now());
        when(readService.markRead(any(), eq(deliveryUuid), any())).thenReturn(read);
        when(outboxRepo.findById(any())).thenReturn(Optional.empty());
        when(inboxFormatter.format(any())).thenReturn(InboxRendering.EMPTY);

        Integration channel = newChannel(channelUuid, orgUuid, "ops-slack",
                IntegrationType.SLACK, Boolean.TRUE, "secret", Map.of());
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(channel));

        NotificationInboxItem item = fetcher.markNotificationRead(deliveryUuid);

        assertNotNull(item);
        assertEquals("ops-slack", item.channelName(),
                "channelName must be resolved from the channel's IntegrationData name");
        assertEquals(Boolean.TRUE, item.channelEnabled(),
                "an enabled channel carries channelEnabled=true");
        assertNull(item.channelDisabledReason(), "enabled channel has no disabled reason");
        // Resolution does NOT go through the admin channel-list query.
        verify(integrationRepo, never()).listIntegrationsByOrg(any());
    }

    @Test
    void markNotificationReadCarriesDisabledChannelStateAndReason() throws Exception {
        // A channel that exists but was auto-disabled (e.g. bad webhook URL)
        // must surface channelEnabled=false + the reason so the inbox can show
        // "disabled (reason)" instead of dead-ending at "(deleted channel)".
        UUID orgUuid = UUID.randomUUID();
        UUID deliveryUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        NotificationDelivery d = newDelivery(deliveryUuid, orgUuid);
        d.setChannelUuid(channelUuid);
        stubUser(orgUuid, perm(orgUuid, PermissionScope.ORGANIZATION, null, PermissionType.ADMIN));
        when(deliveryRepo.findById(deliveryUuid)).thenReturn(Optional.of(d));
        when(deliveryRepo.existsDeliveryVisibleToUser(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
                .thenReturn(true);
        NotificationRead read = mock(NotificationRead.class);
        when(read.getReadAt()).thenReturn(ZonedDateTime.now());
        when(readService.markRead(any(), eq(deliveryUuid), any())).thenReturn(read);
        when(outboxRepo.findById(any())).thenReturn(Optional.empty());
        when(inboxFormatter.format(any())).thenReturn(InboxRendering.EMPTY);

        Integration channel = newChannel(channelUuid, orgUuid, "broken-slack",
                IntegrationType.SLACK, Boolean.FALSE, "secret", Map.of());
        IntegrationData cd = IntegrationData.dataFromRecord(channel);
        cd.setDisabledReason("Webhook URL is not a Slack host; re-enter and re-enable.");
        channel.setRecordData(Utils.dataToRecord(cd));
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(channel));

        NotificationInboxItem item = fetcher.markNotificationRead(deliveryUuid);

        assertNotNull(item);
        assertEquals("broken-slack", item.channelName(), "disabled channel still resolves its name");
        assertEquals(Boolean.FALSE, item.channelEnabled(), "disabled channel carries channelEnabled=false");
        assertTrue(item.channelDisabledReason() != null
                        && item.channelDisabledReason().contains("not a Slack host"),
                "channelDisabledReason carries the auto-disable reason, got: " + item.channelDisabledReason());
    }

    @Test
    void markNotificationReadLeavesChannelNameNullForDeletedChannel() throws Exception {
        // Deleted-channel case: the channelUuid no longer resolves to an
        // Integration, so channelName stays null (the bug's "(deleted
        // channel)" label is now a clean server-side null).
        UUID orgUuid = UUID.randomUUID();
        UUID deliveryUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        NotificationDelivery d = newDelivery(deliveryUuid, orgUuid);
        d.setChannelUuid(channelUuid);
        // Org-admin member passes the authorizeOrgMember gate; the
        // deleted-channel resolution below still yields a null channelName.
        stubUser(orgUuid, perm(orgUuid, PermissionScope.ORGANIZATION, null, PermissionType.ADMIN));
        when(deliveryRepo.findById(deliveryUuid)).thenReturn(Optional.of(d));
        when(deliveryRepo.existsDeliveryVisibleToUser(
                any(), any(), any(), org.mockito.ArgumentMatchers.anyBoolean(), any(), any()))
                .thenReturn(true);
        NotificationRead read = mock(NotificationRead.class);
        when(read.getReadAt()).thenReturn(ZonedDateTime.now());
        when(readService.markRead(any(), eq(deliveryUuid), any())).thenReturn(read);
        when(outboxRepo.findById(any())).thenReturn(Optional.empty());
        when(inboxFormatter.format(any())).thenReturn(InboxRendering.EMPTY);
        // Channel deleted: uuid resolves to nothing.
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.empty());

        NotificationInboxItem item = fetcher.markNotificationRead(deliveryUuid);

        assertNotNull(item);
        assertNull(item.channelName(),
                "channelName must be null when the channel uuid resolves to nothing");
    }

    private NotificationInboxItem invokeToInboxItem(NotificationDelivery d,
            NotificationOutboxEvent event, ZonedDateTime readAt) throws Exception {
        return invokeToInboxItem(d, event, readAt, null);
    }

    private NotificationInboxItem invokeToInboxItem(NotificationDelivery d,
            NotificationOutboxEvent event, ZonedDateTime readAt, String channelName) throws Exception {
        // toInboxItem now takes a ChannelInfo ref; wrap the plain name (these
        // tests don't exercise the enabled/reason fields).
        Object channelInfo = channelName == null ? null
                : new NotificationDataFetcher.ChannelInfo(channelName, Boolean.TRUE, null);
        Method m = NotificationDataFetcher.class.getDeclaredMethod("toInboxItem",
                NotificationDelivery.class, NotificationOutboxEvent.class, ZonedDateTime.class,
                NotificationDataFetcher.ChannelInfo.class);
        m.setAccessible(true);
        return (NotificationInboxItem) m.invoke(fetcher, d, event, readAt, channelInfo);
    }

    // ---------------- gap-4: inbox authz gate ----------------
    // The inbox member gate must accept any of: org-admin, a non-empty
    // perspective membership, a non-empty component-team membership, or
    // org-wide READ. The old ORGANIZATION-scoped object check FORBADE a
    // PERSPECTIVE/COMPONENT-scoped permission against the broader ORG
    // scope, so perspective- or component-only members got an opaque
    // "Not authorized" on their own inbox. A true non-member (no perms,
    // no org-wide read) still fails the gate.

    @Test
    void notificationInboxAcceptsPerspectiveOnlyMember() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID perspective = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.PERSPECTIVE, perspective, PermissionType.READ_ONLY));
        // Gate passes on the non-empty perspective arm regardless of the
        // org-wide-read verdict; stub FORBIDDEN to prove that arm isn't
        // what's carrying this case.
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.FORBIDDEN);
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(deliveryRepo.countInbox(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(0L);

        fetcher.getNotificationInbox(orgUuid, null, null, null, null, null, null);

        // Inbox query ran with isOrgAdmin=false and the perspective uuid
        // in the perspectives array literal.
        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(false),
                eq("{" + perspective + "}"), eq("{}"),
                anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notificationInboxAcceptsComponentOnlyMember() throws Exception {
        // A write-team (>= READ_WRITE) component-only member is in the audience
        // under the DEFAULT (watchers OFF -> READ_WRITE floor), with no
        // admin/perspective arm. (READ_ONLY membership is watcher-gated; see the
        // dedicated watcher tests below.)
        UUID orgUuid = UUID.randomUUID();
        UUID component = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.COMPONENT, component, PermissionType.READ_WRITE));
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.FORBIDDEN);
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(deliveryRepo.countInbox(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(0L);

        fetcher.getNotificationInbox(orgUuid, null, null, null, null, null, null);

        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(false),
                eq("{}"), eq("{" + component + "}"),
                anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notificationInboxWatchersOffExcludesReadOnlyFromAudienceButPassesGate() throws Exception {
        // Watcher lever DEFAULT OFF: a read-only (>= READ_ONLY, < READ_WRITE)
        // component member is NOT in the audience (component array empty), yet
        // still PASSES the gate on the fixed READ_ONLY membership floor -- the
        // query runs (no 403) and simply shows them targeted-only rows. This is
        // the gate/audience decoupling: watchers-off un-notifies read-only
        // viewers without locking them out of their inbox.
        UUID orgUuid = UUID.randomUUID();
        UUID component = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.COMPONENT, component, PermissionType.READ_ONLY));
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.FORBIDDEN);
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(deliveryRepo.countInbox(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(0L);

        fetcher.getNotificationInbox(orgUuid, null, null, null, null, null, null);

        // Passes the gate (query runs) but the read-only component is withheld
        // from the audience array.
        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(false),
                eq("{}"), eq("{}"),
                anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notificationInboxWatchersOnIncludesReadOnlyInAudience() throws Exception {
        // Watcher lever ON: the audience floor drops to READ_ONLY, so a read-only
        // component member IS collected into the audience (component array
        // carries the uuid) -- the opt-in "if you can see it, you hear about it".
        UUID orgUuid = UUID.randomUUID();
        UUID component = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.COMPONENT, component, PermissionType.READ_ONLY));
        stubWatchers(orgUuid, true);
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.FORBIDDEN);
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(deliveryRepo.countInbox(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(0L);

        fetcher.getNotificationInbox(orgUuid, null, null, null, null, null, null);

        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(false),
                eq("{}"), eq("{" + component + "}"),
                anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notificationInboxExcludesSubReadOnlyComponentGrant() throws Exception {
        // Gate membership floor: an ESSENTIAL_READ COMPONENT grant is BELOW the
        // fixed READ_ONLY inbox-gate floor (real view access), so it is not a
        // qualifying arm. With org-wide read FORBIDDEN and no perspective / admin
        // arm, the gate 403s. ESSENTIAL_READ/NONE stay excluded regardless of the
        // watcher lever, which only moves the audience floor at/above READ_ONLY.
        UUID orgUuid = UUID.randomUUID();
        UUID component = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.COMPONENT, component, PermissionType.ESSENTIAL_READ));
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.FORBIDDEN);

        assertThrows(RelizaException.class, () ->
                fetcher.getNotificationInbox(orgUuid, null, null, null, null, null, null));

        // The sub-floor component never reaches the visibility query.
        verify(deliveryRepo, never()).findInboxPage(any(), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notificationInboxExcludesNoneTierComponentGrant() throws Exception {
        // NONE is the lowest tier and likewise below the READ_ONLY floor - same
        // exclusion as ESSENTIAL_READ. A component grant of NONE puts nobody on
        // the inbox audience.
        UUID orgUuid = UUID.randomUUID();
        UUID component = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.COMPONENT, component, PermissionType.NONE));
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.FORBIDDEN);

        assertThrows(RelizaException.class, () ->
                fetcher.getNotificationInbox(orgUuid, null, null, null, null, null, null));

        verify(deliveryRepo, never()).findInboxPage(any(), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notificationInboxHonorsGroupGrantedComponentMembership() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID component = UUID.randomUUID();
        // Component-team membership that exists ONLY via a UserGroup: the
        // COMBINED permission set carries the COMPONENT grant, but the user's
        // OWN record has none. Pre-BUG-14 the inbox read own-record perms and
        // showed this user nothing for their component; it must now key off
        // the combined set. This verify fails if the fetcher reverts to
        // getOrgPermissions(own-record).
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.COMPONENT, component, PermissionType.READ_WRITE));
        when(ud.getOrgPermissions(orgUuid)).thenReturn(Set.of());
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.FORBIDDEN);
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(deliveryRepo.countInbox(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(0L);

        fetcher.getNotificationInbox(orgUuid, null, null, null, null, null, null);

        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(false),
                eq("{}"), eq("{" + component + "}"),
                anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notificationInboxHonorsGroupGrantedOrgAdmin() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        // ORGANIZATION/ADMIN granted ONLY via a UserGroup (own record empty)
        // must put the caller on the org-admin "see ALL org deliveries" arm.
        // This has the largest blast radius of the three arms.
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.ORGANIZATION, null, PermissionType.ADMIN));
        when(ud.getOrgPermissions(orgUuid)).thenReturn(Set.of());
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(deliveryRepo.countInbox(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(0L);

        // ORG_ALL scope opts the admin into the org-wide firehose.
        fetcher.getNotificationInbox(orgUuid, null, null, null, NotificationInboxScope.ORG_ALL, null, null);

        // The visibility query runs with isOrgAdmin=true.
        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(true),
                any(), any(), anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notificationInboxHonorsGroupGrantedPerspectiveMembership() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID perspective = UUID.randomUUID();
        // Perspective membership granted ONLY via a UserGroup (own record empty).
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.PERSPECTIVE, perspective, PermissionType.READ_ONLY));
        when(ud.getOrgPermissions(orgUuid)).thenReturn(Set.of());
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.FORBIDDEN);
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(deliveryRepo.countInbox(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(0L);

        fetcher.getNotificationInbox(orgUuid, null, null, null, null, null, null);

        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(false),
                eq("{" + perspective + "}"), eq("{}"),
                anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notificationInboxRejectsTrueNonMember() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        // No perms at all and no org-wide read -> fails all four arms.
        UserData ud = stubUser(orgUuid /* no perms */);
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.FORBIDDEN);

        assertThrows(RelizaException.class, () ->
                fetcher.getNotificationInbox(orgUuid, null, null, null, null, null, null));

        // The visibility query is never reached for a non-member.
        verify(deliveryRepo, never()).findInboxPage(any(), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void markNotificationReadRejectsTrueNonMember() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID deliveryUuid = UUID.randomUUID();
        NotificationDelivery d = newDelivery(deliveryUuid, orgUuid);
        when(deliveryRepo.findById(deliveryUuid)).thenReturn(Optional.of(d));
        UserData ud = stubUser(orgUuid /* no perms */);
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.FORBIDDEN);

        assertThrows(RelizaException.class, () ->
                fetcher.markNotificationRead(deliveryUuid));

        // Never reached the visibility check or the read-state write.
        verify(deliveryRepo, never()).existsDeliveryVisibleToUser(
                any(), any(), any(), anyBoolean(), any(), any());
        verify(readService, never()).markRead(any(), any(), any());
    }

    @Test
    void notificationInboxAcceptsOrgAdmin() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.ORGANIZATION, null, PermissionType.ADMIN));
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(deliveryRepo.countInbox(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(0L);

        // ORG_ALL scope opts the admin into the org-wide firehose.
        fetcher.getNotificationInbox(orgUuid, null, null, null, NotificationInboxScope.ORG_ALL, null, null);

        // Admin passes; isOrgAdmin=true plumbed into the visibility query.
        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(true),
                eq("{}"), eq("{}"), anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notificationInboxAcceptsOrgReadOnlyNonAdmin() throws Exception {
        // Regression guard: an org-READ-only non-admin (no perspective /
        // component perms) must keep today's behavior — pass the gate via
        // the org-wide-read arm, then see an empty/targeted-only inbox —
        // and NOT newly 403.
        UUID orgUuid = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.ORGANIZATION, null, PermissionType.READ_ONLY));
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.AUTHORIZED);
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(deliveryRepo.countInbox(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(0L);

        fetcher.getNotificationInbox(orgUuid, null, null, null, null, null, null);

        // Not org-admin, empty perspective+component arrays — the org-wide
        // read arm is the only thing carrying this caller through.
        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(false),
                eq("{}"), eq("{}"), anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notificationInboxAdminPersonalScopeWithholdsFirehose() throws Exception {
        // Phase 2 core behavior: PERSONAL scope (null default) withholds the
        // org-admin "see ALL org deliveries" firehose even from a real admin,
        // decluttering their bell to their own actionable rows. The admin
        // still PASSES the gate (real isOrgAdmin), only the visibility scope
        // is withheld -> the query receives isOrgAdmin=false.
        UUID orgUuid = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.ORGANIZATION, null, PermissionType.ADMIN));
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(deliveryRepo.countInbox(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(0L);

        fetcher.getNotificationInbox(orgUuid, null, null, null, null, null, null);

        // Admin passes the gate, but the firehose arm is withheld under PERSONAL.
        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(false),
                eq("{}"), eq("{}"), anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notificationInboxAdminOrgAllScopeShowsFirehose() throws Exception {
        // ORG_ALL scope opts a real admin into the org-wide firehose ->
        // the query receives isOrgAdmin=true.
        UUID orgUuid = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.ORGANIZATION, null, PermissionType.ADMIN));
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(deliveryRepo.countInbox(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(0L);

        fetcher.getNotificationInbox(orgUuid, null, null, null, NotificationInboxScope.ORG_ALL, null, null);

        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(true),
                eq("{}"), eq("{}"), anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void notificationInboxNonAdminOrgAllScopeCollapsesToPersonal() throws Exception {
        // A non-admin (write-team component member) requesting ORG_ALL safely
        // collapses to PERSONAL: effectiveOrgAdmin AND-gates on the REAL
        // isOrgAdmin, so the query receives isOrgAdmin=false. The caller
        // still qualifies via the component arm, so the query runs.
        // (READ_WRITE so the member is in the audience under the default
        // watchers-OFF floor; the collapse is what this test pins.)
        UUID orgUuid = UUID.randomUUID();
        UUID component = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.COMPONENT, component, PermissionType.READ_WRITE));
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.FORBIDDEN);
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(deliveryRepo.countInbox(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any())).thenReturn(0L);

        fetcher.getNotificationInbox(orgUuid, null, null, null, NotificationInboxScope.ORG_ALL, null, null);

        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(false),
                eq("{}"), eq("{" + component + "}"),
                anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void unreadCountAdminPersonalWithholdsFirehose() throws Exception {
        // Unread badge under PERSONAL (null default): a real admin's badge
        // reflects their own actionable count, not the org-wide firehose ->
        // countUnread receives isOrgAdmin=false.
        UUID orgUuid = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.ORGANIZATION, null, PermissionType.ADMIN));
        when(readService.countUnread(any(), any(), any(), any(), anyBoolean())).thenReturn(0L);

        fetcher.getNotificationUnreadCount(orgUuid, null);

        // isOrgAdmin is the trailing boolean on countUnread.
        verify(readService).countUnread(any(), eq(orgUuid), any(), any(), eq(false));
    }

    @Test
    void unreadCountAdminOrgAllShowsFirehose() throws Exception {
        // ORG_ALL opts the admin's badge into the org-wide unread count ->
        // countUnread receives isOrgAdmin=true.
        UUID orgUuid = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.ORGANIZATION, null, PermissionType.ADMIN));
        when(readService.countUnread(any(), any(), any(), any(), anyBoolean())).thenReturn(0L);

        fetcher.getNotificationUnreadCount(orgUuid, NotificationInboxScope.ORG_ALL);

        verify(readService).countUnread(any(), eq(orgUuid), any(), any(), eq(true));
    }

    @Test
    void unreadCountNonAdminOrgAllCollapsesToPersonal() throws Exception {
        // Badge-side no-escalation twin of the list test: a non-admin
        // (component-only member) asking for the ORG_ALL badge safely
        // collapses to PERSONAL -> countUnread receives isOrgAdmin=false.
        UUID orgUuid = UUID.randomUUID();
        UUID component = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.COMPONENT, component, PermissionType.READ_ONLY));
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.FORBIDDEN);
        when(readService.countUnread(any(), any(), any(), any(), anyBoolean())).thenReturn(0L);

        fetcher.getNotificationUnreadCount(orgUuid, NotificationInboxScope.ORG_ALL);

        verify(readService).countUnread(any(), eq(orgUuid), any(), any(), eq(false));
    }

    @Test
    void markAllNotificationsReadPersonalScopeDoesNotFirehose() throws Exception {
        // Phase 2 Medium fix: a mark-all under the default PERSONAL view must
        // match the bell it is attached to and sweep only the caller's own
        // unread -- NOT firehose every org delivery a real admin never saw.
        // The sweep query runs with isOrgAdmin=false even for a real admin.
        UUID orgUuid = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.ORGANIZATION, null, PermissionType.ADMIN));
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(readService.markManyRead(any(), any(), any())).thenReturn(0);

        fetcher.markAllNotificationsRead(orgUuid, null);

        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(false),
                eq("{}"), eq("{}"), anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void markAllNotificationsReadOrgAllScopeSweepsFirehose() throws Exception {
        // The explicit ORG_ALL sweep still lets an admin clear the org-wide
        // firehose -> the sweep query runs with isOrgAdmin=true.
        UUID orgUuid = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.ORGANIZATION, null, PermissionType.ADMIN));
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(readService.markManyRead(any(), any(), any())).thenReturn(0);

        fetcher.markAllNotificationsRead(orgUuid, NotificationInboxScope.ORG_ALL);

        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(true),
                eq("{}"), eq("{}"), anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    @Test
    void markAllNotificationsReadNonAdminOrgAllCollapsesToPersonal() throws Exception {
        // No-escalation twin for the sweep: a non-admin asking for an ORG_ALL
        // mark-all collapses to PERSONAL -> the sweep query runs with
        // isOrgAdmin=false, so the mutation can never clear rows the caller
        // was not entitled to see.
        UUID orgUuid = UUID.randomUUID();
        UUID component = UUID.randomUUID();
        UserData ud = stubUser(orgUuid,
                perm(orgUuid, PermissionScope.COMPONENT, component, PermissionType.READ_WRITE));
        when(authorizationService.isUserAuthorizedOrgWide(ud, orgUuid, CallType.READ))
                .thenReturn(AuthorizationStatus.FORBIDDEN);
        when(deliveryRepo.findInboxPage(eq(orgUuid), any(), anyBoolean(), any(), any(),
                anyBoolean(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(readService.markManyRead(any(), any(), any())).thenReturn(0);

        fetcher.markAllNotificationsRead(orgUuid, NotificationInboxScope.ORG_ALL);

        verify(deliveryRepo).findInboxPage(eq(orgUuid), any(), eq(false),
                eq("{}"), eq("{" + component + "}"),
                anyBoolean(), any(), any(), anyInt(), anyInt());
    }

    /**
     * Stub the JWT-bound user as a mock whose org permission set is the
     * supplied list. {@code getUserDataByAuth} normally returns a fresh
     * mock per call (see wireMocks); override it here so the gate reads
     * our permissions and the org-wide-read stub matches this instance.
     */
    private UserData stubUser(UUID orgUuid, UserPermission... perms) {
        UserData ud = mock(UserData.class);
        when(ud.getUuid()).thenReturn(UUID.randomUUID());
        // The inbox gate reads the user's COMBINED org permissions (own record
        // + UserGroups) via obtainCombinedUserOrgPermissions, not the raw
        // own-record getOrgPermissions (BUG 14). Stub that path.
        Permissions combined = mock(Permissions.class);
        when(combined.getOrgPermissionsAsSet(orgUuid)).thenReturn(Set.of(perms));
        when(organizationService.obtainCombinedUserOrgPermissions(ud, orgUuid)).thenReturn(combined);
        when(userService.getUserDataByAuth(any(JwtAuthenticationToken.class)))
                .thenReturn(Optional.of(ud));
        return ud;
    }

    private static UserPermission perm(UUID orgUuid, PermissionScope scope,
            UUID object, PermissionType type) {
        return UserPermission.permissionFactory(orgUuid, scope, object, type, null, null);
    }

    /**
     * Override the org lookup for {@code orgUuid} with an OrganizationData whose
     * Settings expose the given {@code notifyComponentWatchers} value, so the
     * inbox audience floor resolves to READ_ONLY (on) or READ_WRITE (off, the
     * default the base wireMocks stub already yields via a settings-less mock).
     */
    private void stubWatchers(UUID orgUuid, boolean on) {
        OrganizationData od = mock(OrganizationData.class);
        OrganizationData.Settings settings = mock(OrganizationData.Settings.class);
        when(settings.isNotifyComponentWatchersEnabled()).thenReturn(on);
        when(od.getSettings()).thenReturn(settings);
        when(getOrganizationService.getOrganizationData(orgUuid)).thenReturn(Optional.of(od));
    }

    // ---------------- helpers ----------------

    private static NotificationOutboxEvent newOutboxEvent(UUID uuid, UUID org) {
        NotificationOutboxEvent e = new NotificationOutboxEvent();
        e.setUuid(uuid);
        e.setOrg(org);
        e.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        e.setStatus(NotificationOutboxStatus.PENDING);
        e.setOccurredAt(ZonedDateTime.now());
        e.setOrigin(NotificationDeliveryOrigin.REAL);
        e.setRecordData(new HashMap<>());
        return e;
    }

    private static NotificationDelivery newDelivery(UUID uuid, UUID org) {
        NotificationDelivery d = new NotificationDelivery();
        d.setUuid(uuid);
        d.setOrg(org);
        d.setOutboxEventUuid(UUID.randomUUID());
        d.setChannelUuid(UUID.randomUUID());
        d.setStatus(NotificationDeliveryStatus.PENDING);
        d.setOrigin(NotificationDeliveryOrigin.REAL);
        d.setAttemptCount(0);
        d.setNextAttemptAt(ZonedDateTime.now());
        d.setCreatedDate(ZonedDateTime.now());
        d.setRecordData(new HashMap<>());
        return d;
    }

    private static Integration newChannel(UUID uuid, UUID org, String name,
            IntegrationType type, Boolean isEnabled, String secret,
            Map<String, Object> parameters) {
        Integration c = new Integration();
        c.setUuid(uuid);
        IntegrationData data = new IntegrationData();
        data.setUuid(uuid);
        data.setIdentifier(uuid.toString());
        data.setOrg(org);
        data.setName(name);
        data.setType(type);
        data.setIsEnabled(isEnabled);
        data.setSecret(secret);
        data.setParameters(new HashMap<>(parameters));
        c.setRecordData(Utils.dataToRecord(data));
        return c;
    }

    /** A CI / non-notification integration row (null name) sharing the table. */
    private static Integration newCiIntegration(UUID uuid, UUID org, IntegrationType type) {
        Integration c = new Integration();
        c.setUuid(uuid);
        IntegrationData data = new IntegrationData();
        data.setUuid(uuid);
        data.setIdentifier("base");
        data.setOrg(org);
        data.setType(type);
        data.setIsEnabled(Boolean.TRUE);
        c.setRecordData(Utils.dataToRecord(data));
        return c;
    }

    @SuppressWarnings("unchecked")
    private static NotificationSubscription newSubscription(UUID uuid, NotificationSubscriptionData data) {
        NotificationSubscription s = new NotificationSubscription();
        s.setUuid(uuid);
        s.setRecordData(Utils.OM.convertValue(data, Map.class));
        return s;
    }
}
