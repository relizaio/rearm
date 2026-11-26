/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.LicenseSeatEventKind;

/**
 * The payload of a LICENSE_SEAT_EVENT (TPF-167, TPF-165 D9): a refused sign-in or a skipped SSO group
 * lift, for the organization's administrators.
 *
 * @param kind what happened
 * @param email the person it is about
 * @param groupName the user group a SYNC_SKIPPED lift was for; null otherwise
 * @param message the admin text, rendered as is by every channel
 * @param at when it happened, a UTC instant
 * @param targetUsers who receives it: the organization's admins when it was written; the fan-out writes
 *        targeted inbox rows for them alone
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record LicenseSeatEventPayload(LicenseSeatEventKind kind, String email, String groupName, String message,
		Instant at, List<UUID> targetUsers) {
}
