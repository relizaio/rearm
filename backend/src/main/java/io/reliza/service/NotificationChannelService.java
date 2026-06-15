/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.validator.routines.EmailValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import io.reliza.common.CommonVariables.TableName;
import io.reliza.common.Utils;
import io.reliza.common.oss.LicensingConstants;
import io.reliza.exceptions.RelizaException;
import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.WhoUpdated;
import io.reliza.model.dto.notifications.NotificationChannelInput.EmailConfigInput;
import io.reliza.model.dto.notifications.NotificationChannelInput.SentinelConfigInput;
import io.reliza.model.dto.notifications.NotificationChannelInput.SlackConfigInput;
import io.reliza.model.dto.notifications.NotificationChannelInput.TeamsConfigInput;
import io.reliza.model.dto.notifications.NotificationChannelInput.WebhookConfigInput;
import io.reliza.model.EmailDigestPolicy;
import io.reliza.model.SentinelChannelSecret;
import io.reliza.model.WebhookAuthScheme;
import io.reliza.model.WebhookChannelSecret;
import io.reliza.repositories.IntegrationRepository;
import io.reliza.repositories.NotificationDeliveryRepository;
import io.reliza.service.AuditService;
import io.reliza.service.EncryptionService;
import lombok.extern.slf4j.Slf4j;

/**
 * Phase 3 CRUD service for notification channels.
 *
 * <p>As of Phase 2b-1 a notification channel is an {@link Integration}
 * row whose {@link IntegrationData} carries a non-null {@code name} and
 * a type in {@link IntegrationData#NOTIFICATION_DESTINATION_TYPES}
 * (SLACK / MSTEAMS / EMAIL / WEBHOOK / SENTINEL). The retired
 * {@code NotificationChannel} entity + {@code NotificationChannelData}
 * record were folded into the existing {@code integrations} table; this
 * service persists {@code Integration} rows directly.
 *
 * <p>Channels carry the per-tenant destination config that the channel
 * worker dispatches to. The secret material (the webhook URL itself,
 * since it's also the auth credential) lives encrypted at rest via
 * {@link EncryptionService} in {@link IntegrationData#getSecret()} —
 * this service is the only place that decides what gets encrypted.
 *
 * <h3>Upsert semantics</h3>
 * <ul>
 *   <li>UUID present → update an existing row; UUID null → create.</li>
 *   <li>Webhook URL handling on update: a non-blank value replaces the
 *       existing encrypted secret; a null / blank value preserves it.</li>
 *   <li>All validation runs before the create/update branch — invalid
 *       input never reaches the DB.</li>
 *   <li>{@code identifier}: every channel integration row carries a
 *       non-null identifier unique within (org, type). On create it's
 *       assigned a fresh {@code uuid.toString()} (matching the V45
 *       migration); on update the existing identifier is preserved.</li>
 * </ul>
 *
 * <h3>Delete semantics</h3>
 * Hard delete. Any delivery rows that still reference this channel land
 * in {@code NotificationDeliveryWorker}'s "channel no longer exists"
 * path and get marked FAILED. Customers wanting soft-delete semantics
 * can flip {@code isEnabled} to false via {@link #setChannelStatus}.
 */
@Service
@Slf4j
public class NotificationChannelService {

    /**
     * Save-time JSONB size cap. Customer-authored config blobs can be
     * arbitrarily nested; without this cap a buggy client could write a
     * multi-MB record and amplify the fan-out worker's per-batch
     * deserialization cost. Suggested ceiling per the design doc §13.2.
     */
    static final int MAX_RECORD_DATA_BYTES = 256 * 1024;

    @Autowired
    private IntegrationRepository integrationRepo;

    @Autowired
    private AuditService auditService;

    @Autowired
    private EncryptionService encryptionService;

    @Autowired
    private NotificationDeliveryRepository deliveryRepo;

    public Optional<Integration> getChannel(UUID uuid) {
        if (uuid == null) return Optional.empty();
        return integrationRepo.findById(uuid);
    }

