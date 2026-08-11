/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.notifications.NotificationChannelInput.EmailConfigInput;
import io.reliza.model.dto.notifications.NotificationChannelInput.SlackConfigInput;
import io.reliza.model.dto.notifications.NotificationChannelInput.WebhookConfigInput;
import io.reliza.model.EmailDigestPolicy;
import io.reliza.model.WebhookAuthScheme;
import io.reliza.model.WebhookChannelSecret;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.service.AuditService;
import io.reliza.service.EncryptionService;

/**
 * Covers the Phase 3 channel CRUD service after the Phase 2b-1 fold of
 * notification channels into the {@link Integration} table:
 * <ul>
 *   <li>Create vs update branching on UUID presence.</li>
 *   <li>Webhook URL encryption at create + preservation on update.</li>
 *   <li>Slack URL prefix enforcement at save time.</li>
 *   <li>JSONB size cap enforcement.</li>
 *   <li>Status transitions (no-op when state matches; audit + save when not).</li>
 *   <li>Audit emission on update + delete (TableName.INTEGRATIONS).</li>
 * </ul>
 */
class NotificationChannelServiceTest {

    private IntegrationRepository integrationRepo;
    private AuditService auditService;
    private EncryptionService encryptionService;
    private NotificationDeliveryRepository deliveryRepo;
    private NotificationChannelService service;

