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
 * <p>{@code newLifecycle} can be ANY {@link ReleaseLifecycle} value. It was
 * once restricted to the four the legacy path notified on (DRAFT / ASSEMBLED
 * / CANCELLED / REJECTED) and this javadoc promised that as an invariant --
 * consumers must not rely on it. The "verb" rendered by formatters is derived
 * from {@code newLifecycle}, and NotificationLabelProvider covers every
 * constant precisely so the widened set stays renderable.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseLifecycleChangedPayload(
        ReleaseRef release,
        ReleaseLifecycle oldLifecycle,
        ReleaseLifecycle newLifecycle) {
}
