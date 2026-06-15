/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.reliza.model.EmailDigestPolicy;
import io.reliza.model.WebhookAuthScheme;
import io.reliza.model.SentinelChannelSecret;

/**
 * Typed write-input shape for {@code upsertNotificationChannel}. The
 * GraphQL fetcher deserializes the incoming JSON map straight into
 * this record via {@code Utils.OM.convertValue}, matching the
 * {@code WebhookData} / {@code ApprovalPolicyDto} precedent. Jackson
 * handles String→enum coercion for {@link #type} / {@link #status} by
 * exact name match; an unknown enum value lands as a
 * {@code RelizaException} surfaced by the fetcher's catch.
 *
 * <p>Per-channel-type secrets live in their own nested input record
 * ({@link SlackConfigInput} / {@link WebhookConfigInput}). The customer
 * supplies the one matching their {@link #type}; the service ignores
 * the others. On update, leaving the matching config null preserves
 * the existing encrypted secret (the "rename without re-typing"
 * idiom from {@code WebhookService.updateWebhook}).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationChannelInput(
        UUID uuid,
        // Optimistic-locking gate. Captured by the UI at Edit-load time
        // and round-tripped on save; rejected by the service when the
        // current DB row's revision is higher (concurrent admin edit).
        // Null on create or for callers opting out of the check.
        Integer expectedRevision,
        UUID org,
        UUID resourceGroup,
        String name,
        // GraphQL channel-type enum value name (e.g. "SLACK", "MS_TEAMS").
        // Jackson populates it from the GQL enum name; the fetcher maps it
        // onto IntegrationType at the boundary via
        // IntegrationData.fromChannelTypeName(String).
        String type,
        // GraphQL channel-status enum value name ("ENABLED" / "DISABLED").
        // Mapped to IntegrationData.isEnabled at the boundary.
        String status,
        SlackConfigInput slackConfig,
        WebhookConfigInput webhookConfig,
        EmailConfigInput emailConfig,
        TeamsConfigInput teamsConfig,
        SentinelConfigInput sentinelConfig) {

    /** Slack-specific config. Only the incoming-webhook URL — the URL itself is the auth credential. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SlackConfigInput(
            String webhookUrl) {
    }

    /**
     * Generic webhook config. {@code url} is the customer endpoint
     * (required, must be HTTPS). {@code authScheme} picks how the
     * dispatcher signs each POST; {@code authToken} carries the bearer
     * token / HMAC shared secret (must be non-blank when
     * {@code authScheme != NONE}).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WebhookConfigInput(
            String url,
            WebhookAuthScheme authScheme,
            String authToken) {
    }

    /**
     * Email-channel config (Phase 9; digest fields Phase 5 of the
     * notifications plan). Per-channel state is the recipient list plus
     * the digest policy; SMTP / SendGrid credentials come from system
     * config on {@code SystemInfoService}, not from the channel. Stored
     * unencrypted in {@code parameters} — inbox addresses and batching
     * prefs, not secrets.
     *
     * <p>Update semantics are per-field: a null {@code recipients}
     * preserves the existing list (digest-only update); null digest
     * fields preserve the existing digest config (recipients-only
     * update). {@code digestInterval} is an ISO-8601 duration string
     * (e.g. {@code PT24H}), bounds-checked at save time.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EmailConfigInput(
            List<String> recipients,
            EmailDigestPolicy.EmailDigestMode digestMode,
            String digestInterval) {
    }

    /**
     * MS Teams channel config (Phase 10). Power Automate Workflows
     * webhook URL — the URL itself is the auth credential, same as
     * Slack. Stored encrypted at rest via {@code EncryptionService}.
     * On update, leaving {@code webhookUrl} blank preserves the
     * existing encrypted value.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TeamsConfigInput(
            String webhookUrl) {
    }

    /**
     * Azure Sentinel channel config (Phase 11). All six fields are
     * sensitive — combined they grant write access to the customer's
     * Sentinel workspace. Serialized to a {@code SentinelChannelSecret}
     * blob, encrypted via {@code EncryptionService}, stored in
     * {@code NotificationChannelData.encryptedSecret}. On update,
     * leaving the secret-tier fields blank preserves the existing
     * encrypted value (rename-without-re-typing).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SentinelConfigInput(
            String tenantId,
            String clientId,
            String clientSecret,
            String dcrEndpoint,
            String dcrImmutableId,
            String streamName) {
    }
}
