/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.reliza.common.Utils;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationDeliveryOrigin;
import io.reliza.model.NotificationEventType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationOutboxStatus;

/**
 * Unit-tests the {@link SlackChannelDispatcher} config/validation seam that
 * runs BEFORE any HTTP attempt: decrypt -> normalize -> validate. Live HTTP
 * (the doPost that a valid URL reaches) is out of scope, matching
 * {@link WebhookChannelDispatcherTest}; the positive fragment-expansion is
 * unit-tested on the static method in {@link SlackWebhookUrlValidatorTest}.
 *
 * <p>The load-bearing case here is the legacy-fragment guard: a genuinely
 * malformed secret (junk / non-Slack host) must land on
 * {@code CHANNEL_MISCONFIGURED} (auto-disable) rather than being expanded into
 * a valid-looking hooks.slack.com URL that would POST to a dead endpoint on
 * every event forever. This exercises the decrypt->normalize->validate seam
 * through the real dispatcher, so a refactor that drops or reorders normalize
 * is caught.
 */
class SlackChannelDispatcherTest {

    private EncryptionService encryptionService;
    private SlackChannelDispatcher dispatcher;

    @BeforeEach
    void wireMocks() throws Exception {
        encryptionService = mock(EncryptionService.class);
        // Decrypt strips an "ENC:" prefix so fixtures can hand-build a
        // "fake encrypted" blob and read it back (same shape as the
        // WebhookChannelDispatcher test).
        when(encryptionService.decrypt(anyString())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s.startsWith("ENC:") ? s.substring(4) : s;
        });
        dispatcher = new SlackChannelDispatcher();
        Field f = SlackChannelDispatcher.class.getDeclaredField("encryptionService");
        f.setAccessible(true);
        f.set(dispatcher, encryptionService);
        // formatter is only touched after validation succeeds (i.e. on the
        // HTTP path), which these reject-path tests never reach; leaving it
        // null keeps the fixture minimal.
    }

    @Test
    void dispatchRejectsMissingEncryptedSecret() {
        Integration channel = slackChannel(null /* no encrypted secret */);
        ChannelDispatchResult result = dispatcher.dispatch(synthEvent(), channel);
        assertEquals(ChannelDispatchResult.Outcome.NON_RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("no encrypted webhook URL"),
                "Expected missing-secret rejection in: " + result.errorMessage());
    }

    @Test
    void dispatchRejectsDecryptedBlankUrl() {
        Integration channel = slackChannel("ENC:   "); // decrypts to whitespace
        ChannelDispatchResult result = dispatcher.dispatch(synthEvent(), channel);
        assertEquals(ChannelDispatchResult.Outcome.NON_RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("blank"),
                "Expected blank-URL rejection in: " + result.errorMessage());
    }

    @Test
    void dispatchAutoDisablesJunkFragment() {
        // A non-blank but malformed secret ("/", single token) must NOT be
        // fabricated into a deliverable URL -- normalize leaves it unexpanded,
        // isValid rejects it, and the channel is auto-disabled. This is the
        // guard against perpetual live POSTs to a dead endpoint.
        for (String junk : new String[] {"abc", "/", "//"}) {
            ChannelDispatchResult result = dispatcher.dispatch(synthEvent(), slackChannel("ENC:" + junk));
            assertEquals(ChannelDispatchResult.Outcome.CHANNEL_MISCONFIGURED, result.outcome(),
                    "Junk fragment '" + junk + "' should auto-disable, got: " + result.errorMessage());
            assertTrue(result.errorMessage().contains("not a Slack host"),
                    "Expected not-a-Slack-host reason for '" + junk + "' in: " + result.errorMessage());
        }
    }

    @Test
    void dispatchAutoDisablesNonSlackUrl() {
        // A full but non-Slack URL passes through normalize unchanged and is
        // rejected on the host check -- auto-disable, no off-host POST.
        Integration channel = slackChannel("ENC:https://evil.example.com/services/T0/B0/xyz");
        ChannelDispatchResult result = dispatcher.dispatch(synthEvent(), channel);
        assertEquals(ChannelDispatchResult.Outcome.CHANNEL_MISCONFIGURED, result.outcome());
        assertTrue(result.errorMessage().contains("not a Slack host"));
    }

    // ---------------- helpers ----------------

    /** Build a SLACK-type Integration channel row with the given encrypted secret. */
    private static Integration slackChannel(String encryptedSecret) {
        Integration channel = new Integration();
        channel.setUuid(UUID.randomUUID());
        IntegrationData data = new IntegrationData();
        data.setUuid(channel.getUuid());
        data.setIdentifier(channel.getUuid().toString());
        data.setOrg(UUID.randomUUID());
        data.setName("Slack test");
        data.setType(IntegrationType.SLACK);
        data.setIsEnabled(Boolean.TRUE);
        data.setSecret(encryptedSecret);
        data.setParameters(Map.of());
        channel.setRecordData(Utils.dataToRecord(data));
        return channel;
    }

    private static NotificationOutboxEvent synthEvent() {
        NotificationOutboxEvent event = new NotificationOutboxEvent();
        event.setUuid(UUID.randomUUID());
        event.setOrg(UUID.randomUUID());
        event.setEventType(NotificationEventType.NEW_VULN_AFFECTS_RELEASES);
        event.setStatus(NotificationOutboxStatus.PENDING);
        event.setOrigin(NotificationDeliveryOrigin.REAL);
        event.setOccurredAt(ZonedDateTime.now());
        event.setDedupKey("vuln:new:CVE-2025-X");
        Map<String, Object> payload = new HashMap<>();
        payload.put("vulnPrimaryId", "CVE-2025-X");
        payload.put("severity", "CRITICAL");
        event.setRecordData(payload);
        return event;
    }
}
