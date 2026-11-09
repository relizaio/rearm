/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** The document's {@code definitions}: the shared component definitions others reference by refId. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Cdx20SchemaDef("#/properties/definitions")
public class Cdx20Definitions {
	private List<Cdx20Component> components;
}
