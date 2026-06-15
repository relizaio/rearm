/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * GraphQL output shape for the Phase 2e {@code notificationChannels}
 * listing query.
 *
 * <p><b>Two layers of credential hygiene:</b>
 * <ol>
 *   <li>{@code encryptedSecret} is never projected. The data fetcher
 *       builds this record from {@code IntegrationData} and
 *       intentionally omits the encrypted blob — set-only via Phase 3
 *       CRUD mutations.</li>
 *   <li>{@code configData} is also omitted from the read surface.
 *       The blob may contain Slack/Teams webhook URLs that the
 *       customer stored unencrypted, and a webhook URL is itself an
 *       auth credential. An org-admin read would let any admin lift
 *       production channel URLs. Phase 3 channel CRUD will split the
 *       blob into typed credential-vs-display fields; v1 stays
 *       conservative and exposes neither.</li>
 * </ol>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationChannelResult(
        UUID uuid,
        UUID org,
        UUID resourceGroup,
        String name,
        String type,
        String status,
        // Hibernate @Version-managed row revision. UI captures this on
        // Edit-load and round-trips it as expectedRevision on save so a
        // concurrent admin edit is rejected with a clear "this record
        // was edited by another user — reload" error rather than
        // silently overwriting their changes.
        Integer revision,
        // Effective email digest policy — EMAIL channels only, null for
        // other types. Read back with defaults applied (absent config =
        // ROLLING / PT24H). Not credentials, so exempt from the
        // configData read-surface embargo above.
        String digestMode,
        String digestInterval,
        // Recipient inbox addresses — EMAIL channels only, null for
        // other types. Typed display field per the credential-vs-display
        // split anticipated above: addresses are inboxes, not secrets,
        // and the edit UI needs them to render the current list. The
        // configData embargo stays in force for everything else.
        List<String> emailRecipients) {
}
