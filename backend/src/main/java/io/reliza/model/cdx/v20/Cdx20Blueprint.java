/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** A blueprint: the modelled system's scope, its assets and the assumptions made about them. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Cdx20SchemaDef("#/$defs/cyclonedx-blueprint-2.0/$defs/blueprint")
public class Cdx20Blueprint {
	private String refId;
	private String name;
	private Cdx20BlueprintScope scope;
	private List<Cdx20Asset> assets;
	private List<Cdx20Assumption> assumptions;
}
