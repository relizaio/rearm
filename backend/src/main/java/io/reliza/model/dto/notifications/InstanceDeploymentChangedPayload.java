/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationSeverity;

/**
 * Typed accessor for the JSONB payload on {@link NotificationOutboxEvent} when
 * {@code eventType == INSTANCE_DEPLOYMENT_CHANGED} or
 * {@code INSTANCE_DEPLOYMENT_FAILED}. One payload type serves both events: the
 * FAILED event carries the ERROR items, the CHANGED event carries the rest (see
 * ai-plans/instance-event-notifications.md sec 4).
 *
 * <p>The producer emits one of these per deploy after the coalesce flush,
 * carrying the net diff from {@code fromRevision} (the pre-burst revisionActual)
 * to {@code toRevision} (the settled revisionActual).
 *
 * <p>{@code statuses} is the distinct set of item statuses as
 * {@code UpdateStatus.name()} strings -- a {@code List<String>}, not a typed set,
 * so customer CEL filters can use {@code "CONVERGED" in event.statuses} (CEL's
 * {@code in} does not match raw enum objects). {@code severity} is the max across
 * items and is what {@code NotificationFanOutService.extractEventSeverity} reads
 * for route severity gating.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstanceDeploymentChangedPayload(
        InstanceRef instance,
        int fromRevision,
        int toRevision,
        List<InstanceDeploymentItem> items,
        List<String> statuses,
        NotificationSeverity severity) {
}
