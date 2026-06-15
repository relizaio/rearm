/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import io.reliza.model.ComponentData.ComponentType;
import io.reliza.model.ReleaseData.ReleaseLifecycle;

/**
 * Shared release-identity block embedded in every release-scoped
 * notification payload ({@link ReleaseCreatedPayload},
 * {@link ReleaseLifecycleChangedPayload}, {@link ReleaseBomDiffPayload}).
 *
 * <p>Unlike the vuln events — whose affected-release set is enriched at
 * fan-out time because the connecting artifact metrics aren't written
 * until later in the DT-sync loop — every field here is known at
 * produce-time (the release-save context already holds the component,
 * branch, SCE and user). The producer therefore builds this in full and
 * no fan-out enrichment is needed.
 *
 * <p>Channel formatters synthesize deep links from {@code releaseUuid}
 * (and {@code commitUri} when present, since they have no VCS context of
 * their own).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ReleaseRef(
        UUID releaseUuid,
        String version,
        UUID componentUuid,
        String componentName,
        ComponentType componentType,
        UUID branchUuid,
        String branchName,
        ReleaseLifecycle lifecycle,
        String commitHash,
        String commitUri,
        String commitMessage,
        String updatedByName,
        String updatedByEmail) {
}
