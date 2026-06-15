/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One requested approval entry inside an {@link ApprovalRequestedPayload}.
 * Carries the entry's display name as of request time so formatters never
 * need an extra lookup (and a later entry rename doesn't rewrite history).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ApprovalRequestEntryRef(
        UUID approvalEntryUuid,
        String approvalEntryName) {
}
