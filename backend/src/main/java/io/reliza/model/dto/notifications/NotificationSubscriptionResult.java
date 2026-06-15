/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * GraphQL output shape for the Phase 2e
 * {@code notificationSubscriptions} listing query. Lifts the
 * subscription's UUID + revision to top-level fields and projects
 * the nested {@code filter} / {@code routes} / {@code rateLimit}
 * blobs as JSON strings so the UI can deserialize them client-side
 * without expanding the schema with five inner types.
 *
 * <p>Why string-JSON for the nested config: Phase 3 will add typed
 * Input types for subscription CRUD, at which point the read surface
 * can mirror them. For the read-only v1 surface, keeping the schema
 * compact is the right tradeoff — the UI already knows the shape
 * since it authored it.
 *
 * <p>{@code eventTypes} is exposed as a {@code List<String>} of enum
 * names rather than as a GraphQL enum list, again for forward-
 * compatibility with new event types — adding one is a Java enum
 * addition that wouldn't require a schema-side change.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationSubscriptionResult(
        UUID uuid,
        UUID org,
        UUID resourceGroup,
        String name,
        String status,
        List<String> eventTypes,
        String filter,
        String routes,
        Integer dedupWindowMinutes,
        String rateLimit,
        // Hibernate @Version-managed row revision. See
        // NotificationChannelResult.revision javadoc.
        Integer revision) {
}
