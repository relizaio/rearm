/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.service;

import java.util.Map;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import io.reliza.model.Integration;
import io.reliza.model.IntegrationData;
import io.reliza.model.IntegrationData.IntegrationType;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.service.EncryptionService;
import lombok.extern.slf4j.Slf4j;

/**
 * Posts a notification event to an MS Teams Power Automate Workflows
 * webhook URL. The webhook URL itself is the auth credential — Teams
 * does not require a signed payload at the receiving end; the URL's
 * secrecy + transport TLS are the entire security boundary.
 *
 * <p>Mirrors {@link SlackChannelDispatcher} structurally — both extend
 * {@link AbstractHttpChannelDispatcher} and only carry the channel-type
 * specific validation + formatter:
 * <ul>
 *   <li>Webhook URL lives encrypted in
 *       {@code NotificationChannelData.encryptedSecret}; decrypted
 *       per-dispatch.</li>
 *   <li>Retry classification delegated to {@link HttpDispatchClassifier}
 *       so the matrix stays in lock-step with Slack/Webhook.</li>
 *   <li>Synchronous {@code .block()} with a 10-second timeout — the
 *       channel worker is already a {@code @Scheduled} job inside the
 *       advisory lock, so a sync HTTP call is appropriate.</li>
 * </ul>
 *
 * <p>Defense-in-depth: re-validates the URL host suffix at dispatch
 * time (see {@link TeamsWebhookUrlValidator}) so a tampered or legacy
 * row never gets a payload over the wire.
 *
 * <p>The URI passthrough that prevents Power Automate SAS URLs from
 * getting their {@code %2F} segments re-encoded (commit
 * {@code bb6f21c5}) lives in {@link AbstractHttpChannelDispatcher#parseUri}
 * so every HTTP dispatcher gets it, not just Teams.
 */
@Service
@Slf4j
public class TeamsChannelDispatcher extends AbstractHttpChannelDispatcher implements ChannelDispatcher {

    private static final IntegrationType CHANNEL_TYPE = IntegrationType.MSTEAMS;

    @Autowired
    private TeamsAdaptiveCardFormatter formatter;

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
                    "Channel " + channel.getUuid() + " has no encrypted webhook URL configured");
        }

        String webhookUrl;
        try {
            webhookUrl = encryptionService.decrypt(data.getSecret());
        } catch (Exception e) {
            return ChannelDispatchResult.nonRetriable(
                    "Channel " + channel.getUuid() + " webhook URL failed to decrypt: " + e.getMessage());
        }
        if (StringUtils.isBlank(webhookUrl)) {
            return ChannelDispatchResult.nonRetriable(
                    "Channel " + channel.getUuid() + " decrypted webhook URL is blank");
        }
        if (!TeamsWebhookUrlValidator.isValid(webhookUrl)) {
            return ChannelDispatchResult.nonRetriable(
                    "Channel " + channel.getUuid() + " webhook URL doesn't match the expected Teams host suffix"
                            + " — refusing to POST vuln-detail payload to a non-Teams endpoint");
        }

        UriParseResult parsed = parseUri(webhookUrl, channel.getUuid());
        if (parsed.isError()) {
            return parsed.error();
        }

        Map<String, Object> payload = formatter.format(event);
        return doPost(parsed.uri(), payload, timeout(), CHANNEL_TYPE, null);
    }
}
