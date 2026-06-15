/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import io.reliza.common.Utils;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.WebhookAuthScheme;
import io.reliza.model.WebhookChannelSecret;
import io.reliza.service.EncryptionService;
import lombok.extern.slf4j.Slf4j;

/**
 * Posts a notification event to a customer-defined HTTPS webhook
 * endpoint. Phase 4 catch-all channel — covers PagerDuty / Opsgenie /
 * Splunk / in-house receivers without per-vendor work.
 *
 * <p>The payload is a stable envelope:
 * <pre>
 *   {
 *     "schemaVersion": 1,
 *     "eventType": "NEW_VULN_AFFECTS_RELEASES",
 *     "occurredAt": "2026-05-27T...",
 *     "origin": "REAL",
 *     "dedupKey": "vuln:new:CVE-2025-X",
 *     "payload": { ...event-specific fields... }
 *   }
 * </pre>
 * Receivers can route on {@code eventType} without parsing the inner
 * payload. {@code schemaVersion} bumps on a breaking shape change.
 *
 * <h3>Auth schemes</h3>
 * The customer picks one of {@link WebhookAuthScheme} at channel-create
 * time:
 * <ul>
 *   <li>{@link WebhookAuthScheme#NONE} — no auth header. Receiver
 *       relies on URL secrecy + transport security.</li>
 *   <li>{@link WebhookAuthScheme#BEARER} — adds
 *       {@code Authorization: Bearer &lt;token&gt;}. Customer-supplied
 *       token stays encrypted at rest inside the
 *       {@link WebhookChannelSecret} blob.</li>
 *   <li>{@link WebhookAuthScheme#HMAC_SHA256} — computes HMAC-SHA256 of
 *       the serialized payload using the shared secret as key, sends
 *       the hex digest in {@code X-Reliza-Signature: sha256=&lt;hex&gt;}.
 *       Receiver verifies. Standard webhook-signing pattern.</li>
 * </ul>
 *
 * <h3>HTTPS-only</h3>
 * The save-time validator (in {@code NotificationChannelService})
 * enforces an {@code https://} prefix; this dispatcher re-checks
 * defensively so a future tamper or legacy plaintext URL never gets
 * a payload over the wire.
 *
 * <h3>HTTP scaffolding</h3>
 * WebClient build, URI passthrough parse, POST + exception classification
 * live in {@link AbstractHttpChannelDispatcher}. Auth headers are
 * applied via the per-call headers hook — the HMAC variant signs the
 * already-serialized body string so the digest covers exactly the bytes
 * that go on the wire.
 */
@Service
@Slf4j
public class WebhookChannelDispatcher extends AbstractHttpChannelDispatcher implements ChannelDispatcher {

    private static final IntegrationType CHANNEL_TYPE = IntegrationType.WEBHOOK;

    /**
     * Envelope schema version. Bump on a breaking shape change so
     * customer receivers can route by version.
     */
    private static final int SCHEMA_VERSION = 1;

    /** Custom signature header name. Receivers verify HMAC against this. */
    private static final String SIGNATURE_HEADER = "X-Reliza-Signature";

    @Autowired
    private EncryptionService encryptionService;

    @Override
    public IntegrationType supportedType() {
        return CHANNEL_TYPE;
    }

