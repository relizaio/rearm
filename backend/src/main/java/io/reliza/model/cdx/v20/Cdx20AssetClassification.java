/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** The part of an asset's classification ReARM reads: its safety integrity levels. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Cdx20SchemaDef("#/$defs/cyclonedx-blueprint-2.0/$defs/assetClassification")
public class Cdx20AssetClassification {
	private List<Cdx20SafetyIntegrityLevel> safetyIntegrityLevels;
}