    /**
     * Backwards-compatible "slack + webhook only" overload. The bulk of
     * the unit tests (and any pre-Phase-9 caller that hasn't been
     * touched) target Slack or Webhook channels and don't care about
     * the per-type configs of newer channel types. Forwards to the
     * canonical signature with the email / teams / sentinel arms nulled.
     */
    public Integration upsertChannel(UUID uuid, IntegrationData seed,
            SlackConfigInput slackConfig, WebhookConfigInput webhookConfig,
            WhoUpdated wu) throws RelizaException {
        return upsertChannel(uuid, /*expectedRevision*/ null, seed,
                slackConfig, webhookConfig, null, null, null, wu);
    }

    /**
     * Create-or-update. {@code uuid == null} on create; the row's
     * persisted form is returned so the caller can return its uuid /
     * revision to the GraphQL response.
     *
     * <p>The seed's {@link IntegrationData#getSecret()} /
     * {@link IntegrationData#getParameters()} are resolved server-side —
     * the caller supplies only the per-type config inputs. On update,
     * leaving the matching config null preserves the existing encrypted
     * value (the "rename without re-typing the URL" idiom).
     */
    @Transactional
    public Integration upsertChannel(UUID uuid, Integer expectedRevision,
            IntegrationData seed,
            SlackConfigInput slackConfig, WebhookConfigInput webhookConfig,
            EmailConfigInput emailConfig, TeamsConfigInput teamsConfig,
            SentinelConfigInput sentinelConfig,
            WhoUpdated wu) throws RelizaException {
        if (seed == null) throw new RelizaException("Channel input is required");
        validateSeed(seed, slackConfig, webhookConfig, emailConfig, teamsConfig, sentinelConfig);

        Integration target;
        IntegrationData existingData = null;
        String identifier;
        if (uuid != null) {
            // Reject supplied-but-nonexistent uuids per the
            // AgentPolicyService / CommitterService / WebhookService
            // convention. Update is update; create is create — no third
            // state.
            Optional<Integration> existing = integrationRepo.findById(uuid);
            if (existing.isEmpty()) {
                throw new RelizaException("Channel not found: " + uuid);
            }
            target = existing.get();
            existingData = parseRecordData(target);
            assertExpectedRevision(target.getRevision(), expectedRevision,
                    "Channel", seed.getName());
            // Preserve the existing identifier on update. Fall back to the
            // uuid string if a legacy row somehow lacks one.
            identifier = existingData != null && StringUtils.isNotBlank(existingData.getIdentifier())
                    ? existingData.getIdentifier()
                    : target.getUuid().toString();
            // Capture the pre-update state for the audit trail before
            // we bump revision on save.
            auditService.createAndSaveAuditRecord(TableName.INTEGRATIONS, target);
        } else {
            target = new Integration();
            target.setUuid(UUID.randomUUID());
            // New channel integrations get identifier = uuid::text, matching
            // the V45 migration so the (org,type) uniqueness holds.
            identifier = target.getUuid().toString();
        }

        String encryptedSecret = resolveEncryptedSecret(existingData, seed.getType(),
                slackConfig, webhookConfig, teamsConfig, sentinelConfig);

        // parameters merge for EMAIL: write the recipient list when
        // emailConfig is present, otherwise preserve whatever was there
        // (so an "update name only" call doesn't blow away the
        // recipients). Non-EMAIL types keep seed.getParameters() as-is.
        Map<String, Object> mergedParameters = mergeConfigData(existingData, seed, emailConfig);

        IntegrationData finalData = new IntegrationData();
        finalData.setUuid(target.getUuid());
        finalData.setIdentifier(identifier);
        finalData.setOrg(seed.getOrg());
        finalData.setResourceGroup(seed.getResourceGroup());
        finalData.setName(seed.getName());
        finalData.setType(seed.getType());
        // ENABLED default: treat null isEnabled as enabled.
        finalData.setIsEnabled(seed.getIsEnabled() == null ? Boolean.TRUE : seed.getIsEnabled());
        finalData.setSecret(encryptedSecret);
        finalData.setParameters(mergedParameters);

        Map<String, Object> recordData = Utils.dataToRecord(finalData);
        assertRecordDataSize(recordData, "channel");
        target.setRecordData(recordData);
        if (uuid != null) {
            // Integration has no @Version — bump revision manually like
            // IntegrationService.saveIntegration does on update.
            target.setRevision(target.getRevision() + 1);
            target.setLastUpdatedDate(ZonedDateTime.now());
        }
        target = (Integration) WhoUpdated.injectWhoUpdatedData(target, wu);
        Integration saved;
        try {
            saved = integrationRepo.save(target);
        } catch (ObjectOptimisticLockingFailureException e) {
            // Pre-check (assertExpectedRevision) catches the common
            // stale-captured-revision case at the start of the txn;
            // this catch covers the narrow race at commit. Translate
            // to the same "Conflict:" string the UI greps for.
            log.info("Optimistic-lock race on channel upsert (org={}, name={}): {}",
                    seed.getOrg(), seed.getName(), e.getMessage());
            throw new RelizaException("Conflict: Channel \"" + seed.getName()
                    + "\" was edited by another user — please reload and retry");
        }
        log.info("Upserted notification channel org={} channel={} type={} enabled={}",
                seed.getOrg(), saved.getUuid(), seed.getType(), finalData.getIsEnabled());
        if (uuid != null && seed.getType() == IntegrationType.EMAIL) {
            releaseOpenBatchWhenImmediate(saved.getUuid(), mergedParameters);
        }
        return saved;
    }

