/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** An assumption made in a blueprint; {@code relatedAssets} are asset refIds. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Cdx20SchemaDef("#/$defs/cyclonedx-blueprint-2.0/$defs/assumption")
public class Cdx20Assumption {
	private String refId;
	private String description;
	private Cdx20AssumptionValidity validity;
	private List<String> relatedAssets;
}
