/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.reliza.model.NotificationDelivery;

/**
 * GraphQL output shape for {@link NotificationDelivery} read queries
 * added in Phase 2e. Surfaces the queue-relevant columns so an operator
 * UI can render "what was attempted, when, what was the outcome."
 *
 * <p>Timestamps are emitted as ISO_OFFSET_DATE_TIME strings rather than
 * a typed scalar — when the rest of the rearm schema standardizes on
 * a DateTime scalar these can be promoted at the same time. Enum
 * projections ({@code status}, {@code origin}) are also strings for
 * now; same forward-compatible reasoning as
 * {@link NotificationOutboxEventResult}.
 *
 * <p>{@code subscriptionUuid} is nullable to surface the Phase 2d
 * channel-test path — those rows have no originating subscription.
 *
 * <p>{@code recordData} is intentionally not projected: it currently
 * carries nothing operator-facing (channel workers will populate
 * digest / headers in a later phase), and Map-of-Object surface in
 * GraphQL is awkward. Add a typed field once it has a stable shape.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationDeliveryResult(
        UUID uuid,
        UUID org,
        UUID outboxEventUuid,
        UUID subscriptionUuid,
        UUID channelUuid,
        String status,
        String origin,
        String dedupKey,
        Integer attemptCount,
        String nextAttemptAt,
        String sentAt,
        String lastError,
        String createdDate) {
}