    @Override
    public ChannelDispatchResult dispatch(NotificationOutboxEvent event, Integration channel) {
        IntegrationData data;
        try {
            data = IntegrationData.dataFromRecord(channel);
        } catch (Exception e) {
            return ChannelDispatchResult.nonRetriable(
                    "Channel " + channel.getUuid() + " has unparseable record_data: " + e.getMessage());
        }
        if (data == null || StringUtils.isBlank(data.getSecret())) {
            return ChannelDispatchResult.nonRetriable(
                    "Channel " + channel.getUuid() + " has no encrypted webhook config");
        }

        WebhookChannelSecret secret;
        try {
            String decrypted = encryptionService.decrypt(data.getSecret());
            secret = Utils.OM.readValue(decrypted, WebhookChannelSecret.class);
        } catch (Exception e) {
            return ChannelDispatchResult.nonRetriable(
                    "Channel " + channel.getUuid() + " webhook config failed to decrypt/parse: "
                            + e.getMessage());
        }
        if (secret == null || StringUtils.isBlank(secret.url())) {
            return ChannelDispatchResult.nonRetriable(
                    "Channel " + channel.getUuid() + " decrypted webhook URL is blank");
        }
        // Defense-in-depth: refuse plaintext HTTP. Save-time validator
        // already enforces this; dispatcher re-checks so a legacy or
        // tampered row never leaks vuln data over the wire. Shared
        // validator so save + dispatch can't disagree.
        if (!WebhookUrlValidator.isHttps(secret.url())) {
            return ChannelDispatchResult.nonRetriable(
                    "Channel " + channel.getUuid() + " webhook URL is not HTTPS"
                            + " — refusing to POST vuln payload over plaintext");
        }

        UriParseResult parsed = parseUri(secret.url(), channel.getUuid());
        if (parsed.isError()) {
            return parsed.error();
        }

        Map<String, Object> envelope = buildEnvelope(event);
        String body;
        try {
            body = Utils.OM.writeValueAsString(envelope);
        } catch (Exception e) {
            return ChannelDispatchResult.nonRetriable(
                    "Failed to serialize webhook envelope for event " + event.getUuid()
                            + ": " + e.getMessage());
        }

        WebhookAuthScheme scheme = secret.authScheme() != null ? secret.authScheme() : WebhookAuthScheme.NONE;
        String authToken = secret.authToken();

        // Validate auth config up front so a missing token surfaces as a
        // non-retriable config error rather than getting wrapped in the
        // doPost runtime-classifier (which would also non-retriable it,
        // but with a less useful message).
        try {
            validateAuthConfig(scheme, authToken);
        } catch (RuntimeException e) {
            return ChannelDispatchResult.nonRetriable(
                    "Channel " + channel.getUuid() + " auth setup failed: " + e.getMessage());
        }

        return doPost(parsed.uri(), body, timeout(), CHANNEL_TYPE, spec -> {
            // Re-pin Content-Type at request scope (belt + suspenders;
            // the WebClient default already sets it, but bodyValue(String)
            // can otherwise be inferred as text/plain by some encoders).
            spec.header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE);
            applyAuth(spec, scheme, authToken, body);
        });
    }

    /**
     * Build the stable envelope shape. Lifts the outbox-level metadata
     * (event type, severity, occurredAt, dedup key, origin) to top-level
     * fields so receivers can route on them without parsing the inner
     * event payload.
     */
    private static Map<String, Object> buildEnvelope(NotificationOutboxEvent event) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", SCHEMA_VERSION);
        envelope.put("eventType", event.getEventType() != null ? event.getEventType().name() : null);
        envelope.put("occurredAt", event.getOccurredAt() != null
                ? event.getOccurredAt().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                : null);
        envelope.put("origin", event.getOrigin() != null ? event.getOrigin().name() : null);
        envelope.put("dedupKey", event.getDedupKey());
        envelope.put("eventUuid", event.getUuid() != null ? event.getUuid().toString() : null);
        envelope.put("payload", event.getRecordData() != null ? event.getRecordData() : Map.of());
        return envelope;
    }

    /**
     * Up-front validation of auth-scheme + token consistency. Mirrors
     * the throw conditions in {@link #applyAuth} so the operator-facing
     * error message surfaces before any wire byte is touched.
     */
    private static void validateAuthConfig(WebhookAuthScheme scheme, String authToken) {
        switch (scheme) {
            case NONE -> { /* no token needed */ }
            case BEARER -> {
                if (StringUtils.isBlank(authToken)) {
                    throw new IllegalStateException("Bearer auth requires a non-blank token");
                }
            }
            case HMAC_SHA256 -> {
                if (StringUtils.isBlank(authToken)) {
                    throw new IllegalStateException("HMAC auth requires a non-blank shared secret");
                }
            }
        }
    }

    private static void applyAuth(WebClient.RequestBodySpec spec, WebhookAuthScheme scheme,
            String authToken, String body) {
        switch (scheme) {
            case NONE -> { /* no header */ }
            case BEARER -> spec.header(HttpHeaders.AUTHORIZATION, "Bearer " + authToken);
            case HMAC_SHA256 -> spec.header(SIGNATURE_HEADER, "sha256=" + computeHmacSha256Hex(authToken, body));
        }
    }

    private static String computeHmacSha256Hex(String secret, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b & 0xff));
            }
            return hex.toString();
        } catch (Exception e) {
            // HmacSHA256 is part of every Java distribution since 1.5;
            // this branch only fires for an unrecoverable JCE error.
            throw new IllegalStateException("HMAC-SHA256 computation failed", e);
        }
    }
}
