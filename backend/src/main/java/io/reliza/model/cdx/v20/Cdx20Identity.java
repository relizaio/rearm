/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** One identity claim: a scheme (predefined, such as purl, or custom) and the asserted value. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Cdx20SchemaDef("#/$defs/cyclonedx-component-2.0/$defs/identity")
public class Cdx20Identity {
	/** The predefined identity scheme for Package URLs. */
	public static final String PURL_SCHEME = "purl";

	private Cdx20Scheme scheme;
	private String value;
}
