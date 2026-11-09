/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** What a blueprint covers, by name and description. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Cdx20SchemaDef("#/$defs/cyclonedx-blueprint-2.0/$defs/scope")
public class Cdx20BlueprintScope {
	private String refId;
	private String name;
	private String description;
}
