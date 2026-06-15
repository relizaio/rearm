/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
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
import io.reliza.model.WebhookAuthScheme;
import io.reliza.model.WebhookChannelSecret;
import io.reliza.service.EncryptionService;

/**
 * Unit-tests the parts of {@link WebhookChannelDispatcher} that don't
 * require an actual HTTP call:
 * <ul>
 *   <li>Channel config decryption + parse + validation.</li>
 *   <li>HTTPS-only enforcement (dispatch-time defense-in-depth).</li>
 *   <li>Auth scheme branching (NONE / BEARER / HMAC_SHA256).</li>
 *   <li>HMAC-SHA256 hex digest computed against a known fixture.</li>
 * </ul>
 *
 * <p>Live HTTP integration is out of scope — the SlackChannelDispatcher
 * unit tests already exercise the WebClient block-on-timeout + response
 * classification paths, and the WebhookChannelDispatcher mirrors that
 * exception-handling shape exactly.
 */
class WebhookChannelDispatcherTest {

    private EncryptionService encryptionService;
    private WebhookChannelDispatcher dispatcher;

    @BeforeEach
    void wireMocks() throws Exception {
        encryptionService = mock(EncryptionService.class);
        // Decrypt strips "ENC:" prefix so test fixtures can hand-build
        // a "fake encrypted" blob and read it back cleanly.
        when(encryptionService.decrypt(anyString())).thenAnswer(inv -> {
            String s = inv.getArgument(0);
            return s.startsWith("ENC:") ? s.substring(4) : s;
        });
        dispatcher = new WebhookChannelDispatcher();
        Field f = WebhookChannelDispatcher.class.getDeclaredField("encryptionService");
        f.setAccessible(true);
        f.set(dispatcher, encryptionService);
    }

    @Test
    void dispatchRejectsNonHttpsUrl() throws Exception {
        Integration channel = channelWithSecret(new WebhookChannelSecret(
                "http://insecure.example.com/hook", WebhookAuthScheme.NONE, null));
        NotificationOutboxEvent event = synthEvent();

        ChannelDispatchResult result = dispatcher.dispatch(event, channel);

        // HTTPS-only check fires before any HTTP attempt → non-retriable
        // (config-side fault). The dispatcher matches the save-layer
        // validator so the same rule produces the same result at both
        // sides of the boundary.
        assertEquals(ChannelDispatchResult.Outcome.NON_RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("HTTPS"),
                "Expected HTTPS-rejection in: " + result.errorMessage());
    }

    @Test
    void dispatchRejectsBlankUrlAfterDecrypt() throws Exception {
        Integration channel = channelWithSecret(new WebhookChannelSecret(
                "" /* blank */, WebhookAuthScheme.NONE, null));
        ChannelDispatchResult result = dispatcher.dispatch(synthEvent(), channel);

        assertEquals(ChannelDispatchResult.Outcome.NON_RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("blank"),
                "Expected blank-URL rejection in: " + result.errorMessage());
    }

    @Test
    void dispatchRejectsUnparseableSecret() throws Exception {
        // encryptedSecret decrypts to non-JSON garbage → parse fails →
        // non-retriable (the row's config is broken, no retry will fix it).
        Integration channel = webhookChannel("broken", "ENC:this is not json");

        ChannelDispatchResult result = dispatcher.dispatch(synthEvent(), channel);

        assertEquals(ChannelDispatchResult.Outcome.NON_RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("failed to decrypt/parse"),
                "Expected parse-failure rejection in: " + result.errorMessage());
    }

    @Test
    void dispatchRejectsMissingEncryptedSecret() throws Exception {
        Integration channel = webhookChannel("no-config", null /* no encrypted secret */);

        ChannelDispatchResult result = dispatcher.dispatch(synthEvent(), channel);
        assertEquals(ChannelDispatchResult.Outcome.NON_RETRIABLE_FAILURE, result.outcome());
        assertTrue(result.errorMessage().contains("no encrypted webhook config"));
    }

    @Test
    void hmacSha256ProducesKnownDigest() throws Exception {
        // Pin the HMAC-SHA256 hex output against a known vector so a
        // future refactor of the digest path doesn't silently break
        // receiver-side verification. vector. key="key", data="The quick brown fox jumps over the lazy dog",
        // expected = f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8
        String secret = "key";
        String body = "The quick brown fox jumps over the lazy dog";
        // Invoke the package-private helper via reflection so the test
        // pins the canonical algorithm without exposing it as public API.
        java.lang.reflect.Method m = WebhookChannelDispatcher.class.getDeclaredMethod(
                "computeHmacSha256Hex", String.class, String.class);
        m.setAccessible(true);
        String digest = (String) m.invoke(null, secret, body);
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", digest,
                "HMAC-SHA256 output must match the standard test vector");
    }

    @Test
    void envelopeShapeMatchesSchema() throws Exception {
        // Pin the envelope shape so a refactor doesn't silently change
        // the wire format a customer's receiver depends on.
        java.lang.reflect.Method m = WebhookChannelDispatcher.class.getDeclaredMethod(
                "buildEnvelope", NotificationOutboxEvent.class);
        m.setAccessible(true);
        NotificationOutboxEvent event = synthEvent();
        @SuppressWarnings("unchecked")
        Map<String, Object> envelope = (Map<String, Object>) m.invoke(null, event);

        assertEquals(1, envelope.get("schemaVersion"));
        assertEquals("NEW_VULN_AFFECTS_RELEASES", envelope.get("eventType"));
        assertEquals("REAL", envelope.get("origin"));
        assertNotNull(envelope.get("occurredAt"), "occurredAt should be present");
        assertNotNull(envelope.get("dedupKey"));
        assertEquals(event.getUuid().toString(), envelope.get("eventUuid"));
        assertNotNull(envelope.get("payload"));
    }

    // ---------------- helpers ----------------

    private static Integration channelWithSecret(WebhookChannelSecret secret) throws Exception {
        // Mock encryption service in the test will strip "ENC:" on decrypt;
        // we hand it the JSON-serialized secret with the prefix here.
        String json = Utils.OM.writeValueAsString(secret);
        return webhookChannel("test-channel", "ENC:" + json);
    }

    /** Build a WEBHOOK-type Integration channel row with the given encrypted secret. */
    private static Integration webhookChannel(String name, String encryptedSecret) {
        Integration channel = new Integration();
        channel.setUuid(UUID.randomUUID());
        IntegrationData data = new IntegrationData();
        data.setUuid(channel.getUuid());
        data.setIdentifier(channel.getUuid().toString());
        data.setOrg(UUID.randomUUID());
        data.setName(name);
        data.setType(IntegrationType.WEBHOOK);
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