    /**
     * Digest off-switch (Phase 5): when an EMAIL channel's effective
     * digestMode is IMMEDIATE after an update, release any rows still
     * parked as BATCHED to PENDING/now so the worker drains them as
     * individual emails — otherwise they'd stay invisible until a
     * deadline written under the old ROLLING policy, up to
     * {@link EmailDigestPolicy#MAX_INTERVAL} after the operator turned
     * batching off. Keyed on the resulting mode rather than on a
     * ROLLING→IMMEDIATE transition so stale parked rows are also swept.
     * Runs inside the upsert's transaction: the released rows and the
     * config that released them commit together.
     *
     * <p>Interval-only changes deliberately don't touch an open batch:
     * its deadline was anchored when the batch opened, and the new
     * interval applies from the next window.
     */
    private void releaseOpenBatchWhenImmediate(UUID channelUuid, Map<String, Object> mergedParameters) {
        EmailDigestPolicy newPolicy = EmailDigestPolicy.fromParameters(mergedParameters);
        if (newPolicy.mode() != EmailDigestPolicy.EmailDigestMode.IMMEDIATE) return;
        int released = deliveryRepo.releaseBatchedForChannel(channelUuid, ZonedDateTime.now());
        if (released > 0) {
            log.info("Released {} parked digest rows to PENDING for channel {} (digestMode now IMMEDIATE)",
                    released, channelUuid);
        }
    }

    /**
     * Flip channel enabled/disabled state. The fan-out and dispatch
     * workers already short-circuit on disabled channels, so this is the
     * kill-switch / re-enable surface. No-op when the requested state
     * equals the current state (skip a noisy revision bump).
     */
    @Transactional
    public Integration setChannelStatus(UUID uuid, boolean enabled,
            WhoUpdated wu) throws RelizaException {
        if (uuid == null) throw new RelizaException("uuid is required");
        Optional<Integration> oChannel = integrationRepo.findById(uuid);
        if (oChannel.isEmpty()) throw new RelizaException("Channel not found: " + uuid);
        Integration channel = oChannel.get();
        IntegrationData data = parseRecordData(channel);
        if (data == null) throw new RelizaException("Channel " + uuid + " has unparseable record_data");
        // Treat null isEnabled as enabled for the no-op comparison.
        boolean currentEnabled = !Boolean.FALSE.equals(data.getIsEnabled());
        if (currentEnabled == enabled) {
            return channel;
        }
        auditService.createAndSaveAuditRecord(TableName.INTEGRATIONS, channel);
        data.setIsEnabled(enabled);
        Map<String, Object> recordData = Utils.dataToRecord(data);
        channel.setRecordData(recordData);
        channel.setRevision(channel.getRevision() + 1);
        channel.setLastUpdatedDate(ZonedDateTime.now());
        channel = (Integration) WhoUpdated.injectWhoUpdatedData(channel, wu);
        return integrationRepo.save(channel);
    }

    /**
     * Hard delete. Audit emitted before deletion so the row's last
     * state is preserved for forensic value.
     */
    @Transactional
    public void deleteChannel(UUID uuid) throws RelizaException {
        if (uuid == null) throw new RelizaException("uuid is required");
        Optional<Integration> oChannel = integrationRepo.findById(uuid);
        if (oChannel.isEmpty()) return;
        auditService.createAndSaveAuditRecord(TableName.INTEGRATIONS, oChannel.get());
        integrationRepo.deleteById(uuid);
        log.info("Deleted notification channel {}", uuid);
    }

