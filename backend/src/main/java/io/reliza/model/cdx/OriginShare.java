/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/**
 * One country-of-origin share of an {@link Origin}. {@code originCode} is ISO
 * 3166 country/subdivision (e.g. "CA", "US-MA"); {@code percentage} is 0.0-1.0
 * and may be null on a single-origin entry (= 100%). Covers CISA
 * SUPPLIER_SOURCED_PCTG.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OriginShare {
	private String originCode;
	private Double percentage;
}
