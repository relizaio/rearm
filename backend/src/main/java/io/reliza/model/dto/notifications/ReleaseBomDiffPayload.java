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
 * the once-per-release reconcile. At least one of {@code added} /
 * {@code removed} is non-empty; an empty side is an empty list (TPF-147).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseBomDiffPayload(
        ReleaseRef release,
        List<BomComponentChange> added,
        List<BomComponentChange> removed) {
}