    @BeforeEach
    void wireMocks() throws Exception {
        integrationRepo = mock(IntegrationRepository.class);
        auditService = mock(AuditService.class);
        encryptionService = mock(EncryptionService.class);
        deliveryRepo = mock(NotificationDeliveryRepository.class);
        // save returns the input
        when(integrationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        // encrypt: prefix the plaintext so tests can assert it was called
        when(encryptionService.encrypt(anyString())).thenAnswer(inv ->
                "ENC:" + inv.getArgument(0));

        service = new NotificationChannelService();
        inject("integrationRepo", integrationRepo);
        inject("auditService", auditService);
        inject("encryptionService", encryptionService);
        inject("deliveryRepo", deliveryRepo);
    }

    /**
     * EMAIL/SENTINEL channels hit the {@code isOssEdition()} gate in
     * {@code validateSeed}; those features are Pro-only and correctly gated
     * off on the OSS/CE edition. Skip such cases cleanly there so the
     * CE-mirrored copy of this test stays green.
     */
    private static void assumeProEdition() {
        org.junit.jupiter.api.Assumptions.assumeFalse(
            io.reliza.common.oss.LicensingConstants.isOssEdition(),
            "Pro-only feature; skipped on OSS edition");
    }

    private void inject(String field, Object value) throws Exception {
        Field f = NotificationChannelService.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(service, value);
    }

    /** Build a bare IntegrationData seed (no secret/parameters — those resolve server-side). */
    private static IntegrationData seed(UUID org, String name, IntegrationType type,
            Boolean isEnabled) {
        IntegrationData d = new IntegrationData();
        d.setOrg(org);
        d.setName(name);
        d.setType(type);
        d.setIsEnabled(isEnabled);
        d.setParameters(new HashMap<>());
        return d;
    }

    private static IntegrationData parse(Integration channel) {
        return IntegrationData.dataFromRecord(channel);
    }

    @Test
    void createEncryptsWebhookUrlAndPersists() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        Integration saved = service.upsertChannel(
                null, seed(orgUuid, "slack-prod", IntegrationType.SLACK, Boolean.TRUE),
                new SlackConfigInput("https://hooks.slack.com/services/T00/B00/abc"), null,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        assertNotNull(saved.getUuid(), "Create path should generate a uuid");
        IntegrationData persisted = parse(saved);
        assertEquals("ENC:https://hooks.slack.com/services/T00/B00/abc",
                persisted.getSecret(),
                "Plaintext URL should have been routed through EncryptionService");
        assertEquals("slack-prod", persisted.getName());
        // No prior row = no audit emitted on this create
        verify(auditService, never()).createAndSaveAuditRecord(any(), any());
    }

    @Test
    void updatePreservesEncryptedSecretWhenWebhookUrlIsBlank() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration existing = stubChannel(channelUuid, orgUuid, "ENC:old-secret", true);
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        Integration saved = service.upsertChannel(
                channelUuid, seed(orgUuid, "slack-prod-renamed", IntegrationType.SLACK, Boolean.TRUE),
                null /* blank slackConfig — preserves existing */, null,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        IntegrationData persisted = parse(saved);
        assertEquals("ENC:old-secret", persisted.getSecret(),
                "Blank webhook URL on update should preserve existing encrypted value");
        assertEquals("slack-prod-renamed", persisted.getName(),
                "Other fields should still update");
        // Audit emitted because the row pre-existed
        verify(auditService).createAndSaveAuditRecord(
                eq(TableName.INTEGRATIONS), eq(existing));
    }

    @Test
    void updateReplacesEncryptedSecretWhenWebhookUrlProvided() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration existing = stubChannel(channelUuid, orgUuid, "ENC:old-secret", true);
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        Integration saved = service.upsertChannel(
                channelUuid, seed(orgUuid, "slack-prod", IntegrationType.SLACK, Boolean.TRUE),
                new SlackConfigInput("https://hooks.slack.com/services/T11/B22/newsecret"), null,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        IntegrationData persisted = parse(saved);
        assertEquals("ENC:https://hooks.slack.com/services/T11/B22/newsecret",
                persisted.getSecret(),
                "Non-blank webhook URL on update should overwrite encrypted value");
    }

    @Test
    void slackUrlPrefixEnforcedAtSave() {
        UUID orgUuid = UUID.randomUUID();
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertChannel(null, seed(orgUuid, "evil", IntegrationType.SLACK, Boolean.TRUE),
                        new SlackConfigInput("https://hooks.evil.com/services/T00/B00/abc"), null,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("hooks.slack.com"),
                "Save-time check should reject non-Slack URL: " + e.getMessage());
    }

    @Test
    void missingRequiredFieldsRejected() {
        UUID orgUuid = UUID.randomUUID();
        // Missing name
        assertThrows(RelizaException.class, () ->
                service.upsertChannel(null, seed(orgUuid, null, IntegrationType.SLACK, Boolean.TRUE),
                        new SlackConfigInput("https://hooks.slack.com/services/T00/B00/abc"), null,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
    }

    @Test
    void recordDataSizeCapRejectsOversized() {
        UUID orgUuid = UUID.randomUUID();
        // Bloated parameters to trip the 256KB cap
        Map<String, Object> bigConfig = new HashMap<>();
        StringBuilder pad = new StringBuilder();
        for (int i = 0; i < 300_000; i++) pad.append('A');
        bigConfig.put("pad", pad.toString());
        IntegrationData s = seed(orgUuid, "big", IntegrationType.SLACK, Boolean.TRUE);
        s.setParameters(bigConfig);

        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertChannel(null, s,
                        new SlackConfigInput("https://hooks.slack.com/services/T00/B00/abc"), null,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("exceeds"),
                "Expected size-cap rejection, got: " + e.getMessage());
    }

    @Test
    void setStatusToCurrentValueIsNoOp() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration existing = stubChannel(channelUuid, orgUuid, "ENC:secret", true);
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        Integration saved = service.setChannelStatus(
                channelUuid, /*enabled*/ true,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        // Same-value transition: no audit, no save
        verify(auditService, never()).createAndSaveAuditRecord(any(), any());
        verify(integrationRepo, never()).save(any());
        // Returns the existing row unchanged
        assertEquals(existing, saved);
    }

    @Test
    void setStatusToDifferentValueAuditsAndSaves() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration existing = stubChannel(channelUuid, orgUuid, "ENC:secret", true);
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        service.setChannelStatus(channelUuid, /*enabled*/ false,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        verify(auditService).createAndSaveAuditRecord(
                eq(TableName.INTEGRATIONS), eq(existing));
        ArgumentCaptor<Integration> captor = ArgumentCaptor.forClass(Integration.class);
        verify(integrationRepo).save(captor.capture());
        IntegrationData persisted = parse(captor.getValue());
        assertEquals(Boolean.FALSE, persisted.getIsEnabled());
    }

    @Test
    void autoDisableSetsReasonAndDisables() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        Integration existing = stubChannel(channelUuid, UUID.randomUUID(), "ENC:secret", true);
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        service.autoDisableForMisconfiguration(channelUuid, "Webhook URL is not a Slack host");

        verify(auditService).createAndSaveAuditRecord(eq(TableName.INTEGRATIONS), eq(existing));
        ArgumentCaptor<Integration> captor = ArgumentCaptor.forClass(Integration.class);
        verify(integrationRepo).save(captor.capture());
        IntegrationData persisted = parse(captor.getValue());
        assertEquals(Boolean.FALSE, persisted.getIsEnabled());
        assertEquals("Webhook URL is not a Slack host", persisted.getDisabledReason());
    }

    @Test
    void autoDisableIsIdempotentWhenAlreadyDisabledWithSameReason() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        Integration existing = stubChannel(channelUuid, UUID.randomUUID(), "ENC:secret", false);
        IntegrationData d = parse(existing);
        d.setDisabledReason("bad host");
        existing.setRecordData(Utils.dataToRecord(d));
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        service.autoDisableForMisconfiguration(channelUuid, "bad host");

        // Nothing changed -- no audit, no save (skip revision churn).
        verify(auditService, never()).createAndSaveAuditRecord(any(), any());
        verify(integrationRepo, never()).save(any());
    }

    @Test
    void reEnablingClearsDisabledReason() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        Integration existing = stubChannel(channelUuid, UUID.randomUUID(), "ENC:secret", false);
        IntegrationData d = parse(existing);
        d.setDisabledReason("bad host");
        existing.setRecordData(Utils.dataToRecord(d));
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        service.setChannelStatus(channelUuid, /*enabled*/ true,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        ArgumentCaptor<Integration> captor = ArgumentCaptor.forClass(Integration.class);
        verify(integrationRepo).save(captor.capture());
        IntegrationData persisted = parse(captor.getValue());
        assertEquals(Boolean.TRUE, persisted.getIsEnabled());
        assertNull(persisted.getDisabledReason());
    }

    @Test
    void deleteChannelEmitsAuditBeforeDeletion() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        Integration existing = stubChannel(channelUuid, UUID.randomUUID(), "ENC:s", true);
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        service.deleteChannel(channelUuid);

        verify(auditService).createAndSaveAuditRecord(
                eq(TableName.INTEGRATIONS), eq(existing));
        verify(integrationRepo, times(1)).deleteById(channelUuid);
    }