    private static void validateSeed(IntegrationData seed,
            SlackConfigInput slackConfig, WebhookConfigInput webhookConfig,
            EmailConfigInput emailConfig, TeamsConfigInput teamsConfig,
            SentinelConfigInput sentinelConfig) throws RelizaException {
        if (seed.getOrg() == null) throw new RelizaException("org is required");
        if (StringUtils.isBlank(seed.getName())) throw new RelizaException("name is required");
        if (seed.getType() == null) throw new RelizaException("type is required");
        // CE/Pro split: EMAIL and SENTINEL destinations are Pro-only.
        // Slack / Teams / Webhook are available on the CE (OSS) edition.
        if (LicensingConstants.isOssEdition()
                && (seed.getType() == IntegrationType.EMAIL || seed.getType() == IntegrationType.SENTINEL)) {
            throw new RelizaException(seed.getType()
                    + " notification channels require a ReARM Pro license");
        }
        // Reject the non-matching per-type config explicitly. Silently
        // dropping a populated webhookConfig for a SLACK channel (or
        // vice versa) would be a footgun.
        switch (seed.getType()) {
            case SLACK -> {
                rejectMismatchedConfig("SLACK", "slackConfig",
                        webhookConfig, emailConfig, teamsConfig, sentinelConfig);
                validateSlackConfig(slackConfig);
            }
            case WEBHOOK -> {
                rejectMismatchedConfig("WEBHOOK", "webhookConfig",
                        slackConfig, emailConfig, teamsConfig, sentinelConfig);
                validateWebhookConfig(webhookConfig);
            }
            case EMAIL -> {
                rejectMismatchedConfig("EMAIL", "emailConfig",
                        slackConfig, webhookConfig, teamsConfig, sentinelConfig);
                validateEmailConfig(emailConfig);
            }
            case MSTEAMS -> {
                rejectMismatchedConfig("MS_TEAMS", "teamsConfig",
                        slackConfig, webhookConfig, emailConfig, sentinelConfig);
                validateTeamsConfig(teamsConfig);
            }
            case SENTINEL -> {
                rejectMismatchedConfig("SENTINEL", "sentinelConfig",
                        slackConfig, webhookConfig, emailConfig, teamsConfig);
                validateSentinelConfig(sentinelConfig);
            }
            default -> throw new RelizaException(
                    "type " + seed.getType() + " is not a notification-channel destination type");
        }
    }

    private static void validateSlackConfig(SlackConfigInput config) throws RelizaException {
        if (config == null) return; // null = preserve existing on update
        if (StringUtils.isNotBlank(config.webhookUrl())
                && !SlackWebhookUrlValidator.isValid(config.webhookUrl())) {
            throw new RelizaException("Slack webhook URL must start with "
                    + SlackWebhookUrlValidator.SLACK_WEBHOOK_HOST_PREFIX);
        }
    }

    private static void validateTeamsConfig(TeamsConfigInput config) throws RelizaException {
        if (config == null) return; // null = preserve existing on update
        if (StringUtils.isNotBlank(config.webhookUrl())
                && !TeamsWebhookUrlValidator.isValid(config.webhookUrl())) {
            throw new RelizaException("MS Teams webhook URL must be HTTPS and end at a Power Automate"
                    + " Workflows host (accepted suffixes: "
                    + String.join(", ", TeamsWebhookUrlValidator.ACCEPTED_HOST_SUFFIXES) + ")");
        }
    }

    /**
     * Reject the case where the per-type input doesn't match the
     * channel type. We name the offending fields explicitly so the
     * operator sees "type=SLACK does not accept webhookConfig" and not
     * a generic "wrong inputs" message.
     */
    private static void rejectMismatchedConfig(String type, String acceptedField,
            Object... offendersInOrder) throws RelizaException {
        String[] allNames = {"slackConfig", "webhookConfig", "emailConfig",
                "teamsConfig", "sentinelConfig"};
        java.util.List<String> offendingNames = new java.util.ArrayList<>(4);
        int oi = 0;
        for (String name : allNames) {
            if (name.equals(acceptedField)) continue;
            if (offendersInOrder[oi] != null) offendingNames.add(name);
            oi++;
        }
        if (!offendingNames.isEmpty()) {
            throw new RelizaException(
                    "type=" + type + " does not accept "
                            + String.join("/", offendingNames)
                            + "; use " + acceptedField + " instead");
        }
    }

