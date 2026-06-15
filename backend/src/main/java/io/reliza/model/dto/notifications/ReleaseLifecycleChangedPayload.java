/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.ReleaseData.ReleaseLifecycle;
import io.reliza.model.NotificationOutboxEvent;

/**
 * Typed accessor for the JSONB payload on {@link NotificationOutboxEvent}
 * when {@code eventType == RELEASE_LIFECYCLE_CHANGED}.
 *
 * <p>The producer only emits this for the four transitions the legacy
 * path notified on (DRAFT / ASSEMBLED / CANCELLED / REJECTED), so
 * {@code newLifecycle} is always one of those; the "verb" rendered by
 * formatters is derived from {@code newLifecycle}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseLifecycleChangedPayload(
        ReleaseRef release,
        ReleaseLifecycle oldLifecycle,
        ReleaseLifecycle newLifecycle) {
}
