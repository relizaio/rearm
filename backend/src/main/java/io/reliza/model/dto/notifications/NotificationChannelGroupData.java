/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.reliza.model.NotificationChannelGroup;

/**
 * Typed accessor for the JSONB payload of a {@link NotificationChannelGroup}.
 *
 * <p>Groups are pure naming + composition — no per-type config, no
 * encryption, no dispatch logic. {@code channels} is the ordered list
 * of member channel-integration UUIDs; duplicates within a
 * single group are rejected at save time by
 * {@code NotificationChannelGroupService.validateSeed} and the fan-out
 * dedup pass also collapses repeats across group + route.channels.
 *
 * <p>Cross-org membership is rejected at save time — every channel in
 * {@code channels} must belong to {@code org}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NotificationChannelGroupData(
        UUID org,
        UUID resourceGroup,
        String name,
        List<UUID> channels) {
}