    /**
     * Validate Sentinel config: all six fields required on create. On
     * update, a fully-null config means "preserve existing." We don't
     * support partial updates of the sentinel secret blob — either
     * supply all six fields or none.
     */
    private static void validateSentinelConfig(SentinelConfigInput config) throws RelizaException {
        if (config == null) return; // null = preserve existing on update
        boolean anyPopulated = StringUtils.isNotBlank(config.tenantId())
                || StringUtils.isNotBlank(config.clientId())
                || StringUtils.isNotBlank(config.clientSecret())
                || StringUtils.isNotBlank(config.dcrEndpoint())
                || StringUtils.isNotBlank(config.dcrImmutableId())
                || StringUtils.isNotBlank(config.streamName());
        if (!anyPopulated) return; // all blank = preserve existing
        if (StringUtils.isBlank(config.tenantId())) {
            throw new RelizaException("Sentinel tenantId is required");
        }
        if (StringUtils.isBlank(config.clientId())) {
            throw new RelizaException("Sentinel clientId is required");
        }
        if (StringUtils.isBlank(config.clientSecret())) {
            throw new RelizaException("Sentinel clientSecret is required");
        }
        if (StringUtils.isBlank(config.dcrEndpoint())) {
            throw new RelizaException("Sentinel dcrEndpoint is required");
        }
        if (!SentinelEndpointValidator.isValid(config.dcrEndpoint())) {
            throw new RelizaException("Sentinel DCR endpoint must be HTTPS at an "
                    + SentinelEndpointValidator.AZURE_HOST_SUFFIX + " host");
        }
        if (StringUtils.isBlank(config.dcrImmutableId())) {
            throw new RelizaException("Sentinel dcrImmutableId is required");
        }
        if (StringUtils.isBlank(config.streamName())) {
            throw new RelizaException("Sentinel streamName is required");
        }
    }

    private static void validateWebhookConfig(WebhookConfigInput config) throws RelizaException {
        if (config == null) return; // null = preserve existing on update
        if (StringUtils.isNotBlank(config.url())) {
            if (!WebhookUrlValidator.isHttps(config.url())) {
                throw new RelizaException("Webhook URL must be HTTPS (got: "
                        + StringUtils.truncate(config.url(), 80) + ")");
            }
        }
        if (config.authScheme() != null && config.authScheme() != WebhookAuthScheme.NONE
                && StringUtils.isBlank(config.authToken())) {
            throw new RelizaException("Webhook auth scheme " + config.authScheme()
                    + " requires a non-blank authToken");
        }
        if (StringUtils.isNotBlank(config.authToken()) && containsControlChars(config.authToken())) {
            throw new RelizaException(
                    "Webhook authToken contains control characters (CR/LF/etc.) — reject");
        }
    }

