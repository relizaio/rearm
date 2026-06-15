/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.NotificationOutboxEvent;

/**
 * Typed accessor for the JSONB payload on {@link NotificationOutboxEvent}
 * when {@code eventType == RELEASE_BOM_DIFF}.
 *
 * <p>{@code added} / {@code removed} are the component deltas captured by
 * the once-per-release reconcile. The legacy alert only fired when both
 * lists were non-empty; the producer preserves that gate, so a payload
 * that reaches a formatter always has content on both sides.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseBomDiffPayload(
        ReleaseRef release,
        List<BomComponentChange> added,
        List<BomComponentChange> removed) {
}
