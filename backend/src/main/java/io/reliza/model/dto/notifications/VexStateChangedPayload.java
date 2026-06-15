/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.reliza.model.NotificationOutboxEvent;

/**
 * Typed accessor for the JSONB payload stored on {@link NotificationOutboxEvent}
 * when its {@code eventType == VEX_STATE_CHANGED}.
 *
 * <p>Fires when a VEX statement transitions between
 * {@code affected / not_affected / under_investigation / fixed} for a
 * (vuln × release) pair (§4.1 of the design doc). Lets customers
 * close-the-loop on an earlier alert ("this CVE is actually
 * not_affected now — quiet down the Slack thread").
 *
 * <p>VEX states are kept as plain strings rather than an enum because
 * the canonical taxonomy lives on the VEX schema (OpenVEX / CycloneDX
 * VEX) and the codebase represents them as strings throughout — a
 * locally-defined enum here would diverge.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VexStateChangedPayload(
        String vulnPrimaryId,
        UUID releaseUuid,
        String componentPurl,
        String oldState,
        String newState) {
}
