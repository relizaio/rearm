/**
* Copyright Reliza Incorporated. 2019 - 2026. Licensed under the terms of AGPL-3.0-only.
*/

package io.reliza.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A ReARM-native typed identifier. The single identifier shape stored on the
 * {@code identifiers} JSONB list of components, releases, deliverables,
 * shipped products, and devices. {@link RearmIdentifierType} is a superset of
 * the code-generated TEA enum; TEA export filters to the TEA-representable
 * subset and converts to {@link io.reliza.model.tea.TeaIdentifier}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class RearmIdentifier {

	@JsonProperty
	private RearmIdentifierType idType;

	@JsonProperty
	private String idValue;
}