    private static boolean containsControlChars(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c < 0x20 && c != '\t') || c == 0x7F) return true;
        }
        return false;
    }

    /**
     * Validate email-channel config: recipient list non-empty on create,
     * every address syntactically valid; digest interval (when supplied)
     * a parseable ISO-8601 duration within bounds. Like Slack/Webhook
     * validate methods, null = "preserve existing" on update. A config
     * carrying only digest fields (null recipients) is a digest-only
     * update and skips recipient validation; an entirely-empty config
     * is still rejected so a stray {@code emailConfig: {}} doesn't pass
     * silently.
     */
    private static void validateEmailConfig(EmailConfigInput config) throws RelizaException {
        if (config == null) return;
        boolean digestOnly = config.recipients() == null
                && (config.digestMode() != null || StringUtils.isNotBlank(config.digestInterval()));
        if (!digestOnly) {
            if (config.recipients() == null || config.recipients().isEmpty()) {
                throw new RelizaException(
                        "Email channel requires at least one recipient address");
            }
            EmailValidator ev = EmailValidator.getInstance();
            for (String r : config.recipients()) {
                if (StringUtils.isBlank(r)) {
                    throw new RelizaException("Recipient list contains a blank entry");
                }
                if (!ev.isValid(r.trim())) {
                    throw new RelizaException(
                            "Recipient address is not a valid email: " + r);
                }
            }
        }
        validateDigestInterval(config.digestInterval());
    }

    /**
     * Strict write-side counterpart of the tolerant
     * {@link EmailDigestPolicy#fromParameters} read path: a customer
     * typo in the interval should be rejected at save, not silently
     * coerced to the 24h default at fan-out.
     */
    private static void validateDigestInterval(String digestInterval) throws RelizaException {
        if (StringUtils.isBlank(digestInterval)) return;
        Duration parsed;
        try {
            parsed = Duration.parse(digestInterval.trim());
        } catch (DateTimeParseException e) {
            throw new RelizaException("digestInterval must be an ISO-8601 duration"
                    + " (e.g. PT24H), got: " + StringUtils.truncate(digestInterval, 40));
        }
        if (parsed.compareTo(EmailDigestPolicy.MIN_INTERVAL) < 0
                || parsed.compareTo(EmailDigestPolicy.MAX_INTERVAL) > 0) {
            throw new RelizaException("digestInterval must be between "
                    + EmailDigestPolicy.MIN_INTERVAL + " and "
                    + EmailDigestPolicy.MAX_INTERVAL + ", got: " + parsed);
        }
    }

    /**
     * Merge the incoming {@code emailConfig} fields into the channel's
     * {@code parameters} map. Each field is independent: null
     * recipients / null digest fields preserve their existing values
     * (update-rename idiom). For non-EMAIL channel types, returns
     * seed.getParameters() unchanged.
     */
    private Map<String, Object> mergeConfigData(IntegrationData existingData,
            IntegrationData seed, EmailConfigInput emailConfig) {
        Map<String, Object> incoming = seed.getParameters() != null
                ? new HashMap<>(seed.getParameters()) : new HashMap<>();
        if (seed.getType() != IntegrationType.EMAIL) {
            return incoming;
        }
        if (emailConfig != null && emailConfig.recipients() != null) {
            List<String> trimmed = new ArrayList<>();
            for (String r : emailConfig.recipients()) {
                if (StringUtils.isNotBlank(r)) trimmed.add(r.trim());
            }
            incoming.put(EmailDigestPolicy.RECIPIENTS_KEY, trimmed);
        } else {
            preserveExistingParameter(incoming, existingData, EmailDigestPolicy.RECIPIENTS_KEY);
        }
        if (emailConfig != null && emailConfig.digestMode() != null) {
            incoming.put(EmailDigestPolicy.DIGEST_MODE_KEY, emailConfig.digestMode().name());
        } else {
            preserveExistingParameter(incoming, existingData, EmailDigestPolicy.DIGEST_MODE_KEY);
        }
        if (emailConfig != null && StringUtils.isNotBlank(emailConfig.digestInterval())) {
            incoming.put(EmailDigestPolicy.DIGEST_INTERVAL_KEY, emailConfig.digestInterval().trim());
        } else {
            preserveExistingParameter(incoming, existingData, EmailDigestPolicy.DIGEST_INTERVAL_KEY);
        }
        return incoming;
    }

    private static void preserveExistingParameter(Map<String, Object> incoming,
            IntegrationData existingData, String key) {
        if (existingData == null || existingData.getParameters() == null
                || incoming.containsKey(key)) {
            return;
        }
        Object existing = existingData.getParameters().get(key);
        if (existing != null) incoming.put(key, existing);
    }

    /**
     * Build the encrypted-secret blob for the channel. The shape is
     * per-channel-type — Slack stores just the webhook URL; WEBHOOK
     * stores a JSON-serialized {@link WebhookChannelSecret}.
     *
     * <p>Preserves the existing value when the per-type config is
     * null/blank on update (the "rename without re-typing" idiom).
     */
    private String resolveEncryptedSecret(IntegrationData existingData,
            IntegrationType type,
            SlackConfigInput slackConfig,
            WebhookConfigInput webhookConfig,
            TeamsConfigInput teamsConfig,
            SentinelConfigInput sentinelConfig) throws RelizaException {
        String existingSecret = existingData != null ? existingData.getSecret() : null;
        return switch (type) {
            case SLACK -> resolveSlackSecret(slackConfig, existingSecret);
            case WEBHOOK -> resolveWebhookSecret(webhookConfig, existingSecret);
            case MSTEAMS -> resolveTeamsSecret(teamsConfig, existingSecret);
            case SENTINEL -> resolveSentinelSecret(sentinelConfig, existingSecret);
            // EMAIL carries its config in parameters (recipients), not secret.
            case EMAIL -> existingSecret;
            default -> throw new RelizaException(
                    "type " + type + " is not a notification-channel destination type");
        };
    }

    private String resolveSlackSecret(SlackConfigInput config, String existingSecret) {
        if (config != null && StringUtils.isNotBlank(config.webhookUrl())) {
            return encryptionService.encrypt(config.webhookUrl());
        }
        return existingSecret;
    }

    private String resolveTeamsSecret(TeamsConfigInput config, String existingSecret) {
        if (config != null && StringUtils.isNotBlank(config.webhookUrl())) {
            return encryptionService.encrypt(config.webhookUrl());
        }
        return existingSecret;
    }

    private String resolveSentinelSecret(SentinelConfigInput config, String existingSecret)
            throws RelizaException {
        if (config == null || StringUtils.isBlank(config.tenantId())) {
            return existingSecret;
        }
        SentinelChannelSecret secret = new SentinelChannelSecret(
                config.tenantId(),
                config.clientId(),
                config.clientSecret(),
                config.dcrEndpoint(),
                config.dcrImmutableId(),
                config.streamName());
        try {
            String json = Utils.OM.writeValueAsString(secret);
            return encryptionService.encrypt(json);
        } catch (RuntimeException e) {
            throw new RelizaException("Failed to serialize Sentinel secret: " + e.getMessage());
        }
    }

    private String resolveWebhookSecret(WebhookConfigInput config, String existingSecret)
            throws RelizaException {
        if (config == null || StringUtils.isBlank(config.url())) {
            return existingSecret;
        }
        WebhookAuthScheme scheme = config.authScheme() != null
                ? config.authScheme() : WebhookAuthScheme.NONE;
        WebhookChannelSecret secret = new WebhookChannelSecret(
                config.url(), scheme, config.authToken());
        try {
            String json = Utils.OM.writeValueAsString(secret);
            return encryptionService.encrypt(json);
        } catch (RuntimeException e) {
            throw new RelizaException("Failed to serialize webhook secret: " + e.getMessage());
        }
    }

    private IntegrationData parseRecordData(Integration channel) {
        if (channel == null || channel.getRecordData() == null) return null;
        try {
            return IntegrationData.dataFromRecord(channel);
        } catch (RuntimeException e) {
            log.warn("Failed to parse channel {} record_data: {}", channel.getUuid(), e.getMessage());
            return null;
        }
    }

    /**
     * JSONB size cap. Triggered at save so a bug in the input layer
     * (or a hostile customer) can't write a multi-MB row that then
     * amplifies cost across every fan-out batch.
     */
    static void assertRecordDataSize(Map<String, Object> recordData, String entityLabel) throws RelizaException {
        try {
            String serialized = Utils.OM.writeValueAsString(recordData);
            if (serialized.length() > MAX_RECORD_DATA_BYTES) {
                throw new RelizaException(entityLabel + " record_data exceeds "
                        + MAX_RECORD_DATA_BYTES + " bytes (was " + serialized.length() + ")");
            }
        } catch (RelizaException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new RelizaException(entityLabel + " record_data failed to serialize: " + e.getMessage());
        }
    }

    /**
     * Optimistic-locking gate shared by all three notification upsert
     * paths (channel / subscription / channel-group). The UI captures
     * the row's {@code revision} on Edit-load and round-trips it as
     * {@code expectedRevision} on save; this method rejects the save
     * when the current DB row's revision differs (a concurrent admin
     * edit landed between load and save) instead of letting the second
     * writer silently overwrite the first.
     *
     * <p>Null {@code expectedRevision} opts out — preserves backward
     * compatibility for create calls and any caller that doesn't want
     * the check.
     */
    static void assertExpectedRevision(int currentRevision, Integer expectedRevision,
            String entityLabel, String entityName) throws RelizaException {
        if (expectedRevision == null) return;
        if (currentRevision != expectedRevision) {
            throw new RelizaException("Conflict: " + entityLabel + " \"" + entityName
                    + "\" was edited by another user — please reload and retry"
                    + " (expected revision " + expectedRevision
                    + ", current " + currentRevision + ")");
        }
    }
}
