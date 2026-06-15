/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * GraphQL output shape for the Phase 2d mutations
 * ({@code injectSyntheticEvent}, {@code testNotificationChannel}).
 *
 * <p>The mutations return synchronously once the outbox row is
 * persisted. The actual webhook POST happens asynchronously on the
 * next fan-out tick + channel-worker tick; a follow-up phase will
 * add a query so UIs can poll delivery status by event uuid.
 *
 * <p>Strings (rather than schema enums) for {@code eventType /
 * status / origin} are a temporary v1 choice — the GraphQL schema
 * doesn't yet declare these as enum types so we surface the
 * canonical name. When a future phase adds the read-side notification
 * surface (history view, deliveries query), it can promote those to
 * real schema enums at the same time. Until then the field is
 * declared as {@code String!} so wire-format changes don't require a
 * schema bump.
 *
 * <p>{@code occurredAt} is ISO_OFFSET_DATE_TIME formatted; pick up
 * the standardized scalar when the Phase 2e history query lands.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationOutboxEventResult(
        UUID uuid,
        UUID org,
        String eventType,
        String status,
        String occurredAt,
        String origin,
        String dedupKey,
        UUID channelTestTarget) {
}
