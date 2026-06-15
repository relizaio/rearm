/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** Postal address (CDX address). {@code isoCode} is ISO 3166 country/subdivision, e.g. "US-MA". */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Address {
	private String streetAddress;
	private String city;
	private String region;
	private String postalCode;
	private String country;
	private String isoCode;
	private Coords coords;
}
