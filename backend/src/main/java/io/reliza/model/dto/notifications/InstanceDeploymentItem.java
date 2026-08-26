/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.dto.UpdateStatus;

/**
 * One changed project/bundle line inside an
 * {@link InstanceDeploymentChangedPayload}. Mirrors the fields of an
 * {@code UpdateProgressDto} the {@code InstanceService.compareInstancesActual}
 * diff produces, flattened for the notification surface.
 *
 * <p>{@code componentType} reuses the existing {@link ComponentType}
 * ({@code PRODUCT} renders as "Bundle", {@code COMPONENT} as "Project" in the
 * customer-facing samples) rather than a new enum. {@code version} is the settled
 * (new) version, populated on a deployed item; {@code fromVersion} is the prior
 * version when this was an update (null on first deploy / undeploy), so formatters
 * can show "from -> to". {@code failureReason} carries a human-readable reason on
 * an ERROR item when one is available (phase-2 deployment-failure ingest); null
 * otherwise.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record InstanceDeploymentItem(
        ComponentType componentType,
        String name,
        String namespace,
        UpdateStatus status,
        String version,
        String fromVersion,
        String failureReason,
        UUID componentUuid,
        UUID releaseUuid) {
}
