/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** A blueprint asset; {@code componentRef} is the refId (or BOM-Link) of the component it represents. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Cdx20SchemaDef("#/$defs/cyclonedx-blueprint-2.0/$defs/asset")
public class Cdx20Asset {
	private String refId;
	private String name;
	private String componentRef;
	private Cdx20AssetClassification classification;
}
