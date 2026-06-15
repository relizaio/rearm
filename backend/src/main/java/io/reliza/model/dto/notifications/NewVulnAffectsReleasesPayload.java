/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.reliza.model.NotificationOutboxEvent;
import io.reliza.model.NotificationSeverity;

/**
 * Typed accessor for the JSONB payload stored on {@link NotificationOutboxEvent}
 * when its {@code eventType == NEW_VULN_AFFECTS_RELEASES}.
 *
 * <p>Producers (Phase 2) build this record from a {@code VulnerabilityRecord}
 * upsert, Jackson-serialize it into the outbox row's {@code record_data}
 * JSONB column. Consumers (the outbox worker, the CEL activation
 * builder, channel formatters) deserialize back to this record.
 *
 * <p>Field set mirrors §4.5 of the design doc. The exact CEL surface that
 * customer filters see is documented on
 * {@code EventActivationMapBuilder.buildForEvent(NotificationOutboxEvent)}.
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} keeps deserialization
 * tolerant of older payloads after a forward-compatible field addition —
 * see "Event-schema versioning discipline" risk in §13.2.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record NewVulnAffectsReleasesPayload(
        String vulnPrimaryId,
        List<String> aliases,
        Double cvssScore,
        String cvssVector,
        Double epssScore,
        Boolean kevListed,
        String fixVersion,
        NotificationSeverity severity,
        AffectedComponent affectedComponent,
        List<AffectedRelease> affectedReleases) {
}
