/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Encrypted-blob shape for {@code IntegrationType.WEBHOOK} notification
 * channels.
 *
 * <p>Slack channels store a single secret (the incoming-webhook URL,
 * which is itself the auth credential) in
 * {@code IntegrationData.secret}. Generic webhook
 * channels have to carry more — the URL plus an optional auth token —
 * and the auth scheme determines what the token means. Rather than
 * splitting secrets across multiple columns, the channel service
 * serializes this record to JSON and encrypts the whole blob into the
 * single {@code secret} field. The dispatcher decrypts +
 * deserializes on every send.
 *
 * <p>{@code authToken} is null when {@code authScheme == NONE} and
 * carries the bearer token / HMAC shared secret otherwise. Either way
 * it's encrypted at rest because the whole record is the encrypted
 * blob.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record WebhookChannelSecret(
        String url,
        WebhookAuthScheme authScheme,
        String authToken) {
}
