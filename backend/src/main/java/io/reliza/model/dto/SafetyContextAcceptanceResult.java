/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.UUID;

import io.reliza.model.TrustAction;

/** Result of applying the import trust gate to one staged proposal. */
public record SafetyContextAcceptanceResult(
    TrustAction action,
    SafetyContextRecordData proposal,
    UUID acceptedRecordUuid
) {
    /** Public DTO shape intentionally carries typed safety-context data. */
    public record SafetyContextRecordData(
        UUID uuid,
        UUID org,
        UUID releaseUuid,
        UUID sbomComponentUuid,
        String matchStatus,
        String status,
        String statusReason,
        String acceptedVia,
        UUID acceptedRecordUuid
    ) {}
}
