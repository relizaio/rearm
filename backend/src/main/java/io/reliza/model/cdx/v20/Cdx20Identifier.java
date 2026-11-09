/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** Identities of a component as asserted by one party ({@code party} is the party's refId). */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Cdx20SchemaDef("#/$defs/cyclonedx-component-2.0/$defs/identifier")
public class Cdx20Identifier {
	private String refId;
	private String party;
	private List<Cdx20Identity> identities;
}
