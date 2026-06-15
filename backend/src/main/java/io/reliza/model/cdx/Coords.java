/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** Decimal-degree coordinates (CDX address coords; CISA FGA_LOC_COORDS). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Coords {
	private Double latitude;
	private Double longitude;
}
