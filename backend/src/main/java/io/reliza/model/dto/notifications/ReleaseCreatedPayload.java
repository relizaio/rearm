/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.NotificationOutboxEvent;

/**
 * Typed accessor for the JSONB payload on {@link NotificationOutboxEvent}
 * when {@code eventType == RELEASE_CREATED}.
 *
 * <p>{@code scheduled} mirrors the legacy split between
 * {@code RELEASE_SCHEDULED} (the release landed PENDING) and
 * {@code NEW_RELEASE} (any other lifecycle) — formatters use it to pick
 * the "scheduled" vs "created" verb.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseCreatedPayload(
        ReleaseRef release,
        boolean scheduled) {
}