    @Test
    void deleteChannelOnMissIsNoOp() throws Exception {
        UUID channelUuid = UUID.randomUUID();
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.empty());

        service.deleteChannel(channelUuid);

        verify(auditService, never()).createAndSaveAuditRecord(any(), any());
        verify(integrationRepo, never()).deleteById(any());
    }

    @Test
    void createWithNullStatusDefaultsToEnabled() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        Integration saved = service.upsertChannel(null,
                seed(orgUuid, "slack-prod", IntegrationType.SLACK, null /* status unset */),
                new SlackConfigInput("https://hooks.slack.com/services/T00/B00/abc"), null,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        IntegrationData persisted = parse(saved);
        assertEquals(Boolean.TRUE, persisted.getIsEnabled());
    }

    @Test
    void upsertWithSuppliedButNonexistentUuidIsRejected() {
        // Layer-2 BLOCKER fix: matches AgentPolicyService / CommitterService /
        // WebhookService convention. uuid != null + not found = throw,
        // not create-with-claimed-uuid.
        UUID orgUuid = UUID.randomUUID();
        UUID claimedUuid = UUID.randomUUID();
        when(integrationRepo.findById(claimedUuid)).thenReturn(Optional.empty());

        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertChannel(claimedUuid,
                        seed(orgUuid, "evil", IntegrationType.SLACK, Boolean.TRUE),
                        new SlackConfigInput("https://hooks.slack.com/services/T00/B00/abc"), null,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("Channel not found"),
                "Expected not-found rejection, got: " + e.getMessage());
        // The row was NOT created
        verify(integrationRepo, never()).save(any());
    }

    @Test
    void blankWebhookUrlOnCreateLandsNullEncryptedSecret() throws Exception {
        // Defensible behaviour: create-without-URL succeeds (so a customer
        // can stub the row and provide the URL later), but the dispatcher
        // will reject at runtime.
        UUID orgUuid = UUID.randomUUID();
        Integration saved = service.upsertChannel(null,
                seed(orgUuid, "slack-prod", IntegrationType.SLACK, Boolean.TRUE), null, null,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        IntegrationData persisted = parse(saved);
        assertNull(persisted.getSecret());
    }

    @Test
    void createWebhookChannelEncryptsConfigBlob() throws Exception {
        // Phase 4: generic webhook channel. URL + auth scheme + token
        // get packed into WebhookChannelSecret, JSON-serialized, then
        // encrypted as a single blob.
        UUID orgUuid = UUID.randomUUID();
        Integration saved = service.upsertChannel(
                null, seed(orgUuid, "pd-prod", IntegrationType.WEBHOOK, Boolean.TRUE), null,
                new WebhookConfigInput("https://events.pagerduty.com/v2/enqueue",
                        WebhookAuthScheme.BEARER, "secret-token-xyz"),
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        IntegrationData persisted = parse(saved);
        assertTrue(persisted.getSecret().startsWith("ENC:"),
                "Webhook config should have been encrypted, got: " + persisted.getSecret());
        String innerJson = persisted.getSecret().substring(4);
        WebhookChannelSecret unwrapped = Utils.OM.readValue(innerJson, WebhookChannelSecret.class);
        assertEquals("https://events.pagerduty.com/v2/enqueue", unwrapped.url());
        assertEquals(WebhookAuthScheme.BEARER, unwrapped.authScheme());
        assertEquals("secret-token-xyz", unwrapped.authToken());
    }

    @Test
    void webhookUrlMustBeHttps() {
        UUID orgUuid = UUID.randomUUID();
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertChannel(null, seed(orgUuid, "evil", IntegrationType.WEBHOOK, Boolean.TRUE),
                        null,
                        new WebhookConfigInput("http://insecure.example.com/hook",
                                WebhookAuthScheme.NONE, null),
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("HTTPS"),
                "Expected HTTPS-only rejection, got: " + e.getMessage());
    }

    @Test
    void webhookBearerSchemeRequiresAuthToken() {
        UUID orgUuid = UUID.randomUUID();
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertChannel(null,
                        seed(orgUuid, "missing-token", IntegrationType.WEBHOOK, Boolean.TRUE), null,
                        new WebhookConfigInput("https://example.com/hook",
                                WebhookAuthScheme.BEARER, null /* missing token */),
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("non-blank authToken"),
                "Expected auth-token-required rejection, got: " + e.getMessage());
    }

    @Test
    void webhookNoneSchemeAcceptsBlankToken() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        // No auth → no token required
        Integration saved = service.upsertChannel(null,
                seed(orgUuid, "open-receiver", IntegrationType.WEBHOOK, Boolean.TRUE), null,
                new WebhookConfigInput("https://internal-ip-allowlisted.example.com/hook",
                        WebhookAuthScheme.NONE, null),
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        IntegrationData persisted = parse(saved);
        String innerJson = persisted.getSecret().substring(4);
        WebhookChannelSecret unwrapped = Utils.OM.readValue(innerJson, WebhookChannelSecret.class);
        assertEquals(WebhookAuthScheme.NONE, unwrapped.authScheme());
        assertNull(unwrapped.authToken());
    }

    @Test
    void mismatchedConfigForSlackTypeIsRejected() {
        UUID orgUuid = UUID.randomUUID();
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertChannel(null,
                        seed(orgUuid, "wrong-shape", IntegrationType.SLACK, Boolean.TRUE),
                        null,
                        new WebhookConfigInput("https://example.com/hook",
                                WebhookAuthScheme.NONE, null),
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("type=SLACK does not accept webhookConfig"),
                "Expected type-mismatch rejection naming the offending field, got: " + e.getMessage());
    }

    @Test
    void mismatchedConfigForWebhookTypeIsRejected() {
        UUID orgUuid = UUID.randomUUID();
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertChannel(null,
                        seed(orgUuid, "wrong-shape", IntegrationType.WEBHOOK, Boolean.TRUE),
                        new SlackConfigInput("https://hooks.slack.com/services/T/B/x"),
                        null,
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("type=WEBHOOK does not accept slackConfig"),
                "Expected type-mismatch rejection naming the offending field, got: " + e.getMessage());
    }

    @Test
    void webhookAuthTokenWithControlCharsIsRejected() {
        UUID orgUuid = UUID.randomUUID();
        RelizaException e = assertThrows(RelizaException.class, () ->
                service.upsertChannel(null,
                        seed(orgUuid, "injection-attempt", IntegrationType.WEBHOOK, Boolean.TRUE), null,
                        new WebhookConfigInput("https://example.com/hook",
                                WebhookAuthScheme.BEARER,
                                "valid-token\r\nX-Injected: evil"),
                        WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(e.getMessage().contains("control characters"),
                "Expected control-char rejection, got: " + e.getMessage());
    }

    @Test
    void mixedCaseHttpsAcceptedForWebhookUrl() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        // Should succeed despite uppercase scheme
        Integration saved = service.upsertChannel(null,
                seed(orgUuid, "mixed-case", IntegrationType.WEBHOOK, Boolean.TRUE), null,
                new WebhookConfigInput("HTTPS://example.com/hook",
                        WebhookAuthScheme.NONE, null),
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));
        assertNotNull(saved.getUuid());
    }

    @Test
    void webhookUpdateWithBlankConfigPreservesExistingSecret() throws Exception {
        // Same preserve-on-blank idiom as Slack: rename without re-typing.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration existing = stubChannel(channelUuid, orgUuid, "ENC:existing-encrypted-blob",
                true, IntegrationType.WEBHOOK, "pd-prod");
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        // Blank webhook config → preserve existing
        Integration saved = service.upsertChannel(
                channelUuid, seed(orgUuid, "pd-prod-renamed", IntegrationType.WEBHOOK, Boolean.TRUE),
                null, null,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        IntegrationData persisted = parse(saved);
        assertEquals("ENC:existing-encrypted-blob", persisted.getSecret(),
                "Blank webhook config on update should preserve existing encrypted blob");
        assertEquals("pd-prod-renamed", persisted.getName());
    }

    // ---------- Phase 5: email digest config ----------

    @Test
    void createEmailChannelPersistsRecipientsAndDigestConfig() throws Exception {
        assumeProEdition();
        Integration saved = service.upsertChannel(null, null,
                seed(UUID.randomUUID(), "email-ops", IntegrationType.EMAIL, Boolean.TRUE),
                null, null,
                new EmailConfigInput(java.util.List.of(" ops@example.com "),
                        EmailDigestPolicy.EmailDigestMode.ROLLING, "PT12H"),
                null, null, WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        Map<String, Object> params = parse(saved).getParameters();
        assertEquals(java.util.List.of("ops@example.com"),
                params.get(EmailDigestPolicy.RECIPIENTS_KEY), "Recipients must be trimmed");
        assertEquals("ROLLING", params.get(EmailDigestPolicy.DIGEST_MODE_KEY));
        assertEquals("PT12H", params.get(EmailDigestPolicy.DIGEST_INTERVAL_KEY));
    }

    @Test
    void digestOnlyUpdatePreservesRecipients() throws Exception {
        assumeProEdition();
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration existing = emailChannelWithParams(channelUuid, orgUuid, Map.of(
                EmailDigestPolicy.RECIPIENTS_KEY, java.util.List.of("ops@example.com"),
                EmailDigestPolicy.DIGEST_INTERVAL_KEY, "PT12H"));
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        Integration saved = service.upsertChannel(channelUuid, null,
                seed(orgUuid, "email-ops", IntegrationType.EMAIL, Boolean.TRUE),
                null, null,
                new EmailConfigInput(null, EmailDigestPolicy.EmailDigestMode.IMMEDIATE, null),
                null, null, WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        Map<String, Object> params = parse(saved).getParameters();
        assertEquals(java.util.List.of("ops@example.com"),
                params.get(EmailDigestPolicy.RECIPIENTS_KEY),
                "Digest-only update must not drop the recipient list");
        assertEquals("IMMEDIATE", params.get(EmailDigestPolicy.DIGEST_MODE_KEY));
        assertEquals("PT12H", params.get(EmailDigestPolicy.DIGEST_INTERVAL_KEY),
                "Unsupplied digest interval must be preserved");
    }

    @Test
    void recipientsOnlyUpdatePreservesDigestConfig() throws Exception {
        assumeProEdition();
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration existing = emailChannelWithParams(channelUuid, orgUuid, Map.of(
                EmailDigestPolicy.RECIPIENTS_KEY, java.util.List.of("old@example.com"),
                EmailDigestPolicy.DIGEST_MODE_KEY, "IMMEDIATE",
                EmailDigestPolicy.DIGEST_INTERVAL_KEY, "PT6H"));
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        Integration saved = service.upsertChannel(channelUuid, null,
                seed(orgUuid, "email-ops", IntegrationType.EMAIL, Boolean.TRUE),
                null, null,
                new EmailConfigInput(java.util.List.of("new@example.com"), null, null),
                null, null, WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        Map<String, Object> params = parse(saved).getParameters();
        assertEquals(java.util.List.of("new@example.com"),
                params.get(EmailDigestPolicy.RECIPIENTS_KEY));
        assertEquals("IMMEDIATE", params.get(EmailDigestPolicy.DIGEST_MODE_KEY),
                "Recipients-only update must not drop the digest mode");
        assertEquals("PT6H", params.get(EmailDigestPolicy.DIGEST_INTERVAL_KEY));
    }

    @Test
    void entirelyEmptyEmailConfigIsRejected() {
        assumeProEdition();
        RelizaException ex = assertThrows(RelizaException.class, () ->
                service.upsertChannel(null, null,
                        seed(UUID.randomUUID(), "email-ops", IntegrationType.EMAIL, Boolean.TRUE),
                        null, null, new EmailConfigInput(null, null, null),
                        null, null, WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(ex.getMessage().contains("recipient"), ex.getMessage());
    }

    @Test
    void unparseableDigestIntervalIsRejected() {
        assumeProEdition();
        RelizaException ex = assertThrows(RelizaException.class, () ->
                service.upsertChannel(null, null,
                        seed(UUID.randomUUID(), "email-ops", IntegrationType.EMAIL, Boolean.TRUE),
                        null, null,
                        new EmailConfigInput(java.util.List.of("ops@example.com"), null, "24 hours"),
                        null, null, WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(ex.getMessage().contains("ISO-8601"), ex.getMessage());
    }

    @Test
    void outOfBoundsDigestIntervalIsRejected() {
        assumeProEdition();
        RelizaException ex = assertThrows(RelizaException.class, () ->
                service.upsertChannel(null, null,
                        seed(UUID.randomUUID(), "email-ops", IntegrationType.EMAIL, Boolean.TRUE),
                        null, null,
                        new EmailConfigInput(java.util.List.of("ops@example.com"), null, "PT1M"),
                        null, null, WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(ex.getMessage().contains("between"), ex.getMessage());
    }

    @Test
    void switchingDigestModeToImmediateReleasesParkedRows() throws Exception {
        assumeProEdition();
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration existing = emailChannelWithParams(channelUuid, orgUuid, Map.of(
                EmailDigestPolicy.RECIPIENTS_KEY, java.util.List.of("ops@example.com"),
                EmailDigestPolicy.DIGEST_MODE_KEY, "ROLLING"));
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        service.upsertChannel(channelUuid, null,
                seed(orgUuid, "email-ops", IntegrationType.EMAIL, Boolean.TRUE),
                null, null,
                new EmailConfigInput(null, EmailDigestPolicy.EmailDigestMode.IMMEDIATE, null),
                null, null, WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        verify(deliveryRepo).releaseBatchedForChannel(eq(channelUuid), any());
    }

    @Test
    void updateKeepingRollingModeDoesNotReleaseParkedRows() throws Exception {
        assumeProEdition();
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration existing = emailChannelWithParams(channelUuid, orgUuid, Map.of(
                EmailDigestPolicy.RECIPIENTS_KEY, java.util.List.of("ops@example.com"),
                EmailDigestPolicy.DIGEST_MODE_KEY, "ROLLING"));
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        service.upsertChannel(channelUuid, null,
                seed(orgUuid, "email-ops", IntegrationType.EMAIL, Boolean.TRUE),
                null, null,
                new EmailConfigInput(java.util.List.of("new@example.com"), null, "PT6H"),
                null, null, WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        verify(deliveryRepo, never()).releaseBatchedForChannel(any(), any());
    }

    @Test
    void creatingImmediateChannelDoesNotTouchDeliveries() throws Exception {
        assumeProEdition();
        service.upsertChannel(null, null,
                seed(UUID.randomUUID(), "email-ops", IntegrationType.EMAIL, Boolean.TRUE),
                null, null,
                new EmailConfigInput(java.util.List.of("ops@example.com"),
                        EmailDigestPolicy.EmailDigestMode.IMMEDIATE, null),
                null, null, WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        // Fresh channel can't have parked rows; release runs on update only.
        verify(deliveryRepo, never()).releaseBatchedForChannel(any(), any());
    }

    @Test
    void invalidRecipientAddressIsRejected() {
        assumeProEdition();
        RelizaException ex = assertThrows(RelizaException.class, () ->
                service.upsertChannel(null, null,
                        seed(UUID.randomUUID(), "email-ops", IntegrationType.EMAIL, Boolean.TRUE),
                        null, null,
                        new EmailConfigInput(java.util.List.of("not-an-email"), null, null),
                        null, null, WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));
        assertTrue(ex.getMessage().contains("not a valid email"), ex.getMessage());
    }

    private static Integration emailChannelWithParams(UUID channelUuid, UUID org,
            Map<String, Object> parameters) {
        Integration channel = new Integration();
        channel.setUuid(channelUuid);
        IntegrationData data = new IntegrationData();
        data.setUuid(channelUuid);
        data.setIdentifier(channelUuid.toString());
        data.setOrg(org);
        data.setName("email-ops");
        data.setType(IntegrationType.EMAIL);
        data.setIsEnabled(Boolean.TRUE);
        data.setParameters(new HashMap<>(parameters));
        channel.setRecordData(Utils.dataToRecord(data));
        return channel;
    }

    private static Integration stubChannel(UUID channelUuid, UUID org,
            String encryptedSecret, boolean enabled) {
        return stubChannel(channelUuid, org, encryptedSecret, enabled,
                IntegrationType.SLACK, "test-channel");
    }

    private static Integration stubChannel(UUID channelUuid, UUID org,
            String encryptedSecret, boolean enabled, IntegrationType type, String name) {
        Integration channel = new Integration();
        channel.setUuid(channelUuid);
        IntegrationData data = new IntegrationData();
        data.setUuid(channelUuid);
        data.setIdentifier(channelUuid.toString());
        data.setOrg(org);
        data.setName(name);
        data.setType(type);
        data.setIsEnabled(enabled);
        data.setSecret(encryptedSecret);
        data.setParameters(Map.of());
        channel.setRecordData(Utils.dataToRecord(data));
        return channel;
    }

    // ---- Optimistic-locking gate (slice 1+2+4 cross-cutting) ---------------

    @Test
    void updateWithMatchingExpectedRevisionSucceeds() throws Exception {
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration existing = stubChannel(channelUuid, orgUuid, "ENC:s", true);
        existing.setRevision(7);
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        Integration saved = service.upsertChannel(
                channelUuid, /*expectedRevision*/ 7,
                seed(orgUuid, "renamed", IntegrationType.SLACK, Boolean.TRUE),
                null, null, null, null, null,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        assertEquals(channelUuid, saved.getUuid());
        IntegrationData persisted = parse(saved);
        assertEquals("renamed", persisted.getName());
    }

    @Test
    void updateWithMismatchedExpectedRevisionThrowsConflict() {
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration existing = stubChannel(channelUuid, orgUuid, "ENC:s", true);
        existing.setRevision(9); // someone else already bumped past our captured 7
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        RelizaException ex = assertThrows(RelizaException.class, () -> service.upsertChannel(
                channelUuid, /*expectedRevision*/ 7,
                seed(orgUuid, "renamed-by-second-admin", IntegrationType.SLACK, Boolean.TRUE),
                null, null, null, null, null,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test")));

        // Conflict: prefix is the distinctive marker the UI greps for.
        assertTrue(ex.getMessage().startsWith("Conflict:"),
                "Expected Conflict: prefix, got: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("expected revision 7"));
        assertTrue(ex.getMessage().contains("current 9"));
        // The save itself never happened
        verify(integrationRepo, never()).save(any());
    }

    @Test
    void updateWithNullExpectedRevisionSkipsCheck() throws Exception {
        // Backwards-compat: callers that don't pass expectedRevision get
        // the old last-writer-wins behavior, no Conflict thrown.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration existing = stubChannel(channelUuid, orgUuid, "ENC:s", true);
        existing.setRevision(9);
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        Integration saved = service.upsertChannel(
                channelUuid, /*expectedRevision*/ null,
                seed(orgUuid, "renamed-without-revision", IntegrationType.SLACK, Boolean.TRUE),
                null, null, null, null, null,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        assertNotNull(saved);
    }

    @Test
    void revisionBumpedOnUpdate() throws Exception {
        // Integration has no @Version — the service bumps revision manually.
        UUID orgUuid = UUID.randomUUID();
        UUID channelUuid = UUID.randomUUID();
        Integration existing = stubChannel(channelUuid, orgUuid, "ENC:s", true);
        existing.setRevision(3);
        when(integrationRepo.findById(channelUuid)).thenReturn(Optional.of(existing));

        Integration saved = service.upsertChannel(
                channelUuid, seed(orgUuid, "renamed", IntegrationType.SLACK, Boolean.TRUE),
                null, null,
                WhoUpdated.getApiWhoUpdated(UUID.randomUUID(), "test"));

        assertEquals(4, saved.getRevision(), "revision should bump by 1 on update");
        // disabled flag preserved from seed
        assertFalse(Boolean.FALSE.equals(parse(saved).getIsEnabled()));
    }
}
