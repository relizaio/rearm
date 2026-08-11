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
 * Posts a notification event to a Slack incoming-webhook URL.
 *
 * <p>The channel's webhook URL lives encrypted in
 * {@code NotificationChannelData.encryptedSecret}; we decrypt it
 * per-dispatch (no per-channel cache — channels are typically
 * single-digit per org and the decrypt is fast enough).
 *
 * <p>HTTP scaffolding — WebClient build, URI passthrough parse, POST
 * + exception classification — lives in
 * {@link AbstractHttpChannelDispatcher}. The subclass keeps only the
 * Slack-specific parts: decrypt the URL, validate the host suffix,
 * format the payload via {@link SlackBlockKitFormatter}.
 *
 * <p>Retry classification is delegated to {@link HttpDispatchClassifier}
 * so the matrix stays in lock-step with the other HTTP dispatchers.
 *
 * <p>Synchronous {@code .block()} on the reactive call — the channel
 * worker is already a {@code @Scheduled} job inside the advisory lock
 * (§5.2). A sync wait on a per-delivery HTTP call is appropriate; we
 * cap with a 10-second timeout so a hung Slack endpoint can't stall the
 * batch.
 */
@Service
@Slf4j
public class SlackChannelDispatcher extends AbstractHttpChannelDispatcher implements ChannelDispatcher {

    private static final IntegrationType CHANNEL_TYPE = IntegrationType.SLACK;

    @Autowired
    private SlackBlockKitFormatter formatter;

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
        // Normalize the decrypted secret: strips a pasted trailing newline /
        // surrounding spaces AND expands a legacy base-integration path
        // fragment (e.g. "T0/B0/xxx", from the old prefix-prepending sender)
        // into a full hooks.slack.com URL, so migrated base Slack channels
        // keep delivering. A full URL passes through unchanged. The value we
        // validate AND post below is this normalized string.
        webhookUrl = SlackWebhookUrlValidator.normalize(webhookUrl);
        if (!SlackWebhookUrlValidator.isValid(webhookUrl)) {
            // The channel's URL isn't a Slack host -- it will never deliver.
            // Auto-disable it (channelMisconfigured) rather than emitting a
            // FAILED row for every matching event forever.
            return ChannelDispatchResult.channelMisconfigured(
                    "Webhook URL is not a Slack host (" + SlackWebhookUrlValidator.SLACK_WEBHOOK_HOST
                            + "); re-enter a Slack incoming-webhook or Workflow URL, then re-enable.");
        }

        UriParseResult parsed = parseUri(webhookUrl, channel.getUuid());
        if (parsed.isError()) {
            return parsed.error();
        }

        Map<String, Object> payload = formatter.format(event);
        return doPost(parsed.uri(), payload, timeout(), CHANNEL_TYPE, null);
    }
}
