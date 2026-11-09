/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/
package io.reliza.model.cdx.v20;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.Data;

/** A component hash: algorithm name and hex content, as written in the document. */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@Cdx20SchemaDef("#/$defs/cyclonedx-common-2.0/$defs/hash")
public class Cdx20Hash {
	private String alg;
	private String content;
}
