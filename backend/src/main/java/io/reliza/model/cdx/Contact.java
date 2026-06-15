/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** A contact on a {@link Party} (CDX contact). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class Contact {
	private String name;
	private String email;
	private String phone;
}
