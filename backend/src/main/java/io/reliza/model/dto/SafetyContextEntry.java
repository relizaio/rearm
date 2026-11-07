/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.dto;

import java.util.List;

/**
 * A CycloneDX 2.0 blueprint asset carrying safety-integrity classification,
 * together with the component reference it names. The source JSON is retained
 * because native 2.0 risk, evidence, and review structures are not flattened
 * into a ReARM-specific safety decision.
 */
public record SafetyContextEntry(
    String assetRefId,
    String componentRefId,
    String componentBomRef,
    String componentPurl,
    String componentName,
    String componentVersion,
    List<String> safetyIntegrityLevelsJson,
    String rawAssetJson
) {}
