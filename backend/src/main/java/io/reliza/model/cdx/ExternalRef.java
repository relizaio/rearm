/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** A CDX externalReference (datasheet, certification, documentation, vcs, ...). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExternalRef {
	private String type;
	private String url;
	private String comment;
}
