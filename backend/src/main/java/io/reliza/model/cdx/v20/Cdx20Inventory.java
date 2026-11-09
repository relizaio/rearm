/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** One inventory: the product it describes ({@code subject}) and the components it holds. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Cdx20SchemaDef("#/$defs/cyclonedx-component-2.0/$defs/inventory")
public class Cdx20Inventory {
	private String refId;
	private Cdx20Component subject;
	private List<Cdx20Component> components;
}
