/**
* Copyright Reliza Incorporated. 2019 - 2026. All rights reserved.
*/
package io.reliza.model.dto.notifications;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * One added/removed component line in a {@link ReleaseBomDiffPayload}.
 * A self-contained copy of {@code AcollectionData.DiffComponent}'s purl +
 * version so the JSONB payload doesn't couple to the core acollection
 * model's serialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record BomComponentChange(
        String purl,
        String version) {
}
